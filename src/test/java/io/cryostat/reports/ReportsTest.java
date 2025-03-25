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
package io.cryostat.reports;

import static io.restassured.RestAssured.given;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.CacheEnabledTestProfile;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.vertx.core.json.JsonObject;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestHTTPEndpoint(Reports.class)
@TestProfile(CacheEnabledTestProfile.class)
public class ReportsTest extends AbstractTransactionalTestBase {

    @TestHTTPResource("/api/notifications")
    URI wsUri;

    private static final LinkedBlockingDeque<String> WS_MESSAGES = new LinkedBlockingDeque<>();

    @Test
    void testGetBadArchiveSource() {
        given().log()
                .all()
                .when()
                .get("/api/v4/reports/nonexistent")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void testGetNonexistentTargetSource() {
        given().log()
                .all()
                .when()
                .pathParams("targetId", Integer.MAX_VALUE, "recordingName", "foo")
                .get("/api/v4/targets/{targetId}/reports/{recordingName}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @Test
    void testGetNonexistentRecordingSource() {
        int targetId = defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .pathParams("targetId", targetId, "recordingName", "foo")
                .get("/api/v4/targets/{targetId}/reports/{recordingName}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @Test
    void testGetReportByTargetAndRemoteId() {
        int targetId = defineSelfCustomTarget();
        int remoteId =
                given().log()
                        .all()
                        .when()
                        .pathParams(Map.of("targetId", targetId))
                        .formParam("recordingName", "activeRecordingsTestReports")
                        .formParam("events", "template=Continuous")
                        .pathParam("targetId", targetId)
                        .post("/api/v4/targets/{targetId}/recordings")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .and()
                        .extract()
                        .body()
                        .jsonPath()
                        .getInt("remoteId");

        given().log()
                .all()
                .when()
                .pathParams("targetId", targetId, "remoteId", remoteId)
                .get("/api/v4/targets/{targetId}/reports/{remoteId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(202)
                .contentType(ContentType.TEXT)
                .body(Matchers.any(String.class))
                .assertThat()
                // 202 Indicates report generation is in progress and sends an intermediate
                // response.
                // Verify we get a location header from a 202.
                .header(
                        "Location",
                        "http://localhost:8081/api/v4/targets/"
                                + targetId
                                + "/reports/"
                                + remoteId);

        given().log()
                .all()
                .when()
                .pathParams("targetId", targetId, "remoteId", remoteId)
                .delete("/api/v4/targets/{targetId}/recordings/{remoteId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);
    }

    @Test
    void testGetReportByUrl() {
        int targetId = defineSelfCustomTarget();
        JsonPath recording =
                given().log()
                        .all()
                        .when()
                        .pathParams(Map.of("targetId", targetId))
                        .formParam("recordingName", "activeRecordingsTestReportsURL")
                        .formParam("events", "template=Continuous")
                        .pathParam("targetId", targetId)
                        .post("/api/v4/targets/{targetId}/recordings")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .and()
                        .extract()
                        .body()
                        .jsonPath();

        String reportUrl = recording.getString("reportUrl");
        int remoteId = recording.getInt("remoteId");

        given().log()
                .all()
                .when()
                .get(reportUrl)
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(202)
                .contentType(ContentType.TEXT)
                .body(Matchers.any(String.class))
                .assertThat()
                // 202 Indicates report generation is in progress and sends an intermediate
                // response.
                // Verify we get a location header from a 202.
                .header("Location", "http://localhost:8081" + reportUrl);

        given().log()
                .all()
                .when()
                .pathParams("targetId", targetId, "remoteId", remoteId)
                .delete("/api/v4/targets/{targetId}/recordings/{remoteId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);
    }

    @Test
    void testArchiveAndGetReportByUrl()
            throws InterruptedException, IOException, DeploymentException {
        int targetId = defineSelfCustomTarget();
        JsonPath activeRecording =
                given().log()
                        .all()
                        .when()
                        .pathParams(Map.of("targetId", targetId))
                        .formParam("recordingName", "archivedRecordingsTestReportsURL")
                        .formParam("events", "template=Continuous")
                        .pathParam("targetId", targetId)
                        .post("/api/v4/targets/{targetId}/recordings")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .and()
                        .extract()
                        .body()
                        .jsonPath();

        Thread.sleep(10_000);

        int remoteId = activeRecording.getInt("remoteId");

        String archiveJobId =
                given().log()
                        .all()
                        .when()
                        .pathParam("targetId", targetId)
                        .pathParam("remoteId", remoteId)
                        .body("SAVE")
                        .patch("/api/v4/targets/{targetId}/recordings/{remoteId}")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .and()
                        .extract()
                        .body()
                        .asString();

        String archivedRecordingName = null;
        String reportUrl = null;
        boolean archiveJobComplete = false;
        long start = System.nanoTime();
        long timeout = TimeUnit.SECONDS.toNanos(30);
        try (Session session =
                ContainerProvider.getWebSocketContainer()
                        .connectToServer(WebSocketClient.class, wsUri)) {
            long now;
            do {
                now = System.nanoTime();

                JsonObject msg = new JsonObject(WS_MESSAGES.poll(10, TimeUnit.SECONDS));
                String category = msg.getJsonObject("meta").getString("category");
                if ("ArchiveRecordingSuccess".equals(category)) {
                    JsonObject body = msg.getJsonObject("message");
                    MatcherAssert.assertThat(
                            body.getString("jobId"), Matchers.equalTo(archiveJobId));
                    archivedRecordingName = body.getString("recording");
                    reportUrl = body.getString("reportUrl");
                    archiveJobComplete = true;
                    break;
                }
            } while (now < start + timeout);
        } finally {
            WS_MESSAGES.clear();
        }
        Assertions.assertTrue(archiveJobComplete);

        String reportJobId =
                given().log()
                        .all()
                        .when()
                        .get(reportUrl)
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(202)
                        .contentType(ContentType.TEXT)
                        .body(Matchers.any(String.class))
                        .assertThat()
                        // 202 Indicates report generation is in progress and sends an intermediate
                        // response.
                        // Verify we get a location header from a 202.
                        .header("Location", "http://localhost:8081" + reportUrl)
                        .and()
                        .extract()
                        .body()
                        .asString();
        boolean reportJobComplete = false;
        start = System.nanoTime();
        try (Session session =
                ContainerProvider.getWebSocketContainer()
                        .connectToServer(WebSocketClient.class, wsUri)) {
            long now;
            do {
                now = System.nanoTime();

                JsonObject msg = new JsonObject(WS_MESSAGES.poll(10, TimeUnit.SECONDS));
                String category = msg.getJsonObject("meta").getString("category");
                if ("ReportSuccess".equals(category)) {
                    JsonObject body = msg.getJsonObject("message");
                    MatcherAssert.assertThat(
                            body.getString("jobId"), Matchers.equalTo(reportJobId));
                    reportJobComplete = true;
                    break;
                }
            } while (now < start + timeout);
        } finally {
            WS_MESSAGES.clear();
        }
        Assertions.assertTrue(reportJobComplete);

        given().log()
                .all()
                .when()
                .get(reportUrl)
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body(Matchers.any(String.class));

        given().log()
                .all()
                .when()
                .pathParams("targetId", targetId, "remoteId", remoteId)
                .delete("/api/v4/targets/{targetId}/recordings/{remoteId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);

        given().log()
                .all()
                .when()
                .pathParams("connectUrl", SELF_JMX_URL, "filename", archivedRecordingName)
                .delete("/api/beta/recordings/{connectUrl}/{filename}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);
    }

    @ClientEndpoint
    static class WebSocketClient {
        @OnMessage
        void message(String msg) {
            WS_MESSAGES.add(msg);
        }
    }
}
