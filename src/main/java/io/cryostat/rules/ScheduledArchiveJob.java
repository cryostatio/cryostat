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
package io.cryostat.rules;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.cryostat.ConfigProperties;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.targets.Target;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Perform recording archival by pulling data stream from a target and copying it into a file in S3
 * object storage.
 *
 * @see io.cryostat.target.Target
 * @see io.cryostat.recordings.ActiveRecording
 * @see io.cryostat.rules.Rule
 * @see io.cryostat.rules.RuleExecutor
 */
class ScheduledArchiveJob implements Job {

    private static final Pattern RECORDING_FILENAME_PATTERN =
            Pattern.compile(
                    "([A-Za-z\\d\\.-]*)_([A-Za-z\\d-_]*)_([\\d]*T[\\d]*Z)(\\.[\\d]+)?(\\.jfr)?");

    @Inject RecordingHelper recordingHelper;
    @Inject Logger logger;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_ARCHIVES)
    String archiveBucket;

    @Override
    public void execute(JobExecutionContext ctx) throws JobExecutionException {
        String jvmId = (String) ctx.getJobDetail().getJobDataMap().get("jvmId");
        String recordingName = (String) ctx.getJobDetail().getJobDataMap().get("recordingName");
        long recordingId = (long) ctx.getJobDetail().getJobDataMap().get("recording");
        ActiveRecording recording =
                QuarkusTransaction.joiningExisting()
                        .call(
                                () ->
                                        recordingHelper
                                                .getActiveRecording(
                                                        QuarkusTransaction.joiningExisting()
                                                                .call(
                                                                        () ->
                                                                                Target
                                                                                        .getTargetByJvmId(
                                                                                                jvmId)
                                                                                        .orElseThrow()),
                                                        recordingId)
                                                .orElseThrow());
        int preservedArchives = (int) ctx.getJobDetail().getJobDataMap().get("preservedArchives");

        if (recording == null) {
            throw new IllegalStateException(
                    String.format(
                            "Target %s did not have recording with remote ID %d",
                            jvmId, recordingId));
        }

        try {
            Queue<String> previousRecordings = new ArrayDeque<>(preservedArchives);

            initPreviousRecordings(jvmId, recordingName, previousRecordings);
            while (previousRecordings.size() >= preservedArchives) {
                pruneArchive(jvmId, previousRecordings, previousRecordings.remove());
            }

            previousRecordings.add(recordingHelper.archiveRecording(recording).name());
        } catch (S3Exception e) {
            JobExecutionException ex = new JobExecutionException(e);
            ex.setRefireImmediately(true);
            throw ex;
        } catch (Exception e) {
            throw new JobExecutionException(e);
        }
    }

    void initPreviousRecordings(
            String jvmId, String recordingName, Queue<String> previousRecordings) {
        recordingHelper.listArchivedRecordingObjects(jvmId).stream()
                .sorted((a, b) -> a.lastModified().compareTo(b.lastModified()))
                .forEach(
                        item -> {
                            String path = item.key().strip();
                            String[] parts = path.split("/");
                            String filename = parts[1];
                            Matcher m = RECORDING_FILENAME_PATTERN.matcher(filename);
                            if (m.matches() && Objects.equals(recordingName, m.group(2))) {
                                previousRecordings.add(filename);
                            }
                        });
    }

    void pruneArchive(String jvmId, Queue<String> previousRecordings, String filename) {
        try {
            recordingHelper.deleteArchivedRecording(jvmId, filename);
        } catch (IOException e) {
            logger.error(e);
        }
        previousRecordings.remove(filename);
    }
}
