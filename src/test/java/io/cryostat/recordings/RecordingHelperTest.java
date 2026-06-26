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

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.resources.S3StorageResource;
import io.cryostat.targets.Target;

import io.quarkus.narayana.jta.QuarkusTransaction;
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

    @Test
    void shouldIncludeConfiguredDurationInActiveRecordingMetadata() throws Exception {
        long recordingId = 42L;
        long startTime = 123456789L;
        long duration = 98765L;
        ActiveRecording recording = new ActiveRecording();
        recording.id = recordingId;
        recording.startTime = startTime;
        recording.duration = duration;
        recording.metadata = new ActiveRecordings.Metadata(Map.of("label", "value"));
        Target target = new Target();
        target.jvmId = selfJvmId;
        target.connectUrl = URI.create(SELF_JMX_URL);
        recording.target = target;

        ActiveRecordings.Metadata metadata =
                recordingHelper.createActiveRecordingMetadata(recording);

        assertThat(metadata.labels().get("label"), is("value"));
        assertThat(metadata.labels().get("connectUrl"), is(SELF_JMX_URL));
        assertThat(metadata.labels().get("jvmId"), is(selfJvmId));
        assertThat(
                metadata.labels().get(RecordingHelper.START_TIME_LABEL),
                is(String.valueOf(startTime)));
        assertThat(
                metadata.labels().get(RecordingHelper.DURATION_LABEL),
                is(String.valueOf(duration)));
        assertThat(
                metadata.labels().get(RecordingHelper.ACTIVE_RECORDING_ID_LABEL),
                is(String.valueOf(recordingId)));
    }

    @Test
    void shouldUseElapsedDurationInActiveRecordingMetadataForContinuousRecording()
            throws Exception {
        long recordingId = 43L;
        long startTime = Instant.now().minusSeconds(5).toEpochMilli();
        ActiveRecording recording = new ActiveRecording();
        recording.id = recordingId;
        recording.startTime = startTime;
        recording.duration = 0L;
        recording.metadata = new ActiveRecordings.Metadata(Map.of("label", "value"));
        Target target = new Target();
        target.jvmId = selfJvmId;
        target.connectUrl = URI.create(SELF_JMX_URL);
        recording.target = target;

        long before = Instant.now().toEpochMilli();
        ActiveRecordings.Metadata metadata =
                recordingHelper.createActiveRecordingMetadata(recording);
        long after = Instant.now().toEpochMilli();
        long actualDuration = Long.parseLong(metadata.labels().get(RecordingHelper.DURATION_LABEL));

        assertThat(actualDuration, greaterThanOrEqualTo(before - startTime));
        assertThat(actualDuration, lessThanOrEqualTo(after - startTime));
    }

    @Test
    void shouldPersistActiveRecordingIdWhenUploadingArchivedRecording() throws Exception {
        defineSelfCustomTarget();
        long remoteId =
                startSelfRecording("recording-helper-active-id", Map.of("events", "template=ALL"))
                        .getLong("remoteId");
        ActiveRecording activeRecording =
                QuarkusTransaction.requiringNew()
                        .call(
                                () ->
                                        ActiveRecording.find("remoteId", remoteId)
                                                .firstResultOptional()
                                                .map(ActiveRecording.class::cast)
                                                .orElseThrow());

        Path recordingFile = Files.createTempFile("recording-helper-active-id", ".jfr");
        Files.write(recordingFile, new byte[] {9, 8, 7, 6});

        try {
            ActiveRecordings.Metadata metadata =
                    new ActiveRecordings.Metadata(
                            Map.of(
                                    "purpose",
                                    "persist-active-id",
                                    RecordingHelper.ACTIVE_RECORDING_ID_LABEL,
                                    String.valueOf(activeRecording.id)));

            recordingHelper.uploadArchivedRecording(
                    selfJvmId,
                    new TestFileUpload("persist-active-id.jfr", recordingFile),
                    metadata);

            ArchivedRecordingInfo info =
                    QuarkusTransaction.requiringNew()
                            .call(
                                    () ->
                                            ArchivedRecordingInfo.find(
                                                            "jvmId = ?1 and filename = ?2",
                                                            selfJvmId,
                                                            "persist-active-id.jfr")
                                                    .firstResultOptional()
                                                    .map(ArchivedRecordingInfo.class::cast)
                                                    .orElseThrow());

            assertThat(info.activeRecordingId, is(activeRecording.id));
        } finally {
            cleanupSelfRecording();
            Files.deleteIfExists(recordingFile);
        }
    }
}
