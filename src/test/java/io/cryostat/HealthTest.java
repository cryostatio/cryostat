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

import io.cryostat.resources.GrafanaResource;
import io.cryostat.resources.JFRDatasourceResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = GrafanaResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = JFRDatasourceResource.class, restrictToAnnotatedClass = true)
@TestHTTPEndpoint(Health.class)
public class HealthTest {

    @ConfigProperty(name = ConfigProperties.GRAFANA_DASHBOARD_URL)
    String dashboardURL;

    @ConfigProperty(name = ConfigProperties.GRAFANA_DATASOURCE_URL)
    String datasourceURL;

    @Test
    public void testHealthLiveness() {
        when().get("/liveness").then().statusCode(204);
    }

    @Test
    public void testHealth() {
        when().get()
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body(
                        "cryostatVersion", Matchers.instanceOf(String.class),
                        "build.git.hash", Matchers.not(Matchers.emptyOrNullString()),
                        "services.dashboard.configured", is(true),
                        "services.dashboard.available", is(true),
                        "services.dashboard.url", is(dashboardURL),
                        "services.datasource.configured", is(true),
                        "services.datasource.available", is(true),
                        "services.reports.configured", is(false),
                        "services.reports.available", is(true));
    }
}
