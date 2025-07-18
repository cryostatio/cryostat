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

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import io.cryostat.resources.AgentApplicationResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.TimeoutException;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import itest.bases.HttpClientTest;
import itest.resources.S3StorageResource;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import org.hamcrest.Matchers;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@QuarkusIntegrationTest
@QuarkusTestResource(value = AgentApplicationResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
@EnabledIfEnvironmentVariable(
        named = "CI",
        matches = "true",
        disabledReason =
                "Runs well in CI under Docker, but not locally under Podman due to testcontainers"
                        + " 'Broken Pipe' IOException")
public class AgentTargetAnalysisIT extends HttpClientTest {

    public static final String TEMPLATE_CONTINUOUS = "template=Continuous,type=TARGET";

    static WebSocketClient WS_CLIENT;
    static Session WS_SESSION;

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

    @Test
    void testGetAgentTargetReport()
            throws InterruptedException,
                    IOException,
                    DeploymentException,
                    TimeoutException,
                    ExecutionException,
                    java.util.concurrent.TimeoutException {
        Target agent = getAgentTarget();
        long targetId = agent.id;
        String archivedRecordingName = null;
        long recordingId = -1;
        try {
            recordingId =
                    startRecording(targetId, "targetAnalysisReportRecording", TEMPLATE_CONTINUOUS);

            Thread.sleep(5_000);

            String[] archiveJobId = new String[1];
            Executors.newSingleThreadScheduledExecutor()
                    .schedule(
                            () -> {
                                archiveJobId[0] =
                                        given().log()
                                                .all()
                                                .when()
                                                .pathParams("targetId", targetId)
                                                .post("/api/v4.1/targets/{targetId}/reports")
                                                .then()
                                                .log()
                                                .all()
                                                .and()
                                                .assertThat()
                                                // 202 Indicates report generation is in progress
                                                // and sends an
                                                // intermediate
                                                // response.
                                                .statusCode(202)
                                                .contentType(ContentType.TEXT)
                                                .body(Matchers.any(String.class))
                                                .assertThat()
                                                // Verify we get a location header from a 202.
                                                .header(
                                                        "Location",
                                                        String.format(
                                                                "http://localhost:8081/api/v4.1/targets/%d/reports",
                                                                targetId))
                                                .and()
                                                .extract()
                                                .body()
                                                .asString();
                            },
                            10,
                            TimeUnit.SECONDS);

            JsonObject archiveMessage =
                    expectWebSocketNotification(
                            "ArchiveRecordingSuccess",
                            o ->
                                    archiveJobId[0].equals(
                                            o.getJsonObject("message").getString("jobId")));
            archivedRecordingName = archiveMessage.getJsonObject("message").getString("recording");

            expectWebSocketNotification(
                    "ReportSuccess",
                    o ->
                            Objects.equals(
                                    agent.jvmId, o.getJsonObject("message").getString("jvmId")));
        } finally {
            if (archivedRecordingName != null) {
                given().log()
                        .all()
                        .when()
                        .pathParams(
                                "connectUrl", agent.connectUrl, "filename", archivedRecordingName)
                        .delete("/api/beta/recordings/{connectUrl}/{filename}")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(204);
            }

            if (recordingId >= 0) {
                given().log()
                        .all()
                        .when()
                        .pathParams("targetId", targetId, "recordingId", recordingId)
                        .delete("/api/v4/targets/{targetId}/recordings/{recordingId}")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(204);
            }
        }
    }

    private long startRecording(long targetId, String recordingName, String events)
            throws java.util.concurrent.TimeoutException, InterruptedException, ExecutionException {
        MultiMap form = MultiMap.caseInsensitiveMultiMap();
        form.add("recordingName", recordingName);
        form.add("duration", "5");
        form.add("events", events);
        return given().log()
                .all()
                .pathParams("targetId", targetId)
                .formParam("recordingName", recordingName)
                .formParam("duration", "5")
                .formParam("events", events)
                .when()
                .post("/api/v4/targets/{targetId}/recordings")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(201)
                .and()
                .extract()
                .jsonPath()
                .getLong("remoteId");
    }

    private Target getAgentTarget() throws InterruptedException {
        final long timeout = 60_000;
        final long start = System.currentTimeMillis();
        long now = start;
        do {
            now = System.currentTimeMillis();
            var targets =
                    given().log()
                            .all()
                            .when()
                            .get("/api/v4/targets")
                            .then()
                            .log()
                            .all()
                            .and()
                            .assertThat()
                            .statusCode(200)
                            .and()
                            .extract()
                            .body()
                            .as(Target[].class);
            for (var target : targets) {
                if (Objects.equals(target.alias, AgentApplicationResource.ALIAS)) {
                    return target;
                }
            }
            Thread.sleep(5_000);
        } while (now < start + timeout);
        throw new IllegalStateException();
    }

    private record Target(
            long id,
            String jvmId,
            String connectUrl,
            String alias,
            Annotations annotations,
            List<KeyValue> labels,
            boolean agent) {}

    private record Annotations(List<KeyValue> platform, List<KeyValue> cryostat) {}

    private record KeyValue(String key, String value) {}

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
    private static class WebSocketClient {
        private final LinkedBlockingDeque<String> msgQ = new LinkedBlockingDeque<>();
        private final Logger logger = Logger.getLogger(getClass());

        @OnMessage
        void message(String msg) {
            logger.info(msg);
            msgQ.add(msg);
        }
    }
}
