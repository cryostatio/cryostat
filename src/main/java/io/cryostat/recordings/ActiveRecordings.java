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
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.common.unit.IOptionDescriptor;
import org.openjdk.jmc.flightrecorder.configuration.IFlightRecorderService;
import org.openjdk.jmc.flightrecorder.configuration.recording.RecordingOptionsBuilder;

import io.cryostat.ConfigProperties;
import io.cryostat.Producers;
import io.cryostat.StorageBuckets;
import io.cryostat.core.EventOptionsBuilder;
import io.cryostat.core.RecordingOptionsCustomizer;
import io.cryostat.libcryostat.sys.Clock;
import io.cryostat.libcryostat.templates.Template;
import io.cryostat.libcryostat.templates.TemplateType;
import io.cryostat.recordings.RecordingHelper.RecordingOptions;
import io.cryostat.recordings.RecordingHelper.RecordingReplace;
import io.cryostat.recordings.RecordingHelper.SnapshotCreationException;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.util.HttpMimeType;

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
import jakarta.ws.rs.core.HttpHeaders;
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

@Path("")
public class ActiveRecordings {

    @Inject TargetConnectionManager connectionManager;
    @Inject EventBus bus;
    @Inject RecordingOptionsBuilderFactory recordingOptionsBuilderFactory;
    @Inject RecordingOptionsCustomizerFactory recordingOptionsCustomizerFactory;
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
    @Path("/api/v4/targets/{id}/recordings")
    @RolesAllowed("read")
    public List<LinkedRecordingDescriptor> listForTarget(@RestPath long id) throws Exception {
        Target target = Target.find("id", id).singleResult();
        return recordingHelper.listActiveRecordings(target).stream()
                .map(recordingHelper::toExternalForm)
                .toList();
    }

    @PATCH
    @Transactional
    @Blocking
    @Path("/api/v4/targets/{targetId}/recordings/{remoteId}")
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
    @Path("/api/v4/targets/{id}/snapshot")
    @RolesAllowed("write")
    public Uni<RestResponse<LinkedRecordingDescriptor>> createSnapshotUsingTargetId(
            @RestPath long id) throws Exception {
        Target target = Target.find("id", id).singleResult();
        return recordingHelper
                .createSnapshot(target)
                .onItem()
                .transform(
                        recording ->
                                ResponseBuilder.ok(recordingHelper.toExternalForm(recording))
                                        .build())
                .onFailure(SnapshotCreationException.class)
                .recoverWithItem(ResponseBuilder.<LinkedRecordingDescriptor>accepted().build());
    }

    @POST
    @Transactional
    @Blocking
    @Path("/api/v4/targets/{id}/recordings")
    @RolesAllowed("write")
    public RestResponse<LinkedRecordingDescriptor> createRecording(
            @Context UriInfo uriInfo,
            @RestPath long id,
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

        Target target = Target.find("id", id).singleResult();

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
    @Path("/api/v4/targets/{targetId}/recordings/{remoteId}")
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
    @Path("/api/v4/targets/{targetId}/recordings/{remoteId}/upload")
    @RolesAllowed("write")
    public Uni<String> uploadActiveToGrafana(@RestPath long targetId, @RestPath long remoteId)
            throws Exception {
        return recordingHelper.uploadToJFRDatasource(targetId, remoteId);
    }

    @GET
    @Blocking
    @Path("/api/v4/targets/{id}/recordingOptions")
    @RolesAllowed("read")
    public Map<String, Object> getRecordingOptions(@RestPath long id) throws Exception {
        Target target = Target.find("id", id).singleResult();
        return connectionManager.executeConnectedTask(
                target,
                connection -> {
                    RecordingOptionsBuilder builder = recordingOptionsBuilderFactory.create(target);
                    return getRecordingOptions(connection.getService(), builder);
                });
    }

    @PATCH
    @Blocking
    @Path("/api/v4/targets/{id}/recordingOptions")
    @RolesAllowed("read")
    @SuppressFBWarnings(
            value = "UC_USELESS_OBJECT",
            justification = "SpotBugs thinks the options map is unused, but it is used")
    public Map<String, Object> patchRecordingOptions(
            @RestPath long id,
            @RestForm String toDisk,
            @RestForm String maxAge,
            @RestForm String maxSize)
            throws Exception {
        final String unsetKeyword = "unset";

        Map<String, String> options = new HashMap<>();
        Pattern bool = Pattern.compile("true|false|" + unsetKeyword);
        if (toDisk != null) {
            Matcher m = bool.matcher(toDisk);
            if (!m.matches()) {
                throw new BadRequestException("Invalid options");
            }
            options.put("toDisk", toDisk);
        }
        if (maxAge != null) {
            if (!unsetKeyword.equals(maxAge)) {
                try {
                    Long.parseLong(maxAge);
                    options.put("maxAge", maxAge);
                } catch (NumberFormatException e) {
                    throw new BadRequestException("Invalid options");
                }
            }
        }
        if (maxSize != null) {
            if (!unsetKeyword.equals(maxSize)) {
                try {
                    Long.parseLong(maxSize);
                    options.put("maxSize", maxSize);
                } catch (NumberFormatException e) {
                    throw new BadRequestException("Invalid options");
                }
            }
        }
        Target target = Target.find("id", id).singleResult();
        for (var entry : options.entrySet()) {
            RecordingOptionsCustomizer.OptionKey optionKey =
                    RecordingOptionsCustomizer.OptionKey.fromOptionName(entry.getKey()).get();
            var recordingOptionsCustomizer = recordingOptionsCustomizerFactory.create(target);
            if (unsetKeyword.equals(entry.getValue())) {
                recordingOptionsCustomizer.unset(optionKey);
            } else {
                recordingOptionsCustomizer.set(optionKey, entry.getValue());
            }
        }

        return connectionManager.executeConnectedTask(
                target,
                connection -> {
                    var builder = recordingOptionsBuilderFactory.create(target);
                    return getRecordingOptions(connection.getService(), builder);
                });
    }

    @GET
    @Blocking
    @Path("/api/v4/activedownload/{id}")
    @RolesAllowed("read")
    public RestResponse<InputStream> handleActiveDownload(@RestPath long id) throws Exception {
        ActiveRecording recording = ActiveRecording.find("id", id).singleResult();
        if (!transientArchivesEnabled) {
            return ResponseBuilder.<InputStream>ok()
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            String.format("attachment; filename=\"%s.jfr\"", recording.name))
                    .header(HttpHeaders.CONTENT_TYPE, HttpMimeType.OCTET_STREAM.mime())
                    .entity(recordingHelper.getActiveInputStream(recording))
                    .build();
        }

        String savename = recording.name;
        String filename =
                recordingHelper
                        .archiveRecording(
                                recording, savename, Instant.now().plus(transientArchivesTtl))
                        .name();
        String encodedKey = recordingHelper.encodedKey(recording.target.jvmId, filename);
        if (!savename.endsWith(".jfr")) {
            savename += ".jfr";
        }
        return ResponseBuilder.<InputStream>create(RestResponse.Status.PERMANENT_REDIRECT)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        String.format("attachment; filename=\"%s\"", savename))
                .location(
                        URI.create(
                                String.format(
                                        "/api/v4/download/%s?f=%s",
                                        encodedKey,
                                        base64Url.encodeAsString(
                                                savename.getBytes(StandardCharsets.UTF_8)))))
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
