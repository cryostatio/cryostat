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
package io.cryostat.security.rbac;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.cryostat.ConfigProperties;

import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;

@Path("api/beta/auth")
public class PermissionCheck {

    @Inject SsarAuthorizer ssarAuthorizer;
    @Inject PermissionMapper permissionMapper;
    @Inject RbacConfig rbacConfig;
    @Inject Logger log;

    // See PermissionAnnotationArchTest for how this is generated
    private final String PERMISSION_SET =
            "jmcagent:delete, eventtypes:read, unifiedlogs:read, asyncprofiler:write,"
                + " heapdumps:write, certificates:read, unifiedlogs:delete, reports:read,"
                + " discoveryplugins:read, reports:write, threaddumps:delete, diagnostics:write,"
                + " matchexpressions:write, jmcagent:read, targets:write, matchexpressions:delete,"
                + " archivedrecordings:read, activerecordings:write, threaddumps:read,"
                + " eventtemplates:read, smarttriggers:delete, credentials:delete, targets:read,"
                + " eventtemplates:write, heapdumps:read, archivedrecordings:write,"
                + " asyncprofiler:read, credentials:read, automatedrules:read,"
                + " asyncprofiler:delete, discoveryplugins:write, targets:delete,"
                + " credentials:write, automatedrules:delete, unifiedlogs:write,"
                + " activerecordings:read, automatedrules:write, discoveryplugins:delete,"
                + " activerecordings:delete, discoverynodes:delete, discoverynodes:read,"
                + " threaddumps:write, matchexpressions:read, smarttriggers:write,"
                + " eventtemplates:delete, heapdumps:delete, smarttriggers:read,"
                + " archivedrecordings:delete, discoverynodes:write, jmcagent:write";

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration connectionFailedTimeout;

    private Set<String> permissionSet = new HashSet<>();

    @POST
    @Path("check")
    @PermitAll
    @Operation(summary = "Check if the caller is authorized for the given permission.")
    public Uni<PermissionResponse> check(
            @RestForm("permission") String permission, RoutingContext ctx) {
        switch (rbacConfig.mode()) {
            case OPENSHIFT -> {
                String token = SsarAuthorizer.extractAccessToken(ctx);
                ctx.put(SsarAuthorizer.ATTR_RAW_ACCESS_TOKEN, token);
                return ssarAuthorizer
                        .checkSsarPermission(token, permission)
                        .map(PermissionResponse::new);
            }
            case BASIC -> {
                // Permissive, accept all requests.
                return Uni.createFrom().item(new PermissionResponse(true));
            }
            default -> {
                return Uni.createFrom().item(new PermissionResponse(true));
            }
        }
    }

    @GET
    @Path("permissions")
    @PermitAll
    @Operation(summary = "Return an exhaustive list of permissions known to Cryostat")
    public List<Permission> permissions() {
        if (permissionSet.isEmpty()) {
            generatePermissionSet();
        }
        return permissionSet.stream()
                .map(
                        (s) -> {
                            String[] tmp = s.strip().split(":");
                            return new Permission(tmp[0], tmp[1]);
                        })
                .toList();
    }

    @POST
    @Path("mapping")
    @PermitAll
    @Operation(summary = "Return the permission mappings for the current user")
    public List<MappingResponse> mappings(RoutingContext ctx) {
        if (permissionSet.isEmpty()) {
            generatePermissionSet();
        }
        return permissions().stream()
                .map(
                        (p) ->
                                new MappingResponse(
                                        p,
                                        check(p.action + ":" + p.resource, ctx)
                                                .await()
                                                .atMost(connectionFailedTimeout)))
                .toList();
    }

    private void generatePermissionSet() {
        String[] permissions = PERMISSION_SET.split(",");
        for (String p : permissions) {
            permissionSet.add(p.strip());
        }
    }

    public record PermissionResponse(boolean authorized) {}
    ;

    public record MappingResponse(Permission permission, PermissionResponse response) {}
    ;

    public record Permission(String resource, String action) {}
    ;
}
