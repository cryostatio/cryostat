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

import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.targets.Target;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.PermissionsAllowed;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.UriBuilder;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;

@Path("/api/v5/targets/{jvmId}/diagnostics/unified-logs")
public class UnifiedLogs {

    @Inject DiagnosticsHelper helper;
    @Inject Logger log;

    @Path("/pull")
    @PermissionsAllowed(
            value = {"targets:read", "unifiedlogs:write"},
            inclusive = true)
    @Blocking
    @POST
    public RestResponse<UnifiedLog> pullUnifiedLog(@RestPath String jvmId) {
        Target target =
                QuarkusTransaction.requiringNew()
                        .call(() -> Target.getTargetByJvmId(jvmId))
                        .orElseThrow();
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
                                io.cryostat.diagnostic.UnifiedLog
                                        .<io.cryostat.diagnostic.UnifiedLog>find("target", target)
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
        return RestResponse.ok(result.orElseThrow());
    }

    @PermissionsAllowed(
            value = {"targets:read", "unifiedlogs:read"},
            inclusive = true)
    @Blocking
    @GET
    public List<UnifiedLog> listUnifiedLogs(@RestPath String jvmId) {
        return helper.listUnifiedLogObjects(jvmId).stream()
                .map(
                        item -> {
                            String[] parts = item.key().strip().split("/");
                            String filename = parts[1];
                            String storageKey = DiagnosticsHelper.storageKey(jvmId, filename);
                            Metadata metadata =
                                    helper.getUnifiedLogMetadata(storageKey)
                                            .orElse(Metadata.empty());
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

    @Path("/{logId}")
    @PermissionsAllowed(
            value = {"targets:read", "unifiedlogs:read"},
            inclusive = true)
    @Blocking
    @GET
    public RestResponse<Object> downloadUnifiedLog(
            @RestPath String jvmId, @RestPath String logId, @RestQuery String filename)
            throws URISyntaxException {
        String encodedKey = helper.encodedKey(jvmId, logId);
        UriBuilder uri = UriBuilder.fromPath("/api/v5/diagnostics/unified-logs/download/{key}");
        if (StringUtils.isNotBlank(filename)) {
            uri = uri.queryParam("filename", filename);
        }
        return RestResponse.seeOther(uri.build(encodedKey));
    }

    @Path("/{logId}")
    @PermissionsAllowed(
            value = {"targets:read", "unifiedlogs:delete"},
            inclusive = true)
    @Blocking
    @DELETE
    public void deleteUnifiedLog(@RestPath String jvmId, @RestPath String logId) {
        helper.deleteUnifiedLog(jvmId, logId);
    }

    @Path("/{logId}")
    @PermissionsAllowed(
            value = {"targets:read", "unifiedlogs:write"},
            inclusive = true)
    @Blocking
    @PATCH
    @Consumes("application/json")
    public UnifiedLog patchUnifiedLogMetadata(
            @RestPath String jvmId, @RestPath String logId, MetadataBody body) throws Exception {
        return helper.updateUnifiedLogMetadata(jvmId, logId, body.labels());
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public record MetadataBody(Map<String, String> labels) {
        public MetadataBody {
            Objects.requireNonNull(labels);
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
