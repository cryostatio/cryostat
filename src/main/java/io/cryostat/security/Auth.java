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

import java.time.Duration;
import java.util.Map;

import io.quarkus.vertx.http.runtime.security.HttpAuthenticator;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

@Path("")
public class Auth {

    @Inject Logger logger;

    @POST
    @Path("/api/v2.1/logout")
    public void logout() {}

    @POST
    @Path("/api/v2.1/auth")
    @PermitAll
    @Produces("application/json")
    public Response login(@Context RoutingContext context) {
        HttpAuthenticator authenticator = context.get(HttpAuthenticator.class.getName());
        return authenticator
                .attemptAuthentication(context)
                .onItemOrFailure()
                .transform(
                        (id, t) -> {
                            if (id == null) {
                                var cd = authenticator.getChallenge(context).await().indefinitely();
                                return Response.status(cd.status)
                                        .header(cd.headerName.toString(), cd.headerContent)
                                        .entity(
                                                Map.of(
                                                        "meta",
                                                        Map.of(
                                                                "status", "Unauthorized",
                                                                "type", "text/plain"),
                                                        "data",
                                                        Map.of(
                                                                "reason",
                                                                "HTTP Authorization Failure")))
                                        .build();
                            }
                            if (t != null) {
                                var cd = authenticator.getChallenge(context).await().indefinitely();
                                logger.error("Internal authentication failure", t);
                                return Response.status(cd.status)
                                        .header(cd.headerName.toString(), cd.headerContent)
                                        .entity(
                                                Map.of(
                                                        "meta",
                                                        Map.of(
                                                                "status", "Unauthorized",
                                                                "type", "text/plain"),
                                                        "data",
                                                        Map.of(
                                                                "reason",
                                                                "HTTP Authorization Failure")))
                                        .build();
                            }
                            return Response.ok(
                                            Map.of(
                                                    "meta",
                                                    Map.of(
                                                            "status", "OK",
                                                            "type", "application/json"),
                                                    "data",
                                                    Map.of(
                                                            "result",
                                                            Map.of(
                                                                    "username",
                                                                    id.getPrincipal().getName()))))
                                    .build();
                        })
                .await()
                .atMost(Duration.ofSeconds(20));
    }
}
