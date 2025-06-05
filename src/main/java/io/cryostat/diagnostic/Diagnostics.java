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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import io.cryostat.ConfigProperties;
import io.cryostat.Producers;
import io.cryostat.recordings.LongRunningRequestGenerator;
import io.cryostat.recordings.LongRunningRequestGenerator.ThreadDumpRequest;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.util.HttpMimeType;

import io.smallrye.common.annotation.Blocking;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
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
    @Named(Producers.BASE64_URL)
    Base64 base64Url;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_THREAD_DUMPS)
    String bucket;

    @ConfigProperty(name = ConfigProperties.STORAGE_PRESIGNED_DOWNLOADS_ENABLED)
    boolean presignedDownloadsEnabled;

    @ConfigProperty(name = ConfigProperties.STORAGE_EXT_URL)
    Optional<String> externalStorageUrl;

    @Inject EventBus bus;
    @Inject DiagnosticsHelper helper;

    @Path("targets/{targetId}/threaddump")
    @RolesAllowed("write")
    @Blocking
    @POST
    public String threadDump(
            HttpServerResponse response, @RestPath long targetId, @RestQuery String format) {
        log.trace("Creating new thread dump request for target: " + targetId);
        ThreadDumpRequest request =
                new ThreadDumpRequest(
                        UUID.randomUUID().toString(), Long.toString(targetId), format);
        response.endHandler(
                (e) -> bus.publish(LongRunningRequestGenerator.THREAD_DUMP_ADDRESS, request));
        return request.id();
    }

    @Path("targets/{targetId}/threaddump")
    @RolesAllowed("read")
    @Blocking
    @GET
    public List<ThreadDump> getThreadDumps(@RestPath long targetId) {
        log.warn("Fetching thread dumps for target: " + targetId);
        log.warn("Thread dumps: " + helper.getThreadDumps(targetId));
        log.warn("Storage bucket: " + bucket);
        return helper.getThreadDumps(targetId);
    }

    @DELETE
    @Blocking
    @Path("targets/{targetId}/threaddump/{threadDumpId}")
    @RolesAllowed("write")
    public void deleteThreadDump(@RestPath String threadDumpId) {
        try {
            log.warn("Deleting thread dump with ID: " + threadDumpId);
            storage.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(threadDumpId).build());
        } catch (NoSuchKeyException e) {
            throw new NotFoundException(e);
        }
        storage.deleteObject(
                DeleteObjectRequest.builder().bucket(bucket).key(threadDumpId).build());
    }

    @Path("/threaddump/download/{encodedKey}")
    @RolesAllowed("read")
    @Blocking
    @GET
    public RestResponse<Object> handleStorageDownload(
            @RestPath String encodedKey, @RestQuery String query) throws URISyntaxException {
        Pair<String, String> decodedKey = helper.decodedKey(encodedKey);
        var threadDumpId = decodedKey.getValue().strip();
        log.warn("Handling download Request for encodedKey: " + encodedKey);
        log.warn("Handling download Request for query: " + query);
        log.warn("Decoded key: " + decodedKey.toString());
        log.warn("UUID: " + threadDumpId);
        log.warn("Bucket: " + bucket);
        storage.headObject(HeadObjectRequest.builder().bucket(bucket).key(threadDumpId).build())
                .sdkHttpResponse();

        if (!presignedDownloadsEnabled) {
            return ResponseBuilder.ok()
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            String.format("attachment; filename=\"%s\"", decodedKey.getValue()))
                    .header(HttpHeaders.CONTENT_TYPE, HttpMimeType.OCTET_STREAM.mime())
                    .entity(helper.getThreadDumpStream(encodedKey))
                    .build();
        }

        log.tracev("Handling presigned download request for {0}", decodedKey);
        GetObjectRequest getRequest =
                GetObjectRequest.builder().bucket(bucket).key(threadDumpId).build();
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
        if (StringUtils.isNotBlank(query)) {
            response =
                    response.header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            String.format(
                                    "attachment; filename=\"%s\"",
                                    new String(base64Url.decode(query), StandardCharsets.UTF_8)));
        }
        return response.location(uri).build();
    }

    @Path("targets/{targetId}/gc")
    @RolesAllowed("write")
    @Blocking
    @POST
    public void gc(@RestPath long targetId) {
        targetConnectionManager.executeConnectedTask(
                Target.getTargetById(targetId),
                conn ->
                        conn.invokeMBeanOperation(
                                "java.lang:type=Memory", "gc", null, null, Void.class));
    }

    public record ThreadDump(String content, String jvmId, String downloadUrl, String uuid) {

        public ThreadDump {
            Objects.requireNonNull(content);
            Objects.requireNonNull(jvmId);
            Objects.requireNonNull(downloadUrl);
            Objects.requireNonNull(uuid);
        }
    }
}
