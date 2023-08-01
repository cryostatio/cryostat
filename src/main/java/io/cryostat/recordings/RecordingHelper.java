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
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.configuration.events.EventOptionID;
import org.openjdk.jmc.flightrecorder.configuration.recording.RecordingOptionsBuilder;
import org.openjdk.jmc.rjmx.services.jfr.IEventTypeInfo;
import org.openjdk.jmc.rjmx.services.jfr.IRecordingDescriptor;

import io.cryostat.ConfigProperties;
import io.cryostat.core.net.JFRConnection;
import io.cryostat.core.sys.Clock;
import io.cryostat.core.sys.FileSystem;
import io.cryostat.core.templates.Template;
import io.cryostat.core.templates.TemplateType;
import io.cryostat.recordings.ActiveRecording.Listener.RecordingEvent;
import io.cryostat.recordings.Recordings.LinkedRecordingDescriptor;
import io.cryostat.recordings.Recordings.Metadata;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.util.HttpMimeType;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Blocking;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.ext.web.client.WebClient;
import io.vertx.mutiny.ext.web.multipart.MultipartForm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
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
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@ApplicationScoped
public class RecordingHelper {

    private static final String JFR_MIME = "application/jfr";
    private static final Pattern TEMPLATE_PATTERN =
            Pattern.compile("^template=([\\w]+)(?:,type=([\\w]+))?$");
    private final Base64 base64Url = new Base64(0, null, true);
    public static final String DATASOURCE_FILENAME = "cryostat-analysis.jfr";

    private final long httpTimeoutSeconds = 5; // TODO: configurable client timeout

    @Inject Logger logger;
    @Inject TargetConnectionManager connectionManager;
    @Inject RecordingOptionsBuilderFactory recordingOptionsBuilderFactory;
    @Inject EventOptionsBuilder.Factory eventOptionsBuilderFactory;
    @Inject ScheduledExecutorService scheduler;
    @Inject EventBus bus;

    @Inject Clock clock;
    @Inject S3Presigner presigner;
    @Inject RemoteRecordingInputStreamFactory remoteRecordingStreamFactory;
    @Inject ObjectMapper mapper;
    @Inject S3Client storage;
    @Inject FileSystem fs;

