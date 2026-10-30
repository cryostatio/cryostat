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
import java.util.ArrayList;
import java.util.List;

import io.cryostat.ConfigProperties;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;

@Path("api/beta/auth")
public class PermissionCheck {

    @Inject SsarAuthorizer ssarAuthorizer;
    @Inject PermissionMapper permissionMapper;
    @Inject RbacConfig rbacConfig;
    @Inject SecurityIdentity securityIdentity;
    @Inject Logger log;

    // See PermissionAnnotationArchTest for how this is generated
    private static final List<Permission> PERMISSION_SET =
            List.of(
                            "activerecordings:delete",
                            "activerecordings:write",
                            "activerecordings:read",
                            "archivedrecordings:delete",
                            "archivedrecordings:read",
                            "archivedrecordings:write",
                            "audit:read",
                            "asyncprofiler:delete",
                            "asyncprofiler:read",
                            "asyncprofiler:write",
                            "automatedrules:delete",
                            "automatedrules:read",
                            "automatedrules:write",
                            "certificates:read",
                            "credentials:delete",
                            "credentials:read",
                            "credentials:write",
                            "diagnostics:write",
                            "discoverynodes:delete",
                            "discoverynodes:read",
                            "discoverynodes:write",
                            "discoveryplugins:delete",
                            "discoveryplugins:read",
                            "discoveryplugins:write",
                            "eventtemplates:delete",
                            "eventtemplates:read",
                            "eventtemplates:write",
                            "eventtypes:read",
                            "heapdumps:delete",
                            "heapdumps:read",
                            "heapdumps:write",
                            "jmcagent:delete",
                            "jmcagent:read",
                            "jmcagent:write",
                            "matchexpressions:delete",
                            "matchexpressions:read",
                            "matchexpressions:write",
                            "reports:read",
                            "reports:write",
                            "smarttriggers:delete",
                            "smarttriggers:read",
                            "smarttriggers:write",
                            "targets:delete",
                            "targets:read",
                            "targets:write",
                            "threaddumps:delete",
                            "threaddumps:read",
                            "threaddumps:write",
                            "unifiedlogs:delete",
                            "unifiedlogs:read",
                            "unifiedlogs:write")
                    .stream()
                    .map(Permission::fromString)
                    .toList();

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration connectionFailedTimeout;

    @POST
    @Path("check")
    @PermitAll
    @Operation(summary = "Check if the caller is authorized for the given permission.")
    public Uni<PermissionResponse> check(
            @RestForm("permission") String permission, RoutingContext ctx) {
        if (Boolean.TRUE.equals(
                securityIdentity.getAttribute(
                        RbacHttpAuthenticationMechanism.AGENT_IDENTITY_ATTRIBUTE))) {
            return securityIdentity
                    .checkPermission(PermissionMapper.toPermission(permission))
                    .map(PermissionResponse::new);
        }
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
        return List.copyOf(PERMISSION_SET);
    }

    @POST
    @Path("mapping")
    @PermitAll
    @Operation(summary = "Return the permission mappings for the current user")
    public Uni<List<MappingResponse>> mappings(RoutingContext ctx) {
        List<Uni<PermissionResponse>> unresolved = new ArrayList<>();
        List<Permission> permissions = permissions();
        for (Permission p : permissions) {
            unresolved.add(check(p.toString(), ctx));
        }
        Uni<List<MappingResponse>> result =
                Uni.join()
                        .all(unresolved)
                        .andCollectFailures()
                        .onItem()
                        .transform(
                                resolvedItem -> {
                                    List<MappingResponse> resolved = new ArrayList<>();
                                    // all(unresolved) preserves the ordering, the resolved
                                    // list of responses will correspond to permissions
                                    for (int i = 0; i < resolvedItem.size(); i++) {
                                        resolved.add(
                                                new MappingResponse(
                                                        permissions.get(i), resolvedItem.get(i)));
                                    }
                                    return resolved;
                                });
        return result;
    }

    public record PermissionResponse(boolean authorized) {}
    ;

    public record MappingResponse(Permission permission, PermissionResponse response) {}
    ;

    record Permission(String resource, String action) {
        Permission(String resource, String action) {
            if (StringUtils.isBlank(resource) || StringUtils.isBlank(action)) {
                throw new IllegalArgumentException();
            }
            this.resource = resource;
            this.action = action;
        }

        public static Permission fromString(String s) {
            if (s.indexOf(":") < 0) {
                throw new IllegalArgumentException();
            }
            String[] parts = s.split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException();
            }
            return new Permission(parts[0].strip(), parts[1].strip());
        }

        @Override
        public String toString() {
            return this.resource + ":" + this.action;
        }
    }
}
