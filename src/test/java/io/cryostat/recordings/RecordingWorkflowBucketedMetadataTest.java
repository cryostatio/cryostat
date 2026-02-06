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
package io.cryostat.recordings;

import static io.restassured.RestAssured.given;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.resources.S3StorageBucketedMetadataResource;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

@QuarkusTest
@WithTestResource(
        value = S3StorageBucketedMetadataResource.class,
        scope = TestResourceScope.MATCHING_RESOURCES)
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
public class RecordingWorkflowBucketedMetadataTest extends AbstractTransactionalTestBase {

    private final ExecutorService worker = ForkJoinPool.commonPool();

    static final int REQUEST_TIMEOUT_SECONDS = RecordingWorkflowTest.REQUEST_TIMEOUT_SECONDS;
    String testRecordingName = "bucketedmeta_itest";
    long testRemoteId;

    @BeforeEach
    void setupRecordingWorkflowBucketedMetadataTest()
            throws InterruptedException,
                    TimeoutException,
                    ExecutionException,
                    JsonProcessingException {
        cleanupSelfActiveAndArchivedRecordings();
    }

    @AfterEach
    void cleanupRecordingWorkflowBucketedMetadataTest()
            throws InterruptedException,
                    TimeoutException,
                    ExecutionException,
                    JsonProcessingException {
        cleanupSelfActiveAndArchivedRecordings();
    }

