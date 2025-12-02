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
import java.util.List;
import java.util.Objects;

import io.cryostat.ConfigProperties;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.targets.Target;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

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

    @Inject RecordingHelper recordingHelper;
    @Inject Logger logger;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_ARCHIVES)
    String archiveBucket;

    @Override
    public void execute(JobExecutionContext ctx) throws JobExecutionException {
        String jvmId = (String) ctx.getMergedJobDataMap().get("jvmId");
        String ruleName = (String) ctx.getMergedJobDataMap().get("ruleName");
        int preservedArchives = (int) ctx.getMergedJobDataMap().get("preservedArchives");

        try {
            List<S3Object> previousRecordings = previousRecordings(jvmId, ruleName);
            // minus 1 because we will continue to add one more after pruning
            if (previousRecordings.size() >= preservedArchives - 1) {
                List<S3Object> toPrune =
                        previousRecordings.subList(
                                preservedArchives - 1, previousRecordings.size());
                for (var obj : toPrune) {
                    String path = obj.key().strip();
                    String[] parts = path.split("/");
                    String filename = parts[1];
                    QuarkusTransaction.joiningExisting()
                            .run(
                                    () -> {
                                        try {
                                            recordingHelper.deleteArchivedRecording(
                                                    jvmId, filename);
                                        } catch (IOException e) {
                                            logger.warn(e);
                                        }
                                    });
                }
            }
            QuarkusTransaction.joiningExisting()
                    .call(
                            () -> {
                                long recordingId =
                                        (long) ctx.getMergedJobDataMap().get("recording");
                                ActiveRecording recording =
                                        recordingHelper
                                                .getActiveRecording(
                                                        Target.getTargetByJvmId(jvmId)
                                                                .orElseThrow(),
                                                        recordingId)
                                                .orElseThrow(
                                                        () -> {
                                                            JobExecutionException ex =
                                                                    new JobExecutionException(
                                                                            String.format(
                                                                                    """
                                                                                    Target %s did not have recording with remote ID %d
                                                                                    """
                                                                                            .strip(),
                                                                                    jvmId,
                                                                                    recordingId));
                                                            ex.setUnscheduleFiringTrigger(true);
                                                            return ex;
                                                        });
                                return recordingHelper.archiveRecording(recording);
                            });
        } catch (S3Exception e) {
            JobExecutionException ex = new JobExecutionException(e);
            ex.setRefireImmediately(true);
            throw ex;
        } catch (Exception e) {
            throw new JobExecutionException(e);
        }
    }

    List<S3Object> previousRecordings(String jvmId, String ruleName) {
        return recordingHelper.listArchivedRecordingObjects(jvmId).parallelStream()
                .sorted((a, b) -> b.lastModified().compareTo(a.lastModified()))
                .map(r -> Pair.of(r, recordingHelper.getArchivedRecordingMetadata(r.key())))
                .filter(
                        p ->
                                p.getRight()
                                        .map(Metadata::labels)
                                        .map(l -> l.get(RuleExecutor.RULE_LABEL_KEY))
                                        .filter(StringUtils::isNotBlank)
                                        .map(l -> Objects.equals(l, ruleName))
                                        .orElse(false))
                .map(Pair::getLeft)
                .toList();
    }
}
