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
import java.text.ParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.flightrecorder.configuration.events.EventOptionID;
import org.openjdk.jmc.flightrecorder.controlpanel.ui.configuration.model.xml.JFCGrammar;
import org.openjdk.jmc.flightrecorder.controlpanel.ui.configuration.model.xml.XMLAttributeInstance;
import org.openjdk.jmc.flightrecorder.controlpanel.ui.configuration.model.xml.XMLModel;
import org.openjdk.jmc.flightrecorder.controlpanel.ui.configuration.model.xml.XMLTagInstance;
import org.openjdk.jmc.flightrecorder.controlpanel.ui.configuration.model.xml.XMLValidationResult;
import org.openjdk.jmc.flightrecorder.controlpanel.ui.model.EventConfiguration;

import io.cryostat.ConfigProperties;
import io.cryostat.core.FlightRecorderException;
import io.cryostat.core.templates.MutableTemplateService.InvalidEventTemplateException;
import io.cryostat.core.templates.MutableTemplateService.InvalidXmlException;
import io.cryostat.core.templates.Template;
import io.cryostat.core.templates.TemplateService;
import io.cryostat.core.templates.TemplateType;
import io.cryostat.util.HttpStatusCodeIdentifier;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.hc.core5.http.ContentType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jsoup.nodes.Document;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@ApplicationScoped
class S3TemplateService implements TemplateService {

    @Inject S3Client storage;
    @Inject Logger logger;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_EVENT_TEMPLATES)
    String eventTemplatesBucket;

    void onStart(@Observes StartupEvent evt) {
        // FIXME refactor this to a reusable utility method since this is done for custom event
        // templates, archived recordings, and archived reports
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

    @Override
    public Optional<IConstrainedMap<EventOptionID>> getEvents(
            String templateName, TemplateType templateType) throws FlightRecorderException {
        return Optional.empty();
    }

    @Override
    public List<Template> getTemplates() throws FlightRecorderException {
        var builder = ListObjectsV2Request.builder().bucket(eventTemplatesBucket);
        var objects = storage.listObjectsV2(builder.build());
        var templates = convertObjects(objects);
        return templates;
    }

    private List<Template> convertObjects(ListObjectsV2Response objects) {
        return List.of();
    }

    @Override
    public Optional<Document> getXml(String templateName, TemplateType templateType)
            throws FlightRecorderException {
        return Optional.empty();
    }

    @Blocking
    public Template addTemplate(String templateText)
            throws InvalidXmlException, InvalidEventTemplateException, IOException {
        try {
            XMLModel model = EventConfiguration.createModel(templateText);
            model.checkErrors();

            for (XMLValidationResult result : model.getResults()) {
                if (result.isError()) {
                    throw new IllegalArgumentException(
                            new InvalidEventTemplateException(result.getText()));
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
                throw new IllegalArgumentException(
                        new InvalidEventTemplateException(
                                "Template has no configuration label attribute"));
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
            // FIXME InvalidXmlException constructor should be made public in -core
            // throw new InvalidXmlException("Unable to parse XML stream", ioe);
            throw new IllegalArgumentException("Unable to parse XML stream", ioe);
        } catch (ParseException | IllegalArgumentException e) {
            throw new IllegalArgumentException(new InvalidEventTemplateException("Invalid XML", e));
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
