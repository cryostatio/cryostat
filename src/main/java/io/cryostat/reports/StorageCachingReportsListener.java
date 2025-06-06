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
package io.cryostat.reports;

import java.util.Optional;

import io.cryostat.ConfigProperties;
import io.cryostat.recordings.ActiveRecordings;
import io.cryostat.recordings.ArchivedRecordings.ArchivedRecording;
import io.cryostat.recordings.RecordingHelper;

import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@ApplicationScoped
class StorageCachingReportsListener {

    @ConfigProperty(name = ConfigProperties.REPORTS_STORAGE_CACHE_ENABLED)
    boolean enabled;

    @ConfigProperty(name = ConfigProperties.ARCHIVED_REPORTS_STORAGE_CACHE_NAME)
    String bucket;

    @Inject S3Client storage;

    @Inject Logger logger;

    @ConsumeEvent(value = ActiveRecordings.ARCHIVED_RECORDING_DELETED)
    public void handleArchivedRecordingDeletion(ArchivedRecording recording) {
        if (!enabled) {
            return;
        }
        Optional.ofNullable(recording.metadata().labels().get("jvmId"))
                .ifPresent(
                        jvmId -> {
                            var key = RecordingHelper.archivedRecordingKey(jvmId, recording.name());
                            logger.tracev("Picked up deletion of archived recording: {0}", key);
                            var req = DeleteObjectRequest.builder().bucket(bucket).key(key).build();
                            try {
                                storage.deleteObject(req);
                            } catch (S3Exception e) {
                                logger.warn(e);
                            }
                        });
    }
}
