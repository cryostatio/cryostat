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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.cryostat.AbstractTransactionalTestBase;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.quarkus.test.junit.QuarkusTest;
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
public class TargetRecordingPatchTest extends AbstractTransactionalTestBase {

    private final ExecutorService worker = ForkJoinPool.commonPool();

    static final int REQUEST_TIMEOUT_SECONDS = 30;
    static final String TEST_RECORDING_NAME = "patchRecording";

    @BeforeEach
    void setupTargetRecordingPatchTest()
            throws InterruptedException,
                    TimeoutException,
                    ExecutionException,
                    JsonProcessingException {
        cleanupSelfActiveAndArchivedRecordings();
    }

    @AfterEach
    void cleanupTargetRecordingPatchTest()
            throws InterruptedException,
                    TimeoutException,
                    ExecutionException,
                    JsonProcessingException {
        cleanupSelfActiveAndArchivedRecordings();
    }

    @Test
    void testSaveEmptyRecordingDoesNotArchiveRecordingFile() throws Exception {
        long remoteId = -1;

        // Set recording options to create an empty recording
        given().contentType("application/x-www-form-urlencoded")
                .formParam("toDisk", "false")
                .formParam("maxSize", "0")
                .when()
                .patch("/api/v4/targets/{targetId}/recordingOptions", getSelfReferenceTargetId())
                .then()
                .statusCode(200);

        // Create an empty recording
        Response postResponse =
                given().contentType("application/x-www-form-urlencoded")
                        .formParam("recordingName", TEST_RECORDING_NAME)
                        .formParam("duration", "5")
                        .formParam("events", "template=ALL")
                        .when()
                        .post("/api/v4/targets/{targetId}/recordings", getSelfReferenceTargetId())
                        .then()
                        .statusCode(201)
                        .extract()
                        .response();
        JsonObject postResult = new JsonObject(postResponse.body().asString());
        remoteId = postResult.getLong("remoteId");

        // Attempt to save the recording to archive
        Response saveResponse =
                given().contentType("text/plain")
                        .body("SAVE")
                        .when()
                        .patch(
                                "/api/v4/targets/{targetId}/recordings/{remoteId}",
                                getSelfReferenceTargetId(),
                                remoteId)
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();
        MatcherAssert.assertThat(saveResponse.body().asString(), Matchers.any(String.class));

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
                given().when()
                        .get("/api/v4/recordings")
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();
        JsonArray listResp = new JsonArray(listResponse.body().asString());
        Assertions.assertTrue(listResp.isEmpty());
    }
}
