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
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import org.openjdk.jmc.flightrecorder.configuration.events.EventOptionID;
import org.openjdk.jmc.flightrecorder.configuration.recording.RecordingOptionsBuilder;
import org.openjdk.jmc.rjmx.services.jfr.IEventTypeInfo;
import org.openjdk.jmc.rjmx.services.jfr.IRecordingDescriptor;

import io.cryostat.ConfigProperties;
import io.cryostat.Producers;
import io.cryostat.core.EventOptionsBuilder;
import io.cryostat.core.net.JFRConnection;
import io.cryostat.core.sys.Clock;
import io.cryostat.core.sys.FileSystem;
import io.cryostat.core.templates.Template;
import io.cryostat.core.templates.TemplateType;
import io.cryostat.recordings.ActiveRecording.Listener.ActiveRecordingEvent;
import io.cryostat.recordings.ActiveRecording.Listener.ArchivedRecordingEvent;
import io.cryostat.recordings.Recordings.ArchivedRecording;
import io.cryostat.recordings.Recordings.LinkedRecordingDescriptor;
import io.cryostat.recordings.Recordings.Metadata;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.util.HttpMimeType;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.ext.web.client.WebClient;
import io.vertx.mutiny.ext.web.multipart.MultipartForm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.ResponseBuilder;
import jdk.jfr.RecordingState;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.jaxrs.ResponseBuilderImpl;
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
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@ApplicationScoped
public class RecordingHelper {

    public static final String JFR_MIME = HttpMimeType.JFR.mime();

    private static final Pattern TEMPLATE_PATTERN =
            Pattern.compile("^template=([\\w]+)(?:,type=([\\w]+))?$");
    public static final String DATASOURCE_FILENAME = "cryostat-analysis.jfr";

    private static final long httpTimeoutSeconds = 5; // TODO: configurable client timeout

    @Inject Logger logger;
    @Inject EntityManager entityManager;
    @Inject TargetConnectionManager connectionManager;
    @Inject RecordingOptionsBuilderFactory recordingOptionsBuilderFactory;
    @Inject EventOptionsBuilder.Factory eventOptionsBuilderFactory;
    @Inject ScheduledExecutorService scheduler;
    @Inject EventBus bus;

    @Inject Clock clock;
    @Inject S3Presigner presigner;

    @Inject
    @Named(Producers.BASE64_URL)
    Base64 base64Url;

    @Inject RemoteRecordingInputStreamFactory remoteRecordingStreamFactory;
    @Inject S3Client storage;
    @Inject FileSystem fs;

