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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.cryostat.ConfigProperties;
import io.cryostat.expressions.MatchExpressionEvaluator;
import io.cryostat.libcryostat.templates.Template;
import io.cryostat.libcryostat.templates.TemplateType;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.recordings.RecordingHelper.RecordingOptions;
import io.cryostat.recordings.RecordingHelper.RecordingReplace;
import io.cryostat.rules.Rule.RuleEvent;
import io.cryostat.targets.Target;
import io.cryostat.targets.Target.TargetDiscovery;
import io.cryostat.util.EntityExistsException;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.Trigger.TriggerState;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;

/**
 * Handle executing on Automated Rule activations against Targets. Start new Flight Recordings on
 * targets as needed, and schedule periodically copying data streams from the remote targets into
 * Cryostat S3 object storage.
 *
 * @see io.cryostat.rules.RuleService
 * @see io.cryostat.target.Target
 * @see io.cryostat.rules.Rule
 * @see io.cryostat.expressions.MatchExpression
 * @see io.cryostat.expressions.MatchExpressionEvaluator
 */
@ApplicationScoped
public class RuleExecutor {

    static final String RULE_LABEL_KEY = "rule";
    static final String ARCHIVAL_GROUP_PREFIX = "rule.scheduled-archive.";

    @Inject Logger logger;
    @Inject RecordingHelper recordingHelper;
    @Inject MatchExpressionEvaluator evaluator;
    @Inject Scheduler quartz;
    @Inject ActivationLock activationLock;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration connectionFailedTimeout;

    void onStop(@Observes ShutdownEvent evt) throws SchedulerException {
        quartz.shutdown();
    }

    @ConsumeEvent(value = Target.TARGET_JVM_DISCOVERY, blocking = true)
    void onMessage(TargetDiscovery event) {
        switch (event.kind()) {
            case LOST:
                Set<JobKey> jobs = new HashSet<>();
                try {
                    jobs.addAll(
                            quartz.getJobKeys(
                                    GroupMatcher.groupEndsWith(
                                            String.format(".%s", event.serviceRef().jvmId))));
                } catch (SchedulerException e) {
                    logger.error(e);
                }
                Iterator<JobKey> it = jobs.iterator();
                while (it.hasNext()) {
                    JobKey key = it.next();
                    try {
                        quartz.deleteJob(key);
                    } catch (SchedulerException e) {
                        logger.errorv(
                                "Failed to delete job {0} due to loss of target {1}",
                                key.getName(), event.serviceRef().connectUrl);
                    }
                }
                break;
            default:
                break;
        }
    }

    @ConsumeEvent(value = Rule.RULE_ADDRESS, blocking = true)
    @Transactional
    public void handleRuleModification(RuleEvent event) {
        Rule rule = event.rule();
        switch (event.category()) {
            case UPDATED:
                if (!rule.enabled) {
                    cancelTasksForRule(rule);
                } else {
                    refreshArchivalJobsForRule(rule);
                }
                break;
            case DELETED:
                cancelTasksForRule(rule);
                break;
            default:
                break;
        }
    }

    @ConsumeEvent(value = Rule.RULE_ADDRESS + "?clean", blocking = true)
    @Transactional
    public void handleRuleRecordingCleanup(Rule rule) {
        cancelTasksForRule(rule);
        logger.debugv("Cancelled scheduled tasks for rule \"{0}\"", rule.name);
    }

    public Uni<Void> activate(Target target, Rule rule) {
        try {
            Pair<String, TemplateType> pair =
                    recordingHelper.parseEventSpecifier(rule.eventSpecifier);
            Template template =
                    recordingHelper.getPreferredTemplate(target, pair.getKey(), pair.getValue());

            var priorRecording =
                    recordingHelper.getActiveRecording(
                            target, r -> Objects.equals(r.name, rule.getRecordingName()));
            if (priorRecording.isPresent()) {
                recordingHelper
                        .stopRecording(priorRecording.get())
                        .await()
                        .atMost(connectionFailedTimeout);
            }
            var labels = new HashMap<>(rule.metadata.labels());
            labels.put(RULE_LABEL_KEY, rule.name);
            ActiveRecording recording = null;
            try {
                recording =
                        recordingHelper
                                .startRecording(
                                        target,
                                        RecordingReplace.STOPPED,
                                        template,
                                        createRecordingOptions(rule),
                                        labels)
                                .await()
                                .atMost(connectionFailedTimeout);
            } catch (EntityExistsException eee) {
                logger.debugv(
                        "Recording \"{0}\" already exists on target {1}, fetching existing"
                                + " recording",
                        rule.getRecordingName(), target.id);
                var existingRecording =
                        recordingHelper.getActiveRecording(
                                target, r -> Objects.equals(r.name, rule.getRecordingName()));
                if (existingRecording.isPresent()) {
                    recording = existingRecording.get();
                } else {
                    logger.warnv(
                            "Recording \"{0}\" should exist on target {1} but was not found",
                            rule.getRecordingName(), target.id);
                }
            }
            if (recording != null && rule.isArchiver()) {
                final ActiveRecording started = recording;
                activationLock.withLock(
                        target.jvmId,
                        () -> {
                            scheduleArchival(
                                    rule, target, currentRuleRecording(rule, target, started));
                            return null;
                        });
            } else if (recording == null) {
                logger.errorv(
                        "Failed to activate rule \"{0}\" on target {1}: recording is null",
                        rule.name, target.connectUrl);
                throw new IllegalStateException(
                        "Recording activation failed but no exception was thrown");
            }
            return Uni.createFrom().nullItem();
        } catch (Exception e) {
            logger.errorv(
                    e,
                    "Rule \"{0}\" activation failed on target {1}",
                    rule.name,
                    target.connectUrl);
            return Uni.createFrom().failure(e);
        }
    }

