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

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_HEAP_DUMPS)
    String heapDumpBucket;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_THREAD_DUMPS)
    String bucket;

    @Inject
    @Identifier(Producers.BASE64_URL)
    Base64 base64Url;

    @Inject S3Client storage;
    @Inject Logger log;
    @Inject Clock clock;

    private static final String DUMP_THREADS = "threadPrint";
    private static final String DUMP_THREADS_TO_FIlE = "threadDumpToFile";
    private static final String DIAGNOSTIC_BEAN_NAME = "com.sun.management:type=DiagnosticCommand";
    static final String THREAD_DUMP_REQUESTED = "ThreadDumpRequested";
    static final String THREAD_DUMP_DELETED = "ThreadDumpDeleted";
    
    private static final String DUMP_HEAP = "dumpHeap";
    private static final String HOTSPOT_DIAGNOSTIC_BEAN_NAME =
            "com.sun.management:type=HotSpotDiagnostic";
    static final String HEAP_DUMP_REQUESTED = "HeapDumpRequested";
    static final String HEAP_DUMP_DELETED = "HeapDumpDeleted";
    static final String HEAP_DUMP_UPLOADED = "HeapDumpUploaded";

    @Inject EventBus bus;
    @Inject TargetConnectionManager targetConnectionManager;
    @Inject StorageBuckets buckets;

    void onStart(@Observes StartupEvent evt) {
        log.tracev("Creating heap dump bucket: {0}", heapDumpBucket);
        buckets.createIfNecessary(heapDumpBucket);
        log.tracev("Creating thread dump bucket: {0}", bucket);
        buckets.createIfNecessary(bucket);
    }

    public void dumpHeap(long targetId) {
        log.warnv("Heap Dump request received for Target: {0}", targetId);
        Object[] params = new Object[2];
        String[] signature = new String[] {String.class.getName(), boolean.class.getName()};
        params[0] =
                generateFileName(
                        Target.getTargetById(targetId).jvmId,
                        UUID.randomUUID().toString(),
                        ".hprof");
        params[1] = false;
        log.warnv("Generated filename: {0}", params[1]);
        // Heap Dump Retrieval is handled by a separate endpoint
        targetConnectionManager.executeConnectedTask(
                Target.getTargetById(targetId),
                conn ->
                        conn.invokeMBeanOperation(
                                HOTSPOT_DIAGNOSTIC_BEAN_NAME,
                                DUMP_HEAP,
                                params,
                                signature,
                                String.class));
    }

    public String generateFileName(String jvmId, String uuid, String extension) {
        Target t = Target.getTargetByJvmId(jvmId).get();
        if (Objects.isNull(t)) {
            log.errorv("jvmId {0} failed to resolve to target. Defaulting to uuid.", jvmId);
            return uuid;
        }
        return t.alias + "_" + uuid + extension;
    }

    public void deleteHeapDump(String heapDumpID, long targetId)
            throws BadRequestException, NoSuchKeyException {
        String jvmId = Target.getTargetById(targetId).jvmId;
        String key = heapDumpKey(jvmId, heapDumpID);
        storage.headObject(HeadObjectRequest.builder().bucket(heapDumpBucket).key(key).build());
        storage.deleteObject(DeleteObjectRequest.builder().bucket(heapDumpBucket).key(key).build());
    }

    public List<HeapDump> getHeapDumps(long targetId) {
        return listHeapDumps(targetId).stream()
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

    public void deleteThreadDump(String threadDumpID, long targetId)
            throws BadRequestException, NoSuchKeyException {
        String jvmId = Target.getTargetById(targetId).jvmId;
        String key = threadDumpKey(jvmId, threadDumpID);
        if (Objects.isNull(jvmId)) {
            log.errorv("TargetId {0} failed to resolve to a jvmId", targetId);
            throw new BadRequestException();
        } else {
            storage.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            storage.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        }
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
                object.lastModified().toEpochMilli());
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

    public ThreadDump addThreadDump(String content, String jvmId) {
        String uuid = UUID.randomUUID().toString();
        log.tracev("Putting Thread dump into storage with key: {0}", threadDumpKey(jvmId, uuid));
        var reqBuilder =
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(threadDumpKey(jvmId, uuid))
                        .contentType(MediaType.TEXT_PLAIN);
        storage.putObject(reqBuilder.build(), RequestBody.fromString(content));
        return new ThreadDump(
                jvmId,
                downloadUrl(jvmId, uuid),
                uuid,
                clock.now().getEpochSecond(),
                content.length());
    }

    public HeapDump addHeapDump(String jvmId, FileUpload heapDump) {
        String filename = heapDump.fileName().strip();
        if (StringUtils.isBlank(filename)) {
            throw new BadRequestException();
        }
        if (!filename.endsWith(".hprof")) {
            filename = filename + ".hprof";
        }
        log.warnv("Putting Heap dump into storage with key: {0}", heapDumpKey(jvmId, filename));
        var reqBuilder =
                PutObjectRequest.builder()
                        .bucket(heapDumpBucket)
                        .key(heapDumpKey(jvmId, filename))
                        // FIXME: Is this correct?
                        .contentType(MediaType.TEXT_PLAIN);

        storage.putObject(reqBuilder.build(), RequestBody.fromFile(heapDump.filePath()));
        var dump =
                new HeapDump(
                        jvmId,
                        heapDumpDownloadUrl(jvmId, filename),
                        filename,
                        clock.now().getEpochSecond());
        var target = Target.getTargetByJvmId(jvmId);
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(
                        HEAP_DUMP_UPLOADED,
                        Map.of("targetId", target.get().id, "filename", filename)));
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

    /*                                     */
    /*         */

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
        var key = threadDumpKey(decodedKey);

        GetObjectRequest getRequest = GetObjectRequest.builder().bucket(bucket).key(key).build();
        return storage.getObject(getRequest);
    }

    public String heapDumpKey(String jvmId, String uuid) {
        return (jvmId + "/" + uuid).strip();
    }

    public String heapDumpKey(Pair<String, String> pair) {
        return heapDumpKey(pair.getKey(), pair.getValue());
    }

    public InputStream getHeapDumpStream(String jvmId, String threadDumpID) {
        return getHeapDumpStream(encodedKey(jvmId, threadDumpID));
    }

    public InputStream getHeapDumpStream(String encodedKey) {
        Pair<String, String> decodedKey = decodedKey(encodedKey);
        var key = heapDumpKey(decodedKey);

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

    public List<S3Object> listThreadDumps(long targetId) {
        var builder = ListObjectsV2Request.builder().bucket(bucket);
        String jvmId = Target.getTargetById(targetId).jvmId;
        if (Objects.isNull(jvmId)) {
            log.errorv("TargetId {0} failed to resolve to a jvmId", targetId);
        }
        if (StringUtils.isNotBlank(jvmId)) {
            builder = builder.prefix(jvmId);
        }
        return storage.listObjectsV2(builder.build()).contents();
    }

    public List<S3Object> listHeapDumps(long targetId) {
        var builder = ListObjectsV2Request.builder().bucket(heapDumpBucket);
        String jvmId = Target.getTargetById(targetId).jvmId;
        if (Objects.isNull(jvmId)) {
            log.errorv("TargetId {0} failed to resolve to a jvmId", targetId);
        }
        if (StringUtils.isNotBlank(jvmId)) {
            builder = builder.prefix(jvmId);
        }
        return storage.listObjectsV2(builder.build()).contents();
    }
}
