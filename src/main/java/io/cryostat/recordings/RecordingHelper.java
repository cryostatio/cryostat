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
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.common.unit.QuantityConversionException;
import org.openjdk.jmc.flightrecorder.configuration.IRecordingDescriptor;
import org.openjdk.jmc.flightrecorder.configuration.recording.RecordingOptionsBuilder;

import io.cryostat.ConfigProperties;
import io.cryostat.Producers;
import io.cryostat.core.EventOptionsBuilder;
import io.cryostat.core.FlightRecorderException;
import io.cryostat.core.net.JFRConnection;
import io.cryostat.core.sys.Clock;
import io.cryostat.core.sys.FileSystem;
import io.cryostat.core.templates.Template;
import io.cryostat.core.templates.TemplateType;
import io.cryostat.events.EventTemplates;
import io.cryostat.events.S3TemplateService;
import io.cryostat.events.TargetTemplateService;
import io.cryostat.recordings.ActiveRecording.Listener.ActiveRecordingEvent;
import io.cryostat.recordings.ActiveRecording.Listener.ArchivedRecordingEvent;
import io.cryostat.recordings.Recordings.ArchivedRecording;
import io.cryostat.recordings.Recordings.LinkedRecordingDescriptor;
import io.cryostat.recordings.Recordings.Metadata;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.util.EntityExistsException;
import io.cryostat.util.HttpMimeType;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import io.vertx.mutiny.ext.web.multipart.MultipartForm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.Response;
import jdk.jfr.RecordingState;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.validator.routines.UrlValidator;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

@ApplicationScoped
public class RecordingHelper {

    public static final String JFR_MIME = HttpMimeType.JFR.mime();

    private static final Pattern TEMPLATE_PATTERN =
            Pattern.compile("^template=([\\w]+)(?:,type=([\\w]+))?$");
    public static final String DATASOURCE_FILENAME = "cryostat-analysis.jfr";

    @Inject S3Client storage;

    @Inject WebClient webClient;
    @Inject FileSystem fs;
    @Inject Clock clock;
    @Inject TargetConnectionManager connectionManager;
    @Inject RemoteRecordingInputStreamFactory remoteRecordingStreamFactory;
    @Inject RecordingOptionsBuilderFactory recordingOptionsBuilderFactory;
    @Inject EventOptionsBuilder.Factory eventOptionsBuilderFactory;
    @Inject TargetTemplateService.Factory targetTemplateServiceFactory;
    @Inject S3TemplateService customTemplateService;
    @Inject Scheduler scheduler;

    @Inject
    @Named(Producers.BASE64_URL)
    Base64 base64Url;

