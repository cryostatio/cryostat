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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class AgentPushMaxFilesTest extends AbstractTransactionalTestBase {

    static final String TEST_JVM_ID = "test-agent-jvmid";

    @Inject RecordingHelper recordingHelper;

    @BeforeEach
    void setup() {
        cleanupAgentRecordings();
    }

    @AfterEach
    void cleanup() {
        cleanupAgentRecordings();
    }

    private void cleanupAgentRecordings() {
        recordingHelper
                .listArchivedRecordingObjects(TEST_JVM_ID)
                .forEach(
                        obj -> {
                            String filename = obj.key().strip().split("/")[1];
                            try {
                                recordingHelper.deleteArchivedRecording(TEST_JVM_ID, filename);
                            } catch (Exception e) {
                                // ignore cleanup errors
                            }
                        });
    }

    @Test
    void testAgentPushWithMaxFilesOnlyPrunesScheduledPushes() throws Exception {
        // Seed one non-pushed recording (no pushType label) for the same target
        Path nonPushedFile = Files.createTempFile("non-pushed", ".jfr");
        Files.write(nonPushedFile, new byte[] {1, 2, 3, 4});
        recordingHelper.uploadArchivedRecording(
                TEST_JVM_ID,
                new TestFileUpload("non-pushed.jfr", nonPushedFile),
                new Metadata(Map.of("jvmId", TEST_JVM_ID)));

        // Seed one scheduled-push recording (with pushType=SCHEDULED label)
        Path pushedFile1 = Files.createTempFile("pushed-1", ".jfr");
        Files.write(pushedFile1, new byte[] {5, 6, 7, 8});
        recordingHelper.uploadArchivedRecording(
                TEST_JVM_ID,
                new TestFileUpload("pushed-1.jfr", pushedFile1),
                new Metadata(Map.of("jvmId", TEST_JVM_ID, "pushType", "SCHEDULED")));

        // Verify both are present
        var before = recordingHelper.listArchivedRecordings(TEST_JVM_ID);
        assertThat(
                before.stream().map(ArchivedRecordings.ArchivedRecording::name).toList(),
                hasItem("non-pushed.jfr"));
        assertThat(
                before.stream().map(ArchivedRecordings.ArchivedRecording::name).toList(),
                hasItem("pushed-1.jfr"));

        // Push a second scheduled recording with maxFiles=1; only pushed-1 should be pruned
        Path pushedFile2 = Files.createTempFile("pushed-2", ".jfr");
        Files.write(pushedFile2, new byte[] {9, 10, 11, 12});

        given().log()
                .all()
                .when()
                .contentType(ContentType.MULTIPART)
                .pathParam("jvmId", TEST_JVM_ID)
                .multiPart("recording", pushedFile2.toFile(), "application/octet-stream")
                .multiPart("labels", "{\"pushType\":\"SCHEDULED\"}", "application/json")
                .multiPart("maxFiles", "1")
                .post("/api/beta/recordings/{jvmId}")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(204);

        List<String> names =
                recordingHelper.listArchivedRecordings(TEST_JVM_ID).stream()
                        .map(ArchivedRecordings.ArchivedRecording::name)
                        .toList();

        // Non-pushed recording must be untouched
        assertThat(names, hasItem("non-pushed.jfr"));
        // Older pushed recording must have been pruned
        assertThat(names, not(hasItem("pushed-1.jfr")));
        // The newly uploaded pushed recording must be present
        assertThat(names, hasItem("pushed-2.jfr"));
        // Total: non-pushed + newest pushed = 2
        assertThat(names.size(), equalTo(2));
    }

    @Test
    void testAgentPushWithoutMaxFilesDoesNotPrune() throws Exception {
        // Seed two scheduled-push recordings
        Path pushedFile1 = Files.createTempFile("pushed-a", ".jfr");
        Files.write(pushedFile1, new byte[] {1, 2, 3, 4});
        recordingHelper.uploadArchivedRecording(
                TEST_JVM_ID,
                new TestFileUpload("pushed-a.jfr", pushedFile1),
                new Metadata(Map.of("jvmId", TEST_JVM_ID, "pushType", "SCHEDULED")));

        Path pushedFile2 = Files.createTempFile("pushed-b", ".jfr");
        Files.write(pushedFile2, new byte[] {5, 6, 7, 8});
        recordingHelper.uploadArchivedRecording(
                TEST_JVM_ID,
                new TestFileUpload("pushed-b.jfr", pushedFile2),
                new Metadata(Map.of("jvmId", TEST_JVM_ID, "pushType", "SCHEDULED")));

        // Push a third recording without maxFiles; nothing should be pruned
        Path pushedFile3 = Files.createTempFile("pushed-c", ".jfr");
        Files.write(pushedFile3, new byte[] {9, 10, 11, 12});

        given().log()
                .all()
                .when()
                .contentType(ContentType.MULTIPART)
                .pathParam("jvmId", TEST_JVM_ID)
                .multiPart("recording", pushedFile3.toFile(), "application/octet-stream")
                .multiPart("labels", "{\"pushType\":\"SCHEDULED\"}", "application/json")
                .post("/api/beta/recordings/{jvmId}")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(204);

        List<String> names =
                recordingHelper.listArchivedRecordings(TEST_JVM_ID).stream()
                        .map(ArchivedRecordings.ArchivedRecording::name)
                        .toList();

        assertThat(names, hasItem("pushed-a.jfr"));
        assertThat(names, hasItem("pushed-b.jfr"));
        assertThat(names, hasItem("pushed-c.jfr"));
        assertThat(names.size(), equalTo(3));
    }
}
