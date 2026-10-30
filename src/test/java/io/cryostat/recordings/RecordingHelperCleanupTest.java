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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.resources.S3StorageResource;
import io.cryostat.targets.TargetConnectionManager;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

@QuarkusTest
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
@TestProfile(RecordingHelperCleanupTest.BucketModeProfile.class)
public class RecordingHelperCleanupTest extends AbstractTransactionalTestBase {

    @Inject TargetConnectionManager connectionManager;
    @InjectSpy RemoteRecordingInputStreamFactory remoteRecordingStreamFactory;
    @InjectSpy BucketedArchivedRecordingMetadataService service;
    @InjectSpy S3Client storage;
    @Inject RecordingHelper recordingHelper;

    public static class BucketModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("storage.metadata.storage-mode", "BUCKET");
        }
    }

    @Test
    public void shouldCleanupSynthesizedUploadWhenMetadataFails() throws Exception {
        defineSelfCustomTarget();
        long remoteId =
                startSelfRecording("recording-helper-active-id", Map.of("events", "template=ALL"))
                        .getLong("remoteId");
        shutdownScheduler();

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
        ActiveRecordings.Metadata metadata =
                new ActiveRecordings.Metadata(
                        Map.of(
                                "purpose",
                                "persist-active-id",
                                RecordingHelper.ACTIVE_RECORDING_ID_LABEL,
                                String.valueOf(activeRecording.id)));

        Mockito.doThrow(new RuntimeException()).when(service).create(any(), any());
        Mockito.doThrow(new RuntimeException()).when(service).create(any(), any(), any());
        // This is expected to rethrow the original exception, in this case
        // the runtime exception we initially threw.
        assertThrows(
                RuntimeException.class,
                () ->
                        recordingHelper.uploadSynthesizedRecording(
                                selfJvmId, recordingFile, recordingFile.toString(), metadata));

        Mockito.verify(storage).deleteObject(any(DeleteObjectRequest.class));
        // Archives should not have anything.
        List<ArchivedRecordings.ArchivedRecording> archivedRecordings =
                recordingHelper.listArchivedRecordings((String) null);

        assertThat(archivedRecordings, hasSize(equalTo(0)));
    }
}
