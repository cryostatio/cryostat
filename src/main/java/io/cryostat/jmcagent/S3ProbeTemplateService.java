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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.cryostat.ConfigProperties;
import io.cryostat.DeclarativeConfiguration;
import io.cryostat.Producers;
import io.cryostat.StorageBuckets;
import io.cryostat.core.jmcagent.ProbeTemplate;
import io.cryostat.core.jmcagent.ProbeTemplateService;
import io.cryostat.recordings.ArchivedRecordingMetadataService;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Identifier;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.xml.sax.SAXException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;

public class S3ProbeTemplateService implements ProbeTemplateService {

    private static final String META_KEY_CLASS_PREFIX = "classprefix";
    private static final String META_KEY_ALLOW_TO_STRING = "allowtostring";
    private static final String META_KEY_ALLOW_CONVERTER = "allowconverter";

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_PROBE_TEMPLATES)
    String bucket;

    @ConfigProperty(name = ConfigProperties.STORAGE_METADATA_PROBE_TEMPLATES_STORAGE_MODE)
    String storageMode;

    @ConfigProperty(name = ConfigProperties.PROBE_TEMPLATES_DIR)
    Path dir;

    @Inject DeclarativeConfiguration declarativeConfiguration;
    @Inject S3Client storage;
    @Inject StorageBuckets storageBuckets;
    @Inject Instance<BucketedProbeTemplateMetadataService> metadataService;

    @Inject EventBus bus;

    @Inject
    @Identifier(Producers.BASE64_URL)
    Base64 base64Url;

    @Inject Logger logger;

    private static final String TEMPLATE_DELETED_CATEGORY = "ProbeTemplateDeleted";
    private static final String TEMPLATE_UPLOADED_CATEGORY = "ProbeTemplateUploaded";

    void onStart(@Observes StartupEvent evt) {
        storageBuckets.createIfNecessary(bucket);
        try {
            declarativeConfiguration
                    .walk(dir)
                    .forEach(
                            path -> {
                                try {
                                    logger.debugv(
                                            "Uploading probe template from {0} to S3",
                                            path.toString());
                                    addTemplate(path, path.toString());
                                } catch (IOException | SAXException e) {
                                    logger.error(e);
                                } catch (DuplicateProbeTemplateException e) {
                                    logger.warn(e);
                                }
                            });
        } catch (IOException e) {
            logger.warn(e);
        }
    }

    @Override
    public List<ProbeTemplate> getTemplates() {
        return storage
                .listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
                .contents()
                .stream()
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
        if (!storage.headObject(
                        HeadObjectRequest.builder().bucket(bucket).key(templateName).build())
                .sdkHttpResponse()
                .isSuccessful()) {
            throw new NotFoundException();
        }
        var req = DeleteObjectRequest.builder().bucket(bucket).key(templateName).build();
        switch (storageMode()) {
            case TAGGING:
            // fall-through
            case METADATA:
                // no-op, S3 will clean up tagging/metadata along with the actual object
                break;
            case BUCKET:
                try {
                    metadataService.get().delete(templateName);
                } catch (IOException ioe) {
                    logger.warn(ioe);
                }
                break;
            default:
                throw new IllegalStateException();
        }
        if (storage.deleteObject(req).sdkHttpResponse().isSuccessful()) {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(
                            TEMPLATE_DELETED_CATEGORY, Map.of("probeTemplate", templateName)));
        }
    }

    private ArchivedRecordingMetadataService.StorageMode storageMode() {
        return RecordingHelper.storageMode(storageMode);
    }

    private InputStream getModel(String name) {
        var req = GetObjectRequest.builder().bucket(bucket).key(name).build();
        return storage.getObject(req);
    }

    private ProbeTemplate convertObject(S3Object object) throws Exception {
        String fileName = object.key();
        String classPrefix;
        boolean allowToString, allowConverter;
        switch (storageMode()) {
            case TAGGING:
                var getTaggingReq =
                        GetObjectTaggingRequest.builder().bucket(bucket).key(object.key()).build();
                var tagging = storage.getObjectTagging(getTaggingReq);
                var tagSet = tagging.tagSet();
                if (!tagging.hasTagSet() || tagSet.isEmpty()) {
                    throw new Exception("No metadata found");
                }
                var decodedTagList = new ArrayList<Pair<String, String>>();
                tagSet.forEach(
                        t -> {
                            var encodedKey = t.key();
                            var decodedKey =
                                    new String(base64Url.decode(encodedKey), StandardCharsets.UTF_8)
                                            .trim();
                            var encodedValue = t.value();
                            var decodedValue =
                                    new String(
                                                    base64Url.decode(encodedValue),
                                                    StandardCharsets.UTF_8)
                                            .trim();
                            decodedTagList.add(Pair.of(decodedKey, decodedValue));
                        });
                classPrefix =
                        decodedTagList.stream()
                                .filter(t -> t.getKey().equals(META_KEY_CLASS_PREFIX))
                                .map(Pair::getValue)
                                .findFirst()
                                .orElseThrow();
                allowToString =
                        Boolean.valueOf(
                                decodedTagList.stream()
                                        .filter(t -> t.getKey().equals(META_KEY_ALLOW_TO_STRING))
                                        .map(Pair::getValue)
                                        .findFirst()
                                        .orElseThrow());
                allowConverter =
                        Boolean.valueOf(
                                decodedTagList.stream()
                                        .filter(t -> t.getKey().equals(META_KEY_ALLOW_CONVERTER))
                                        .map(Pair::getValue)
                                        .findFirst()
                                        .orElseThrow());
                break;
            case METADATA:
                var getMetaReq =
                        HeadObjectRequest.builder().bucket(bucket).key(object.key()).build();
                var meta = storage.headObject(getMetaReq).metadata();
                try {
                    classPrefix = Objects.requireNonNull(meta.get(META_KEY_CLASS_PREFIX));
                    allowToString = Boolean.valueOf(meta.get(META_KEY_ALLOW_TO_STRING));
                    allowConverter = Boolean.valueOf(meta.get(META_KEY_ALLOW_CONVERTER));
                } catch (NullPointerException npe) {
                    npe.printStackTrace();
                    throw new IOException(npe);
                }
                break;
            case BUCKET:
                var pt = metadataService.get().read(fileName).orElseThrow();
                classPrefix = pt.getClassPrefix();
                allowToString = pt.getAllowToString();
                allowConverter = pt.getAllowConverter();
                break;
            default:
                throw new IllegalStateException();
        }
        // filename, classprefix, allowtostring, allowconverter
        return new ProbeTemplate(
                fileName,
                classPrefix,
                Boolean.valueOf(allowToString),
                Boolean.valueOf(allowConverter));
    }

    public ProbeTemplate addTemplate(Path path, String fileName) throws IOException, SAXException {
        try (var stream = Files.newInputStream(path)) {
            ProbeTemplate template = new ProbeTemplate();
            template.setFileName(fileName);
            template.deserialize(stream);
            var existing = getTemplates();
            if (existing.stream()
                    .anyMatch(t -> Objects.equals(t.getFileName(), template.getFileName()))) {
                throw new DuplicateProbeTemplateException(template.getFileName());
            }
            var reqBuilder =
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(fileName)
                            .contentType(MediaType.APPLICATION_XML);
            switch (storageMode()) {
                case TAGGING:
                    reqBuilder =
                            reqBuilder.tagging(
                                    createTemplateTagging(
                                            template.getClassPrefix(),
                                            String.valueOf(template.getAllowToString()),
                                            String.valueOf(template.getAllowConverter())));
                    break;
                case METADATA:
                    reqBuilder =
                            reqBuilder.metadata(
                                    Map.of(
                                            META_KEY_CLASS_PREFIX, template.getClassPrefix(),
                                            META_KEY_ALLOW_CONVERTER,
                                                    Boolean.toString(template.getAllowConverter()),
                                            META_KEY_ALLOW_TO_STRING,
                                                    Boolean.toString(template.getAllowToString())));
                    break;
                case BUCKET:
                    metadataService.get().create(fileName, template);
                    break;
                default:
                    throw new IllegalStateException();
            }
            var xml = template.serialize();
            storage.putObject(reqBuilder.build(), RequestBody.fromString(xml));
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(
                            TEMPLATE_UPLOADED_CATEGORY,
                            Map.of(
                                    "probeTemplate",
                                    template.getFileName(),
                                    "templateContent",
                                    xml)));
            return template;
        }
    }

    private Tagging createTemplateTagging(
            String classPrefix, String allowToString, String allowConverter) {
        var map =
                Map.of(
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

    static class DuplicateProbeTemplateException extends IllegalArgumentException {
        DuplicateProbeTemplateException(String templateName) {
            super(String.format("Probe Template with name \"%s\" already exists", templateName));
        }
    }
}
