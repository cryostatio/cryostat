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

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import io.cryostat.resources.AgentApplicationResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.vertx.core.json.JsonObject;
import itest.bases.HttpClientTest;
import itest.resources.S3StorageResource;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import junit.framework.AssertionFailedError;
import org.hamcrest.Matchers;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

@QuarkusTestResource(value = AgentApplicationResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class AgentTestBase extends HttpClientTest {

    static final Duration DISCOVERY_PERIOD = Duration.ofSeconds(5);
    static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);
    static final String CONTINUOUS_TEMPLATE = "template=Continuous,type=TARGET";

    static WebSocketClient WS_CLIENT;
    static Session WS_SESSION;

    protected Target target;

    @BeforeAll
    static void setupWebSocketClient() throws IOException, DeploymentException {
        WS_CLIENT = new WebSocketClient();
        WS_SESSION =
                ContainerProvider.getWebSocketContainer()
                        .connectToServer(
                                WS_CLIENT, URI.create("ws://localhost:8081/api/notifications"));
    }

    @BeforeEach
    void clearWebSocketNotifications() {
        WS_CLIENT.msgQ.clear();
    }

    @AfterAll
    static void tearDownWebSocketClient() throws IOException {
        WS_SESSION.close();
    }

    @BeforeEach
    void getTarget() throws InterruptedException, TimeoutException, ExecutionException {
        target = waitForDiscovery();
    }

    public static boolean enabled() {
        String arch = Optional.ofNullable(System.getenv("CI_ARCH")).orElse("").trim();
        boolean ci = Boolean.valueOf(System.getenv("CI"));
        return !ci || (ci && "amd64".equalsIgnoreCase(arch));
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

    protected JsonObject expectWebSocketNotification(String category)
            throws IOException, DeploymentException, InterruptedException, TimeoutException {
        return expectWebSocketNotification(category, Duration.ofSeconds(30), v -> true);
    }

    protected JsonObject expectWebSocketNotification(String category, Duration timeout)
            throws IOException, DeploymentException, InterruptedException, TimeoutException {
        return expectWebSocketNotification(category, timeout, v -> true);
    }

    protected JsonObject expectWebSocketNotification(
            String category, Predicate<JsonObject> predicate)
            throws IOException, DeploymentException, InterruptedException, TimeoutException {
        return expectWebSocketNotification(category, Duration.ofSeconds(30), predicate);
    }

    protected JsonObject expectWebSocketNotification(
            String category, Duration timeout, Predicate<JsonObject> predicate)
            throws IOException, DeploymentException, InterruptedException, TimeoutException {
        long now = System.nanoTime();
        long deadline = now + timeout.toNanos();
        logger.infov(
                "waiting up to {0} for a WebSocket notification with category={1}",
                timeout, category);
        do {
            now = System.nanoTime();
            String msg = WS_CLIENT.msgQ.poll(1, TimeUnit.SECONDS);
            if (msg == null) {
                continue;
            }
            JsonObject obj = new JsonObject(msg);
            String msgCategory = obj.getJsonObject("meta").getString("category");
            if (category.equals(msgCategory) && predicate.test(obj)) {
                return obj;
            }
        } while (now < deadline);
        throw new TimeoutException();
    }

    @ClientEndpoint
    static class WebSocketClient {
        private final LinkedBlockingDeque<String> msgQ = new LinkedBlockingDeque<>();
        private final Logger logger = Logger.getLogger(getClass());

        @OnMessage
        void message(String msg) {
            logger.info(msg);
            msgQ.add(msg);
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
