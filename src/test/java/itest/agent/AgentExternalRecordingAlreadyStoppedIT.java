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
package itest.agent;

import static io.restassured.RestAssured.given;

import java.time.Duration;

import io.cryostat.resources.AgentExternalRecordingAlreadyStoppedApplicationResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import itest.resources.S3StorageITResource;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Test for the case where an external recording is already in STOPPED state when Cryostat first
 * discovers the target. Verifies that the recording is immediately archived.
 */
@QuarkusIntegrationTest
@QuarkusTestResource(
        value = AgentExternalRecordingAlreadyStoppedApplicationResource.class,
        restrictToAnnotatedClass = true)
@QuarkusTestResource(value = S3StorageITResource.class, restrictToAnnotatedClass = true)
public class AgentExternalRecordingAlreadyStoppedIT extends AgentTestBase {

    @Test
    void testAlreadyStoppedExternalRecordingIsArchivedImmediately() throws Exception {
        Thread.sleep(
                Duration.ofSeconds(
                                AgentExternalRecordingAlreadyStoppedApplicationResource
                                                .RECORDING_DURATION_SECONDS
                                        + 5)
                        .toMillis());

        var response =
                given().log()
                        .all()
                        .pathParams("targetId", target.id())
                        .when()
                        .get("/api/v4/targets/{targetId}/recordings")
                        .then()
                        .log()
                        .all()
                        .statusCode(200)
                        .extract()
                        .response();

        JsonArray recordings = new JsonArray(response.body().asString());

        MatcherAssert.assertThat(
                "Should have exactly 1 recording (the already-stopped external one)",
                recordings.size(),
                Matchers.equalTo(1));

        JsonObject recording = recordings.getJsonObject(0);

        MatcherAssert.assertThat(
                "Recording name should match",
                recording.getString("name"),
                Matchers.equalTo(
                        AgentExternalRecordingAlreadyStoppedApplicationResource.RECORDING_NAME));

        MatcherAssert.assertThat(
                "Recording should already be stopped when discovered",
                recording.getString("state"),
                Matchers.equalTo("STOPPED"));

        JsonObject archiveNotification =
                webSocketClient.expectNotification(
                        "ArchivedRecordingCreated", Duration.ofSeconds(30));

        JsonObject archiveMessage = archiveNotification.getJsonObject("message");
        JsonObject archivedRecording = archiveMessage.getJsonObject("recording");

        MatcherAssert.assertThat(
                "Archived recording name should contain the already-stopped recording name",
                archivedRecording.getString("name"),
                Matchers.containsString(
                        AgentExternalRecordingAlreadyStoppedApplicationResource.RECORDING_NAME));
    }
}
