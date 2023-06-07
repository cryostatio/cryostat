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
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.common.unit.IOptionDescriptor;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.configuration.events.EventOptionID;
import org.openjdk.jmc.flightrecorder.configuration.recording.RecordingOptionsBuilder;
import org.openjdk.jmc.rjmx.services.jfr.FlightRecorderException;
import org.openjdk.jmc.rjmx.services.jfr.IEventTypeInfo;
import org.openjdk.jmc.rjmx.services.jfr.IFlightRecorderService;
import org.openjdk.jmc.rjmx.services.jfr.IRecordingDescriptor;

import io.cryostat.core.net.JFRConnection;
import io.cryostat.core.sys.Clock;
import io.cryostat.core.templates.Template;
import io.cryostat.core.templates.TemplateType;
import io.cryostat.recordings.ActiveRecording.Listener.RecordingEvent;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.util.HttpStatusCodeIdentifier;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.Response;
import jdk.jfr.RecordingState;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

@Path("")
public class Recordings {

    private static final String JFR_MIME = "application/jfr";
    private static final Pattern TEMPLATE_PATTERN =
            Pattern.compile("^template=([\\w]+)(?:,type=([\\w]+))?$");

    @Inject Logger logger;
    @Inject TargetConnectionManager connectionManager;
    @Inject EventBus bus;
    @Inject RecordingOptionsBuilderFactory recordingOptionsBuilderFactory;
    @Inject EventOptionsBuilder.Factory eventOptionsBuilderFactory;
    @Inject Clock clock;
    @Inject S3Client storage;
    @Inject RemoteRecordingInputStreamFactory remoteRecordingStreamFactory;
    @Inject ScheduledExecutorService scheduler;

    @ConfigProperty(name = "storage.buckets.archives.name")
    String archiveBucket;

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
    @Path("/api/v1/recordings")
    @RolesAllowed("read")
    public List<ArchivedRecording> listArchivesV1() {
        var result = new ArrayList<ArchivedRecording>();
        storage.listObjectsV2(ListObjectsV2Request.builder().bucket(archiveBucket).build())
                .contents()
                .forEach(
                        item -> {
                            String path = item.key();
                            String[] parts = path.split("/");
                            String jvmId = parts[0];
                            String filename = parts[1];
                            Metadata metadata =
                                    new Metadata(
                                            storage
                                                    .getObjectTagging(
                                                            GetObjectTaggingRequest.builder()
                                                                    .bucket(archiveBucket)
                                                                    .key(path)
                                                                    .build())
                                                    .tagSet()
                                                    .stream()
                                                    .collect(
                                                            Collectors.toMap(
                                                                    Tag::key, Tag::value)));
                            result.add(
                                    new ArchivedRecording(
                                            filename,
                                            "TODO",
                                            "TODO",
                                            metadata,
                                            item.size(),
                                            item.lastModified().getEpochSecond()));
                        });
        return result;
    }