    @Inject EventBus bus;
    @Inject Logger logger;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_ARCHIVES)
    String archiveBucket;

    @ConfigProperty(name = ConfigProperties.AWS_OBJECT_EXPIRATION_LABELS)
    String objectExpirationLabel;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration connectionFailedTimeout;

    @ConfigProperty(name = ConfigProperties.GRAFANA_DATASOURCE_URL)
    Optional<String> grafanaDatasourceURLProperty;

    CompletableFuture<URL> grafanaDatasourceURL = new CompletableFuture<>();

    private final List<JobKey> jobs = new CopyOnWriteArrayList<>();

    void onStart(@Observes StartupEvent evt) {
        if (grafanaDatasourceURLProperty.isEmpty()) {
            grafanaDatasourceURL.completeExceptionally(
                    new HttpException(
                            Response.Status.BAD_GATEWAY.getStatusCode(),
                            String.format(
                                    "Configuration property %s is not set",
                                    ConfigProperties.GRAFANA_DATASOURCE_URL)));
            return;
        }
        try {
            URL uploadUrl =
                    new URL(grafanaDatasourceURLProperty.orElseThrow(() -> new HttpException()));
            boolean isValidUploadUrl =
                    new UrlValidator(UrlValidator.ALLOW_LOCAL_URLS).isValid(uploadUrl.toString());
            if (!isValidUploadUrl) {
                grafanaDatasourceURL.completeExceptionally(
                        new HttpException(
                                Response.Status.BAD_GATEWAY.getStatusCode(),
                                String.format(
                                        "Configuration property %s=%s is not acceptable",
                                        ConfigProperties.GRAFANA_DATASOURCE_URL,
                                        grafanaDatasourceURLProperty.get())));
                return;
            }
            grafanaDatasourceURL.complete(new URL(grafanaDatasourceURLProperty.get()));
        } catch (MalformedURLException e) {
            grafanaDatasourceURL.completeExceptionally(
                    new HttpException(
                            Response.Status.BAD_GATEWAY.getStatusCode(),
                            String.format(
                                    "Configuration property %s=%s is not a valid URL",
                                    ConfigProperties.GRAFANA_DATASOURCE_URL,
                                    grafanaDatasourceURLProperty.get())));
            return;
        }
    }

    // FIXME hacky. This opens a remote connection on each call and updates our database with the
    // data we find there. We should have some remote connection callback (JMX listener, WebSocket)
    // to the target and update our database when remote recording events occur, rather than doing a
    // full sync when this method is called.
    public List<ActiveRecording> listActiveRecordings(Target target) {
        return QuarkusTransaction.joiningExisting().call(() -> listActiveRecordingsImpl(target));
    }

    private List<ActiveRecording> listActiveRecordingsImpl(Target target) {
        target = Target.find("id", target.id).singleResult();
        try {
            var previousRecordings = target.activeRecordings;
            var previousIds =
                    new HashSet<>(previousRecordings.stream().map(r -> r.remoteId).toList());
            var previousNames =
                    new HashSet<>(previousRecordings.stream().map(r -> r.name).toList());
            List<IRecordingDescriptor> descriptors =
                    connectionManager.executeConnectedTask(
                            target, conn -> conn.getService().getAvailableRecordings());
            boolean updated = false;
            var it = target.activeRecordings.iterator();
            while (it.hasNext()) {
                var r = it.next();
                if (!previousIds.contains(r.remoteId)) {
                    r.delete();
                    it.remove();
                    updated |= true;
                }
            }
            for (var descriptor : descriptors) {
                if (previousIds.contains(descriptor.getId())) {
                    var recording = target.getRecordingById(descriptor.getId());
                    switch (descriptor.getState()) {
                        case CREATED:
                            recording.state = RecordingState.DELAYED;
                            updated |= true;
                            break;
                        case RUNNING:
                            recording.state = RecordingState.RUNNING;
                            updated |= true;
                            break;
                        case STOPPING:
                            recording.state = RecordingState.RUNNING;
                            updated |= true;
                            break;
                        case STOPPED:
                            recording.state = RecordingState.STOPPED;
                            updated |= true;
                            break;
                        default:
                            recording.state = RecordingState.NEW;
                            updated |= true;
                            break;
                    }
                    if (updated) {
                        try {
                            recording.persist();
                        } catch (PersistenceException e) {
                            logger.warn(e);
                        }
                    }
                    continue;
                }
                updated |= true;
                // TODO is there any metadata to attach here?
                var recording = ActiveRecording.from(target, descriptor, new Metadata(Map.of()));
                recording.external = true;
                // FIXME this is a hack. Older Cryostat versions enforced that recordings' names
                // were unique within the target JVM, but this could only be enforced when Cryostat
                // was originating the recording creation. Recordings already have unique IDs, so
                // enforcing unique names was only for the purpose of providing a tidier UI. We
                // should remove this assumption/enforcement and allow recordings to have non-unique
                // names. However, the UI is currently built with this expectation and often uses
                // recordings' names as unique keys rather than their IDs.
                while (previousNames.contains(recording.name)) {
                    recording.name = String.format("%s-%d", recording.name, recording.remoteId);
                }
                previousNames.add(recording.name);
                previousIds.add(recording.remoteId);
                recording.persist();
                target.activeRecordings.add(recording);
            }
            if (updated) {
                target.persist();
            }
        } catch (Exception e) {
            logger.errorv(
                    e,
                    "Failure to synchronize existing target recording state for {0}",
                    target.connectUrl);
        }
        return target.activeRecordings;
    }

    public Uni<ActiveRecording> startRecording(
            Target target,
            RecordingReplace replace,
            Template template,
            RecordingOptions options,
            Map<String, String> rawLabels)
            throws QuantityConversionException {
        return QuarkusTransaction.joiningExisting()
                .call(() -> startRecordingImpl(target, replace, template, options, rawLabels));
    }

    private Uni<ActiveRecording> startRecordingImpl(
            Target target,
            RecordingReplace replace,
            Template template,
            RecordingOptions options,
            Map<String, String> rawLabels) {
        String recordingName = options.name();

        RecordingState previousState =
                connectionManager.executeConnectedTask(
                        target,
                        conn ->
                                getDescriptorByName(conn, recordingName)
                                        .map(this::mapState)
                                        .orElse(null));
        boolean restart =
                previousState == null
                        || shouldRestartRecording(replace, previousState, recordingName);
        if (!restart) {
            throw new EntityExistsException("Recording", recordingName);
        }
        listActiveRecordings(target).stream()
                .filter(r -> r.name.equals(recordingName))
                .forEach(this::deleteRecording);
        var desc =
                connectionManager.executeConnectedTask(
                        target,
                        conn -> {
                            RecordingOptionsBuilder optionsBuilder =
                                    recordingOptionsBuilderFactory
                                            .create(target)
                                            .name(recordingName);
                            if (options.duration().isPresent()) {
                                optionsBuilder =
                                        optionsBuilder.duration(
                                                TimeUnit.SECONDS.toMillis(
                                                        options.duration().get()));
                            }
                            if (options.toDisk().isPresent()) {
                                optionsBuilder = optionsBuilder.toDisk(options.toDisk().get());
                            }
                            if (options.maxAge().isPresent()) {
                                optionsBuilder = optionsBuilder.maxAge(options.maxAge().get());
                            }
                            if (options.maxSize().isPresent()) {
                                optionsBuilder = optionsBuilder.maxSize(options.maxSize().get());
                            }
                            IConstrainedMap<String> recordingOptions = optionsBuilder.build();

                            switch (template.getType()) {
                                case CUSTOM:
                                    return conn.getService()
                                            .start(
                                                    recordingOptions,
                                                    customTemplateService
                                                            .getXml(
                                                                    template.getName(),
                                                                    TemplateType.CUSTOM)
                                                            .orElseThrow());
                                case TARGET:
                                    return conn.getService().start(recordingOptions, template);
                                default:
                                    throw new IllegalStateException(
                                            "Unknown template type: " + template.getType());
                            }
                        });

        Map<String, String> labels = new HashMap<>(rawLabels);
        labels.put("template.name", template.getName());
        labels.put("template.type", template.getType().toString());
        Metadata meta = new Metadata(labels);

        ActiveRecording recording = ActiveRecording.from(target, desc, meta);
        recording.persist();

        target.activeRecordings.add(recording);
        target.persist();

        if (!recording.continuous) {
            JobDetail jobDetail =
                    JobBuilder.newJob(StopRecordingJob.class)
                            .withIdentity(recording.name, target.jvmId)
                            .build();
            if (!jobs.contains(jobDetail.getKey())) {
                Map<String, Object> data = jobDetail.getJobDataMap();
                data.put("recordingId", recording.id);
                data.put("archive", options.archiveOnStop().orElse(false));
                Trigger trigger =
                        TriggerBuilder.newTrigger()
                                .withIdentity(recording.name, target.jvmId)
                                .usingJobData(jobDetail.getJobDataMap())
                                .startAt(new Date(System.currentTimeMillis() + recording.duration))
                                .build();
                try {
                    scheduler.scheduleJob(jobDetail, trigger);
                } catch (SchedulerException e) {
                    logger.warn(e);
                }
            }
        }

        logger.debugv("Started recording: {0} {1}", target.connectUrl, target.activeRecordings);

        return Uni.createFrom().item(recording);
    }

    public Uni<ActiveRecording> createSnapshot(Target target) {
        return connectionManager.executeConnectedTaskUni(
                target,
                connection -> {
                    IRecordingDescriptor desc = connection.getService().getSnapshotRecording();

                    String rename =
                            String.format("%s-%d", desc.getName().toLowerCase(), desc.getId());

                    RecordingOptionsBuilder recordingOptionsBuilder =
                            recordingOptionsBuilderFactory.create(target).name(rename);

                    connection
                            .getService()
                            .updateRecordingOptions(desc, recordingOptionsBuilder.build());

                    Optional<IRecordingDescriptor> updatedDescriptor =
                            getDescriptorByName(connection, rename);

                    if (updatedDescriptor.isEmpty()) {
                        throw new IllegalStateException(
                                "The most recent snapshot of the recording cannot be"
                                        + " found after renaming.");
                    }

                    desc = updatedDescriptor.get();

                    try (InputStream snapshot =
                            remoteRecordingStreamFactory.open(connection, target, desc)) {
                        if (!snapshotIsReadable(target, snapshot)) {
                            connection.getService().close(desc);
                            throw new SnapshotCreationException(
                                    "Snapshot was not readable - are there any source recordings?");
                        }
                    }

                    ActiveRecording recording =
                            ActiveRecording.from(
                                    target,
                                    desc,
                                    new Metadata(
                                            Map.of(
                                                    "jvmId",
                                                    target.jvmId,
                                                    "connectUrl",
                                                    target.connectUrl.toString())));
                    recording.persist();

                    target.activeRecordings.add(recording);
                    target.persist();

                    var event =
                            new ActiveRecordingEvent(
                                    Recordings.RecordingEventCategory.SNAPSHOT_CREATED,
                                    ActiveRecordingEvent.Payload.of(this, recording));
                    bus.publish(event.category().category(), event.payload().recording());
                    bus.publish(
                            MessagingServer.class.getName(),
                            new Notification(event.category().category(), event.payload()));

                    return recording;
                });
    }

    private boolean snapshotIsReadable(Target target, InputStream snapshot) throws IOException {
        if (!connectionManager.markConnectionInUse(target)) {
            throw new IOException(
                    "Target connection unexpectedly closed while streaming recording");
        }

        try {
            return snapshot.read() != -1;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean shouldRestartRecording(
            RecordingReplace replace, RecordingState state, String recordingName)
            throws BadRequestException {
        switch (replace) {
            case ALWAYS:
                return true;
            case NEVER:
                return false;
            case STOPPED:
                return state == RecordingState.STOPPED;
            default:
                return true;
        }
    }

    public Uni<ActiveRecording> stopRecording(ActiveRecording recording, boolean archive)
            throws Exception {
        var out =
                connectionManager.executeConnectedTask(
                        recording.target,
                        conn -> {
                            var desc = getDescriptorById(conn, recording.remoteId);
                            if (desc.isEmpty()) {
                                throw new NotFoundException();
                            }
                            if (!desc.get()
                                    .getState()
                                    .equals(
                                            org.openjdk.jmc.flightrecorder.configuration
                                                    .IRecordingDescriptor.RecordingState.STOPPED)) {
                                conn.getService().stop(desc.get());
                            }
                            recording.state = RecordingState.STOPPED;
                            return recording;
                        });
        return QuarkusTransaction.joiningExisting()
                .call(
                        () -> {
                            out.persist();
                            if (archive) {
                                archiveRecording(out, null, null);
                            }
                            return Uni.createFrom().item(out);
                        });
    }

    public Uni<ActiveRecording> stopRecording(ActiveRecording recording) throws Exception {
        return stopRecording(recording, false);
    }

    public Uni<ActiveRecording> deleteRecording(ActiveRecording recording) {
        var closed =
                connectionManager.executeConnectedTask(
                        recording.target,
                        conn -> {
                            var desc = getDescriptorById(conn, recording.remoteId);
                            if (desc.isEmpty()) {
                                throw new NotFoundException();
                            }
                            conn.getService().close(desc.get());
                            return recording;
                        });
        return QuarkusTransaction.joiningExisting()
                .call(
                        () -> {
                            closed.target.activeRecordings.remove(recording);
                            closed.target.persist();
                            closed.delete();
                            return Uni.createFrom().item(closed);
                        });
    }

    public LinkedRecordingDescriptor toExternalForm(ActiveRecording recording) {
        return new LinkedRecordingDescriptor(
                recording.id,
                recording.remoteId,
                recording.state,
                recording.duration,
                recording.startTime,
                recording.continuous,
                recording.toDisk,
                recording.maxSize,
                recording.maxAge,
                recording.name,
                downloadUrl(recording),
                reportUrl(recording),
                recording.metadata);
    }

    public Pair<String, TemplateType> parseEventSpecifier(String eventSpecifier) {
        if (TEMPLATE_PATTERN.matcher(eventSpecifier).matches()) {
            Matcher m = TEMPLATE_PATTERN.matcher(eventSpecifier);
            m.find();
            String templateName = m.group(1);
            String typeName = m.group(2);
            TemplateType templateType = null;
            if (StringUtils.isNotBlank(typeName)) {
                templateType = TemplateType.valueOf(typeName.toUpperCase());
            }
            return Pair.of(templateName, templateType);
        }
        throw new BadRequestException(eventSpecifier);
    }

    public Template getPreferredTemplate(
            Target target, String templateName, TemplateType templateType) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(templateName);
        if (templateName.equals(EventTemplates.ALL_EVENTS_TEMPLATE.getName())) {
            return EventTemplates.ALL_EVENTS_TEMPLATE;
        }
        Supplier<Optional<Template>> custom =
                () -> {
                    try {
                        return customTemplateService.getTemplates().stream()
                                .filter(t -> t.getName().equals(templateName))
                                .findFirst();
                    } catch (FlightRecorderException e) {
                        logger.warn(e);
                        return Optional.empty();
                    }
                };

        Supplier<Optional<Template>> remote =
                () -> {
                    try {
                        return targetTemplateServiceFactory.create(target).getTemplates().stream()
                                .filter(t -> t.getName().equals(templateName))
                                .findFirst();
                    } catch (FlightRecorderException e) {
                        logger.warn(e);
                        return Optional.empty();
                    }
                };
        if (templateType == null) {
            return custom.get()
                    .or(() -> remote.get())
                    .orElseThrow(
                            () ->
                                    new BadRequestException(
                                            String.format(
                                                    "Invalid/unknown event template %s",
                                                    templateName)));
        }
        switch (templateType) {
            case TARGET:
                return remote.get().orElseThrow();
            case CUSTOM:
                return custom.get().orElseThrow();
            default:
                return custom.get()
                        .or(() -> remote.get())
                        .orElseThrow(
                                () ->
                                        new BadRequestException(
                                                String.format(
                                                        "Invalid/unknown event template %s",
                                                        templateName)));
        }
    }

    Optional<IRecordingDescriptor> getDescriptorById(JFRConnection connection, long remoteId) {
        try {
            return connection.getService().getAvailableRecordings().stream()
                    .filter(r -> remoteId == r.getId())
                    .findFirst();
        } catch (Exception e) {
            throw new ServerErrorException(500, e);
        }
    }

    Optional<IRecordingDescriptor> getDescriptor(
            JFRConnection connection, ActiveRecording activeRecording) {
        return getDescriptorById(connection, activeRecording.remoteId);
    }

    public Optional<IRecordingDescriptor> getDescriptorByName(
            JFRConnection connection, String recordingName) {
        try {
            return connection.getService().getAvailableRecordings().stream()
                    .filter(r -> Objects.equals(r.getName(), recordingName))
                    .findFirst();
        } catch (Exception e) {
            throw new ServerErrorException(500, e);
        }
    }

    private RecordingState mapState(IRecordingDescriptor desc) {
        switch (desc.getState()) {
            case CREATED:
                return RecordingState.NEW;
            case RUNNING:
                return RecordingState.RUNNING;
            case STOPPING:
                return RecordingState.RUNNING;
            case STOPPED:
                return RecordingState.STOPPED;
            default:
                logger.warnv("Unrecognized recording state: {0}", desc.getState());
                return RecordingState.CLOSED;
        }
    }

    public List<S3Object> listArchivedRecordingObjects() {
        return listArchivedRecordingObjects(null);
    }

    public List<ArchivedRecording> listArchivedRecordings() {
        return listArchivedRecordingObjects().stream()
                .map(
                        item -> {
                            String path = item.key().strip();
                            String[] parts = path.split("/");
                            String jvmId = parts[0];
                            String filename = parts[1];
                            Metadata metadata =
                                    getArchivedRecordingMetadata(jvmId, filename)
                                            .orElseGet(Metadata::empty);
                            return new ArchivedRecording(
                                    jvmId,
                                    filename,
                                    downloadUrl(jvmId, filename),
                                    reportUrl(jvmId, filename),
                                    metadata,
                                    item.size(),
                                    item.lastModified().getEpochSecond());
                        })
                .toList();
    }

    public List<S3Object> listArchivedRecordingObjects(String jvmId) {
        var builder = ListObjectsV2Request.builder().bucket(archiveBucket);
        if (StringUtils.isNotBlank(jvmId)) {
            builder = builder.prefix(jvmId);
        }
        return storage.listObjectsV2(builder.build()).contents().stream()
                .filter(
                        o -> {
                            var metadata = getArchivedRecordingMetadata(o.key());
                            var temporary = metadata.map(m -> m.expiry() != null).orElse(false);
                            return !temporary;
                        })
                .toList();
    }

    public List<ArchivedRecording> listArchivedRecordings(String jvmId) {
        return listArchivedRecordingObjects(jvmId).stream()
                .map(
                        item -> {
                            String path = item.key().strip();
                            String[] parts = path.split("/");
                            String filename = parts[1];
                            Metadata metadata =
                                    getArchivedRecordingMetadata(jvmId, filename)
                                            .orElseGet(Metadata::empty);
                            return new ArchivedRecording(
                                    jvmId,
                                    filename,
                                    downloadUrl(jvmId, filename),
                                    reportUrl(jvmId, filename),
                                    metadata,
                                    item.size(),
                                    item.lastModified().getEpochSecond());
                        })
                .toList();
    }

    public List<ArchivedRecording> listArchivedRecordings(Target target) {
        return listArchivedRecordings(target.jvmId);
    }

    public ArchivedRecording archiveRecording(
            ActiveRecording recording, String savename, Instant expiry) throws Exception {
        // AWS object key name guidelines advise characters to avoid (% so we should not pass url
        // encoded characters)
        String transformedAlias =
                URLDecoder.decode(recording.target.alias, StandardCharsets.UTF_8)
                        .replaceAll("[\\._/]+", "-");
        Instant now = clock.now();
        String timestamp = now.truncatedTo(ChronoUnit.SECONDS).toString().replaceAll("[-:]+", "");
        String filename =
                String.format("%s_%s_%s.jfr", transformedAlias, recording.name, timestamp);
        if (StringUtils.isBlank(savename)) {
            savename = filename;
        }
        int mib = 1024 * 1024;
        String key = archivedRecordingKey(recording.target.jvmId, filename);
        String multipartId = null;
        List<Pair<Integer, String>> parts = new ArrayList<>();
        long accum = 0;
        try (var stream = getActiveInputStream(recording);
                var ch = Channels.newChannel(stream)) {
            ByteBuffer buf = ByteBuffer.allocate(20 * mib);
            CreateMultipartUploadRequest.Builder builder =
                    CreateMultipartUploadRequest.builder()
                            .bucket(archiveBucket)
                            .key(key)
                            .contentType(JFR_MIME)
                            .contentDisposition(
                                    String.format("attachment; filename=\"%s\"", savename))
                            .tagging(createActiveRecordingTagging(recording, expiry));
            if (expiry != null && expiry.isAfter(Instant.now())) {
                builder = builder.expires(expiry);
            }
            CreateMultipartUploadRequest request = builder.build();
            multipartId = storage.createMultipartUpload(request).uploadId();
            int read = 0;
            for (int i = 1; i <= 10_000; i++) {
                read = ch.read(buf);

                if (read == 0) {
                    read = retryRead(ch, buf);
                }
                accum += read;
                if (read == -1) {
                    logger.tracev("Completed upload of {0} chunks ({1} bytes)", i - 1, accum + 1);
                    logger.tracev("Key: {0}", key);
                    break;
                }

                logger.tracev("Writing chunk {0} of {1} bytes", i, read);
                String eTag =
                        storage.uploadPart(
                                        UploadPartRequest.builder()
                                                .bucket(archiveBucket)
                                                .key(key)
                                                .uploadId(multipartId)
                                                .partNumber(i)
                                                .contentLength(Long.valueOf(read))
                                                .build(),
                                        RequestBody.fromByteBuffer(buf.slice(0, read)))
                                .eTag();
                parts.add(Pair.of(i, eTag));
                buf.clear();
                // S3 API limit
                if (i == 10_000) {
                    throw new IndexOutOfBoundsException("Exceeded S3 maximum part count");
                }
            }
        } catch (Exception e) {
            logger.error("Could not upload recording to S3 storage", e);
            try {
                if (multipartId != null) {
                    storage.abortMultipartUpload(
                            AbortMultipartUploadRequest.builder()
                                    .bucket(archiveBucket)
                                    .key(key)
                                    .uploadId(multipartId)
                                    .build());
                }
            } catch (Exception e2) {
                logger.error("Could not abort S3 multipart upload", e2);
            }
            throw e;
        }
        try {
            storage.completeMultipartUpload(
                    CompleteMultipartUploadRequest.builder()
                            .bucket(archiveBucket)
                            .key(key)
                            .uploadId(multipartId)
                            .multipartUpload(
                                    CompletedMultipartUpload.builder()
                                            .parts(
                                                    parts.stream()
                                                            .map(
                                                                    part ->
                                                                            CompletedPart.builder()
                                                                                    .partNumber(
                                                                                            part
                                                                                                    .getLeft())
                                                                                    .eTag(
                                                                                            part
                                                                                                    .getRight())
                                                                                    .build())
                                                            .toList())
                                            .build())
                            .build());
        } catch (SdkClientException e) {
            // Amazon S3 couldn't be contacted for a response, or the client
            // couldn't parse the response from Amazon S3.
            throw e;
        }
        if (expiry == null) {
            ArchivedRecording archivedRecording =
                    new ArchivedRecording(
                            recording.target.jvmId,
                            filename,
                            downloadUrl(recording.target.jvmId, filename),
                            reportUrl(recording.target.jvmId, filename),
                            recording.metadata,
                            accum,
                            now.getEpochSecond());

            URI connectUrl = recording.target.connectUrl;

            var event =
                    new ArchivedRecordingEvent(
                            Recordings.RecordingEventCategory.ARCHIVED_CREATED,
                            ArchivedRecordingEvent.Payload.of(connectUrl, archivedRecording));
            bus.publish(event.category().category(), event.payload().recording());
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(event.category().category(), event.payload()));
        }
        return new ArchivedRecording(
                recording.target.jvmId,
                filename,
                downloadUrl(recording.target.jvmId, filename),
                reportUrl(recording.target.jvmId, filename),
                recording.metadata,
                accum,
                now.getEpochSecond());
    }

    public Optional<Metadata> getArchivedRecordingMetadata(String jvmId, String filename) {
        return getArchivedRecordingMetadata(archivedRecordingKey(jvmId, filename));
    }

    public Optional<Metadata> getArchivedRecordingMetadata(String storageKey) {
        try {
            return Optional.of(
                    taggingToMetadata(
                            storage.getObjectTagging(
                                            GetObjectTaggingRequest.builder()
                                                    .bucket(archiveBucket)
                                                    .key(storageKey)
                                                    .build())
                                    .tagSet()));
        } catch (NoSuchKeyException nske) {
            logger.warn(nske);
            return Optional.empty();
        }
    }

    private String decodeBase64(String encoded) {
        return new String(base64Url.decode(encoded), StandardCharsets.UTF_8);
    }

    public String archivedRecordingKey(String jvmId, String filename) {
        return (jvmId + "/" + filename).strip();
    }

    public String archivedRecordingKey(Pair<String, String> pair) {
        return archivedRecordingKey(pair.getKey(), pair.getValue());
    }

    public String encodedKey(String jvmId, String filename) {
        Objects.requireNonNull(jvmId);
        Objects.requireNonNull(filename);
        return base64Url.encodeAsString(
                (archivedRecordingKey(jvmId, filename)).getBytes(StandardCharsets.UTF_8));
    }

    // TODO refactor this and encapsulate archived recording keys as a record with override toString
    public Pair<String, String> decodedKey(String encodedKey) {
        String key = new String(base64Url.decode(encodedKey), StandardCharsets.UTF_8);
        String[] parts = key.split("/");
        return Pair.of(parts[0], parts[1]);
    }

    public InputStream getActiveInputStream(ActiveRecording recording) throws Exception {
        return remoteRecordingStreamFactory.open(recording);
    }

    public InputStream getActiveInputStream(long targetId, long remoteId) throws Exception {
        var target = Target.getTargetById(targetId);
        var recording = target.getRecordingById(remoteId);
        var stream = remoteRecordingStreamFactory.open(recording);
        return stream;
    }

    public InputStream getArchivedRecordingStream(String jvmId, String recordingName) {
        return getArchivedRecordingStream(encodedKey(jvmId, recordingName));
    }

    public InputStream getArchivedRecordingStream(String encodedKey) {
        String key = new String(base64Url.decode(encodedKey), StandardCharsets.UTF_8);

        GetObjectRequest getRequest =
                GetObjectRequest.builder().bucket(archiveBucket).key(key).build();

        return storage.getObject(getRequest);
    }

    public String downloadUrl(ActiveRecording recording) {
        return String.format("/api/v3/activedownload/%d", recording.id);
    }

    public String downloadUrl(String jvmId, String filename) {
        return String.format("/api/v3/download/%s", encodedKey(jvmId, filename));
    }

    public String reportUrl(ActiveRecording recording) {
        return String.format(
                "/api/v3/targets/%d/reports/%d", recording.target.id, recording.remoteId);
    }

    public String reportUrl(String jvmId, String filename) {
        return String.format("/api/v3/reports/%s", encodedKey(jvmId, filename));
    }

    private int retryRead(ReadableByteChannel channel, ByteBuffer buffer) throws IOException {
        int attempts = 30;
        int read = 0;

        while (attempts > 0) {
            logger.trace("No bytes read, retrying...");
            read = channel.read(buffer);
            if (read > 0 || read < 0) {
                break;
            } else {
                attempts--;
            }
        }

        if (read == 0) {
            throw new IOException("No bytes read after 30 retry attempts");
        }

        return read;
    }

    void safeCloseRecording(JFRConnection conn, IRecordingDescriptor rec) {
        try {
            conn.getService().close(rec);
        } catch (org.openjdk.jmc.flightrecorder.configuration.FlightRecorderException e) {
            logger.error("Failed to stop remote recording", e);
        } catch (Exception e) {
            logger.error("Unexpected exception", e);
        }
    }

    /* Archived Recording Helpers */
    public void deleteArchivedRecording(String jvmId, String filename) {
        storage.deleteObject(
                DeleteObjectRequest.builder()
                        .bucket(archiveBucket)
                        .key(archivedRecordingKey(jvmId, filename))
                        .build());

        var metadata = Metadata.empty(); // TODO
        var target = Target.getTargetByJvmId(jvmId);
        var event =
                new ArchivedRecordingEvent(
                        Recordings.RecordingEventCategory.ARCHIVED_DELETED,
                        ArchivedRecordingEvent.Payload.of(
                                target.map(t -> t.connectUrl).orElse(null),
                                new ArchivedRecording(
                                        jvmId,
                                        filename,
                                        downloadUrl(jvmId, filename),
                                        reportUrl(jvmId, filename),
                                        metadata,
                                        0,
                                        0)));
        bus.publish(event.category().category(), event.payload().recording());
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(event.category().category(), event.payload()));
    }

    Tagging createActiveRecordingTagging(ActiveRecording recording, Instant expiry) {
        Map<String, String> labels = new HashMap<>(recording.metadata.labels());
        labels.put("connectUrl", recording.target.connectUrl.toString());
        labels.put("jvmId", recording.target.jvmId);
        Metadata metadata = new Metadata(labels, expiry);
        return createMetadataTagging(metadata);
    }

    // Metadata
    Tagging createMetadataTagging(Metadata metadata) {
        // TODO attach other metadata than labels somehow. Prefixed keys to create partitioning?
        var tags = new ArrayList<Tag>();
        tags.addAll(
                metadata.labels().entrySet().stream()
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
        if (metadata.expiry() != null) {
            tags.add(
                    Tag.builder()
                            .key(
                                    base64Url.encodeAsString(
                                            objectExpirationLabel.getBytes(StandardCharsets.UTF_8)))
                            .value(
                                    base64Url.encodeAsString(
                                            metadata.expiry()
                                                    .atOffset(ZoneOffset.UTC)
                                                    .toString()
                                                    .getBytes(StandardCharsets.UTF_8)))
                            .build());
        }
        return Tagging.builder().tagSet(tags).build();
    }

    private Metadata taggingToMetadata(List<Tag> tagSet) {
        // TODO parse out other metadata than labels
        Instant expiry = null;
        var labels = new HashMap<String, String>();
        for (var tag : tagSet) {
            var key = decodeBase64(tag.key());
            var value = decodeBase64(tag.value());
            if (key.equals(objectExpirationLabel)) {
                expiry = Instant.parse(value);
            } else {
                labels.put(key, value);
            }
        }
        return new Metadata(labels, expiry);
    }

    public ActiveRecording updateRecordingMetadata(
            long recordingId, Map<String, String> newLabels) {
        return QuarkusTransaction.joiningExisting()
                .call(
                        () -> {
                            ActiveRecording recording =
                                    ActiveRecording.find("id", recordingId).singleResult();

                            if (!recording.metadata.labels().equals(newLabels)) {
                                Metadata updatedMetadata = new Metadata(newLabels);
                                recording.setMetadata(updatedMetadata);
                                recording.persist();

                                notify(
                                        new ActiveRecordingEvent(
                                                Recordings.RecordingEventCategory.METADATA_UPDATED,
                                                ActiveRecordingEvent.Payload.of(this, recording)));
                            }
                            return recording;
                        });
    }

    private void notify(ActiveRecordingEvent event) {
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(event.category().category(), event.payload()));
    }

    public ArchivedRecording updateArchivedRecordingMetadata(
            String jvmId, String filename, Map<String, String> updatedLabels) {
        String key = archivedRecordingKey(jvmId, filename);
        Optional<Metadata> existingMetadataOpt = getArchivedRecordingMetadata(key);

        if (existingMetadataOpt.isEmpty()) {
            throw new NotFoundException(
                    "Could not find metadata for archived recording with key: " + key);
        }

        Metadata updatedMetadata = new Metadata(updatedLabels);

        Tagging tagging = createMetadataTagging(updatedMetadata);
        storage.putObjectTagging(
                PutObjectTaggingRequest.builder()
                        .bucket(archiveBucket)
                        .key(key)
                        .tagging(tagging)
                        .build());

        var response =
                storage.headObject(
                        HeadObjectRequest.builder().bucket(archiveBucket).key(key).build());
        long size = response.contentLength();
        Instant lastModified = response.lastModified();

        ArchivedRecording updatedRecording =
                new ArchivedRecording(
                        jvmId,
                        filename,
                        downloadUrl(jvmId, filename),
                        reportUrl(jvmId, filename),
                        updatedMetadata,
                        size,
                        lastModified.getEpochSecond());

        notifyArchiveMetadataUpdate(updatedRecording);
        return updatedRecording;
    }

    private void notifyArchiveMetadataUpdate(ArchivedRecording updatedRecording) {

        var event =
                new ArchivedRecordingEvent(
                        Recordings.RecordingEventCategory.METADATA_UPDATED,
                        new ArchivedRecordingEvent.Payload(
                                updatedRecording.downloadUrl(), updatedRecording));
        bus.publish(event.category().category(), event.payload().recording());
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(event.category().category(), event.payload()));
    }

    public Uni<String> uploadToJFRDatasource(long targetEntityId, long remoteId) throws Exception {
        Target target = Target.getTargetById(targetEntityId);
        Objects.requireNonNull(target, "Target from targetId not found");
        ActiveRecording recording = target.getRecordingById(remoteId);
        Objects.requireNonNull(recording, "ActiveRecording from remoteId not found");
        Path recordingPath =
                connectionManager.executeConnectedTask(
                        target,
                        connection -> {
                            return getRecordingCopyPath(connection, target, recording.name)
                                    .orElseThrow(
                                            () ->
                                                    new RecordingNotFoundException(
                                                            target.targetId(), recording.name));
                        });

        return uploadToJFRDatasource(recordingPath);
    }

    public Uni<String> uploadToJFRDatasource(Pair<String, String> key) throws Exception {
        Objects.requireNonNull(key);
        Objects.requireNonNull(key.getKey());
        Objects.requireNonNull(key.getValue());
        GetObjectRequest getRequest =
                GetObjectRequest.builder()
                        .bucket(archiveBucket)
                        .key(archivedRecordingKey(key))
                        .build();

        Path recordingPath = fs.createTempFile(null, null);
        // the S3 client will create the file at this path, we just need to get a fresh temp file
        // path but one that does not yet exist
        fs.deleteIfExists(recordingPath);

        storage.getObject(getRequest, recordingPath);

        return uploadToJFRDatasource(recordingPath);
    }

    private Uni<String> uploadToJFRDatasource(Path recordingPath)
            throws URISyntaxException, InterruptedException, ExecutionException {
        MultipartForm form =
                MultipartForm.create()
                        .binaryFileUpload(
                                "file", DATASOURCE_FILENAME, recordingPath.toString(), JFR_MIME);

        var asyncRequest =
                webClient
                        .postAbs(
                                grafanaDatasourceURL
                                        .get()
                                        .toURI()
                                        .resolve("/load")
                                        .normalize()
                                        .toString())
                        .addQueryParam("overwrite", "true")
                        .timeout(connectionFailedTimeout.toMillis())
                        .sendMultipartForm(form);
        return asyncRequest
                .onItem()
                .transform(HttpResponse::bodyAsString)
                .eventually(
                        () -> {
                            try {
                                fs.deleteIfExists(recordingPath);
                            } catch (IOException e) {
                                logger.warn(e);
                            }
                        });
    }

    Optional<Path> getRecordingCopyPath(
            JFRConnection connection, Target target, String recordingName) throws Exception {
        return connection.getService().getAvailableRecordings().stream()
                .filter(recording -> recording.getName().equals(recordingName))
                .findFirst()
                .map(
                        descriptor -> {
                            try {
                                Path tempFile = fs.createTempFile(null, null);
                                try (var stream =
                                        remoteRecordingStreamFactory.open(
                                                connection, target, descriptor)) {
                                    fs.copy(stream, tempFile, StandardCopyOption.REPLACE_EXISTING);
                                }
                                return tempFile;
                            } catch (Exception e) {
                                logger.warn(e);
                                throw new BadRequestException(e);
                            }
                        });
    }

    public record RecordingOptions(
            String name,
            Optional<Boolean> toDisk,
            Optional<Boolean> archiveOnStop,
            Optional<Long> duration,
            Optional<Long> maxSize,
            Optional<Long> maxAge) {}

    public enum RecordingReplace {
        ALWAYS,
        NEVER,
        STOPPED;

        public static RecordingReplace fromString(String replace) {
            for (RecordingReplace r : RecordingReplace.values()) {
                if (r.name().equalsIgnoreCase(replace)) {
                    return r;
                }
            }
            throw new IllegalArgumentException("Invalid recording replace value: " + replace);
        }
    }

    static class StopRecordingJob implements Job {

        @Inject RecordingHelper recordingHelper;
        @Inject Logger logger;

        @Override
        @Transactional
        public void execute(JobExecutionContext ctx) throws JobExecutionException {
            var jobDataMap = ctx.getJobDetail().getJobDataMap();
            try {
                Optional<ActiveRecording> recording =
                        ActiveRecording.find("id", (Long) jobDataMap.get("recordingId"))
                                .singleResultOptional();
                if (recording.isPresent()) {
                    recordingHelper.stopRecording(
                            recording.get(), (Boolean) jobDataMap.get("archive"));
                }
            } catch (Exception e) {
                throw new JobExecutionException(e);
            }
        }
    }

    static class RecordingNotFoundException extends Exception {
        public RecordingNotFoundException(String targetId, String recordingName) {
            super(
                    String.format(
                            "Recording %s was not found in the target [%s].",
                            recordingName, targetId));
        }

        public RecordingNotFoundException(Pair<String, String> key) {
            this(key.getLeft(), key.getRight());
        }
    }

    public static class SnapshotCreationException extends Exception {
        public SnapshotCreationException(String message) {
            super(message);
        }
    }
}
