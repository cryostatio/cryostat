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
package io.cryostat.jmcagent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.cryostat.ConfigProperties;
import io.cryostat.Producers;
import io.cryostat.StorageBuckets;
import io.cryostat.core.agent.ProbeTemplate;
import io.cryostat.core.agent.ProbeTemplateService;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.eventbus.EventBus;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hc.core5.http.ContentType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.xml.sax.SAXException;
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

public class S3ProbeTemplateService implements ProbeTemplateService {

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_PROBE_TEMPLATES)
    String bucket;

    @Inject S3Client storage;
    @Inject StorageBuckets storageBuckets;

    @Inject EventBus bus;

    @Inject
    @Named(Producers.BASE64_URL)
    Base64 base64Url;

    @Inject Logger logger;

    private static final String TEMPLATE_DELETED_CATEGORY = "ProbeTemplateDeleted";
    private static final String TEMPLATE_UPLOADED_CATEGORY = "ProbeTemplateUploaded";

    void onStart(@Observes StartupEvent evt) {
        storageBuckets.createIfNecessary(bucket);
    }

    @Override
    public List<ProbeTemplate> getTemplates() {
        return getObjects().stream()
                .map(
                        t -> {
                            try {
                                return convertObject(t);
                            } catch (Exception e) {
                                logger.error(e);
                                return null;
                            }
                        })
                .filter(Objects::nonNull)
                .toList();
    }

    public String getTemplateContent(String fileName) {
        try (var stream = getModel(fileName)) {
            ProbeTemplate template = new ProbeTemplate();
            template.setFileName(fileName);
            template.deserialize(stream);
            return template.serialize();
        } catch (Exception e) {
            logger.error(e);
            return null;
        }
    }

    public void deleteTemplate(String templateName) {
        var template =
                getTemplates().stream()
                        .filter(t -> t.getFileName().equals(templateName))
                        .findFirst()
                        .orElseThrow();
        var req = DeleteObjectRequest.builder().bucket(bucket).key(templateName).build();
        if (storage.deleteObject(req).sdkHttpResponse().isSuccessful()) {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(TEMPLATE_DELETED_CATEGORY, Map.of("probeTemplate", template)));
        }
    }

    private InputStream getModel(String name) {
        var req = GetObjectRequest.builder().bucket(bucket).key(name).build();
        return storage.getObject(req);
    }

    private List<S3Object> getObjects() {
        var builder = ListObjectsV2Request.builder().bucket(bucket);
        return storage.listObjectsV2(builder.build()).contents();
    }

    private ProbeTemplate convertObject(S3Object object) throws Exception {
        var req = GetObjectTaggingRequest.builder().bucket(bucket).key(object.key()).build();
        var tagging = storage.getObjectTagging(req);
        var list = tagging.tagSet();
        if (!tagging.hasTagSet() || list.isEmpty()) {
            throw new Exception("No metadata found");
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
        var fileName =
                decodedList.stream()
                        .filter(t -> t.getKey().equals("fileName"))
                        .map(Pair::getValue)
                        .findFirst()
                        .orElseThrow();
        var classPrefix =
                decodedList.stream()
                        .filter(t -> t.getKey().equals("classPrefix"))
                        .map(Pair::getValue)
                        .findFirst()
                        .orElseThrow();
        var allowToString =
                decodedList.stream()
                        .filter(t -> t.getKey().equals("allowToString"))
                        .map(Pair::getValue)
                        .findFirst()
                        .orElseThrow();
        var allowConverter =
                decodedList.stream()
                        .filter(t -> t.getKey().equals("allowConverter"))
                        .map(Pair::getValue)
                        .findFirst()
                        .orElseThrow();
        // filename, classprefix, allowtostring, allowconverter
        return new ProbeTemplate(
                fileName,
                classPrefix,
                Boolean.valueOf(allowToString),
                Boolean.valueOf(allowConverter));
    }

    public ProbeTemplate addTemplate(InputStream stream, String fileName)
            throws IOException, SAXException {
        try (stream) {
            ProbeTemplate template = new ProbeTemplate();
            template.setFileName(fileName);
            template.deserialize(stream);
            storage.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(fileName)
                            .contentType(ContentType.APPLICATION_XML.getMimeType())
                            .tagging(
                                    createTemplateTagging(
                                            fileName,
                                            template.getClassPrefix(),
                                            String.valueOf(template.getAllowToString()),
                                            String.valueOf(template.getAllowConverter())))
                            .build(),
                    RequestBody.fromString(template.serialize()));
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(
                            TEMPLATE_UPLOADED_CATEGORY,
                            Map.of("probeTemplate", template.getFileName())));
            return template;
        }
    }

    private Tagging createTemplateTagging(
            String templateName, String classPrefix, String allowToString, String allowConverter) {
        var map =
                Map.of(
                        "fileName",
                        templateName,
                        "classPrefix",
                        classPrefix,
                        "allowToString",
                        allowToString,
                        "allowConverter",
                        allowConverter);
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
