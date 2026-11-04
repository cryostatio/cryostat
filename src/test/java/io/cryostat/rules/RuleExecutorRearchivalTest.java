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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Predicate;

import io.cryostat.libcryostat.templates.Template;
import io.cryostat.libcryostat.templates.TemplateType;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.recordings.RecordingHelper.RecordingOptions;
import io.cryostat.recordings.RecordingHelper.RecordingReplace;
import io.cryostat.targets.Target;

import io.smallrye.mutiny.Uni;
import org.apache.commons.lang3.tuple.Pair;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.Trigger.TriggerState;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;

/**
 * Reproduces GitHub issue #1885: when an Automated Rule is activated against a target a second time
 * (for example after the agent re-registers, or after the target is MODIFIED) and the rule's
 * recording is re-created with a new remoteId, the already-existing periodic archival Quartz job is
 * kept as-is. It continues to reference the old, now-nonexistent recording remoteId, so the next
 * firing of {@link ScheduledArchiveJob} fails with a NoResultException and unschedules its own
 * trigger. Since the job still exists, every later activation is short-circuited by the checkExists
 * early return in {@link RuleExecutor#activate} and no trigger is ever added back.
 *
 * <p>These tests use an in-memory Quartz scheduler. It is only started briefly to replay the
 * self-unscheduling behaviour of {@link ScheduledArchiveJob} using a stand-in job class, since the
 * real job requires CDI and a database.
 */
public class RuleExecutorRearchivalTest {

    static final String JVM_ID = "test-jvm-id";
    static final long OLD_REMOTE_ID = 1L;
    static final long NEW_REMOTE_ID = 2L;

    Scheduler quartz;
    RecordingHelper recordingHelper;
    RuleExecutor executor;
    Rule rule;
    Target target;

    @BeforeEach
    void setUp() throws Exception {
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "RuleExecutorRearchivalTest");
        props.setProperty("org.quartz.threadPool.threadCount", "1");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        quartz = new StdSchedulerFactory(props).getScheduler();

        recordingHelper = mock(RecordingHelper.class);
        when(recordingHelper.parseEventSpecifier(any()))
                .thenReturn(Pair.of("Continuous", TemplateType.TARGET));
        when(recordingHelper.getPreferredTemplate(any(), any(), any()))
                .thenReturn(mock(Template.class));
        when(recordingHelper.getActiveRecording(any(Target.class), anyPredicate()))
                .thenReturn(Optional.empty());

        executor = new RuleExecutor();
        executor.logger = Logger.getLogger(RuleExecutor.class);
        executor.recordingHelper = recordingHelper;
        executor.quartz = quartz;
        executor.connectionFailedTimeout = Duration.ofSeconds(5);

        target = new Target();
        target.jvmId = JVM_ID;
        target.alias = "test-target";

