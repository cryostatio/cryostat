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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import io.cryostat.rules.RuleService.PermanentFailureException;

import io.smallrye.mutiny.Uni;
import jakarta.persistence.NoResultException;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerKey;

/**
 * When a rule's recording has been deleted out from under its periodic archival job, ex. a user
 * manually deleted the auto_ recording, nothing re-activates the rule by itself. The archival job
 * must therefore have the rule re-activated, which re-creates the recording and rebinds the job,
 * instead of unscheduling itself and leaving the rule silently archiving nothing. A freshly
 * re-created recording is empty, so the job does not try to archive it on the same tick. It carries
 * on at its normal cadence and archives it as usual on the next tick. If the target itself is gone
 * there is nothing to recover and the job still unschedules.
 */
public class ScheduledArchiveJobRecoveryTest {

    static final String JVM_ID = "test-jvm-id";
    static final String RULE_NAME = "test_rule";
    static final long STALE_REMOTE_ID = 5L;
    static final long NEW_REMOTE_ID = 77L;

    ScheduledArchiveJob job;
    RuleService ruleService;
    JobExecutionContext ctx;
    Scheduler scheduler;
    JobDataMap runningData;
    JobDataMap storedData;

    @BeforeEach
    void setUp() throws Exception {
        ruleService = mock(RuleService.class);
        job = new ScheduledArchiveJob();
        job.logger = Logger.getLogger(ScheduledArchiveJob.class);
        job.ruleService = ruleService;
        job.connectionFailedTimeout = Duration.ofSeconds(5);

        JobKey key = new JobKey(RULE_NAME, "rule.scheduled-archive." + JVM_ID);

        runningData = new JobDataMap();
        runningData.put("recording", STALE_REMOTE_ID);
        runningData.put("retryCount", 0);
        JobDetail runningDetail = mock(JobDetail.class);
        when(runningDetail.getKey()).thenReturn(key);
        when(runningDetail.getJobDataMap()).thenReturn(runningData);

        storedData = new JobDataMap();
        storedData.put("recording", NEW_REMOTE_ID);
        JobDetail storedDetail = mock(JobDetail.class);
        when(storedDetail.getJobDataMap()).thenReturn(storedData);

        SimpleTrigger trigger = mock(SimpleTrigger.class);
        when(trigger.getKey()).thenReturn(new TriggerKey(RULE_NAME, key.getGroup()));
        when(trigger.getRepeatInterval()).thenReturn(30_000L);

        scheduler = mock(Scheduler.class);
        when(scheduler.getJobDetail(key)).thenReturn(storedDetail);

        ctx = mock(JobExecutionContext.class);
        when(ctx.getJobDetail()).thenReturn(runningDetail);
        when(ctx.getTrigger()).thenReturn(trigger);
        when(ctx.getScheduler()).thenReturn(scheduler);

        when(ruleService.activateRule(RULE_NAME, JVM_ID)).thenReturn(Uni.createFrom().nullItem());
    }

    /**
     * The deleted recording is re-created by re-activating the rule. This tick does not archive the
     * new, empty recording and does not fail, so the trigger carries on at its normal cadence.
     */
    @Test
    void missingRecordingTriggersReactivationWithoutFailingTheTick() throws Exception {
        job.onMissing(
                ctx,
                new ScheduledArchiveJob.RecordingMissingException("gone"),
                JVM_ID,
                RULE_NAME,
                0);

        verify(ruleService).activateRule(RULE_NAME, JVM_ID);
        verify(scheduler, never()).rescheduleJob(any(TriggerKey.class), any(Trigger.class));
    }

