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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import io.cryostat.ConfigProperties;
import io.cryostat.Producers;
import io.cryostat.StorageBuckets;
import io.cryostat.diagnostic.Diagnostics.ThreadDump;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;

import io.quarkus.runtime.StartupEvent;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;

@ApplicationScoped
public class DiagnosticsHelper {

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_THREAD_DUMPS)
    String bucket;

    @ConfigProperty(name = ConfigProperties.STORAGE_METADATA_STORAGE_MODE)
    String storageMode;

    @Inject
    @Named(Producers.BASE64_URL)
    Base64 base64Url;

    @Inject S3Client storage;
    @Inject Logger log;

    @Inject Instance<BucketedDiagnosticsMetadataService> metadataService;

    private static final String DUMP_THREADS = "threadPrint";
    private static final String DUMP_THREADS_TO_FIlE = "threadDumpToFile";
    private static final String DIAGNOSTIC_BEAN_NAME = "com.sun.management:type=DiagnosticCommand";
    static final String THREAD_DUMP_REQUESTED = "ThreadDumpRequested";
    static final String THREAD_DUMP_DELETED = "ThreadDumpDeleted";
    private static final String META_KEY_NAME = "uuid";
    private static final String META_KEY_JVMID = "jvmId";

    public static final String METADATA_STORAGE_MODE_TAGGING = "tagging";
    public static final String METADATA_STORAGE_MODE_OBJECTMETA = "metadata";
    public static final String METADATA_STORAGE_MODE_BUCKET = "bucket";

    @Inject EventBus bus;
    @Inject TargetConnectionManager targetConnectionManager;
    @Inject StorageBuckets buckets;

    void onStart(@Observes StartupEvent evt) {
        log.tracev("Creating thread dump bucket: {0}", bucket);
        buckets.createIfNecessary(bucket);
    }

    public ThreadDump dumpThreads(String format, long targetId) {
        if (!(format.equals(DUMP_THREADS) || format.equals(DUMP_THREADS_TO_FIlE))) {
            throw new BadRequestException();
        }
        log.tracev(
                "Thread Dump request received for Target: {0} with format: {1}", targetId, format);
        Object[] params = new Object[1];
        String[] signature = new String[] {String[].class.getName()};
        return targetConnectionManager.executeConnectedTask(
                Target.getTargetById(targetId),
                conn -> {
                    String content =
                            conn.invokeMBeanOperation(
                                    DIAGNOSTIC_BEAN_NAME, format, params, signature, String.class);
                    return addThreadDump(content, Target.getTargetById(targetId).jvmId);
                });
    }

    public List<ThreadDump> getThreadDumps(long targetId) {
        return listThreadDumps(targetId).stream()
                .map(
                        item -> {
                            try {
                                return convertObject(item);
                            } catch (Exception e) {
                                log.error(e);
                                return null;
                            }
                        })
                .filter(
                        item -> {
                            log.tracev("Item jvmID: {0}", item.jvmId());
                            log.tracev("Item key: {0}", item.uuid());
                            log.tracev("Item download URL: {0}", item.downloadUrl());
                            return Target.<Target>findById(targetId).jvmId.equals(item.jvmId());
                        })
                .filter(item -> !Objects.isNull(item))
                .toList();
    }

    private ThreadDump convertObject(S3Object object) throws Exception {
        String jvmId, uuid;
        switch (storageMode(storageMode)) {
            case StorageMode.TAGGING:
                var req =
                        GetObjectTaggingRequest.builder().bucket(bucket).key(object.key()).build();
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
                                    new String(base64Url.decode(encodedKey), StandardCharsets.UTF_8)
                                            .trim();
                            var encodedValue = t.value();
                            var decodedValue =
                                    new String(
                                                    base64Url.decode(encodedValue),
                                                    StandardCharsets.UTF_8)
                                            .trim();
                            decodedList.add(Pair.of(decodedKey, decodedValue));
                        });
                jvmId =
                        decodedList.stream()
                                .filter(t -> t.getKey().equals("jvmId"))
                                .map(Pair::getValue)
                                .findFirst()
                                .orElseThrow();
                uuid =
                        decodedList.stream()
                                .filter(t -> t.getKey().equals("uuid"))
                                .map(Pair::getValue)
                                .findFirst()
                                .orElseThrow();
                // content, jvmid, downloadurl, uuid
                break;
            case StorageMode.METADATA:
                var headReq = HeadObjectRequest.builder().bucket(bucket).key(object.key()).build();
                var meta = storage.headObject(headReq).metadata();
                uuid = Objects.requireNonNull(meta.get(META_KEY_NAME));
                jvmId = Objects.requireNonNull(meta.get(META_KEY_JVMID));
                break;
            case StorageMode.BUCKET:
                var t = metadataService.get().read(object.key()).orElseThrow();
                uuid = t.uuid();
                jvmId = t.jvmId();
                break;
            default:
                throw new IllegalStateException();
        }
        return new ThreadDump(getThreadDumpContent(uuid), jvmId, downloadUrl(jvmId, uuid), uuid);
    }

    public String getThreadDumpContent(String uuid) throws IOException {
        InputStream is = getModel(uuid);
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    private InputStream getModel(String name) {
        var req = GetObjectRequest.builder().bucket(bucket).key(name).build();
        return storage.getObject(req);
    }

    public ThreadDump addThreadDump(String content, String jvmId) {
        String uuid = UUID.randomUUID().toString();
        var reqBuilder =
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(uuid)
                        .contentType(MediaType.TEXT_PLAIN);
        switch (storageMode(storageMode)) {
            case StorageMode.TAGGING:
                reqBuilder = reqBuilder.tagging(createTagging(jvmId, uuid));
                log.tracev("Putting Thread dump into storage with key: {0}", uuid);
                log.tracev("jvmID: {0}", jvmId);
                log.tracev("Bucket: {0}", bucket);
                break;
            case StorageMode.METADATA:
                reqBuilder =
                        reqBuilder.metadata(Map.of(META_KEY_NAME, uuid, META_KEY_JVMID, jvmId));
                break;
            case StorageMode.BUCKET:
                try {
                    metadataService
                            .get()
                            .create(
                                    uuid,
                                    new ThreadDump(content, jvmId, downloadUrl(jvmId, uuid), uuid));
                    break;
                } catch (IOException ioe) {
                    log.warnv("Exception thrown while adding thread dump to storage: {0}", ioe);
                }
            default:
                throw new IllegalStateException();
        }
        storage.putObject(reqBuilder.build(), RequestBody.fromString(content));
        return new ThreadDump(content, jvmId, downloadUrl(jvmId, uuid), uuid);
    }

    private Tagging createTagging(String jvmId, String uuid) {
        var map = Map.of("jvmId", jvmId, "uuid", uuid);
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

    public String downloadUrl(String jvmId, String filename) {
        return String.format(
                "/api/beta/diagnostics/threaddump/download/%s", encodedKey(jvmId, filename));
    }

    public String encodedKey(String jvmId, String uuid) {
        Objects.requireNonNull(jvmId);
        Objects.requireNonNull(uuid);
        return base64Url.encodeAsString(
                (threadDumpKey(jvmId, uuid)).getBytes(StandardCharsets.UTF_8));
    }

    public String threadDumpKey(String jvmId, String uuid) {
        return (jvmId + "/" + uuid).strip();
    }

    public String threadDumpKey(Pair<String, String> pair) {
        return threadDumpKey(pair.getKey(), pair.getValue());
    }

    public InputStream getThreadDumpStream(String jvmId, String threadDumpID) {
        return getThreadDumpStream(encodedKey(jvmId, threadDumpID));
    }

    public InputStream getThreadDumpStream(String encodedKey) {
        Pair<String, String> decodedKey = decodedKey(encodedKey);
        var key = decodedKey.getValue().strip();

        GetObjectRequest getRequest = GetObjectRequest.builder().bucket(bucket).key(key).build();

        return storage.getObject(getRequest);
    }

    public Pair<String, String> decodedKey(String encodedKey) {
        String key = new String(base64Url.decode(encodedKey), StandardCharsets.UTF_8);
        String[] parts = key.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException();
        }
        return Pair.of(parts[0], parts[1]);
    }

    public List<S3Object> listThreadDumps(long targetId) {
        var jvmId = Target.<Target>findById(targetId).jvmId;
        log.tracev("Listing thread dumps for jvmId: {0}", jvmId);
        return storage
                .listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
                .contents()
                .stream()
                .toList();
    }

    public static StorageMode storageMode(String name) {
        return Arrays.asList(StorageMode.values()).stream()
                .filter(s -> s.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow();
    }

    static enum StorageMode {
        TAGGING(METADATA_STORAGE_MODE_TAGGING),
        METADATA(METADATA_STORAGE_MODE_OBJECTMETA),
        BUCKET(METADATA_STORAGE_MODE_BUCKET),
        ;
        private final String key;

        private StorageMode(String key) {
            this.key = key;
        }

        public String getKey() {
            return key;
        }
    }
}
