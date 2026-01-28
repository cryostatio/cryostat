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

import java.io.BufferedInputStream;
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
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.common.unit.QuantityConversionException;
import org.openjdk.jmc.flightrecorder.configuration.IRecordingDescriptor;
import org.openjdk.jmc.flightrecorder.configuration.recording.RecordingOptionsBuilder;

import io.cryostat.ConfigProperties;
import io.cryostat.Producers;
import io.cryostat.StorageBuckets;
import io.cryostat.core.EventOptionsBuilder;
import io.cryostat.core.FlightRecorderException;
import io.cryostat.core.net.JFRConnection;
import io.cryostat.events.EventTemplates;
import io.cryostat.events.PresetTemplateService;
import io.cryostat.events.S3TemplateService;
import io.cryostat.events.TargetTemplateService;
import io.cryostat.libcryostat.sys.Clock;
import io.cryostat.libcryostat.sys.FileSystem;
import io.cryostat.libcryostat.templates.Template;
import io.cryostat.libcryostat.templates.TemplateType;
import io.cryostat.recordings.ActiveRecording.Listener.ActiveRecordingEvent;
import io.cryostat.recordings.ActiveRecording.Listener.ArchivedRecordingEvent;
import io.cryostat.recordings.ActiveRecordings.LinkedRecordingDescriptor;
import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.recordings.ArchivedRecordings.ArchivedRecording;
import io.cryostat.reports.AnalysisReportAggregator;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.util.EntityExistsException;
import io.cryostat.util.HttpMimeType;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Identifier;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jdk.jfr.RecordingState;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.validator.routines.UrlValidator;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.PartFilename;
import org.jboss.resteasy.reactive.PartType;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.quartz.DisallowConcurrentExecution;
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
import org.quartz.plugins.interrupt.JobInterruptMonitorPlugin;
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
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest.Builder;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Utility class for all things relating to Flight Recording operations. This class is used to
 * interact with remote target JVMs when performing any kind of JFR operations such as starting,
 * stopping, or deleting recordings, as well as retrieving recording data streams or piping these
 * data streams to other destinations. This also implements complementary operations on archived
 * recordings, which are active recordings that have already been copied from data streams into S3
 * object storage.
 *
 * @see io.cryostat.target.Target
 * @see io.cryostat.recordings.Recording
 * @see io.cryostat.recordings.ActiveRecordings
 * @see io.cryostat.recordings.ArchivedRecordings
 */
@ApplicationScoped
public class RecordingHelper {

    private static final int S3_API_PART_LIMIT = 10_000;
    private static final int MIB = 1024 * 1024;

    private static final Pattern TEMPLATE_PATTERN =
            Pattern.compile("^template=([\\w]+)(?:,type=([\\w]+))?$");
    public static final String DATASOURCE_FILENAME = "cryostat-analysis.jfr";

    @Inject S3Client storage;

    @Inject @RestClient DatasourceClient datasourceClient;
    @Inject StorageBuckets buckets;
    @Inject FileSystem fs;
    @Inject Clock clock;
    @Inject TargetConnectionManager connectionManager;
    @Inject RemoteRecordingInputStreamFactory remoteRecordingStreamFactory;
    @Inject RecordingOptionsBuilderFactory recordingOptionsBuilderFactory;
    @Inject EventOptionsBuilder.Factory eventOptionsBuilderFactory;
    @Inject TargetTemplateService.Factory targetTemplateServiceFactory;
    @Inject S3TemplateService customTemplateService;
    @Inject PresetTemplateService presetTemplateService;
    @Inject Instance<ArchivedRecordingMetadataService> metadataService;
    @Inject Scheduler scheduler;
    @Inject S3Presigner presigner;

    @Inject
    @Identifier(Producers.BASE64_URL)
    Base64 base64Url;

    @Inject EventBus bus;
    @Inject Logger logger;

