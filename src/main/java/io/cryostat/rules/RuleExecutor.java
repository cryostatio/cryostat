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
import io.cryostat.rules.RuleService.ActivationAttempt;
import io.cryostat.targets.Target;
import io.cryostat.targets.Target.TargetDiscovery;
import io.cryostat.util.EntityExistsException;

import io.quarkus.narayana.jta.QuarkusTransaction;
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
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
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

    @Inject Logger logger;
    @Inject RecordingHelper recordingHelper;
    @Inject MatchExpressionEvaluator evaluator;
    @Inject Scheduler quartz;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration connectionFailedTimeout;

    void onStop(@Observes ShutdownEvent evt) throws SchedulerException {
        quartz.shutdown();
    }

    @ConsumeEvent(blocking = true)
    @Transactional
    Uni<Void> onMessage(ActivationAttempt attempt) {
        logger.tracev(
                "Attempting to activate rule \"{0}\" for target {1} -" + " attempt #{2}",
                attempt.ruleId(), attempt.targetId(), attempt.attempts());
        try {
            Target target = Target.<Target>find("id", attempt.targetId()).singleResult();
            Rule rule = Rule.<Rule>find("id", attempt.ruleId()).singleResult();
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
                // ignore - the recording already existed and was running, so we don't want to
                // replace it - but we should continue on to reschedule the periodic archival job
            }
            if (recording != null && rule.isArchiver()) {
                scheduleArchival(rule, target, recording);
            }
        } catch (Exception e) {
            logger.warn(e);
            return Uni.createFrom().failure(e);
        }

        return Uni.createFrom().nullItem();
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
        var targets = evaluator.getMatchedTargets(rule.matchExpression);
        for (var target : targets) {
            QuarkusTransaction.joiningExisting()
                    .run(
                            () -> {
                                try {
                                    var opt =
                                            recordingHelper.getActiveRecording(
                                                    target,
                                                    r ->
                                                            Objects.equals(
                                                                    r.name,
                                                                    rule.getRecordingName()));
                                    if (opt.isEmpty()) {
                                        logger.warnv(
                                                "Target {0} did not have expected Automated Rule"
                                                        + " recording with name {1}",
                                                target.id, rule.getRecordingName());
                                        return;
                                    }
                                    var recording = opt.get();
                                    recordingHelper
                                            .stopRecording(recording)
                                            .await()
                                            .atMost(connectionFailedTimeout);
                                } catch (Exception e) {
                                    logger.warn(e);
                                }
                            });
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
        JobDetail jobDetail =
                JobBuilder.newJob(ScheduledArchiveJob.class)
                        .withIdentity(
                                rule.name, String.format("rule.scheduled-archive.%s", target.jvmId))
                        .usingJobData("jvmId", target.jvmId)
                        .usingJobData("recordingName", rule.getRecordingName())
                        .usingJobData("recording", recording.remoteId)
                        .usingJobData("preservedArchives", rule.preservedArchives)
                        .build();

        if (quartz.checkExists(jobDetail.getKey())) {
            return;
        }

        int initialDelay = rule.initialDelaySeconds;
        int archivalPeriodSeconds = rule.archivalPeriodSeconds;
        if (initialDelay <= 0) {
            initialDelay = archivalPeriodSeconds;
        }

        Trigger trigger =
                TriggerBuilder.newTrigger()
                        .withIdentity(jobDetail.getKey().getName(), jobDetail.getKey().getGroup())
                        .withSchedule(
                                SimpleScheduleBuilder.simpleSchedule()
                                        .withIntervalInSeconds(archivalPeriodSeconds)
                                        .repeatForever()
                                        .withMisfireHandlingInstructionNextWithRemainingCount())
                        .startAt(new Date(System.currentTimeMillis() + initialDelay * 1000))
                        .build();
        try {
            quartz.scheduleJob(jobDetail, trigger);
        } catch (SchedulerException e) {
            logger.errorv(
                    e,
                    "Failed to schedule archival job for rule {0} in target {1}",
                    rule.name,
                    target.alias);
        }
    }
}
