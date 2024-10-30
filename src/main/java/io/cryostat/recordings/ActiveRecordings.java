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

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import io.cryostat.ConfigProperties;
import io.cryostat.libcryostat.templates.Template;
import io.cryostat.libcryostat.templates.TemplateType;
import io.cryostat.recordings.ArchiveRequestGenerator.ArchiveRequest;
import io.cryostat.recordings.RecordingHelper.RecordingOptions;
import io.cryostat.recordings.RecordingHelper.RecordingReplace;
import io.cryostat.targets.Target;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import jdk.jfr.RecordingState;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;

@Path("/api/v4/targets/{targetId}/recordings")
public class ActiveRecordings {

    @Inject ObjectMapper mapper;
    @Inject RecordingHelper recordingHelper;
    @Inject ArchiveRequestGenerator generator;
    @Inject EventBus bus;
    @Inject Logger logger;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration connectionFailedTimeout;

    @GET
    @Blocking
    @Transactional
    @RolesAllowed("read")
    public List<LinkedRecordingDescriptor> list(@RestPath long targetId) throws Exception {
        Target target = Target.find("id", targetId).singleResult();
        return recordingHelper.listActiveRecordings(target).stream()
                .map(recordingHelper::toExternalForm)
                .toList();
    }

    @GET
    @Blocking
    @Path("/{remoteId}")
    @RolesAllowed("read")
    public RestResponse<InputStream> download(@RestPath long targetId, @RestPath long remoteId)
            throws Exception {
        Target target = Target.find("id", targetId).singleResult();
        var recording =
                target.activeRecordings.stream()
                        .filter(r -> r.remoteId == remoteId)
                        .findFirst()
                        .orElseThrow();
        return ResponseBuilder.<InputStream>create(RestResponse.Status.PERMANENT_REDIRECT)
                .location(URI.create(String.format("/api/v4/activedownload/%d", recording.id)))
                .build();
    }

    @PATCH
    @Transactional
    @Blocking
    @Path("/{remoteId}")
    @RolesAllowed("write")
    public String patch(@RestPath long targetId, @RestPath long remoteId, String body)
            throws Exception {
        Target target = Target.find("id", targetId).singleResult();
        Optional<ActiveRecording> recording =
                recordingHelper.listActiveRecordings(target).stream()
                        .filter(rec -> rec.remoteId == remoteId)
                        .findFirst();
        if (!recording.isPresent()) {
            throw new NotFoundException();
        }
        ActiveRecording activeRecording = recording.get();
        switch (body.toLowerCase()) {
            case "stop":
                recordingHelper
                        .stopRecording(activeRecording)
                        .await()
                        .atMost(connectionFailedTimeout);
                return null;
            case "save":
                // FIXME this operation might take a long time to complete, depending on the
                // amount of JFR data in the target and the speed of the connection between the
                // target and Cryostat. We should not make the client wait until this operation
                // completes before sending a response - it should be async. Here we should just
                // return an Accepted response, and if a failure occurs that should be indicated
                // as a websocket notification.

                /*
                 * Desired workflow:
                 *   Client sends a PATCH request to Cryostat
                 *   Cryostat receives the PATCH and checks that the specified active recording exists and that the target JVM is reachable (ex. try to open a connection and do something relatively lightweight like compute its JVM ID). If this check succeeds respond to the PATCH with 202, if it fails respond with a 404 (recording not found) or 502 (target not reachable) etc.
                 *   In the background, Cryostat creates the S3 file upload request, opens a target connection, pipes the bytes, etc. - same as steps 2-5 above
                 *   Cryostat emits a WebSocket notification, either indicating task successful completion or task failure.
                 */
                logger.info("Ceating request");
                ArchiveRequest request =
                        new ArchiveRequest(UUID.randomUUID().toString(), activeRecording);
                logger.info(
                        "Request created: ("
                                + request.getId()
                                + ", "
                                + request.getRecording().name
                                + ")");
                bus.publish(ArchiveRequestGenerator.ARCHIVE_ADDRESS, request);
                return request.getId();
            default:
                throw new BadRequestException(body);
        }
    }

