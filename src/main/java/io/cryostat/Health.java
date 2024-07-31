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
import java.util.Objects;
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
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@Path("")
class Health {

    private static final String LOCAL_REPORT_GENERATION_URL = "http://localhost/";

    @ConfigProperty(name = "quarkus.application.name")
    String name;

    @ConfigProperty(name = "quarkus.application.version")
    String version;

    @ConfigProperty(name = ConfigProperties.GRAFANA_DASHBOARD_URL)
    Optional<String> dashboardURL;

    @ConfigProperty(name = ConfigProperties.GRAFANA_DASHBOARD_EXT_URL)
    Optional<String> dashboardExternalURL;

    @ConfigProperty(name = ConfigProperties.GRAFANA_DATASOURCE_URL)
    Optional<String> datasourceURL;

    @ConfigProperty(name = ConfigProperties.REPORTS_SIDECAR_URL)
    String reportsClientURL;

    @Inject BuildInfo buildInfo;
    @Inject WebClient webClient;
    @Inject Logger logger;

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

        // the reports URL is always present as it is required for the generated client, so the
        // value "http://localhost/" is used to indicate that no sidecar report generation service
        // is configured and the Cryostat instance itself should handle report generation. Consider
        // this case as reports being unconfigured, but available. If the URL is overridden to some
        // other value then this means sidecar report generation is requested, so it is configured
        // and the availability must be tested.
        boolean reportsConfigured =
                StringUtils.isNotBlank(reportsClientURL)
                        && !Objects.equals(LOCAL_REPORT_GENERATION_URL, reportsClientURL);
        if (reportsConfigured) {
            checkUri(Optional.of(reportsClientURL), "/health", reportsAvailable);
        } else {
            reportsAvailable.complete(true);
        }

        return Response.ok(
                        Map.of(
                                "cryostatVersion",
                                String.format("v%s", version),
                                "buildInfo",
                                Map.of("git", buildInfo.getGitInfo()),
                                "dashboardConfigured",
                                dashboardURL.isPresent(),
                                "dashboardAvailable",
                                dashboardAvailable.join(),
                                "datasourceConfigured",
                                datasourceURL.isPresent(),
                                "datasourceAvailable",
                                datasourceAvailable.join(),
                                "reportsConfigured",
                                reportsConfigured,
                                "reportsAvailable",
                                reportsAvailable.join()))
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
    @Path("/api/v1/grafana_dashboard_url")
    @PermitAll
    @Produces({MediaType.APPLICATION_JSON})
    public Response grafanaDashboardUrl() {
        String url =
                dashboardExternalURL.orElseGet(
                        () -> dashboardURL.orElseThrow(() -> new BadRequestException()));

        return Response.ok(Map.of("grafanaDashboardUrl", url)).build();
    }

    @GET
    @Path("/api/v1/grafana_datasource_url")
    @PermitAll
    @Produces({MediaType.APPLICATION_JSON})
    public Response grafanaDatasourceUrl() {
        return Response.ok(Map.of("grafanaDatasourceUrl", datasourceURL)).build();
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
            logger.debugv("Testing health of {0}={1} {2}", configProperty, uri.toString(), path);
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
}