    @ConfigProperty(name = ConfigProperties.STORAGE_METADATA_ARCHIVES_STORAGE_MODE)
    String metadataStorageMode;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_ARCHIVES)
    String archiveBucket;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration connectionFailedTimeout;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_UPLOAD_TIMEOUT)
    Duration uploadFailedTimeout;

    @ConfigProperty(name = ConfigProperties.GRAFANA_DATASOURCE_URL)
    Optional<String> grafanaDatasourceURLProperty;

    @ConfigProperty(name = ConfigProperties.EXTERNAL_RECORDINGS_AUTOANALYZE)
    boolean externalRecordingAutoanalyze;

    @ConfigProperty(name = ConfigProperties.EXTERNAL_RECORDINGS_ARCHIVE)
    boolean externalRecordingArchive;

    @ConfigProperty(name = ConfigProperties.JFR_DATASOURCE_USE_PRESIGNED_TRANSFER)
    boolean usePresignedTransfer;

    CompletableFuture<URL> grafanaDatasourceURL = new CompletableFuture<>();

    void onStart(@Observes StartupEvent evt) {
        buckets.createIfNecessary(archiveBucket);

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

    public List<ActiveRecording> listActiveRecordings(Target target) {
        return QuarkusTransaction.joiningExisting()
                .call(() -> Target.<Target>find("id", target.id).singleResult().activeRecordings);
    }

    public Optional<ActiveRecording> getActiveRecording(
            Target target, Predicate<ActiveRecording> fn) {
        return listActiveRecordings(target).stream().filter(fn).findFirst();
    }

    public Optional<ActiveRecording> getActiveRecording(Target target, long remoteId) {
        return getActiveRecording(target, r -> r.remoteId == remoteId);
    }

    public List<ActiveRecording> syncActiveRecordings(Target target) {
        return QuarkusTransaction.joiningExisting().call(() -> listActiveRecordingsImpl(target));
    }

    private List<ActiveRecording> listActiveRecordingsImpl(Target target) {
        target = Target.getTargetById(target.id);
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
                var labels = new HashMap<String, String>();
                if (externalRecordingAutoanalyze) {
                    labels.put(AnalysisReportAggregator.AUTOANALYZE_LABEL, Boolean.TRUE.toString());
                }
                // TODO is there any other metadata to attach here?
                var recording = ActiveRecording.from(target, descriptor, new Metadata(labels));
                recording.external = true;
                if (externalRecordingArchive && recording.external) {
                    recording.archiveOnStop = true;
                }
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
            throw e;
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
        return startRecordingImpl(target, replace, template, options, rawLabels);
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
                                QuarkusTransaction.joiningExisting()
                                        .call(
                                                () ->
                                                        getDescriptorByName(conn, recordingName)
                                                                .map(this::mapState)
                                                                .orElse(null)));
        boolean restart = previousState == null || shouldRestartRecording(replace, previousState);
        if (!restart) {
            return Uni.createFrom()
                    .failure(() -> new EntityExistsException("Recording", recordingName));
        }
        getActiveRecording(target, r -> r.name.equals(recordingName))
                .ifPresent(r -> this.deleteRecording(r).await().atMost(connectionFailedTimeout));
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
                                case PRESET:
                                    return conn.getService()
                                            .start(
                                                    recordingOptions,
                                                    presetTemplateService
                                                            .getXml(
                                                                    template.getName(),
                                                                    TemplateType.CUSTOM)
                                                            .orElseThrow());
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

        ActiveRecording recording = ActiveRecording.from(target, desc, meta, options);
        recording.persist();

        target.activeRecordings.add(recording);
        target.persist();

        if (!recording.continuous) {
            JobKey key =
                    JobKey.jobKey(
                            String.format("%s.%d", target.jvmId, recording.remoteId),
                            "recording.fixed-duration");
            JobDetail jobDetail =
                    JobBuilder.newJob(StopRecordingJob.class)
                            .withIdentity(key)
                            .usingJobData(JobInterruptMonitorPlugin.AUTO_INTERRUPTIBLE, "true")
                            .usingJobData("recordingId", recording.id)
                            .build();
            try {
                if (!scheduler.checkExists(key)) {
                    Trigger trigger =
                            TriggerBuilder.newTrigger()
                                    .withIdentity(key.getName(), key.getGroup())
                                    .startAt(
                                            new Date(
                                                    System.currentTimeMillis()
                                                            + recording.duration))
                                    .build();
                    scheduler.scheduleJob(jobDetail, trigger);
                }
            } catch (SchedulerException e) {
                logger.warn(e);
            }
        }

        logger.debugv("Started recording: {0} {1}", target.connectUrl, target.activeRecordings);

        return Uni.createFrom().item(recording);
    }

    public Uni<ActiveRecording> createSnapshot(Target target) {
        return this.createSnapshot(target, Map.of());
    }

    public Uni<ActiveRecording> createSnapshot(
            Target target, Map<String, String> additionalLabels) {
        return connectionManager
                .executeConnectedTaskUni(
                        target,
                        connection -> {
                            IRecordingDescriptor rec =
                                    connection.getService().getSnapshotRecording();
                            try (InputStream snapshot =
                                    remoteRecordingStreamFactory.openDirect(
                                            connection, target, rec)) {
                                if (!snapshotIsReadable(target, snapshot)) {
                                    safeCloseRecording(connection, rec);
                                    throw new SnapshotCreationException(
                                            "Snapshot was not readable - are there any source"
                                                    + " recordings?");
                                }
                            }
                            return rec;
                        })
                .onItem()
                .transform(
                        desc -> {
                            var labels = new HashMap<String, String>(additionalLabels);
                            labels.putAll(
                                    Map.of(
                                            "jvmId",
                                            target.jvmId,
                                            "connectUrl",
                                            target.connectUrl.toString()));
                            return QuarkusTransaction.joiningExisting()
                                    .call(
                                            () -> {
                                                var fTarget = Target.<Target>findById(target.id);
                                                ActiveRecording recording =
                                                        ActiveRecording.from(
                                                                fTarget,
                                                                desc,
                                                                new Metadata(labels));
                                                recording.persist();

                                                fTarget.activeRecordings.add(recording);
                                                fTarget.persist();
                                                return recording;
                                            });
                        });
    }

    private boolean snapshotIsReadable(Target target, InputStream snapshot) throws IOException {
        try {
            if (!connectionManager.markConnectionInUse(target)) {
                throw new IOException(
                        "Target connection unexpectedly closed while streaming recording");
            }
            return snapshot.read() != -1;
        } catch (Exception e) {
            logger.warn(e);
            return false;
        }
    }

    private boolean shouldRestartRecording(RecordingReplace replace, RecordingState state)
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

    public Uni<ActiveRecording> stopRecording(ActiveRecording recording) throws Exception {
        return connectionManager.executeConnectedTask(
                recording.target,
                conn -> {
                    var desc = getDescriptorById(conn, recording.remoteId);
                    if (desc.isEmpty()) {
                        return Uni.createFrom().failure(NotFoundException::new);
                    }
                    if (!desc.get()
                            .getState()
                            .equals(
                                    org.openjdk.jmc.flightrecorder.configuration
                                            .IRecordingDescriptor.RecordingState.STOPPED)) {
                        conn.getService().stop(desc.get());
                    }
                    return Uni.createFrom()
                            .item(
                                    QuarkusTransaction.joiningExisting()
                                            .call(
                                                    () -> {
                                                        ActiveRecording r =
                                                                ActiveRecording.find(
                                                                                "id", recording.id)
                                                                        .singleResult();
                                                        r.state = RecordingState.STOPPED;
                                                        r.persist();
                                                        return r;
                                                    }));
                });
    }

    public Uni<ActiveRecording> deleteRecording(ActiveRecording recording) {
        return connectionManager
                .executeConnectedTaskUni(
                        recording.target,
                        conn -> {
                            getDescriptorById(conn, recording.remoteId)
                                    .ifPresent(d -> safeCloseRecording(conn, d));
                            return null;
                        })
                .onItem()
                .transform(
                        (r) ->
                                QuarkusTransaction.joiningExisting()
                                        .call(
                                                () -> {
                                                    recording.target.activeRecordings.remove(
                                                            recording);
                                                    recording.target.persist();
                                                    recording.delete();
                                                    return recording;
                                                }));
    }

    public LinkedRecordingDescriptor toExternalForm(ActiveRecording recording) {
        return new LinkedRecordingDescriptor(
                recording.id,
                recording.remoteId,
                recording.state,
                recording.duration,
                recording.startTime,
                recording.archiveOnStop,
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
        Supplier<Optional<Template>> preset =
                () -> {
                    try {
                        return presetTemplateService.getTemplates().stream()
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
                    .or(() -> preset.get())
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
            case PRESET:
                return preset.get().orElseThrow();
            default:
                return custom.get()
                        .or(() -> preset.get())
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
        return storage.listObjectsV2(builder.build()).contents();
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

    public ArchivedRecording archiveRecording(ActiveRecording recording) throws Exception {
        // AWS object key name guidelines advise characters to avoid (% so we should not pass url
        // encoded characters)
        String transformedAlias =
                URLDecoder.decode(recording.target.alias, StandardCharsets.UTF_8)
                        .replaceAll("[\\._/]+", "-");
        Instant now = clock.now();
        String timestamp = now.truncatedTo(ChronoUnit.SECONDS).toString().replaceAll("[-:]+", "");
        String filename =
                String.format("%s_%s_%s.jfr", transformedAlias, recording.name, timestamp);
        String key = archivedRecordingKey(recording.target.jvmId, filename);
        String multipartId = null;
        List<Pair<Integer, String>> parts = new ArrayList<>();
        long accum = 0;
        try (var stream = getActiveInputStream(recording, uploadFailedTimeout);
                var ch = Channels.newChannel(stream)) {
            ByteBuffer buf = ByteBuffer.allocate(20 * MIB);
            CreateMultipartUploadRequest.Builder builder =
                    CreateMultipartUploadRequest.builder()
                            .bucket(archiveBucket)
                            .key(key)
                            .contentType(HttpMimeType.JFR.mime())
                            .contentDisposition(
                                    String.format("attachment; filename=\"%s\"", filename));
            switch (storageMode()) {
                case TAGGING:
                    builder = builder.tagging(createActiveRecordingTagging(recording));
                    break;
                case METADATA:
                    builder = builder.metadata(createActiveRecordingMetadata(recording).labels());
                    break;
                case BUCKET:
                    metadataService
                            .get()
                            .create(
                                    recording.target.jvmId,
                                    filename,
                                    createActiveRecordingMetadata(recording));
                    break;
                default:
                    throw new IllegalStateException();
            }
            CreateMultipartUploadRequest request = builder.build();
            multipartId = storage.createMultipartUpload(request).uploadId();
            int read = 0;
            for (int i = 1; i <= S3_API_PART_LIMIT; i++) {
                read = ch.read(buf);

                if (read == 0) {
                    read = retryRead(ch, buf);
                }
                accum += read;
                if (read == -1) {
                    logger.tracev(
                            "Key: {0} completed upload of {1} chunks ({2} bytes)",
                            key, i - 1, accum + 1);
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
                if (i == S3_API_PART_LIMIT) {
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
                        ActiveRecordings.RecordingEventCategory.ARCHIVED_CREATED,
                        ArchivedRecordingEvent.Payload.of(connectUrl, archivedRecording));
        bus.publish(event.category().category(), event.payload().recording());
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(event.category().category(), event.payload()));
        return new ArchivedRecording(
                recording.target.jvmId,
                filename,
                downloadUrl(recording.target.jvmId, filename),
                reportUrl(recording.target.jvmId, filename),
                recording.metadata,
                accum,
                now.getEpochSecond());
    }

    public Optional<ArchivedRecording> getArchivedRecordingInfo(String jvmId, String filename) {
        return listArchivedRecordingObjects(jvmId).stream()
                .filter(
                        item -> {
                            String objectName = item.key().strip();
                            String f = objectName.split("/")[1];
                            return Objects.equals(filename, f);
                        })
                .map(
                        item -> {
                            String objectName = item.key().strip();
                            String f = objectName.split("/")[1];
                            Metadata metadata =
                                    getArchivedRecordingMetadata(jvmId, f)
                                            .orElseGet(Metadata::empty);
                            return new ArchivedRecording(
                                    jvmId,
                                    filename,
                                    downloadUrl(jvmId, f),
                                    reportUrl(jvmId, f),
                                    metadata,
                                    item.size(),
                                    item.lastModified().getEpochSecond());
                        })
                .findFirst();
    }

    public Optional<Metadata> getArchivedRecordingMetadata(String jvmId, String filename) {
        return getArchivedRecordingMetadata(archivedRecordingKey(jvmId, filename));
    }

    public Optional<Metadata> getArchivedRecordingMetadata(String storageKey) {
        try {
            switch (storageMode()) {
                case TAGGING:
                    return Optional.of(
                            taggingToMetadata(
                                    storage.getObjectTagging(
                                                    GetObjectTaggingRequest.builder()
                                                            .bucket(archiveBucket)
                                                            .key(storageKey)
                                                            .build())
                                            .tagSet()));
                case METADATA:
                    var resp =
                            storage.headObject(
                                    HeadObjectRequest.builder()
                                            .bucket(archiveBucket)
                                            .key(storageKey)
                                            .build());
                    if (!resp.hasMetadata()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Metadata(new HashMap<>(resp.metadata())));
                case BUCKET:
                    return metadataService.get().read(storageKey);
                default:
                    throw new IllegalStateException();
            }
        } catch (NoSuchKeyException nske) {
            logger.warn(nske);
            return Optional.empty();
        } catch (IOException ioe) {
            logger.error(ioe);
            return Optional.empty();
        }
    }

    private String decodeBase64(String encoded) {
        return new String(base64Url.decode(encoded), StandardCharsets.UTF_8);
    }

    public static String archivedRecordingKey(String jvmId, String filename) {
        return (jvmId + "/" + filename).strip();
    }

    public static String archivedRecordingKey(Pair<String, String> pair) {
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
        if (parts.length != 2) {
            throw new IllegalArgumentException();
        }
        return Pair.of(parts[0], parts[1]);
    }

    public InputStream getActiveInputStream(ActiveRecording recording, Duration timeout)
            throws Exception {
        return getActiveInputStream(recording.target.id, recording.remoteId, timeout);
    }

    public InputStream getActiveInputStream(long targetId, long remoteId, Duration timeout)
            throws Exception {
        return QuarkusTransaction.joiningExisting()
                .call(
                        () -> {
                            var target = Target.getTargetById(targetId);
                            var recording = target.getRecordingById(remoteId);
                            var stream = remoteRecordingStreamFactory.open(recording, timeout);
                            return stream;
                        });
    }

    public InputStream getArchivedRecordingStream(String jvmId, String recordingName) {
        return getArchivedRecordingStream(encodedKey(jvmId, recordingName));
    }

    public InputStream getArchivedRecordingStream(String encodedKey) {
        String key = new String(base64Url.decode(encodedKey), StandardCharsets.UTF_8);

        GetObjectRequest getRequest =
                GetObjectRequest.builder().bucket(archiveBucket).key(key).build();

        return new BufferedInputStream(storage.getObject(getRequest));
    }

    public String downloadUrl(ActiveRecording recording) {
        return String.format("/api/v4/activedownload/%d", recording.id);
    }

    public String downloadUrl(String jvmId, String filename) {
        return String.format("/api/v4/download/%s", encodedKey(jvmId, filename));
    }

    public String reportUrl(ActiveRecording recording) {
        return String.format(
                "/api/v4/targets/%d/reports/%d", recording.target.id, recording.remoteId);
    }

    public String reportUrl(String jvmId, String filename) {
        return String.format("/api/v4/reports/%s", encodedKey(jvmId, filename));
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

    public HeadObjectResponse assertArchivedRecordingExists(String jvmId, String filename) {
        var key = archivedRecordingKey(jvmId, filename);
        var resp =
                storage.headObject(
                        HeadObjectRequest.builder().bucket(archiveBucket).key(key).build());
        if (!resp.sdkHttpResponse().isSuccessful()) {
            throw new HttpException(
                    resp.sdkHttpResponse().statusCode(),
                    resp.sdkHttpResponse().statusText().orElse(""));
        }
        return resp;
    }

    /* Archived Recording Helpers */
    public void deleteArchivedRecording(String jvmId, String filename) throws IOException {
        assertArchivedRecordingExists(jvmId, filename);
        var metadata = getArchivedRecordingMetadata(jvmId, filename).orElseGet(Metadata::empty);
        var target = Target.getTargetByJvmId(jvmId);

        var key = archivedRecordingKey(jvmId, filename);

        var resp =
                storage.deleteObject(
                        DeleteObjectRequest.builder().bucket(archiveBucket).key(key).build());
        if (!resp.sdkHttpResponse().isSuccessful()) {
            throw new HttpException(
                    resp.sdkHttpResponse().statusCode(),
                    resp.sdkHttpResponse().statusText().orElse(""));
        }

        switch (storageMode()) {
            case TAGGING:
            // fall-through
            case METADATA:
                // no-op - the S3 instance will delete the tagging/metadata associated with the
                // object automatically
                break;
            case BUCKET:
                metadataService.get().delete(jvmId, filename);
                break;
            default:
                throw new IllegalStateException();
        }

        var event =
                new ArchivedRecordingEvent(
                        ActiveRecordings.RecordingEventCategory.ARCHIVED_DELETED,
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

    Metadata createActiveRecordingMetadata(ActiveRecording recording) {
        Map<String, String> labels = new HashMap<>(recording.metadata.labels());
        labels.put("connectUrl", recording.target.connectUrl.toString());
        labels.put("jvmId", recording.target.jvmId);
        Metadata metadata = new Metadata(labels);
        return metadata;
    }

    Tagging createActiveRecordingTagging(ActiveRecording recording) {
        return createMetadataTagging(createActiveRecordingMetadata(recording));
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
        return Tagging.builder().tagSet(tags).build();
    }

    private Metadata taggingToMetadata(List<Tag> tagSet) {
        // TODO parse out other metadata than labels
        var labels = new HashMap<String, String>();
        for (var tag : tagSet) {
            var key = decodeBase64(tag.key());
            var value = decodeBase64(tag.value());
            labels.put(key, value);
        }
        return new Metadata(labels);
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
                                                ActiveRecordings.RecordingEventCategory
                                                        .METADATA_UPDATED,
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

    public static ArchivedRecordingMetadataService.StorageMode storageMode(String name) {
        return Arrays.asList(ArchivedRecordingMetadataService.StorageMode.values()).stream()
                .filter(s -> s.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow();
    }

    private ArchivedRecordingMetadataService.StorageMode storageMode() {
        return storageMode(metadataStorageMode);
    }

    public ArchivedRecording uploadArchivedRecording(
            String jvmId, FileUpload recording, Metadata metadata) throws IOException {
        String filename = recording.fileName().strip();
        if (StringUtils.isBlank(filename)) {
            throw new BadRequestException();
        }
        if (!filename.endsWith(".jfr")) {
            filename = filename + ".jfr";
        }
        Map<String, String> labels = new HashMap<>(metadata.labels());
        labels.put("jvmId", jvmId);
        String key = archivedRecordingKey(jvmId, filename);
        Builder requestBuilder =
                PutObjectRequest.builder()
                        .bucket(archiveBucket)
                        .key(key)
                        .contentType(HttpMimeType.JFR.mime())
                        .contentDisposition(String.format("attachment; filename=\"%s\"", filename));
        switch (storageMode()) {
            case TAGGING:
                requestBuilder =
                        requestBuilder.tagging(createMetadataTagging(new Metadata(labels)));
                break;
            case METADATA:
                requestBuilder = requestBuilder.metadata(labels);
                break;
            case BUCKET:
                metadataService.get().create(jvmId, filename, metadata);
                break;
            default:
                throw new IllegalStateException();
        }
        storage.putObject(requestBuilder.build(), RequestBody.fromFile(recording.filePath()));

        var target = Target.getTargetByJvmId(jvmId);
        ArchivedRecording archivedRecording =
                new ArchivedRecording(
                        jvmId,
                        filename,
                        downloadUrl(jvmId, filename),
                        reportUrl(jvmId, filename),
                        metadata,
                        recording.size(),
                        clock.now().getEpochSecond());
        var event =
                new ArchivedRecordingEvent(
                        ActiveRecordings.RecordingEventCategory.ARCHIVED_CREATED,
                        ArchivedRecordingEvent.Payload.of(
                                target.map(t -> t.connectUrl).orElse(null), archivedRecording));
        bus.publish(event.category().category(), event.payload().recording());
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(event.category().category(), event.payload()));
        // Clean up the recording file after uploading
        try {
            Files.delete(recording.filePath());
        } catch (IOException ioe) {
            logger.warn(ioe);
        }
        return archivedRecording;
    }

    public ArchivedRecording updateArchivedRecordingMetadata(
            String jvmId, String filename, Map<String, String> updatedLabels) throws IOException {
        String key = archivedRecordingKey(jvmId, filename);
        Optional<Metadata> existingMetadataOpt = getArchivedRecordingMetadata(key);

        if (existingMetadataOpt.isEmpty()) {
            throw new NotFoundException(
                    "Could not find metadata for archived recording with key: " + key);
        }

        Metadata updatedMetadata = new Metadata(updatedLabels);

        switch (storageMode()) {
            case TAGGING:
                Tagging tagging = createMetadataTagging(updatedMetadata);
                storage.putObjectTagging(
                        PutObjectTaggingRequest.builder()
                                .bucket(archiveBucket)
                                .key(key)
                                .tagging(tagging)
                                .build());
                break;
            case METADATA:
                throw new BadRequestException(
                        String.format(
                                "Metadata updates are not supported with server configuration"
                                        + " %s=%s",
                                ConfigProperties.STORAGE_METADATA_ARCHIVES_STORAGE_MODE,
                                metadataStorageMode));
            case BUCKET:
                metadataService.get().update(jvmId, filename, updatedMetadata);
                break;
            default:
                throw new IllegalStateException();
        }

        var response = assertArchivedRecordingExists(jvmId, filename);
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

        notifyArchiveMetadataUpdate(jvmId, updatedRecording);
        return updatedRecording;
    }

    private void notifyArchiveMetadataUpdate(String jvmId, ArchivedRecording updatedRecording) {
        var event =
                new ArchivedRecordingEvent(
                        ActiveRecordings.RecordingEventCategory.METADATA_UPDATED,
                        new ArchivedRecordingEvent.Payload(
                                updatedRecording.downloadUrl(), jvmId, updatedRecording));
        bus.publish(event.category().category(), event.payload().recording());
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(event.category().category(), event.payload()));
    }

    public Uni<String> uploadToJFRDatasource(long targetEntityId, long remoteId) throws Exception {
        InputStream is =
                QuarkusTransaction.joiningExisting()
                        .call(
                                () -> {
                                    Target target = Target.getTargetById(targetEntityId);
                                    Objects.requireNonNull(
                                            target, "Target from targetId not found");
                                    ActiveRecording recording = target.getRecordingById(remoteId);
                                    Objects.requireNonNull(
                                            recording, "ActiveRecording from remoteId not found");
                                    return getActiveInputStream(recording, connectionFailedTimeout);
                                });
        return uploadToJFRDatasource(is);
    }

    public Uni<String> uploadToJFRDatasource(Pair<String, String> key) throws Exception {
        // upload an already-archived recording to datasource
        Objects.requireNonNull(key);
        Objects.requireNonNull(key.getKey());
        Objects.requireNonNull(key.getValue());

        if (usePresignedTransfer) {
            return uploadPresignedToJFRDatasource(key.getKey(), key.getValue());
        } else {
            GetObjectRequest getRequest =
                    GetObjectRequest.builder()
                            .bucket(archiveBucket)
                            .key(archivedRecordingKey(key))
                            .build();
            return uploadToJFRDatasource(storage.getObject(getRequest));
        }
    }

    private Uni<String> uploadPresignedToJFRDatasource(String jvmId, String filename)
            throws URISyntaxException {
        var uri = getPresignedPath(jvmId, filename);
        return datasourceClient
                .uploadPresigned(uri.toString())
                .onItem()
                .transform(
                        r -> {
                            try (r) {
                                return r.readEntity(String.class);
                            }
                        });
    }

    private Uni<String> uploadToJFRDatasource(InputStream is)
            throws URISyntaxException, InterruptedException, ExecutionException {
        return datasourceClient
                .upload(is, true)
                .onItem()
                .transform(
                        r -> {
                            try (r) {
                                return r.readEntity(String.class);
                            }
                        });
    }

    private URI getPresignedPath(String jvmId, String filename) throws URISyntaxException {
        logger.infov("Handling presigned download request for {0}/{1}", jvmId, filename);
        GetObjectRequest getRequest =
                GetObjectRequest.builder()
                        .bucket(archiveBucket)
                        .key(RecordingHelper.archivedRecordingKey(Pair.of(jvmId, filename)))
                        .build();
        GetObjectPresignRequest presignRequest =
                GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(1))
                        .getObjectRequest(getRequest)
                        .build();
        return URI.create(presigner.presignGetObject(presignRequest).url().toString()).normalize();
    }

    public record RecordingOptions(
            String name,
            Optional<Boolean> toDisk,
            Optional<Boolean> archiveOnStop,
            Optional<Long> duration,
            Optional<Long> maxSize,
            Optional<Long> maxAge) {
        static RecordingOptions empty(String name) {
            return new RecordingOptions(
                    name,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
        }
    }

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

    @DisallowConcurrentExecution
    static class StopRecordingJob implements Job {

        @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
        Duration connectionTimeout;

        @Inject RecordingHelper recordingHelper;
        @Inject Logger logger;

        @Override
        @Transactional
        public void execute(JobExecutionContext ctx) throws JobExecutionException {
            try {
                ActiveRecording recording =
                        ActiveRecording.find("id", ctx.getMergedJobDataMap().get("recordingId"))
                                .singleResult();
                recordingHelper.stopRecording(recording).await().atMost(connectionTimeout);
            } catch (Exception e) {
                var jee = new JobExecutionException(e);
                jee.setUnscheduleFiringTrigger(true);
                jee.setRefireImmediately(false);
                throw jee;
            }
        }
    }

    public static class SnapshotCreationException extends Exception {
        public SnapshotCreationException(String message) {
            super(message);
        }
    }

    @RegisterRestClient(configKey = "jfr-datasource")
    interface DatasourceClient {
        @POST
        @jakarta.ws.rs.Path("/load")
        Uni<Response> upload(
                @RestForm
                        @PartType(MediaType.APPLICATION_OCTET_STREAM)
                        @PartFilename("cryostat-analysis.jfr")
                        InputStream file,
                @RestQuery boolean overwrite);

        @POST
        @jakarta.ws.rs.Path("/load_presigned")
        @Consumes(MediaType.MULTIPART_FORM_DATA)
        Uni<Response> uploadPresigned(@RestForm("uri") @PartType(MediaType.TEXT_PLAIN) String uri);
    }
}
