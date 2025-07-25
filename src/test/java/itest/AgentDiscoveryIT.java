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

import static io.restassured.RestAssured.given;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import io.cryostat.resources.AgentApplicationResource;
import io.cryostat.util.HttpStatusCodeIdentifier;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.vertx.core.json.JsonObject;
import itest.bases.HttpClientTest;
import junit.framework.AssertionFailedError;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@QuarkusIntegrationTest
@QuarkusTestResource(value = AgentApplicationResource.class, restrictToAnnotatedClass = true)
@EnabledIfEnvironmentVariable(
        named = "PR_CI",
        matches = "true",
        disabledReason =
                "Runs well in PR CI under Docker, but not on main CI or locally under Podman due to"
                        + " testcontainers 'Broken Pipe' IOException")
public class AgentDiscoveryIT extends HttpClientTest {

    static final Logger logger = Logger.getLogger(AgentDiscoveryIT.class);
    static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Test
    void shouldDiscoverTarget() throws InterruptedException, TimeoutException, ExecutionException {
        long last = System.nanoTime();
        long elapsed = 0;
        while (true) {
            var resp = given().when().get("/api/v4/targets").then().extract();
            if (HttpStatusCodeIdentifier.isSuccessCode(resp.statusCode())) {
                List<Map<String, Object>> result = resp.body().jsonPath().getList("$");
                if (result.size() == 1) {
                    JsonObject obj = new JsonObject(result.get(0));
                    MatcherAssert.assertThat(
                            obj.getString("alias"),
                            Matchers.equalTo(AgentApplicationResource.ALIAS));
                    MatcherAssert.assertThat(
                            obj.getString("connectUrl"),
                            Matchers.equalTo(
                                    String.format(
                                            "http://%s:%d",
                                            AgentApplicationResource.ALIAS,
                                            AgentApplicationResource.PORT)));

                    MatcherAssert.assertThat(obj.getBoolean("agent"), Matchers.is(true));
                    break;
                } else if (result.size() > 1) {
                    throw new IllegalStateException("Discovered too many targets");
                }
            }

            long now = System.nanoTime();
            elapsed += (now - last);
            last = now;
            if (Duration.ofNanos(elapsed).compareTo(TIMEOUT) > 0) {
                throw new AssertionFailedError("Timed out");
            }
            Thread.sleep(5_000);
        }
    }
}
