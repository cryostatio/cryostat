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
package io.cryostat;

import java.util.Map;
import java.util.Optional;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@Path("")
class Health {

    @ConfigProperty(name = "quarkus.application.name")
    String name;

    @ConfigProperty(name = "quarkus.application.version")
    String version;

    @ConfigProperty(name = "quarkus.http.host")
    String host;

    @ConfigProperty(name = "quarkus.http.port")
    int port;

    @ConfigProperty(name = "quarkus.http.ssl-port")
    int sslPort;

    @ConfigProperty(name = "quarkus.http.ssl.certificate.key-store-password")
    Optional<String> sslPass;

    @Inject Logger logger;

    @GET
    @Path("/health")
    @PermitAll
    public Response health() {
        return Response.ok(
                        Map.of(
                                "cryostatVersion",
                                String.format("v%s", version),
                                "dashboardConfigured",
                                false,
                                "dashboardAvailable",
                                false,
                                "datasourceConfigured",
                                false,
                                "datasourceAvailable",
                                false,
                                "reportsConfigured",
                                false,
                                "reportsAvailable",
                                false))
                .header("Access-Control-Allow-Origin", "http://localhost:9000")
                .header(
                        "Access-Control-Allow-Headers",
                        "accept, origin, authorization, content-type,"
                                + " x-requested-with, x-jmx-authorization")
                .header("Access-Control-Expose-Headers", "x-www-authenticate, x-jmx-authenticate")
                .header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
                .header("Access-Control-Allow-Credentials", "true")
                .build();
    }

    @GET
    @Path("/health/liveness")
    @PermitAll
    public void liveness() {}

    @GET
    @Path("/api/v1/notifications_url")
    @PermitAll
    public Response notificationsUrl() {
        // TODO @PermitAll annotation seems to skip the CORS filter, so these headers don't get
        // added. We shouldn't need to add them manually like this and they should not be added in
        // prod builds.
        boolean ssl = sslPass.isPresent();
        return Response.ok(
                        Map.of(
                                "notificationsUrl",
                                String.format(
                                        "%s://%s:%d/api/v1/notifications",
                                        ssl ? "wss" : "ws", host, ssl ? sslPort : port)))
                .header("Access-Control-Allow-Origin", "http://localhost:9000")
                .header(
                        "Access-Control-Allow-Headers",
                        "accept, origin, authorization, content-type,"
                                + " x-requested-with, x-jmx-authorization")
                .header("Access-Control-Expose-Headers", "x-www-authenticate, x-jmx-authenticate")
                .header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
                .header("Access-Control-Allow-Credentials", "true")
                .build();
    }
}
