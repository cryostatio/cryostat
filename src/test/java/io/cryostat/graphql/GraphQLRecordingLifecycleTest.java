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
package io.cryostat.graphql;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@QuarkusTest
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
class GraphQLRecordingLifecycleTest extends AbstractGraphQLTestBase {

    @Test
    void testReplaceAlwaysOnStoppedRecording() throws Exception {
        try {
            // Start a Recording
            String recordingName = "test";
            JsonObject notificationRecording = createRecording(recordingName);
            assertThat(notificationRecording.getString("name"), equalTo(recordingName));
            assertThat(notificationRecording.getString("state"), equalTo("RUNNING"));

            // Stop the Recording
            notificationRecording = stopRecording();
            assertThat(notificationRecording.getString("name"), equalTo(recordingName));
            assertThat(notificationRecording.getString("state"), equalTo("STOPPED"));

            // Restart the recording with replace:ALWAYS
            notificationRecording = restartRecording(recordingName, "ALWAYS");
            assertThat(notificationRecording.getString("name"), equalTo(recordingName));
            assertThat(notificationRecording.getString("state"), equalTo("RUNNING"));
        } finally {
            // Delete the Recording
            deleteRecording();
        }
    }

    @Test
    void testReplaceNeverOnStoppedRecording() throws Exception {
        try {
            String recordingName = "test";
            // Start a Recording
            JsonObject notificationRecording = createRecording(recordingName);
            assertThat(notificationRecording.getString("name"), equalTo(recordingName));
            assertThat(notificationRecording.getString("state"), equalTo("RUNNING"));

            // Stop the Recording
            notificationRecording = stopRecording();
            assertThat(notificationRecording.getString("name"), equalTo(recordingName));
            assertThat(notificationRecording.getString("state"), equalTo("STOPPED"));

            // Restart the recording with replace:NEVER
            JsonObject error = restartRecordingWithError(recordingName, "NEVER");
            assertThat(
                    error.getString("message"),
                    containsString(
                            String.format(
                                    "Recording with name %s already exists. Try again with a"
                                            + " different name",
                                    recordingName)));
        } finally {
            // Delete the Recording
            deleteRecording();
        }
    }

    @Test
    void testReplaceStoppedOnStoppedRecording() throws Exception {
        try {
            // Start a Recording
            String recordingName = "test";
            JsonObject notificationRecording = createRecording(recordingName);
            assertThat(notificationRecording.getString("name"), equalTo(recordingName));
            assertThat(notificationRecording.getString("state"), equalTo("RUNNING"));

            // Stop the Recording
            notificationRecording = stopRecording();
            assertThat(notificationRecording.getString("name"), equalTo(recordingName));
            assertThat(notificationRecording.getString("state"), equalTo("STOPPED"));

            // Restart the recording with replace:STOPPED
            notificationRecording = restartRecording(recordingName, "STOPPED");
            assertThat(notificationRecording.getString("name"), equalTo(recordingName));
            assertThat(notificationRecording.getString("state"), equalTo("RUNNING"));
        } finally {
            // Delete the Recording
            deleteRecording();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"STOPPED", "NEVER"})
    void testReplaceStoppedOrNeverOnRunningRecording(String replace) throws Exception {
        try {
            String recordingName = "test";
            // Start a Recording
            JsonObject notificationRecording = createRecording(recordingName);
            assertThat(notificationRecording.getString("name"), equalTo(recordingName));
            assertThat(notificationRecording.getString("state"), equalTo("RUNNING"));

            // Restart the recording with the provided string values above
            JsonObject error = restartRecordingWithError(recordingName, replace);
            assertThat(
                    error.getString("message"),
                    containsString(
                            String.format(
                                    "Recording with name %s already exists. Try again with a"
                                            + " different name",
                                    recordingName)));
        } finally {
            // Delete the Recording
            deleteRecording();
        }
    }

    @Test
    void testReplaceAlwaysOnRunningRecording() throws Exception {
        try {
            // Start a Recording
            String recordingName = "test";
            JsonObject notificationRecording = createRecording(recordingName);
            assertThat(notificationRecording.getString("name"), equalTo(recordingName));
            assertThat(notificationRecording.getString("state"), equalTo("RUNNING"));

            // Restart the recording with replace:ALWAYS
            notificationRecording = restartRecording(recordingName, "ALWAYS");
            assertThat(notificationRecording.getString("name"), equalTo(recordingName));
            assertThat(notificationRecording.getString("state"), equalTo("RUNNING"));
        } finally {
            // Delete the Recording
            deleteRecording();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALWAYS", "STOPPED", "NEVER"})
    void testStartingNewRecordingWithAllReplaceValues(String replace) throws Exception {
        try {
            String recordingName = "test";
            JsonObject notificationRecording = restartRecording(recordingName, replace);
            assertThat(notificationRecording.getString("name"), equalTo(recordingName));
            assertThat(notificationRecording.getString("state"), equalTo("RUNNING"));
        } finally {
            // Delete the Recording
            deleteRecording();
        }
    }
}