    @Inject WebClient webClient;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_ARCHIVES)
    String archiveBucket;

    boolean shouldRestartRecording(
            RecordingReplace replace, RecordingState state, String recordingName)
            throws BadRequestException {
        switch (replace) {
            case ALWAYS:
                return true;
            case NEVER:
                return false;
            case STOPPED:
                if (state == RecordingState.RUNNING) {
                    throw new BadRequestException(
                            String.format(
                                    "replace=='STOPPED' but recording with name \"%s\" is already"
                                            + " running",
                                    recordingName));
                }
                return state == RecordingState.STOPPED;
            default:
                return true;
        }
    }

    @Blocking
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public LinkedRecordingDescriptor startRecording(
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
        Optional<IRecordingDescriptor> previous = getDescriptorByName(connection, recordingName);
        if (previous.isPresent()) {
            RecordingState previousState = mapState(previous.get());
            boolean restart = shouldRestartRecording(replace, previousState, recordingName);
            if (!restart) {
                throw new BadRequestException(
                        String.format("Recording with name \"%s\" already exists", recordingName));
            }
            if (!ActiveRecording.deleteFromTarget(target, recordingName)) {
                logger.warnf(
                        "Could not delete recording %s from target %s",
                        recordingName, target.alias);
            }
        }

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
        return new LinkedRecordingDescriptor(
                desc.getId(),
                mapState(desc),
                desc.getDuration().in(UnitLookup.MILLISECOND).longValue(),
                desc.getStartTime().in(UnitLookup.EPOCH_MS).longValue(),
                desc.isContinuous(),
                desc.getToDisk(),
                desc.getMaxSize().in(UnitLookup.BYTE).longValue(),
                desc.getMaxAge().in(UnitLookup.MILLISECOND).longValue(),
                desc.getName(),
                "TODO",
                "TODO",
                meta);
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

    @Blocking
    public List<S3Object> listArchivedRecordingObjects() {
        return storage.listObjectsV2(ListObjectsV2Request.builder().bucket(archiveBucket).build())
                .contents();
    }

    @Blocking
    public String saveRecording(Target target, ActiveRecording activeRecording) throws Exception {
        // AWS object key name guidelines advise characters to avoid (% so we should not pass url
        // encoded characters)
        String transformedAlias =
                URLDecoder.decode(target.alias, StandardCharsets.UTF_8).replaceAll("[\\._/]+", "-");
        String timestamp =
                clock.now().truncatedTo(ChronoUnit.SECONDS).toString().replaceAll("[-:]+", "");
        String filename =
                String.format("%s_%s_%s.jfr", transformedAlias, activeRecording.name, timestamp);
        int mib = 1024 * 1024;
        String key = String.format("%s/%s", target.jvmId, filename);
        String multipartId = null;
        List<Pair<Integer, String>> parts = new ArrayList<>();
        try (var stream = remoteRecordingStreamFactory.open(target, activeRecording);
                var ch = Channels.newChannel(stream)) {
            ByteBuffer buf = ByteBuffer.allocate(20 * mib);
            multipartId =
                    storage.createMultipartUpload(
                                    CreateMultipartUploadRequest.builder()
                                            .bucket(archiveBucket)
                                            .key(key)
                                            .contentType(JFR_MIME)
                                            .tagging(
                                                    createMetadataTagging(activeRecording.metadata))
                                            .build())
                            .uploadId();
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
                                                .build(),
                                        RequestBody.fromByteBuffer(buf))
                                .eTag();
                parts.add(Pair.of(i, eTag));
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
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(
                        "ActiveRecordingSaved",
                        new RecordingEvent(target.connectUrl, activeRecording.toExternalForm())));
        return filename;
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
                        .key(String.format("%s/%s", jvmId, filename))
                        .build());
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(
                        "ArchivedRecordingDeleted",
                        new RecordingEvent(URI.create("localhost:0"), Map.of("name", filename))));
    }

    // Metadata
    private Tagging createMetadataTagging(Metadata metadata) {
        // TODO attach other metadata than labels somehow. Prefixed keys to create partitioning?
        return Tagging.builder()
                .tagSet(
                        metadata.labels().entrySet().stream()
                                .map(
                                        e ->
                                                Tag.builder()
                                                        .key(
                                                                base64Url.encodeAsString(
                                                                        e.getKey().getBytes()))
                                                        .value(
                                                                base64Url.encodeAsString(
                                                                        e.getValue().getBytes()))
                                                        .build())
                                .toList())
                .build();
    }

    // jfr-datasource handling
    public Response doPost(long targetEntityId, String recordingName, URL uploadUrl)
            throws Exception {
        Target target = Target.findById(targetEntityId);
        Path recordingPath =
                connectionManager.executeConnectedTask(
                        target,
                        connection -> {
                            return getRecordingCopyPath(
                                            connection, target.targetId(), recordingName)
                                    .orElseThrow(
                                            () ->
                                                    new RecordingNotFoundException(
                                                            target.targetId(), recordingName));
                        });

        MultipartForm form =
                MultipartForm.create()
                        .binaryFileUpload(
                                "file",
                                DATASOURCE_FILENAME,
                                recordingPath.toString(),
                                HttpMimeType.OCTET_STREAM.toString());

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
                    .indefinitely(); // The timeout in from the webClient request should be
            // sufficient
        } finally {
            fs.deleteIfExists(recordingPath);
        }
    }

    Optional<Path> getRecordingCopyPath(
            JFRConnection connection, String targetId, String recordingName) throws Exception {
        return connection.getService().getAvailableRecordings().stream()
                .filter(recording -> recording.getName().equals(recordingName))
                .findFirst()
                .map(
                        descriptor -> {
                            try {
                                Path tempFile = fs.createTempFile(null, null);
                                try (InputStream stream =
                                        connection.getService().openStream(descriptor, false)) {
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
}
