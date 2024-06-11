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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.cryostat.core.templates.Template;
import io.cryostat.core.templates.TemplateType;
import io.cryostat.expressions.MatchExpressionEvaluator;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.recordings.RecordingHelper.RecordingOptions;
import io.cryostat.recordings.RecordingHelper.RecordingReplace;
import io.cryostat.recordings.RecordingOptionsBuilderFactory;
import io.cryostat.rules.Rule.RuleEvent;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jdk.jfr.RecordingState;
import org.apache.commons.lang3.tuple.Pair;
import org.jboss.logging.Logger;
import org.projectnessie.cel.tools.ScriptException;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;

@ApplicationScoped
public class RuleService {

    @Inject Logger logger;
    @Inject MatchExpressionEvaluator evaluator;
    @Inject TargetConnectionManager connectionManager;
    @Inject RecordingOptionsBuilderFactory recordingOptionsBuilderFactory;
    @Inject RecordingHelper recordingHelper;
    @Inject EntityManager entityManager;
    @Inject org.quartz.Scheduler quartz;

    private final List<JobKey> jobs = new CopyOnWriteArrayList<>();
    private final Map<Long, CopyOnWriteArrayList<ActiveRecording>> ruleRecordingMap =
            new ConcurrentHashMap<>();

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        logger.trace("RuleService started");
        try (Stream<Rule> rules = Rule.streamAll()) {
            rules.forEach(
                    rule -> {
                        if (rule.enabled) {
                            applyRuleToMatchingTargets(rule);
                        }
                    });
        }
    }

    @ConsumeEvent(value = Rule.RULE_ADDRESS, blocking = true)
    @Transactional
    public void handleRuleModification(RuleEvent event) {
        Rule rule = event.rule();
        var relatedRecordings =
                ruleRecordingMap.computeIfAbsent(rule.id, k -> new CopyOnWriteArrayList<>());
        switch (event.category()) {
            case CREATED:
                if (rule.enabled) {
                    applyRuleToMatchingTargets(rule);
                }
                break;
            case UPDATED:
                if (rule.enabled) {
                    applyRuleToMatchingTargets(rule);
                } else {
                    cancelTasksForRule(rule);
                    relatedRecordings.clear();
                }
                break;
            case DELETED:
                cancelTasksForRule(rule);
                relatedRecordings.clear();
                ruleRecordingMap.remove(rule.id);
                break;
            default:
                break;
        }
    }

    @ConsumeEvent(value = Rule.RULE_ADDRESS + "?clean", blocking = true)
    @Transactional
    public void handleRuleRecordingCleanup(Rule rule) {
        var relatedRecordings = ruleRecordingMap.get(rule.id);
        if (relatedRecordings == null) {
            throw new IllegalStateException("No tasks associated with rule " + rule.id);
        }
        relatedRecordings.forEach(
                rec -> {
                    ActiveRecording attachedRecoding = entityManager.merge(rec);
                    attachedRecoding.state = RecordingState.STOPPED;
                    attachedRecoding.persist();
                    // the RULE_ADDRESS will handle the task cancellations + removal
                });
    }

    void activate(Rule rule, Target target) throws Exception {
        var options = createRecordingOptions(rule);

        Pair<String, TemplateType> pair = recordingHelper.parseEventSpecifier(rule.eventSpecifier);
        Template template =
                recordingHelper.getPreferredTemplate(target, pair.getKey(), pair.getValue());

        ActiveRecording recording =
                recordingHelper
                        .startRecording(
                                target,
                                RecordingReplace.STOPPED,
                                template,
                                options,
                                Map.of("rule", rule.name))
                        .await()
                        .atMost(Duration.ofSeconds(10));
        Target attachedTarget = entityManager.merge(target);

        var relatedRecordings = ruleRecordingMap.get(rule.id);
        relatedRecordings.add(recording);

        if (rule.isArchiver()) {
            scheduleArchival(rule, attachedTarget, recording);
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

    void applyRuleToMatchingTargets(Rule rule) {
        try (Stream<Target> targets = Target.streamAll()) {
            targets.filter(
                            target -> {
                                try {
                                    return evaluator.applies(rule.matchExpression, target);
                                } catch (ScriptException e) {
                                    logger.warn(e);
                                    return false;
                                }
                            })
                    .forEach(
                            target -> {
                                try {
                                    activate(rule, target);
                                } catch (Exception e) {
                                    logger.error(e);
                                }
                            });
        }
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
        data.put("rule", rule.id);
        data.put("target", target.id);
        data.put("recording", recording.id);

        Trigger trigger =
                TriggerBuilder.newTrigger()
                        .withIdentity(rule.name, target.jvmId)
                        .usingJobData(jobDetail.getJobDataMap())
                        .withSchedule(
                                SimpleScheduleBuilder.simpleSchedule()
                                        .withIntervalInSeconds(archivalPeriodSeconds)
                                        .repeatForever()
                                        .withMisfireHandlingInstructionNowWithExistingCount())
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

    private void cancelTasksForRule(Rule rule) {
        if (rule.isArchiver()) {
            List<String> targets =
                    evaluator.getMatchedTargets(rule.matchExpression).stream()
                            .map(t -> t.jvmId)
                            .collect(Collectors.toList());
            jobs.forEach(
                    jk -> {
                        if (targets.contains(jk.getGroup())) {
                            try {
                                quartz.deleteJob(jk);
                            } catch (SchedulerException e) {
                                logger.error(
                                        "Failed to delete job "
                                                + jk.getName()
                                                + " for rule "
                                                + rule.name);
                            } finally {
                                jobs.remove(jk);
                            }
                        }
                    });
        }
    }

    @SuppressFBWarnings(value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"})
    public record RuleRecording(Rule rule, ActiveRecording recording) {
        public RuleRecording {
            Objects.requireNonNull(rule);
            Objects.requireNonNull(recording);
        }
    }
}
