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

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class TargetRecordingPatchTest extends AbstractTransactionalTestBase {

    private final ExecutorService worker = ForkJoinPool.commonPool();
    private static final int REQUEST_TIMEOUT_SECONDS = 30;

    final String TEST_RECORDING_NAME = "patchRecording";

    @BeforeEach
    void setupTargetRecordingPatchTest() throws Exception {
        getSelfReferenceTargetId();
        cleanupSelfActiveAndArchivedRecordings();
    }

    @AfterEach
    void cleanupTargetRecordingPatchTest() throws Exception {
        cleanupSelfActiveAndArchivedRecordings();
    }

    private long getSelfReferenceTargetId() {
        if (selfId < 1) {
            defineSelfCustomTarget();
        }
        return selfId;
    }

    @Test
    void testSaveEmptyRecordingDoesNotArchiveRecordingFile() throws Exception {
        long targetId = getSelfReferenceTargetId();

        // Set recording options to create an empty recording (toDisk=false, maxSize=0)
        given().log()
                .all()
                .contentType(ContentType.URLENC)
                .formParam("toDisk", "false")
                .formParam("maxSize", "0")
                .when()
                .patch("/api/v4/targets/{targetId}/recordingOptions", targetId)
                .then()
                .log()
                .all()
                .statusCode(200);

        // Create an empty recording with 5s duration
        Response postResponse =
                given().log()
                        .all()
                        .contentType(ContentType.URLENC)
                        .formParam("recordingName", TEST_RECORDING_NAME)
                        .formParam("duration", "5")
                        .formParam("events", "template=ALL")
                        .when()
                        .post("/api/v4/targets/{targetId}/recordings", targetId)
                        .then()
                        .log()
                        .all()
                        .statusCode(201)
                        .extract()
                        .response();

        JsonObject postResult = new JsonObject(postResponse.body().asString());
        long remoteId = postResult.getLong("remoteId");

        // Attempt to save the recording to archive
        Response saveResponse =
                given().log()
                        .all()
                        .contentType("text/plain")
                        .body("SAVE")
                        .when()
                        .patch(
                                "/api/v4/targets/{targetId}/recordings/{remoteId}",
                                targetId,
                                remoteId)
                        .then()
                        .log()
                        .all()
                        .statusCode(200)
                        .extract()
                        .response();

        MatcherAssert.assertThat(saveResponse.body().asString(), Matchers.any(String.class));

        // Expect ArchiveRecordingFailed notification
        CountDownLatch latch = new CountDownLatch(1);
        worker.submit(
                () -> {
                    try {
                        return expectWebSocketNotification(
                                "ArchiveRecordingFailed", Duration.ofSeconds(15));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        latch.countDown();
                    }
                });

        latch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Assert that no recording was archived
        Response listResponse =
                given().log()
                        .all()
                        .when()
                        .get("/api/v4/recordings")
                        .then()
                        .log()
                        .all()
                        .statusCode(200)
                        .extract()
                        .response();

        JsonArray listResp = new JsonArray(listResponse.body().asString());
        Assertions.assertTrue(listResp.isEmpty());
    }
}
