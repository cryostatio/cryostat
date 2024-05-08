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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.common.unit.SimpleConstrainedMap;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.configuration.events.EventConfiguration;
import org.openjdk.jmc.flightrecorder.configuration.events.EventOptionID;

import io.cryostat.ConfigProperties;
import io.cryostat.Producers;
import io.cryostat.StorageBuckets;
import io.cryostat.core.FlightRecorderException;
import io.cryostat.core.templates.MutableTemplateService;
import io.cryostat.core.templates.MutableTemplateService.InvalidEventTemplateException;
import io.cryostat.core.templates.MutableTemplateService.InvalidXmlException;
import io.cryostat.core.templates.Template;
import io.cryostat.core.templates.TemplateType;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;

@ApplicationScoped
public class S3TemplateService extends AbstractFileBasedTemplateService
        implements MutableTemplateService {

    static final String EVENT_TEMPLATE_CREATED = "TemplateUploaded";
    static final String EVENT_TEMPLATE_DELETED = "TemplateDeleted";

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_EVENT_TEMPLATES)
    String bucket;

    @Inject S3Client storage;
    @Inject StorageBuckets storageBuckets;

    @Inject EventBus bus;

    @Inject
    @Named(Producers.BASE64_URL)
    Base64 base64Url;

    @Inject Logger logger;

    void onStart(@Observes StartupEvent evt) {
        storageBuckets.createIfNecessary(bucket);
    }

    @Override
    public Optional<IConstrainedMap<EventOptionID>> getEvents(
            String templateName, TemplateType unused) throws FlightRecorderException {
        try (var stream = getModel(templateName)) {
            return Optional.of(
                    new EventConfiguration(parseXml(stream))
                            .getEventOptions(
                                    new SimpleConstrainedMap<>(
                                            UnitLookup.PLAIN_TEXT.getPersister())));
        } catch (IOException | ParseException e) {
            logger.error(e);
            return Optional.empty();
        }
    }

    public boolean hasTemplate(String name) throws FlightRecorderException {
        return getTemplates().stream().anyMatch(t -> t.getName().equals(name));
    }

    @Override
    public List<Template> getTemplates() throws FlightRecorderException {
        return getObjects().stream()
                .map(
                        t -> {
                            try {
                                return convertObject(t);
                            } catch (InvalidEventTemplateException e) {
                                logger.error(e);
                                return null;
                            }
                        })
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public Optional<Document> getXml(String templateName, TemplateType unused)
            throws FlightRecorderException {
        try (var stream = getModel(templateName)) {
            Document doc =
                    Jsoup.parse(stream, StandardCharsets.UTF_8.name(), "", Parser.xmlParser());
            return Optional.of(doc);
        } catch (IOException e) {
            logger.error(e);
            return Optional.empty();
        }
    }

    private List<S3Object> getObjects() {
        var builder = ListObjectsV2Request.builder().bucket(bucket);
        return storage.listObjectsV2(builder.build()).contents();
    }

    private Template convertObject(S3Object object) throws InvalidEventTemplateException {
        var req = GetObjectTaggingRequest.builder().bucket(bucket).key(object.key()).build();
        var tagging = storage.getObjectTagging(req);
        var list = tagging.tagSet();
        if (!tagging.hasTagSet() || list.isEmpty()) {
            throw new InvalidEventTemplateException("No metadata found");
        }
        var decodedList = new ArrayList<Pair<String, String>>();
        list.forEach(
                t -> {
                    var encodedKey = t.key();
                    var decodedKey =
                            new String(base64Url.decode(encodedKey), StandardCharsets.UTF_8).trim();
                    var encodedValue = t.value();
                    var decodedValue =
                            new String(base64Url.decode(encodedValue), StandardCharsets.UTF_8)
                                    .trim();
                    decodedList.add(Pair.of(decodedKey, decodedValue));
                });
        var label =
                decodedList.stream()
                        .filter(t -> t.getKey().equals("label"))
                        .map(Pair::getValue)
                        .findFirst()
                        .orElseThrow();
        var description =
                decodedList.stream()
                        .filter(t -> t.getKey().equals("description"))
                        .map(Pair::getValue)
                        .findFirst()
                        .orElseThrow();
        var provider =
                decodedList.stream()
                        .filter(t -> t.getKey().equals("provider"))
                        .map(Pair::getValue)
                        .findFirst()
                        .orElseThrow();

        return new Template(label, description, provider, TemplateType.CUSTOM);
    }

    private InputStream getModel(String name) {
        var req = GetObjectRequest.builder().bucket(bucket).key(name).build();
        return storage.getObject(req);
    }

    @Override
    public Template addTemplate(InputStream stream)
            throws InvalidXmlException, InvalidEventTemplateException, IOException {
        try (stream) {
            var model = parseXml(stream);
            var template = createTemplate(model);
            storage.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(template.getName())
                            .contentType(MediaType.APPLICATION_XML)
                            .tagging(
                                    createTemplateTagging(
                                            template.getName(),
                                            template.getDescription(),
                                            template.getProvider()))
                            .build(),
                    RequestBody.fromString(model.toString()));

            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(EVENT_TEMPLATE_CREATED, Map.of("template", template)));
            return template;
        } catch (IOException ioe) {
            // FIXME InvalidXmlException constructor should be made public in -core
            // throw new InvalidXmlException("Unable to parse XML stream", ioe);
            throw new IllegalArgumentException("Unable to parse XML stream", ioe);
        } catch (ParseException | IllegalArgumentException e) {
            throw new IllegalArgumentException(new InvalidEventTemplateException("Invalid XML", e));
        }
    }

    @Override
    public void deleteTemplate(String templateName) {
        try {
            var template =
                    getTemplates().stream()
                            .filter(t -> t.getName().equals(templateName))
                            .findFirst()
                            .orElseThrow();
            var req = DeleteObjectRequest.builder().bucket(bucket).key(templateName).build();
            if (storage.deleteObject(req).sdkHttpResponse().isSuccessful()) {
                bus.publish(
                        MessagingServer.class.getName(),
                        new Notification(EVENT_TEMPLATE_DELETED, Map.of("template", template)));
            }
        } catch (FlightRecorderException e) {
            logger.error(e);
        }
    }

    private Tagging createTemplateTagging(
            String templateName, String description, String provider) {
        var map = Map.of("label", templateName, "description", description, "provider", provider);
        var tags = new ArrayList<Tag>();
        tags.addAll(
                map.entrySet().stream()
                        .map(
                                e ->
                                        Tag.builder()
                                                .key(
                                                        base64Url.encodeAsString(
                                                                e.getKey()
                                                                        .getBytes(
                                                                                StandardCharsets
                                                                                        .UTF_8)))
                                                .value(
                                                        base64Url.encodeAsString(
                                                                e.getValue()
                                                                        .getBytes(
                                                                                StandardCharsets
                                                                                        .UTF_8)))
                                                .build())
                        .toList());
        return Tagging.builder().tagSet(tags).build();
    }
}
