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
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.TimeoutException;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import jakarta.websocket.DeploymentException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@QuarkusIntegrationTest
@EnabledIfEnvironmentVariable(
        named = "PR_CI",
        matches = "true",
        disabledReason =
                "Runs well in PR CI under Docker, but not on main CI or locally under Podman due to"
                        + " testcontainers 'Broken Pipe' IOException")
public class AgentTargetAnalysisIT extends AgentTestBase {

    @Test
    void testGetAgentTargetReport()
            throws InterruptedException,
                    IOException,
                    DeploymentException,
                    TimeoutException,
                    ExecutionException,
                    java.util.concurrent.TimeoutException {
        Target agent = waitForDiscovery();
        long targetId = target.id();
        String archivedRecordingName = null;
        long recordingId = -1;
        try {
            recordingId =
                    startRecording(targetId, "targetAnalysisReportRecording", CONTINUOUS_TEMPLATE);

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
                                                // and sends an intermediate response.
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
                                    agent.jvmId(), o.getJsonObject("message").getString("jvmId")));
        } finally {
            if (archivedRecordingName != null) {
                given().log()
                        .all()
                        .when()
                        .pathParams(
                                "connectUrl", agent.connectUrl(), "filename", archivedRecordingName)
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
}
