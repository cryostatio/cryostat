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

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.cryostat.resources.S3StorageResource;
import io.cryostat.util.HttpMimeType;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.handler.HttpException;
import itest.bases.StandardSelfTest;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(S3StorageResource.class)
public class ReportGenerationTest extends StandardSelfTest {

    private final ExecutorService worker = ForkJoinPool.commonPool();

    static final String TEST_RECORDING_NAME = "reportGeneration";

    @AfterEach
    void cleanup()
            throws InterruptedException,
                    TimeoutException,
                    ExecutionException,
                    JsonProcessingException {
        cleanupSelfActiveAndArchivedRecordings();
    }

    private String archivedReportRequestURL() {
        return String.format("/api/beta/reports/%s", getSelfReferenceConnectUrlEncoded());
    }

    private String recordingRequestURL() {
        return String.format("/api/v4/targets/%d/recordings", getSelfReferenceTargetId());
    }

    @Test
    void testGetActiveReport() throws Exception {
        JsonObject activeRecording = null;
        // Create a recording
        CompletableFuture<JsonObject> postResponse = new CompletableFuture<>();
        MultiMap form = MultiMap.caseInsensitiveMultiMap();
        form.add("recordingName", TEST_RECORDING_NAME);
        form.add("duration", "5");
        form.add("events", "template=ALL");

        webClient
                .post(recordingRequestURL())
                .sendForm(
                        form,
                        ar -> {
                            if (assertRequestStatus(ar, postResponse)) {
                                postResponse.complete(ar.result().bodyAsJsonObject());
                            }
                        });

        activeRecording = postResponse.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Wait some time to get more recording data
        Thread.sleep(5_000);

        // Get a report for the above recording
        HttpResponse<Buffer> resp =
                webClient
                        .get(activeRecording.getString("reportUrl"))
                        .putHeader(HttpHeaders.ACCEPT.toString(), HttpMimeType.JSON.mime())
                        .send()
                        .toCompletionStage()
                        .toCompletableFuture()
                        .get();
        MatcherAssert.assertThat(
                resp.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(400)));
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

