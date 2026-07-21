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
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class RecordingsSynthesisWorkflowTest extends AbstractTransactionalTestBase {

    @Inject RecordingHelper recordingHelper;

    @AfterEach
    void deleteAllArchivedRecordingsForJvmId() throws IOException {
        if (selfJvmId == null || selfJvmId.isBlank()) {
            return;
        }
        for (var obj : recordingHelper.listArchivedRecordingObjects(selfJvmId)) {
            String[] parts = obj.key().strip().split("/");
            if (parts.length == 2) {
                try {
                    recordingHelper.deleteArchivedRecording(parts[0], parts[1]);
                } catch (Exception ignored) {
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Full async workflow: 202 → RecordingSynthesisComplete notification
    // -------------------------------------------------------------------------

    @Test
    public void testSynthesisJobCompletesAndEmitsNotification() throws Exception {
        defineSelfCustomTarget();

        Path file1 = Files.createTempFile("synthesis-workflow-a", ".jfr");
        Path file2 = Files.createTempFile("synthesis-workflow-b", ".jfr");
        // Write minimal valid-ish JFR-shaped bytes (content doesn't matter for concat test)
        Files.write(file1, new byte[] {1, 2, 3, 4});
        Files.write(file2, new byte[] {5, 6, 7, 8});
        try {
            // [800s, 1300s) and [1200s, 2100s) together span [1000s, 2000s) without gap
            recordingHelper.uploadArchivedRecording(
                    selfJvmId,
                    new TestFileUpload("workflow-a.jfr", file1),
                    new ActiveRecordings.Metadata(
                            Map.of(
                                    RecordingHelper.START_TIME_LABEL, String.valueOf(800_000L),
                                    RecordingHelper.DURATION_LABEL, String.valueOf(500_000L))));
            recordingHelper.uploadArchivedRecording(
                    selfJvmId,
                    new TestFileUpload("workflow-b.jfr", file2),
                    new ActiveRecordings.Metadata(
                            Map.of(
                                    RecordingHelper.START_TIME_LABEL, String.valueOf(1_200_000L),
                                    RecordingHelper.DURATION_LABEL, String.valueOf(900_000L))));
        } finally {
            Files.deleteIfExists(file1);
            Files.deleteIfExists(file2);
        }

        final String[] jobId = new String[1];

        Executors.newSingleThreadScheduledExecutor()
                .schedule(
                        () -> {
                            var body =
                                    given().basePath("/")
                                            .log()
                                            .all()
                                            .queryParam("fromTimestamp", 1000L)
                                            .queryParam("toTimestamp", 2000L)
                                            .when()
                                            .post(
                                                    "/api/beta/recording_synthesis/{jvmId}",
                                                    selfJvmId)
                                            .then()
                                            .log()
                                            .all()
                                            .assertThat()
                                            .statusCode(202)
                                            .contentType(ContentType.TEXT)
                                            .extract()
                                            .body()
                                            .asString();
                            jobId[0] = body.strip();
                        },
                        1,
                        TimeUnit.SECONDS);

        var notification =
                webSocketClient.expectNotification(
                        "RecordingSynthesisComplete",
                        json ->
                                Objects.equals(
                                        json.getJsonObject("message").getString("jobId"),
                                        jobId[0]));

        var message = notification.getJsonObject("message");
        assertThat(message.containsKey("recording"), is(true));
        var recording = message.getJsonObject("recording");
        assertThat(recording.getString("jvmId"), is(selfJvmId));
        var labels = recording.getJsonObject("metadata").getJsonArray("labels");
        String syntheticValue =
                labels.stream()
                        .map(o -> (io.vertx.core.json.JsonObject) o)
                        .filter(e -> "synthetic".equals(e.getString("key")))
                        .map(e -> e.getString("value"))
                        .findFirst()
                        .orElse(null);
        assertThat(syntheticValue, is("true"));
    }

    // -------------------------------------------------------------------------
    // Idempotency at consumer level: a race-winning fully-covering recording
    // emits RecordingSynthesisComplete with the existing recording, no re-upload
    // -------------------------------------------------------------------------

    @Test
    public void
            testSynthesisJobIdempotentWhenFullyCoveringRecordingAlreadyExistsBeforeConsumerRuns()
                    throws Exception {
        defineSelfCustomTarget();

        Path file1 = Files.createTempFile("synthesis-idem-a", ".jfr");
        Path file2 = Files.createTempFile("synthesis-idem-b", ".jfr");
        Path fileCover = Files.createTempFile("synthesis-idem-cover", ".jfr");
        Files.write(file1, new byte[] {1, 2});
        Files.write(file2, new byte[] {3, 4});
        Files.write(fileCover, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        try {
            // Two partial recordings to trigger the 202 path
            recordingHelper.uploadArchivedRecording(
                    selfJvmId,
                    new TestFileUpload("idem-a.jfr", file1),
                    new ActiveRecordings.Metadata(
                            Map.of(
                                    RecordingHelper.START_TIME_LABEL, String.valueOf(800_000L),
                                    RecordingHelper.DURATION_LABEL, String.valueOf(500_000L))));
            recordingHelper.uploadArchivedRecording(
                    selfJvmId,
                    new TestFileUpload("idem-b.jfr", file2),
                    new ActiveRecordings.Metadata(
                            Map.of(
                                    RecordingHelper.START_TIME_LABEL, String.valueOf(1_200_000L),
                                    RecordingHelper.DURATION_LABEL, String.valueOf(900_000L))));
        } finally {
            Files.deleteIfExists(file1);
            Files.deleteIfExists(file2);
        }

        final String[] jobId = new String[1];

        // Issue first synthesis request to get a 202 job
        Executors.newSingleThreadScheduledExecutor()
                .schedule(
                        () -> {
                            var body =
                                    given().basePath("/")
                                            .queryParam("fromTimestamp", 1000L)
                                            .queryParam("toTimestamp", 2000L)
                                            .when()
                                            .post(
                                                    "/api/beta/recording_synthesis/{jvmId}",
                                                    selfJvmId)
                                            .then()
                                            .assertThat()
                                            .statusCode(202)
                                            .extract()
                                            .body()
                                            .asString();
                            jobId[0] = body.strip();
                        },
                        1,
                        TimeUnit.SECONDS);

        // Wait for the synthesis to complete (success notification)
        var notification =
                webSocketClient.expectNotification(
                        "RecordingSynthesisComplete",
                        json ->
                                Objects.equals(
                                        json.getJsonObject("message").getString("jobId"),
                                        jobId[0]));

        // Extract the synthesised recording name
        var syntheticName =
                notification.getJsonObject("message").getJsonObject("recording").getString("name");

        // Issue a second request with the same range. Must be served as an immediate 200
        // because the synthetic recording now fully covers the range
        given().basePath("/")
                .log()
                .all()
                .queryParam("fromTimestamp", 1000L)
                .queryParam("toTimestamp", 2000L)
                .when()
                .post("/api/beta/recording_synthesis/{jvmId}", selfJvmId)
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("name", is(syntheticName))
                .body("jvmId", is(selfJvmId));

        try {
            Files.deleteIfExists(fileCover);
        } catch (Exception ignored) {
        }
    }
}
