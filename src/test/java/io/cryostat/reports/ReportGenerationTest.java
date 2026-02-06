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

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.util.HttpMimeType;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonObject;
import org.apache.http.client.utils.URLEncodedUtils;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class ReportGenerationTest extends AbstractTransactionalTestBase {

    private final ExecutorService worker = ForkJoinPool.commonPool();

    static final String TEST_RECORDING_NAME = "reportGeneration";

    @BeforeEach
    void setupReportGenerationTest() throws Exception {
        // Ensure self target exists before cleanup
        getSelfReferenceTargetId();
        cleanupSelfActiveAndArchivedRecordings();
    }

    @AfterEach
    void cleanupReportGenerationTest() throws Exception {
        cleanupSelfActiveAndArchivedRecordings();
    }

    private String getSelfReferenceConnectUrlEncoded() {
        return URLEncodedUtils.formatSegments(SELF_JMX_URL).substring(1);
    }

    private String archivedReportRequestURL() {
        return String.format("/api/beta/reports/%s", getSelfReferenceConnectUrlEncoded());
    }

    private String recordingRequestURL() {
        return String.format("/api/v4/targets/%d/recordings", getSelfReferenceTargetId());
    }

    @Test
    void testGetActiveReport() throws Exception {
        // Create a recording
        Response postResponse =
                given().basePath("/")
                        .contentType(ContentType.URLENC)
                        .formParam("recordingName", TEST_RECORDING_NAME)
                        .formParam("duration", "5")
                        .formParam("events", "template=ALL")
                        .when()
                        .post(recordingRequestURL())
                        .then()
                        .statusCode(201)
                        .extract()
                        .response();

        JsonObject activeRecording = new JsonObject(postResponse.body().asString());

        // Wait some time to get more recording data
        Thread.sleep(5_000);

        // Get a report for the above recording
        Response resp =
                given().basePath("/")
                        .header("Accept", HttpMimeType.JSON.mime())
                        .when()
                        .get(activeRecording.getString("reportUrl"))
                        .then()
                        .statusCode(
                                Matchers.allOf(
                                        Matchers.greaterThanOrEqualTo(200), Matchers.lessThan(400)))
                        .extract()
                        .response();

        MatcherAssert.assertThat(resp, Matchers.notNullValue());

        // Check that report generation concludes
        CountDownLatch latch = new CountDownLatch(1);
        Future<JsonObject> f =
                worker.submit(
                        () -> {
                            try {
                                return expectWebSocketNotification(
                                        "ReportSuccess", Duration.ofSeconds(15));
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                latch.countDown();
                            }
                        });

        latch.await(30, TimeUnit.SECONDS);
        JsonObject notification = f.get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(notification.getJsonObject("message"), Matchers.notNullValue());
    }

    @Test
    void testGetArchivedReport() throws Exception {
        // Create a recording
        Response postResponse =
                given().basePath("/")
                        .contentType(ContentType.URLENC)
                        .formParam("recordingName", TEST_RECORDING_NAME)
                        .formParam("duration", "5")
                        .formParam("events", "template=ALL")
                        .when()
                        .post(recordingRequestURL())
                        .then()
                        .statusCode(201)
                        .extract()
                        .response();

        JsonObject activeRecording = new JsonObject(postResponse.body().asString());

        // Wait some time to get more recording data
        Thread.sleep(5_000);

        // Check that recording archiving concludes
        CountDownLatch archiveLatch = new CountDownLatch(1);
        Future<JsonObject> archiveFuture =
                worker.submit(
                        () -> {
                            try {
                                return expectWebSocketNotification(
                                        "ArchiveRecordingSuccess", Duration.ofSeconds(15));
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                archiveLatch.countDown();
                            }
                        });

        // Save the recording to archive
        Response saveResponse =
                given().basePath("/")
                        .contentType("text/plain;charset=UTF-8")
                        .body("SAVE")
                        .when()
                        .patch(
                                String.format(
                                        "%s/%d",
                                        recordingRequestURL(), activeRecording.getLong("remoteId")))
                        .then()
                        .statusCode(
                                Matchers.allOf(
                                        Matchers.greaterThanOrEqualTo(200), Matchers.lessThan(400)))
                        .contentType("text/plain;charset=UTF-8")
                        .extract()
                        .response();

        String archiveJobId = saveResponse.body().asString();
        JsonObject notification = archiveFuture.get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
                notification.getJsonObject("message").getMap(),
                Matchers.hasEntry("jobId", archiveJobId));
        String archivedRecordingName = notification.getJsonObject("message").getString("recording");
        MatcherAssert.assertThat(archivedRecordingName, Matchers.not(Matchers.blankOrNullString()));

        // Request a report for the archived recording
        Response reportJobResponse =
                given().basePath("/")
                        .header("Accept", HttpMimeType.JSON.mime())
                        .when()
                        .get(activeRecording.getString("reportUrl"))
                        .then()
                        .statusCode(
                                Matchers.allOf(
                                        Matchers.greaterThanOrEqualTo(200), Matchers.lessThan(400)))
                        .contentType("text/plain")
                        .extract()
                        .response();

        // Check that report generation concludes
        CountDownLatch reportLatch = new CountDownLatch(1);
        Future<JsonObject> reportFuture =
                worker.submit(
                        () -> {
                            try {
                                return expectWebSocketNotification(
                                        "ReportSuccess", Duration.ofSeconds(15));
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                reportLatch.countDown();
                            }
                        });

        // wait for report generation to complete
        String reportJobId = reportJobResponse.body().asString();
        notification = reportFuture.get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
                notification.getJsonObject("message").getMap(),
                Matchers.equalTo(Map.of("jobId", reportJobId, "jvmId", selfJvmId)));

        // FIXME caching is not working in the test harness, so after the job completes we still
        // aren't able to retrieve the report document - we just get issued a new job ID
        //
        // Request a report for the archived recording
        // Response reportResponse =
        //         given().basePath("/")
        //                 .header("Accept", HttpMimeType.JSON.mime())
        //                 .when()
        //                 .get(activeRecording.getString("reportUrl"))
        //                 .then()
        //                 .statusCode(
        //                         Matchers.allOf(
        //                                 Matchers.greaterThanOrEqualTo(200),
        //                                 Matchers.lessThan(400)))
        //                 .contentType("application/json;charset=UTF-8")
        //                 .extract()
        //                 .response();
        //
        // JsonObject jsonResponse = new JsonObject(reportResponse.body().asString());
        // MatcherAssert.assertThat(jsonResponse, Matchers.notNullValue());
        // MatcherAssert.assertThat(
        //         jsonResponse.getMap(),
        //         Matchers.is(Matchers.aMapWithSize(Matchers.greaterThan(0))));
    }

    @Test
    @Disabled("TODO query parameter filter is not implemented")
    void testGetFilteredActiveReport() throws Exception {
        try {
            // Create a recording
            Response postResponse =
                    given().basePath("/")
                            .contentType(ContentType.URLENC)
                            .formParam("recordingName", TEST_RECORDING_NAME)
                            .formParam("duration", "5")
                            .formParam("events", "template=ALL")
                            .when()
                            .post(recordingRequestURL())
                            .then()
                            .statusCode(201)
                            .extract()
                            .response();

            JsonObject activeRecording = new JsonObject(postResponse.body().asString());

            // Wait some time to get more recording data
            Thread.sleep(5_000);

            // Get a report for the above recording
            Response reportResponse =
                    given().basePath("/")
                            .queryParam("filter", "heap")
                            .header("Accept", HttpMimeType.JSON.mime())
                            .when()
                            .get(activeRecording.getString("reportUrl"))
                            .then()
                            .statusCode(
                                    Matchers.allOf(
                                            Matchers.greaterThanOrEqualTo(200),
                                            Matchers.lessThan(400)))
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
        } finally {
            // Cleanup is handled by @AfterEach
        }
    }

    @Test
    void testGetReportThrowsWithNonExistentRecordingName() throws Exception {
        Response response =
                given().basePath("/")
                        .header("Accept", HttpMimeType.HTML.mime())
                        .when()
                        .get(
                                String.format(
                                        "%s/%s", archivedReportRequestURL(), TEST_RECORDING_NAME))
                        .then()
                        .extract()
                        .response();

        MatcherAssert.assertThat(response.statusCode(), Matchers.equalTo(404));
    }
}
