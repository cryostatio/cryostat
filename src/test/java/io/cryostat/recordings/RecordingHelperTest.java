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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
class RecordingHelperTest extends AbstractTransactionalTestBase {

    @Inject RecordingHelper recordingHelper;

    @Test
    void shouldListArchivedRecordingsAcrossJvmIdsWhenSourceTargetIsNull() throws Exception {
        int firstTargetId = defineSelfCustomTarget();
        assertThat(firstTargetId, greaterThan(0));

        Path firstRecording = Files.createTempFile("recording-helper-first-target", ".jfr");
        Path secondRecording = Files.createTempFile("recording-helper-second-target", ".jfr");
        Files.write(firstRecording, new byte[] {1, 2, 3, 4});
        Files.write(secondRecording, new byte[] {5, 6, 7, 8});

        try {
            String secondJvmId = "other-jvm-id";
            ActiveRecordings.Metadata metadata =
                    new ActiveRecordings.Metadata(Map.of("purpose", "query-all-archives"));

            recordingHelper.uploadArchivedRecording(
                    selfJvmId, new TestFileUpload("first-target.jfr", firstRecording), metadata);
            recordingHelper.uploadArchivedRecording(
                    secondJvmId,
                    new TestFileUpload("second-target.jfr", secondRecording),
                    metadata);

            List<ArchivedRecordings.ArchivedRecording> archivedRecordings =
                    recordingHelper.listArchivedRecordings((String) null);

            assertThat(archivedRecordings, hasSize(greaterThanOrEqualTo(2)));
            assertThat(
                    archivedRecordings.stream()
                            .map(ArchivedRecordings.ArchivedRecording::jvmId)
                            .toList(),
                    hasItems(selfJvmId, secondJvmId));

            ArchivedRecordings.ArchivedRecording secondTargetRecording =
                    archivedRecordings.stream()
                            .filter(r -> "second-target.jfr".equals(r.name()))
                            .findFirst()
                            .orElseThrow();

            assertThat(secondTargetRecording.jvmId(), is(secondJvmId));
        } finally {
            Files.deleteIfExists(firstRecording);
            Files.deleteIfExists(secondRecording);
        }
    }
}
