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
package itest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import itest.bases.StandardSelfTest;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusIntegrationTest
public class HealthIT extends StandardSelfTest {

    JsonObject response;

    @BeforeEach
    void createRequest() throws Exception {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        HttpRequest<Buffer> req = webClient.get("/health");
        req.send(
                ar -> {
                    if (ar.failed()) {
                        future.completeExceptionally(ar.cause());
                        return;
                    }
                    future.complete(ar.result().bodyAsJsonObject());
                });
        response = future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void shouldIncludeApplicationVersion() {
        Assertions.assertTrue(response.containsKey("cryostatVersion"));
        MatcherAssert.assertThat(
                response.getString("cryostatVersion"), Matchers.not(Matchers.emptyOrNullString()));
        MatcherAssert.assertThat(
                response.getString("cryostatVersion"), Matchers.not(Matchers.equalTo("unknown")));
        MatcherAssert.assertThat(
                response.getString("cryostatVersion"),
                Matchers.matchesRegex("^v[\\d]\\.[\\d]\\.[\\d](?:-snapshot)?"));
    }

    @Test
    void shouldIncludeGitHash() {
        Assertions.assertTrue(response.containsKey("build"));
        Assertions.assertTrue(response.getJsonObject("build").containsKey("git"));
        Assertions.assertTrue(
                response.getJsonObject("build").getJsonObject("git").containsKey("hash"));
        MatcherAssert.assertThat(
                response.getJsonObject("build").getJsonObject("git").getString("hash"),
                Matchers.not(Matchers.emptyOrNullString()));
        MatcherAssert.assertThat(
                response.getJsonObject("build").getJsonObject("git").getString("hash"),
                Matchers.matchesRegex("^[a-f0-9]+$"));
    }

    @Test
    void shouldHaveConfiguredDatasource() {
        Assertions.assertTrue(response.containsKey("datasourceConfigured"));
        MatcherAssert.assertThat(
                response.getString("datasourceConfigured"), Matchers.equalTo("true"));
    }

    @Test
    void shouldHaveConfiguredDashboard() {
        Assertions.assertTrue(response.containsKey("dashboardConfigured"));
        MatcherAssert.assertThat(
                response.getString("dashboardConfigured"), Matchers.equalTo("true"));
    }

    @Test
    void shouldHaveConfigureREports() {
        Assertions.assertTrue(response.containsKey("reportsConfigured"));
        MatcherAssert.assertThat(
                response.getString("reportsConfigured"), Matchers.equalTo("false"));
    }

    @Test
    void shouldHaveAvailableDatasource() {
        Assertions.assertTrue(response.containsKey("datasourceAvailable"));
        MatcherAssert.assertThat(
                response.getString("datasourceAvailable"), Matchers.equalTo("false"));
    }

    @Test
    void shouldHaveAvailableDashboard() {
        Assertions.assertTrue(response.containsKey("dashboardAvailable"));
        MatcherAssert.assertThat(
                response.getString("dashboardAvailable"), Matchers.equalTo("false"));
    }

    @Test
    void shouldHaveAvailableREports() {
        Assertions.assertTrue(response.containsKey("reportsAvailable"));
        MatcherAssert.assertThat(response.getString("reportsAvailable"), Matchers.equalTo("true"));
    }
}
