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

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import io.cryostat.ConfigProperties;
import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.recordings.LongRunningRequestGenerator;
import io.cryostat.recordings.LongRunningRequestGenerator.HeapDumpRequest;
import io.cryostat.recordings.LongRunningRequestGenerator.ThreadDumpRequest;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.util.HttpMimeType;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

@Path("/api/beta/diagnostics/")
public class Diagnostics {

    @Inject TargetConnectionManager targetConnectionManager;
    @Inject S3Client storage;
    @Inject S3Presigner presigner;
    @Inject Logger log;
    @Inject LongRunningRequestGenerator generator;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_THREAD_DUMPS)
    String threadDumpsBucket;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_HEAP_DUMPS)
    String heapDumpsBucket;

    @ConfigProperty(name = ConfigProperties.STORAGE_PRESIGNED_DOWNLOADS_ENABLED)
    boolean presignedDownloadsEnabled;

    @ConfigProperty(name = ConfigProperties.STORAGE_EXT_URL)
    Optional<String> externalStorageUrl;

    @Inject EventBus bus;
    @Inject DiagnosticsHelper helper;

    @Path("fs/threaddumps")
    @RolesAllowed("read")
    @GET
    public Collection<ArchivedThreadDumpDirectory> listFsThreadDumps() {
        var map = new HashMap<String, ArchivedThreadDumpDirectory>();
        helper.listThreadDumpObjects()
                .forEach(
                        item -> {
                            String path = item.key().strip();
                            String[] parts = path.split("/");
                            String jvmId = parts[0];
                            String filename = parts[1];

                            Metadata metadata =
                                    helper.getThreadDumpMetadata(path).orElseGet(Metadata::empty);
                            var dir =
                                    map.computeIfAbsent(
                                            jvmId,
                                            id ->
                                                    new ArchivedThreadDumpDirectory(
                                                            id, new ArrayList<>()));
                            dir.threadDumps.add(
                                    new ThreadDump(
                                            jvmId,
                                            helper.threadDumpDownloadUrl(jvmId, filename),
                                            filename,
                                            item.lastModified().getEpochSecond(),
                                            item.size(),
                                            metadata));
                        });
        return map.values();
    }

    @Path("targets/{targetId}/threaddump")
    @RolesAllowed("write")
    @POST
    public String threadDump(
            HttpServerResponse response,
            @RestPath long targetId,
            @QueryParam("format") @DefaultValue(DiagnosticsHelper.DUMP_THREADS) String format) {
        log.tracev("Creating new thread dump request for target: {0}", targetId);
        ThreadDumpRequest request =
                new ThreadDumpRequest(UUID.randomUUID().toString(), targetId, format);
        response.endHandler(
                (e) -> bus.publish(LongRunningRequestGenerator.THREAD_DUMP_ADDRESS, request));
        return request.id();
    }

    @Path("targets/{targetId}/threaddump")
    @RolesAllowed("read")
    @Blocking
    @Transactional
    @GET
    public List<ThreadDump> getThreadDumps(@RestPath long targetId) {
        log.tracev("Fetching thread dumps for target: {0}", targetId);
        return helper.getThreadDumps(Target.getTargetById(targetId));
    }

    @DELETE
    @Blocking
    @Transactional
    @Path("targets/{targetId}/threaddump/{threadDumpId}")
    @RolesAllowed("write")
    public void deleteThreadDump(@RestPath long targetId, @RestPath String threadDumpId) {
        log.tracev("Deleting thread dump with ID: {0}", threadDumpId);
        helper.deleteThreadDump(Target.getTargetById(targetId).jvmId, threadDumpId);
    }

    @DELETE
    @Blocking
    @Path("fs/threaddumps/{jvmId}/{threadDumpId}")
    @RolesAllowed("write")
    public void deleteThreadDump(@RestPath String jvmId, @RestPath String threadDumpId) {
        log.tracev("Deleting thread dump with ID: {0}", threadDumpId);
        helper.deleteThreadDump(jvmId, threadDumpId);
    }

    @Path("/threaddump/download/{encodedKey}")
    @RolesAllowed("read")
    @Blocking
    @GET
    public RestResponse<Object> handleThreadDumpsStorageDownload(
            @RestPath String encodedKey, @RestQuery String filename) throws URISyntaxException {
        Pair<String, String> decodedKey = helper.decodedKey(encodedKey);
        log.tracev("Handling download Request for key: {0}", decodedKey);
        log.tracev("Handling download Request for query: {0}", filename);
        String key = helper.storageKey(decodedKey);
        storage.headObject(HeadObjectRequest.builder().bucket(threadDumpsBucket).key(key).build())
                .sdkHttpResponse();
        String contentName =
                StringUtils.isNotBlank(filename)
                        ? filename
                        : helper.generateFileName(
                                decodedKey.getLeft(), decodedKey.getRight(), ".thread_dump");

        if (!presignedDownloadsEnabled) {
            return ResponseBuilder.ok()
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            String.format("attachment; filename=\"%s\"", contentName))
                    .header(HttpHeaders.CONTENT_TYPE, HttpMimeType.OCTET_STREAM.mime())
                    .entity(helper.getThreadDumpStream(encodedKey))
                    .build();
        }

        log.tracev("Handling presigned download request for {0}", decodedKey);
        GetObjectRequest getRequest =
                GetObjectRequest.builder().bucket(threadDumpsBucket).key(key).build();
        GetObjectPresignRequest presignRequest =
                GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(1))
                        .getObjectRequest(getRequest)
                        .build();
        PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
        URI uri = presignedRequest.url().toURI();
        if (externalStorageUrl.isPresent()) {
            String extUrl = externalStorageUrl.get();
            if (StringUtils.isNotBlank(extUrl)) {
                URI extUri = new URI(extUrl);
                uri =
                        new URI(
                                extUri.getScheme(),
                                extUri.getAuthority(),
                                URI.create(String.format("%s/%s", extUri.getPath(), uri.getPath()))
                                        .normalize()
                                        .getPath(),
                                uri.getQuery(),
                                uri.getFragment());
            }
        }
        return ResponseBuilder.create(RestResponse.Status.PERMANENT_REDIRECT)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        String.format("attachment; filename=\"%s\"", contentName))
                .location(uri)
                .build();
    }

    @Path("targets/{targetId}/gc")
    @RolesAllowed("write")
    @Blocking
    @Transactional
    @POST
    @Operation(
            summary = "Initiate a garbage collection on the specified target",
            description =
                    """
                    Request the remote target to perform a garbage collection. The target JVM is free to ignore this
                    request. This is generally equivalent to a System.gc() call made within the target JVM.
                    """)
    public void gc(@RestPath long targetId) {
        targetConnectionManager.executeConnectedTask(
                Target.getTargetById(targetId),
                conn ->
                        conn.invokeMBeanOperation(
                                "java.lang:type=Memory", "gc", null, null, Void.class));
    }

    @Path("fs/heapdumps")
    @RolesAllowed("read")
    @GET
    public Collection<ArchivedHeapDumpDirectory> listFsHeapDumps() {
        var map = new HashMap<String, ArchivedHeapDumpDirectory>();
        helper.listHeapDumpObjects()
                .forEach(
                        item -> {
                            String path = item.key().strip();
                            String[] parts = path.split("/");
                            String jvmId = parts[0];
                            String filename = parts[1];

                            Metadata metadata =
                                    helper.getHeapDumpMetadata(path).orElseGet(Metadata::empty);
                            var dir =
                                    map.computeIfAbsent(
                                            jvmId,
                                            id ->
                                                    new ArchivedHeapDumpDirectory(
                                                            id, new ArrayList<>()));
                            dir.heapDumps.add(
                                    new HeapDump(
                                            jvmId,
                                            helper.heapDumpDownloadUrl(jvmId, filename),
                                            filename,
                                            item.lastModified().getEpochSecond(),
                                            item.size(),
                                            metadata));
                        });
        return map.values();
    }

    @Path("targets/{targetId}/heapdump")
    @RolesAllowed("write")
    @POST
    @Operation(
            summary = "Initiates a heap dump on the specified target",
            description =
                    """
                    Request the remote target to perform a heap dump.
                    """)
    public String heapDump(HttpServerResponse response, @RestPath long targetId) {
        log.tracev("Initiating heap dump for target: {0}", targetId);
        if (!Target.getTargetById(targetId).isAgent()) {
            // While we can trigger a heap dump in a JMX target, without the agent
            // we can't retrieve it. We should fail here.
            throw new BadRequestException("Target is not an agent connection.");
        }
        HeapDumpRequest request = new HeapDumpRequest(UUID.randomUUID().toString(), targetId);
        response.endHandler(
                (e) -> bus.publish(LongRunningRequestGenerator.HEAP_DUMP_REQUEST_ADDRESS, request));
        return request.id();
    }

    @Path("heapdump/upload/{jvmId}")
    @RolesAllowed("read")
    @Blocking
    @POST
    public void uploadHeapDump(
            @RestPath String jvmId,
            @Parameter(required = true) @RestForm("heapDump") FileUpload heapDump,
            @Parameter(required = true) @RestForm("jobId") String jobId,
            @Parameter(required = false) @RestForm("labels") JsonObject rawLabels) {
        log.tracev(
                "Received heap dump upload request for target: {0} with job ID {1}", jvmId, jobId);
        jvmId = jvmId.strip();
        doUpload(heapDump, jvmId, jobId);
    }

    @Blocking
    Map<String, Object> doUpload(FileUpload heapDump, String jvmId, String jobId) {
        var dump = helper.addHeapDump(jvmId, heapDump, jobId);
        return Map.of("name", dump.heapDumpId());
    }

    @Path("targets/{targetId}/heapdump")
    @RolesAllowed("read")
    @Blocking
    @Transactional
    @GET
    public List<HeapDump> getHeapDumps(@RestPath long targetId) {
        log.tracev("Fetching heap dumps for target: {0}", targetId);
        return helper.getHeapDumps(Target.getTargetById(targetId));
    }

    @DELETE
    @Blocking
    @Path("targets/{targetId}/heapdump/{heapDumpId}")
    @RolesAllowed("write")
    public void deleteHeapDump(@RestPath String heapDumpId, @RestPath long targetId) {
        log.tracev("Deleting heap dump with ID: {0}", heapDumpId);
        helper.deleteHeapDump(
                QuarkusTransaction.joiningExisting()
                        .call(() -> Target.getTargetById(targetId).jvmId),
                heapDumpId);
    }

    @DELETE
    @Blocking
    @Path("fs/heapdumps/{jvmId}/{heapDumpId}")
    @RolesAllowed("write")
    public void deleteHeapDumpByPath(@RestPath String jvmId, @RestPath String heapDumpId) {
        log.tracev("Deleting heap dump with ID: {0}", heapDumpId);
        helper.deleteHeapDump(jvmId, heapDumpId);
    }

    @Path("/heapdump/download/{encodedKey}")
    @RolesAllowed("read")
    @Blocking
    @GET
    public RestResponse<Object> handleHeapDumpsStorageDownload(
            @RestPath String encodedKey, @RestQuery String filename) throws URISyntaxException {
        Pair<String, String> decodedKey = helper.decodedKey(encodedKey);
        log.tracev("Handling download Request for key: {0}", decodedKey);
        log.tracev("Handling download Request for query: {0}", filename);
        String key = helper.storageKey(decodedKey);
        try {
            storage.headObject(HeadObjectRequest.builder().bucket(heapDumpsBucket).key(key).build())
                    .sdkHttpResponse();
        } catch (NoSuchKeyException e) {
            log.warnv("Failed to find heap dump for key {0}", decodedKey.toString());
            throw new NotFoundException(e);
        }
        String contentName = StringUtils.isNotBlank(filename) ? filename : decodedKey.getRight();

        if (!presignedDownloadsEnabled) {
            log.tracev("Non presigned download, sending response");
            return ResponseBuilder.ok()
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            String.format("attachment; filename=\"%s\"", contentName))
                    .header(HttpHeaders.CONTENT_TYPE, HttpMimeType.OCTET_STREAM.mime())
                    .entity(helper.getHeapDumpStream(encodedKey))
                    .build();
        }

        log.tracev("Handling presigned download request for {0}", decodedKey);
        GetObjectRequest getRequest =
                GetObjectRequest.builder().bucket(heapDumpsBucket).key(key).build();
        GetObjectPresignRequest presignRequest =
                GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(1))
                        .getObjectRequest(getRequest)
                        .build();
        PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
        URI uri = presignedRequest.url().toURI();
        if (externalStorageUrl.isPresent()) {
            String extUrl = externalStorageUrl.get();
            if (StringUtils.isNotBlank(extUrl)) {
                URI extUri = new URI(extUrl);
                uri =
                        new URI(
                                extUri.getScheme(),
                                extUri.getAuthority(),
                                URI.create(String.format("%s/%s", extUri.getPath(), uri.getPath()))
                                        .normalize()
                                        .getPath(),
                                uri.getQuery(),
                                uri.getFragment());
            }
        }
        return ResponseBuilder.create(RestResponse.Status.PERMANENT_REDIRECT)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        String.format("attachment; filename=\"%s\"", contentName))
                .location(uri)
                .build();
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public record ArchivedHeapDumpDirectory(String jvmId, List<HeapDump> heapDumps) {
        public ArchivedHeapDumpDirectory {
            Objects.requireNonNull(jvmId);
            Objects.requireNonNull(heapDumps);
        }
    }

    public record HeapDump(
            String jvmId,
            String downloadUrl,
            String heapDumpId,
            long lastModified,
            long size,
            Metadata metadata) {

        public HeapDump {
            Objects.requireNonNull(jvmId);
            Objects.requireNonNull(downloadUrl);
            Objects.requireNonNull(heapDumpId);
            Objects.requireNonNull(metadata);
        }
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public record ArchivedThreadDumpDirectory(String jvmId, List<ThreadDump> threadDumps) {
        public ArchivedThreadDumpDirectory {
            Objects.requireNonNull(jvmId);
            Objects.requireNonNull(threadDumps);
        }
    }

    public record ThreadDump(
            String jvmId,
            String downloadUrl,
            String threadDumpId,
            long lastModified,
            long size,
            Metadata metadata) {
        public ThreadDump {
            Objects.requireNonNull(jvmId);
            Objects.requireNonNull(downloadUrl);
            Objects.requireNonNull(threadDumpId);
            Objects.requireNonNull(metadata);
        }
    }
}
