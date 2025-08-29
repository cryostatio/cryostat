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
package io.cryostat.diagnostic;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.cryostat.ConfigProperties;
import io.cryostat.StorageBuckets;
import io.cryostat.diagnostic.Diagnostics.HeapDump;
import io.cryostat.recordings.ArchivedRecordingMetadataService;
import io.cryostat.util.CRUDService;
import io.cryostat.util.HttpMimeType;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.lookup.LookupIfProperty;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@ApplicationScoped
@LookupIfProperty(
        name = ConfigProperties.STORAGE_METADATA_HEAP_DUMPS_STORAGE_MODE,
        stringValue = ArchivedRecordingMetadataService.METADATA_STORAGE_MODE_BUCKET)
public class BucketedHeapDumpsMetadataService
        implements CRUDService<String, HeapDump, HeapDump> {

    @ConfigProperty(name = ConfigProperties.STORAGE_METADATA_HEAP_DUMPS_STORAGE_MODE)
    String storageMode;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_METADATA)
    String bucket;

    @ConfigProperty(name = ConfigProperties.AWS_METADATA_PREFIX_HEAP_DUMPS)
    String prefix;

    @Inject S3Client storage;
    @Inject StorageBuckets buckets;

    @Inject ObjectMapper mapper;

    @Inject Logger logger;

    void onStart(@Observes StartupEvent evt) {
        if (!ArchivedRecordingMetadataService.METADATA_STORAGE_MODE_BUCKET.equals(storageMode)) {
            return;
        }
        buckets.createIfNecessary(bucket);
    }

    @Override
    public List<HeapDump> list() throws IOException {
        var builder = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix);
        var objs = storage.listObjectsV2(builder.build()).contents();
        return objs.stream()
                .map(
                        t -> {
                            // TODO this entails a remote file read over the network and then some
                            // minor processing of the received file. More time will be spent
                            // retrieving the data than processing it, so this should be
                            // parallelized.
                            try {
                                return read(t.key()).orElseThrow();
                            } catch (IOException e) {
                                logger.error(e);
                                return null;
                            }
                        })
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public void create(String k, HeapDump heapDump) throws IOException {
        storage.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(prefix(k))
                        .contentType(HttpMimeType.OCTET_STREAM.mime())
                        .build(),
                RequestBody.fromBytes(mapper.writeValueAsBytes(heapDump)));
    }

    @Override
    public Optional<HeapDump> read(String k) throws IOException {
        try (var stream =
                new BufferedInputStream(
                        storage.getObject(
                                GetObjectRequest.builder()
                                        .bucket(bucket)
                                        .key(prefix(k))
                                        .build()))) {
            return Optional.of(mapper.readValue(stream, HeapDump.class));
        }
    }

    @Override
    public void delete(String k) throws IOException {
        storage.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(prefix(k)).build());
    }

    private String prefix(String key) {
        return String.format("%s/%s", prefix, key);
    }
}