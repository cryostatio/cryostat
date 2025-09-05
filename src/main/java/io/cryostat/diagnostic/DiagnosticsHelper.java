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
import java.util.Objects;
import java.util.UUID;

import io.cryostat.ConfigProperties;
import io.cryostat.Producers;
import io.cryostat.StorageBuckets;
import io.cryostat.diagnostic.Diagnostics.ThreadDump;
import io.cryostat.libcryostat.sys.Clock;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Identifier;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

@ApplicationScoped
public class DiagnosticsHelper {

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

    @Inject EventBus bus;
    @Inject TargetConnectionManager targetConnectionManager;
    @Inject StorageBuckets buckets;

    void onStart(@Observes StartupEvent evt) {
        log.tracev("Creating thread dump bucket: {0}", bucket);
        buckets.createIfNecessary(bucket);
    }

    public ThreadDump dumpThreads(Target target, String format) {
        if (!(format.equals(DUMP_THREADS) || format.equals(DUMP_THREADS_TO_FIlE))) {
            throw new IllegalArgumentException();
        }
        log.tracev(
                "Thread Dump request received for Target: {0} with format: {1}", target.id, format);
        Object[] params = new Object[1];
        String[] signature = new String[] {String[].class.getName()};
        return targetConnectionManager.executeConnectedTask(
                target,
                conn -> {
                    String content =
                            conn.invokeMBeanOperation(
                                    DIAGNOSTIC_BEAN_NAME, format, params, signature, String.class);
                    return addThreadDump(target, content);
                });
    }

    public void deleteThreadDump(Target target, String threadDumpID) {
        if (Objects.isNull(target.jvmId)) {
            log.errorv("TargetId {0} failed to resolve to a jvmId", target.id);
            throw new IllegalArgumentException();
        } else {
            String key = threadDumpKey(target.jvmId, threadDumpID);
            storage.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            storage.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
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
                "Putting Thread dump into storage with key: {0}",
                threadDumpKey(target.jvmId, uuid));
        var req =
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(threadDumpKey(target.jvmId, uuid))
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
        var key = threadDumpKey(decodedKey);
        var getRequest = GetObjectRequest.builder().bucket(bucket).key(key).build();
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
}
