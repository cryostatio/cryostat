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

import io.cryostat.resources.AgentExternalRecordingApplicationResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import itest.resources.S3StorageITResource;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Integration test for external recording detection using the Cryostat agent. Tests that recordings
 * started externally (not via Cryostat API) are properly detected, labeled, and managed according
 * to external recording configuration.
 */
@QuarkusIntegrationTest
@QuarkusTestResource(
        value = AgentExternalRecordingApplicationResource.class,
        restrictToAnnotatedClass = true)
@QuarkusTestResource(value = S3StorageITResource.class, restrictToAnnotatedClass = true)
public class AgentExternalRecordingIT extends AgentTestBase {

    @Test
    void testExternalRecordingDetectionAndLifecycle() throws Exception {
        // Wait a bit for external recording detection to occur
        Thread.sleep(3000);

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
                "Should have exactly 1 recording (the external one)",
                recordings.size(),
                Matchers.equalTo(1));

        JsonObject recording = recordings.getJsonObject(0);

        MatcherAssert.assertThat(
                "Recording name should match",
                recording.getString("name"),
                Matchers.equalTo(AgentExternalRecordingApplicationResource.RECORDING_NAME));

        MatcherAssert.assertThat(
                "Recording should be running",
                recording.getString("state"),
                Matchers.equalTo("RUNNING"));

        MatcherAssert.assertThat(
                "Recording duration should be 90 seconds",
                recording.getInteger("duration"),
                Matchers.equalTo(90_000));

        // Verify autoanalyze label
        JsonObject metadata = recording.getJsonObject("metadata");
        MatcherAssert.assertThat("Metadata should not be null", metadata, Matchers.notNullValue());

        JsonArray labels = metadata.getJsonArray("labels");
        MatcherAssert.assertThat("Labels should not be null", labels, Matchers.notNullValue());

        boolean hasAutoanalyze = false;
        for (int i = 0; i < labels.size(); i++) {
            JsonObject label = labels.getJsonObject(i);
            if ("autoanalyze".equals(label.getString("key"))
                    && "true".equals(label.getString("value"))) {
                hasAutoanalyze = true;
                break;
            }
        }
        MatcherAssert.assertThat(
                "Should have autoanalyze label", hasAutoanalyze, Matchers.equalTo(true));

        expectWebSocketNotification("ActiveRecordingStopped", Duration.ofSeconds(120));
        Thread.sleep(2000);

        var stoppedResponse =
                given().log()
                        .all()
                        .when()
                        .get("/api/v4/targets/{targetId}/recordings", target.id())
                        .then()
                        .log()
                        .all()
                        .statusCode(200)
                        .extract()
                        .response();

        JsonArray stoppedRecordings = new JsonArray(stoppedResponse.body().asString());

        MatcherAssert.assertThat(
                "Should still have 1 recording in active list",
                stoppedRecordings.size(),
                Matchers.equalTo(1));

        JsonObject stoppedRecording = stoppedRecordings.getJsonObject(0);
        MatcherAssert.assertThat(
                "Recording should be stopped",
                stoppedRecording.getString("state"),
                Matchers.equalTo("STOPPED"));

        JsonObject archiveNotification =
                expectWebSocketNotification("ArchivedRecordingCreated", Duration.ofSeconds(10));

        JsonObject archiveMessage = archiveNotification.getJsonObject("message");
        JsonObject archivedRecording = archiveMessage.getJsonObject("recording");

        MatcherAssert.assertThat(
                "Archived recording name should contain external-test-recording",
                archivedRecording.getString("name"),
                Matchers.containsString("external-test-recording"));
    }
}