    /**
     * The job persists its own copy of the job data when it finishes, which would overwrite the
     * remoteId the re-activation has just stored for the re-created recording. The running job must
     * adopt the stored remoteId so that the next tick archives the new recording.
     */
    @Test
    void recoveryAdoptsReboundRecordingSoItIsNotOverwrittenOnCompletion() throws Exception {
        job.onMissing(
                ctx,
                new ScheduledArchiveJob.RecordingMissingException("gone"),
                JVM_ID,
                RULE_NAME,
                0);

        assertEquals(
                NEW_REMOTE_ID,
                runningData.getLong("recording"),
                "the running job's data must carry the re-created recording's remoteId");
    }

    /** A successful recovery clears any backoff state left over from earlier failed attempts. */
    @Test
    void recoveryResetsRetryCount() throws Exception {
        runningData.put("retryCount", 3);

        job.onMissing(
                ctx,
                new ScheduledArchiveJob.RecordingMissingException("gone"),
                JVM_ID,
                RULE_NAME,
                3);

        assertEquals(0, runningData.getInt("retryCount"));
    }

    /**
     * If the re-activation fails, ex. the target is momentarily unreachable, the job falls back to
     * retrying with backoff rather than giving up, so that it can recover on a later attempt.
     */
    @Test
    void failedReactivationFallsBackToBackoffRetry() throws Exception {
        when(ruleService.activateRule(RULE_NAME, JVM_ID))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("unreachable")));

        JobExecutionException ex =
                assertThrows(
                        JobExecutionException.class,
                        () ->
                                job.onMissing(
                                        ctx,
                                        new ScheduledArchiveJob.RecordingMissingException("gone"),
                                        JVM_ID,
                                        RULE_NAME,
                                        0));

        assertFalse(ex.unscheduleFiringTrigger(), "job must stay scheduled to retry");
        verify(scheduler).rescheduleJob(any(TriggerKey.class), any(Trigger.class));
        assertEquals(1, runningData.getInt("retryCount"));
        assertEquals(
                STALE_REMOTE_ID,
                runningData.getLong("recording"),
                "a failed recovery must not claim a new recording");
    }

    /** Retries are bounded, after which the job gives up as it does for any other failure. */
    @Test
    void backoffRetriesAreBounded() throws Exception {
        when(ruleService.activateRule(RULE_NAME, JVM_ID))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("unreachable")));

        JobExecutionException ex =
                assertThrows(
                        JobExecutionException.class,
                        () ->
                                job.onMissing(
                                        ctx,
                                        new ScheduledArchiveJob.RecordingMissingException("gone"),
                                        JVM_ID,
                                        RULE_NAME,
                                        6));

        assertTrue(ex.unscheduleFiringTrigger(), "job must give up once retries are exhausted");
    }

    /** If the rule or target no longer exists, retrying cannot help, so the job unschedules. */
    @Test
    void permanentActivationFailureUnschedules() throws Exception {
        when(ruleService.activateRule(RULE_NAME, JVM_ID))
                .thenReturn(
                        Uni.createFrom().failure(new PermanentFailureException("rule deleted")));

        JobExecutionException ex =
                assertThrows(
                        JobExecutionException.class,
                        () ->
                                job.onMissing(
                                        ctx,
                                        new ScheduledArchiveJob.RecordingMissingException("gone"),
                                        JVM_ID,
                                        RULE_NAME,
                                        0));

        assertTrue(ex.unscheduleFiringTrigger());
        verify(scheduler, never()).rescheduleJob(any(TriggerKey.class), any(Trigger.class));
    }

    /**
     * With no target there is nothing to re-create a recording on, so no re-activation is asked.
     */
    @Test
    void missingTargetUnschedulesWithoutReactivation() throws Exception {
        JobExecutionException ex =
                assertThrows(
                        JobExecutionException.class,
                        () ->
                                job.onMissing(
                                        ctx,
                                        new NoResultException("Target not found"),
                                        JVM_ID,
                                        RULE_NAME,
                                        0));

        assertTrue(ex.unscheduleFiringTrigger());
        verify(ruleService, never()).activateRule(eq(RULE_NAME), eq(JVM_ID));
    }
}