    @POST
    @Transactional
    @Blocking
    @RolesAllowed("write")
    public RestResponse<LinkedRecordingDescriptor> create(
            @Context UriInfo uriInfo,
            @RestPath long targetId,
            @RestForm String recordingName,
            @RestForm String events,
            @RestForm Optional<String> replace,
            // restart param is deprecated, only 'replace' should be used and takes priority if both
            // are provided
            @Deprecated @RestForm Optional<Boolean> restart,
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

        Target target = Target.find("id", targetId).singleResult();

        Pair<String, TemplateType> pair = recordingHelper.parseEventSpecifier(events);
        Template template =
                recordingHelper.getPreferredTemplate(target, pair.getKey(), pair.getValue());

        Map<String, String> labels = new HashMap<>();
        if (rawMetadata.isPresent()) {
            labels.putAll(mapper.readValue(rawMetadata.get(), Metadata.class).labels);
        }
        RecordingReplace replacement = RecordingReplace.NEVER;
        if (replace.isPresent()) {
            replacement = RecordingReplace.fromString(replace.get());
        } else if (restart.isPresent()) {
            replacement = restart.get() ? RecordingReplace.ALWAYS : RecordingReplace.NEVER;
        }
        ActiveRecording recording =
                recordingHelper
                        .startRecording(
                                target,
                                replacement,
                                template,
                                new RecordingOptions(
                                        recordingName,
                                        toDisk,
                                        archiveOnStop,
                                        duration,
                                        maxSize,
                                        maxAge),
                                labels)
                        .await()
                        .atMost(Duration.ofSeconds(10));

        return ResponseBuilder.<LinkedRecordingDescriptor>created(
                        uriInfo.getAbsolutePathBuilder().path(String.valueOf(recording.id)).build())
                .entity(recordingHelper.toExternalForm(recording))
                .build();
    }

    @DELETE
    @Transactional
    @Blocking
    @Path("/{remoteId}")
    @RolesAllowed("write")
    public void delete(@RestPath long targetId, @RestPath long remoteId) throws Exception {
        Target target = Target.find("id", targetId).singleResult();
        var recording = target.getRecordingById(remoteId);
        if (recording == null) {
            throw new NotFoundException();
        }
        recordingHelper.deleteRecording(recording).await().atMost(connectionFailedTimeout);
    }

    @POST
    @Blocking
    @Path("/{remoteId}/upload")
    @RolesAllowed("write")
    public Uni<String> uploadToGrafana(@RestPath long targetId, @RestPath long remoteId)
            throws Exception {
        return recordingHelper.uploadToJFRDatasource(targetId, remoteId);
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

    public static final String ACTIVE_RECORDING_CREATED = "ActiveRecordingCreated";
    public static final String ACTIVE_RECORDING_STOPPED = "ActiveRecordingStopped";
    public static final String ARCHIVED_RECORDING_DELETED = "ArchivedRecordingDeleted";
    public static final String ARCHIVED_RECORDING_CREATED = "ArchivedRecordingCreated";
    public static final String ACTIVE_RECORDING_DELETED = "ActiveRecordingDeleted";
    public static final String ACTIVE_RECORDING_SAVED = "ActiveRecordingSaved";
    public static final String SNAPSHOT_RECORDING_CREATED = "SnapshotCreated";
    public static final String RECORDING_METADATA_UPDATED = "RecordingMetadataUpdated";

    public enum RecordingEventCategory {
        ACTIVE_CREATED(ACTIVE_RECORDING_CREATED),
        ACTIVE_STOPPED(ACTIVE_RECORDING_STOPPED),
        ACTIVE_SAVED(ACTIVE_RECORDING_SAVED),
        ACTIVE_DELETED(ACTIVE_RECORDING_DELETED),
        ARCHIVED_CREATED(ARCHIVED_RECORDING_CREATED),
        ARCHIVED_DELETED(ARCHIVED_RECORDING_DELETED),
        SNAPSHOT_CREATED(SNAPSHOT_RECORDING_CREATED),
        METADATA_UPDATED(RECORDING_METADATA_UPDATED),
        ;

        private final String category;

        private RecordingEventCategory(String category) {
            this.category = category;
        }

        public String category() {
            return category;
        }
    }
}
