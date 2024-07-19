/*
 * Copyright The Cryostat Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cryostat.events;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import io.cryostat.core.templates.MutableTemplateService.InvalidXmlException;
import io.cryostat.libcryostat.sys.FileSystem;
import io.cryostat.libcryostat.templates.InvalidEventTemplateException;
import io.cryostat.libcryostat.templates.Template;
import io.cryostat.libcryostat.templates.TemplateType;
import io.cryostat.targets.Target;
import io.cryostat.util.HttpMimeType;

import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.multipart.FileUpload;

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

    @Inject FileSystem fs;
    @Inject TargetTemplateService.Factory targetTemplateServiceFactory;
    @Inject S3TemplateService customTemplateService;
    @Inject Logger logger;

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
    public Response postTemplatesV1(@RestForm("template") FileUpload body) {
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(URI.create("/api/v3/event_templates"))
                .build();
    }

    @POST
    @Path("/api/v3/event_templates")
    @RolesAllowed("write")
    public void postTemplates(@RestForm("template") FileUpload body) throws IOException {
        if (body == null || body.filePath() == null || !"template".equals(body.name())) {
            throw new BadRequestException();
        }
        try (var stream = fs.newInputStream(body.filePath())) {
            customTemplateService.addTemplate(stream);
        } catch (InvalidEventTemplateException | InvalidXmlException e) {
            throw new BadRequestException(e);
        }
    }

    @DELETE
    @Path("/api/v1/templates/{templateName}")
    @RolesAllowed("write")
    public Response deleteTemplatesV1(@RestPath String templateName) {
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(URI.create(String.format("/api/v3/event_templates/%s", templateName)))
                .build();
    }

    @DELETE
    @Blocking
    @Path("/api/v3/event_templates/{templateName}")
    @RolesAllowed("write")
    public void deleteTemplates(@RestPath String templateName) {
        customTemplateService.deleteTemplate(templateName);
    }

    @GET
    @Path("/api/v1/targets/{connectUrl}/templates/{templateName}/type/{templateType}")
    @RolesAllowed("read")
    public Response getTargetTemplateV1(
            @RestPath URI connectUrl,
            @RestPath String templateName,
            @RestPath TemplateType templateType) {
        Target target = Target.getTargetByConnectUrl(connectUrl);
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(
                        URI.create(
                                String.format(
                                        "/api/v3/targets/%d/event_templates/%s/%s",
                                        target.id, templateType, templateName)))
                .build();
    }

    @GET
    @Path("/api/v2.1/targets/{connectUrl}/templates/{templateName}/type/{templateType}")
    @RolesAllowed("read")
    public Response getTargetTemplateV2_1(
            @RestPath URI connectUrl,
            @RestPath String templateName,
            @RestPath TemplateType templateType) {
        Target target = Target.getTargetByConnectUrl(connectUrl);
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(
                        URI.create(
                                String.format(
                                        "/api/v3/targets/%d/event_templates/%s/%s",
                                        target.id, templateType, templateName)))
                .build();
    }

    @GET
    @Blocking
    @Path("/api/v3/event_templates")
    @RolesAllowed("read")
    public List<Template> listTemplates() throws Exception {
        var list = new ArrayList<Template>();
        list.add(ALL_EVENTS_TEMPLATE);
        list.addAll(customTemplateService.getTemplates());
        return list;
    }

    @GET
    @Blocking
    @Path("/api/v3/targets/{id}/event_templates")
    @RolesAllowed("read")
    public List<Template> listTargetTemplates(@RestPath long id) throws Exception {
        Target target = Target.find("id", id).singleResult();
        var list = new ArrayList<Template>();
        list.add(ALL_EVENTS_TEMPLATE);
        list.addAll(targetTemplateServiceFactory.create(target).getTemplates());
        list.addAll(customTemplateService.getTemplates());
        return list;
    }

    @GET
    @Blocking
    @Path("/api/v3/targets/{id}/event_templates/{templateType}/{templateName}")
    @RolesAllowed("read")
    public Response getTargetTemplate(
            @RestPath long id, @RestPath TemplateType templateType, @RestPath String templateName)
            throws Exception {
        Target target = Target.find("id", id).singleResult();
        String xml;
        switch (templateType) {
            case TARGET:
                xml =
                        targetTemplateServiceFactory
                                .create(target)
                                .getXml(templateName, templateType)
                                .orElseThrow();
                break;
            case CUSTOM:
                xml = customTemplateService.getXml(templateName, templateType).orElseThrow();
                break;
            default:
                throw new BadRequestException();
        }
        return Response.status(RestResponse.Status.OK)
                .header(HttpHeaders.CONTENT_TYPE, HttpMimeType.JFC.mime())
                .entity(xml)
                .build();
    }
}
