/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat.events;

import java.io.IOException;
import java.net.URI;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.openjdk.jmc.flightrecorder.controlpanel.ui.configuration.model.xml.JFCGrammar;
import org.openjdk.jmc.flightrecorder.controlpanel.ui.configuration.model.xml.XMLAttributeInstance;
import org.openjdk.jmc.flightrecorder.controlpanel.ui.configuration.model.xml.XMLModel;
import org.openjdk.jmc.flightrecorder.controlpanel.ui.configuration.model.xml.XMLTagInstance;
import org.openjdk.jmc.flightrecorder.controlpanel.ui.configuration.model.xml.XMLValidationResult;
import org.openjdk.jmc.flightrecorder.controlpanel.ui.model.EventConfiguration;

import io.cryostat.core.templates.MutableTemplateService.InvalidEventTemplateException;
import io.cryostat.core.templates.MutableTemplateService.InvalidXmlException;
import io.cryostat.core.templates.Template;
import io.cryostat.core.templates.TemplateType;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.util.HttpStatusCodeIdentifier;

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.apache.http.entity.ContentType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Path("")
public class EventTemplates {

    public static final Template ALL_EVENTS_TEMPLATE =
            new Template(
                    "ALL",
                    "Enable all available events in the target JVM, with default option values."
                            + " This will be very expensive and is intended primarily for testing"
                            + " Cryostat's own capabilities.",
                    "Cryostat",
                    TemplateType.TARGET);

    @Inject Vertx vertx;
    @Inject TargetConnectionManager connectionManager;
    @Inject S3Client storage;
    @Inject Logger logger;

    @ConfigProperty(name = "storage.buckets.event-templates.name")
    String eventTemplatesBucket;

    void onStart(@Observes StartupEvent evt) {
        boolean exists = false;
        try {
            exists =
                    HttpStatusCodeIdentifier.isSuccessCode(
                            storage.headBucket(
                                            HeadBucketRequest.builder()
                                                    .bucket(eventTemplatesBucket)
                                                    .build())
                                    .sdkHttpResponse()
                                    .statusCode());
        } catch (Exception e) {
            logger.info(e);
        }
        if (!exists) {
            try {
                storage.createBucket(
                        CreateBucketRequest.builder().bucket(eventTemplatesBucket).build());
            } catch (Exception e) {
                logger.error(e);
            }
        }
    }

    @GET
    @Path("/api/v1/targets/{connectUrl}/templates")
    @RolesAllowed("read")
    public Response listTemplatesV1(@RestPath URI connectUrl) throws Exception {
        Target target = Target.getTargetByConnectUrl(connectUrl);
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(
                        URI.create(String.format("/api/v3/targets/%d/event_templates", target.id)))
                .build();
    }

    @POST
    @Path("/api/v1/templates")
    @RolesAllowed("write")
    public void postTemplatesV1(@RestForm("template") FileUpload body) throws Exception {
        try {
            var path = body.filePath();
            vertx.fileSystem()
                    .readFile(path.toString())
                    .onComplete(
                            ar -> {
                                try {
                                    addTemplate(ar.result().toString());
                                } catch (Exception e) {
                                    logger.error(e);
                                }
                            })
                    .onFailure(
                            ar -> {
                                // TODO respond with error to original client
                                logger.error(ar.getCause());
                            });
        } catch (IllegalArgumentException e) {
            logger.error(e);
            throw new BadRequestException(e);
        }
    }

    @GET
    @Path("/api/v1/targets/{connectUrl}/templates/{templateName}/type/{templateType}")
    @RolesAllowed("read")
    public Response getTargetTemplateV1(
            @RestPath URI connectUrl,
            @RestPath String templateName,
            @RestPath TemplateType templateType)
            throws Exception {
        Target target = Target.getTargetByConnectUrl(connectUrl);
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(
                        URI.create(
                                String.format(
                                        "/api/v3/targets/%d/event_templates/%s/%s",
                                        target.id, templateName, templateType)))
                .build();
    }

    @GET
    @Path("/api/v3/targets/{id}/event_templates")
    @RolesAllowed("read")
    public List<Template> listTemplates(@RestPath long id) throws Exception {
        Target target = Target.find("id", id).singleResult();
        return connectionManager.executeConnectedTask(
                target,
                connection -> {
                    List<Template> list =
                            new ArrayList<>(connection.getTemplateService().getTemplates());
                    list.add(ALL_EVENTS_TEMPLATE);
                    return list;
                });
    }

    @GET
    @Path("/api/v3/targets/{id}/event_templates/{templateName}/{templateType}")
    @RolesAllowed("read")
    public String getTargetTemplate(
            @RestPath long id, @RestPath String templateName, @RestPath TemplateType templateType)
            throws Exception {
        Target target = Target.find("id", id).singleResult();
        return connectionManager.executeConnectedTask(
                target,
                conn ->
                        conn.getTemplateService()
                                .getXml(templateName, templateType)
                                .orElseThrow(NotFoundException::new)
                                .toString());
    }

    public Template addTemplate(String templateText)
            throws InvalidXmlException, InvalidEventTemplateException, IOException {
        try {
            XMLModel model = EventConfiguration.createModel(templateText);
            model.checkErrors();

            for (XMLValidationResult result : model.getResults()) {
                if (result.isError()) {
                    // throw new InvalidEventTemplateException(result.getText());
                    throw new IllegalArgumentException(result.getText());
                }
            }

            XMLTagInstance configuration = model.getRoot();
            XMLAttributeInstance labelAttr = null;
            for (XMLAttributeInstance attr : configuration.getAttributeInstances()) {
                if (attr.getAttribute().getName().equals("label")) {
                    labelAttr = attr;
                    break;
                }
            }

            if (labelAttr == null) {
                // throw new InvalidEventTemplateException(
                //         "Template has no configuration label attribute");
                throw new IllegalArgumentException("Template has no configuration label attribute");
            }

            String templateName = labelAttr.getExplicitValue();
            templateName = templateName.replaceAll("[\\W]+", "_");

            XMLTagInstance root = model.getRoot();
            root.setValue(JFCGrammar.ATTRIBUTE_LABEL_MANDATORY, templateName);

            String key = templateName;
            storage.putObject(
                    PutObjectRequest.builder()
                            .bucket(eventTemplatesBucket)
                            .key(key)
                            .contentType(ContentType.APPLICATION_XML.getMimeType())
                            .build(),
                    RequestBody.fromString(model.toString()));

            return new Template(
                    templateName,
                    getAttributeValue(root, "description"),
                    getAttributeValue(root, "provider"),
                    TemplateType.CUSTOM);
        } catch (IOException ioe) {
            // throw new InvalidXmlException("Unable to parse XML stream", ioe);
            throw new IllegalArgumentException("Unable to parse XML stream", ioe);
        } catch (ParseException | IllegalArgumentException e) {
            // throw new InvalidEventTemplateException("Invalid XML", e);
            throw new IllegalArgumentException("Invalid XML", e);
        }
    }

    protected String getAttributeValue(XMLTagInstance node, String valueKey) {
        return node.getAttributeInstances().stream()
                .filter(i -> Objects.equals(valueKey, i.getAttribute().getName()))
                .map(i -> i.getValue())
                .findFirst()
                .get();
    }
}
