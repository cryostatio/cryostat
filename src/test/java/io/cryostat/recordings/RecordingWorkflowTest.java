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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.resources.S3StorageResource;
import io.cryostat.util.HttpMimeType;

import io.quarkus.test.common.QuarkusTestResource;
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

@QuarkusTest
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class RecordingWorkflowTest extends AbstractTransactionalTestBase {

    static final String TEST_RECORDING_NAME = "workflow_itest";
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    @BeforeEach
    void setupRecordingWorkflowTest() {
        cleanupSelfActiveAndArchivedRecordings();
    }

    @AfterEach
    void cleanupRecordingWorkflowTest() {
        cleanupSelfActiveAndArchivedRecordings();
    }

    @Test
    public void testWorkflow() throws Exception {
        // Ensure self target is defined
        if (selfId < 1) {
            defineSelfCustomTarget();
        }

        // Check preconditions - no recordings should exist
        Response listResponse1 =
                given().log()
                        .all()
                        .when()
                        .basePath("/api/v4/targets/{targetId}/recordings")
                        .pathParam("targetId", selfId)
                        .get()
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .response();
        JsonArray listResp = new JsonArray(listResponse1.body().asString());
        Assertions.assertTrue(listResp.isEmpty());

        // Schedule recording creation asynchronously to allow WebSocket listener to be ready
        executor.schedule(
                () -> {
                    given().log()
                            .all()
                            .when()
                            .basePath("/api/v4/targets/{targetId}/recordings")
                            .pathParam("targetId", selfId)
                            .formParam("recordingName", TEST_RECORDING_NAME)
                            .formParam("duration", "20")
                            .formParam("events", "template=ALL")
                            .post()
                            .then()
                            .log()
                            .all()
                            .and()
                            .assertThat()
                            .statusCode(201);
                },
                1,
                TimeUnit.SECONDS);

        // Wait for ActiveRecordingCreated notification
        expectWebSocketNotification("ActiveRecordingCreated");
        Thread.sleep(1000); // allow time for things to settle

        // Verify in-memory recording created
        Response listResponse2 =
                given().log()
                        .all()
                        .when()
                        .basePath("/api/v4/targets/{targetId}/recordings")
                        .pathParam("targetId", selfId)
                        .get()
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
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
        MatcherAssert.assertThat(
                recordingInfo.getString("name"), Matchers.equalTo(TEST_RECORDING_NAME));
        MatcherAssert.assertThat(recordingInfo.getString("state"), Matchers.equalTo("RUNNING"));

        Thread.sleep(4_000L); // wait some time to save a portion of the recording

        // Save a copy of the partial recording dump - schedule asynchronously
        executor.schedule(
                () -> {
                    given().log()
                            .all()
                            .when()
                            .basePath("/api/v4/targets/{targetId}/recordings/{remoteId}")
                            .pathParam("targetId", selfId)
                            .pathParam("remoteId", remoteId)
                            .contentType(HttpMimeType.PLAINTEXT.mime())
                            .body("SAVE")
                            .patch()
                            .then()
                            .log()
                            .all()
                            .and()
                            .assertThat()
                            .statusCode(200);
                },
                1,
                TimeUnit.SECONDS);

        // Wait for the archive request to conclude
        JsonObject archiveNotification = expectWebSocketNotification("ArchiveRecordingSuccess");
        String archivedRecordingFilename =
                archiveNotification.getJsonObject("message").getString("recording");

        // Check that the in-memory recording list hasn't changed
        Response listResponse3 =
                given().log()
                        .all()
                        .when()
                        .basePath("/api/v4/targets/{targetId}/recordings")
                        .pathParam("targetId", selfId)
                        .get()
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .response();
        listResp = new JsonArray(listResponse3.body().asString());

        MatcherAssert.assertThat(
                "list should have size 1 after recording save",
                listResp.size(),
                Matchers.equalTo(1));
        recordingInfo = listResp.getJsonObject(0);
        MatcherAssert.assertThat(
                recordingInfo.getString("name"), Matchers.equalTo(TEST_RECORDING_NAME));
        MatcherAssert.assertThat(recordingInfo.getString("state"), Matchers.equalTo("RUNNING"));

        // Verify saved recording created
        Response listArchivedResponse =
                given().log()
                        .all()
                        .when()
                        .basePath("/api/v4/recordings")
                        .get()
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .response();
        JsonArray archivedList = new JsonArray(listArchivedResponse.body().asString());

        MatcherAssert.assertThat(
                "list-saved should have size 1 after recording save",
                archivedList.size(),
                Matchers.equalTo(1));
        JsonObject archivedRecordingInfo = archivedList.getJsonObject(0);
        String archivedRecordingName = archivedRecordingInfo.getString("name");
        MatcherAssert.assertThat(
                archivedRecordingName,
                Matchers.matchesRegex(
                        SELFTEST_ALIAS + "_" + TEST_RECORDING_NAME + "_[\\d]{8}T[\\d]{6}Z.jfr"));
        // Verify the archived recording filename from notification matches the one in the list
        MatcherAssert.assertThat(
                "Archived recording filename from notification should match the one in the list",
                archivedRecordingFilename,
                Matchers.equalTo(archivedRecordingName));
        String savedDownloadUrl = archivedRecordingInfo.getString("downloadUrl");

        // Wait for the recording to complete (duration=20s)
        expectWebSocketNotification(
                "ActiveRecordingStopped", Duration.ofMinutes(1)); // wait for the dump to complete
        Thread.sleep(1000); // allow time for things to settle

        // Verify the in-memory recording list has not changed, except recording is now stopped
        Response listResponse4 =
                given().log()
                        .all()
                        .when()
                        .basePath("/api/v4/targets/{targetId}/recordings")
                        .pathParam("targetId", selfId)
                        .get()
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .response();
        listResp = new JsonArray(listResponse4.body().asString());
        MatcherAssert.assertThat(
                "list should have size 1 after wait period", listResp.size(), Matchers.equalTo(1));
        recordingInfo = listResp.getJsonObject(0);
        MatcherAssert.assertThat(
                recordingInfo.getString("name"), Matchers.equalTo(TEST_RECORDING_NAME));
        MatcherAssert.assertThat(recordingInfo.getString("state"), Matchers.equalTo("STOPPED"));
        MatcherAssert.assertThat(recordingInfo.getInteger("duration"), Matchers.equalTo(20_000));

        // Verify in-memory and saved recordings can be downloaded successfully
        String inMemoryDownloadUrl = recordingInfo.getString("downloadUrl");
        Path inMemoryDownloadPath = downloadRecording(inMemoryDownloadUrl, TEST_RECORDING_NAME);
        Path savedDownloadPath =
                downloadRecording(savedDownloadUrl, TEST_RECORDING_NAME + "_saved");

        MatcherAssert.assertThat(savedDownloadPath.toFile().length(), Matchers.greaterThan(0L));
        MatcherAssert.assertThat(
                inMemoryDownloadPath.toFile().length(),
                Matchers.greaterThan(savedDownloadPath.toFile().length()));

        List<RecordedEvent> inMemoryEvents = RecordingFile.readAllEvents(inMemoryDownloadPath);
        List<RecordedEvent> savedEvents = RecordingFile.readAllEvents(savedDownloadPath);

        MatcherAssert.assertThat(inMemoryEvents.size(), Matchers.greaterThan(savedEvents.size()));

        // Test report generation - schedule asynchronously
        String reportUrl = recordingInfo.getString("reportUrl");
        executor.schedule(
                () -> {
                    given().log()
                            .all()
                            .when()
                            .basePath("")
                            .get(reportUrl)
                            .then()
                            .log()
                            .all()
                            .and()
                            .assertThat()
                            .statusCode(
                                    Matchers.allOf(
                                            Matchers.greaterThanOrEqualTo(200),
                                            Matchers.lessThan(300)))
                            .header("Location", Matchers.notNullValue())
                            .body(Matchers.notNullValue());
                },
                1,
                TimeUnit.SECONDS);

        // Check that report generation concludes
        JsonObject notification = expectWebSocketNotification("ReportSuccess");
        MatcherAssert.assertThat(notification.getJsonObject("message"), Matchers.notNullValue());

        // Cleanup downloaded files
        Files.deleteIfExists(inMemoryDownloadPath);
        Files.deleteIfExists(savedDownloadPath);
    }

    private Path downloadRecording(String downloadUrl, String baseName) throws IOException {
        Response downloadResponse =
                given().log()
                        .all()
                        .when()
                        .basePath("")
                        .get(downloadUrl)
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .response();

        Path tempFile = Files.createTempFile(baseName, ".jfr");
        Files.write(tempFile, downloadResponse.asByteArray());
        return tempFile;
    }
}
