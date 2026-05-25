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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.cryostat.ConfigProperties;
import io.cryostat.expressions.MatchExpressionEvaluator;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.rules.Rule.RuleEvent;
import io.cryostat.targets.Target;
import io.cryostat.targets.Target.TargetDiscovery;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Asynchronous;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;
import org.projectnessie.cel.tools.ScriptException;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.matchers.GroupMatcher;

/**
 * Handle {@link io.cryostat.target.Target} instances appearing and disappearing, and {@link
 * io.cryostat.rules.Rule} instances being created or deleted. Delegates to the RuleExecutor to kick
 * off new Flight Recordings in applicable targets and to handle periodically copying recording
 * data, if configured to do so.
 *
 * @see io.cryostat.rules.RuleExecutor
 * @see io.cryostat.expressions.MatchExpression
 * @see io.cryostat.expressions.MatchExpressionEvaluator
 */
@ApplicationScoped
public class RuleService {

    @Inject Logger logger;
    @Inject MatchExpressionEvaluator evaluator;
    @Inject RecordingHelper recordingHelper;
    @Inject RuleExecutor ruleExecutor;
    @Inject Scheduler quartz;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration connectionFailedTimeout;

    void onStart(@Observes StartupEvent ev) {
        logger.trace("RuleService started");
        // Apply all enabled rules to matching targets
        for (Rule rule : enabledRules()) {
            try {
                QuarkusTransaction.requiringNew().run(() -> applyRuleToMatchingTargets(rule));
            } catch (Exception e) {
                logger.error(e);
            }
        }
    }

    void onStop(@Observes ShutdownEvent evt) {
        try {
            quartz.shutdown();
        } catch (SchedulerException e) {
            logger.error("Failed to shutdown Quartz scheduler", e);
        }
    }

    @ConsumeEvent(value = Target.TARGET_JVM_DISCOVERY, blocking = true)
    @Transactional
    void onMessage(TargetDiscovery event) {
        if (event.serviceRef().id == null) {
            return;
        }
        switch (event.kind()) {
            case MODIFIED:
            case FOUND:
                if (event.serviceRef().isConnectable()) {
                    applyRulesToTarget(event.serviceRef());
                }
                break;
            case LOST:
                cancelJobsForTarget(event.serviceRef());
                break;
            default:
                break;
        }
    }

    @ConsumeEvent(value = Rule.RULE_ADDRESS, blocking = true)
    @Transactional
    public void handleRuleModification(RuleEvent event) {
        if (event.rule().id == null) {
            // rule is not persisted yet, skip and wait for an update after it is
            return;
        }
        switch (event.category()) {
            case CREATED:
            // fall-through
            case UPDATED:
                if (event.rule().enabled) {
                    applyRuleToMatchingTargets(event.rule());
                }
                break;
            default:
                break;
        }
    }

    @ConsumeEvent(value = Rule.RULE_ADDRESS + "?clean", blocking = true)
    @Transactional
    public void handleRuleRecordingCleanup(Rule rule) {
        var targets = evaluator.getMatchedTargets(rule.matchExpression);
        for (var target : targets) {
            scheduleCleanupJob(rule, target);
        }
    }

