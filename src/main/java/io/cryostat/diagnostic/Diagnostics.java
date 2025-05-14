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
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import io.cryostat.ConfigProperties;
import io.cryostat.Producers;
import io.cryostat.StorageBuckets;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.util.HttpMimeType;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

@Path("/api/beta/diagnostics/targets/{targetId}")
public class Diagnostics {

    @Inject TargetConnectionManager targetConnectionManager;
    @Inject StorageBuckets buckets;
    @Inject S3Client storage;
    @Inject S3Presigner presigner;
    @Inject Logger log;

    @Inject
    @Named(Producers.BASE64_URL)
    Base64 base64Url;

    private static final String DUMP_THREADS = "threadPrint";
    private static final String DUMP_THREADS_TO_FIlE = "threadDumpToFile";
    private static final String DIAGNOSTIC_BEAN_NAME = "com.sun.management:type=DiagnosticCommand";

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_THREAD_DUMPS)
    String bucket;

    @ConfigProperty(name = ConfigProperties.STORAGE_PRESIGNED_DOWNLOADS_ENABLED)
    boolean presignedDownloadsEnabled;

    @ConfigProperty(name = ConfigProperties.STORAGE_EXT_URL)
    Optional<String> externalStorageUrl;

    void onStart(@Observes StartupEvent evt) {
        buckets.createIfNecessary(bucket);
    }

    @Path("/threaddump")
    @RolesAllowed("write")
    @Blocking
    @POST
    public void threadDump(@RestPath long targetId, @RestQuery String format) {
        if (!(format.equals(DUMP_THREADS) || format.equals(DUMP_THREADS_TO_FIlE))) {
            throw new BadRequestException();
        }
        Object[] params = new Object[1];
        String[] signature = new String[] {String[].class.getName()};
        targetConnectionManager.executeConnectedTask(
                Target.getTargetById(targetId),
                conn -> {
                    String content =
                            conn.invokeMBeanOperation(
                                    DIAGNOSTIC_BEAN_NAME,
                                    format,
                                    params,
                                    signature,
                                    String.class);
                    addThreadDump(content, Target.getTargetById(targetId).jvmId);
                    return content;
                });
    }

    @DELETE
    @Blocking
    @Path("/{threadDumpId}")
    @RolesAllowed("write")
    public void deleteThreadDump(@RestPath String threadDumpId) {
        try {
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
        Pair<String, String> decodedKey = decodedKey(encodedKey);
        String key = threadDumpKey(decodedKey);

        storage.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
                .sdkHttpResponse();

        if (!presignedDownloadsEnabled) {
            return ResponseBuilder.ok()
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            String.format("attachment; filename=\"%s\"", decodedKey.getValue()))
                    .header(HttpHeaders.CONTENT_TYPE, HttpMimeType.OCTET_STREAM.mime())
                    .entity(getThreadDumpStream(encodedKey))
                    .build();
        }

        log.tracev("Handling presigned download request for {0}", decodedKey);
        GetObjectRequest getRequest = GetObjectRequest.builder().bucket(bucket).key(key).build();
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

    public InputStream getThreadDumpStream(String jvmId, String threadDumpID) {
        return getThreadDumpStream(encodedKey(jvmId, threadDumpID));
    }

    public InputStream getThreadDumpStream(String encodedKey) {
        String key = new String(base64Url.decode(encodedKey), StandardCharsets.UTF_8);

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

    public String encodedKey(String jvmId, String filename) {
        Objects.requireNonNull(jvmId);
        Objects.requireNonNull(filename);
        return base64Url.encodeAsString(
                (threadDumpKey(jvmId, filename)).getBytes(StandardCharsets.UTF_8));
    }

    public String threadDumpKey(String jvmId, String filename) {
        return (jvmId + "/" + filename).strip();
    }

    public String threadDumpKey(Pair<String, String> pair) {
        return threadDumpKey(pair.getKey(), pair.getValue());
    }

    public ThreadDump addThreadDump(String content, String jvmId) {
        String uuid = UUID.randomUUID().toString();
        storage.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(uuid)
                        .contentType(MediaType.TEXT_PLAIN)
                        .tagging(createTagging(jvmId, uuid))
                        .build(),
                RequestBody.fromString(content));
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

    public List<S3Object> listThreadDumps(String jvmId) {
        var builder = ListObjectsV2Request.builder().bucket(bucket);
        if (StringUtils.isNotBlank(jvmId)) {
            builder = builder.prefix(jvmId);
        }
        return storage.listObjectsV2(builder.build()).contents().stream().toList();
    }

    public String downloadUrl(String jvmId, String filename) {
        return String.format("/threaddump/download/%s", encodedKey(jvmId, filename));
    }

    @Path("/gc")
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
