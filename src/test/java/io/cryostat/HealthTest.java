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
    public void testNotificationsUrl() {
        boolean ssl = sslPass.isPresent();
        when().get("/api/v1/notifications_url")
                .then()
                .statusCode(200)
                .body(
                        "notificationsUrl",
                        is(
                                String.format(
                                        "%s://%s:%d/api/v1/notifications",
                                        ssl ? "wss" : "ws", host, ssl ? sslPort : port)));
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
