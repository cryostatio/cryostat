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
import java.net.URL;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.cryostat.resources.AgentApplicationResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import itest.resources.S3StorageITResource;
import jakarta.websocket.DeploymentException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

@QuarkusIntegrationTest
@QuarkusTestResource(value = AgentApplicationResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = S3StorageITResource.class, restrictToAnnotatedClass = true)
public class AgentWorkflowIT extends AgentTestBase {

    static final String RECORDING_NAME = AgentWorkflowIT.class.getSimpleName();

    @TestHTTPResource("/api/v4.1/targets")
    URL targetsUrl;

    @Test
    void shouldDiscoverTarget() throws InterruptedException, TimeoutException, ExecutionException {
        MatcherAssert.assertThat(target.agent(), Matchers.equalTo(true));
        MatcherAssert.assertThat(target.alias(), Matchers.equalTo(AgentApplicationResource.ALIAS));
        MatcherAssert.assertThat(target.connectUrl(), Matchers.startsWith("http"));
        MatcherAssert.assertThat(target.jvmId(), Matchers.not(Matchers.blankOrNullString()));
    }

    @Test
    void testListNoRecordings() {
        given().log()
                .all()
                .pathParams("targetId", target.id())
                .when()
                .get("/api/v4/targets/{targetId}/recordings")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200)
                .and()
                .body("$.size()", Matchers.equalTo(0));
    }

    @Test
    void testStartListAndDeleteRecording() {
        var recordingId =
                given().log()
                        .all()
                        .pathParams("targetId", target.id())
                        .when()
                        .formParam("recordingName", RECORDING_NAME)
                        .formParam("events", CONTINUOUS_TEMPLATE)
                        .formParam("duration", 10)
                        .post("/api/v4/targets/{targetId}/recordings")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .and()
                        .body("name", Matchers.equalTo(RECORDING_NAME))
                        .body("state", Matchers.equalTo("RUNNING"))
                        .body("duration", Matchers.equalTo(10_000))
                        .and()
                        .extract()
                        .jsonPath()
                        .getLong("remoteId");

        given().log()
                .all()
                .pathParams("targetId", target.id())
                .when()
                .get("/api/v4/targets/{targetId}/recordings")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200)
                .and()
                .body("$.size()", Matchers.equalTo(1))
                .and()
                .body("[0].name", Matchers.equalTo(RECORDING_NAME))
                .body("[0].state", Matchers.equalTo("RUNNING"))
                .body("[0].duration", Matchers.equalTo(10_000));

        given().log()
                .all()
                .pathParams("targetId", target.id(), "recordingId", recordingId)
                .when()
                .delete("/api/v4/targets/{targetId}/recordings/{recordingId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);

        testListNoRecordings();
    }

    @Test
    void testGetAgentTargetReport()
            throws InterruptedException,
                    IOException,
                    DeploymentException,
                    ExecutionException,
                    TimeoutException {
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
                                                                "%s/%d/reports",
                                                                targetsUrl.toString(), targetId))
                                                .and()
                                                .extract()
                                                .body()
                                                .asString();
                            },
                            10,
                            TimeUnit.SECONDS);

            JsonObject archiveMessage =
                    webSocketClient.expectNotification(
                            "ArchiveRecordingSuccess",
                            o ->
                                    archiveJobId[0].equals(
                                            o.getJsonObject("message").getString("jobId")));
            archivedRecordingName = archiveMessage.getJsonObject("message").getString("recording");

            webSocketClient.expectNotification(
                    "ReportSuccess",
                    o ->
                            Objects.equals(
                                    target.jvmId(), o.getJsonObject("message").getString("jvmId")));
        } finally {
            if (archivedRecordingName != null) {
                given().log()
                        .all()
                        .when()
                        .pathParams(
                                "connectUrl",
                                target.connectUrl(),
                                "filename",
                                archivedRecordingName)
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
            throws TimeoutException, InterruptedException, ExecutionException {
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
