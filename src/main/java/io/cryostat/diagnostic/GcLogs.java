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
import java.util.Objects;
import java.util.Optional;

import io.cryostat.ConfigProperties;
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
public class GcLogs {

    @Inject S3Client storage;
    @Inject S3Presigner presigner;
    @Inject Logger log;
    @Inject DiagnosticsHelper helper;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_GC_LOGS)
    String gcLogsBucket;

    @ConfigProperty(name = ConfigProperties.STORAGE_PRESIGNED_DOWNLOADS_ENABLED)
    boolean presignedDownloadsEnabled;

    @ConfigProperty(name = ConfigProperties.STORAGE_EXT_URL)
    Optional<String> externalStorageUrl;

    @Path("targets/{targetId}/gclogging")
    @RolesAllowed("write")
    @Blocking
    @POST
    public GcLog enableGcLogging(
            @RestPath long targetId,
            @QueryParam("what") @DefaultValue("gc") String what,
            @QueryParam("decorators") @DefaultValue("time,level") String decorators) {
        Target target =
                QuarkusTransaction.requiringNew().call(() -> Target.getTargetById(targetId));
        if (!target.isAgent()) {
            throw new BadRequestException("GC log collection requires an Agent-monitored target");
        }
        io.cryostat.diagnostic.GcLog session =
                QuarkusTransaction.requiringNew()
                        .call(
                                () -> {
                                    io.cryostat.diagnostic.GcLog entity =
                                            io.cryostat.diagnostic.GcLog.enable(
                                                    target, what, decorators);
                                    entity.persist();
                                    return entity;
                                });
        try {
            helper.enableGcLogging(target, what, decorators);
        } catch (Exception e) {
            QuarkusTransaction.requiringNew()
                    .run(
                            () -> {
                                io.cryostat.diagnostic.GcLog s =
                                        io.cryostat.diagnostic.GcLog.findById(session.id);
                                s.markFailed();
                                s.persist();
                            });
            throw e;
        }
        return new GcLog(target.jvmId, null, null, session.enabledAt / 1000, 0);
    }

    @Path("targets/{targetId}/gclogging")
    @RolesAllowed("write")
    @Blocking
    @PATCH
    public GcLog reconfigureGcLogging(
            @RestPath long targetId,
            @QueryParam("what") @DefaultValue("gc") String what,
            @QueryParam("decorators") @DefaultValue("time,level") String decorators) {
        Target target =
                QuarkusTransaction.requiringNew().call(() -> Target.getTargetById(targetId));
        if (!target.isAgent()) {
            throw new BadRequestException("GC log collection requires an Agent-monitored target");
        }
        AgentClient.GcLogStatus status = helper.gcLogStatus(target);
        if (!status.enabled()) {
            throw new ClientErrorException(Response.Status.CONFLICT);
        }
        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            io.cryostat.diagnostic.GcLog.<io.cryostat.diagnostic.GcLog>find(
                                            "target", target)
                                    .firstResultOptional()
                                    .ifPresent(
                                            s -> {
                                                s.markReconfigured(what, decorators);
                                                s.persist();
                                            });
                        });
        helper.reconfigureGcLogging(target, what, decorators);
        return new GcLog(target.jvmId, null, null, System.currentTimeMillis() / 1000, 0);
    }

    @Path("targets/{targetId}/gclogging")
    @RolesAllowed("write")
    @Blocking
    @DELETE
    public void disableGcLogging(@RestPath long targetId) {
        Target target =
                QuarkusTransaction.requiringNew().call(() -> Target.getTargetById(targetId));
        if (!target.isAgent()) {
            throw new BadRequestException("GC log collection requires an Agent-monitored target");
        }
        io.cryostat.diagnostic.GcLog session =
                QuarkusTransaction.requiringNew()
                        .call(
                                () ->
                                        io.cryostat.diagnostic.GcLog
                                                .<io.cryostat.diagnostic.GcLog>find(
                                                        "target", target)
                                                .firstResult());
        if (session != null) {
            QuarkusTransaction.requiringNew()
                    .run(
                            () -> {
                                io.cryostat.diagnostic.GcLog s =
                                        io.cryostat.diagnostic.GcLog.findById(session.id);
                                if (s != null) {
                                    s.persist();
                                }
                            });
        }
        helper.disableGcLogging(target);
        if (session != null) {
            QuarkusTransaction.requiringNew()
                    .run(() -> io.cryostat.diagnostic.GcLog.deleteById(session.id));
        }
    }

    @Path("targets/{targetId}/gclogging")
    @RolesAllowed("read")
    @Blocking
    @GET
    public AgentClient.GcLogStatus gcLoggingStatus(@RestPath long targetId) {
        Target target =
                QuarkusTransaction.requiringNew().call(() -> Target.getTargetById(targetId));
        return helper.gcLogStatus(target);
    }

    @Path("targets/{targetId}/gclogging/pull")
    @RolesAllowed("write")
    @Blocking
    @POST
    public GcLog pullGcLog(@RestPath long targetId) {
        Target target =
                QuarkusTransaction.requiringNew().call(() -> Target.getTargetById(targetId));
        if (!target.isAgent()) {
            throw new BadRequestException("GC log collection requires an Agent-monitored target");
        }
        GcLog result;
        try {
            result = helper.pullGcLog(target);
        } catch (Exception e) {
            QuarkusTransaction.requiringNew()
                    .run(
                            () -> {
                                io.cryostat.diagnostic.GcLog.<io.cryostat.diagnostic.GcLog>find(
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
        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            io.cryostat.diagnostic.GcLog.<io.cryostat.diagnostic.GcLog>find(
                                            "target", target)
                                    .firstResultOptional()
                                    .ifPresent(
                                            s -> {
                                                s.markPulled(result.gcLogId(), result.size());
                                                s.persist();
                                            });
                        });
        return result;
    }

    @Path("targets/{targetId}/gclogs")
    @RolesAllowed("read")
    @Blocking
    @GET
    public List<GcLog> listGcLogs(@RestPath long targetId) {
        String jvmId =
                QuarkusTransaction.requiringNew().call(() -> Target.getTargetById(targetId).jvmId);
        return helper.listGcLogObjects(jvmId).stream()
                .map(
                        item -> {
                            String[] parts = item.key().strip().split("/");
                            String filename = parts[1];
                            return new GcLog(
                                    jvmId,
                                    helper.gcLogDownloadUrl(jvmId, filename),
                                    filename,
                                    item.lastModified().getEpochSecond(),
                                    item.size());
                        })
                .toList();
    }

    @Path("targets/{targetId}/gclogs/{gcLogId}")
    @RolesAllowed("read")
    @Blocking
    @GET
    public RestResponse<Object> downloadGcLog(
            @RestPath long targetId, @RestPath String gcLogId, @RestQuery String filename)
            throws URISyntaxException {
        String jvmId =
                QuarkusTransaction.requiringNew().call(() -> Target.getTargetById(targetId).jvmId);
        String encodedKey = helper.encodedKey(jvmId, gcLogId);
        return RestResponse.seeOther(
                new URI(String.format("/api/beta/diagnostics/gclog/download/%s", encodedKey)));
    }

    @Path("targets/{targetId}/gclogs/{gcLogId}")
    @RolesAllowed("write")
    @Blocking
    @DELETE
    public void deleteGcLog(@RestPath long targetId, @RestPath String gcLogId) {
        String jvmId =
                QuarkusTransaction.requiringNew().call(() -> Target.getTargetById(targetId).jvmId);
        helper.deleteGcLog(jvmId, gcLogId);
    }

    @Path("fs/gclogs")
    @RolesAllowed("read")
    @Blocking
    @GET
    public Collection<ArchivedGcLogDirectory> listFsGcLogs() {
        var map = new HashMap<String, ArchivedGcLogDirectory>();
        helper.listGcLogObjects()
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
                                                    new ArchivedGcLogDirectory(
                                                            id, new ArrayList<>()));
                            dir.gcLogs()
                                    .add(
                                            new GcLog(
                                                    jvmId,
                                                    helper.gcLogDownloadUrl(jvmId, filename),
                                                    filename,
                                                    item.lastModified().getEpochSecond(),
                                                    item.size()));
                        });
        return map.values();
    }

    @Path("fs/gclogs/{jvmId}/{gcLogId}")
    @RolesAllowed("write")
    @Blocking
    @DELETE
    public void deleteGcLogByPath(@RestPath String jvmId, @RestPath String gcLogId) {
        helper.deleteGcLog(jvmId, gcLogId);
    }

    @Path("/gclog/download/{encodedKey}")
    @RolesAllowed("read")
    @Blocking
    @GET
    public RestResponse<Object> handleGcLogStorageDownload(
            @RestPath String encodedKey, @RestQuery String filename) throws URISyntaxException {
        Pair<String, String> decodedKey = helper.decodedKey(encodedKey);
        log.tracev("Handling GC log download Request for key: {0}", decodedKey);
        String key = helper.storageKey(decodedKey);
        try {
            storage.headObject(HeadObjectRequest.builder().bucket(gcLogsBucket).key(key).build())
                    .sdkHttpResponse();
        } catch (NoSuchKeyException e) {
            log.warnv("Failed to find GC log for key {0}", decodedKey.toString());
            throw new NotFoundException(e);
        }
        String contentName = StringUtils.isNotBlank(filename) ? filename : decodedKey.getRight();

        if (!presignedDownloadsEnabled) {
            return ResponseBuilder.ok()
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            String.format("attachment; filename=\"%s\"", contentName))
                    .header(HttpHeaders.CONTENT_TYPE, HttpMimeType.OCTET_STREAM.mime())
                    .entity(helper.getGcLogStream(encodedKey))
                    .build();
        }

        GetObjectRequest getRequest =
                GetObjectRequest.builder().bucket(gcLogsBucket).key(key).build();
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
    public record ArchivedGcLogDirectory(String jvmId, List<GcLog> gcLogs) {
        public ArchivedGcLogDirectory {
            Objects.requireNonNull(jvmId);
            Objects.requireNonNull(gcLogs);
        }
    }

    public record GcLog(
            String jvmId, String downloadUrl, String gcLogId, long lastModified, long size) {
        public GcLog {
            Objects.requireNonNull(jvmId);
        }
    }

    public record GcLogEvent(DiagnosticsHelper.EventCategory category, Payload payload) {
        public GcLogEvent {
            Objects.requireNonNull(category);
            Objects.requireNonNull(payload);
        }

        public record Payload(String jvmId, GcLog gcLog) {
            public Payload {
                Objects.requireNonNull(jvmId);
                Objects.requireNonNull(gcLog);
            }

            public static Payload of(String jvmId, GcLog gcLog) {
                return new Payload(jvmId, gcLog);
            }
        }
    }
}