    @Retry(
            maxRetries = 12,
            delay = 1,
            delayUnit = ChronoUnit.SECONDS,
            maxDuration = 5,
            durationUnit = ChronoUnit.MINUTES,
            jitter = 200,
            retryOn = {Exception.class},
            abortOn = {
                NoResultException.class,
                NotFoundException.class,
                PermanentFailureException.class
            })
    @Bulkhead(value = 10, waitingTaskQueue = 255)
    @CircuitBreaker(
            requestVolumeThreshold = 10,
            failureRatio = 0.5,
            delay = 30,
            delayUnit = ChronoUnit.SECONDS,
            successThreshold = 3)
    @Timeout(value = 30, unit = ChronoUnit.SECONDS)
    @Asynchronous
    public Uni<Void> activateRule(String ruleName, String jvmId) {
        RuleActivationEvent event = new RuleActivationEvent();
        event.begin();
        event.ruleName = ruleName;
        event.jvmId = jvmId;

        try {
            return QuarkusTransaction.joiningExisting()
                    .call(
                            () -> {
                                var targetOpt = Target.getTargetByJvmId(jvmId);
                                if (targetOpt.isEmpty()) {
                                    logger.warnv(
                                            "Target with jvmId {0} no longer exists, aborting rule"
                                                    + " activation",
                                            jvmId);
                                    if (event.shouldCommit()) {
                                        event.success = false;
                                        event.permanentFailure = true;
                                        event.errorMessage = "Target not found";
                                        event.commit();
                                    }
                                    throw new PermanentFailureException(
                                            "Target with jvmId " + jvmId + " not found");
                                }
                                Target target = targetOpt.get();

                                var ruleOpt =
                                        Rule.<Rule>find("name", ruleName).firstResultOptional();
                                if (ruleOpt.isEmpty()) {
                                    logger.warnv(
                                            "Rule {0} no longer exists, aborting activation",
                                            ruleName);
                                    if (event.shouldCommit()) {
                                        event.success = false;
                                        event.permanentFailure = true;
                                        event.errorMessage = "Rule not found";
                                        event.commit();
                                    }
                                    throw new PermanentFailureException(
                                            "Rule " + ruleName + " not found");
                                }
                                Rule rule = ruleOpt.get();

                                return ruleExecutor
                                        .activate(target, rule)
                                        .onItem()
                                        .invoke(
                                                () -> {
                                                    if (event.shouldCommit()) {
                                                        event.success = true;
                                                        event.commit();
                                                    }
                                                })
                                        .onFailure()
                                        .invoke(
                                                t -> {
                                                    if (event.shouldCommit()) {
                                                        event.success = false;
                                                        event.errorMessage = t.getMessage();
                                                        event.commit();
                                                    }
                                                });
                            });
        } catch (PermanentFailureException e) {
            return Uni.createFrom().failure(e);
        } catch (Exception e) {
            if (event.shouldCommit()) {
                event.success = false;
                event.errorMessage = e.getMessage();
                event.commit();
            }
            return Uni.createFrom().failure(e);
        }
    }

