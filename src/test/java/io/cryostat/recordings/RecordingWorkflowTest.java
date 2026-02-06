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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.resources.S3StorageResource;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
@WithTestResource(
        value = S3StorageResource.class,
        scope = TestResourceScope.MATCHING_RESOURCES,
        parallel = true)
public class RecordingWorkflowTest extends AbstractTransactionalTestBase {

    static final int REQUEST_TIMEOUT_SECONDS = 30;
    static String TEST_RECORDING_NAME = "workflow_itest";
    static long TEST_REMOTE_ID;
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void cleanupRecordingWorkflowTest()
            throws InterruptedException,
                    TimeoutException,
                    ExecutionException,
                    JsonProcessingException {
        cleanupSelfActiveAndArchivedRecordings();
    }

    @Test
    public void testWorkflow() throws Exception {
        // Check preconditions
        Response listResp1 =
                given().when()
                        .get("/api/v4/targets/{id}/recordings", getSelfReferenceTargetId())
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();
        JsonArray listResp = new JsonArray(listResp1.body().asString());
        Assertions.assertTrue(listResp.isEmpty());

        List<String> archivedRecordingFilenames = new ArrayList<>();
        executor.schedule(
                () -> {
                    try {
                        // create an in-memory recording
                        given().contentType(ContentType.URLENC)
                                .formParam("recordingName", TEST_RECORDING_NAME)
                                .formParam("duration", "20")
                                .formParam("events", "template=ALL")
                                .when()
                                .post("/api/v4/targets/{id}/recordings", getSelfReferenceTargetId())
                                .then()
                                .statusCode(201);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                1,
                TimeUnit.SECONDS);
        expectWebSocketNotification("ActiveRecordingCreated");
        Thread.sleep(1000); // allow time for things to settle

        // verify in-memory recording created
        Response listResp2 =
                given().when()
                        .get("/api/v4/targets/{id}/recordings", getSelfReferenceTargetId())
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();
        listResp = new JsonArray(listResp2.body().asString());

        MatcherAssert.assertThat(
                "list should have size 1 after recording creation",
                listResp.size(),
                Matchers.equalTo(1));
        JsonObject recordingInfo = listResp.getJsonObject(0);
        long remoteId = recordingInfo.getLong("remoteId");
        TEST_REMOTE_ID = remoteId;
        MatcherAssert.assertThat(
                recordingInfo.getString("name"), Matchers.equalTo(TEST_RECORDING_NAME));
        MatcherAssert.assertThat(recordingInfo.getString("state"), Matchers.equalTo("RUNNING"));

        Thread.sleep(4_000L); // wait some time to save a portion of the recording

        // save a copy of the partial recording dump
        executor.schedule(
                () -> {
                    try {
                        given().contentType("text/plain")
                                .body("SAVE")
                                .when()
                                .patch(
                                        "/api/v4/targets/{targetId}/recordings/{recordingId}",
                                        getSelfReferenceTargetId(),
                                        TEST_REMOTE_ID)
                                .then()
                                .statusCode(200);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                1,
                TimeUnit.SECONDS);
        // Wait for the archive request to conclude, the server won't block the client
        // while it performs the archive so we need to wait.
        JsonObject archiveNotification = expectWebSocketNotification("ArchiveRecordingSuccess");
        archivedRecordingFilenames.add(
                archiveNotification.getJsonObject("message").getString("recording").toString());

        // check that the in-memory recording list hasn't changed
        Response listResp3 =
                given().when()
                        .get("/api/v4/targets/{id}/recordings", getSelfReferenceTargetId())
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();
        listResp = new JsonArray(listResp3.body().asString());

        MatcherAssert.assertThat(
                "list should have size 1 after recording creation",
                listResp.size(),
                Matchers.equalTo(1));
        recordingInfo = listResp.getJsonObject(0);
        MatcherAssert.assertThat(
                recordingInfo.getString("name"), Matchers.equalTo(TEST_RECORDING_NAME));
        MatcherAssert.assertThat(recordingInfo.getString("state"), Matchers.equalTo("RUNNING"));

        // verify saved recording created
        Response listResp4 =
                given().when()
                        .get("/api/v4/recordings")
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();
        listResp = new JsonArray(listResp4.body().asString());

        MatcherAssert.assertThat(
                "list-saved should have size 1 after recording save",
                listResp.size(),
                Matchers.equalTo(1));
        recordingInfo = listResp.getJsonObject(0);
        MatcherAssert.assertThat(
                recordingInfo.getString("name"),
                Matchers.matchesRegex(
                        SELFTEST_ALIAS + "_" + TEST_RECORDING_NAME + "_[\\d]{8}T[\\d]{6}Z.jfr"));
        String savedDownloadUrl = recordingInfo.getString("downloadUrl");

        expectWebSocketNotification(
                "ActiveRecordingStopped", Duration.ofMinutes(1)); // wait for the dump to complete
        Thread.sleep(1000); // allow time for things to settle

        // verify the in-memory recording list has not changed, except recording is now stopped
        Response listResp5 =
                given().when()
                        .get("/api/v4/targets/{id}/recordings", getSelfReferenceTargetId())
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();
        listResp = new JsonArray(listResp5.body().asString());
        MatcherAssert.assertThat(
                "list should have size 1 after wait period", listResp.size(), Matchers.equalTo(1));
        recordingInfo = listResp.getJsonObject(0);
        MatcherAssert.assertThat(
                recordingInfo.getString("name"), Matchers.equalTo(TEST_RECORDING_NAME));
        MatcherAssert.assertThat(recordingInfo.getString("state"), Matchers.equalTo("STOPPED"));
        MatcherAssert.assertThat(recordingInfo.getInteger("duration"), Matchers.equalTo(20_000));

        // verify in-memory and saved recordings can be downloaded successfully and yield
        // non-empty recording binaries containing events, and that
        // the fully completed in-memory recording is larger than the saved partial copy
        String inMemoryDownloadUrl = recordingInfo.getString("downloadUrl");
        Path inMemoryDownloadPath =
                downloadFile(inMemoryDownloadUrl, TEST_RECORDING_NAME, ".jfr")
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Path savedDownloadPath =
                downloadFile(savedDownloadUrl, TEST_RECORDING_NAME + "_saved", ".jfr")
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        MatcherAssert.assertThat(savedDownloadPath.toFile().length(), Matchers.greaterThan(0L));
        MatcherAssert.assertThat(
                inMemoryDownloadPath.toFile().length(),
                Matchers.greaterThan(savedDownloadPath.toFile().length()));

        List<RecordedEvent> inMemoryEvents = RecordingFile.readAllEvents(inMemoryDownloadPath);
        List<RecordedEvent> savedEvents = RecordingFile.readAllEvents(savedDownloadPath);

        MatcherAssert.assertThat(inMemoryEvents.size(), Matchers.greaterThan(savedEvents.size()));

        String reportUrl = recordingInfo.getString("reportUrl");
        executor.schedule(
                () -> {
                    try {
                        Response reportResponse =
                                given().when()
                                        .get(reportUrl)
                                        .then()
                                        .statusCode(
                                                Matchers.both(Matchers.greaterThanOrEqualTo(200))
                                                        .and(Matchers.lessThan(300)))
                                        .header("Location", Matchers.notNullValue())
                                        .extract()
                                        .response();
                        MatcherAssert.assertThat(
                                reportResponse.body().asString(), Matchers.notNullValue());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                1,
                TimeUnit.SECONDS);

        // Check that report generation concludes
        JsonObject notification = expectWebSocketNotification("ReportSuccess");
        MatcherAssert.assertThat(notification.getJsonObject("message"), Matchers.notNullValue());
    }
}
