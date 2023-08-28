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

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.common.unit.IOptionDescriptor;
import org.openjdk.jmc.flightrecorder.configuration.recording.RecordingOptionsBuilder;
import org.openjdk.jmc.rjmx.services.jfr.FlightRecorderException;
import org.openjdk.jmc.rjmx.services.jfr.IFlightRecorderService;
import org.openjdk.jmc.rjmx.services.jfr.IRecordingDescriptor;

import io.cryostat.ConfigProperties;
import io.cryostat.Producers;
import io.cryostat.V2Response;
import io.cryostat.core.RecordingOptionsCustomizer;
import io.cryostat.core.net.JFRConnection;
import io.cryostat.core.sys.Clock;
import io.cryostat.core.templates.TemplateType;
import io.cryostat.recordings.ActiveRecording.Listener.RecordingEvent;
import io.cryostat.recordings.RecordingHelper.RecordingReplace;
import io.cryostat.recordings.RecordingHelper.SnapshotCreationException;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.util.HttpStatusCodeIdentifier;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import com.arjuna.ats.jta.exceptions.NotImplementedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.HttpHeaders;
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
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

@Path("")
public class Recordings {

    @Inject Logger logger;
    @Inject TargetConnectionManager connectionManager;
    @Inject EventBus bus;
    @Inject RecordingOptionsBuilderFactory recordingOptionsBuilderFactory;
    @Inject RecordingOptionsCustomizer recordingOptionsCustomizer;
    @Inject EventOptionsBuilder.Factory eventOptionsBuilderFactory;
    @Inject Clock clock;
    @Inject S3Client storage;
    @Inject S3Presigner presigner;
    @Inject RemoteRecordingInputStreamFactory remoteRecordingStreamFactory;
    @Inject ScheduledExecutorService scheduler;
    @Inject ObjectMapper mapper;
    @Inject RecordingHelper recordingHelper;

