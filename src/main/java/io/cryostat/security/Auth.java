/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
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
