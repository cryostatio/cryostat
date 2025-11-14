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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
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

    @Inject Logger logger;
    @Inject RecordingHelper recordingHelper;
    @Inject MatchExpressionEvaluator evaluator;
    @Inject Scheduler quartz;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration connectionFailedTimeout;

    private final List<JobKey> jobs = new CopyOnWriteArrayList<>();

    void onStop(@Observes ShutdownEvent evt) throws SchedulerException {
        quartz.shutdown();
    }

    @ConsumeEvent(blocking = true)
    @Transactional
    Uni<Void> onMessage(ActivationAttempt attempt) {
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
            labels.put("rule", rule.name);
            ActiveRecording recording =
                    recordingHelper
                            .startRecording(
                                    target,
                                    RecordingReplace.STOPPED,
                                    template,
                                    createRecordingOptions(rule),
                                    labels)
                            .await()
                            .atMost(Duration.ofSeconds(10));

            if (rule.isArchiver()) {
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
                for (var jk : jobs) {
                    if (Objects.equals(event.serviceRef().jvmId, jk.getGroup())) {
                        try {
                            quartz.deleteJob(jk);
                        } catch (SchedulerException e) {
                            logger.errorv(
                                    "Failed to delete job {0} due to loss of target {1}",
                                    jk.getName(), event.serviceRef().connectUrl);
                        } finally {
                            jobs.remove(jk);
                        }
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
            for (var jk : jobs) {
                if (targets.contains(jk.getGroup())) {
                    try {
                        quartz.deleteJob(jk);
                    } catch (SchedulerException e) {
                        logger.error(
                                "Failed to delete job " + jk.getName() + " for rule " + rule.name);
                    } finally {
                        jobs.remove(jk);
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

    private void scheduleArchival(Rule rule, Target target, ActiveRecording recording) {
        JobDetail jobDetail =
                JobBuilder.newJob(ScheduledArchiveJob.class)
                        .withIdentity(rule.name, target.jvmId)
                        .build();

        if (jobs.contains(jobDetail.getKey())) {
            return;
        }

        int initialDelay = rule.initialDelaySeconds;
        int archivalPeriodSeconds = rule.archivalPeriodSeconds;
        if (initialDelay <= 0) {
            initialDelay = archivalPeriodSeconds;
        }

        Map<String, Object> data = jobDetail.getJobDataMap();
        data.put("jvmId", target.jvmId);
        data.put("recordingName", rule.getRecordingName());
        data.put("recording", recording.remoteId);
        data.put("preservedArchives", rule.preservedArchives);

        Trigger trigger =
                TriggerBuilder.newTrigger()
                        .withIdentity(rule.name, target.jvmId)
                        .usingJobData(jobDetail.getJobDataMap())
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
        jobs.add(jobDetail.getKey());
    }
}