    @Inject WebClient webClient;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_ARCHIVES)
    String archiveBucket;

    @ConfigProperty(name = ConfigProperties.AWS_OBJECT_EXPIRATION_LABELS)
    String objectExpirationLabel;

    public ActiveRecording startRecording(
            Target target,
            IConstrainedMap<String> recordingOptions,
            String templateName,
            TemplateType templateType,
            Metadata metadata,
            boolean archiveOnStop,
            RecordingReplace replace,
            JFRConnection connection)
            throws Exception {
        String recordingName = (String) recordingOptions.get(RecordingOptionsBuilder.KEY_NAME);
        TemplateType preferredTemplateType =
                getPreferredTemplateType(connection, templateName, templateType);
        getDescriptorByName(connection, recordingName)
                .ifPresent(
                        previous -> {
                            RecordingState previousState = mapState(previous);
                            boolean restart =
                                    shouldRestartRecording(replace, previousState, recordingName);
                            if (!restart) {
                                throw new BadRequestException(
                                        String.format(
                                                "Recording with name \"%s\" already exists",
                                                recordingName));
                            }
                            if (!ActiveRecording.deleteFromTarget(target, recordingName)) {
                                logger.warnf(
                                        "Could not delete recording %s from target %s",
                                        recordingName, target.alias);
                            }
                        });

        IRecordingDescriptor desc =
                connection
                        .getService()
                        .start(
                                recordingOptions,
                                enableEvents(connection, templateName, preferredTemplateType));

        Map<String, String> labels = metadata.labels();
        labels.put("template.name", templateName);
        labels.put("template.type", preferredTemplateType.name());
        Metadata meta = new Metadata(labels);

        ActiveRecording recording = ActiveRecording.from(target, desc, meta);
        recording.persist();

        target.activeRecordings.add(recording);
        target.persist();

        logger.tracev("Started recording: {0} {1}", target.connectUrl, target.activeRecordings);

        return recording;
    }

    public ActiveRecording createSnapshot(Target target, JFRConnection connection)
            throws Exception {
        IRecordingDescriptor desc = connection.getService().getSnapshotRecording();

        String rename = String.format("%s-%d", desc.getName().toLowerCase(), desc.getId());

        RecordingOptionsBuilder recordingOptionsBuilder =
                recordingOptionsBuilderFactory.create(connection.getService());
        recordingOptionsBuilder.name(rename);

        connection.getService().updateRecordingOptions(desc, recordingOptionsBuilder.build());

        Optional<IRecordingDescriptor> updatedDescriptor = getDescriptorByName(connection, rename);

        if (updatedDescriptor.isEmpty()) {
            throw new IllegalStateException(
                    "The most recent snapshot of the recording cannot be"
                            + " found after renaming.");
        }

        desc = updatedDescriptor.get();

        try (InputStream snapshot = remoteRecordingStreamFactory.open(connection, target, desc)) {
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

    public Pair<String, TemplateType> parseEventSpecifierToTemplate(String eventSpecifier) {
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

    private IConstrainedMap<EventOptionID> enableAllEvents(JFRConnection connection)
            throws Exception {
        EventOptionsBuilder builder = eventOptionsBuilderFactory.create(connection);

        for (IEventTypeInfo eventTypeInfo : connection.getService().getAvailableEventTypes()) {
            builder.addEvent(eventTypeInfo.getEventTypeID().getFullKey(), "enabled", "true");
        }

        return builder.build();
    }

    public IConstrainedMap<EventOptionID> enableEvents(
            JFRConnection connection, String templateName, TemplateType templateType)
            throws Exception {
        if (templateName.equals("ALL")) {
            return enableAllEvents(connection);
        }
        // if template type not specified, try to find a Custom template by that name. If none,
        // fall back on finding a Target built-in template by the name. If not, throw an
        // exception and bail out.
        TemplateType type = getPreferredTemplateType(connection, templateName, templateType);
        return connection.getTemplateService().getEvents(templateName, type).get();
    }

    public TemplateType getPreferredTemplateType(
            JFRConnection connection, String templateName, TemplateType templateType)
            throws Exception {
        if (templateType != null) {
            return templateType;
        }
        if (templateName.equals("ALL")) {
            // special case for the ALL meta-template
            return TemplateType.TARGET;
        }
        List<Template> matchingNameTemplates =
                connection.getTemplateService().getTemplates().stream()
                        .filter(t -> t.getName().equals(templateName))
                        .toList();
        boolean custom =
                matchingNameTemplates.stream()
                        .anyMatch(t -> t.getType().equals(TemplateType.CUSTOM));
        if (custom) {
            return TemplateType.CUSTOM;
        }
        boolean target =
                matchingNameTemplates.stream()
                        .anyMatch(t -> t.getType().equals(TemplateType.TARGET));
        if (target) {
            return TemplateType.TARGET;
        }
        throw new BadRequestException(
                String.format("Invalid/unknown event template %s", templateName));
    }

    static Optional<IRecordingDescriptor> getDescriptorById(
            JFRConnection connection, long remoteId) {
        try {
            return connection.getService().getAvailableRecordings().stream()
                    .filter(r -> remoteId == r.getId())
                    .findFirst();
        } catch (Exception e) {
            throw new ServerErrorException(500, e);
        }
    }

    static Optional<IRecordingDescriptor> getDescriptor(
            JFRConnection connection, ActiveRecording activeRecording) {
        return getDescriptorById(connection, activeRecording.remoteId);
    }

    public static Optional<IRecordingDescriptor> getDescriptorByName(
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

    public String saveRecording(ActiveRecording recording) throws Exception {
        return saveRecording(recording, null);
    }

    public String saveRecording(ActiveRecording recording, Instant expiry) throws Exception {
        // AWS object key name guidelines advise characters to avoid (% so we should not pass url
        // encoded characters)
        String transformedAlias =
                URLDecoder.decode(recording.target.alias, StandardCharsets.UTF_8)
                        .replaceAll("[\\._/]+", "-");
        String timestamp =
                clock.now().truncatedTo(ChronoUnit.SECONDS).toString().replaceAll("[-:]+", "");
        String filename =
                String.format("%s_%s_%s.jfr", transformedAlias, recording.name, timestamp);
        int mib = 1024 * 1024;
        String key = archivedRecordingKey(recording.target.jvmId, filename);
        String multipartId = null;
        List<Pair<Integer, String>> parts = new ArrayList<>();
        try (var stream = remoteRecordingStreamFactory.open(recording);
                var ch = Channels.newChannel(stream)) {
            ByteBuffer buf = ByteBuffer.allocate(20 * mib);
            CreateMultipartUploadRequest.Builder builder =
                    CreateMultipartUploadRequest.builder()
                            .bucket(archiveBucket)
                            .key(key)
                            .contentType(JFR_MIME)
                            .tagging(createActiveRecordingTagging(recording, expiry));
            if (expiry != null && expiry.isAfter(Instant.now())) {
                builder = builder.expires(expiry);
            }
            CreateMultipartUploadRequest request = builder.build();
            multipartId = storage.createMultipartUpload(request).uploadId();
            int read = 0;
            long accum = 0;
            for (int i = 1; i <= 10_000; i++) {
                read = ch.read(buf);

                if (read == 0) {
                    read = retryRead(ch, buf);
                }
                accum += read;
                if (read == -1) {
                    logger.infov("Completed upload of {0} chunks ({1} bytes)", i - 1, accum + 1);
                    logger.infov("Key: {0}", key);
                    break;
                }

                logger.infov("Writing chunk {0} of {1} bytes", i, read);
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
            var event =
                    new ActiveRecordingEvent(
                            Recordings.RecordingEventCategory.ACTIVE_SAVED,
                            ActiveRecordingEvent.Payload.of(this, recording));
            bus.publish(event.category().category(), event.payload().recording());
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(event.category().category(), event.payload()));
        }
        return filename;
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
        var target = Target.<Target>findById(targetId);
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
            logger.info("No bytes read, retrying...");
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

    // jfr-datasource handling
    public Response uploadToJFRDatasource(long targetEntityId, long remoteId, URL uploadUrl)
            throws Exception {
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

        MultipartForm form =
                MultipartForm.create()
                        .binaryFileUpload(
                                "file", DATASOURCE_FILENAME, recordingPath.toString(), JFR_MIME);

        try {
            ResponseBuilder builder = new ResponseBuilderImpl();
            var asyncRequest =
                    webClient
                            .postAbs(uploadUrl.toURI().resolve("/load").normalize().toString())
                            .addQueryParam("overwrite", "true")
                            .timeout(TimeUnit.SECONDS.toMillis(httpTimeoutSeconds))
                            .sendMultipartForm(form);
            return asyncRequest
                    .onItem()
                    .transform(
                            r ->
                                    builder.status(r.statusCode(), r.statusMessage())
                                            .entity(r.bodyAsString())
                                            .build())
                    .onFailure()
                    .recoverWithItem(
                            (failure) -> {
                                logger.error(failure);
                                return Response.serverError().build();
                            })
                    .await()
                    .indefinitely(); // The timeout from the request should be sufficient
        } finally {
            fs.deleteIfExists(recordingPath);
        }
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
                                logger.error(e);
                                throw new BadRequestException(e);
                            }
                        });
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

    static class SnapshotCreationException extends Exception {
        public SnapshotCreationException(String message) {
            super(message);
        }
    }
}
