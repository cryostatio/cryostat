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
package io.cryostat.agent;

import static io.restassured.RestAssured.given;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import io.cryostat.AbstractTestBase;
import io.cryostat.resources.AgentApplicationResource;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;

public abstract class AgentTestBase extends AbstractTestBase {

    static final Duration DISCOVERY_PERIOD = Duration.ofSeconds(30);
    static final Duration DISCOVERY_TIMEOUT = Duration.ofMinutes(5);

    protected static volatile ExecutorService WORKER = Executors.newCachedThreadPool();

    protected Target target;

    @BeforeEach
    void getTarget() throws InterruptedException, TimeoutException, ExecutionException {
        if (target == null) {
            target = waitForDiscovery();
        }
    }

    Target waitForDiscovery() throws InterruptedException, TimeoutException, ExecutionException {
        return waitForDiscovery(t -> t.agent() && AgentApplicationResource.ALIAS.equals(t.alias()));
    }

    Target waitForDiscovery(Predicate<Target> p)
            throws InterruptedException, TimeoutException, ExecutionException {
        CompletableFuture<Target> webSocketFuture =
                CompletableFuture.supplyAsync(() -> waitForDiscoveryViaWebSocket(p), WORKER);

        CompletableFuture<Target> pollingFuture =
                CompletableFuture.supplyAsync(() -> waitForDiscoveryViaPolling(p), WORKER);

        try {
            Target result =
                    CompletableFuture.anyOf(webSocketFuture, pollingFuture)
                            .thenApply(r -> (Target) r)
                            .get(DISCOVERY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            webSocketFuture.cancel(true);
            pollingFuture.cancel(true);

            return result;
        } catch (ExecutionException e) {
            webSocketFuture.cancel(true);
            pollingFuture.cancel(true);

            if (e.getCause() instanceof RuntimeException) {
                Throwable cause = e.getCause().getCause();
                if (cause instanceof TimeoutException) {
                    throw (TimeoutException) cause;
                } else if (cause instanceof InterruptedException) {
                    throw (InterruptedException) cause;
                }
            }
            throw e;
        } catch (InterruptedException e) {
            webSocketFuture.cancel(true);
            pollingFuture.cancel(true);
            throw e;
        }
    }

    private Target waitForDiscoveryViaWebSocket(Predicate<Target> p) {
        try {
            JsonObject notification =
                    webSocketClient.expectNotification(
                            "TargetJvmDiscovery", DISCOVERY_TIMEOUT, msg -> matchesAgent(msg));

            Target target = extractTargetFromNotification(notification);

            if (!p.test(target)) {
                throw new IllegalStateException(
                        "Target discovered via WebSocket but does not match predicate");
            }

            return target;
        } catch (InterruptedException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean matchesAgent(JsonObject msg) {
        JsonObject event = msg.getJsonObject("message").getJsonObject("event");
        if (!"FOUND".equals(event.getString("kind"))) {
            return false;
        }
        JsonObject serviceRef = event.getJsonObject("serviceRef");
        if (serviceRef == null) {
            return false;
        }
        String alias = serviceRef.getString("alias");
        return AgentApplicationResource.ALIAS.equals(alias);
    }

    private Target extractTargetFromNotification(JsonObject notification) {
        JsonObject event = notification.getJsonObject("message").getJsonObject("event");
        JsonObject serviceRef = event.getJsonObject("serviceRef");

        long id = serviceRef.getLong("id");
        String jvmId = serviceRef.getString("jvmId");
        String connectUrl = serviceRef.getString("connectUrl");
        String alias = serviceRef.getString("alias");

        List<KeyValue> labels = extractKeyValueArray(serviceRef.getValue("labels"));
        Annotations annotations = extractAnnotations(serviceRef.getValue("annotations"));
        boolean agent = serviceRef.getBoolean("agent", false);

        return new Target(id, jvmId, connectUrl, alias, labels, annotations, agent);
    }

    private List<KeyValue> extractKeyValueArray(Object arrayObj) {
        if (arrayObj == null) {
            return List.of();
        }
        if (arrayObj instanceof JsonArray) {
            JsonArray array = (JsonArray) arrayObj;
            return array.stream()
                    .map(
                            item -> {
                                JsonObject obj = (JsonObject) item;
                                return new KeyValue(obj.getString("key"), obj.getString("value"));
                            })
                    .toList();
        }
        return List.of();
    }

    private Annotations extractAnnotations(Object annotationsObj) {
        if (annotationsObj == null) {
            return new Annotations(List.of(), List.of());
        }
        if (!(annotationsObj instanceof JsonObject)) {
            return new Annotations(List.of(), List.of());
        }

        JsonObject annotationsJson = (JsonObject) annotationsObj;
        List<KeyValue> cryostatList = extractKeyValueArray(annotationsJson.getValue("cryostat"));
        List<KeyValue> platformList = extractKeyValueArray(annotationsJson.getValue("platform"));

        return new Annotations(cryostatList, platformList);
    }

    private Target waitForDiscoveryViaPolling(Predicate<Target> p) {
        long last = System.nanoTime();
        long elapsed = 0;
        while (!Thread.currentThread().isInterrupted()) {
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
                        throw new IllegalStateException("Timed out waiting for target discovery");
                    }
                    long remaining = DISCOVERY_PERIOD.toMillis();
                    while (remaining > 0 && !Thread.currentThread().isInterrupted()) {
                        long step = Math.min(remaining, 500);
                        try {
                            Thread.sleep(step);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        remaining -= step;
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        throw new RuntimeException(
                                new InterruptedException("Discovery polling interrupted"));
                    }
                    continue;
                case 1:
                    return targets.get(0);
                default:
                    throw new IllegalStateException("Multiple matching targets found");
            }
        }
        throw new RuntimeException(new InterruptedException("Discovery polling interrupted"));
    }

    @AfterAll
    static void shutDownWorker() throws InterruptedException {
        WORKER.shutdownNow();
        WORKER.awaitTermination(10, TimeUnit.SECONDS);
        WORKER = Executors.newCachedThreadPool();
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