    @Test
    public void testWorkflow() throws Exception {
        // Check preconditions
        Response listResponse1 =
                given().when()
                        .get("/api/v4/targets/{targetId}/recordings", getSelfReferenceTargetId())
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();
        JsonArray listResp = new JsonArray(listResponse1.body().asString());
        Assertions.assertTrue(listResp.isEmpty());

        List<String> archivedRecordingFilenames = new ArrayList<>();
        // create an in-memory recording
        given().contentType("application/x-www-form-urlencoded")
                .formParam("recordingName", testRecordingName)
                .formParam("duration", "20")
                .formParam("events", "template=ALL")
                .when()
                .post("/api/v4/targets/{targetId}/recordings", getSelfReferenceTargetId())
                .then()
                .statusCode(201);
        Thread.sleep(500);

        // verify in-memory recording created
        Response listResponse2 =
                given().when()
                        .get("/api/v4/targets/{targetId}/recordings", getSelfReferenceTargetId())
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();
        listResp = new JsonArray(listResponse2.body().asString());

        MatcherAssert.assertThat(
                "list should have size 1 after recording creation",
                listResp.size(),
                Matchers.equalTo(1));
        JsonObject recordingInfo = listResp.getJsonObject(0);
        long remoteId = recordingInfo.getLong("remoteId");
        testRemoteId = remoteId;
        MatcherAssert.assertThat(
                recordingInfo.getString("name"), Matchers.equalTo(testRecordingName));
        MatcherAssert.assertThat(recordingInfo.getString("state"), Matchers.equalTo("RUNNING"));

        Thread.sleep(5_000L); // wait some time to save a portion of the recording

        // save a copy of the partial recording dump
        given().contentType("text/plain")
                .body("SAVE")
                .when()
                .patch(
                        "/api/v4/targets/{targetId}/recordings/{remoteId}",
                        getSelfReferenceTargetId(),
                        testRemoteId)
                .then()
                .statusCode(200);

        // Wait for the archive request to conclude, the server won't block the client
        // while it performs the archive so we need to wait.
        CountDownLatch archiveLatch = new CountDownLatch(1);
        Future<JsonObject> future =
                worker.submit(
                        () -> {
                            try {
                                return expectWebSocketNotification(
                                        "ArchiveRecordingSuccess", Duration.ofSeconds(5));
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                archiveLatch.countDown();
                            }
                        });
        archiveLatch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        JsonObject archiveNotification = future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        archivedRecordingFilenames.add(
                archiveNotification.getJsonObject("message").getString("recording").toString());

        Thread.sleep(500);
        // check that the in-memory recording list hasn't changed
        Response listResponse3 =
                given().when()
                        .get("/api/v4/targets/{targetId}/recordings", getSelfReferenceTargetId())
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();
        listResp = new JsonArray(listResponse3.body().asString());

        MatcherAssert.assertThat(
                "list should have size 1 after recording creation",
                listResp.size(),
                Matchers.equalTo(1));
        recordingInfo = listResp.getJsonObject(0);
        MatcherAssert.assertThat(
                recordingInfo.getString("name"), Matchers.equalTo(testRecordingName));
        MatcherAssert.assertThat(recordingInfo.getString("state"), Matchers.equalTo("RUNNING"));

        // verify saved recording created
        Response listResponse4 =
                given().when()
                        .get("/api/v4/recordings")
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();
        listResp = new JsonArray(listResponse4.body().asString());
        Thread.sleep(500);

        MatcherAssert.assertThat(
                "list-saved should have size 1 after recording save",
                listResp.size(),
                Matchers.equalTo(1));
        recordingInfo = listResp.getJsonObject(0);
        MatcherAssert.assertThat(
                recordingInfo.getString("name"),
                Matchers.matchesRegex(
                        SELFTEST_ALIAS + "_" + testRecordingName + "_[\\d]{8}T[\\d]{6}Z.jfr"));
        String savedDownloadUrl = recordingInfo.getString("downloadUrl");
        Thread.sleep(30_000L); // wait for the dump to complete

        // verify the in-memory recording list has not changed, except recording is now stopped
        Response listResponse5 =
                given().when()
                        .get("/api/v4/targets/{targetId}/recordings", getSelfReferenceTargetId())
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();
        listResp = new JsonArray(listResponse5.body().asString());
        MatcherAssert.assertThat(
                "list should have size 1 after wait period", listResp.size(), Matchers.equalTo(1));
        recordingInfo = listResp.getJsonObject(0);
        MatcherAssert.assertThat(
                recordingInfo.getString("name"), Matchers.equalTo(testRecordingName));
        MatcherAssert.assertThat(recordingInfo.getString("state"), Matchers.equalTo("STOPPED"));
        MatcherAssert.assertThat(recordingInfo.getInteger("duration"), Matchers.equalTo(20_000));

        // verify in-memory and saved recordings can be downloaded successfully and yield
        // non-empty recording binaries containing events, and that
        // the fully completed in-memory recording is larger than the saved partial copy
        String inMemoryDownloadUrl = recordingInfo.getString("downloadUrl");
        Path inMemoryDownloadPath =
                downloadFile(inMemoryDownloadUrl, testRecordingName, ".jfr")
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Thread.sleep(100);

        Path savedDownloadPath =
                downloadFile(savedDownloadUrl, testRecordingName + "_saved", ".jfr")
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Thread.sleep(100);
        MatcherAssert.assertThat(savedDownloadPath.toFile().length(), Matchers.greaterThan(0L));
        MatcherAssert.assertThat(
                inMemoryDownloadPath.toFile().length(),
                Matchers.greaterThan(savedDownloadPath.toFile().length()));

        List<RecordedEvent> inMemoryEvents = RecordingFile.readAllEvents(inMemoryDownloadPath);
        List<RecordedEvent> savedEvents = RecordingFile.readAllEvents(savedDownloadPath);

        MatcherAssert.assertThat(inMemoryEvents.size(), Matchers.greaterThan(savedEvents.size()));

        String reportUrl = recordingInfo.getString("reportUrl");

        CompletableFuture<Response> reportResponseFuture = new CompletableFuture<>();
        worker.submit(
                () -> {
                    try {
                        Response reportResponse =
                                given().when()
                                        .get(reportUrl)
                                        .then()
                                        .statusCode(
                                                Matchers.allOf(
                                                        Matchers.greaterThanOrEqualTo(200),
                                                        Matchers.lessThan(300)))
                                        .extract()
                                        .response();
                        reportResponseFuture.complete(reportResponse);
                    } catch (Exception e) {
                        reportResponseFuture.completeExceptionally(e);
                    }
                });
        Response reportResponse =
                reportResponseFuture.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        MatcherAssert.assertThat(reportResponse.getHeader("Location"), Matchers.notNullValue());
        MatcherAssert.assertThat(reportResponse.body().asString(), Matchers.notNullValue());

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
}