    /**
     * Activations for Targets that share a jvmId can interleave their stop and start steps, so the
     * recording an activation started may already have been replaced by the time it gets to
     * schedule archival. Resolve the rule recording that is live now so that the archival job is
     * never bound to a remoteId that a concurrent activation has already superseded.
     */
    private ActiveRecording currentRuleRecording(
            Rule rule, Target target, ActiveRecording fallback) {
        return recordingHelper
                .getActiveRecording(target, r -> Objects.equals(r.name, rule.getRecordingName()))
                .orElse(fallback);
    }

    /**
     * Bring every existing archival job of the rule in line with the rule's current settings. A
     * rule can be edited in place, without being toggled and without its Targets being
     * rediscovered, so nothing else would carry the edit into jobs that were created under the old
     * settings. Jobs are found by name rather than by evaluating the rule's match expression, so
     * jobs on every JVM the rule has been active on are covered. A rule that no longer archives has
     * its archival jobs removed.
     */
    private void refreshArchivalJobsForRule(Rule rule) {
        Set<JobKey> keys;
        try {
            keys = quartz.getJobKeys(GroupMatcher.groupStartsWith(ARCHIVAL_GROUP_PREFIX));
        } catch (SchedulerException e) {
            logger.error(e);
            return;
        }
        for (JobKey key : keys) {
            if (!Objects.equals(key.getName(), rule.name)) {
                continue;
            }
            String jvmId = key.getGroup().substring(ARCHIVAL_GROUP_PREFIX.length());
            try {
                activationLock.withLock(
                        jvmId,
                        () -> {
                            if (rule.isArchiver()) {
                                syncArchivalJob(rule, key, null);
                            } else {
                                quartz.deleteJob(key);
                            }
                            return null;
                        });
            } catch (Exception e) {
                logger.errorv(
                        e,
                        "Failed to refresh archival job {0} for updated rule {1}",
                        key.getGroup(),
                        rule.name);
            }
        }
    }

    private void cancelTasksForRule(Rule rule) {
        if (rule.isArchiver()) {
            List<String> targets =
                    evaluator.getMatchedTargets(rule.matchExpression).stream()
                            .map(t -> t.jvmId)
                            .collect(Collectors.toList());
            for (String s : targets) {
                Set<JobKey> jobs = new HashSet<>();
                try {
                    jobs.addAll(
                            quartz.getJobKeys(GroupMatcher.groupEndsWith(String.format(".%s", s))));
                } catch (SchedulerException e) {
                    logger.error(e);
                }
                Iterator<JobKey> it = jobs.iterator();
                while (it.hasNext()) {
                    JobKey key = it.next();
                    try {
                        quartz.deleteJob(key);
                    } catch (SchedulerException e) {
                        logger.errorv(
                                "Failed to delete job {0} due to loss of target {1}",
                                key.getName(), s);
                    }
                }
            }
        }
    }

    private RecordingOptions createRecordingOptions(Rule rule) {
        return new RecordingOptions(
                rule.getRecordingName(),
                Optional.of(true),
                Optional.of(true),
                Optional.empty(),
                Optional.ofNullable((long) rule.maxSizeBytes),
                Optional.ofNullable((long) rule.maxAgeSeconds));
    }

    private void scheduleArchival(Rule rule, Target target, ActiveRecording recording)
            throws SchedulerException {
        logger.debugv(
                "Scheduling archiver job for rule {0} on target {1} recording {2}",
                rule.id, target.jvmId, recording.remoteId);
        JobDetail jobDetail =
                JobBuilder.newJob(ScheduledArchiveJob.class)
                        .withIdentity(rule.name, ARCHIVAL_GROUP_PREFIX + target.jvmId)
                        .usingJobData("jvmId", target.jvmId)
                        .usingJobData("ruleName", rule.name)
                        .usingJobData("recording", recording.remoteId)
                        .usingJobData("preservedArchives", rule.preservedArchives)
                        .usingJobData("retryCount", 0)
                        .build();

        if (quartz.checkExists(jobDetail.getKey())) {
            syncArchivalJob(rule, jobDetail.getKey(), recording);
            return;
        }

        try {
            quartz.scheduleJob(
                    jobDetail,
                    createArchivalTrigger(rule, jobDetail.getKey(), initialFireTime(rule)));
            logger.debugv(
                    "Scheduled archiver job for rule {0} on target {1} recording {2}",
                    rule.id, target.jvmId, recording.remoteId);
        } catch (SchedulerException e) {
            logger.errorv(
                    e,
                    "Failed to schedule archival job for rule {0} in target {1}",
                    rule.name,
                    target.alias);
        }
    }

