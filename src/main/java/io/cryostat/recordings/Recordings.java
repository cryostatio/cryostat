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

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.common.unit.IOptionDescriptor;
import org.openjdk.jmc.flightrecorder.configuration.recording.RecordingOptionsBuilder;
import org.openjdk.jmc.rjmx.services.jfr.FlightRecorderException;
import org.openjdk.jmc.rjmx.services.jfr.IFlightRecorderService;
import org.openjdk.jmc.rjmx.services.jfr.IRecordingDescriptor;

import io.cryostat.ConfigProperties;
import io.cryostat.core.net.JFRConnection;
import io.cryostat.core.sys.Clock;
import io.cryostat.core.templates.TemplateType;
import io.cryostat.recordings.ActiveRecording.Listener.RecordingEvent;
import io.cryostat.recordings.RecordingHelper.RecordingReplace;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.util.HttpStatusCodeIdentifier;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import com.arjuna.ats.jta.exceptions.NotImplementedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import jdk.jfr.RecordingState;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.validator.routines.UrlValidator;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

@Path("")
public class Recordings {

    private static final String JFR_MIME = "application/jfr";

    @Inject Logger logger;
    @Inject TargetConnectionManager connectionManager;
    @Inject EventBus bus;
    @Inject RecordingOptionsBuilderFactory recordingOptionsBuilderFactory;
    @Inject EventOptionsBuilder.Factory eventOptionsBuilderFactory;
    @Inject Clock clock;
    @Inject S3Client storage;
    @Inject S3Presigner presigner;
    @Inject RemoteRecordingInputStreamFactory remoteRecordingStreamFactory;
    @Inject ScheduledExecutorService scheduler;
    @Inject ObjectMapper mapper;
    @Inject RecordingHelper recordingHelper;
    private final Base64 base64Url = new Base64(0, null, true);

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_ARCHIVES)
    String archiveBucket;

    @ConfigProperty(name = ConfigProperties.GRAFANA_DATASOURCE_URL)
    Optional<String> grafanaDatasourceURL;

    void onStart(@Observes StartupEvent evt) {
        boolean exists = false;
        try {
            exists =
                    HttpStatusCodeIdentifier.isSuccessCode(
                            storage.headBucket(
                                            HeadBucketRequest.builder()
                                                    .bucket(archiveBucket)
                                                    .build())
                                    .sdkHttpResponse()
                                    .statusCode());
        } catch (Exception e) {
            logger.info(e);
        }
        if (!exists) {
            try {
                storage.createBucket(CreateBucketRequest.builder().bucket(archiveBucket).build());
            } catch (Exception e) {
                logger.error(e);
            }
        }
    }

    @GET
    @Path("/api/v1/recordings")
    @RolesAllowed("read")
    public List<ArchivedRecording> listArchivesV1() {
        var result = new ArrayList<ArchivedRecording>();
        storage.listObjectsV2(ListObjectsV2Request.builder().bucket(archiveBucket).build())
                .contents()
                .forEach(
                        item -> {
                            String path = item.key().strip();
                            String[] parts = path.split("/");
                            String jvmId = parts[0];
                            String filename = parts[1];
                            Metadata metadata = getArchivedRecordingMetadata(jvmId, filename);
                            result.add(
                                    new ArchivedRecording(
                                            filename,
                                            "/api/v3/download/"
                                                    + base64Url.encodeAsString(
                                                            (jvmId + "/" + filename)
                                                                    .getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8)),
                                            "TODO",
                                            metadata,
                                            item.size(),
                                            item.lastModified().getEpochSecond()));
                        });
        return result;
    }

    @POST
    @Path("/api/v1/recordings")
    @RolesAllowed("write")
    public Map<String, Object> upload(
            @RestForm("recording") FileUpload recording, @RestForm("labels") JsonObject rawLabels)
            throws Exception {
        Map<String, String> labels = new HashMap<>();
        if (rawLabels != null) {
            rawLabels.getMap().forEach((k, v) -> labels.put(k, v.toString()));
        }
        Metadata metadata = new Metadata(labels);
        return doUpload(recording, metadata, "uploads");
    }

    @POST
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
        Metadata metadata = new Metadata(labels);
        logger.infov(
                "recording:{0}, labels:{1}, maxFiles:{2}", recording.fileName(), labels, maxFiles);
        doUpload(recording, metadata, jvmId);
        var objs = new ArrayList<S3Object>();
        storage.listObjectsV2(
                        ListObjectsV2Request.builder().bucket(archiveBucket).prefix(jvmId).build())
                .contents()
                .iterator()
                .forEachRemaining(objs::add);
        var toRemove =
                objs.stream()
                        .sorted((a, b) -> b.lastModified().compareTo(a.lastModified()))
                        .skip(max)
                        .toList();
        if (toRemove.isEmpty()) {
            return;
        }
        logger.infov("Removing {0}", toRemove);
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(
                        "ArchivedRecordingDeleted",
                        new RecordingEvent(
                                URI.create(jvmId),
                                new ArchivedRecording(
                                        recording.fileName(),
                                        "/api/v3/download/"
                                                + base64Url.encodeAsString(
                                                        (jvmId + "/" + recording.fileName().strip())
                                                                .getBytes(StandardCharsets.UTF_8)),
                                        "TODO",
                                        metadata,
                                        0 /*filesize*/,
                                        clock.getMonotonicTime()))));
        storage.deleteObjects(
                        DeleteObjectsRequest.builder()
                                .bucket(archiveBucket)
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
        storage.listObjectsV2(
                        ListObjectsV2Request.builder().bucket(archiveBucket).prefix(jvmId).build())
                .contents()
                .forEach(
                        item -> {
                            String objectName = item.key().strip();
                            String filename = objectName.split("/")[1];
                            Metadata metadata = getArchivedRecordingMetadata(jvmId, filename);
                            result.add(
                                    new ArchivedRecording(
                                            filename,
                                            "/api/v3/download"
                                                    + base64Url.encodeAsString(
                                                            (jvmId + "/" + filename)
                                                                    .getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8)),
                                            "TODO",
                                            metadata,
                                            item.size(),
                                            item.lastModified().getEpochSecond()));
                        });
        return result;
    }

    @DELETE
    @Path("/api/beta/recordings/{jvmId}/{filename}")
    @RolesAllowed("write")
    public void agentDelete(
            @RestPath String jvmId,
            @RestPath String filename,
            @RestForm("recording") FileUpload recording,
            @RestForm("labels") JsonObject rawLabels)
            throws Exception {
        storage.deleteObject(
                DeleteObjectRequest.builder()
                        .bucket(archiveBucket)
                        .key(String.format("%s/%s", jvmId, filename))
                        .build());
    }

    @Blocking
    Map<String, Object> doUpload(FileUpload recording, Metadata metadata, String jvmId) {
        logger.infov(
                "Upload: {0} {1} {2} {3}",
                recording.name(), recording.fileName(), recording.filePath(), metadata.labels);
        String filename = recording.fileName().strip();
        if (StringUtils.isBlank(filename)) {
            throw new BadRequestException();
        }
        if (!filename.endsWith(".jfr")) {
            filename = filename + ".jfr";
        }
        String key = String.format("%s/%s", jvmId, filename);
        storage.putObject(
                PutObjectRequest.builder()
                        .bucket(archiveBucket)
                        .key(key)
                        .contentType(JFR_MIME)
                        .tagging(createMetadataTagging(metadata))
                        .build(),
                RequestBody.fromFile(recording.filePath()));
        logger.info("Upload complete");
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(
                        "ArchivedRecordingCreated",
                        new RecordingEvent(
                                URI.create(jvmId),
                                new ArchivedRecording(
                                        filename,
                                        "/api/v3/download/"
                                                + base64Url.encodeAsString(
                                                        key.getBytes(StandardCharsets.UTF_8)),
                                        "TODO",
                                        metadata,
                                        0 /*filesize*/,
                                        clock.getMonotonicTime()))));

        return Map.of("name", filename, "metadata", Map.of("labels", metadata.labels));
    }

    @DELETE
    @Path("/api/v1/recordings/{filename}")
    @RolesAllowed("write")
    @Blocking
    public void delete(@RestPath String filename) throws Exception {
        storage.deleteObject(
                DeleteObjectRequest.builder()
                        .bucket(archiveBucket)
                        .key(String.format("%s/%s", "uploads", filename))
                        .build());
    }

    @GET
    @Path("/api/beta/fs/recordings")
    @RolesAllowed("read")
    public Collection<ArchivedRecordingDirectory> listFsArchives() {
        var map = new HashMap<String, ArchivedRecordingDirectory>();
        storage.listObjectsV2(ListObjectsV2Request.builder().bucket(archiveBucket).build())
                .contents()
                .forEach(
                        item -> {
                            String path = item.key().strip();
                            String[] parts = path.split("/");
                            String jvmId = parts[0];
                            String filename = parts[1];

                            Metadata metadata = getArchivedRecordingMetadata(jvmId, filename);

                            String connectUrl =
                                    metadata.labels.computeIfAbsent(
                                            "connectUrl", k -> "lost-" + jvmId);
                            var dir =
                                    map.computeIfAbsent(
                                            jvmId,
                                            id ->
                                                    new ArchivedRecordingDirectory(
                                                            connectUrl, id, new ArrayList<>()));
                            dir.recordings.add(
                                    new ArchivedRecording(
                                            filename,
                                            "/api/v3/download/"
                                                    + base64Url.encodeAsString(
                                                            path.getBytes(StandardCharsets.UTF_8)),
                                            "TODO",
                                            metadata,
                                            item.size(),
                                            item.lastModified().getEpochSecond()));
                        });
        return map.values();
    }

    @GET
    @Path("/api/v3/targets/{id}/recordings")
    @RolesAllowed("read")
    public List<LinkedRecordingDescriptor> listForTarget(@RestPath long id) throws Exception {
        Target target = Target.find("id", id).singleResult();
        return target.activeRecordings.stream().map(ActiveRecording::toExternalForm).toList();
    }

    @GET
    @Path("/api/v1/targets/{connectUrl}/recordings")
    @RolesAllowed("read")
    public Response listForTargetByUrl(@RestPath URI connectUrl) throws Exception {
        Target target = Target.getTargetByConnectUrl(connectUrl);
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(URI.create(String.format("/api/v3/targets/%d/recordings", target.id)))
                .build();
    }

    @PATCH
    @Transactional
    @Path("/api/v3/targets/{targetId}/recordings/{remoteId}")
    @RolesAllowed("write")
    public String patch(@RestPath long targetId, @RestPath long remoteId, String body)
            throws Exception {
        Target target = Target.find("id", targetId).singleResult();
        Optional<ActiveRecording> recording =
                target.activeRecordings.stream()
                        .filter(rec -> rec.remoteId == remoteId)
                        .findFirst();
        if (!recording.isPresent()) {
            throw new NotFoundException();
        }
        ActiveRecording activeRecording = recording.get();
        switch (body.toLowerCase()) {
            case "stop":
                activeRecording.state = RecordingState.STOPPED;
                activeRecording.persist();
                return null;
            case "save":
                return recordingHelper.saveRecording(target, activeRecording);
            default:
                throw new BadRequestException(body);
        }
    }

    @PATCH
    @Transactional
    @Path("/api/v1/targets/{connectUrl}/recordings/{recordingName}")
    @RolesAllowed("write")
    public Response patchV1(@RestPath URI connectUrl, @RestPath String recordingName, String body)
            throws Exception {
        Target target = Target.getTargetByConnectUrl(connectUrl);
        Optional<IRecordingDescriptor> recording =
                connectionManager.executeConnectedTask(
                        target, conn -> RecordingHelper.getDescriptorByName(conn, recordingName));
        if (recording.isEmpty()) {
            throw new NotFoundException();
        }
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(
                        URI.create(
                                String.format(
                                        "/api/v3/targets/%d/recordings/%s",
                                        target.id, recording.get().getId())))
                .build();
    }

    @Transactional
    @POST
    @Path("/api/v3/targets/{id}/recordings")
    @RolesAllowed("write")
    public LinkedRecordingDescriptor createRecording(
            @RestPath long id,
            @RestForm String recordingName,
            @RestForm String events,
            @RestForm Optional<String> replace,
            @RestForm Optional<Long> duration,
            @RestForm Optional<Boolean> toDisk,
            @RestForm Optional<Long> maxAge,
            @RestForm Optional<Long> maxSize,
            @RestForm("metadata") Optional<String> rawMetadata,
            @RestForm Optional<Boolean> archiveOnStop)
            throws Exception {
        if (StringUtils.isBlank(recordingName)) {
            throw new BadRequestException("\"recordingName\" form parameter must be provided");
        }
        if (StringUtils.isBlank(events)) {
            throw new BadRequestException("\"events\" form parameter must be provided");
        }

        Target target = Target.find("id", id).singleResult();

        Pair<String, TemplateType> template = recordingHelper.parseEventSpecifierToTemplate(events);

        LinkedRecordingDescriptor descriptor =
                connectionManager.executeConnectedTask(
                        target,
                        connection -> {
                            RecordingOptionsBuilder optionsBuilder =
                                    recordingOptionsBuilderFactory
                                            .create(connection.getService())
                                            .name(recordingName);
                            if (duration.isPresent()) {
                                optionsBuilder.duration(duration.get());
                            }
                            if (toDisk.isPresent()) {
                                optionsBuilder.toDisk(toDisk.get());
                            }
                            if (maxAge.isPresent()) {
                                optionsBuilder.maxAge(maxAge.get());
                            }
                            if (maxSize.isPresent()) {
                                optionsBuilder.maxSize(maxSize.get());
                            }
                            Map<String, String> labels = new HashMap<>();
                            if (rawMetadata.isPresent()) {
                                labels.putAll(
                                        mapper.readValue(rawMetadata.get(), Metadata.class).labels);
                            }
                            IConstrainedMap<String> recordingOptions = optionsBuilder.build();
                            return recordingHelper.startRecording(
                                    target,
                                    recordingOptions,
                                    template.getLeft(),
                                    template.getRight(),
                                    new Metadata(labels),
                                    archiveOnStop.orElse(false),
                                    RecordingReplace.fromString(replace.orElse("always")),
                                    connection);
                        });

        ActiveRecording recording = ActiveRecording.from(target, descriptor);
        recording.persist();
        target.activeRecordings.add(recording);
        target.persist();

        if (recording.duration > 0) {
            scheduler.schedule(
                    () -> stopRecording(target.id, recording.remoteId, archiveOnStop.orElse(false)),
                    recording.duration,
                    TimeUnit.MILLISECONDS);
        }

        return descriptor;
    }

    @Transactional
    void stopRecording(long targetId, long recordingId, boolean archive) {
        try {
            ActiveRecording recording = ActiveRecording.find("id", recordingId).singleResult();
            recording.state = RecordingState.STOPPED;
            recording.persist();
            if (archive) {
                recordingHelper.saveRecording(
                        Target.find("id", targetId).singleResult(), recording);
            }
        } catch (Exception e) {
            logger.error("couldn't update recording", e);
        }
    }

    @Transactional
    @POST
    @Path("/api/v1/targets/{connectUrl}/recordings")
    @RolesAllowed("write")
    public Response createRecordingV1(
            @RestPath URI connectUrl,
            @RestForm String recordingName,
            @RestForm String events,
            @RestForm Optional<Boolean> restart,
            @RestForm Optional<Long> duration,
            @RestForm Optional<Boolean> toDisk,
            @RestForm Optional<Long> maxAge,
            @RestForm Optional<Long> maxSize,
            @RestForm Optional<String> metadata,
            @RestForm Optional<Boolean> archiveOnStop)
            throws Exception {
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(
                        URI.create(
                                String.format(
                                        "/api/v3/targets/%d/recordings",
                                        Target.getTargetByConnectUrl(connectUrl).id)))
                .build();
    }

    @Transactional
    @DELETE
    @Path("/api/v1/targets/{connectUrl}/recordings/{recordingName}")
    @RolesAllowed("write")
    public Response deleteRecordingV1(@RestPath URI connectUrl, @RestPath String recordingName)
            throws Exception {
        if (StringUtils.isBlank(recordingName)) {
            throw new BadRequestException("\"recordingName\" form parameter must be provided");
        }
        Target target = Target.getTargetByConnectUrl(connectUrl);
        long remoteId =
                target.activeRecordings.stream()
                        .filter(r -> Objects.equals(r.name, recordingName))
                        .findFirst()
                        .map(r -> r.remoteId)
                        .orElseThrow(() -> new NotFoundException());
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(
                        URI.create(
                                String.format(
                                        "/api/v3/targets/%d/recordings/%d", target.id, remoteId)))
                .build();
    }

    @Transactional
    @DELETE
    @Path("/api/v3/targets/{targetId}/recordings/{remoteId}")
    @RolesAllowed("write")
    public void deleteRecording(@RestPath long targetId, @RestPath long remoteId) throws Exception {
        Target target = Target.find("id", targetId).singleResult();
        target.activeRecordings.stream()
                .filter(r -> r.remoteId == remoteId)
                .findFirst()
                .ifPresentOrElse(
                        ActiveRecording::delete,
                        () -> {
                            throw new NotFoundException();
                        });
    }

    @Blocking
    @DELETE
    @Path("/api/beta/fs/recordings/{jvmId}/{filename}")
    @RolesAllowed("write")
    public void deleteArchivedRecording(@RestPath String jvmId, @RestPath String filename)
            throws Exception {

        var metadata = getArchivedRecordingMetadata(jvmId, filename);

        String connectUrl = metadata.labels.computeIfAbsent("connectUrl", k -> "lost-" + jvmId);
        storage.deleteObject(
                DeleteObjectRequest.builder()
                        .bucket(archiveBucket)
                        .key(String.format("%s/%s", jvmId, filename))
                        .build());
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(
                        "ArchivedRecordingDeleted",
                        new RecordingEvent(URI.create(connectUrl), Map.of("name", filename))));
    }

    static void safeCloseRecording(JFRConnection conn, IRecordingDescriptor rec, Logger logger) {
        try {
            conn.getService().close(rec);
        } catch (FlightRecorderException e) {
            logger.error("Failed to stop remote recording", e);
        } catch (Exception e) {
            logger.error("Unexpected exception", e);
        }
    }

    @POST
    @Path("/api/v1/targets/{connectUrl}/recordings/{recordingName}/upload")
    @RolesAllowed("write")
    public Response uploadToGrafanaV1(@RestPath URI connectUrl, @RestPath String recordingName) {
        Target target = Target.getTargetByConnectUrl(connectUrl);
        long remoteId =
                target.activeRecordings.stream()
                        .filter(r -> Objects.equals(r.name, recordingName))
                        .findFirst()
                        .map(r -> r.remoteId)
                        .orElseThrow(() -> new NotFoundException());
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(
                        URI.create(
                                String.format(
                                        "/api/v3/targets/%d/recordings/%d/upload",
                                        target.id, remoteId)))
                .build();
    }

    @POST
    @Path("/api/v3/targets/{targetId}/recordings/{remoteId}/upload")
    @RolesAllowed("write")
    @Blocking
    public Response uploadToGrafana(@RestPath long id, @RestPath long remoteId) throws Exception {
        try {
            URL uploadUrl =
                    new URL(
                            grafanaDatasourceURL.orElseThrow(
                                    () ->
                                            new InternalServerErrorException(
                                                    "GRAFANA_DATASOURCE_URL environment variable"
                                                            + " does not exist")));
            boolean isValidUploadUrl =
                    new UrlValidator(UrlValidator.ALLOW_LOCAL_URLS).isValid(uploadUrl.toString());
            if (!isValidUploadUrl) {
                throw new NotImplementedException(
                        String.format(
                                "$%s=%s is an invalid datasource URL",
                                ConfigProperties.GRAFANA_DATASOURCE_URL, uploadUrl.toString()));
            }

            return recordingHelper.doPost(id, remoteId, uploadUrl);
        } catch (MalformedURLException e) {
            throw new NotImplementedException(e);
        }
    }

    @GET
    @Path("/api/v1/targets/{connectUrl}/recordingOptions")
    @RolesAllowed("read")
    public Response getRecordingOptionsV1(@RestPath URI connectUrl) throws Exception {
        Target target = Target.getTargetByConnectUrl(connectUrl);
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(
                        URI.create(String.format("/api/v3/targets/%d/recordingOptions", target.id)))
                .build();
    }

    @GET
    @Path("/api/v3/targets/{id}/recordingOptions")
    @RolesAllowed("read")
    public Map<String, Object> getRecordingOptions(@RestPath long id) throws Exception {
        Target target = Target.find("id", id).singleResult();
        return connectionManager.executeConnectedTask(
                target,
                connection -> {
                    RecordingOptionsBuilder builder =
                            recordingOptionsBuilderFactory.create(connection.getService());
                    return getRecordingOptions(connection.getService(), builder);
                });
    }

    @GET
    @Path("/api/v3/download/{encodedKey}")
    @RolesAllowed("read")
    public Response redirectPresignedDownload(@RestPath String encodedKey)
            throws URISyntaxException {
        String key = new String(base64Url.decode(encodedKey), StandardCharsets.UTF_8);
        logger.infov("Handling presigned download request for {0}", key);
        GetObjectRequest getRequest =
                GetObjectRequest.builder().bucket(archiveBucket).key(key).build();
        GetObjectPresignRequest presignRequest =
                GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(1))
                        .getObjectRequest(getRequest)
                        .build();
        PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(presignedRequest.url().toURI())
                .build();
    }

    private Tagging createMetadataTagging(Metadata metadata) {
        // TODO attach other metadata than labels somehow. Prefixed keys to create partitioning?
        return Tagging.builder()
                .tagSet(
                        metadata.labels.entrySet().stream()
                                .map(
                                        e ->
                                                Tag.builder()
                                                        .key(
                                                                base64Url.encodeAsString(
                                                                        e.getKey().getBytes()))
                                                        // e.getKey())
                                                        .value(
                                                                base64Url.encodeAsString(
                                                                        e.getValue().getBytes()))
                                                        .build())
                                .toList())
                .build();
    }

    private Metadata taggingToMetadata(List<Tag> tagSet) {
        // TODO parse out other metadata than labels
        return new Metadata(
                tagSet.stream()
                        .map(
                                tag ->
                                        Pair.of(
                                                new String(
                                                        base64Url.decode(tag.key()),
                                                        StandardCharsets.UTF_8),
                                                // tag.key(),
                                                new String(
                                                        base64Url.decode(tag.value()),
                                                        StandardCharsets.UTF_8)))
                        .collect(Collectors.toMap(Pair::getKey, Pair::getValue)));
    }

    private Metadata getArchivedRecordingMetadata(String jvmId, String filename) {
        return taggingToMetadata(
                storage.getObjectTagging(
                                GetObjectTaggingRequest.builder()
                                        .bucket(archiveBucket)
                                        .key(String.format("%s/%s", jvmId, filename))
                                        .build())
                        .tagSet());
    }

    private static Map<String, Object> getRecordingOptions(
            IFlightRecorderService service, RecordingOptionsBuilder builder) throws Exception {
        IConstrainedMap<String> recordingOptions = builder.build();

        Map<String, IOptionDescriptor<?>> targetRecordingOptions =
                service.getAvailableRecordingOptions();

        Map<String, Object> map = new HashMap<String, Object>();

        if (recordingOptions.get("toDisk") != null) {
            map.put("toDisk", recordingOptions.get("toDisk"));
        } else {
            map.put("toDisk", targetRecordingOptions.get("disk").getDefault());
        }

        map.put("maxAge", getNumericOption("maxAge", recordingOptions, targetRecordingOptions));
        map.put("maxSize", getNumericOption("maxSize", recordingOptions, targetRecordingOptions));

        return map;
    }

    private static Long getNumericOption(
            String name,
            IConstrainedMap<String> defaultOptions,
            Map<String, IOptionDescriptor<?>> targetOptions) {
        Object value;

        if (defaultOptions.get(name) != null) {
            value = defaultOptions.get(name);
        } else {
            value = targetOptions.get(name).getDefault();
        }

        if (value instanceof Number) {
            return Long.valueOf(((Number) value).longValue());
        }
        return null;
    }

    public record LinkedRecordingDescriptor(
            long id,
            RecordingState state,
            long duration,
            long startTime,
            boolean continuous,
            boolean toDisk,
            long maxSize,
            long maxAge,
            String name,
            String downloadUrl,
            String reportUrl,
            Metadata metadata) {
        public static LinkedRecordingDescriptor from(ActiveRecording recording) {
            return new LinkedRecordingDescriptor(
                    recording.remoteId,
                    recording.state,
                    recording.duration,
                    recording.startTime,
                    recording.continuous,
                    recording.toDisk,
                    recording.maxSize,
                    recording.maxAge,
                    recording.name,
                    "TODO",
                    "TODO",
                    recording.metadata);
        }
    }

    public record ArchivedRecording(
            String name,
            String downloadUrl,
            String reportUrl,
            Metadata metadata,
            long size,
            long archivedTime) {}

    public record ArchivedRecordingDirectory(
            String connectUrl, String jvmId, List<ArchivedRecording> recordings) {}

    public record Metadata(Map<String, String> labels) {}
}
