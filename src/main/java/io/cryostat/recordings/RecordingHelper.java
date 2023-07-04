/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.cryostat.recordings;

import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.configuration.events.EventOptionID;
import org.openjdk.jmc.flightrecorder.configuration.recording.RecordingOptionsBuilder;
import org.openjdk.jmc.rjmx.services.jfr.IEventTypeInfo;
import org.openjdk.jmc.rjmx.services.jfr.IRecordingDescriptor;

import io.cryostat.core.net.JFRConnection;
import io.cryostat.core.sys.Clock;
import io.cryostat.core.templates.Template;
import io.cryostat.core.templates.TemplateType;
import io.cryostat.recordings.ActiveRecording.Listener.RecordingEvent;
import io.cryostat.recordings.Recordings.LinkedRecordingDescriptor;
import io.cryostat.recordings.Recordings.Metadata;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Blocking;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ServerErrorException;
import jdk.jfr.RecordingState;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

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
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
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

    @ConfigProperty(name = "storage.buckets.archives.name")
    String archiveBucket;

    @Blocking
    @Transactional
    public LinkedRecordingDescriptor startRecording(
            Target target,
            IConstrainedMap<String> recordingOptions,
            String templateName,
            TemplateType templateType,
            Metadata metadata,
            Boolean archiveOnStop,
            Boolean restart,
            JFRConnection connection)
            throws Exception {
        String recordingName = (String) recordingOptions.get(RecordingOptionsBuilder.KEY_NAME);
        TemplateType preferredTemplateType =
                getPreferredTemplateType(connection, templateName, templateType);
        Optional<IRecordingDescriptor> previous = getDescriptorByName(connection, recordingName);
        if (previous.isPresent()) {
            if (!restart) {
                throw new BadRequestException(
                        String.format("Recording with name \"%s\" already exists", recordingName));
            } else {
                ActiveRecording.deleteByName(recordingName);
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

    public List<S3Object> listArchivedRecordingObjects() {
      return storage.listObjectsV2(ListObjectsV2Request.builder().bucket(archiveBucket).build()).contents();
    }

    @Blocking
    public String saveRecording(Target target, ActiveRecording activeRecording) throws Exception {
        // AWS object key name guidelines advise characters to avoid (% so we should not pass url encoded characters)
        String transformedAlias = URLDecoder.decode(target.alias, StandardCharsets.UTF_8).replaceAll("/", ".");
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
                accum += read;
                if (read == -1) {
                    logger.infov("Completed upload of {0} chunks ({1} bytes)", i - 1, accum);
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
            e.printStackTrace();
        }
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(
                        "ActiveRecordingSaved",
                        new RecordingEvent(target.connectUrl, activeRecording.toExternalForm())));
        return filename;
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
}
