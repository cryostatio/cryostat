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
package itest.agent;

import static io.restassured.RestAssured.given;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import io.cryostat.resources.AgentApplicationResource;

import io.quarkus.test.common.QuarkusTestResource;
import itest.bases.HttpClientTest;
import itest.resources.S3StorageResource;
import junit.framework.AssertionFailedError;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;

@QuarkusTestResource(value = AgentApplicationResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class AgentTestBase extends HttpClientTest {

    static final Duration DISCOVERY_PERIOD = Duration.ofSeconds(5);
    static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);
    static final String CONTINUOUS_TEMPLATE = "template=Continuous,type=TARGET";

    protected Target target;

    @BeforeEach
    void getTarget() throws InterruptedException, TimeoutException, ExecutionException {
        target = waitForDiscovery();
    }

    Target waitForDiscovery() throws InterruptedException, TimeoutException, ExecutionException {
        return waitForDiscovery(
                t ->
                        t.agent()
                                && AgentApplicationResource.ALIAS.equals(t.alias())
                                && String.format(
                                                "http://%s:%d",
                                                AgentApplicationResource.ALIAS,
                                                AgentApplicationResource.PORT)
                                        .equals(t.connectUrl()));
    }

    Target waitForDiscovery(Predicate<Target> p)
            throws InterruptedException, TimeoutException, ExecutionException {
        long last = System.nanoTime();
        long elapsed = 0;
        while (true) {
            var targets =
                    Arrays.asList(
                                    given().log()
                                            .all()
                                            .when()
                                            .get("/api/v4/targets")
                                            .then()
                                            .log()
                                            .all()
                                            .and()
                                            .assertThat()
                                            .statusCode(
                                                    Matchers.both(
                                                                    Matchers.greaterThanOrEqualTo(
                                                                            200))
                                                            .and(Matchers.lessThan(300)))
                                            .and()
                                            .extract()
                                            .body()
                                            .as(Target[].class))
                            .stream()
                            .filter(p)
                            .toList();
            switch (targets.size()) {
                case 0:
                    long now = System.nanoTime();
                    elapsed += (now - last);
                    last = now;
                    if (Duration.ofNanos(elapsed).compareTo(DISCOVERY_TIMEOUT) > 0) {
                        throw new AssertionFailedError("Timed out");
                    }
                    Thread.sleep(DISCOVERY_PERIOD.toMillis());
                    continue;
                case 1:
                    return targets.get(0);
                default:
                    throw new IllegalStateException();
            }
        }
    }

    record Target(
            long id,
            String jvmId,
            String connectUrl,
            String alias,
            List<KeyValue> labels,
            Annotations annotations,
            boolean agent) {}

    record Annotations(List<KeyValue> cryostat, List<KeyValue> platform) {}

    record KeyValue(String key, String value) {}
}
