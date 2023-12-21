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

import static io.restassured.RestAssured.when;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.*;

import java.util.Optional;

import io.cryostat.resources.GrafanaResource;
import io.cryostat.resources.JFRDatasourceResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(GrafanaResource.class)
@QuarkusTestResource(JFRDatasourceResource.class)
@TestHTTPEndpoint(Health.class)
public class HealthTest {

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

    @ConfigProperty(name = ConfigProperties.GRAFANA_DATASOURCE_URL)
    Optional<String> datasourceURL;

    @Test
    public void testHealth() {
        when().get("/health")
                .then()
                .statusCode(200)
                .body(
                        "cryostatVersion", Matchers.instanceOf(String.class),
                        "dashboardConfigured", is(true),
                        "dashboardAvailable", is(true),
                        "datasourceConfigured", is(true),
                        "datasourceAvailable", is(true),
                        "reportsConfigured", is(false),
                        "reportsAvailable", is(false));
    }

    @Test
    public void testHealthLiveness() {
        when().get("/health/liveness").then().statusCode(204);
    }

    @Test
    public void testGrafanaDashboardUrl() {
        when().get("/api/v1/grafana_dashboard_url")
                .then()
                .statusCode(200)
                .body("grafanaDashboardUrl", is(dashboardURL.orElseGet(() -> "badurl")));
    }

    @Test
    public void testGrafanaDatasourceUrl() {
        when().get("/api/v1/grafana_datasource_url")
                .then()
                .statusCode(200)
                .body("grafanaDatasourceUrl", is(datasourceURL.orElseGet(() -> "badurl")));
    }
}
