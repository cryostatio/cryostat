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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

import io.cryostat.libcryostat.templates.Template;
import io.cryostat.libcryostat.templates.TemplateType;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.recordings.RecordingHelper.RecordingOptions;
import io.cryostat.recordings.RecordingHelper.RecordingReplace;
import io.cryostat.rules.Rule.RuleEvent;
import io.cryostat.rules.Rule.RuleEventCategory;
import io.cryostat.targets.Target;

import io.smallrye.mutiny.Uni;
import org.apache.commons.lang3.tuple.Pair;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.SimpleTrigger;
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
    InMemoryActivationLock lock;
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

        lock = new InMemoryActivationLock();

        executor = new RuleExecutor();
        executor.logger = Logger.getLogger(RuleExecutor.class);
        executor.recordingHelper = recordingHelper;
        executor.quartz = quartz;
        executor.activationLock = lock;
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

    /**
     * The archival job persists its job data after each execution. Without serialization, an
     * execution in progress while the rule re-activates can write its stale copy of the job data
     * back over the updated recording remoteId, so the job must not run concurrently with itself.
     */
    @Test
    void archivalJobDisallowsConcurrentExecution() throws Exception {
        stubRecordingStarts(OLD_REMOTE_ID);

        executor.activate(target, rule).await().indefinitely();

        assertTrue(
                quartz.getJobDetail(jobKey()).isConcurrentExecutionDisallowed(),
                "archival job must not execute concurrently with itself");
        assertTrue(
                quartz.getJobDetail(jobKey()).isPersistJobDataAfterExecution(),
                "archival job is expected to persist its job data after execution");
    }

    /**
     * The same physical JVM can appear as more than one Target, all sharing a jvmId, and a Rule
     * activates against each of them. Creating or updating the archival job and its trigger is a
     * read-modify-write on state shared by those activations, so it must happen while holding the
     * lock for the jvmId.
     */
    @Test
    void archivalJobIsScheduledWhileHoldingJvmIdLock() throws Exception {
        stubRecordingStarts(OLD_REMOTE_ID, NEW_REMOTE_ID);
        AtomicInteger underLock = new AtomicInteger();
        AtomicInteger withoutLock = new AtomicInteger();
        Scheduler spied = spy(quartz);
        Answer<Object> countLockState =
                inv -> {
                    (lock.isHeld(JVM_ID) ? underLock : withoutLock).incrementAndGet();
                    return inv.callRealMethod();
                };
        doAnswer(countLockState).when(spied).scheduleJob(any(JobDetail.class), any(Trigger.class));
        doAnswer(countLockState)
                .when(spied)
                .addJob(any(JobDetail.class), anyBoolean(), anyBoolean());
        executor.quartz = spied;

        executor.activate(target, rule).await().indefinitely();
        executor.activate(target, rule).await().indefinitely();

        assertEquals(List.of(JVM_ID, JVM_ID), lock.acquiredKeys(), "lock must be keyed by jvmId");
        assertEquals(2, underLock.get(), "job must be created and then updated under the lock");
        assertEquals(0, withoutLock.get(), "job must not be changed outside the lock");
    }

    /**
     * Two activations for Targets sharing a jvmId can interleave their slower steps, stopping and
     * starting the rule recording, so the one that finishes last is not necessarily the one that
     * started the live recording. The archival job must follow the recording that is actually live
     * at the time it is bound, not the remoteId the latecomer happened to be handed earlier.
     */
    @Test
    void lateActivationBindsJobToRecordingThatIsCurrentlyActive() throws Exception {
        Target duplicate = new Target();
        duplicate.jvmId = JVM_ID;
        duplicate.alias = "duplicate-target";

        AtomicInteger starts = new AtomicInteger();
        AtomicLong liveRemoteId = new AtomicLong();
        when(recordingHelper.getActiveRecording(any(Target.class), anyPredicate()))
                .thenAnswer(
                        inv ->
                                liveRemoteId.get() == 0
                                        ? Optional.empty()
                                        : Optional.of(recording(liveRemoteId.get())));
        when(recordingHelper.stopRecording(any(ActiveRecording.class)))
                .thenAnswer(inv -> Uni.createFrom().item((ActiveRecording) inv.getArgument(0)));
        stubStartRecordingAnswer(
                () -> {
                    int n = starts.incrementAndGet();
                    if (n == 1) {
                        long late = OLD_REMOTE_ID;
                        executor.activate(duplicate, rule).await().indefinitely();
                        return recording(late);
                    }
                    liveRemoteId.set(NEW_REMOTE_ID);
                    return recording(NEW_REMOTE_ID);
                });

        executor.activate(target, rule).await().indefinitely();

        assertEquals(2, starts.get(), "both activations should have started the recording");
        assertEquals(
                NEW_REMOTE_ID,
                jobData(jobKey()).getLong("recording"),
                "the job must be bound to the live recording, not the late activation's stale"
                        + " remoteId");
    }

    /** Serialization is per jvmId, so activations against distinct JVMs must still overlap. */
    @Test
    void concurrentActivationsForDifferentJvmIdsAreNotSerialized() throws Exception {
        CountDownLatch bothInside = new CountDownLatch(2);
        AtomicInteger nextRemoteId = new AtomicInteger();
        stubStartRecordingAnswer(
                () -> {
                    bothInside.countDown();
                    if (!bothInside.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "activations for distinct jvmIds were serialized");
                    }
                    return recording(nextRemoteId.incrementAndGet());
                });

        Target other = new Target();
        other.jvmId = "other-jvm-id";
        other.alias = "other-target";

        activateConcurrently(target, other);
    }

    /**
     * The archival job copies preservedArchives out of the rule when it is created, so an edit to
     * the rule must reach an already-existing job when the rule is re-activated.
     */
    @Test
    void reactivationRefreshesPreservedArchivesInExistingJob() throws Exception {
        stubRecordingStarts(OLD_REMOTE_ID, OLD_REMOTE_ID);
        executor.activate(target, rule).await().indefinitely();
        assertEquals(3, jobData(jobKey()).getInt("preservedArchives"));

        rule.preservedArchives = 5;
        executor.activate(target, rule).await().indefinitely();

        assertEquals(
                5,
                jobData(jobKey()).getInt("preservedArchives"),
                "existing job must carry the rule's current preservedArchives");
    }

    /**
     * A healthy trigger is normally left alone, but when the rule's archival period has been edited
     * the trigger no longer reflects the rule and must be rescheduled to the new cadence.
     */
    @Test
    void reactivationReschedulesHealthyTriggerWhenPeriodChanged() throws Exception {
        stubRecordingStarts(OLD_REMOTE_ID, OLD_REMOTE_ID);
        executor.activate(target, rule).await().indefinitely();
        Date nextFireBefore = quartz.getTrigger(triggerKey()).getNextFireTime();

        rule.archivalPeriodSeconds = 30;
        long beforeReschedule = System.currentTimeMillis();
        executor.activate(target, rule).await().indefinitely();

        assertEquals(30_000L, repeatInterval(triggerKey()), "trigger must use the new period");
        assertEquals(TriggerState.NORMAL, quartz.getTriggerState(triggerKey()));
        Date nextFireAfter = quartz.getTrigger(triggerKey()).getNextFireTime();
        assertTrue(nextFireAfter.before(nextFireBefore), "next archive should follow new cadence");
        assertTrue(
                nextFireAfter.getTime() <= beforeReschedule + 30_000L + 5_000L,
                "next archive should be due within about one new period");
    }

    /**
     * A Rule can be edited in place without being toggled and without its Targets being
     * rediscovered, so an UPDATED event alone must carry the edit into existing archival jobs.
     */
    @Test
    void ruleUpdateRefreshesPreservedArchivesInExistingJob() throws Exception {
        stubRecordingStarts(OLD_REMOTE_ID);
        executor.activate(target, rule).await().indefinitely();

        rule.preservedArchives = 7;
        ruleUpdated();

        assertEquals(
                7,
                jobData(jobKey()).getInt("preservedArchives"),
                "UPDATED event must refresh the existing job's data");
        assertEquals(
                OLD_REMOTE_ID,
                jobData(jobKey()).getLong("recording"),
                "refreshing the rule's settings must not disturb the recording reference");
    }

    @Test
    void ruleUpdateReschedulesHealthyTriggerWhenPeriodChanged() throws Exception {
        stubRecordingStarts(OLD_REMOTE_ID);
        executor.activate(target, rule).await().indefinitely();
        Date nextFireBefore = quartz.getTrigger(triggerKey()).getNextFireTime();

        rule.archivalPeriodSeconds = 30;
        long beforeReschedule = System.currentTimeMillis();
        ruleUpdated();

        assertEquals(30_000L, repeatInterval(triggerKey()), "trigger must use the new period");
        assertEquals(TriggerState.NORMAL, quartz.getTriggerState(triggerKey()));
        Date nextFireAfter = quartz.getTrigger(triggerKey()).getNextFireTime();
        assertTrue(nextFireAfter.before(nextFireBefore), "next archive should follow new cadence");
        assertTrue(
                nextFireAfter.getTime() <= beforeReschedule + 30_000L + 5_000L,
                "next archive should be due within about one new period");
    }

    /**
     * Edits unrelated to the archival schedule, ex. the description, also publish UPDATED events.
     * They must not disturb a healthy trigger's cadence.
     */
    @Test
    void ruleUpdateLeavesHealthyTriggerAloneWhenScheduleUnchanged() throws Exception {
        stubRecordingStarts(OLD_REMOTE_ID);
        executor.activate(target, rule).await().indefinitely();
        Trigger before = quartz.getTrigger(triggerKey());
        Thread.sleep(1100);

        rule.description = "edited";
        ruleUpdated();

        Trigger after = quartz.getTrigger(triggerKey());
        assertEquals(before.getStartTime(), after.getStartTime(), "trigger must not be replaced");
        assertEquals(before.getNextFireTime(), after.getNextFireTime());
    }

    /** The same rule has one archival job per JVM it is active on and every one must be updated. */
    @Test
    void ruleUpdateRefreshesArchivalJobsOnEveryTarget() throws Exception {
        stubRecordingStarts(OLD_REMOTE_ID, OLD_REMOTE_ID);
        Target other = new Target();
        other.jvmId = "other-jvm-id";
        other.alias = "other-target";
        executor.activate(target, rule).await().indefinitely();
        executor.activate(other, rule).await().indefinitely();

        rule.preservedArchives = 9;
        rule.archivalPeriodSeconds = 30;
        ruleUpdated();

        for (String jvmId : List.of(JVM_ID, other.jvmId)) {
            assertEquals(
                    9,
                    jobData(jobKey(jvmId)).getInt("preservedArchives"),
                    "job for " + jvmId + " must carry the rule's current preservedArchives");
            assertEquals(
                    30_000L,
                    repeatInterval(triggerKey(jvmId)),
                    "trigger for " + jvmId + " must use the new period");
        }
    }

    /** Jobs are keyed by rule name within a per-JVM group, so another rule's job is not ours. */
    @Test
    void ruleUpdateDoesNotTouchOtherRulesArchivalJobs() throws Exception {
        stubRecordingStarts(OLD_REMOTE_ID, OLD_REMOTE_ID);
        Rule other = new Rule();
        other.name = "other_rule";
        other.description = "";
        other.eventSpecifier = rule.eventSpecifier;
        other.archivalPeriodSeconds = 60;
        other.initialDelaySeconds = 0;
        other.preservedArchives = 3;
        other.metadata = new Metadata(new HashMap<>());
        other.enabled = true;
        executor.activate(target, rule).await().indefinitely();
        executor.activate(target, other).await().indefinitely();
        JobKey otherJob = new JobKey(other.name, jobKey().getGroup());
        TriggerKey otherTrigger = new TriggerKey(other.name, jobKey().getGroup());
        Trigger otherTriggerBefore = quartz.getTrigger(otherTrigger);

        rule.preservedArchives = 7;
        rule.archivalPeriodSeconds = 30;
        ruleUpdated();

        assertEquals(3, jobData(otherJob).getInt("preservedArchives"));
        assertEquals(60_000L, repeatInterval(otherTrigger));
        assertEquals(
                otherTriggerBefore.getNextFireTime(),
                quartz.getTrigger(otherTrigger).getNextFireTime());
    }

    /** Job and trigger changes are a read-modify-write that must happen under the jvmId lock. */
    @Test
    void ruleUpdateRefreshesArchivalJobWhileHoldingJvmIdLock() throws Exception {
        stubRecordingStarts(OLD_REMOTE_ID);
        executor.activate(target, rule).await().indefinitely();
        AtomicInteger underLock = new AtomicInteger();
        AtomicInteger withoutLock = new AtomicInteger();
        Scheduler spied = spy(quartz);
        Answer<Object> countLockState =
                inv -> {
                    (lock.isHeld(JVM_ID) ? underLock : withoutLock).incrementAndGet();
                    return inv.callRealMethod();
                };
        doAnswer(countLockState)
                .when(spied)
                .addJob(any(JobDetail.class), anyBoolean(), anyBoolean());
        doAnswer(countLockState)
                .when(spied)
                .rescheduleJob(any(TriggerKey.class), any(Trigger.class));
        executor.quartz = spied;

        rule.preservedArchives = 7;
        rule.archivalPeriodSeconds = 30;
        ruleUpdated();

        assertEquals(2, underLock.get(), "job data and trigger must both change under the lock");
        assertEquals(0, withoutLock.get(), "nothing may change outside the lock");
    }

    /**
     * An UPDATED event carries no fresh recording remoteId, so it cannot make a job that has lost
     * its trigger viable again. Restoring it is left to re-activation, which does know the
     * recording. The rule's own settings are still brought up to date in the job.
     */
    @Test
    void ruleUpdateDoesNotResurrectDeadTrigger() throws Exception {
        stubRecordingStarts(OLD_REMOTE_ID);
        executor.activate(target, rule).await().indefinitely();
        simulateStaleRecordingFailure();
        assertEquals(TriggerState.COMPLETE, quartz.getTriggerState(triggerKey()));

        rule.preservedArchives = 7;
        ruleUpdated();

        assertEquals(TriggerState.COMPLETE, quartz.getTriggerState(triggerKey()));
        assertEquals(7, jobData(jobKey()).getInt("preservedArchives"));
    }

    /**
     * A rule edited so that it no longer archives, ex. preservedArchives or the period set to zero,
     * has no valid archival schedule, so its existing archival jobs must go away.
     */
    @Test
    void ruleUpdateRemovesArchivalJobWhenRuleStopsArchiving() throws Exception {
        stubRecordingStarts(OLD_REMOTE_ID);
        executor.activate(target, rule).await().indefinitely();
        assertTrue(quartz.checkExists(jobKey()));

        rule.preservedArchives = 0;
        ruleUpdated();

        assertFalse(quartz.checkExists(jobKey()), "job must be removed for a non-archiving rule");
    }

    private void ruleUpdated() {
        executor.handleRuleModification(new RuleEvent(RuleEventCategory.UPDATED, rule));
    }

    private long repeatInterval(TriggerKey key) throws Exception {
        return ((SimpleTrigger) quartz.getTrigger(key)).getRepeatInterval();
    }

    private void activateConcurrently(Target first, Target second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> executor.activate(first, rule).await().indefinitely());
            Future<?> b = pool.submit(() -> executor.activate(second, rule).await().indefinitely());
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Test stand-in for {@link ActivationLock} that guards with in-process reentrant locks and
     * records how it was used, since the production implementation needs a PostgreSQL database.
     */
    static class InMemoryActivationLock extends ActivationLock {
        private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
        private final List<String> acquired = new CopyOnWriteArrayList<>();

        @Override
        public <T> T withLock(String key, Callable<T> action) throws Exception {
            ReentrantLock l = locks.computeIfAbsent(key, k -> new ReentrantLock());
            l.lock();
            try {
                acquired.add(key);
                return action.call();
            } finally {
                l.unlock();
            }
        }

        boolean isHeld(String key) {
            ReentrantLock l = locks.get(key);
            return l != null && l.isHeldByCurrentThread();
        }

        List<String> acquiredKeys() {
            return acquired;
        }
    }

    private interface RecordingSupplier {
        ActiveRecording get() throws Exception;
    }

    private void stubStartRecordingAnswer(RecordingSupplier supplier) throws Exception {
        when(recordingHelper.startRecording(
                        any(Target.class),
                        any(RecordingReplace.class),
                        any(Template.class),
                        any(RecordingOptions.class),
                        anyMap()))
                .thenAnswer(inv -> Uni.createFrom().item(supplier.get()));
    }

    private JobKey jobKey() {
        return jobKey(JVM_ID);
    }

    private JobKey jobKey(String jvmId) {
        return new JobKey(rule.name, String.format("rule.scheduled-archive.%s", jvmId));
    }

    private TriggerKey triggerKey() {
        return triggerKey(JVM_ID);
    }

    private TriggerKey triggerKey(String jvmId) {
        return new TriggerKey(rule.name, String.format("rule.scheduled-archive.%s", jvmId));
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
