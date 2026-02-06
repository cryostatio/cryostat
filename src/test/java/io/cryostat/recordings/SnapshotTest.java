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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.startsWith;

import java.net.URI;
import java.time.Instant;
import java.util.regex.Pattern;

import io.cryostat.AbstractTransactionalTestBase;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class SnapshotTest extends AbstractTransactionalTestBase {

    static final String TEST_RECORDING_NAME = "snapshotTest";
    static final Pattern SNAPSHOT_NAME_PATTERN = Pattern.compile("^snapshot-[0-9]+$");

    @BeforeEach
    void setupSnapshotTest() {
        // Define self target before each test
        if (selfId < 1) {
            defineSelfCustomTarget();
        }
        cleanupRecordings();
    }

    @AfterEach
    void cleanupSnapshotTest() {
        cleanupRecordings();
    }

    private void cleanupRecordings() {
        if (selfId < 1) {
            return;
        }

        // Fetch all recordings and delete them
        Response response =
                given().basePath("/")
                        .when()
                        .get("/api/v4/targets/{id}/recordings", selfId)
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();

        var recordings = response.jsonPath().getList("$");
        for (Object obj : recordings) {
            if (obj instanceof java.util.Map) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> recording = (java.util.Map<String, Object>) obj;
                Object remoteIdObj = recording.get("remoteId");
                if (remoteIdObj != null) {
                    long remoteId = ((Number) remoteIdObj).longValue();
                    try {
                        given().basePath("/")
                                .when()
                                .delete(
                                        "/api/v4/targets/{id}/recordings/{remoteId}",
                                        selfId,
                                        remoteId)
                                .then()
                                .statusCode(204);
                        Thread.sleep(100);
                    } catch (Exception e) {
                        System.err.println(
                                "Failed to delete recording: " + remoteId + ", " + e.getMessage());
                    }
                }
            }
        }
    }

    @Test
    void testPostShouldHandleEmptySnapshot() {
        // Verify no recordings exist
        given().basePath("/")
                .when()
                .get("/api/v4/targets/{id}/recordings", selfId)
                .then()
                .statusCode(200)
                .body("size()", equalTo(0));

        // Create empty snapshot
        given().basePath("/")
                .when()
                .post("/api/v4/targets/{id}/snapshot", selfId)
                .then()
                .statusCode(202);

        // Verify still no recordings (empty snapshot doesn't create a recording)
        given().basePath("/")
                .when()
                .get("/api/v4/targets/{id}/recordings", selfId)
                .then()
                .statusCode(200)
                .body("size()", equalTo(0));
    }

    @Test
    void testPostSnapshotThrowsWithNonExistentTarget() {
        given().basePath("/")
                .when()
                .post("/api/v4/targets/notFound%2F9000/snapshot")
                .then()
                .statusCode(404);
    }

    @Test
    void testPostShouldCreateSnapshot() throws Exception {
        // Create a recording first
        given().basePath("/")
                .contentType(ContentType.URLENC)
                .formParam("recordingName", TEST_RECORDING_NAME)
                .formParam("duration", "5")
                .formParam("events", "template=ALL")
                .when()
                .post("/api/v4/targets/{id}/recordings", selfId)
                .then()
                .statusCode(201);

        // Wait for recording to collect some data
        Thread.sleep(5_000L);

        // Create snapshot
        var snapshotResponse =
                given().basePath("/")
                        .when()
                        .post("/api/v4/targets/{id}/snapshot", selfId)
                        .then()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .body("remoteId", greaterThan(0))
                        .body("state", equalTo("STOPPED"))
                        .body("startTime", lessThanOrEqualTo(Instant.now().toEpochMilli()))
                        .body("downloadUrl", startsWith("/api/v4/activedownload/"))
                        .extract()
                        .response();

        long snapshotRemoteId = snapshotResponse.jsonPath().getLong("remoteId");
        String expectedReportUrl =
                URI.create(String.format("/api/v4/targets/%d/reports/%d", selfId, snapshotRemoteId))
                        .getPath();

        given().basePath("/")
                .when()
                .get("/api/v4/targets/{id}/recordings", selfId)
                .then()
                .statusCode(200)
                .body(
                        "find { it.remoteId == " + snapshotRemoteId + " }.reportUrl",
                        equalTo(expectedReportUrl));
    }
}
