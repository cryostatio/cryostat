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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import io.cryostat.AbstractTransactionalTestBase;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.websocket.DeploymentException;
import org.apache.commons.lang3.StringUtils;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public abstract class AbstractReportsTest extends AbstractTransactionalTestBase {

    @Inject ObjectMapper mapper;

    private String archivedRecordingName;

    @BeforeEach
    void setupReportsTest() {
        if (selfId == null) {
            defineSelfCustomTarget();
        }
        archivedRecordingName = null;
    }

    @AfterEach
    void cleanupReportsTest() {
        // Clean up active recordings
        if (selfId != null && selfRecordingId > 0) {
            try {
                cleanupSelfRecording();
            } catch (Exception e) {
                // Ignore cleanup failures
            }
        }

        // Clean up archived recording if created
        if (StringUtils.isNotBlank(selfJvmId) && archivedRecordingName != null) {
            try {
                given().log()
                        .all()
                        .when()
                        .pathParams("jvmId", selfJvmId, "filename", archivedRecordingName)
                        .delete("/api/v5/recordings/{jvmId}/{filename}")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(204);
            } catch (Exception e) {
                // Ignore cleanup failures
            }
            archivedRecordingName = null;
        }
    }

    @Test
    void testGetReportsRules() {
        var json =
                given().log()
                        .all()
                        .when()
                        .get("/api/v5/reports/rules")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .and()
                        .extract()
                        .body()
                        .jsonPath();
        MatcherAssert.assertThat(json, Matchers.notNullValue());
        MatcherAssert.assertThat(json.get("$.size()"), Matchers.greaterThan(0));
    }

    @Test
    void testGetBadArchiveSource() {
        given().log()
                .all()
                .when()
                .get("/api/v5/reports/nonexistent")
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
                .pathParams("jvmId", UUID.randomUUID(), "recordingId", "0")
                .get("/api/v5/targets/{jvmId}/reports/{recordingId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @Test
    void testGetNonexistentRecordingSource() {
        UUID targetId = defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .pathParams("jvmId", selfJvmId, "recordingId", "0")
                .get("/api/v5/targets/{jvmId}/reports/{recordingId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @Test
    void testGetTargetReport()
            throws InterruptedException, IOException, DeploymentException, TimeoutException {
        startSelfRecording("targetAnalysisReportRecording", TEMPLATE_CONTINUOUS);

        String archiveJobId =
                given().log()
                        .all()
                        .when()
                        .pathParams("jvmId", selfJvmId)
                        .post("/api/v5/targets/{jvmId}/reports")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        // 202 Indicates report generation is in progress and sends an
                        // intermediate
                        // response.
                        .statusCode(202)
                        .contentType(ContentType.TEXT)
                        .body(Matchers.any(String.class))
                        .assertThat()
                        // Verify we get a location header from a 202.
                        .header(
                                "Location",
                                String.format("%sapi/v5/targets/%s/reports", baseUrl, selfJvmId))
                        .and()
                        .extract()
                        .body()
                        .asString();

        JsonObject archiveMessage =
                webSocketClient.expectNotification(
                        "ArchiveRecordingSuccess",
                        o -> archiveJobId.equals(o.getJsonObject("message").getString("jobId")));
        archivedRecordingName = archiveMessage.getJsonObject("message").getString("recording");

        webSocketClient.expectNotification(
                "ReportSuccess",
                o -> Objects.equals(selfJvmId, o.getJsonObject("message").getString("jvmId")));
    }

    @Test
    void testGetReportByTargetAndRemoteId() {
        int remoteId = startSelfRecording("reportsTest", TEMPLATE_CONTINUOUS).getInt("remoteId");

        given().log()
                .all()
                .when()
                .pathParams("jvmId", selfJvmId, "recordingId", remoteId)
                .get("/api/v5/targets/{jvmId}/reports/{recordingId}")
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
                        baseUrl + "api/v5/targets/" + selfJvmId + "/reports/" + remoteId);
    }

    @Test
    void testGetReportByUrl() {
        JsonPath recording = startSelfRecording("reportsTestByUrl", TEMPLATE_CONTINUOUS);
        String reportUrl = recording.getString("reportUrl");

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
                .header("Location", baseUrl.toString().replaceAll("/$", "") + reportUrl);
    }

    @Test
    void testArchiveAndGetReportByUrl()
            throws InterruptedException, IOException, DeploymentException, TimeoutException {
        JsonPath activeRecording =
                startSelfRecording("archivedRecordingsTestReportsURL", TEMPLATE_CONTINUOUS);

        Thread.sleep(10_000);

        int remoteId = activeRecording.getInt("remoteId");

        String archiveJobId =
                given().log()
                        .all()
                        .when()
                        .pathParam("jvmId", selfJvmId)
                        .pathParam("remoteId", remoteId)
                        .body("SAVE")
                        .patch("/api/v5/targets/{jvmId}/recordings/{remoteId}")
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

        JsonObject archiveMessage =
                webSocketClient.expectNotification(
                        "ArchiveRecordingSuccess",
                        o -> archiveJobId.equals(o.getJsonObject("message").getString("jobId")));
        archivedRecordingName = archiveMessage.getJsonObject("message").getString("recording");
        String reportUrl = archiveMessage.getJsonObject("message").getString("reportUrl");

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
                        // 202 Indicates report generation is in progress and sends an
                        // intermediate
                        // response.
                        // Verify we get a location header from a 202.
                        .header("Location", baseUrl.toString().replaceAll("/$", "") + reportUrl)
                        .and()
                        .extract()
                        .body()
                        .asString();

        webSocketClient.expectNotification(
                "ReportSuccess",
                o -> reportJobId.equals(o.getJsonObject("message").getString("jobId")));

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
    }
}
