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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import io.cryostat.ConfigProperties;
import io.cryostat.Producers;
import io.cryostat.recordings.LongRunningRequestGenerator;
import io.cryostat.recordings.LongRunningRequestGenerator.HeapDumpRequest;
import io.cryostat.recordings.LongRunningRequestGenerator.ThreadDumpRequest;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.util.HttpMimeType;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.common.annotation.Identifier;
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
import org.apache.commons.codec.binary.Base64;
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

    @Inject
    @Identifier(Producers.BASE64_URL)
    Base64 base64Url;

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
        helper.deleteThreadDump(Target.getTargetById(targetId), threadDumpId);
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
        String key = helper.threadDumpKey(decodedKey);
        storage.headObject(HeadObjectRequest.builder().bucket(threadDumpsBucket).key(key).build())
                .sdkHttpResponse();

        if (!presignedDownloadsEnabled) {
            return ResponseBuilder.ok()
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            String.format(
                                    "attachment; filename=\"%s\"",
                                    helper.generateFileName(
                                            decodedKey.getLeft(),
                                            decodedKey.getRight(),
                                            ".thread_dump")))
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
        ResponseBuilder<Object> response =
                ResponseBuilder.create(RestResponse.Status.PERMANENT_REDIRECT);
        response =
                response.header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        String.format(
                                "attachment; filename=\"%s\"",
                                filename.isBlank()
                                        ? helper.generateFileName(
                                                decodedKey.getLeft(),
                                                decodedKey.getRight(),
                                                ".thread_dump")
                                        : new String(
                                                base64Url.decode(filename),
                                                StandardCharsets.UTF_8)));
        return response.location(uri).build();
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
        log.warnv("Initiating heap dump for target: {0}", targetId);
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
            @Parameter(required = false) @RestForm("labels") JsonObject rawLabels) {
        log.warnv("Received heap dump upload request for target: {0}", jvmId);
        jvmId = jvmId.strip();
        // Map<String, String> labels = new HashMap<>();
        // if (rawLabels != null) {
        //    rawLabels.getMap().forEach((k, v) -> labels.put(k, v.toString()));
        // }
        // labels.put("jvmId", jvmId);
        // log.warnv("Labels: " + labels.toString());
        log.warnv("Delegating to doUpload");
        doUpload(heapDump, jvmId);
    }

    @Blocking
    Map<String, Object> doUpload(FileUpload heapDump, String jvmId) {
        log.warnv("Delegating to helper.addHeapDump");
        var dump = helper.addHeapDump(Target.getTargetByJvmId(jvmId).get(), heapDump);
        return Map.of("name", dump.uuid());
        // TODO: labels support
        // "metadata",
        // dump.metadata().labels());
    }

    @Path("targets/{targetId}/heapdump")
    @RolesAllowed("read")
    @Blocking
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
        try {
            log.tracev("Deleting heap dump with ID: {0}", heapDumpId);
            helper.deleteHeapDump(heapDumpId, Target.getTargetById(targetId));
        } catch (NoSuchKeyException e) {
            throw new NotFoundException(e);
        } catch (BadRequestException e) {
            throw e;
        }
    }

    @Path("/heapdump/download/{encodedKey}")
    @RolesAllowed("read")
    @Blocking
    @GET
    public RestResponse<Object> handleHeapDumpsStorageDownload(
            @RestPath String encodedKey, @RestQuery String filename) throws URISyntaxException {
        Pair<String, String> decodedKey = helper.decodedKey(encodedKey);
        log.warnv("Handling download Request for key: {0}", decodedKey);
        log.warnv("Handling download Request for query: {0}", filename);
        String key = helper.heapDumpKey(decodedKey);
        try {
            storage.headObject(HeadObjectRequest.builder().bucket(heapDumpsBucket).key(key).build())
                    .sdkHttpResponse();
        } catch (NoSuchKeyException e) {
            log.warnv("Failed to find heap dump for key {0}", decodedKey.toString());
            throw new NotFoundException(e);
        }

        if (!presignedDownloadsEnabled) {
            log.warnv("Non presigned download, sending response");
            return ResponseBuilder.ok()
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            String.format(
                                    "attachment; filename=\"%s\"",
                                    filename.isBlank() ? decodedKey.getRight() : filename))
                    .header(HttpHeaders.CONTENT_TYPE, HttpMimeType.OCTET_STREAM.mime())
                    .entity(helper.getHeapDumpStream(encodedKey))
                    .build();
        }

        log.warnv("Handling presigned download request for {0}", decodedKey);
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
        ResponseBuilder<Object> response =
                ResponseBuilder.create(RestResponse.Status.PERMANENT_REDIRECT);
        response =
                response.header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        String.format(
                                "attachment; filename=\"%s\"",
                                filename.isBlank()
                                        ? decodedKey.getLeft()
                                        : new String(
                                                base64Url.decode(filename),
                                                StandardCharsets.UTF_8)));
        return response.location(uri).build();
    }

    public record HeapDump(String jvmId, String downloadUrl, String uuid, long lastModified) {

        public HeapDump {
            Objects.requireNonNull(jvmId);
            Objects.requireNonNull(downloadUrl);
            Objects.requireNonNull(uuid);
        }
    }

    public record ThreadDump(
            String jvmId, String downloadUrl, String threadDumpId, long lastModified, long size) {
        public ThreadDump {
            Objects.requireNonNull(jvmId);
            Objects.requireNonNull(downloadUrl);
            Objects.requireNonNull(threadDumpId);
        }
    }
}