    @POST
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
    @Path("/api/beta/recordings/{jvmId}")
    @RolesAllowed("write")
    public void agentPush(
            @RestPath String jvmId,
            @RestForm("recording") FileUpload recording,
            @RestForm("labels") JsonObject rawLabels,
            @RestForm("maxFiles") int maxFiles)
            throws Exception {
        int max = Integer.MAX_VALUE;
        if (maxFiles > 0) {
            max = maxFiles;
        }
        Map<String, String> labels = new HashMap<>();
        if (rawLabels != null) {
            rawLabels.getMap().forEach((k, v) -> labels.put(k, v.toString()));
        }
        Metadata metadata = new Metadata(labels);
        logger.infov(
                "recording:{0}, labels:{1}, maxFiles:{2}", recording.fileName(), labels, maxFiles);
        doUpload(recording, metadata, jvmId);
        var objs = new ArrayList<S3Object>();
        storage.listObjectsV2(
                        ListObjectsV2Request.builder().bucket(archiveBucket).prefix(jvmId).build())
                .contents()
                .iterator()
                .forEachRemaining(objs::add);
        var toRemove =
                objs.stream()
                        .sorted((a, b) -> b.lastModified().compareTo(a.lastModified()))
                        .skip(max)
                        .toList();
        if (toRemove.isEmpty()) {
            return;
        }
        logger.infov("Removing {0}", toRemove);
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(
                        "ArchivedRecordingDeleted",
                        new RecordingEvent(
                                URI.create(jvmId),
                                new ArchivedRecording(
                                        recording.fileName(),
                                        "TODO",
                                        "TODO",
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
        storage.listObjectsV2(
                        ListObjectsV2Request.builder().bucket(archiveBucket).prefix(jvmId).build())
                .contents()
                .forEach(
                        item -> {
                            String objectName = item.key();
                            String filename = objectName.split("/")[1];
                            Metadata metadata =
                                    new Metadata(
                                            storage
                                                    .getObjectTagging(
                                                            GetObjectTaggingRequest.builder()
                                                                    .bucket(archiveBucket)
                                                                    .key(objectName)
                                                                    .build())
                                                    .tagSet()
                                                    .stream()
                                                    .collect(
                                                            Collectors.toMap(
                                                                    Tag::key, Tag::value)));
                            result.add(
                                    new ArchivedRecording(
                                            filename,
                                            "TODO",
                                            "TODO",
                                            metadata,
                                            item.size(),
                                            item.lastModified().getEpochSecond()));
                        });
        return result;
    }

    @DELETE
    @Path("/api/beta/recordings/{jvmId}/{filename}")
    @RolesAllowed("write")
    public void agentDelete(
            @RestPath String jvmId,
            @RestPath String filename,
            @RestForm("recording") FileUpload recording,
            @RestForm("labels") JsonObject rawLabels)
            throws Exception {
        storage.deleteObject(
                DeleteObjectRequest.builder()
                        .bucket(archiveBucket)
                        .key(String.format("%s/%s", jvmId, filename))
                        .build());
    }

    @Blocking
    Map<String, Object> doUpload(FileUpload recording, Metadata metadata, String jvmId) {
        logger.infov(
                "Upload: {0} {1} {2} {3}",
                recording.name(), recording.fileName(), recording.filePath(), metadata.labels);
        String filename = recording.fileName();
        if (StringUtils.isBlank(filename)) {
            throw new BadRequestException();
        }
        if (!filename.endsWith(".jfr")) {
            filename = filename + ".jfr";
        }
        long filesize = recording.size();
        logger.infov("Uploading {0} ({1} bytes) to S3 storage...", filename, filesize);
        storage.putObject(
                PutObjectRequest.builder()
                        .bucket(archiveBucket)
                        .key(String.format("%s/%s", jvmId, filename))
                        .contentType(JFR_MIME)
                        .tagging(
                                Tagging.builder()
                                        .tagSet(
                                                metadata.labels.entrySet().stream()
                                                        .map(
                                                                e ->
                                                                        Tag.builder()
                                                                                .key(e.getKey())
                                                                                .value(e.getValue())
                                                                                .build())
                                                        .toList())
                                        .build())
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
                                        "TODO",
                                        "TODO",
                                        metadata,
                                        0 /*filesize*/,
                                        clock.getMonotonicTime()))));

        return Map.of("name", filename, "metadata", Map.of("labels", metadata.labels));
    }

    @DELETE
    @Path("/api/v1/recordings/{filename}")
    @RolesAllowed("write")
    @Blocking
    public void delete(@RestPath String filename) throws Exception {
        storage.deleteObject(
                DeleteObjectRequest.builder()
                        .bucket(archiveBucket)
                        .key(String.format("%s/%s", "uploads", filename))
                        .build());
    }

