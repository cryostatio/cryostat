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
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.cryostat.ConfigProperties;
import io.cryostat.Producers;
import io.cryostat.StorageBuckets;
import io.cryostat.core.EventOptionsBuilder;
import io.cryostat.libcryostat.sys.Clock;
import io.cryostat.libcryostat.templates.Template;
import io.cryostat.libcryostat.templates.TemplateType;
import io.cryostat.recordings.RecordingHelper.RecordingOptions;
import io.cryostat.recordings.RecordingHelper.RecordingReplace;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Named;
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
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Path("/api/v4/targets/{targetId}/recordings")
public class ActiveRecordings {

    @Inject TargetConnectionManager connectionManager;
    @Inject EventBus bus;
    @Inject EventOptionsBuilder.Factory eventOptionsBuilderFactory;
    @Inject Clock clock;
    @Inject S3Client storage;
    @Inject StorageBuckets storageBuckets;
    @Inject S3Presigner presigner;
    @Inject RemoteRecordingInputStreamFactory remoteRecordingStreamFactory;
    @Inject ObjectMapper mapper;
    @Inject RecordingHelper recordingHelper;
    @Inject Logger logger;

    @Inject
    @Named(Producers.BASE64_URL)
    Base64 base64Url;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_ARCHIVES)
    String bucket;

    @ConfigProperty(name = ConfigProperties.GRAFANA_DATASOURCE_URL)
    Optional<String> grafanaDatasourceURL;

    @ConfigProperty(name = ConfigProperties.STORAGE_TRANSIENT_ARCHIVES_ENABLED)
    boolean transientArchivesEnabled;

    @ConfigProperty(name = ConfigProperties.STORAGE_TRANSIENT_ARCHIVES_TTL)
    Duration transientArchivesTtl;

    @ConfigProperty(name = ConfigProperties.STORAGE_PRESIGNED_DOWNLOADS_ENABLED)
    boolean presignedDownloadsEnabled;

    @ConfigProperty(name = ConfigProperties.STORAGE_EXT_URL)
    Optional<String> externalStorageUrl;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration connectionFailedTimeout;

    void onStart(@Observes StartupEvent evt) {
        storageBuckets.createIfNecessary(bucket);
    }

    @GET
    @Transactional
    @RolesAllowed("read")
    public List<LinkedRecordingDescriptor> listForTarget(@RestPath long targetId) throws Exception {
        Target target = Target.find("id", targetId).singleResult();
        return recordingHelper.listActiveRecordings(target).stream()
                .map(recordingHelper::toExternalForm)
                .toList();
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
                try {
                    // FIXME this operation might take a long time to complete, depending on the
                    // amount of JFR data in the target and the speed of the connection between the
                    // target and Cryostat. We should not make the client wait until this operation
                    // completes before sending a response - it should be async. Here we should just
                    // return an Accepted response, and if a failure occurs that should be indicated
                    // as a websocket notification.
                    return recordingHelper.archiveRecording(activeRecording, null, null).name();
                } catch (IOException ioe) {
                    logger.warn(ioe);
                    return null;
                }
            default:
                throw new BadRequestException(body);
        }
    }

    @POST
    @Transactional
    @Blocking
    @RolesAllowed("write")
    public RestResponse<LinkedRecordingDescriptor> createRecording(
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
    public void deleteRecording(@RestPath long targetId, @RestPath long remoteId) throws Exception {
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
    public Uni<String> uploadActiveToGrafana(@RestPath long targetId, @RestPath long remoteId)
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
