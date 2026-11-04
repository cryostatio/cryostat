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

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import io.cryostat.ConfigProperties;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.rules.RuleService.PermanentFailureException;
import io.cryostat.targets.Target;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.persistence.NoResultException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.ObjectDeletedException;
import org.jboss.logging.Logger;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.PersistJobDataAfterExecution;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.SimpleTrigger;
import org.quartz.TriggerBuilder;
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
@DisallowConcurrentExecution
@PersistJobDataAfterExecution
class ScheduledArchiveJob implements Job {

    @Inject RecordingHelper recordingHelper;
    @Inject RuleService ruleService;
    @Inject Logger logger;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_ARCHIVES)
    String archiveBucket;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration connectionFailedTimeout;

    /**
     * The target is known but no longer has the recording this job was created for, ex. it was
     * deleted by a user. Unlike a missing target this can be recovered from by re-activating the
     * rule.
     */
    static class RecordingMissingException extends NoResultException {
        RecordingMissingException(String message) {
            super(message);
        }
    }

    @Override
    public void execute(JobExecutionContext ctx) throws JobExecutionException {
        String jvmId = (String) ctx.getMergedJobDataMap().get("jvmId");
        String ruleName = (String) ctx.getMergedJobDataMap().get("ruleName");
        int preservedArchives = (int) ctx.getMergedJobDataMap().get("preservedArchives");
        int retryCount = ctx.getMergedJobDataMap().getIntValue("retryCount");

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
                            .call(
                                    () -> {
                                        recordingHelper.deleteArchivedRecording(jvmId, filename);
                                        return null;
                                    });
                }
            }
            QuarkusTransaction.joiningExisting()
                    .call(
                            () -> {
                                long recordingId =
                                        (long) ctx.getMergedJobDataMap().get("recording");
                                Target target =
                                        Target.getTargetByJvmId(jvmId)
                                                .orElseThrow(
                                                        () ->
                                                                new NoResultException(
                                                                        String.format(
                                                                                "Target %s not"
                                                                                        + " found",
                                                                                jvmId)));
                                ActiveRecording recording =
                                        recordingHelper
                                                .getActiveRecording(target, recordingId)
                                                .orElseThrow(
                                                        () ->
                                                                new RecordingMissingException(
                                                                        String.format(
                                                                                "Target %s did not"
                                                                                    + " have"
                                                                                    + " recording"
                                                                                    + " with remote"
                                                                                    + " ID %d",
                                                                                jvmId,
                                                                                recordingId)));
                                return recordingHelper.archiveRecording(recording);
                            });

            if (retryCount > 0) {
                ctx.getJobDetail().getJobDataMap().put("retryCount", 0);
                logger.debugv(
                        "Archive job for rule {0} target {1} succeeded after {2} retries",
                        ruleName, jvmId, retryCount);
            }
        } catch (NoResultException | ObjectDeletedException e) {
            onMissing(ctx, e, jvmId, ruleName, retryCount);
        } catch (S3Exception e) {
            handleRetryableFailure(ctx, e, jvmId, ruleName, retryCount);
        } catch (Exception e) {
            handleRetryableFailure(ctx, e, jvmId, ruleName, retryCount);
        }
    }

    /**
     * Handle the target or the job's recording having disappeared. If only the recording is gone,
     * have the rule re-activated so that the recording is re-created and this job is rebound to it.
     * The re-created recording has just started and is likely empty, so it is not archived on this
     * tick. The trigger carries on at its normal cadence and the new recording is archived as usual
     * on the next tick. If the re-activation fails the job falls back to retrying with backoff. If
     * the target or the rule is gone there is nothing to recover and the job unschedules itself.
     */
    void onMissing(
            JobExecutionContext ctx, Exception cause, String jvmId, String ruleName, int retryCount)
            throws JobExecutionException {
        if (!(cause instanceof RecordingMissingException)) {
            unscheduleBecauseMissing(cause, jvmId, ruleName);
        }
        logger.warnv(
                cause,
                "Recording no longer exists for rule {0} target {1}, re-activating rule",
                ruleName,
                jvmId);
        try {
            ruleService.activateRule(ruleName, jvmId).await().atMost(connectionFailedTimeout);
        } catch (PermanentFailureException e) {
            unscheduleBecauseMissing(e, jvmId, ruleName);
        } catch (Exception e) {
            handleRetryableFailure(ctx, e, jvmId, ruleName, retryCount);
            return;
        }
        adoptReboundJobData(ctx);
    }

    private void unscheduleBecauseMissing(Exception cause, String jvmId, String ruleName)
            throws JobExecutionException {
        logger.warnv(
                cause,
                "Target or recording no longer exists for rule {0} target {1}, unscheduling job",
                ruleName,
                jvmId);
        JobExecutionException ex = new JobExecutionException(cause);
        ex.setRefireImmediately(false);
        ex.setUnscheduleFiringTrigger(true);
        throw ex;
    }

    /**
     * Re-activation updates the stored job in place, but since this job persists its own job data
     * when it completes, that would overwrite the update. Carry the stored data over into the
     * running job so that the next tick uses the re-created recording.
     */
    private void adoptReboundJobData(JobExecutionContext ctx) {
        JobDataMap running = ctx.getJobDetail().getJobDataMap();
        try {
            JobDetail stored = ctx.getScheduler().getJobDetail(ctx.getJobDetail().getKey());
            if (stored != null) {
                running.putAll(stored.getJobDataMap().getWrappedMap());
            }
        } catch (SchedulerException e) {
            logger.warnv(e, "Failed to read back rebound job data for {0}", ctx.getJobDetail());
        }
        running.put("retryCount", 0);
    }

    private void handleRetryableFailure(
            JobExecutionContext ctx, Exception e, String jvmId, String ruleName, int retryCount)
            throws JobExecutionException {
        int maxRetries = 6;

        if (retryCount >= maxRetries) {
            logger.errorv(
                    e,
                    "Archive job for rule {0} target {1} failed after {2} retries, unscheduling",
                    ruleName,
                    jvmId,
                    retryCount);
            JobExecutionException ex = new JobExecutionException(e);
            ex.setRefireImmediately(false);
            ex.setUnscheduleFiringTrigger(true);
            throw ex;
        }

        int backoffSeconds = (int) Math.pow(2, retryCount);
        ctx.getJobDetail().getJobDataMap().put("retryCount", retryCount + 1);

        logger.warnv(
                e,
                "Archive job for rule {0} target {1} failed (attempt {2}/{3}), retrying in {4}s",
                ruleName,
                jvmId,
                retryCount + 1,
                maxRetries,
                backoffSeconds);

        try {
            int intervalSeconds =
                    (int) (((SimpleTrigger) ctx.getTrigger()).getRepeatInterval() / 1000);
            ctx.getScheduler()
                    .rescheduleJob(
                            ctx.getTrigger().getKey(),
                            TriggerBuilder.newTrigger()
                                    .withIdentity(ctx.getTrigger().getKey())
                                    .startAt(
                                            new Date(
                                                    System.currentTimeMillis()
                                                            + backoffSeconds * 1000L))
                                    .withSchedule(
                                            SimpleScheduleBuilder.simpleSchedule()
                                                    .withIntervalInSeconds(intervalSeconds)
                                                    .repeatForever()
                                                    .withMisfireHandlingInstructionNextWithRemainingCount())
                                    .build());
        } catch (org.quartz.SchedulerException se) {
            logger.errorv(se, "Failed to reschedule job with backoff");
        }

        JobExecutionException ex = new JobExecutionException(e);
        ex.setRefireImmediately(false);
        throw ex;
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
