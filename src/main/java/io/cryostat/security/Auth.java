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
package io.cryostat.security;

import java.util.Map;

import io.cryostat.V2Response;

import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;

@Path("")
public class Auth {

    @POST
    @Path("/api/v2.1/logout")
    @PermitAll
    @Produces("application/json")
    public Response logout(@Context RoutingContext context) {
        return Response.noContent().build();
    }

    @POST
    @Path("/api/v2.1/auth")
    @PermitAll
    @Produces("application/json")
    public Response login(@Context RoutingContext context) {
        return Response.ok()
                .header("X-WWW-Authenticate", "None")
                .entity(V2Response.json(Map.of("username", "user"), Response.Status.OK.toString()))
                .build();
    }
}
