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

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.cryostat.ConfigProperties;
import io.cryostat.Producers;
import io.cryostat.StorageBuckets;
import io.cryostat.libcryostat.sys.Clock;
import io.cryostat.recordings.ActiveRecording.Listener.ArchivedRecordingEvent;
import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.targets.Target;
import io.cryostat.util.HttpMimeType;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.mutiny.core.eventbus.EventBus;
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
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

@Path("")
public class ArchivedRecordings {

    @Inject EventBus bus;
    @Inject Clock clock;
    @Inject S3Client storage;
    @Inject StorageBuckets storageBuckets;
    @Inject S3Presigner presigner;
    @Inject RecordingHelper recordingHelper;
    @Inject Logger logger;

    @Inject
    @Named(Producers.BASE64_URL)
    Base64 base64Url;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_ARCHIVES)
    String bucket;

    @ConfigProperty(name = ConfigProperties.STORAGE_PRESIGNED_DOWNLOADS_ENABLED)
    boolean presignedDownloadsEnabled;

    @ConfigProperty(name = ConfigProperties.STORAGE_EXT_URL)
    Optional<String> externalStorageUrl;

    void onStart(@Observes StartupEvent evt) {
        storageBuckets.createIfNecessary(bucket);
    }

    @GET
    @Blocking
    @Path("/api/v4/recordings")
    @RolesAllowed("read")
    public List<ArchivedRecording> listArchivesV4() {
        return recordingHelper.listArchivedRecordings();
    }

    @POST
    @Blocking
    @Path("/api/v4/recordings")
    @RolesAllowed("write")
    public Map<String, Object> upload(
            @RestForm("recording") FileUpload recording, @RestForm("labels") JsonObject rawLabels)
            throws Exception {
        Map<String, String> labels = new HashMap<>();
        if (rawLabels != null) {
            rawLabels.getMap().forEach((k, v) -> labels.put(k, v.toString()));
        }
        labels.put("jvmId", "uploads");
        labels.put("connectUrl", "uploads");
        Metadata metadata = new Metadata(labels);
        return doUpload(recording, metadata, "uploads");
    }

    @POST
    @Blocking
    @Path("/api/beta/recordings/{jvmId}")
    @RolesAllowed("write")
    public void agentPush(
            @RestPath String jvmId,
            @RestForm("recording") FileUpload recording,
            @RestForm("labels") JsonObject rawLabels,
            @RestForm("maxFiles") int maxFiles)
            throws Exception {
        jvmId = jvmId.strip();
        int max = Integer.MAX_VALUE;
        if (maxFiles > 0) {
            max = maxFiles;
        }
        Map<String, String> labels = new HashMap<>();
        if (rawLabels != null) {
            rawLabels.getMap().forEach((k, v) -> labels.put(k, v.toString()));
        }
        labels.put("jvmId", jvmId);
        Metadata metadata = new Metadata(labels);
        logger.tracev(
                "recording:{0}, labels:{1}, maxFiles:{2}", recording.fileName(), labels, maxFiles);
        doUpload(recording, metadata, jvmId);
        var objs = new ArrayList<S3Object>();
        recordingHelper.listArchivedRecordingObjects(jvmId).iterator().forEachRemaining(objs::add);
        var toRemove =
                objs.stream()
                        .sorted((a, b) -> b.lastModified().compareTo(a.lastModified()))
                        .skip(max)
                        .toList();
        if (toRemove.isEmpty()) {
            return;
        }
        logger.tracev("Removing {0}", toRemove);

        // FIXME this notification should be emitted in the deletion operation stream so that there
        // is one notification per deleted object
        var target = Target.getTargetByJvmId(jvmId);
        var event =
                new ArchivedRecordingEvent(
                        ActiveRecordings.RecordingEventCategory.ARCHIVED_DELETED,
                        ArchivedRecordingEvent.Payload.of(
                                target.map(t -> t.connectUrl).orElse(null),
                                new ArchivedRecording(
                                        jvmId,
                                        recording.fileName(),
                                        recordingHelper.downloadUrl(jvmId, recording.fileName()),
                                        recordingHelper.reportUrl(jvmId, recording.fileName()),
                                        metadata,
                                        0,
                                        clock.getMonotonicTime())));
        bus.publish(event.category().category(), event.payload().recording());
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(event.category().category(), event.payload()));
        storage.deleteObjects(
                        DeleteObjectsRequest.builder()
                                .bucket(bucket)
                                .delete(
                                        Delete.builder()
                                                .objects(
                                                        toRemove.stream()
                                                                .map(S3Object::key)
                                                                .map(
                                                                        k ->
                                                                                ObjectIdentifier
                                                                                        .builder()
                                                                                        .key(k)
                                                                                        .build())
                                                                .toList())
                                                .build())
                                .build())
                .errors()
                .forEach(
                        err -> {
                            logger.errorv(
                                    "Deletion failure: {0} due to {1}", err.key(), err.message());
                        });
    }

    @GET
    @Blocking
    @Path("/api/beta/recordings/{jvmId}")
    @RolesAllowed("read")
    public List<ArchivedRecording> agentGet(@RestPath String jvmId) {
        var result = new ArrayList<ArchivedRecording>();
        recordingHelper
                .listArchivedRecordingObjects(jvmId)
                .forEach(
                        item -> {
                            String objectName = item.key().strip();
                            String filename = objectName.split("/")[1];
                            Metadata metadata =
                                    recordingHelper
                                            .getArchivedRecordingMetadata(jvmId, filename)
                                            .orElseGet(Metadata::empty);
                            result.add(
                                    new ArchivedRecording(
                                            jvmId,
                                            filename,
                                            recordingHelper.downloadUrl(jvmId, filename),
                                            recordingHelper.reportUrl(jvmId, filename),
                                            metadata,
                                            item.size(),
                                            item.lastModified().getEpochSecond()));
                        });
        return result;
    }

    @DELETE
    @Blocking
    @Path("/api/beta/recordings/{connectUrl}/{filename}")
    @RolesAllowed("write")
    public void agentDelete(@RestPath String connectUrl, @RestPath String filename)
            throws Exception {
        String jvmId;
        if ("uploads".equals(connectUrl)) {
            jvmId = "uploads";
        } else {
            jvmId = Target.getTargetByConnectUrl(URI.create(connectUrl)).jvmId;
        }
        if (!recordingHelper.listArchivedRecordingObjects(jvmId).stream()
                .map(item -> item.key().strip().split("/")[1])
                .anyMatch(fn -> Objects.equals(fn, filename))) {
            throw new NotFoundException();
        }
        recordingHelper.deleteArchivedRecording(jvmId, filename);
    }

    @Blocking
    Map<String, Object> doUpload(FileUpload recording, Metadata metadata, String jvmId) {
        logger.tracev(
                "Upload: {0} {1} {2} {3}",
                recording.name(), recording.fileName(), recording.filePath(), metadata.labels());
        String filename = recording.fileName().strip();
        if (StringUtils.isBlank(filename)) {
            throw new BadRequestException();
        }
        if (!filename.endsWith(".jfr")) {
            filename = filename + ".jfr";
        }
        Map<String, String> labels = new HashMap<>(metadata.labels());
        labels.put("jvmId", jvmId);
        String key = recordingHelper.archivedRecordingKey(jvmId, filename);
        storage.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(RecordingHelper.JFR_MIME)
                        .tagging(recordingHelper.createMetadataTagging(new Metadata(labels)))
                        .build(),
                RequestBody.fromFile(recording.filePath()));
        logger.trace("Upload complete");

        var target = Target.getTargetByJvmId(jvmId);
        var event =
                new ArchivedRecordingEvent(
                        ActiveRecordings.RecordingEventCategory.ARCHIVED_CREATED,
                        ArchivedRecordingEvent.Payload.of(
                                target.map(t -> t.connectUrl).orElse(null),
                                new ArchivedRecording(
                                        jvmId,
                                        filename,
                                        recordingHelper.downloadUrl(jvmId, filename),
                                        recordingHelper.reportUrl(jvmId, filename),
                                        metadata,
                                        recording.size(),
                                        clock.getMonotonicTime())));
        bus.publish(event.category().category(), event.payload().recording());
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(event.category().category(), event.payload()));

        return Map.of("name", filename, "metadata", Map.of("labels", metadata.labels()));
    }

    @DELETE
    @Blocking
    @Path("/api/v4/recordings/{filename}")
    @RolesAllowed("write")
    public void delete(@RestPath String filename) throws Exception {
        var key = String.format("%s/%s", "uploads", filename);
        try {
            storage.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException e) {
            throw new NotFoundException(e);
        }
        // TODO scan all prefixes for matching filename? This is an old v1 API problem.
        storage.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @GET
    @Blocking
    @Path("/api/beta/fs/recordings")
    @RolesAllowed("read")
    public Collection<ArchivedRecordingDirectory> listFsArchives() {
        var map = new HashMap<String, ArchivedRecordingDirectory>();
        recordingHelper
                .listArchivedRecordingObjects()
                .forEach(
                        item -> {
                            String path = item.key().strip();
                            String[] parts = path.split("/");
                            String jvmId = parts[0];
                            String filename = parts[1];

                            Metadata metadata =
                                    recordingHelper
                                            .getArchivedRecordingMetadata(jvmId, filename)
                                            .orElseGet(Metadata::empty);

                            String connectUrl =
                                    metadata.labels().computeIfAbsent("connectUrl", k -> jvmId);
                            var dir =
                                    map.computeIfAbsent(
                                            jvmId,
                                            id ->
                                                    new ArchivedRecordingDirectory(
                                                            connectUrl, id, new ArrayList<>()));
                            dir.recordings.add(
                                    new ArchivedRecording(
                                            jvmId,
                                            filename,
                                            recordingHelper.downloadUrl(jvmId, filename),
                                            recordingHelper.reportUrl(jvmId, filename),
                                            metadata,
                                            item.size(),
                                            item.lastModified().getEpochSecond()));
                        });
        return map.values();
    }

    @GET
    @Blocking
    @Path("/api/beta/fs/recordings/{jvmId}")
    @RolesAllowed("read")
    public Collection<ArchivedRecordingDirectory> listFsArchives(@RestPath String jvmId) {
        var map = new HashMap<String, ArchivedRecordingDirectory>();
        recordingHelper
                .listArchivedRecordingObjects(jvmId)
                .forEach(
                        item -> {
                            String filename = item.key().strip().replace(jvmId + "/", "");

                            Metadata metadata =
                                    recordingHelper
                                            .getArchivedRecordingMetadata(jvmId, filename)
                                            .orElseGet(Metadata::empty);

                            String connectUrl =
                                    metadata.labels().computeIfAbsent("connectUrl", k -> jvmId);
                            var dir =
                                    map.computeIfAbsent(
                                            jvmId,
                                            id ->
                                                    new ArchivedRecordingDirectory(
                                                            connectUrl, id, new ArrayList<>()));
                            dir.recordings.add(
                                    new ArchivedRecording(
                                            jvmId,
                                            filename,
                                            recordingHelper.downloadUrl(jvmId, filename),
                                            recordingHelper.reportUrl(jvmId, filename),
                                            metadata,
                                            item.size(),
                                            item.lastModified().getEpochSecond()));
                        });
        return map.values();
    }

    @DELETE
    @Blocking
    @Path("/api/beta/fs/recordings/{jvmId}/{filename}")
    @RolesAllowed("write")
    public void deleteArchivedRecording(@RestPath String jvmId, @RestPath String filename)
            throws Exception {
        logger.tracev("Handling archived recording deletion: {0} / {1}", jvmId, filename);
        var metadata =
                recordingHelper
                        .getArchivedRecordingMetadata(jvmId, filename)
                        .orElseGet(Metadata::empty);

        var connectUrl =
                Target.getTargetByJvmId(jvmId)
                        .map(t -> t.connectUrl)
                        .map(c -> c.toString())
                        .filter(StringUtils::isNotBlank)
                        .orElseGet(
                                () ->
                                        metadata.labels()
                                                .computeIfAbsent(
                                                        "connectUrl", k -> "lost-" + jvmId));
        logger.tracev(
                "Archived recording from connectUrl \"{0}\" has metadata: {1}",
                connectUrl, metadata);
        logger.tracev(
                "Sending S3 deletion request for {0} {1}",
                bucket, recordingHelper.archivedRecordingKey(jvmId, filename));
        var resp =
                storage.deleteObject(
                        DeleteObjectRequest.builder()
                                .bucket(bucket)
                                .key(recordingHelper.archivedRecordingKey(jvmId, filename))
                                .build());
        logger.tracev(
                "Got SDK response {0} {1}",
                resp.sdkHttpResponse().statusCode(), resp.sdkHttpResponse().statusText());
        if (resp.sdkHttpResponse().isSuccessful()) {
            var event =
                    new ArchivedRecordingEvent(
                            ActiveRecordings.RecordingEventCategory.ARCHIVED_DELETED,
                            ArchivedRecordingEvent.Payload.of(
                                    URI.create(connectUrl),
                                    new ArchivedRecording(
                                            jvmId,
                                            filename,
                                            recordingHelper.downloadUrl(jvmId, filename),
                                            recordingHelper.reportUrl(jvmId, filename),
                                            metadata,
                                            0 /* filesize */,
                                            clock.getMonotonicTime())));
            bus.publish(event.category().category(), event.payload().recording());
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(event.category().category(), event.payload()));
        } else {
            throw new HttpException(
                    resp.sdkHttpResponse().statusCode(),
                    resp.sdkHttpResponse().statusText().orElse(""));
        }
    }

    @POST
    @Blocking
    @Path("/api/v4/grafana/{encodedKey}")
    @RolesAllowed("write")
    public Uni<String> uploadArchivedToGrafana(@RestPath String encodedKey) throws Exception {
        var key = recordingHelper.decodedKey(encodedKey);
        var found =
                recordingHelper.listArchivedRecordingObjects().stream()
                        .anyMatch(
                                o ->
                                        Objects.equals(
                                                o.key(),
                                                recordingHelper.archivedRecordingKey(key)));
        if (!found) {
            throw new NotFoundException();
        }
        return recordingHelper.uploadToJFRDatasource(key);
    }

    @GET
    @Blocking
    @Path("/api/v4/download/{encodedKey}")
    @RolesAllowed("read")
    public RestResponse<Object> handleStorageDownload(
            @RestPath String encodedKey, @RestQuery String f) throws URISyntaxException {
        Pair<String, String> pair = recordingHelper.decodedKey(encodedKey);

        if (!presignedDownloadsEnabled) {
            return ResponseBuilder.ok()
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            String.format("attachment; filename=\"%s\"", pair.getValue()))
                    .header(HttpHeaders.CONTENT_TYPE, HttpMimeType.OCTET_STREAM.mime())
                    .entity(recordingHelper.getArchivedRecordingStream(encodedKey))
                    .build();
        }

        logger.tracev("Handling presigned download request for {0}", pair);
        GetObjectRequest getRequest =
                GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(recordingHelper.archivedRecordingKey(pair))
                        .build();
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
        if (StringUtils.isNotBlank(f)) {
            response =
                    response.header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            String.format(
                                    "attachment; filename=\"%s\"",
                                    new String(base64Url.decode(f), StandardCharsets.UTF_8)));
        }
        return response.location(uri).build();
    }

    // TODO include jvmId and filename
    public record ArchivedRecording(
            String jvmId,
            String name,
            String downloadUrl,
            String reportUrl,
            Metadata metadata,
            long size,
            long archivedTime) {
        public ArchivedRecording {
            Objects.requireNonNull(jvmId);
            Objects.requireNonNull(name);
            Objects.requireNonNull(downloadUrl);
            Objects.requireNonNull(reportUrl);
            Objects.requireNonNull(metadata);
        }
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public record ArchivedRecordingDirectory(
            String connectUrl, String jvmId, List<ArchivedRecording> recordings) {
        public ArchivedRecordingDirectory {
            Objects.requireNonNull(connectUrl);
            Objects.requireNonNull(jvmId);
            if (recordings == null) {
                recordings = Collections.emptyList();
            }
        }
    }
}
