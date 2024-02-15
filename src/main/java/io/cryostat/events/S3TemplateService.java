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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.common.unit.SimpleConstrainedMap;
import org.openjdk.jmc.common.unit.UnitLookup;
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
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

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
            String templateName, TemplateType unused) throws FlightRecorderException {
        return getObject(templateName)
                .map(this::getContents)
                .map(
                        stream -> {
                            try {
                                return new EventConfiguration(parseXml(stream))
                                        .getEventOptions(
                                                new SimpleConstrainedMap<>(
                                                        UnitLookup.PLAIN_TEXT.getPersister()));
                            } catch (IOException | ParseException e) {
                                logger.error(e);
                                return null;
                            }
                        });
    }

    @Override
    public List<Template> getTemplates() throws FlightRecorderException {
        return convertObjects(getObjects());
    }

    @Override
    public Optional<Document> getXml(String templateName, TemplateType unused)
            throws FlightRecorderException {
        return getObject(templateName)
                .map(this::getContents)
                .map(
                        stream -> {
                            try (stream) {
                                Document doc =
                                        Jsoup.parse(
                                                stream,
                                                StandardCharsets.UTF_8.name(),
                                                "",
                                                Parser.xmlParser());
                                return doc;
                            } catch (IOException e) {
                                logger.error(e);
                                return null;
                            }
                        });
    }

    @Blocking
    private Optional<S3Object> getObject(String name) {
        // FIXME do this by querying for a single S3Object rather than list and filter
        return getObjects().stream().filter(o -> o.key().equals(name)).findFirst();
    }

    @Blocking
    private List<S3Object> getObjects() {
        var builder = ListObjectsV2Request.builder().bucket(eventTemplatesBucket);
        return storage.listObjectsV2(builder.build()).contents();
    }

    private List<Template> convertObjects(List<S3Object> objects) {
        return objects.stream()
                .map(
                        t -> {
                            try {
                                return convertObject(t);
                            } catch (IOException
                                    | ParseException
                                    | InvalidEventTemplateException e) {
                                logger.error(e);
                                return null;
                            }
                        })
                .toList();
    }

    private Template convertObject(S3Object object)
            throws IOException, ParseException, InvalidEventTemplateException {
        var xml = parseXml(getContents(object));

        // XMLAttributeInstance descAttr = null, providerAttr = null;
        // var configuration = xml.getRoot();
        // for (var attr : configuration.getAttributeInstances()) {
        //     if (attr.getAttribute().getName().equals("description")) {
        //         descAttr = attr;
        //         break;
        //     } else if (attr.getAttribute().getName().equals("provider")) {
        //         providerAttr = attr;
        //         break;
        //     }
        // }
        // if (descAttr == null) {
        //     throw new InvalidEventTemplateException("\"description\" attribute not found");
        // }
        // if (providerAttr == null) {
        //     throw new InvalidEventTemplateException("\"provider\" attribute not found");
        // }

        return new Template(
                object.key(),
                "",
                "unknown",
                // descAttr.getExplicitValue(),
                // providerAttr.getExplicitValue(),
                TemplateType.CUSTOM);
    }

    private InputStream getContents(S3Object object) {
        var req = GetObjectRequest.builder().bucket(eventTemplatesBucket).key(object.key()).build();
        return storage.getObject(req);
    }

    private XMLModel parseXml(InputStream inputStream) throws IOException, ParseException {
        try (inputStream) {
            var model = EventConfiguration.createModel(inputStream);
            model.checkErrors();

            for (XMLValidationResult result : model.getResults()) {
                if (result.isError()) {
                    throw new IllegalArgumentException(
                            new InvalidEventTemplateException(result.getText()));
                }
            }
            return model;
        }
    }

    @Blocking
    public Template addTemplate(String templateText)
            throws InvalidXmlException, InvalidEventTemplateException, IOException {
        try {
            XMLModel model =
                    parseXml(
                            new ByteArrayInputStream(
                                    templateText.getBytes(StandardCharsets.UTF_8)));

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

            // TODO put the template description, provider, and other attributes in metadata so we
            // don't need to download and parse the whole XML just to display the templates list
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
