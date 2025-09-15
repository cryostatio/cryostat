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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import io.cryostat.ConfigProperties;
import io.cryostat.Producers;
import io.cryostat.StorageBuckets;
import io.cryostat.diagnostic.Diagnostics.HeapDump;
import io.cryostat.diagnostic.Diagnostics.ThreadDump;
import io.cryostat.libcryostat.sys.Clock;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Identifier;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
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
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

@ApplicationScoped
public class DiagnosticsHelper {

    static final String THREAD_DUMP_DELETED = "ThreadDumpDeleted";
    static final String THREAD_DUMP_REQUESTED = "ThreadDumpRequested";
    static final String DUMP_THREADS = "threadPrint";
    static final String DUMP_THREADS_TO_FIlE = "threadDumpToFile";
    static final String DUMP_HEAP = "dumpHeap";
    static final String HEAP_DUMP_REQUESTED = "HeapDumpRequested";
    static final String HEAP_DUMP_DELETED_NAME = "HeapDumpDeleted";
    static final String HEAP_DUMP_SUCCESS = "HeapDumpSuccess";
    private static final String DIAGNOSTIC_BEAN_NAME = "com.sun.management:type=DiagnosticCommand";
    private static final String HOTSPOT_DIAGNOSTIC_BEAN_NAME =
            "com.sun.management:type=HotSpotDiagnostic";

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_THREAD_DUMPS)
    String bucket;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_HEAP_DUMPS)
    String heapDumpBucket;

    private List<String> openRequests = new ArrayList<String>();

    @Inject
    @Identifier(Producers.BASE64_URL)
    Base64 base64Url;

    @Inject S3Client storage;
    @Inject Logger log;
    @Inject Clock clock;

    @Inject EventBus bus;
    @Inject TargetConnectionManager targetConnectionManager;
    @Inject StorageBuckets buckets;

    void onStart(@Observes StartupEvent evt) {
        log.tracev("Creating heap dump bucket: {0}", heapDumpBucket);
        buckets.createIfNecessary(heapDumpBucket);
        log.tracev("Creating thread dump bucket: {0}", bucket);
        buckets.createIfNecessary(bucket);
    }

    public void dumpHeap(Target target, String requestId) {
        log.tracev(
                "Heap Dump request received for Target: {0} with jobId {1}", target.id, requestId);
        openRequests.add(requestId);
        Object[] params = new Object[2];
        String[] signature = new String[] {String.class.getName(), boolean.class.getName()};
        // The agent will generate the filename on it's side
        params[0] = "";
        params[1] = false;
        // Heap Dump Retrieval is handled by a separate endpoint
        targetConnectionManager.executeConnectedTask(
                target,
                conn -> {
                    return conn.invokeMBeanOperation(
                            HOTSPOT_DIAGNOSTIC_BEAN_NAME,
                            DUMP_HEAP,
                            params,
                            signature,
                            Void.class,
                            requestId);
                });
    }

    public String generateFileName(String jvmId, String uuid, String extension) {
        Target t = Target.getTargetByJvmId(jvmId).get();
        if (Objects.isNull(t)) {
            log.errorv("jvmId {0} failed to resolve to target. Defaulting to uuid.", jvmId);
            return uuid;
        }
        return t.alias + "_" + uuid + extension;
    }

    public void deleteHeapDump(String heapDumpId, Target target)
            throws BadRequestException, NoSuchKeyException {
        String jvmId = target.jvmId;
        String key = storageKey(jvmId, heapDumpId);
        storage.headObject(HeadObjectRequest.builder().bucket(heapDumpBucket).key(key).build());
        storage.deleteObject(DeleteObjectRequest.builder().bucket(heapDumpBucket).key(key).build());
        var event =
                new HeapDumpEvent(
                        EventCategory.HEAP_DUMP_DELETED,
                        HeapDumpEvent.Payload.of(target, heapDumpId));
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(event.category().category(), event.payload()));
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
                                        String.class,
                                        requestId)));
    }

    public void deleteThreadDump(Target target, String threadDumpId) {
        if (Objects.isNull(target.jvmId)) {
            log.errorv("TargetId {0} failed to resolve to a jvmId", target.id);
            throw new IllegalArgumentException();
        } else {
            String key = storageKey(target.jvmId, threadDumpId);
            storage.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            storage.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            var event =
                    new ThreadDumpEvent(
                            EventCategory.DELETED,
                            ThreadDumpEvent.Payload.of(target, threadDumpId));
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(event.category().category(), event.payload()));
        }
    }

    public List<ThreadDump> getThreadDumps(Target target) {
        return listThreadDumps(target).stream()
                .map(
                        item -> {
                            try {
                                return convertObject(item);
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
        return new HeapDump(
                jvmId,
                heapDumpDownloadUrl(jvmId, uuid),
                uuid,
                object.lastModified().toEpochMilli(),
                object.size());
    }

    private ThreadDump convertObject(S3Object object) throws Exception {
        String jvmId = object.key().split("/")[0];
        String uuid = object.key().split("/")[1];
        return new ThreadDump(
                jvmId,
                downloadUrl(jvmId, uuid),
                uuid,
                object.lastModified().toEpochMilli(),
                object.size());
    }

    public ThreadDump addThreadDump(Target target, String content) {
        String uuid = UUID.randomUUID().toString();
        log.tracev(
                "Putting Thread dump into storage with key: {0}", storageKey(target.jvmId, uuid));
        var req =
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(storageKey(target.jvmId, uuid))
                        .contentType(MediaType.TEXT_PLAIN)
                        .build();
        storage.putObject(req, RequestBody.fromString(content));
        return new ThreadDump(
                target.jvmId,
                downloadUrl(target.jvmId, uuid),
                uuid,
                clock.now().getEpochSecond(),
                content.length());
    }

    public HeapDump addHeapDump(Target target, FileUpload heapDump, String requestId) {
        // TODO: Logic to delete the uploaded file after adding to storage
        // See #1046
        String filename = heapDump.fileName().strip();
        if (StringUtils.isBlank(filename)) {
            throw new BadRequestException();
        }
        if (!filename.endsWith(".hprof")) {
            filename = filename + ".hprof";
        }
        if (!openRequests.contains(requestId)) {
            log.warnv("Unknown upload request with job ID {0}", requestId);
            throw new IllegalArgumentException();
        }
        log.tracev(
                "Putting Heap dump into storage with key: {0}", storageKey(target.jvmId, filename));
        var reqBuilder =
                PutObjectRequest.builder()
                        .bucket(heapDumpBucket)
                        .key(storageKey(target.jvmId, filename))
                        .contentType(MediaType.TEXT_PLAIN);

        storage.putObject(reqBuilder.build(), RequestBody.fromFile(heapDump.filePath()));
        var dump =
                new HeapDump(
                        target.jvmId,
                        heapDumpDownloadUrl(target.jvmId, filename),
                        filename,
                        clock.now().getEpochSecond(),
                        heapDump.filePath().toFile().length());
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(
                        HEAP_DUMP_SUCCESS,
                        Map.of("jobId", requestId, "targetId", target.id, "filename", filename)));
        openRequests.remove(requestId);
        return dump;
    }

    public String downloadUrl(String jvmId, String filename) {
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

    public String storageKey(String jvmId, String uuid) {
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
        GetObjectRequest getRequest = GetObjectRequest.builder().bucket(bucket).key(key).build();
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
        String jvmId = target.jvmId;
        if (Objects.isNull(jvmId)) {
            throw new IllegalArgumentException();
        }
        var req = ListObjectsV2Request.builder().bucket(bucket).prefix(jvmId).build();
        return storage.listObjectsV2(req).contents();
    }

    public List<S3Object> listHeapDumps(Target target) {
        var builder = ListObjectsV2Request.builder().bucket(heapDumpBucket);
        String jvmId = target.jvmId;
        if (Objects.isNull(jvmId)) {
            throw new IllegalArgumentException();
        }
        if (StringUtils.isNotBlank(jvmId)) {
            builder = builder.prefix(jvmId);
        }
        return storage.listObjectsV2(builder.build()).contents();
    }

    public enum EventCategory {
        // ThreadDumpSuccess and HeapDumpSuccess ("CREATED") events are emitted by
        // LongRunningRequestGenerator
        DELETED(THREAD_DUMP_DELETED),
        HEAP_DUMP_DELETED(HEAP_DUMP_DELETED_NAME);

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

        public record Payload(String jvmId, String threadDumpId) {
            public Payload {
                Objects.requireNonNull(jvmId);
                Objects.requireNonNull(threadDumpId);
            }

            public static Payload of(Target target, String threadDumpId) {
                return new Payload(target.jvmId, threadDumpId);
            }
        }
    }

    public record HeapDumpEvent(EventCategory category, Payload payload) {
        public HeapDumpEvent {
            Objects.requireNonNull(category);
            Objects.requireNonNull(payload);
        }

        public record Payload(String jvmId, String heapDumpId) {
            public Payload {
                Objects.requireNonNull(jvmId);
                Objects.requireNonNull(heapDumpId);
            }

            public static Payload of(Target target, String heapDumpId) {
                return new Payload(target.jvmId, heapDumpId);
            }
        }
    }
}