    @GET
    @Path("/api/beta/fs/recordings")
    @RolesAllowed("read")
    public Collection<ArchivedRecordingDirectory> listFsArchives() {
        var map = new HashMap<String, ArchivedRecordingDirectory>();
        storage.listObjectsV2(ListObjectsV2Request.builder().bucket(archiveBucket).build())
                .contents()
                .forEach(
                        item -> {
                            String path = item.key();
                            String[] parts = path.split("/");
                            String jvmId = parts[0];
                            List<Tag> tags =
                                    storage.getObjectTagging(
                                                    GetObjectTaggingRequest.builder()
                                                            .bucket(archiveBucket)
                                                            .key(path)
                                                            .build())
                                            .tagSet();
                            String connectUrl =
                                    tags.stream()
                                            .map(Tag::key)
                                            .filter(key -> key.equals("connectUrl"))
                                            .findFirst()
                                            .orElseGet(() -> "lost-" + jvmId);
                            var dir =
                                    map.computeIfAbsent(
                                            jvmId,
                                            id ->
                                                    new ArchivedRecordingDirectory(
                                                            connectUrl, id, new ArrayList<>()));
                            String filename = parts[1];
                            Metadata metadata =
                                    new Metadata(
                                            storage
                                                    .getObjectTagging(
                                                            GetObjectTaggingRequest.builder()
                                                                    .bucket(archiveBucket)
                                                                    .key(path)
                                                                    .build())
                                                    .tagSet()
                                                    .stream()
                                                    .collect(
                                                            Collectors.toMap(
                                                                    Tag::key, Tag::value)));
                            dir.recordings.add(
                                    new ArchivedRecording(
                                            filename,
                                            "TODO",
                                            "TODO",
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
        return target.activeRecordings.stream().map(ActiveRecording::toExternalForm).toList();
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
                return saveRecording(target, activeRecording);
            default:
                throw new BadRequestException(body);
        }
    }

    @Blocking
    String saveRecording(Target target, ActiveRecording activeRecording) throws Exception {
        String timestamp =
                clock.now().truncatedTo(ChronoUnit.SECONDS).toString().replaceAll("[-:]+", "");
        String filename =
                String.format("%s_%s_%s.jfr", target.alias, activeRecording.name, timestamp);
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
                                                    // TODO attach other metadata than labels
                                                    // somehow
                                                    Tagging.builder()
                                                            .tagSet(
                                                                    activeRecording
                                                                            .metadata
                                                                            .labels
                                                                            .entrySet()
                                                                            .stream()
                                                                            .map(
                                                                                    e ->
                                                                                            Tag
                                                                                                    .builder()
                                                                                                    .key(
                                                                                                            e
                                                                                                                    .getKey())
                                                                                                    .value(
                                                                                                            e
                                                                                                                    .getValue())
                                                                                                    .build())
                                                                            .toList())
                                                            .build())
                                            .build())
                            .uploadId();
            int read = 0;
            long accum = 0;
            for (int i = 1; i <= 10_000; i++) {
                read = ch.read(buf);
                accum += read;
                if (read == -1) {
                    logger.infov("Completed upload of {0} chunks ({1} bytes)", i - 1, accum);
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
                storage.abortMultipartUpload(
                        AbortMultipartUploadRequest.builder()
                                .bucket(archiveBucket)
                                .key(key)
                                .uploadId(multipartId)
                                .build());
            } catch (Exception e2) {
                logger.error("Could not abort S3 multipart upload", e2);
            }
            throw e;
        }
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
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(
                        "ActiveRecordingSaved",
                        new RecordingEvent(target.connectUrl, activeRecording.toExternalForm())));
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(
                        "ArchivedRecordingCreated",
                        new RecordingEvent(target.connectUrl, activeRecording.toExternalForm())));
        return filename;
    }

    @PATCH
    @Transactional
    @Path("/api/v1/targets/{connectUrl}/recordings/{recordingName}")
    @RolesAllowed("write")
    public Response patchV1(@RestPath URI connectUrl, @RestPath String recordingName, String body)
            throws Exception {
        Target target = Target.getTargetByConnectUrl(connectUrl);
        Optional<IRecordingDescriptor> recording =
                connectionManager.executeConnectedTask(
                        target, conn -> getDescriptorByName(conn, recordingName));
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

    @Transactional
    @POST
    @Path("/api/v3/targets/{id}/recordings")
    @RolesAllowed("write")
    public LinkedRecordingDescriptor createRecording(
            @RestPath long id,
            @RestForm String recordingName,
            @RestForm String events,
            @RestForm Optional<Long> duration,
            @RestForm Optional<Boolean> toDisk,
            @RestForm Optional<Long> maxAge,
            @RestForm Optional<Long> maxSize,
            @RestForm Optional<String> metadata,
            @RestForm Optional<Boolean> archiveOnStop)
            throws Exception {
        if (StringUtils.isBlank(recordingName)) {
            throw new BadRequestException("\"recordingName\" form parameter must be provided");
        }
        if (StringUtils.isBlank(events)) {
            throw new BadRequestException("\"events\" form parameter must be provided");
        }

        Target target = Target.find("id", id).singleResult();
        LinkedRecordingDescriptor descriptor =
                connectionManager.executeConnectedTask(
                        target,
                        connection -> {
                            Optional<IRecordingDescriptor> previous =
                                    getDescriptorByName(connection, recordingName);
                            if (previous.isPresent()) {
                                throw new BadRequestException(
                                        String.format(
                                                "Recording with name \"%s\" already exists",
                                                recordingName));
                            }

                            RecordingOptionsBuilder optionsBuilder =
                                    recordingOptionsBuilderFactory
                                            .create(connection.getService())
                                            .name(recordingName);
                            if (duration.isPresent()) {
                                optionsBuilder.duration(duration.get());
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
                            // if (attrs.contains("metadata")) {
                            //     metadata =
                            //             gson.fromJson(
                            //                     attrs.get("metadata"),
                            //                     new TypeToken<Metadata>() {}.getType());
                            // }
                            IConstrainedMap<String> recordingOptions = optionsBuilder.build();

                            Pair<String, TemplateType> template =
                                    parseEventSpecifierToTemplate(events);
                            String templateName = template.getKey();
                            TemplateType templateType = template.getValue();
                            TemplateType preferredTemplateType =
                                    getPreferredTemplateType(
                                            connection, templateName, templateType);

                            IRecordingDescriptor desc =
                                    connection
                                            .getService()
                                            .start(
                                                    recordingOptions,
                                                    enableEvents(
                                                            connection,
                                                            templateName,
                                                            preferredTemplateType));

                            Metadata meta =
                                    new Metadata(
                                            Map.of(
                                                    "template.name",
                                                    templateName,
                                                    "template.type",
                                                    preferredTemplateType.name()));
                            return new LinkedRecordingDescriptor(
                                    desc.getId(),
                                    mapState(desc),
                                    TimeUnit.SECONDS.toMillis(duration.orElse(0L)),
                                    desc.getStartTime().in(UnitLookup.EPOCH_MS).longValue(),
                                    desc.isContinuous(),
                                    desc.getToDisk(),
                                    desc.getMaxSize().in(UnitLookup.BYTE).longValue(),
                                    desc.getMaxAge().in(UnitLookup.MILLISECOND).longValue(),
                                    desc.getName(),
                                    "TODO",
                                    "TODO",
                                    meta);
                        });

        ActiveRecording recording = ActiveRecording.from(target, descriptor);
        recording.persist();
        target.activeRecordings.add(recording);
        target.persist();

        if (recording.duration > 0) {
            scheduler.schedule(
                    () -> stopRecording(target.id, recording.remoteId, archiveOnStop.orElse(false)),
                    recording.duration,
                    TimeUnit.MILLISECONDS);
        }

        return descriptor;
    }

    @Transactional
    void stopRecording(long targetId, long recordingId, boolean archive) {
        try {
            ActiveRecording recording = ActiveRecording.find("id", recordingId).singleResult();
            recording.state = RecordingState.STOPPED;
            recording.persist();
            if (archive) {
                saveRecording(Target.find("id", targetId).singleResult(), recording);
            }
        } catch (Exception e) {
            logger.error("couldn't update recording", e);
        }
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

    static Optional<IRecordingDescriptor> getDescriptorByName(
            JFRConnection connection, String recordingName) {
        try {
            return connection.getService().getAvailableRecordings().stream()
                    .filter(r -> Objects.equals(r.getName(), recordingName))
                    .findFirst();
        } catch (Exception e) {
            throw new ServerErrorException(500, e);
        }
    }

    @Transactional
    @POST
    @Path("/api/v1/targets/{connectUrl}/recordings")
    @RolesAllowed("write")
    public LinkedRecordingDescriptor createRecordingV1(
            @RestPath URI connectUrl,
            @RestForm String recordingName,
            @RestForm String events,
            @RestForm Optional<Long> duration,
            @RestForm Optional<Boolean> toDisk,
            @RestForm Optional<Long> maxAge,
            @RestForm Optional<Long> maxSize,
            @RestForm Optional<String> metadata,
            @RestForm Optional<Boolean> archiveOnStop)
            throws Exception {
        Target target = Target.getTargetByConnectUrl(connectUrl);
        return createRecording(
                target.id,
                recordingName,
                events,
                duration,
                archiveOnStop,
                maxAge,
                maxSize,
                metadata,
                archiveOnStop);
    }

    @Transactional
    @DELETE
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

    @Transactional
    @DELETE
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

    @Blocking
    @DELETE
    @Path("/api/beta/fs/recordings/{jvmId}/{filename}")
    @RolesAllowed("write")
    public void deleteArchivedRecording(@RestPath String jvmId, @RestPath String filename)
            throws Exception {

        var item =
                storage.getObjectTagging(
                        GetObjectTaggingRequest.builder()
                                .bucket(archiveBucket)
                                .key(String.format("%s/%s", jvmId, filename))
                                .build());

        String connectUrl =
                item.tagSet().stream()
                        .map(Tag::key)
                        .filter(key -> key.equals("connectUrl"))
                        .findFirst()
                        .orElseGet(() -> "lost-" + jvmId);
        storage.deleteObject(
                DeleteObjectRequest.builder()
                        .bucket(archiveBucket)
                        .key(String.format("%s/%s", jvmId, filename))
                        .build());
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(
                        "ArchivedRecordingDeleted",
                        new RecordingEvent(URI.create(connectUrl), Map.of("name", filename))));
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

    @GET
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

    private static Pair<String, TemplateType> parseEventSpecifierToTemplate(String eventSpecifier) {
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

    private static TemplateType getPreferredTemplateType(
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

    private IConstrainedMap<EventOptionID> enableEvents(
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

    private IConstrainedMap<EventOptionID> enableAllEvents(JFRConnection connection)
            throws Exception {
        EventOptionsBuilder builder = eventOptionsBuilderFactory.create(connection);

        for (IEventTypeInfo eventTypeInfo : connection.getService().getAvailableEventTypes()) {
            builder.addEvent(eventTypeInfo.getEventTypeID().getFullKey(), "enabled", "true");
        }

        return builder.build();
    }

    public record LinkedRecordingDescriptor(
            long id,
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
        public static LinkedRecordingDescriptor from(ActiveRecording recording) {
            return new LinkedRecordingDescriptor(
                    recording.remoteId,
                    recording.state,
                    recording.duration,
                    recording.startTime,
                    recording.continuous,
                    recording.toDisk,
                    recording.maxSize,
                    recording.maxAge,
                    recording.name,
                    "TODO",
                    "TODO",
                    recording.metadata);
        }
    }

    public record ArchivedRecording(
            String name,
            String downloadUrl,
            String reportUrl,
            Metadata metadata,
            long size,
            long archivedTime) {}

    public record ArchivedRecordingDirectory(
            String connectUrl, String jvmId, List<ArchivedRecording> recordings) {}

    public record Metadata(Map<String, String> labels) {}
}
