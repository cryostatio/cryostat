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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import io.cryostat.util.HttpStatusCodeIdentifier;

import io.smallrye.common.annotation.Blocking;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.ResponseBuilder;
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

    @ConfigProperty(name = ConfigProperties.GRAFANA_DASHBOARD_URL)
    Optional<String> dashboardURL;

    @ConfigProperty(name = ConfigProperties.GRAFANA_DASHBOARD_EXT_URL)
    Optional<String> dashboardExternalURL;

    @ConfigProperty(name = ConfigProperties.GRAFANA_DATASOURCE_URL)
    Optional<String> datasourceURL;

    @Inject Logger logger;
    @Inject WebClient webClient;

    @GET
    @Blocking
    @Path("/health")
    @PermitAll
    public Response health() {
        CompletableFuture<Boolean> datasourceAvailable = new CompletableFuture<>();
        CompletableFuture<Boolean> dashboardAvailable = new CompletableFuture<>();
        CompletableFuture<Boolean> reportsAvailable = new CompletableFuture<>();

        checkUri(dashboardURL, "/api/health", dashboardAvailable);
        checkUri(datasourceURL, "/", datasourceAvailable);
        reportsAvailable.complete(false);

        return new PermittedResponseBuilder(
                        Response.ok(
                                Map.of(
                                        "cryostatVersion",
                                        String.format("v%s", version),
                                        "dashboardConfigured",
                                        dashboardURL.isPresent(),
                                        "dashboardAvailable",
                                        dashboardAvailable.join(),
                                        "datasourceConfigured",
                                        datasourceURL.isPresent(),
                                        "datasourceAvailable",
                                        datasourceAvailable.join(),
                                        "reportsConfigured",
                                        false,
                                        "reportsAvailable",
                                        false)))
                .build();
    }

    @GET
    // This does not actually block, but we force it to execute on the worker pool so that the
    // status check reports not only that the event loop dispatch thread is alive and responsive,
    // but that the worker pool is also actively servicing requests. If we don't force this then
    // this handler only checks if the event loop is alive, but the worker pool may be blocked or
    // otherwise unresponsive and the application as a whole will not be usable.
    @Blocking
    @Path("/health/liveness")
    @PermitAll
    public void liveness() {}

    @GET
    @Path("/api/v1/notifications_url")
    @PermitAll
    public Response notificationsUrl() {
        boolean ssl = sslPass.isPresent();
        return new PermittedResponseBuilder(
                        Response.ok(
                                Map.of(
                                        "notificationsUrl",
                                        String.format(
                                                "%s://%s:%d/api/v1/notifications",
                                                ssl ? "wss" : "ws", host, ssl ? sslPort : port))))
                .build();
    }

    @GET
    @Path("/api/v1/grafana_dashboard_url")
    @PermitAll
    @Produces({MediaType.APPLICATION_JSON})
    public Response grafanaDashboardUrl() {
        String url =
                dashboardExternalURL.orElseGet(
                        () -> dashboardURL.orElseThrow(() -> new BadRequestException()));

        return new PermittedResponseBuilder(Response.ok(Map.of("grafanaDashboardUrl", url)))
                .build();
    }

    @GET
    @Path("/api/v1/grafana_datasource_url")
    @PermitAll
    @Produces({MediaType.APPLICATION_JSON})
    public Response grafanaDatasourceUrl() {
        return new PermittedResponseBuilder(
                        Response.ok(Map.of("grafanaDatasourceUrl", datasourceURL)))
                .corsSkippedHeaders()
                .build();
    }

    private void checkUri(
            Optional<String> configProperty, String path, CompletableFuture<Boolean> future) {
        if (configProperty.isPresent()) {
            URI uri;
            try {
                uri = new URI(configProperty.get());
            } catch (URISyntaxException e) {
                logger.error(e);
                future.complete(false);
                return;
            }
            logger.debugv("Testing health of {1}={2} {3}", configProperty, uri.toString(), path);
            HttpRequest<Buffer> req = webClient.get(uri.getHost(), path);
            if (uri.getPort() != -1) {
                req = req.port(uri.getPort());
            }
            req.ssl("https".equals(uri.getScheme()))
                    .timeout(5000)
                    .send()
                    .subscribe()
                    .with(
                            item -> {
                                future.complete(
                                        HttpStatusCodeIdentifier.isSuccessCode(item.statusCode()));
                            },
                            failure -> {
                                logger.warn(new IOException(failure));
                                future.complete(false);
                            });
        } else {
            future.complete(false);
        }
    }

    static class PermittedResponseBuilder {
        private ResponseBuilder builder;

        public PermittedResponseBuilder(ResponseBuilder builder) {
            this.builder = builder;
        }

        public ResponseBuilder corsSkippedHeaders() {
            // TODO @PermitAll annotation seems to skip the CORS filter, so these headers don't get
            // added. We shouldn't need to add them manually like this and they should not be added
            // in
            // prod builds.
            return this.builder
                    .header("Access-Control-Allow-Origin", "http://localhost:9000")
                    .header(
                            "Access-Control-Allow-Headers",
                            "accept, origin, authorization, content-type,"
                                    + " x-requested-with, x-jmx-authorization")
                    .header(
                            "Access-Control-Expose-Headers",
                            "x-www-authenticate, x-jmx-authenticate")
                    .header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
                    .header("Access-Control-Allow-Credentials", "true");
        }

        public Response build() {
            return builder.build();
        }
    }
}
