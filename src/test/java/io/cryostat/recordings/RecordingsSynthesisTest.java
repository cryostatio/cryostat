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
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

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
public class RecordingsSynthesisTest extends AbstractTransactionalTestBase {

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
    // Invalid timestamp range
    // -------------------------------------------------------------------------

    @Test
    void testFromTimestampEqualToToTimestampReturns400() {
        defineSelfCustomTarget();
        given().basePath("/")
                .log()
                .all()
                .queryParam("fromTimestamp", 1000L)
                .queryParam("toTimestamp", 1000L)
                .when()
                .post("/api/beta/recording_synthesis/{jvmId}", selfJvmId)
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void testFromTimestampAfterToTimestampReturns400() {
        defineSelfCustomTarget();
        given().basePath("/")
                .log()
                .all()
                .queryParam("fromTimestamp", 2000L)
                .queryParam("toTimestamp", 1000L)
                .when()
                .post("/api/beta/recording_synthesis/{jvmId}", selfJvmId)
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(400);
    }

    // -------------------------------------------------------------------------
    // No candidates at all → 400
    // -------------------------------------------------------------------------

    @Test
    void testNoArchivesForJvmIdReturns400() {
        defineSelfCustomTarget();
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
                .statusCode(400);
    }

    @Test
    void testRecordingEndingBeforeRangeStartIsExcludedReturns400() throws Exception {
        defineSelfCustomTarget();

        // Recording ends at 600s — entirely before [1000s, 2000s)
        Path file = Files.createTempFile("synthesis-before-range", ".jfr");
        Files.write(file, new byte[] {1, 2, 3, 4});
        try {
            recordingHelper.uploadArchivedRecording(
                    selfJvmId,
                    new TestFileUpload("before-range.jfr", file),
                    new ActiveRecordings.Metadata(
                            Map.of(
                                    RecordingHelper.START_TIME_LABEL, String.valueOf(500_000L),
                                    RecordingHelper.DURATION_LABEL, String.valueOf(100_000L))));
        } finally {
            Files.deleteIfExists(file);
        }

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
                .statusCode(400);
    }

    @Test
    void testRecordingStartingAfterRangeEndIsExcludedReturns400() throws Exception {
        defineSelfCustomTarget();

        // Recording starts at 2500s — entirely after [1000s, 2000s)
        Path file = Files.createTempFile("synthesis-after-range", ".jfr");
        Files.write(file, new byte[] {1, 2, 3, 4});
        try {
            recordingHelper.uploadArchivedRecording(
                    selfJvmId,
                    new TestFileUpload("after-range.jfr", file),
                    new ActiveRecordings.Metadata(
                            Map.of(
                                    RecordingHelper.START_TIME_LABEL, String.valueOf(2_500_000L),
                                    RecordingHelper.DURATION_LABEL, String.valueOf(500_000L))));
        } finally {
            Files.deleteIfExists(file);
        }

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
                .statusCode(400);
    }

    // -------------------------------------------------------------------------
    // Complete candidate fast-path — immediate 200
    // -------------------------------------------------------------------------

    @Test
    void testSingleCompleteCandidateReturns200() throws Exception {
        defineSelfCustomTarget();

        // [900s, 2100s) fully spans [1000s, 2000s)
        Path file = Files.createTempFile("synthesis-complete", ".jfr");
        Files.write(file, new byte[] {1, 2, 3, 4});
        try {
            recordingHelper.uploadArchivedRecording(
                    selfJvmId,
                    new TestFileUpload("complete.jfr", file),
                    new ActiveRecordings.Metadata(
                            Map.of(
                                    RecordingHelper.START_TIME_LABEL, String.valueOf(900_000L),
                                    RecordingHelper.DURATION_LABEL, String.valueOf(1_200_000L))));
        } finally {
            Files.deleteIfExists(file);
        }

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
                .body("name", is("complete.jfr"))
                .body("jvmId", is(selfJvmId));
    }

    @Test
    void testPreviouslySynthesisedCompleteCandidateReturns200Idempotent() throws Exception {
        defineSelfCustomTarget();

        // A prior synthetic recording that fully covers [1000s, 2000s)
        Path file = Files.createTempFile("synthesis-synthetic-complete", ".jfr");
        Files.write(file, new byte[] {1, 2, 3, 4});
        try {
            recordingHelper.uploadArchivedRecording(
                    selfJvmId,
                    new TestFileUpload("synthetic-complete.jfr", file),
                    new ActiveRecordings.Metadata(
                            Map.of(
                                    RecordingHelper.START_TIME_LABEL,
                                    String.valueOf(900_000L),
                                    RecordingHelper.DURATION_LABEL,
                                    String.valueOf(1_200_000L),
                                    "synthetic",
                                    "true")));
        } finally {
            Files.deleteIfExists(file);
        }

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
                .body("name", is("synthetic-complete.jfr"))
                .body("metadata.labels.find { it.key == 'synthetic' }.value", is("true"))
                .body("jvmId", is(selfJvmId));
    }

    @Test
    void testDensestCompleteCandidateSelectedWhenMultipleExist() throws Exception {
        defineSelfCustomTarget();

        // Two complete candidates covering [1000s, 2000s):
        //   sparse.jfr:  900s start, 1200s duration, 4 bytes  → density ≈ 0.0033 bytes/ms
        //   dense.jfr:   950s start, 1100s duration, 100 bytes → density ≈ 0.091 bytes/ms
        Path sparse = Files.createTempFile("synthesis-sparse", ".jfr");
        Path dense = Files.createTempFile("synthesis-dense", ".jfr");
        Files.write(sparse, new byte[] {1, 2, 3, 4});
        byte[] denseBytes = new byte[100];
        for (int i = 0; i < 100; i++) denseBytes[i] = (byte) i;
        Files.write(dense, denseBytes);
        try {
            recordingHelper.uploadArchivedRecording(
                    selfJvmId,
                    new TestFileUpload("sparse.jfr", sparse),
                    new ActiveRecordings.Metadata(
                            Map.of(
                                    RecordingHelper.START_TIME_LABEL, String.valueOf(900_000L),
                                    RecordingHelper.DURATION_LABEL, String.valueOf(1_200_000L))));
            recordingHelper.uploadArchivedRecording(
                    selfJvmId,
                    new TestFileUpload("dense.jfr", dense),
                    new ActiveRecordings.Metadata(
                            Map.of(
                                    RecordingHelper.START_TIME_LABEL, String.valueOf(950_000L),
                                    RecordingHelper.DURATION_LABEL, String.valueOf(1_100_000L))));
        } finally {
            Files.deleteIfExists(sparse);
            Files.deleteIfExists(dense);
        }

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
                .body("name", is("dense.jfr"));
    }

    // -------------------------------------------------------------------------
    // Single incomplete candidate fast-path — immediate 200
    // -------------------------------------------------------------------------

    @Test
    void testSingleIncompleteCandidateReturns200Directly() throws Exception {
        defineSelfCustomTarget();

        // Overlaps [1000s, 2000s) but doesn't fully span it: starts after fromTimestamp
        Path file = Files.createTempFile("synthesis-incomplete", ".jfr");
        Files.write(file, new byte[] {1, 2, 3, 4});
        try {
            recordingHelper.uploadArchivedRecording(
                    selfJvmId,
                    new TestFileUpload("incomplete.jfr", file),
                    new ActiveRecordings.Metadata(
                            Map.of(
                                    RecordingHelper.START_TIME_LABEL, String.valueOf(1_200_000L),
                                    RecordingHelper.DURATION_LABEL, String.valueOf(900_000L))));
        } finally {
            Files.deleteIfExists(file);
        }

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
                .body("name", is("incomplete.jfr"))
                .body("jvmId", is(selfJvmId));
    }

    // -------------------------------------------------------------------------
    // Multiple incomplete candidates — 202 async job dispatched
    // Gapless coverage is NOT required; any two overlapping recordings qualify
    // -------------------------------------------------------------------------

    @Test
    void testMultipleIncompleteCandidatesReturns202() throws Exception {
        defineSelfCustomTarget();

        // Two overlapping recordings, neither fully covers [1000s, 2000s):
        // [800s, 1300s) and [1500s, 2100s) — there is a gap, but that is acceptable
        Path file1 = Files.createTempFile("synthesis-multi-a", ".jfr");
        Path file2 = Files.createTempFile("synthesis-multi-b", ".jfr");
        Files.write(file1, new byte[] {1, 2, 3, 4});
        Files.write(file2, new byte[] {5, 6, 7, 8});
        try {
            recordingHelper.uploadArchivedRecording(
                    selfJvmId,
                    new TestFileUpload("multi-a.jfr", file1),
                    new ActiveRecordings.Metadata(
                            Map.of(
                                    RecordingHelper.START_TIME_LABEL, String.valueOf(800_000L),
                                    RecordingHelper.DURATION_LABEL, String.valueOf(500_000L))));
            recordingHelper.uploadArchivedRecording(
                    selfJvmId,
                    new TestFileUpload("multi-b.jfr", file2),
                    new ActiveRecordings.Metadata(
                            Map.of(
                                    RecordingHelper.START_TIME_LABEL, String.valueOf(1_500_000L),
                                    RecordingHelper.DURATION_LABEL, String.valueOf(600_000L))));
        } finally {
            Files.deleteIfExists(file1);
            Files.deleteIfExists(file2);
        }

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
                .statusCode(202)
                .contentType(ContentType.TEXT)
                .body(notNullValue());

        webSocketClient.expectNotification("RecordingSynthesisComplete", Duration.ofSeconds(30));
    }

    @Test
    void testCompleteCandidateWinsOverMultipleIncompleteCandidates() throws Exception {
        defineSelfCustomTarget();

        // One complete candidate alongside two incomplete ones —
        // should return the complete candidate as 200, not dispatch a 202 job
        Path complete = Files.createTempFile("synthesis-wins-complete", ".jfr");
        Path inc1 = Files.createTempFile("synthesis-wins-inc1", ".jfr");
        Path inc2 = Files.createTempFile("synthesis-wins-inc2", ".jfr");
        byte[] largeBytes = new byte[200];
        Files.write(complete, largeBytes);
        Files.write(inc1, new byte[] {1, 2});
        Files.write(inc2, new byte[] {3, 4});
        try {
            recordingHelper.uploadArchivedRecording(
                    selfJvmId,
                    new TestFileUpload("wins-complete.jfr", complete),
                    new ActiveRecordings.Metadata(
                            Map.of(
                                    RecordingHelper.START_TIME_LABEL, String.valueOf(900_000L),
                                    RecordingHelper.DURATION_LABEL, String.valueOf(1_200_000L))));
            recordingHelper.uploadArchivedRecording(
                    selfJvmId,
                    new TestFileUpload("wins-inc1.jfr", inc1),
                    new ActiveRecordings.Metadata(
                            Map.of(
                                    RecordingHelper.START_TIME_LABEL, String.valueOf(800_000L),
                                    RecordingHelper.DURATION_LABEL, String.valueOf(500_000L))));
            recordingHelper.uploadArchivedRecording(
                    selfJvmId,
                    new TestFileUpload("wins-inc2.jfr", inc2),
                    new ActiveRecordings.Metadata(
                            Map.of(
                                    RecordingHelper.START_TIME_LABEL, String.valueOf(1_500_000L),
                                    RecordingHelper.DURATION_LABEL, String.valueOf(600_000L))));
        } finally {
            Files.deleteIfExists(complete);
            Files.deleteIfExists(inc1);
            Files.deleteIfExists(inc2);
        }

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
                .body("name", is("wins-complete.jfr"));
    }
}