    @Retry(
            maxRetries = 5,
            delay = 1,
            delayUnit = ChronoUnit.SECONDS,
            maxDuration = 2,
            durationUnit = ChronoUnit.MINUTES,
            jitter = 200,
            retryOn = {Exception.class},
            abortOn = {
                NoResultException.class,
                NotFoundException.class,
                PermanentFailureException.class
            })
    @Bulkhead(value = 10, waitingTaskQueue = 255)
    @CircuitBreaker(
            requestVolumeThreshold = 10,
            failureRatio = 0.5,
            delay = 30,
            delayUnit = ChronoUnit.SECONDS)
    @Timeout(value = 30, unit = ChronoUnit.SECONDS)
    @Asynchronous
    public Uni<Void> cleanupRecording(String ruleName, String jvmId, String recordingName) {
        RecordingCleanupEvent event = new RecordingCleanupEvent();
        event.begin();
        event.ruleName = ruleName;
        event.jvmId = jvmId;
        event.recordingName = recordingName;

        try {
            return QuarkusTransaction.joiningExisting()
                    .call(
                            () -> {
                                var targetOpt = Target.getTargetByJvmId(jvmId);
                                if (targetOpt.isEmpty()) {
                                    logger.infov(
                                            "Target with jvmId {0} no longer exists, cleanup"
                                                    + " complete",
                                            jvmId);
                                    if (event.shouldCommit()) {
                                        event.success = true;
                                        event.skipped = true;
                                        event.commit();
                                    }
                                    throw new PermanentFailureException(
                                            "Target not found - cleanup complete");
                                }
                                Target target = targetOpt.get();

                                var recordingOpt =
                                        recordingHelper.getActiveRecording(
                                                target, r -> Objects.equals(r.name, recordingName));

                                if (recordingOpt.isEmpty()) {
                                    logger.infov(
                                            "Recording \"{0}\" no longer exists on target {1},"
                                                    + " cleanup complete",
                                            recordingName, jvmId);
                                    if (event.shouldCommit()) {
                                        event.success = true;
                                        event.skipped = true;
                                        event.commit();
                                    }
                                    throw new PermanentFailureException(
                                            "Recording not found - cleanup complete");
                                }

                                return recordingHelper
                                        .stopRecording(recordingOpt.get())
                                        .onItem()
                                        .invoke(
                                                () -> {
                                                    if (event.shouldCommit()) {
                                                        event.success = true;
                                                        event.commit();
                                                    }
                                                })
                                        .onFailure()
                                        .invoke(
                                                t -> {
                                                    if (event.shouldCommit()) {
                                                        event.success = false;
                                                        event.errorMessage = t.getMessage();
                                                        event.commit();
                                                    }
                                                })
                                        .replaceWithVoid();
                            });
        } catch (PermanentFailureException e) {
            return Uni.createFrom().failure(e);
        } catch (Exception e) {
            if (event.shouldCommit()) {
                event.success = false;
                event.errorMessage = e.getMessage();
                event.commit();
            }
            return Uni.createFrom().failure(e);
        }
    }

    void applyRulesToTarget(Target target) {
        if (target.jvmId == null) {
            logger.warnv("Target {0} has no jvmId, cannot apply rules", target.connectUrl);
            return;
        }

        for (var rule : enabledRules()) {
            try {
                if (evaluator.applies(rule.matchExpression, target)) {
                    scheduleActivationJob(rule, target);
                }
            } catch (ScriptException se) {
                logger.error(se);
            }
        }
    }

    void applyRuleToMatchingTargets(Rule rule) {
        var targets = evaluator.getMatchedTargets(rule.matchExpression);
        for (var target : targets) {
            if (target.jvmId != null) {
                scheduleActivationJob(rule, target);
            } else {
                logger.warnv(
                        "Target {0} has no jvmId, cannot apply rule {1}",
                        target.connectUrl, rule.name);
            }
        }
    }

    private void scheduleActivationJob(Rule rule, Target target) {
        try {
            JobDetail job =
                    JobBuilder.newJob(RuleActivationJob.class)
                            .withIdentity(
                                    "activation-" + rule.name + "-" + target.jvmId,
                                    "rule-activations")
                            .usingJobData("ruleName", rule.name)
                            .usingJobData("jvmId", target.jvmId)
                            .build();

            if (quartz.checkExists(job.getKey())) {
                logger.debugv(
                        "Activation job already exists: rule={0} jvmId={1}",
                        rule.name, target.jvmId);
                return;
            }

            Trigger trigger =
                    TriggerBuilder.newTrigger()
                            .withIdentity(job.getKey().getName(), job.getKey().getGroup())
                            .startNow()
                            .build();

            quartz.scheduleJob(job, trigger);
            logger.debugv("Scheduled activation job: rule={0} jvmId={1}", rule.name, target.jvmId);

        } catch (SchedulerException e) {
            logger.errorv(
                    e,
                    "Failed to schedule activation job: rule={0} jvmId={1}",
                    rule.name,
                    target.jvmId);
        }
    }

