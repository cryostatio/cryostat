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

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;

import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;

@Path("api/beta/auth")
public class PermissionCheck {

    @Inject SsarAuthorizer ssarAuthorizer;
    @Inject PermissionMapper permissionMapper;
    @Inject RbacConfig rbacConfig;
    @Inject Logger log;

    @POST
    @Path("check")
    @PermitAll
    public PermissionResponse check(@RestForm("permission") String permission, RoutingContext ctx) {
        switch(rbacConfig.mode()) {
            case OPENSHIFT -> {
                String token = SsarAuthorizer.extractAccessToken(ctx);
                ctx.put(SsarAuthorizer.ATTR_RAW_ACCESS_TOKEN, token);
                Uni<Boolean> result = ssarAuthorizer.checkSsarPermission(token, permission);
                return new PermissionResponse(result.await().indefinitely());
            } case BASIC -> {
                // Permissive, accept all requests.
                return new PermissionResponse(true);
            } default -> {
                return new PermissionResponse(true);
            }
        }
    }

    @GET
    @Path("permissions")
    @PermitAll
    public List<Permission> permissions() {
        List<Permission> permissions = new ArrayList<Permission>();
        for (Entry<String, String> e : rbacConfig.permissions().entrySet()) {
            var tmp = e.getKey().split(":");
            permissions.add(new Permission(tmp[0], tmp[1]));
        }
        Optional<List<String>> agentPerms = rbacConfig.agentPermissions();
        if (agentPerms.isPresent()) {
            for (String s : agentPerms.get()) {
                var tmp = s.split(":");
                permissions.add(new Permission(tmp[0], tmp[1]));
            }
        }
        return permissions;
    }

    public record PermissionResponse(boolean authorized) {}
    ;

    public record Permission(String resource, String action) {}
    ;
}
