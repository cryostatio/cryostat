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
package io.cryostat.diagnostic;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import io.cryostat.ConfigProperties;
import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.targets.AgentClient;
import io.cryostat.targets.Target;
import io.cryostat.util.HttpMimeType;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

@Path("/api/beta/diagnostics/")
public class UnifiedLogs {

    static final Pattern SAFE_PARAM_PATTERN = Pattern.compile("^[A-Za-z0-9,+*=]+$");

    @Inject S3Client storage;
    @Inject S3Presigner presigner;
    @Inject Logger log;
    @Inject DiagnosticsHelper helper;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_UNIFIED_LOGS)
    String logsBucket;

    @ConfigProperty(name = ConfigProperties.STORAGE_PRESIGNED_DOWNLOADS_ENABLED)
    boolean presignedDownloadsEnabled;

    @ConfigProperty(name = ConfigProperties.STORAGE_EXT_URL)
    Optional<String> externalStorageUrl;

    @Path("targets/{targetId}/unified-logging")
    @RolesAllowed("write")
    @Blocking
    @POST
    public UnifiedLog enableUnifiedLogging(
            @RestPath long targetId,
            @QueryParam("what") String what,
            @QueryParam("decorators") String decorators) {
        validateLoggingParams(what, decorators);
        Target target =
                QuarkusTransaction.requiringNew().call(() -> Target.getTargetById(targetId));
        if (!target.isAgent()) {
            throw new BadRequestException("Log collection requires an Agent-monitored target");
        }
        io.cryostat.diagnostic.UnifiedLog session =
                QuarkusTransaction.requiringNew()
                        .call(
                                () -> {
                                    io.cryostat.diagnostic.UnifiedLog entity =
                                            io.cryostat.diagnostic.UnifiedLog.enable(
                                                    target, what, decorators);
                                    entity.persist();
                                    return entity;
                                });
        try {
            helper.enableUnifiedLogging(target, what, decorators);
        } catch (Exception e) {
            QuarkusTransaction.requiringNew()
                    .run(
                            () -> {
                                io.cryostat.diagnostic.UnifiedLog s =
                                        io.cryostat.diagnostic.UnifiedLog.findById(session.id);
                                s.markFailed();
                                s.persist();
                            });
            throw e;
        }
        return new UnifiedLog(target.jvmId, null, null, session.enabledAt / 1000, 0, Metadata.empty());
    }

    @Path("targets/{targetId}/unified-logging")
    @RolesAllowed("write")
    @Blocking
    @PATCH
    public UnifiedLog reconfigureUnifiedLogging(
            @RestPath long targetId,
            @QueryParam("what") String what,
            @QueryParam("decorators") String decorators) {
        validateLoggingParams(what, decorators);
        Target target =
                QuarkusTransaction.requiringNew().call(() -> Target.getTargetById(targetId));
        if (!target.isAgent()) {
            throw new BadRequestException("Log collection requires an Agent-monitored target");
        }
        AgentClient.UnifiedLogStatus status = helper.unifiedLogStatus(target);
        if (!status.enabled()) {
            throw new ClientErrorException(Response.Status.CONFLICT);
        }
        Long sessionId =
                QuarkusTransaction.requiringNew()
                        .call(
                                () ->
                                        io.cryostat.diagnostic.UnifiedLog
                                                .<io.cryostat.diagnostic.UnifiedLog>find(
                                                        "target", target)
                                                .firstResultOptional()
                                                .map(
                                                        s -> {
                                                            s.markReconfigured(what, decorators);
                                                            s.persist();
                                                            return s.id;
                                                        })
                                                .orElse(null));
        try {
            helper.reconfigureUnifiedLogging(target, what, decorators);
        } catch (Exception e) {
            if (sessionId != null) {
                QuarkusTransaction.requiringNew()
                        .run(
                                () -> {
                                    io.cryostat.diagnostic.UnifiedLog s =
                                            io.cryostat.diagnostic.UnifiedLog.findById(sessionId);
                                    s.markFailed();
                                    s.persist();
                                });
            }
            throw e;
        }
        return new UnifiedLog(
                target.jvmId, null, null, System.currentTimeMillis() / 1000, 0, Metadata.empty());
    }

    @Path("targets/{targetId}/unified-logging")
    @RolesAllowed("write")
    @Blocking
    @DELETE
    public void disableUnifiedLogging(@RestPath long targetId) {
        Target target =
                QuarkusTransaction.requiringNew().call(() -> Target.getTargetById(targetId));
        if (!target.isAgent()) {
            throw new BadRequestException("Log collection requires an Agent-monitored target");
        }
        io.cryostat.diagnostic.UnifiedLog session =
                QuarkusTransaction.requiringNew()
                        .call(
                                () ->
                                        io.cryostat.diagnostic.UnifiedLog
                                                .<io.cryostat.diagnostic.UnifiedLog>find(
                                                        "target", target)
                                                .firstResult());
        helper.disableUnifiedLogging(target);
        if (session != null) {
            QuarkusTransaction.requiringNew()
                    .run(() -> io.cryostat.diagnostic.UnifiedLog.deleteById(session.id));
        }
    }

    @Path("targets/{targetId}/unified-logging")
    @RolesAllowed("read")
    @Blocking
    @GET
    public AgentClient.UnifiedLogStatus unifiedLoggingStatus(@RestPath long targetId) {
        Target target =
                QuarkusTransaction.requiringNew().call(() -> Target.getTargetById(targetId));
        return helper.unifiedLogStatus(target);
    }

    @Path("targets/{targetId}/unified-logging/pull")
    @RolesAllowed("write")
    @Blocking
    @POST
    public RestResponse<UnifiedLog> pullUnifiedLog(@RestPath long targetId) {
        Target target =
                QuarkusTransaction.requiringNew().call(() -> Target.getTargetById(targetId));
        if (!target.isAgent()) {
            throw new BadRequestException("Log collection requires an Agent-monitored target");
        }
        Optional<UnifiedLog> result;
        try {
            result = helper.pullUnifiedLog(target);
        } catch (Exception e) {
            QuarkusTransaction.requiringNew()
                    .run(
                            () -> {
                                io.cryostat.diagnostic.UnifiedLog.<io.cryostat.diagnostic.UnifiedLog>find(
                                                "target", target)
                                        .firstResultOptional()
                                        .ifPresent(
                                                s -> {
                                                    s.markFailed();
                                                    s.persist();
                                                });
                            });
            throw e;
        }
        if (result.isEmpty()) {
            return RestResponse.noContent();
        }
        return RestResponse.ok(result.get());
    }

    @Path("targets/{targetId}/unified-logs")
    @RolesAllowed("read")
    @Blocking
    @GET
    public List<UnifiedLog> listUnifiedLogs(@RestPath long targetId) {
        String jvmId =
                QuarkusTransaction.requiringNew().call(() -> Target.getTargetById(targetId).jvmId);
        return helper.listUnifiedLogObjects(jvmId).stream()
                .map(
                        item -> {
                            String[] parts = item.key().strip().split("/");
                            String filename = parts[1];
                            String storageKey = DiagnosticsHelper.storageKey(jvmId, filename);
                            Metadata metadata =
                                    helper.getUnifiedLogMetadata(storageKey).orElse(Metadata.empty());
                            return new UnifiedLog(
                                    jvmId,
                                    helper.unifiedLogDownloadUrl(jvmId, filename),
                                    filename,
                                    item.lastModified().getEpochSecond(),
                                    item.size(),
                                    metadata);
                        })
                .toList();
    }

    @Path("targets/{targetId}/unified-logs/{logId}")
    @RolesAllowed("read")
    @Blocking
    @GET
    public RestResponse<Object> downloadUnifiedLog(
            @RestPath long targetId, @RestPath String logId, @RestQuery String filename)
            throws URISyntaxException {
        String jvmId =
                QuarkusTransaction.requiringNew().call(() -> Target.getTargetById(targetId).jvmId);
        String encodedKey = helper.encodedKey(jvmId, logId);
        return RestResponse.seeOther(
                new URI(
                        String.format(
                                "/api/beta/diagnostics/unified-logs/download/%s?filename=%s",
                                encodedKey, filename)));
    }

    @Path("targets/{targetId}/unified-logs/{logId}")
    @RolesAllowed("write")
    @Blocking
    @DELETE
    public void deleteUnifiedLog(@RestPath long targetId, @RestPath String logId) {
        String jvmId =
                QuarkusTransaction.requiringNew().call(() -> Target.getTargetById(targetId).jvmId);
        helper.deleteUnifiedLog(jvmId, logId);
    }

    @Path("fs/unified-logs")
    @RolesAllowed("read")
    @Blocking
    @GET
    public Collection<ArchivedUnifiedLogDirectory> listFsUnifiedLogs() {
        var map = new HashMap<String, ArchivedUnifiedLogDirectory>();
        helper.listUnifiedLogObjects()
                .forEach(
                        item -> {
                            String path = item.key().strip();
                            String[] parts = path.split("/");
                            String jvmId = parts[0];
                            String filename = parts[1];
                            var dir =
                                    map.computeIfAbsent(
                                            jvmId,
                                            id ->
                                                    new ArchivedUnifiedLogDirectory(
                                                            id, new ArrayList<>()));
                            String storageKey = DiagnosticsHelper.storageKey(jvmId, filename);
                            Metadata metadata =
                                    helper.getUnifiedLogMetadata(storageKey).orElse(Metadata.empty());
                            dir.unifiedLogs()
                                    .add(
                                            new UnifiedLog(
                                                    jvmId,
                                                    helper.unifiedLogDownloadUrl(jvmId, filename),
                                                    filename,
                                                    item.lastModified().getEpochSecond(),
                                                    item.size(),
                                                    metadata));
                        });
        return map.values();
    }

    @Path("fs/unified-logs/{jvmId}/{logId}")
    @RolesAllowed("write")
    @Blocking
    @DELETE
    public void deleteUnifiedLogByPath(@RestPath String jvmId, @RestPath String logId) {
        helper.deleteUnifiedLog(jvmId, logId);
    }

    @Path("targets/{targetId}/unified-logs/{logId}")
    @RolesAllowed("write")
    @Blocking
    @PATCH
    @Consumes("application/json")
    public UnifiedLog patchUnifiedLogMetadata(
            @RestPath long targetId, @RestPath String logId, MetadataBody body) throws Exception {
        String jvmId =
                QuarkusTransaction.requiringNew().call(() -> Target.getTargetById(targetId).jvmId);
        return helper.updateUnifiedLogMetadata(jvmId, logId, body.labels());
    }

    @Path("fs/unified-logs/{jvmId}/{logId}")
    @RolesAllowed("write")
    @Blocking
    @PATCH
    @Consumes("application/json")
    public UnifiedLog patchFsUnifiedLogMetadata(
            @RestPath String jvmId, @RestPath String logId, MetadataBody body) throws Exception {
        return helper.updateUnifiedLogMetadata(jvmId, logId, body.labels());
    }

    @Path("/unified-logs/download/{encodedKey}")
    @RolesAllowed("read")
    @Blocking
    @GET
    public RestResponse<Object> handleUnifiedLogStorageDownload(
            @RestPath String encodedKey, @RestQuery String filename) throws URISyntaxException {
        Pair<String, String> decodedKey = helper.decodedKey(encodedKey);
        log.tracev("Handling log download Request for key: {0}", decodedKey);
        String key = helper.storageKey(decodedKey);
        try {
            storage.headObject(HeadObjectRequest.builder().bucket(logsBucket).key(key).build())
                    .sdkHttpResponse();
        } catch (NoSuchKeyException e) {
            log.warnv("Failed to find log for key {0}", decodedKey.toString());
            throw new NotFoundException(e);
        }
        String contentName = StringUtils.isNotBlank(filename) ? filename : decodedKey.getRight();

        if (!presignedDownloadsEnabled) {
            return ResponseBuilder.ok()
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            String.format("attachment; filename=\"%s\"", contentName))
                    .header(HttpHeaders.CONTENT_TYPE, HttpMimeType.OCTET_STREAM.mime())
                    .entity(helper.getUnifiedLogStream(encodedKey))
                    .build();
        }

        GetObjectRequest getRequest =
                GetObjectRequest.builder().bucket(logsBucket).key(key).build();
        GetObjectPresignRequest presignRequest =
                GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(1))
                        .getObjectRequest(getRequest)
                        .build();
        PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
        URI uri = presignedRequest.url().toURI();
        if (externalStorageUrl.isPresent()) {
            String extUrl = externalStorageUrl.get();
            if (StringUtils.isNotBlank(extUrl)) {
                URI extUri = new URI(extUrl);
                uri =
                        new URI(
                                extUri.getScheme(),
                                extUri.getAuthority(),
                                URI.create(String.format("%s/%s", extUri.getPath(), uri.getPath()))
                                        .normalize()
                                        .getPath(),
                                uri.getQuery(),
                                uri.getFragment());
            }
        }
        return ResponseBuilder.create(RestResponse.Status.PERMANENT_REDIRECT)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        String.format("attachment; filename=\"%s\"", contentName))
                .location(uri)
                .build();
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public record MetadataBody(Map<String, String> labels) {
        public MetadataBody {
            Objects.requireNonNull(labels);
        }
    }

    private static void validateLoggingParams(String what, String decorators) {
        if (!SAFE_PARAM_PATTERN.matcher(what).matches()
                || !SAFE_PARAM_PATTERN.matcher(decorators).matches()) {
            throw new BadRequestException(
                    "Query parameters 'what' and 'decorators' must contain only ASCII"
                            + " alphanumerics, commas, plus signs, or asterisks");
        }
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public record ArchivedUnifiedLogDirectory(String jvmId, List<UnifiedLog> unifiedLogs) {
        public ArchivedUnifiedLogDirectory {
            Objects.requireNonNull(jvmId);
            Objects.requireNonNull(unifiedLogs);
        }
    }

    public record UnifiedLog(
            String jvmId,
            String downloadUrl,
            String logId,
            long lastModified,
            long size,
            Metadata metadata) {
        public UnifiedLog {
            Objects.requireNonNull(jvmId);
            Objects.requireNonNull(metadata);
        }
    }

    public record UnifiedLogEvent(DiagnosticsHelper.EventCategory category, Payload payload) {
        public UnifiedLogEvent {
            Objects.requireNonNull(category);
            Objects.requireNonNull(payload);
        }

        public record Payload(String jvmId, UnifiedLog unifiedLog) {
            public Payload {
                Objects.requireNonNull(jvmId);
                Objects.requireNonNull(unifiedLog);
            }

            public static Payload of(String jvmId, UnifiedLog unifiedLog) {
                return new Payload(jvmId, unifiedLog);
            }
        }
    }
}
