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

import java.util.UUID;
import java.util.regex.Pattern;

import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.targets.AgentClient;
import io.cryostat.targets.Target;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.PermissionsAllowed;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;

@Path("/api/v5/targets/{jvmId}/diagnostics/unified-logging")
public class UnifiedLogConfiguration {

    static final Pattern SAFE_PARAM_PATTERN = Pattern.compile("^[A-Za-z0-9,+*=]+$");

    @Inject DiagnosticsHelper helper;
    @Inject Logger log;

    @PermissionsAllowed(
            value = {"targets:read", "unifiedlogs:write"},
            inclusive = true)
    @Blocking
    @POST
    public UnifiedLogs.UnifiedLog enableUnifiedLogging(
            @RestPath String jvmId,
            @QueryParam("what") String what,
            @QueryParam("decorators") String decorators) {
        validateLoggingParams(what, decorators);
        Target target =
                QuarkusTransaction.requiringNew()
                        .call(() -> Target.getTargetByJvmId(jvmId))
                        .orElseThrow();
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
        return new UnifiedLogs.UnifiedLog(
                target.jvmId, null, null, session.enabledAt / 1000, 0, Metadata.empty());
    }

    @PermissionsAllowed(
            value = {"targets:read", "unifiedlogs:write"},
            inclusive = true)
    @Blocking
    @PATCH
    public UnifiedLogs.UnifiedLog reconfigureUnifiedLogging(
            @RestPath String jvmId,
            @QueryParam("what") String what,
            @QueryParam("decorators") String decorators) {
        validateLoggingParams(what, decorators);
        Target target =
                QuarkusTransaction.requiringNew()
                        .call(() -> Target.getTargetByJvmId(jvmId))
                        .orElseThrow();
        if (!target.isAgent()) {
            throw new BadRequestException("Log collection requires an Agent-monitored target");
        }
        AgentClient.UnifiedLogStatus status = helper.unifiedLogStatus(target);
        if (!status.enabled()) {
            throw new ClientErrorException(Response.Status.CONFLICT);
        }
        UUID sessionId =
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
        return new UnifiedLogs.UnifiedLog(
                target.jvmId, null, null, System.currentTimeMillis() / 1000, 0, Metadata.empty());
    }

    @PermissionsAllowed(
            value = {"targets:read", "unifiedlogs:write"},
            inclusive = true)
    @Blocking
    @DELETE
    public void disableUnifiedLogging(@RestPath String jvmId) {
        Target target =
                QuarkusTransaction.requiringNew()
                        .call(() -> Target.getTargetByJvmId(jvmId))
                        .orElseThrow();
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

    @PermissionsAllowed(
            value = {"targets:read", "unifiedlogs:read"},
            inclusive = true)
    @Blocking
    @GET
    public AgentClient.UnifiedLogStatus unifiedLoggingStatus(@RestPath String jvmId) {
        Target target =
                QuarkusTransaction.requiringNew()
                        .call(() -> Target.getTargetByJvmId(jvmId))
                        .orElseThrow();
        return helper.unifiedLogStatus(target);
    }

    private static void validateLoggingParams(String what, String decorators) {
        if (StringUtils.isAnyBlank(what, decorators)) {
            throw new BadRequestException("Query parameters 'what' and 'decorators' are required");
        }
        if (!SAFE_PARAM_PATTERN.matcher(what).matches()
                || !SAFE_PARAM_PATTERN.matcher(decorators).matches()) {
            throw new BadRequestException(
                    "Query parameters 'what' and 'decorators' must contain only ASCII"
                            + " alphanumerics, commas, plus signs, or asterisks");
        }
    }
}
