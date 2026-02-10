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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.resources.S3StorageResource;
import io.cryostat.util.HttpMimeType;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.vertx.core.json.JsonObject;
import jakarta.websocket.DeploymentException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class ReportGenerationTest extends AbstractTransactionalTestBase {

    final ExecutorService worker = ForkJoinPool.commonPool();

    static final String TEST_RECORDING_NAME = "reportGeneration";

    @BeforeEach
    void setupReportGenerationTest() {
        cleanupSelfActiveAndArchivedRecordings();
    }

    @AfterEach
    void cleanupReportGenerationTest() {
        cleanupSelfActiveAndArchivedRecordings();
    }

    private long getSelfReferenceTargetId() {
        if (selfId < 1) {
            defineSelfCustomTarget();
        }
        return selfId;
    }

    private String getSelfReferenceConnectUrlEncoded() {
        return URLEncoder.encode(SELF_JMX_URL, StandardCharsets.UTF_8);
    }

    private String archivedReportRequestURL() {
        return String.format("/api/beta/reports/%s", getSelfReferenceConnectUrlEncoded());
    }

    @Test
    void testGetActiveReport() throws Exception {
        long targetId = getSelfReferenceTargetId();

        // Create a recording
        Response postResponse =
                given().log()
                        .all()
                        .when()
                        .basePath("/api/v4/targets/{targetId}/recordings")
                        .pathParam("targetId", targetId)
                        .formParam("recordingName", TEST_RECORDING_NAME)
                        .formParam("duration", "5")
                        .formParam("events", "template=ALL")
                        .post()
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .extract()
                        .response();

        JsonObject activeRecording = new JsonObject(postResponse.body().asString());

        // Wait some time to get more recording data
        Thread.sleep(5_000);

        // Get a report for the above recording
        Response resp =
                given().log()
                        .all()
                        .when()
                        .header("Accept", HttpMimeType.JSON.mime())
                        .get(activeRecording.getString("reportUrl"))
                        .then()
                        .log()
                        .all()
                        .extract()
                        .response();

        MatcherAssert.assertThat(
                resp.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(400)));
        MatcherAssert.assertThat(resp, Matchers.notNullValue());

        // Check that report generation concludes
        Future<JsonObject> f =
                worker.submit(
                        () -> {
                            try {
                                return expectWebSocketNotification(
                                        "ReportSuccess", Duration.ofSeconds(15));
                            } catch (IOException | DeploymentException | InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        });

        JsonObject notification = f.get();
        MatcherAssert.assertThat(notification.getJsonObject("message"), Matchers.notNullValue());
    }

    @Test
    void testGetArchivedReport() throws Exception {
        long targetId = getSelfReferenceTargetId();

        // Create a recording
        Response postResponse =
                given().log()
                        .all()
                        .when()
                        .basePath("/api/v4/targets/{targetId}/recordings")
                        .pathParam("targetId", targetId)
                        .formParam("recordingName", TEST_RECORDING_NAME)
                        .formParam("duration", "5")
                        .formParam("events", "template=ALL")
                        .post()
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .extract()
                        .response();

        JsonObject activeRecording = new JsonObject(postResponse.body().asString());

        // Wait some time to get more recording data
        Thread.sleep(5_000);

        // Check that recording archiving concludes
        Future<JsonObject> archiveFuture =
                worker.submit(
                        () -> {
                            try {
                                return expectWebSocketNotification(
                                        "ArchiveRecordingSuccess", Duration.ofSeconds(15));
                            } catch (IOException | DeploymentException | InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        });

        // Save the recording to archive
        Response saveResponse =
                given().log()
                        .all()
                        .when()
                        .basePath("/api/v4/targets/{targetId}/recordings/{remoteId}")
                        .pathParam("targetId", targetId)
                        .pathParam("remoteId", activeRecording.getLong("remoteId"))
                        .contentType("text/plain;charset=UTF-8")
                        .body("SAVE")
                        .patch()
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(
                                Matchers.both(Matchers.greaterThanOrEqualTo(200))
                                        .and(Matchers.lessThan(400)))
                        .contentType("text/plain;charset=UTF-8")
                        .extract()
                        .response();

        String archiveJobId = saveResponse.body().asString();
        JsonObject notification = archiveFuture.get();
        MatcherAssert.assertThat(
                notification.getJsonObject("message").getMap(),
                Matchers.hasEntry("jobId", archiveJobId));
        String archivedRecordingName = notification.getJsonObject("message").getString("recording");
        MatcherAssert.assertThat(archivedRecordingName, Matchers.not(Matchers.blankOrNullString()));

        // Request a report for the archived recording
        Response jobIdResponse =
                given().log()
                        .all()
                        .when()
                        .header("Accept", HttpMimeType.JSON.mime())
                        .get(activeRecording.getString("reportUrl"))
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(
                                Matchers.both(Matchers.greaterThanOrEqualTo(200))
                                        .and(Matchers.lessThan(400)))
                        .contentType("text/plain")
                        .extract()
                        .response();

        // Check that report generation concludes
        Future<JsonObject> reportFuture =
                worker.submit(
                        () -> {
                            try {
                                return expectWebSocketNotification(
                                        "ReportSuccess", Duration.ofSeconds(15));
                            } catch (IOException | DeploymentException | InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        });

        // wait for report generation to complete
        String reportJobId = jobIdResponse.body().asString();
        notification = reportFuture.get();
        MatcherAssert.assertThat(
                notification.getJsonObject("message").getMap(),
                Matchers.equalTo(Map.of("jobId", reportJobId, "jvmId", selfJvmId)));
    }

    @Test
    @Disabled("TODO query parameter filter is not implemented")
    void testGetFilteredActiveReport() throws Exception {
        long targetId = getSelfReferenceTargetId();

        // Create a recording
        Response postResponse =
                given().log()
                        .all()
                        .when()
                        .basePath("/api/v4/targets/{targetId}/recordings")
                        .pathParam("targetId", targetId)
                        .formParam("recordingName", TEST_RECORDING_NAME)
                        .formParam("duration", "5")
                        .formParam("events", "template=ALL")
                        .post()
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .extract()
                        .response();

        JsonObject activeRecording = new JsonObject(postResponse.body().asString());

        // Wait some time to get more recording data
        Thread.sleep(5_000);

        // Get a report for the above recording
        Response reportResponse =
                given().log()
                        .all()
                        .when()
                        .queryParam("filter", "heap")
                        .header("Accept", HttpMimeType.JSON.mime())
                        .get(activeRecording.getString("reportUrl"))
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(
                                Matchers.both(Matchers.greaterThanOrEqualTo(200))
                                        .and(Matchers.lessThan(400)))
                        .contentType("application/json;charset=UTF-8")
                        .extract()
                        .response();

        JsonObject jsonResponse = new JsonObject(reportResponse.body().asString());
        MatcherAssert.assertThat(jsonResponse, Matchers.notNullValue());
        MatcherAssert.assertThat(jsonResponse.getMap(), Matchers.is(Matchers.aMapWithSize(8)));
        Assertions.assertTrue(jsonResponse.containsKey("HeapContent"));
        Assertions.assertTrue(jsonResponse.containsKey("StringDeduplication"));
        Assertions.assertTrue(jsonResponse.containsKey("PrimitiveToObjectConversion"));
        Assertions.assertTrue(jsonResponse.containsKey("GcFreedRatio"));
        Assertions.assertTrue(jsonResponse.containsKey("HighGc"));
        Assertions.assertTrue(jsonResponse.containsKey("HeapDump"));
        Assertions.assertTrue(jsonResponse.containsKey("Allocations.class"));
        Assertions.assertTrue(jsonResponse.containsKey("LowOnPhysicalMemory"));
        for (var obj : jsonResponse.getMap().entrySet()) {
            var value = JsonObject.mapFrom(obj.getValue());
            Assertions.assertTrue(value.containsKey("score"));
            MatcherAssert.assertThat(
                    value.getDouble("score"),
                    Matchers.anyOf(
                            Matchers.equalTo(-1d),
                            Matchers.equalTo(-2d),
                            Matchers.equalTo(-3d),
                            Matchers.both(Matchers.lessThanOrEqualTo(100d))
                                    .and(Matchers.greaterThanOrEqualTo(0d))));
            Assertions.assertTrue(value.containsKey("name"));
            MatcherAssert.assertThat(
                    value.getString("name"), Matchers.not(Matchers.emptyOrNullString()));
            Assertions.assertTrue(value.containsKey("topic"));
            MatcherAssert.assertThat(
                    value.getString("topic"), Matchers.not(Matchers.emptyOrNullString()));
            Assertions.assertTrue(value.containsKey("description"));
        }
    }

    @Test
    void testGetReportThrowsWithNonExistentRecordingName() {
        io.restassured.response.ValidatableResponse response =
                given().log()
                        .all()
                        .when()
                        .header("Accept", HttpMimeType.HTML.mime())
                        .get(
                                String.format(
                                        "%s/%s", archivedReportRequestURL(), TEST_RECORDING_NAME))
                        .then()
                        .log()
                        .all();

        response.assertThat().statusCode(404);
    }
}