    /**
     * Bring an already-scheduled archival job in line with the rule and, when known, with the
     * recording that the rule has just (re-)activated. The caller must hold the activation lock for
     * the job's jvmId.
     *
     * <p>The job refers to its recording by remoteId, which changes whenever the rule recording is
     * re-created, so a stale remoteId is updated in place. It also holds a copy of the rule's
     * preservedArchives, which is refreshed when the rule has been edited. The job's trigger is
     * left untouched while it is alive and still matches the rule's archival period, so that the
     * archival cadence is not interrupted. A live trigger whose period no longer matches the rule
     * is rescheduled to the new period. A trigger that has ended, ex. because the job unscheduled
     * itself after failing to find its recording or after exhausting its retries, is replaced so
     * that archiving resumes, but only when the recording is known, since a rule edit alone cannot
     * make a job with a stale recording viable again.
     *
     * @param recording the rule's current recording on the target, or null if not known
     */
    private void syncArchivalJob(Rule rule, JobKey key, ActiveRecording recording)
            throws SchedulerException {
        JobDetail existing = quartz.getJobDetail(key);
        if (existing == null) {
            return;
        }
        JobDataMap data = existing.getJobDataMap();
        TriggerKey triggerKey = new TriggerKey(key.getName(), key.getGroup());
        TriggerState state = quartz.getTriggerState(triggerKey);
        boolean triggerDead =
                state == TriggerState.NONE
                        || state == TriggerState.COMPLETE
                        || state == TriggerState.ERROR;
        boolean restoreTrigger = triggerDead && recording != null;
        boolean recordingStale =
                recording != null
                        && (!data.containsKey("recording")
                                || data.getLong("recording") != recording.remoteId);
        boolean archivesStale =
                !data.containsKey("preservedArchives")
                        || data.getInt("preservedArchives") != rule.preservedArchives;

        if (recordingStale || archivesStale || restoreTrigger) {
            JobBuilder builder = existing.getJobBuilder();
            if (recording != null) {
                builder = builder.usingJobData("recording", recording.remoteId);
            }
            builder = builder.usingJobData("preservedArchives", rule.preservedArchives);
            if (restoreTrigger) {
                builder = builder.usingJobData("retryCount", 0);
            }
            quartz.addJob(builder.build(), true, true);
            logger.debugv("Updated archiver job {0} for rule {1}", key.getGroup(), rule.name);
        }

        if (restoreTrigger) {
            Trigger trigger = createArchivalTrigger(rule, key, initialFireTime(rule));
            if (quartz.checkExists(triggerKey)) {
                quartz.rescheduleJob(triggerKey, trigger);
            } else {
                quartz.scheduleJob(trigger);
            }
            logger.debugv("Restored archiver trigger for job {0}", key.getGroup());
        } else if (!triggerDead && periodStale(quartz.getTrigger(triggerKey), rule)) {
            long next = System.currentTimeMillis() + rule.archivalPeriodSeconds * 1000L;
            quartz.rescheduleJob(triggerKey, createArchivalTrigger(rule, key, new Date(next)));
            logger.debugv(
                    "Rescheduled archiver trigger for job {0} to period {1}s",
                    key.getGroup(), rule.archivalPeriodSeconds);
        }
    }

    private boolean periodStale(Trigger trigger, Rule rule) {
        return trigger instanceof SimpleTrigger simple
                && simple.getRepeatInterval() != rule.archivalPeriodSeconds * 1000L;
    }

    private Date initialFireTime(Rule rule) {
        int initialDelay = rule.initialDelaySeconds;
        if (initialDelay <= 0) {
            initialDelay = rule.archivalPeriodSeconds;
        }
        return new Date(System.currentTimeMillis() + initialDelay * 1000L);
    }

    private Trigger createArchivalTrigger(Rule rule, JobKey key, Date startAt) {
        int archivalPeriodSeconds = rule.archivalPeriodSeconds;
        return TriggerBuilder.newTrigger()
                .withIdentity(key.getName(), key.getGroup())
                .forJob(key)
                .withSchedule(
                        SimpleScheduleBuilder.simpleSchedule()
                                .withIntervalInSeconds(archivalPeriodSeconds)
                                .repeatForever()
                                .withMisfireHandlingInstructionNextWithRemainingCount())
                .startAt(startAt)
                .build();
    }
}
