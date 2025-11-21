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
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import io.cryostat.ConfigProperties;
import io.cryostat.Producers;
import io.cryostat.StorageBuckets;
import io.cryostat.diagnostic.Diagnostics.HeapDump;
import io.cryostat.diagnostic.Diagnostics.ThreadDump;
import io.cryostat.diagnostic.HeapDumpsMetadataService.StorageMode;
import io.cryostat.libcryostat.sys.Clock;
import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Identifier;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;

@ApplicationScoped
public class DiagnosticsHelper {

    static final String THREAD_DUMP_DELETED = "ThreadDumpDeleted";
    static final String THREAD_DUMP_REQUESTED = "ThreadDumpRequested";
    static final String THREAD_DUMP_SUCCESS = "ThreadDumpSuccess";
    static final String THREAD_DUMP_METADATA = "ThreadDumpMetadataUpdated";
    static final String DUMP_THREADS = "threadPrint";
    static final String DUMP_THREADS_TO_FIlE = "threadDumpToFile";
    static final String DUMP_HEAP = "dumpHeap";
    static final String HEAP_DUMP_REQUESTED = "HeapDumpRequested";
    static final String HEAP_DUMP_DELETED_NAME = "HeapDumpDeleted";
    static final String HEAP_DUMP_SUCCESS = "HeapDumpSuccess";
    static final String HEAP_DUMP_UPLOADED_NAME = "HeapDumpUploaded";
    static final String HEAP_DUMP_METADATA = "HeapDumpMetadataUpdated";
    private static final String DIAGNOSTIC_BEAN_NAME = "com.sun.management:type=DiagnosticCommand";
    private static final String HOTSPOT_DIAGNOSTIC_BEAN_NAME =
            "com.sun.management:type=HotSpotDiagnostic";

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_THREAD_DUMPS)
    String threadDumpBucket;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_HEAP_DUMPS)
    String heapDumpBucket;

    @ConfigProperty(name = ConfigProperties.STORAGE_METADATA_STORAGE_MODE)
    String metadataStorageMode;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_UPLOAD_TIMEOUT)
    Duration uploadFailedTimeout;

    @Inject
    @Identifier(Producers.BASE64_URL)
    Base64 base64Url;

    @Inject Instance<HeapDumpsMetadataService> heapDumpsMetadataService;
    @Inject Instance<ThreadDumpsMetadataService> threadDumpsMetadataService;

    @Inject S3Client storage;
    @Inject Logger log;
    @Inject Clock clock;

    @Inject EventBus bus;
    @Inject TargetConnectionManager targetConnectionManager;
    @Inject StorageBuckets buckets;

    void onStart(@Observes StartupEvent evt) {
        log.tracev("Creating heap dump bucket: {0}", heapDumpBucket);
        buckets.createIfNecessary(heapDumpBucket);
        log.tracev("Creating thread dump bucket: {0}", threadDumpBucket);
        buckets.createIfNecessary(threadDumpBucket);
    }

    public void dumpHeap(Target target, String requestId) {
        log.tracev(
                "Heap Dump request received for Target: {0} with jobId {1}", target.id, requestId);
        Object[] params = new Object[3];
        String[] signature = new String[] {String.class.getName(), boolean.class.getName()};
        // The agent will generate the filename on its side
        params[0] = "";
        params[1] = false;
        params[2] = requestId;
        // Heap Dump Retrieval is handled by a separate endpoint
        targetConnectionManager.executeConnectedTask(
                target,
                conn ->
                        conn.invokeMBeanOperation(
                                HOTSPOT_DIAGNOSTIC_BEAN_NAME,
                                DUMP_HEAP,
                                params,
                                signature,
                                Void.class),
                uploadFailedTimeout);
    }

    public String generateFileName(String jvmId, String uuid, String extension) {
        Target t =
                QuarkusTransaction.joiningExisting()
                        .call(() -> Target.getTargetByJvmId(jvmId))
                        .get();
        if (Objects.isNull(t)) {
            log.errorv("jvmId {0} failed to resolve to target. Defaulting to uuid.", jvmId);
            return uuid;
        }
        return t.alias + "_" + uuid + extension;
    }

    public void deleteHeapDump(String jvmId, String heapDumpId)
            throws BadRequestException, NoSuchKeyException {
        String key = storageKey(jvmId, heapDumpId);
        storage.headObject(HeadObjectRequest.builder().bucket(heapDumpBucket).key(key).build());
        storage.deleteObject(DeleteObjectRequest.builder().bucket(heapDumpBucket).key(key).build());
        switch (storageMode()) {
            case TAGGING:
            // fall-through
            case METADATA:
                // no-op - the S3 instance will delete the tagging/metadata associated with the
                // object automatically
                break;
            case BUCKET:
                try {
                    heapDumpsMetadataService.get().delete(jvmId, heapDumpId);
                } catch (IOException ioe) {
                    log.warn(ioe);
                }
                break;
            default:
                throw new IllegalStateException();
        }
        var event =
                new HeapDumpEvent(
                        EventCategory.HEAP_DUMP_DELETED,
                        HeapDumpEvent.Payload.of(
                                jvmId,
                                new HeapDump(
                                        jvmId,
                                        heapDumpDownloadUrl(jvmId, heapDumpId),
                                        heapDumpId,
                                        0,
                                        0,
                                        new Metadata(Map.of()))));
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(event.category().category(), event.payload()));
    }

    public List<S3Object> listHeapDumpObjects() {
        return listHeapDumpObjects(null);
    }

    public List<S3Object> listHeapDumpObjects(String jvmId) {
        var builder = ListObjectsV2Request.builder().bucket(heapDumpBucket);
        if (StringUtils.isNotBlank(jvmId)) {
            builder = builder.prefix(jvmId);
        }
        return storage.listObjectsV2(builder.build()).contents();
    }

    public List<HeapDump> getHeapDumps(String jvmId) {
        return getHeapDumps(
                jvmId == null
                        ? null
                        : QuarkusTransaction.joiningExisting()
                                .call(() -> Target.getTargetByJvmId(jvmId))
                                .get());
    }

    public List<HeapDump> getHeapDumps(Target target) {
        return listHeapDumps(target).stream()
                .map(
                        item -> {
                            try {
                                return convertHeapDump(item);
                            } catch (Exception e) {
                                log.error(e);
                                return null;
                            }
                        })
                .filter(Objects::nonNull)
                .toList();
    }

    public ThreadDump dumpThreads(Target target, String format, String requestId) {
        if (!(format.equals(DUMP_THREADS) || format.equals(DUMP_THREADS_TO_FIlE))) {
            throw new IllegalArgumentException();
        }
        log.tracev(
                "Thread Dump request received for Target: {0} with format: {1}", target.id, format);
        final Object[] params = new Object[1];
        final String[] signature = new String[] {String[].class.getName()};
        return targetConnectionManager.executeConnectedTask(
                target,
                conn ->
                        addThreadDump(
                                target,
                                conn.invokeMBeanOperation(
                                        DIAGNOSTIC_BEAN_NAME,
                                        format,
                                        params,
                                        signature,
                                        String.class)),
                uploadFailedTimeout);
    }

    public void deleteThreadDump(String jvmId, String threadDumpId) {
        String key = storageKey(jvmId, threadDumpId);
        storage.headObject(HeadObjectRequest.builder().bucket(threadDumpBucket).key(key).build());
        storage.deleteObject(
                DeleteObjectRequest.builder().bucket(threadDumpBucket).key(key).build());
        switch (storageMode()) {
            case TAGGING:
            // fall-through
            case METADATA:
                // no-op - the S3 instance will delete the tagging/metadata associated with the
                // object automatically
                break;
            case BUCKET:
                try {
                    threadDumpsMetadataService.get().delete(jvmId, threadDumpId);
                } catch (IOException ioe) {
                    log.warn(ioe);
                }
                break;
            default:
                throw new IllegalStateException();
        }
        var event =
                new ThreadDumpEvent(
                        EventCategory.DELETED,
                        ThreadDumpEvent.Payload.of(
                                new ThreadDump(
                                        jvmId,
                                        threadDumpDownloadUrl(jvmId, threadDumpId),
                                        threadDumpId,
                                        0,
                                        0,
                                        new Metadata(Map.of())),
                                ""));
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(event.category().category(), event.payload()));
    }

    public List<S3Object> listThreadDumpObjects() {
        return listThreadDumpObjects(null);
    }

    public List<S3Object> listThreadDumpObjects(String jvmId) {
        var builder = ListObjectsV2Request.builder().bucket(threadDumpBucket);
        if (StringUtils.isNotBlank(jvmId)) {
            builder = builder.prefix(jvmId);
        }
        return storage.listObjectsV2(builder.build()).contents();
    }

    public List<ThreadDump> getThreadDumps(String jvmId) {
        return getThreadDumps(jvmId == null ? null : Target.getTargetByJvmId(jvmId).get());
    }

    public List<ThreadDump> getThreadDumps(Target target) {
        return listThreadDumps(target).stream()
                .map(
                        item -> {
                            try {
                                return convertThreadDump(item);
                            } catch (Exception e) {
                                log.error(e);
                                return null;
                            }
                        })
                .filter(Objects::nonNull)
                .toList();
    }

    private HeapDump convertHeapDump(S3Object object) throws Exception {
        String jvmId = object.key().split("/")[0];
        String uuid = object.key().split("/")[1];
        Optional<Metadata> metadata = getHeapDumpMetadata(storageKey(jvmId, uuid));
        return new HeapDump(
                jvmId,
                heapDumpDownloadUrl(jvmId, uuid),
                uuid,
                object.lastModified().getEpochSecond(),
                object.size(),
                metadata.orElse(new Metadata(Map.of())));
    }

    private ThreadDump convertThreadDump(S3Object object) throws Exception {
        String jvmId = object.key().split("/")[0];
        String uuid = object.key().split("/")[1];
        Optional<Metadata> metadata = getThreadDumpMetadata(storageKey(jvmId, uuid));
        return new ThreadDump(
                jvmId,
                threadDumpDownloadUrl(jvmId, uuid),
                uuid,
                object.lastModified().getEpochSecond(),
                object.size(),
                metadata.orElse(new Metadata(Map.of())));
    }

    public ThreadDump addThreadDump(Target target, String content) {
        String uuid = UUID.randomUUID().toString();
        log.tracev(
                "Putting Thread dump into storage with key: {0}", storageKey(target.jvmId, uuid));
        var req =
                PutObjectRequest.builder()
                        .bucket(threadDumpBucket)
                        .key(storageKey(target.jvmId, uuid))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .contentDisposition(
                                String.format(
                                        "attachment; filename=\"%s\"",
                                        generateFileName(target.jvmId, uuid, ".thread_dump")));

        switch (storageMode()) {
            case TAGGING:
                req = req.tagging(createMetadataTagging(new Metadata(Map.of())));
                break;
            case METADATA:
                req = req.metadata(Map.of());
                break;
            case BUCKET:
                try {
                    threadDumpsMetadataService
                            .get()
                            .create(target.jvmId, uuid, new Metadata(Map.of()));
                } catch (IOException ioe) {
                    log.warn(ioe);
                }
                break;
            default:
                throw new IllegalStateException();
        }

        storage.putObject(req.build(), RequestBody.fromString(content));
        return new ThreadDump(
                target.jvmId,
                threadDumpDownloadUrl(target.jvmId, uuid),
                uuid,
                clock.now().getEpochSecond(),
                content.length(),
                new Metadata(Map.of()));
    }

    public HeapDump addHeapDump(String jvmId, FileUpload heapDump, String requestId) {
        String filename = heapDump.fileName().strip();
        if (StringUtils.isBlank(filename)) {
            throw new BadRequestException();
        }
        if (!filename.endsWith(".hprof")) {
            filename = filename + ".hprof";
        }
        log.tracev("Putting Heap dump into storage with key: {0}", storageKey(jvmId, filename));
        var reqBuilder =
                PutObjectRequest.builder()
                        .bucket(heapDumpBucket)
                        .key(storageKey(jvmId, filename))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .contentDisposition(String.format("attachment; filename=\"%s\"", filename));

        switch (storageMode()) {
            case TAGGING:
                reqBuilder = reqBuilder.tagging(createMetadataTagging(new Metadata(Map.of())));
                break;
            case METADATA:
                reqBuilder = reqBuilder.metadata(Map.of());
                break;
            case BUCKET:
                try {
                    heapDumpsMetadataService.get().create(jvmId, filename, new Metadata(Map.of()));
                } catch (IOException ioe) {
                    log.warn(ioe);
                }
                break;
            default:
                throw new IllegalStateException();
        }

        storage.putObject(reqBuilder.build(), RequestBody.fromFile(heapDump.filePath()));
        var dump =
                new HeapDump(
                        jvmId,
                        heapDumpDownloadUrl(jvmId, filename),
                        filename,
                        clock.now().getEpochSecond(),
                        heapDump.filePath().toFile().length(),
                        new Metadata(Map.of()));
        var event =
                new HeapDumpEvent(
                        EventCategory.HEAP_DUMP_UPLOADED, HeapDumpEvent.Payload.of(jvmId, dump));
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(event.category().category(), event.payload()));

        try {
            // Clean up temporary files
            Files.delete(heapDump.filePath());
        } catch (IOException ioe) {
            log.warn(ioe);
        }
        return dump;
    }

    public String threadDumpDownloadUrl(String jvmId, String filename) {
        return String.format(
                "/api/beta/diagnostics/threaddump/download/%s", encodedKey(jvmId, filename));
    }

    public String heapDumpDownloadUrl(String jvmId, String filename) {
        return String.format(
                "/api/beta/diagnostics/heapdump/download/%s", encodedKey(jvmId, filename));
    }

    public String encodedKey(String jvmId, String uuid) {
        Objects.requireNonNull(jvmId);
        Objects.requireNonNull(uuid);
        return base64Url.encodeAsString((storageKey(jvmId, uuid)).getBytes(StandardCharsets.UTF_8));
    }

    public static String storageKey(String jvmId, String uuid) {
        return (jvmId + "/" + uuid).strip();
    }

    public String storageKey(Pair<String, String> pair) {
        return storageKey(pair.getKey(), pair.getValue());
    }

    public InputStream getThreadDumpStream(String jvmId, String threadDumpID) {
        return getThreadDumpStream(encodedKey(jvmId, threadDumpID));
    }

    public InputStream getThreadDumpStream(String encodedKey) {
        Pair<String, String> decodedKey = decodedKey(encodedKey);
        var key = storageKey(decodedKey);
        GetObjectRequest getRequest =
                GetObjectRequest.builder().bucket(threadDumpBucket).key(key).build();
        return storage.getObject(getRequest);
    }

    public InputStream getHeapDumpStream(String jvmId, String threadDumpID) {
        return getHeapDumpStream(encodedKey(jvmId, threadDumpID));
    }

    public InputStream getHeapDumpStream(String encodedKey) {
        Pair<String, String> decodedKey = decodedKey(encodedKey);
        var key = storageKey(decodedKey);

        GetObjectRequest getRequest =
                GetObjectRequest.builder().bucket(heapDumpBucket).key(key).build();
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

    public List<S3Object> listThreadDumps(Target target) {
        ListObjectsV2Request.Builder builder =
                ListObjectsV2Request.builder().bucket(threadDumpBucket);
        if (target != null) {
            String jvmId = target.jvmId;
            if (StringUtils.isNotBlank(jvmId)) {
                builder = builder.prefix(jvmId);
            }
        }
        return storage.listObjectsV2(builder.build()).contents();
    }

    public List<S3Object> listHeapDumps(Target target) {
        var builder = ListObjectsV2Request.builder().bucket(heapDumpBucket);
        if (target != null) {
            String jvmId = target.jvmId;
            if (StringUtils.isNotBlank(jvmId)) {
                builder = builder.prefix(jvmId);
            }
        }
        return storage.listObjectsV2(builder.build()).contents();
    }

    public Optional<Metadata> getThreadDumpMetadata(String storageKey) {
        return getObjectMetadata(storageKey, threadDumpBucket);
    }

    public Optional<Metadata> getHeapDumpMetadata(String storageKey) {
        return getObjectMetadata(storageKey, heapDumpBucket);
    }

    // Labels Handling
    public Optional<Metadata> getObjectMetadata(String storageKey, String storageBucket) {
        try {
            switch (storageMode()) {
                case TAGGING:
                    return Optional.of(
                            taggingToMetadata(
                                    storage.getObjectTagging(
                                                    GetObjectTaggingRequest.builder()
                                                            .bucket(storageBucket)
                                                            .key(storageKey)
                                                            .build())
                                            .tagSet()));
                case METADATA:
                    var resp =
                            storage.headObject(
                                    HeadObjectRequest.builder()
                                            .bucket(storageBucket)
                                            .key(storageKey)
                                            .build());
                    if (!resp.hasMetadata()) {
                        // Return an empty metadata map to support the case of
                        // dumps with no existing metadata. Cases of
                        // missing keys/other errors are distinguished by an empty optional
                        return Optional.of(new Metadata(Map.of()));
                    }
                    // Resp.metadata returns an immutable map which can break things
                    // later using computeIfAbsent, wrap it in a copy constructor.
                    return Optional.of(new Metadata(new HashMap<>(resp.metadata())));
                case BUCKET:
                    return storageBucket.equals(threadDumpBucket)
                            ? threadDumpsMetadataService.get().read(storageKey)
                            : heapDumpsMetadataService.get().read(storageKey);
                default:
                    throw new IllegalStateException();
            }
        } catch (NoSuchKeyException nske) {
            log.warn(nske);
            return Optional.empty();
        } catch (IOException ioe) {
            log.error(ioe);
            return Optional.empty();
        }
    }

    public static StorageMode storageMode(String name) {
        return Arrays.asList(StorageMode.values()).stream()
                .filter(s -> s.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow();
    }

    private String decodeBase64(String encoded) {
        return new String(base64Url.decode(encoded), StandardCharsets.UTF_8);
    }

    private StorageMode storageMode() {
        return storageMode(metadataStorageMode);
    }

    Tagging createMetadataTagging(Metadata metadata) {
        // TODO attach other metadata than labels somehow. Prefixed keys to create partitioning?
        var tags = new ArrayList<Tag>();
        tags.addAll(
                metadata.labels().entrySet().stream()
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

    private Metadata taggingToMetadata(List<Tag> tagSet) {
        // TODO parse out other metadata than labels
        var labels = new HashMap<String, String>();
        for (var tag : tagSet) {
            var key = decodeBase64(tag.key());
            var value = decodeBase64(tag.value());
            labels.put(key, value);
        }
        return new Metadata(labels);
    }

    public HeadObjectResponse assertObjectExists(
            String jvmId, String filename, String storageBucket) {
        var key = storageKey(jvmId, filename);
        var resp =
                storage.headObject(
                        HeadObjectRequest.builder().bucket(storageBucket).key(key).build());
        if (!resp.sdkHttpResponse().isSuccessful()) {
            throw new HttpException(
                    resp.sdkHttpResponse().statusCode(),
                    resp.sdkHttpResponse().statusText().orElse(""));
        }
        return resp;
    }

    public Metadata updateMetadata(
            String jvmId, String identifier, Map<String, String> metadata, String storageBucket)
            throws IOException {
        String key = storageKey(jvmId, identifier);

        Metadata updatedMetadata = new Metadata(metadata);

        switch (storageMode()) {
            case TAGGING:
                Tagging tagging = createMetadataTagging(updatedMetadata);
                storage.putObjectTagging(
                        PutObjectTaggingRequest.builder()
                                .bucket(storageBucket)
                                .key(key)
                                .tagging(tagging)
                                .build());
                break;
            case METADATA:
                throw new BadRequestException(
                        String.format(
                                "Metadata updates are not supported with server configuration"
                                        + " %s=%s",
                                ConfigProperties.STORAGE_METADATA_STORAGE_MODE,
                                metadataStorageMode));
            case BUCKET:
                if (storageBucket.equals(threadDumpBucket)) {
                    threadDumpsMetadataService.get().update(jvmId, identifier, updatedMetadata);
                } else {
                    heapDumpsMetadataService.get().update(jvmId, identifier, updatedMetadata);
                }
                break;
            default:
                throw new IllegalStateException();
        }

        return updatedMetadata;
    }

    public ThreadDump updateThreadDumpMetadata(
            String jvmId, String threadDumpId, Map<String, String> metadata) throws IOException {
        var response = assertObjectExists(jvmId, threadDumpId, threadDumpBucket);
        Metadata updatedMetadata = updateMetadata(jvmId, threadDumpId, metadata, threadDumpBucket);

        long size = response.contentLength();
        long lastModified = response.lastModified().getEpochSecond();

        ThreadDump updatedDump =
                new ThreadDump(
                        jvmId,
                        threadDumpDownloadUrl(jvmId, threadDumpId),
                        threadDumpId,
                        lastModified,
                        size,
                        updatedMetadata);

        var event =
                new ThreadDumpEvent(
                        DiagnosticsHelper.EventCategory.THREAD_DUMP_METADATA_UPDATED,
                        new ThreadDumpEvent.Payload(jvmId, updatedDump, ""));
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(event.category().category(), event.payload()));

        return updatedDump;
    }

    public HeapDump updateHeapDumpMetadata(
            String jvmId, String heapDumpId, Map<String, String> metadata) throws IOException {
        var response = assertObjectExists(jvmId, heapDumpId, heapDumpBucket);
        Metadata updatedMetadata = updateMetadata(jvmId, heapDumpId, metadata, heapDumpBucket);

        long size = response.contentLength();
        long lastModified = response.lastModified().getEpochSecond();

        HeapDump updatedDump =
                new HeapDump(
                        jvmId,
                        heapDumpDownloadUrl(jvmId, heapDumpId),
                        heapDumpId,
                        lastModified,
                        size,
                        updatedMetadata);

        var event =
                new HeapDumpEvent(
                        DiagnosticsHelper.EventCategory.HEAP_DUMP_METADATA_UPDATED,
                        new HeapDumpEvent.Payload(jvmId, updatedDump));
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(event.category().category(), event.payload()));

        return updatedDump;
    }

    public enum EventCategory {
        // ThreadDumpSuccess and HeapDumpSuccess ("CREATED") events are emitted by
        // LongRunningRequestGenerator
        DELETED(THREAD_DUMP_DELETED),
        CREATED(THREAD_DUMP_SUCCESS),
        HEAP_DUMP_DELETED(HEAP_DUMP_DELETED_NAME),
        HEAP_DUMP_UPLOADED(HEAP_DUMP_UPLOADED_NAME),
        THREAD_DUMP_METADATA_UPDATED(THREAD_DUMP_METADATA),
        HEAP_DUMP_METADATA_UPDATED(HEAP_DUMP_METADATA);

        private final String category;

        private EventCategory(String category) {
            this.category = category;
        }

        public String category() {
            return category;
        }
    }

    public record ThreadDumpEvent(EventCategory category, Payload payload) {
        public ThreadDumpEvent {
            Objects.requireNonNull(category);
            Objects.requireNonNull(payload);
        }

        public record Payload(String jvmId, ThreadDump threadDump, String jobId) {
            public Payload {
                Objects.requireNonNull(jvmId);
                Objects.requireNonNull(threadDump);
            }

            public static Payload of(ThreadDump dump, String jobId) {
                return new Payload(dump.jvmId(), dump, jobId);
            }
        }
    }

    public record HeapDumpEvent(EventCategory category, Payload payload) {
        public HeapDumpEvent {
            Objects.requireNonNull(category);
            Objects.requireNonNull(payload);
        }

        public record Payload(String jvmId, HeapDump heapDump) {
            public Payload {
                Objects.requireNonNull(jvmId);
                Objects.requireNonNull(heapDump);
            }

            public static Payload of(String jvmId, HeapDump heapDump) {
                return new Payload(jvmId, heapDump);
            }
        }
    }
}