        rule = new Rule();
        rule.name = "test_rule";
        rule.description = "";
        rule.eventSpecifier = "template=Continuous,type=TARGET";
        rule.archivalPeriodSeconds = 60;
        rule.initialDelaySeconds = 0;
        rule.preservedArchives = 3;
        rule.maxAgeSeconds = 0;
        rule.maxSizeBytes = 0;
        rule.metadata = new Metadata(new HashMap<>());
        rule.enabled = true;
    }

    @AfterEach
    void tearDown() throws Exception {
        quartz.shutdown();
    }

    /**
     * Baseline: the first activation schedules a job and trigger bound to the first recording. This
     * passes today and confirms the scenario setup is sound.
     */
    @Test
    void firstActivationSchedulesJobForFirstRecording() throws Exception {
        stubRecordingStarts(OLD_REMOTE_ID);

        executor.activate(target, rule).await().indefinitely();

        JobKey key = jobKey();
        assertTrue(quartz.checkExists(key), "archival job should be scheduled");
        assertEquals(OLD_REMOTE_ID, jobData(key).getLong("recording"));
        assertNotNull(quartz.getTrigger(triggerKey()), "archival trigger should be scheduled");
    }

    /**
     * Expected: when the rule is re-activated and its recording is re-created with a new remoteId,
     * the archival job must be bound to the new recording. Actual: the stale job is kept and still
     * references the old remoteId, so ScheduledArchiveJob will not be able to find the recording on
     * its next firing.
     */
    @Test
    void reactivationRebindsArchivalJobToNewRecording() throws Exception {
        stubRecordingStarts(OLD_REMOTE_ID, NEW_REMOTE_ID);

        executor.activate(target, rule).await().indefinitely();
        assertEquals(OLD_REMOTE_ID, jobData(jobKey()).getLong("recording"));

        executor.activate(target, rule).await().indefinitely();

        assertEquals(
                NEW_REMOTE_ID,
                jobData(jobKey()).getLong("recording"),
                "archival job must reference the re-created recording's remoteId, not the stale"
                        + " one");
    }

    /**
     * Expected: re-activating a rule whose archival trigger is healthy must not interrupt or
     * reschedule the existing archival cadence, even though the job must be pointed at the
     * re-created recording. This pins down the schedule half of the requirement so that a fix
     * cannot satisfy the remoteId half by resetting the timer.
     */
    @Test
    void reactivationDoesNotRescheduleHealthyTrigger() throws Exception {
        stubRecordingStarts(OLD_REMOTE_ID, NEW_REMOTE_ID);

        executor.activate(target, rule).await().indefinitely();
        Trigger before = quartz.getTrigger(triggerKey());
        assertNotNull(before);
        Date nextFireBefore = before.getNextFireTime();
        Date startBefore = before.getStartTime();
        assertNotNull(nextFireBefore);

        // ensure that any rescheduling based on "now" would be observably different
        Thread.sleep(1100);

        executor.activate(target, rule).await().indefinitely();

        Trigger after = quartz.getTrigger(triggerKey());
        assertNotNull(after, "healthy trigger must still exist after re-activation");
        assertEquals(
                nextFireBefore,
                after.getNextFireTime(),
                "re-activation must not move the next archival fire time");
        assertEquals(startBefore, after.getStartTime(), "trigger must not have been replaced");
        assertEquals(TriggerState.NORMAL, quartz.getTriggerState(triggerKey()));
        assertEquals(
                NEW_REMOTE_ID,
                jobData(jobKey()).getLong("recording"),
                "archival job must be pointed at the re-created recording");
    }

    /**
     * Expected: after the stale job has unscheduled its own trigger (which is what
     * ScheduledArchiveJob does on NoResultException via setUnscheduleFiringTrigger(true)), a
     * subsequent activation must restore periodic archiving. Actual: the job still exists, so the
     * checkExists early return in RuleExecutor.scheduleArchival skips scheduling and the job
     * remains permanently without a trigger.
     */
    @Test
    void reactivationRestoresTriggerAfterJobUnscheduledItself() throws Exception {
        stubRecordingStarts(OLD_REMOTE_ID, NEW_REMOTE_ID);

        executor.activate(target, rule).await().indefinitely();
        assertNotNull(quartz.getTrigger(triggerKey()));

        simulateStaleRecordingFailure();
        assertEquals(TriggerState.COMPLETE, quartz.getTriggerState(triggerKey()));
        assertTrue(quartz.checkExists(jobKey()), "job survives its trigger completing");

        executor.activate(target, rule).await().indefinitely();

        List<? extends Trigger> triggers = quartz.getTriggersOfJob(jobKey());
        assertFalse(
                triggers.isEmpty(),
                "re-activating the rule must restore a trigger for the archival job");
        assertEquals(
                TriggerState.NORMAL,
                quartz.getTriggerState(triggers.get(0).getKey()),
                "restored trigger should be active");
        assertEquals(NEW_REMOTE_ID, jobData(jobKey()).getLong("recording"));
    }

    /**
     * Replays what ScheduledArchiveJob does when the stored remoteId no longer resolves: the job
     * throws a JobExecutionException with unscheduleFiringTrigger=true, leaving the trigger
     * COMPLETE while the job itself remains. The real job class needs CDI and a database, so a
     * stand-in job class with the same job key, data, and failure behaviour is fired instead.
     */
    private void simulateStaleRecordingFailure() throws Exception {
        JobDetail original = quartz.getJobDetail(jobKey());
        JobDetail stand =
                JobBuilder.newJob(UnschedulingJob.class)
                        .withIdentity(jobKey())
                        .usingJobData(original.getJobDataMap())
                        .build();
        quartz.addJob(stand, true, true);
        Trigger now =
                TriggerBuilder.newTrigger()
                        .withIdentity(triggerKey())
                        .forJob(jobKey())
                        .startNow()
                        .withSchedule(
                                SimpleScheduleBuilder.simpleSchedule()
                                        .withIntervalInSeconds(60)
                                        .repeatForever())
                        .build();
        quartz.rescheduleJob(triggerKey(), now);
        quartz.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (quartz.getTriggerState(triggerKey()) != TriggerState.COMPLETE
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        quartz.standby();
    }

    public static class UnschedulingJob implements Job {
        @Override
        public void execute(JobExecutionContext ctx) throws JobExecutionException {
            JobExecutionException ex = new JobExecutionException("stale remote recording id");
            ex.setRefireImmediately(false);
            ex.setUnscheduleFiringTrigger(true);
            throw ex;
        }
    }

    private JobKey jobKey() {
        return new JobKey(rule.name, String.format("rule.scheduled-archive.%s", JVM_ID));
    }

    private TriggerKey triggerKey() {
        return new TriggerKey(rule.name, String.format("rule.scheduled-archive.%s", JVM_ID));
    }

    private JobDataMap jobData(JobKey key) throws Exception {
        return quartz.getJobDetail(key).getJobDataMap();
    }

    private void stubRecordingStarts(long first, long... rest) throws Exception {
        var stubbing =
                when(recordingHelper.startRecording(
                                any(Target.class),
                                any(RecordingReplace.class),
                                any(Template.class),
                                any(RecordingOptions.class),
                                anyMap()))
                        .thenReturn(Uni.createFrom().item(recording(first)));
        for (long id : rest) {
            stubbing = stubbing.thenReturn(Uni.createFrom().item(recording(id)));
        }
    }

    private ActiveRecording recording(long remoteId) {
        ActiveRecording r = new ActiveRecording();
        r.remoteId = remoteId;
        r.name = rule.getRecordingName();
        r.target = target;
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Predicate<ActiveRecording> anyPredicate() {
        return any(Predicate.class);
    }
}
