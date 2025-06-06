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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.cryostat.ConfigProperties;
import io.cryostat.DeclarativeConfiguration;
import io.cryostat.StorageBuckets;
import io.cryostat.core.jmcagent.ProbeTemplate;
import io.cryostat.core.jmcagent.ProbeTemplateService;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.quarkus.runtime.StartupEvent;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.xml.sax.SAXException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class S3ProbeTemplateService implements ProbeTemplateService {

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_PROBE_TEMPLATES)
    String bucket;

    @ConfigProperty(name = ConfigProperties.PROBE_TEMPLATES_DIR)
    Path dir;

    @Inject DeclarativeConfiguration declarativeConfiguration;
    @Inject S3Client storage;
    @Inject StorageBuckets storageBuckets;
    @Inject EventBus bus;
    @Inject Logger logger;

    private static final String TEMPLATE_DELETED_CATEGORY = "ProbeTemplateDeleted";
    private static final String TEMPLATE_UPLOADED_CATEGORY = "ProbeTemplateUploaded";

    void onStart(@Observes StartupEvent evt) {
        storageBuckets
                .createIfNecessary(bucket)
                .thenRunAsync(
                        () -> {
                            try {
                                declarativeConfiguration
                                        .walk(dir)
                                        .forEach(
                                                path -> {
                                                    try {
                                                        logger.debugv(
                                                                "Uploading probe template from {0}"
                                                                        + " to S3",
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
                        });
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
                                return convertObject(t.key());
                            } catch (Exception e) {
                                logger.error(e);
                                return null;
                            }
                        })
                .filter(Objects::nonNull)
                .toList();
    }

    public String getTemplateContent(String fileName) throws IOException, SAXException {
        return convertObject(fileName).serialize();
    }

    public void deleteTemplate(String templateName) {
        if (!storage.headObject(
                        HeadObjectRequest.builder().bucket(bucket).key(templateName).build())
                .sdkHttpResponse()
                .isSuccessful()) {
            throw new NotFoundException();
        }
        if (storage.deleteObject(
                        DeleteObjectRequest.builder().bucket(bucket).key(templateName).build())
                .sdkHttpResponse()
                .isSuccessful()) {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(
                            TEMPLATE_DELETED_CATEGORY, Map.of("probeTemplate", templateName)));
        }
    }

    private InputStream getModel(String name) {
        var req = GetObjectRequest.builder().bucket(bucket).key(name).build();
        return new BufferedInputStream(storage.getObject(req));
    }

    private ProbeTemplate convertObject(String fileName) throws IOException, SAXException {
        try (var stream = getModel(fileName)) {
            ProbeTemplate template = new ProbeTemplate();
            template.setFileName(fileName);
            template.deserialize(stream);
            return template;
        }
    }

    public ProbeTemplate addTemplate(Path path, String fileName) throws IOException, SAXException {
        try (var stream = new BufferedInputStream(Files.newInputStream(path))) {
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

    static class DuplicateProbeTemplateException extends IllegalArgumentException {
        DuplicateProbeTemplateException(String templateName) {
            super(String.format("Probe Template with name \"%s\" already exists", templateName));
        }
    }
}
