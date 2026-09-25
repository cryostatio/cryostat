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
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.util.HttpStatusCodeIdentifier;

import io.quarkus.rest.client.reactive.Url;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/** Status and configuration verification for the application. */
@Path("/health")
class Health {

    private static final String LOCAL_REPORT_GENERATION_URL = "http://localhost/";

    @ConfigProperty(name = "quarkus.application.name")
    String name;

    @ConfigProperty(name = "quarkus.application.version")
    String version;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_HEALTH_TIMEOUT)
    Duration timeout;

    @ConfigProperty(name = ConfigProperties.GRAFANA_DASHBOARD_URL)
    Optional<String> dashboardURL;

    @ConfigProperty(name = ConfigProperties.GRAFANA_DASHBOARD_EXT_URL)
    Optional<String> dashboardExternalURL;

    @ConfigProperty(name = ConfigProperties.GRAFANA_DATASOURCE_URL)
    Optional<String> datasourceURL;

    @ConfigProperty(name = ConfigProperties.REPORTS_SIDECAR_URL)
    String reportsClientURL;

    @Inject TargetConnectionManager tcm;
    @Inject BuildInfo buildInfo;
    @Inject @RestClient HealthClient client;
    @Inject Logger logger;

    static final Target SELF;

    static {
        SELF = new Target();
        SELF.connectUrl = URI.create("service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi");
    }

    @GET
    @Blocking
    @Path("/liveness")
    @PermitAll
    @Operation(
            summary = "Check if the application is able to accept and respond to requests.",
            description =
                    """
                    Performs a simple target connection request on a worker thread.
                    This is a simply check to determine if the application has available threads
                    to service requests. HTTP 204 No Content is the only expected response.
                    If the application is not live and no worker threads are available,
                    then the client will never receive a response.
                    """)
    public Uni<Void> liveness() {
        return tcm.executeDirect(
                SELF,
                Optional.empty(),
                conn -> {
                    conn.getJvmIdentifier();
                    return null;
                });
    }

    @GET
    @Blocking
    @PermitAll
    @Operation(
            summary = "Check the overall status of the application",
            description =
                    """
                    Returns a map indicating whether various external components (ex.
                        jfr-datasource, grafana-dashboard) are configured and whether those
                        components can be reached by the Cryostat application. Also includes
                        application semantic version and build information.
                    """)
    public ApplicationHealth health() {
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

        return new ApplicationHealth(
                String.format("v%s", version),
                buildInfo,
                new Services(
                        new ExternalService(
                                dashboardURL.isPresent(),
                                safeGet(dashboardAvailable),
                                dashboardExternalURL
                                        .or(() -> dashboardURL)
                                        .map(URI::create)
                                        .orElse(null)),
                        new InternalService(
                                datasourceURL.isPresent(), safeGet(datasourceAvailable)),
                        new InternalService(reportsConfigured, safeGet(reportsAvailable))));
    }

    private void checkUri(
            Optional<String> configProperty, String path, CompletableFuture<Boolean> future) {
        if (configProperty.isPresent()) {
            URI uri = UriBuilder.fromUri(configProperty.get()).path(path).build();
            logger.debugv("Testing health of {0}", uri.toString());
            client.test(uri)
                    .subscribe()
                    .with(
                            item -> {
                                future.complete(
                                        HttpStatusCodeIdentifier.isSuccessCode(item.getStatus()));
                            },
                            failure -> {
                                logger.warn(new IOException(failure));
                                future.complete(false);
                            });
        } else {
            future.complete(false);
        }
    }

    private boolean safeGet(CompletableFuture<Boolean> future) {
        try {
            return future.get(timeout.getSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            logger.warn(e);
            return false;
        }
    }

    static record ApplicationHealth(String cryostatVersion, BuildInfo build, Services services) {}

    record InternalService(boolean configured, boolean available) {}

    record ExternalService(boolean configured, boolean available, URI url) {}

    static record Services(
            ExternalService dashboard, InternalService datasource, InternalService reports) {}

    @RegisterRestClient(
            configKey = "health",
            // baseUri should not be used - we always use an overridden URL for every request
            baseUri = "http://localhost")
    interface HealthClient {
        @GET
        Uni<Response> test(@Url URI url);
    }
}
