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
import java.util.HashMap;
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
    @PermitAll
    @Produces("application/json")
    public Response logout(@Context RoutingContext context) {
        HttpAuthenticator authenticator = context.get(HttpAuthenticator.class.getName());
        return authenticator
                .attemptAuthentication(context)
                .onItemOrFailure()
                .transform(
                        (id, t) -> {
                            if (id == null) {
                                return unauthorizedResponse(context);
                            }
                            if (t != null) {
                                logger.error("Internal authentication failure", t);
                                return unauthorizedResponse(context);
                            }
                            return okResponse(context, null);
                        })
                .await()
                .atMost(Duration.ofSeconds(20));
    }

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
                                return unauthorizedResponse(context);
                            }
                            if (t != null) {
                                logger.error("Internal authentication failure", t);
                                return unauthorizedResponse(context);
                            }
                            return okResponse(
                                    context, Map.of("username", id.getPrincipal().getName()));
                        })
                .await()
                .atMost(Duration.ofSeconds(20));
    }

    private Response unauthorizedResponse(RoutingContext context) {
        HttpAuthenticator authenticator = context.get(HttpAuthenticator.class.getName());
        var challengeData = authenticator.getChallenge(context).await().indefinitely();
        return Response.status(challengeData.status)
                .header(challengeData.headerName.toString(), challengeData.headerContent)
                .entity(
                        Map.of(
                                "meta",
                                Map.of(
                                        "status", "Unauthorized",
                                        "type", "text/plain"),
                                "data",
                                Map.of("reason", "HTTP Authorization Failure")))
                .build();
    }

    private Response okResponse(RoutingContext context, Object result) {
        HttpAuthenticator authenticator = context.get(HttpAuthenticator.class.getName());
        var challengeData = authenticator.getChallenge(context).await().indefinitely();
        var data = new HashMap<String, Object>();
        data.put("result", result);
        return Response.ok(
                        Map.of(
                                "meta",
                                Map.of(
                                        "status", "OK",
                                        "type", "application/json"),
                                "data",
                                data))
                .header(challengeData.headerName.toString(), challengeData.headerContent)
                .build();
    }
}