    private void scheduleCleanupJob(Rule rule, Target target) {
        if (target.jvmId == null) {
            logger.warnv("Target {0} has no jvmId, cannot schedule cleanup", target.connectUrl);
            return;
        }

        try {
            JobDetail job =
                    JobBuilder.newJob(RecordingCleanupJob.class)
                            .withIdentity(
                                    "cleanup-" + rule.name + "-" + target.jvmId, "rule-cleanups")
                            .usingJobData("ruleName", rule.name)
                            .usingJobData("jvmId", target.jvmId)
                            .usingJobData("recordingName", rule.getRecordingName())
                            .build();

            if (quartz.checkExists(job.getKey())) {
                logger.debugv(
                        "Cleanup job already exists: rule={0} jvmId={1}", rule.name, target.jvmId);
                return;
            }

            Trigger trigger =
                    TriggerBuilder.newTrigger()
                            .withIdentity(job.getKey().getName(), job.getKey().getGroup())
                            .startNow()
                            .build();

            quartz.scheduleJob(job, trigger);
            logger.debugv("Scheduled cleanup job: rule={0} jvmId={1}", rule.name, target.jvmId);

        } catch (SchedulerException e) {
            logger.errorv(
                    e,
                    "Failed to schedule cleanup job: rule={0} jvmId={1}",
                    rule.name,
                    target.jvmId);
        }
    }

    private void cancelJobsForTarget(Target target) {
        if (target.jvmId == null) {
            return;
        }

        try {
            Set<JobKey> activationJobs =
                    quartz.getJobKeys(GroupMatcher.jobGroupEquals("rule-activations"));
            Set<JobKey> cleanupJobs =
                    quartz.getJobKeys(GroupMatcher.jobGroupEquals("rule-cleanups"));

            for (JobKey key : activationJobs) {
                if (key.getName().endsWith("-" + target.jvmId)) {
                    quartz.deleteJob(key);
                    logger.debugv(
                            "Cancelled activation job {0} for lost target {1}",
                            key.getName(), target.jvmId);
                }
            }

            for (JobKey key : cleanupJobs) {
                if (key.getName().endsWith("-" + target.jvmId)) {
                    quartz.deleteJob(key);
                    logger.debugv(
                            "Cancelled cleanup job {0} for lost target {1}",
                            key.getName(), target.jvmId);
                }
            }
        } catch (SchedulerException e) {
            logger.errorv(e, "Failed to cancel jobs for target {0}", target.jvmId);
        }
    }

    private static List<Rule> enabledRules() {
        return QuarkusTransaction.joiningExisting()
                .call(() -> Rule.<Rule>find("enabled", true).list())
                .stream()
                .filter(r -> r.id != null)
                .toList();
    }

    /**
     * Exception indicating a permanent failure that should not be retried. Used for cases like
     * target/rule not found where retrying won't help.
     *
     * <p>When thrown, SmallRye Fault Tolerance will abort retries and the Quartz job will be
     * unscheduled.
     */
    public static class PermanentFailureException extends RuntimeException {
        public PermanentFailureException(String message) {
            super(message);
        }
    }

    @Label("Rule Activation")
    @Description("Tracks rule activation attempts with success/failure status")
    @SuppressFBWarnings(
            value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD",
            justification = "JFR event fields are read by JFR infrastructure via reflection")
    public static class RuleActivationEvent extends Event {
        @Label("Rule Name")
        public String ruleName;

        @Label("JVM ID")
        public String jvmId;

        @Label("Success")
        public boolean success;

        @Label("Permanent Failure")
        public boolean permanentFailure;

        @Label("Error Message")
        public String errorMessage;
    }

    @Label("Recording Cleanup")
    @Description("Tracks recording cleanup attempts with success/failure status")
    @SuppressFBWarnings(
            value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD",
            justification = "JFR event fields are read by JFR infrastructure via reflection")
    public static class RecordingCleanupEvent extends Event {
        @Label("Rule Name")
        public String ruleName;

        @Label("JVM ID")
        public String jvmId;

        @Label("Recording Name")
        public String recordingName;

        @Label("Success")
        public boolean success;

        @Label("Skipped")
        public boolean skipped;

        @Label("Error Message")
        public String errorMessage;
    }
}
