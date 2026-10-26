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
package io.cryostat.recordings;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.Optional;

import io.cryostat.ConfigProperties;
import io.cryostat.StorageBuckets;
import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.storage.StorageConfiguration;
import io.cryostat.util.HttpMimeType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.lookup.LookupIfProperty;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.UploadRequest;

@ApplicationScoped
@LookupIfProperty(
        name = ConfigProperties.STORAGE_METADATA_ARCHIVES_STORAGE_MODE,
        stringValue = ArchivedRecordingMetadataService.METADATA_STORAGE_MODE_BUCKET)
/**
 * Implements archived recording metadata as standalone objects in a bucket. When the storage mode
 * is set to select this implementation, archived recordings' metadata is not stored using the S3
 * object Tags, but rather as separate JSON files in separate buckets. This ensures broader
 * compatibility with different S3-like implementations and providers, and also circumvents any
 * limitations on tag count or tag size/length.
 */
class BucketedArchivedRecordingMetadataService implements ArchivedRecordingMetadataService {

    @Inject StorageBuckets storageBuckets;
    @Inject S3Client storage;
    @Inject S3TransferManager transferManager;
    @Inject Logger logger;
    @Inject StorageConfiguration storageConfig;

    @ConfigProperty(name = ConfigProperties.STORAGE_METADATA_ARCHIVES_STORAGE_MODE)
    String storageMode;

    // don't use the application-wide instance. That one serializes maps as key-value pair lists for
    // historical API reasons, but for this internal usage we just want the default behaviour.
    private final ObjectMapper mapper = new ObjectMapper();

    void onStart(@Observes StartupEvent evt) {
        if (!METADATA_STORAGE_MODE_BUCKET.equals(storageMode)) {
            return;
        }
        String bucket =
                storageConfig
                        .getMetadataStorageInfo(
                                ConfigProperties.StorageResourceType.ARCHIVED_RECORDINGS)
                        .bucketName();
        storageBuckets.createIfNecessary(bucket);
    }

    @Override
    public void create(String storageKey, Metadata metadata) throws JsonProcessingException {
        var storageInfo =
                storageConfig.getMetadataStorageInfo(
                        ConfigProperties.StorageResourceType.ARCHIVED_RECORDINGS);
        PutObjectRequest.Builder builder =
                PutObjectRequest.builder()
                        .bucket(storageInfo.bucketName())
                        .key(getMetadataKey(storageKey))
                        .contentType(HttpMimeType.JSON.mime());
        transferManager
                .upload(
                        UploadRequest.builder()
                                .putObjectRequest(builder.build())
                                .requestBody(
                                        AsyncRequestBody.fromBytes(
                                                mapper.writeValueAsBytes(metadata)))
                                .build())
                .completionFuture()
                .join();
    }

    @Override
    public Optional<Metadata> read(String storageKey) throws IOException {
        var storageInfo =
                storageConfig.getMetadataStorageInfo(
                        ConfigProperties.StorageResourceType.ARCHIVED_RECORDINGS);
        GetObjectRequest.Builder builder =
                GetObjectRequest.builder()
                        .bucket(storageInfo.bucketName())
                        .key(getMetadataKey(storageKey));
        var resp = storage.getObject(builder.build());
        if (resp.response().sdkHttpResponse().isSuccessful()) {
            return Optional.of(mapper.readValue(new BufferedInputStream(resp), Metadata.class));
        }
        return Optional.empty();
    }

    @Override
    public void delete(String storageKey) {
        var storageInfo =
                storageConfig.getMetadataStorageInfo(
                        ConfigProperties.StorageResourceType.ARCHIVED_RECORDINGS);
        DeleteObjectRequest.Builder builder =
                DeleteObjectRequest.builder()
                        .bucket(storageInfo.bucketName())
                        .key(getMetadataKey(storageKey));
        storage.deleteObject(builder.build());
    }

    private String getMetadataKey(String identifier) {
        var storageInfo =
                storageConfig.getMetadataStorageInfo(
                        ConfigProperties.StorageResourceType.ARCHIVED_RECORDINGS);
        return String.format("%s/%s", storageInfo.directoryName(), identifier);
    }
}
