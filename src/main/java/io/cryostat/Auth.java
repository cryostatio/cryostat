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
package io.cryostat;

import java.time.Duration;
import java.util.Map;

import javax.annotation.security.PermitAll;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticator;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/api/v2.1/auth")
public class Auth {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @POST
    @PermitAll
    @Produces("application/json")
    public Response post(@Context RoutingContext context) {
        HttpAuthenticator authenticator = context.get(HttpAuthenticator.class.getName());
        return authenticator
                .attemptAuthentication(context)
                .onItemOrFailure()
                .transform(
                        (id, t) -> {
                            if (id == null) {
                                return Response.status(HttpResponseStatus.UNAUTHORIZED.code())
                                        .header(
                                                "X-WWW-Authenticate",
                                                authenticator
                                                        .getChallenge(context)
                                                        .await()
                                                        .indefinitely()
                                                        .headerContent)
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
                                logger.error("Internal authentication failure", t);
                                return Response.status(HttpResponseStatus.UNAUTHORIZED.code())
                                        .header(
                                                "X-WWW-Authenticate",
                                                authenticator
                                                        .getChallenge(context)
                                                        .await()
                                                        .indefinitely()
                                                        .headerContent)
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