    @Inject
    @Named(Producers.BASE64_URL)
    Base64 base64Url;

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
    @Blocking
    @Path("/api/v1/recordings")
    @RolesAllowed("read")
    public List<ArchivedRecording> listArchivesV1() {
        var result = new ArrayList<ArchivedRecording>();
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
                            result.add(
                                    new ArchivedRecording(
                                            filename,
                                            recordingHelper.downloadUrl(jvmId, filename),
                                            recordingHelper.reportUrl(jvmId, filename),
                                            metadata,
                                            item.size(),
                                            item.lastModified().getEpochSecond()));
                        });
        return result;
    }

    @POST
    @Blocking
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
        logger.infov(
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
        logger.infov("Removing {0}", toRemove);
        // FIXME this notification should be emitted in the deletion operation stream so that there
        // is one notification per deleted object
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(
                        "ArchivedRecordingDeleted",
                        new RecordingEvent(
                                URI.create(jvmId),
                                new ArchivedRecording(
                                        recording.fileName(),
                                        recordingHelper.downloadUrl(jvmId, recording.fileName()),
                                        recordingHelper.reportUrl(jvmId, recording.fileName()),
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
    public void agentDelete(
            @RestPath String connectUrl,
            @RestPath String filename,
            @RestForm("recording") FileUpload recording,
            @RestForm("labels") JsonObject rawLabels)
            throws Exception {
        var target = Target.getTargetByConnectUrl(URI.create(connectUrl));
        var resp =
                storage.deleteObject(
                        DeleteObjectRequest.builder()
                                .bucket(archiveBucket)
                                .key(recordingHelper.archivedRecordingKey(target.jvmId, filename))
                                .build());
        if (resp.sdkHttpResponse().isSuccessful()) {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(
                            "ArchivedRecordingDeleted",
                            new RecordingEvent(URI.create(connectUrl), Map.of("name", filename))));
        } else {
            throw new HttpException(
                    resp.sdkHttpResponse().statusCode(),
                    resp.sdkHttpResponse().statusText().orElse(""));
        }
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
        Map<String, String> labels = new HashMap<>(metadata.labels);
        labels.put("jvmId", jvmId);
        String key = recordingHelper.archivedRecordingKey(jvmId, filename);
        storage.putObject(
                PutObjectRequest.builder()
                        .bucket(archiveBucket)
                        .key(key)
                        .contentType(RecordingHelper.JFR_MIME)
                        .tagging(recordingHelper.createMetadataTagging(new Metadata(labels)))
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
                                        recordingHelper.downloadUrl(jvmId, filename),
                                        recordingHelper.reportUrl(jvmId, filename),
                                        metadata,
                                        0 /*filesize*/,
                                        clock.getMonotonicTime()))));

        return Map.of("name", filename, "metadata", Map.of("labels", metadata.labels));
    }

    @DELETE
    @Blocking
    @Path("/api/v1/recordings/{filename}")
    @RolesAllowed("write")
    public void delete(@RestPath String filename) throws Exception {
        // TODO scan all prefixes for matching filename? This is an old v1 API problem.
        storage.deleteObject(
                DeleteObjectRequest.builder()
                        .bucket(archiveBucket)
                        .key(String.format("%s/%s", "uploads", filename))
                        .build());
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
                                    metadata.labels.computeIfAbsent("connectUrl", k -> jvmId);
                            var dir =
                                    map.computeIfAbsent(
                                            jvmId,
                                            id ->
                                                    new ArchivedRecordingDirectory(
                                                            connectUrl, id, new ArrayList<>()));
                            dir.recordings.add(
                                    new ArchivedRecording(
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
    @Path("/api/v3/targets/{id}/recordings")
    @RolesAllowed("read")
    public List<LinkedRecordingDescriptor> listForTarget(@RestPath long id) throws Exception {
        Target target = Target.find("id", id).singleResult();
        return target.activeRecordings.stream().map(recordingHelper::toExternalForm).toList();
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
    @Blocking
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
                try {
                    return recordingHelper.saveRecording(activeRecording);
                } catch (IOException ioe) {
                    logger.warn(ioe);
                    return null;
                }
            default:
                throw new BadRequestException(body);
        }
    }

    @PATCH
    @Transactional
    @Blocking
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

    @POST
    @Transactional
    @Blocking
    @Path("/api/v1/targets/{connectUrl}/snapshot")
    @RolesAllowed("write")
    public Response createSnapshotV1(@RestPath URI connectUrl) throws Exception {
        Target target = Target.getTargetByConnectUrl(connectUrl);
        try {
            ActiveRecording recording =
                    connectionManager.executeConnectedTask(
                            target,
                            connection -> recordingHelper.createSnapshot(target, connection));
            return Response.status(Response.Status.OK).entity(recording.name).build();
        } catch (SnapshotCreationException sce) {
            return Response.status(Response.Status.ACCEPTED).build();
        }
    }

    @POST
    @Transactional
    @Blocking
    @Path("/api/v2/targets/{connectUrl}/snapshot")
    @RolesAllowed("write")
    public Response createSnapshotV2(@RestPath URI connectUrl) throws Exception {
        Target target = Target.getTargetByConnectUrl(connectUrl);
        try {
            ActiveRecording recording =
                    connectionManager.executeConnectedTask(
                            target,
                            connection -> recordingHelper.createSnapshot(target, connection));
            return Response.status(Response.Status.CREATED)
                    .entity(
                            V2Response.json(
                                    Response.Status.CREATED,
                                    recordingHelper.toExternalForm(recording)))
                    .build();
        } catch (SnapshotCreationException sce) {
            return Response.status(Response.Status.ACCEPTED)
                    .entity(V2Response.json(Response.Status.ACCEPTED, null))
                    .build();
        }
    }

    @POST
    @Transactional
    @Blocking
    @Path("/api/v3/targets/{id}/snapshot")
    @RolesAllowed("write")
    public Response createSnapshot(@RestPath long id) throws Exception {
        Target target = Target.find("id", id).singleResult();
        try {
            ActiveRecording recording =
                    connectionManager.executeConnectedTask(
                            target,
                            connection -> recordingHelper.createSnapshot(target, connection));
            return Response.status(Response.Status.OK)
                    .entity(recordingHelper.toExternalForm(recording))
                    .build();
        } catch (SnapshotCreationException sce) {
            return Response.status(Response.Status.ACCEPTED).build();
        }
    }

    @POST
    @Transactional
    @Blocking
    @Path("/api/v3/targets/{id}/recordings")
    @RolesAllowed("write")
    public Response createRecording(
            @RestPath long id,
            @RestForm String recordingName,
            @RestForm String events,
            @RestForm Optional<String> replace,
            // restart param is deprecated, only 'replace' should be used and takes priority if both
            // are provided
            @RestForm Optional<Boolean> restart,
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

        ActiveRecording recording =
                connectionManager.executeConnectedTask(
                        target,
                        connection -> {
                            RecordingOptionsBuilder optionsBuilder =
                                    recordingOptionsBuilderFactory
                                            .create(connection.getService())
                                            .name(recordingName);
                            if (duration.isPresent()) {
                                optionsBuilder.duration(TimeUnit.SECONDS.toMillis(duration.get()));
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
                            RecordingReplace replacement = RecordingReplace.NEVER;
                            if (replace.isPresent()) {
                                replacement = RecordingReplace.fromString(replace.get());
                            } else if (restart.isPresent()) {
                                replacement =
                                        restart.get()
                                                ? RecordingReplace.ALWAYS
                                                : RecordingReplace.NEVER;
                            }
                            IConstrainedMap<String> recordingOptions = optionsBuilder.build();
                            return recordingHelper.startRecording(
                                    target,
                                    recordingOptions,
                                    template.getLeft(),
                                    template.getRight(),
                                    new Metadata(labels),
                                    archiveOnStop.orElse(false),
                                    replacement,
                                    connection);
                        });

        if (recording.duration > 0) {
            scheduler.schedule(
                    () -> stopRecording(recording.id, archiveOnStop.orElse(false)),
                    recording.duration,
                    TimeUnit.MILLISECONDS);
        }

        return Response.status(Response.Status.CREATED)
                .entity(recordingHelper.toExternalForm(recording))
                .build();
    }

    @Transactional
    void stopRecording(long id, boolean archive) {
        ActiveRecording.<ActiveRecording>findByIdOptional(id)
                .ifPresent(
                        recording -> {
                            try {
                                recording.state = RecordingState.STOPPED;
                                recording.persist();
                                if (archive) {
                                    recordingHelper.saveRecording(recording);
                                }
                            } catch (Exception e) {
                                logger.error("couldn't update recording", e);
                            }
                        });
    }

    @POST
    @Transactional
    @Blocking
    @Path("/api/v1/targets/{connectUrl}/recordings")
    @RolesAllowed("write")
    public Response createRecordingV1(@RestPath URI connectUrl) throws Exception {
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(
                        URI.create(
                                String.format(
                                        "/api/v3/targets/%d/recordings",
                                        Target.getTargetByConnectUrl(connectUrl).id)))
                .build();
    }

    @DELETE
    @Transactional
    @Blocking
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

    @DELETE
    @Transactional
    @Blocking
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

    @DELETE
    @Blocking
    @Path("/api/beta/fs/recordings/{jvmId}/{filename}")
    @RolesAllowed("write")
    public void deleteArchivedRecording(@RestPath String jvmId, @RestPath String filename)
            throws Exception {
        logger.infov("Handling archived recording deletion: {0} / {1}", jvmId, filename);
        var metadata =
                recordingHelper
                        .getArchivedRecordingMetadata(jvmId, filename)
                        .orElseGet(Metadata::empty);

        var connectUrl =
                Target.getTargetByJvmId(jvmId)
                        .map(t -> t.connectUrl)
                        .map(c -> c.toString())
                        .orElseGet(() -> metadata.labels.computeIfAbsent("connectUrl", k -> jvmId));
        logger.infov(
                "Archived recording from connectUrl \"{0}\" has metadata: {1}",
                connectUrl, metadata);
        logger.infov(
                "Sending S3 deletion request for {0} {1}",
                archiveBucket, recordingHelper.archivedRecordingKey(jvmId, filename));
        var resp =
                storage.deleteObject(
                        DeleteObjectRequest.builder()
                                .bucket(archiveBucket)
                                .key(recordingHelper.archivedRecordingKey(jvmId, filename))
                                .build());
        logger.infov(
                "Got SDK response {0} {1}",
                resp.sdkHttpResponse().statusCode(), resp.sdkHttpResponse().statusText());
        if (resp.sdkHttpResponse().isSuccessful()) {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(
                            "ArchivedRecordingDeleted",
                            new RecordingEvent(URI.create(connectUrl), Map.of("name", filename))));
        } else {
            throw new HttpException(
                    resp.sdkHttpResponse().statusCode(),
                    resp.sdkHttpResponse().statusText().orElse(""));
        }
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
    @Blocking
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
    @Blocking
    @Path("/api/v3/targets/{targetId}/recordings/{remoteId}/upload")
    @RolesAllowed("write")
    public Response uploadToGrafana(@RestPath long targetId, @RestPath long remoteId)
            throws Exception {
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

            return recordingHelper.uploadToJFRDatasource(targetId, remoteId, uploadUrl);
        } catch (MalformedURLException e) {
            throw new NotImplementedException(e);
        }
    }

    @GET
    @Blocking
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
    @Blocking
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

    @PATCH
    @Blocking
    @Path("/api/v1/targets/{connectUrl}/recordingOptions")
    @RolesAllowed("write")
    public Response patchRecordingOptionsV1(@RestPath URI connectUrl) {
        Target target = Target.getTargetByConnectUrl(connectUrl);
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(
                        URI.create(String.format("/api/v3/targets/%d/recordingOptions", target.id)))
                .build();
    }

    @PATCH
    @Blocking
    @Path("/api/v3/targets/{id}/recordingOptions")
    @RolesAllowed("read")
    public Map<String, Object> patchRecordingOptions(
            @RestPath long id,
            @RestForm String toDisk,
            @RestForm String maxAge,
            @RestForm String maxSize)
            throws Exception {
        final String unsetKeyword = "unset";

        Map<String, String> form = new HashMap<>();
        Pattern bool = Pattern.compile("true|false|" + unsetKeyword);
        if (toDisk != null) {
            Matcher m = bool.matcher(toDisk);
            if (!m.matches()) {
                throw new BadRequestException("Invalid options");
            }
            form.put("toDisk", toDisk);
        }
        if (maxAge != null) {
            if (!unsetKeyword.equals(maxAge)) {
                try {
                    Long.parseLong(maxAge);
                    form.put("maxAge", maxAge);
                } catch (NumberFormatException e) {
                    throw new BadRequestException("Invalid options");
                }
            }
        }
        if (maxSize != null) {
            if (!unsetKeyword.equals(maxSize)) {
                try {
                    Long.parseLong(maxSize);
                    form.put("maxSize", maxSize);
                } catch (NumberFormatException e) {
                    throw new BadRequestException("Invalid options");
                }
            }
        }
        form.entrySet()
                .forEach(
                        e -> {
                            RecordingOptionsCustomizer.OptionKey optionKey =
                                    RecordingOptionsCustomizer.OptionKey.fromOptionName(e.getKey())
                                            .get();
                            if ("unset".equals(e.getValue())) {
                                recordingOptionsCustomizer.unset(optionKey);
                            } else {
                                recordingOptionsCustomizer.set(optionKey, e.getValue());
                            }
                        });

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
    @Blocking
    @Path("/api/v3/activedownload/{id}")
    @RolesAllowed("read")
    public Response createAndRedirectPresignedDownload(@RestPath long id) throws Exception {
        ActiveRecording recording = ActiveRecording.findById(id);
        if (recording == null) {
            throw new NotFoundException();
        }
        String filename =
                recordingHelper.saveRecording(
                        recording, Instant.now().plusSeconds(60)); // TODO make expiry configurable
        String encodedKey = recordingHelper.encodedKey(recording.target.jvmId, filename);
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        String.format("attachment; filename=\"%s\"", filename))
                .location(URI.create(String.format("/api/v3/download/%s", encodedKey)))
                .build();
    }

    @GET
    @Blocking
    @Path("/api/v3/download/{encodedKey}")
    @RolesAllowed("read")
    public Response redirectPresignedDownload(@RestPath String encodedKey)
            throws URISyntaxException {
        Pair<String, String> pair = recordingHelper.decodedKey(encodedKey);
        logger.infov("Handling presigned download request for {0}", pair);
        GetObjectRequest getRequest =
                GetObjectRequest.builder()
                        .bucket(archiveBucket)
                        .key(recordingHelper.archivedRecordingKey(pair))
                        .build();
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
            long remoteId,
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
        public LinkedRecordingDescriptor {
            Objects.requireNonNull(state);
            Objects.requireNonNull(name);
            Objects.requireNonNull(downloadUrl);
            Objects.requireNonNull(reportUrl);
            Objects.requireNonNull(metadata);
        }
    }

    public record ArchivedRecording(
            String name,
            String downloadUrl,
            String reportUrl,
            Metadata metadata,
            long size,
            long archivedTime) {
        public ArchivedRecording {
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

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public record Metadata(Map<String, String> labels, Instant expiry) {
        public Metadata {
            Objects.requireNonNull(labels);
        }

        public Metadata(Map<String, String> labels) {
            this(labels, null);
        }

        public Metadata(Metadata other) {
            this(new HashMap<>((other.labels)));
        }

        public Metadata(Metadata other, Instant expiry) {
            this(new HashMap<>((other.labels)), expiry);
        }

        public static Metadata empty() {
            return new Metadata(new HashMap<>());
        }
    }
}