        latch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        JsonObject notification = f.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        MatcherAssert.assertThat(notification.getJsonObject("message"), Matchers.notNullValue());
    }

    @Test
    void testGetArchivedReport() throws Exception {
        JsonObject activeRecording = null;
        CompletableFuture<String> saveRecordingResp = new CompletableFuture<>();
        String archivedRecordingName = null;

        // Create a recording
        CompletableFuture<JsonObject> postResponse = new CompletableFuture<>();
        MultiMap form = MultiMap.caseInsensitiveMultiMap();
        form.add("recordingName", TEST_RECORDING_NAME);
        form.add("duration", "5");
        form.add("events", "template=ALL");

        webClient
                .post(recordingRequestURL())
                .sendForm(
                        form,
                        ar -> {
                            if (assertRequestStatus(ar, postResponse)) {
                                postResponse.complete(ar.result().bodyAsJsonObject());
                            }
                        });

        activeRecording = postResponse.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

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
        webClient
                .patch(
                        String.format(
                                "%s/%d",
                                recordingRequestURL(), activeRecording.getLong("remoteId")))
                .putHeader(HttpHeaders.CONTENT_TYPE.toString(), "text/plain;charset=UTF-8")
                .sendBuffer(
                        Buffer.buffer("SAVE"),
                        ar -> {
                            if (assertRequestStatus(ar, saveRecordingResp)) {
                                MatcherAssert.assertThat(
                                        ar.result().statusCode(),
                                        Matchers.both(Matchers.greaterThanOrEqualTo(200))
                                                .and(Matchers.lessThan(400)));
                                MatcherAssert.assertThat(
                                        ar.result().getHeader(HttpHeaders.CONTENT_TYPE.toString()),
                                        Matchers.equalTo("text/plain;charset=UTF-8"));
                                saveRecordingResp.complete(ar.result().bodyAsString());
                            }
                        });

        String archiveJobId = saveRecordingResp.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        JsonObject notification = archiveFuture.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
                notification.getJsonObject("message").getMap(),
                Matchers.hasEntry("jobId", archiveJobId));
        archivedRecordingName = notification.getJsonObject("message").getString("recording");
        MatcherAssert.assertThat(archivedRecordingName, Matchers.not(Matchers.blankOrNullString()));

        // Request a report for the archived recording
        CompletableFuture<String> jobIdResponse = new CompletableFuture<>();
        webClient
                .get(activeRecording.getString("reportUrl"))
                .putHeader(HttpHeaders.ACCEPT.toString(), HttpMimeType.JSON.mime())
                .send(
                        ar -> {
                            if (assertRequestStatus(ar, jobIdResponse)) {
                                MatcherAssert.assertThat(
                                        ar.result().statusCode(),
                                        Matchers.both(Matchers.greaterThanOrEqualTo(200))
                                                .and(Matchers.lessThan(400)));
                                MatcherAssert.assertThat(
                                        ar.result().getHeader(HttpHeaders.CONTENT_TYPE.toString()),
                                        Matchers.equalTo("text/plain"));
                                jobIdResponse.complete(ar.result().bodyAsString());
                            }
                        });

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
        String reportJobId = jobIdResponse.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        notification = reportFuture.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
                notification.getJsonObject("message").getMap(),
                Matchers.equalTo(Map.of("jobId", reportJobId, "jvmId", getSelfReferenceJvmId())));

        // FIXME caching is not working in the test harness, so after the job completes we still
        // aren't able to retrieve the report document - we just get issued a new job ID
        //
        // Request a report for the archived recording
        // CompletableFuture<JsonObject> reportResponse = new CompletableFuture<>();
        // webClient
        //         .get(activeRecording.getString("reportUrl"))
        //         .putHeader(HttpHeaders.ACCEPT.toString(), HttpMimeType.JSON.mime())
        //         .send(
        //                 ar -> {
        //                     if (assertRequestStatus(ar, reportResponse)) {
        //                         MatcherAssert.assertThat(
        //                                 ar.result().statusCode(),
        //                                 Matchers.both(Matchers.greaterThanOrEqualTo(200))
        //                                         .and(Matchers.lessThan(400)));
        //                         MatcherAssert.assertThat(
        //                                 ar.result()
        //
        // .getHeader(HttpHeaders.CONTENT_TYPE.toString()),
        //                                 Matchers.equalTo("application/json;charset=UTF-8"));
        //                         reportResponse.complete(ar.result().bodyAsJsonObject());
        //                     }
        //                 });

        // JsonObject jsonResponse = reportResponse.get();
        // MatcherAssert.assertThat(jsonResponse, Matchers.notNullValue());
        // MatcherAssert.assertThat(
        //         jsonResponse.getMap(),
        //         Matchers.is(Matchers.aMapWithSize(Matchers.greaterThan(0))));
    }

    @Test
    @Disabled("TODO query parameter filter is not implemented")
    void testGetFilteredActiveReport() throws Exception {
        JsonObject activeRecording = null;
        try {
            // Create a recording
            CompletableFuture<JsonObject> postResponse = new CompletableFuture<>();
            MultiMap form = MultiMap.caseInsensitiveMultiMap();
            form.add("recordingName", TEST_RECORDING_NAME);
            form.add("duration", "5");
            form.add("events", "template=ALL");

            webClient
                    .post(recordingRequestURL())
                    .sendForm(
                            form,
                            ar -> {
                                if (assertRequestStatus(ar, postResponse)) {
                                    postResponse.complete(ar.result().bodyAsJsonObject());
                                }
                            });

            activeRecording = postResponse.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Wait some time to get more recording data
            Thread.sleep(5_000);

            // Get a report for the above recording
            CompletableFuture<JsonObject> reportResponse = new CompletableFuture<>();
            webClient
                    .get(activeRecording.getString("reportUrl"))
                    .addQueryParam("filter", "heap")
                    .putHeader(HttpHeaders.ACCEPT.toString(), HttpMimeType.JSON.mime())
                    .send(
                            ar -> {
                                if (assertRequestStatus(ar, reportResponse)) {
                                    MatcherAssert.assertThat(
                                            ar.result().statusCode(),
                                            Matchers.both(Matchers.greaterThanOrEqualTo(200))
                                                    .and(Matchers.lessThan(400)));
                                    MatcherAssert.assertThat(
                                            ar.result()
                                                    .getHeader(HttpHeaders.CONTENT_TYPE.toString()),
                                            Matchers.equalTo("application/json;charset=UTF-8"));
                                    reportResponse.complete(ar.result().bodyAsJsonObject());
                                }
                            });
            JsonObject jsonResponse = reportResponse.get();
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
            if (activeRecording != null) {
                // Clean up recording
                CompletableFuture<JsonObject> deleteActiveRecResponse = new CompletableFuture<>();
                webClient
                        .delete(
                                String.format(
                                        "%s/%d",
                                        recordingRequestURL(), activeRecording.getLong("id")))
                        .send(
                                ar -> {
                                    if (assertRequestStatus(ar, deleteActiveRecResponse)) {
                                        deleteActiveRecResponse.complete(
                                                ar.result().bodyAsJsonObject());
                                    }
                                });
                deleteActiveRecResponse.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void testGetReportThrowsWithNonExistentRecordingName() throws Exception {
        CompletableFuture<JsonObject> response = new CompletableFuture<>();
        webClient
                .get(String.format("%s/%s", archivedReportRequestURL(), TEST_RECORDING_NAME))
                .putHeader(HttpHeaders.ACCEPT.toString(), HttpMimeType.HTML.mime())
                .send(
                        ar -> {
                            assertRequestStatus(ar, response);
                        });
        ExecutionException ex =
                Assertions.assertThrows(
                        ExecutionException.class,
                        () -> response.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        MatcherAssert.assertThat(
                ((HttpException) ex.getCause()).getStatusCode(), Matchers.equalTo(404));
        MatcherAssert.assertThat(ex.getCause().getMessage(), Matchers.equalTo("Not Found"));
    }
}
