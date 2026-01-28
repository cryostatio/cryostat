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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import io.cryostat.ConfigProperties;
import io.cryostat.libcryostat.templates.Template;
import io.cryostat.libcryostat.templates.TemplateType;
import io.cryostat.recordings.LongRunningRequestGenerator.ArchiveRequest;
import io.cryostat.recordings.LongRunningRequestGenerator.GrafanaActiveUploadRequest;
import io.cryostat.recordings.RecordingHelper.RecordingOptions;
import io.cryostat.recordings.RecordingHelper.RecordingReplace;
import io.cryostat.targets.Target;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.http.HttpServerResponse;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;

@Path("/api/v4/targets/{targetId}/recordings")
public class ActiveRecordings {

    @Inject ObjectMapper mapper;
    @Inject RecordingHelper recordingHelper;
    @Inject LongRunningRequestGenerator generator;
    @Inject EventBus bus;
    @Inject Logger logger;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration connectionFailedTimeout;

    @GET
    @Blocking
    @Transactional
    @RolesAllowed("read")
    @Operation(
            summary = "List active recordings on the specified target",
            description =
                    """
                    Retrieve a list of active recordings currently present on the specified target. This may initiate
                    a new remote connection to the target to update Cryostat's model of available recordings.
                    """)
    public List<LinkedRecordingDescriptor> list(@RestPath long targetId) throws Exception {
        Target target = Target.getTargetById(targetId);
        return recordingHelper.listActiveRecordings(target).stream()
                .map(recordingHelper::toExternalForm)
                .toList();
    }

    @GET
    @Blocking
    @Path("/{remoteId}")
    @RolesAllowed("read")
    @Operation(
            summary = "Download a Flight Recording binary file",
            description =
                    """
                    Given a recording ID and a remote recording ID within that target, Cryostat will open a remote
                    connection to the target and pipe back a data stream containing the Flight Recording binary file
                    format for that recording. The client can feed this data to other tooling which ingests the JFR
                    binary file format.
                    """)
    public RestResponse<InputStream> download(@RestPath long targetId, @RestPath long remoteId)
            throws Exception {
        Target target = Target.getTargetById(targetId);
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
    @Operation(
            summary = "Update a remote recording on the specified target",
            description =
                    """
                    Remote recordings can be stopped by sending the request body "stop", or copied to archives by
                    sending the request body "save". The body is case-insensitive.
                    """)
    public String patch(
            HttpServerResponse response,
            @RestPath long targetId,
            @RestPath long remoteId,
            String body)
            throws Exception {
        Target target = Target.getTargetById(targetId);
        Optional<ActiveRecording> recording =
                recordingHelper.listActiveRecordings(target).stream()
                        .filter(rec -> rec.remoteId == remoteId)
                        .findFirst();
        if (!recording.isPresent()) {
            throw new NotFoundException();
        }
        ActiveRecording activeRecording = recording.get();
        switch (body.strip().toLowerCase()) {
            case "stop":
                recordingHelper
                        .stopRecording(activeRecording)
                        .await()
                        .atMost(connectionFailedTimeout);
                return null;
            case "save":
                ArchiveRequest request =
                        new ArchiveRequest(UUID.randomUUID().toString(), activeRecording);
                logger.tracev(
                        "Request created: ({0}, {1})", request.id(), request.recording().name);
                response.endHandler(
                        (e) ->
                                bus.publish(
                                        LongRunningRequestGenerator.ARCHIVE_REQUEST_ADDRESS,
                                        request));
                return request.id();
            default:
                throw new BadRequestException(body);
        }
    }

    @POST
    @Transactional
    @Blocking
    @RolesAllowed("write")
    @Operation(
            summary = "Start a new recording on the specified target",
            description =
                    """
                    Create a new Flight Recording on the specified target. The recording will be immediately started
                    and begin capturing Flight Recording data.
                    The recording must be given a name (unique within the
                    target). An event specifier string must be included, which follows the format
                    "template={name},(type={type})". The type parameter is optional and the template name is required.
                    See the Event Templates API for more information about the values that can be used here.
                    """)
    public RestResponse<LinkedRecordingDescriptor> create(
            @Context UriInfo uriInfo,
            @RestPath long targetId,
            @Parameter(required = true, description = "must be unique within the target") @RestForm
                    String recordingName,
            @Parameter(required = true, description = "ex. template=Profiling,type=TARGET")
                    @RestForm
                    String events,
            @RestForm Optional<String> replace,
            // restart param is deprecated, only 'replace' should be used and takes priority if both
            // are provided
            @Parameter(deprecated = true) @Deprecated @RestForm Optional<Boolean> restart,
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

        Target target = Target.getTargetById(targetId);

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
    @Operation(
            summary = "Delete a recording from the specified target",
            description =
                    """
                    Delete a recording from the specified target. This will remove it both from Cryostat's database
                    as well as remove the recording and release all resources in the remote target JVM.
                    """)
    public void delete(@RestPath long targetId, @RestPath long remoteId) throws Exception {
        Target target = Target.getTargetById(targetId);
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
    @Operation(
            summary = "Upload a recording for analysis in Grafana dashboard",
            description =
                    """
                    Upload the current data stream of the specified recording to the jfr-datasource for online analysis
                    in the associated Grafana dashboard.
                    """)
    public String uploadToGrafana(
            HttpServerResponse response, @RestPath long targetId, @RestPath long remoteId)
            throws Exception {
        // Send an intermediate response back to the client while another thread handles the upload
        // request
        logger.trace("Creating grafana upload request");
        GrafanaActiveUploadRequest request =
                new GrafanaActiveUploadRequest(UUID.randomUUID().toString(), remoteId, targetId);
        logger.tracev(
                "Request created: ({0}, {1}, {2})"
                        + request.id()
                        + request.remoteId()
                        + request.targetId());
        response.endHandler(
                (e) ->
                        bus.publish(
                                LongRunningRequestGenerator.GRAFANA_ACTIVE_REQUEST_ADDRESS,
                                request));
        return request.id();
    }

    public record LinkedRecordingDescriptor(
            long id,
            long remoteId,
            RecordingState state,
            long duration,
            long startTime,
            boolean archiveOnStop,
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
    public record Metadata(Map<String, String> labels) {
        public Metadata {
            Objects.requireNonNull(labels);
        }

        public Metadata() {
            this(Map.of());
        }

        public Metadata(Metadata other) {
            this(new HashMap<>((other.labels)));
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
    public static final String RECORDING_METADATA_UPDATED = "RecordingMetadataUpdated";

    public enum RecordingEventCategory {
        ACTIVE_CREATED(ACTIVE_RECORDING_CREATED),
        ACTIVE_STOPPED(ACTIVE_RECORDING_STOPPED),
        ACTIVE_SAVED(ACTIVE_RECORDING_SAVED),
        ACTIVE_DELETED(ACTIVE_RECORDING_DELETED),
        ARCHIVED_CREATED(ARCHIVED_RECORDING_CREATED),
        ARCHIVED_DELETED(ARCHIVED_RECORDING_DELETED),
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
