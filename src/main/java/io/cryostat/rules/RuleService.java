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
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import io.cryostat.ConfigProperties;
import io.cryostat.expressions.MatchExpressionEvaluator;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.rules.Rule.RuleEvent;
import io.cryostat.targets.Target;
import io.cryostat.targets.Target.TargetDiscovery;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.projectnessie.cel.tools.ScriptException;
import org.quartz.SchedulerException;

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
    @Inject EventBus bus;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration connectionFailedTimeout;

    private final BlockingQueue<ActivationAttempt> activations =
            new PriorityBlockingQueue<>(255, Comparator.comparing(t -> t.attempts.get()));
    private final BlockingQueue<CleanupAttempt> cleanups =
            new PriorityBlockingQueue<>(255, Comparator.comparing(t -> t.attempts.get()));
    private final ExecutorService activator = Executors.newSingleThreadExecutor();
    private final ExecutorService cleaner = Executors.newSingleThreadExecutor();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    void onStart(@Observes StartupEvent ev) {
        logger.trace("RuleService started");
        activator.submit(
                () -> {
                    for (Rule rule : enabledRules()) {
                        try {
                            QuarkusTransaction.requiringNew()
                                    .run(() -> applyRuleToMatchingTargets(rule));
                        } catch (Exception e) {
                            logger.error(e);
                        }
                    }
                });
        activator.submit(
                () -> {
                    while (!activator.isShutdown()) {
                        ActivationAttempt attempt = null;
                        try {
                            attempt = activations.take();
                        } catch (InterruptedException ie) {
                            logger.trace(ie);
                            break;
                        }
                        final ActivationAttempt fAttempt = attempt;
                        workers.submit(() -> fireAttemptExecution(fAttempt));
                    }
                });
        cleaner.submit(
                () -> {
                    while (!cleaner.isShutdown()) {
                        CleanupAttempt attempt = null;
                        try {
                            attempt = cleanups.take();
                        } catch (InterruptedException ie) {
                            logger.trace(ie);
                            break;
                        }
                        final CleanupAttempt fAttempt = attempt;
                        workers.submit(() -> fireCleanupExecution(fAttempt));
                    }
                });
    }

    void onStop(@Observes ShutdownEvent evt) throws SchedulerException {
        activator.shutdown();
        cleaner.shutdown();
        activations.clear();
        cleanups.clear();
    }

    @ConsumeEvent(value = Target.TARGET_JVM_DISCOVERY, blocking = true)
    @Transactional
    void onMessage(TargetDiscovery event) {
        if (event.serviceRef().id == null) {
            // target is not persisted yet, skip and wait for an update after it is
            return;
        }
        switch (event.kind()) {
            case MODIFIED:
            // fall-through
            case FOUND:
                if (event.serviceRef().isConnectable()) {
                    applyRulesToTarget(event.serviceRef());
                }
                break;
            case LOST:
                resetActivations(event.serviceRef());
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
            CleanupAttempt attempt = new CleanupAttempt(rule, target);
            cleanups.add(attempt);
            logger.debugv(
                    "Queued cleanup for recording \"{0}\" on target {1}",
                    rule.getRecordingName(), target.id);
        }
    }

    private void fireAttemptExecution(ActivationAttempt fAttempt) {
        try {
            logger.tracev(
                    "Attempting to activate rule \"{0}\" for" + " target {1} - attempt #{2}",
                    fAttempt.ruleId, fAttempt.targetId, fAttempt.attempts);
            bus.request(RuleExecutor.class.getName(), fAttempt)
                    .await()
                    .atMost(connectionFailedTimeout);
            logger.tracev(
                    "Activated rule \"{0}\" for target {1}", fAttempt.ruleId, fAttempt.targetId);
        } catch (Exception e) {
            if (fAttempt != null) {
                int count = fAttempt.attempts.incrementAndGet();
                int delay = (int) Math.pow(2, count);
                TimeUnit unit = TimeUnit.SECONDS;
                int limit = 5;
                if (count < limit) {
                    logger.debugv(
                            "Rule \"{0}\" activation attempt"
                                    + " #{1} for target {2} failed,"
                                    + " rescheduling in {3}{4} ...",
                            fAttempt.ruleId, count - 1, fAttempt.targetId, delay, unit);
                    Infrastructure.getDefaultWorkerPool()
                            .schedule(() -> activations.add(fAttempt), delay, unit);
                } else {
                    logger.errorv(
                            "Rule \"{0}\" activation attempt"
                                    + " #{1} failed for target {2}"
                                    + " - limit ({3}) reached! Will"
                                    + " not retry...",
                            fAttempt.ruleId, count, fAttempt.targetId, limit);
                }
            }
            logger.error(e);
        }
    }

    private void fireCleanupExecution(CleanupAttempt fAttempt) {
        try {
            logger.tracev(
                    "Attempting to cleanup recording \"{0}\" on target {1} - attempt #{2}",
                    fAttempt.recordingName, fAttempt.targetId, fAttempt.attempts.get());

            QuarkusTransaction.requiringNew()
                    .run(
                            () -> {
                                var targetOpt =
                                        Target.<Target>find("id", fAttempt.targetId)
                                                .firstResultOptional();
                                if (targetOpt.isEmpty()) {
                                    logger.infov(
                                            "Target {0} no longer exists, cleanup complete",
                                            fAttempt.targetId);
                                    return;
                                }
                                Target target = targetOpt.get();

                                var recordingOpt =
                                        recordingHelper.getActiveRecording(
                                                target,
                                                r ->
                                                        Objects.equals(
                                                                r.name, fAttempt.recordingName));
                                if (recordingOpt.isEmpty()) {
                                    logger.infov(
                                            "Recording \"{0}\" no longer exists on target {1},"
                                                    + " cleanup complete",
                                            fAttempt.recordingName, fAttempt.targetId);
                                    return;
                                }

                                var recording = recordingOpt.get();
                                try {
                                    recordingHelper.stopRecording(recording).await().indefinitely();
                                    logger.infov(
                                            "Successfully stopped recording \"{0}\" on target {1}",
                                            fAttempt.recordingName, fAttempt.targetId);
                                } catch (Exception stopEx) {
                                    throw new RuntimeException(stopEx);
                                }
                            });
        } catch (Exception e) {
            if (fAttempt != null) {
                int count = fAttempt.attempts.incrementAndGet();
                int delay = (int) Math.pow(2, count);
                TimeUnit unit = TimeUnit.SECONDS;
                int limit = 5;

                // Check if this is a permanent failure (target/recording gone)
                boolean isPermanentFailure = isPermanentCleanupFailure(e);

                if (isPermanentFailure) {
                    logger.infov(
                            "Recording \"{0}\" cleanup on target {1} failed permanently: {2}."
                                    + " Will not retry.",
                            fAttempt.recordingName, fAttempt.targetId, e.getMessage());
                } else if (count < limit) {
                    logger.debugv(
                            "Recording \"{0}\" cleanup attempt #{1} on target {2} failed,"
                                    + " rescheduling in {3}{4} ...",
                            fAttempt.recordingName, count - 1, fAttempt.targetId, delay, unit);
                    Infrastructure.getDefaultWorkerPool()
                            .schedule(() -> cleanups.add(fAttempt), delay, unit);
                } else {
                    logger.errorv(
                            "Recording \"{0}\" cleanup attempt #{1} failed on target {2}"
                                    + " - limit ({3}) reached! Will not retry...",
                            fAttempt.recordingName, count, fAttempt.targetId, limit);
                }
            }
            if (!isPermanentCleanupFailure(e)) {
                logger.warn(e);
            }
        }
    }

    private boolean isPermanentCleanupFailure(Throwable t) {
        if (t == null) {
            return false;
        }
        // Target deleted or recording already stopped/deleted
        if (t instanceof NoResultException) {
            return true;
        }
        if (t instanceof NotFoundException) {
            return true;
        }
        if (t instanceof HttpException httpEx) {
            // 404 = recording not found, 400 = bad request (recording already stopped)
            return httpEx.getStatusCode() == 404 || httpEx.getStatusCode() == 400;
        }
        // Check cause recursively
        return isPermanentCleanupFailure(t.getCause());
    }

    private void resetActivations(Rule rule) {
        resetActivations(a -> a.ruleId == rule.id);
    }

    private void resetActivations(Target target) {
        resetActivations(a -> a.targetId == target.id);
        resetCleanups(c -> c.targetId == target.id);
    }

    private void resetActivations(Predicate<ActivationAttempt> p) {
        Iterator<ActivationAttempt> it = activations.iterator();
        while (it.hasNext()) {
            ActivationAttempt attempt = it.next();
            if (p.test(attempt)) {
                it.remove();
            }
        }
    }

    private void resetCleanups(Predicate<CleanupAttempt> p) {
        Iterator<CleanupAttempt> it = cleanups.iterator();
        while (it.hasNext()) {
            CleanupAttempt attempt = it.next();
            if (p.test(attempt)) {
                it.remove();
            }
        }
    }

    void applyRulesToTarget(Target target) {
        resetActivations(target);
        for (var rule : enabledRules()) {
            try {
                if (evaluator.applies(rule.matchExpression, target)) {
                    activations.add(new ActivationAttempt(rule, target));
                }
            } catch (ScriptException se) {
                logger.error(se);
            }
        }
    }

    private static List<Rule> enabledRules() {
        return QuarkusTransaction.joiningExisting()
                .call(() -> Rule.<Rule>find("enabled", true).list())
                .stream()
                .filter(r -> r.id != null)
                .toList();
    }

    void applyRuleToMatchingTargets(Rule r) {
        resetActivations(r);
        var targets = evaluator.getMatchedTargets(r.matchExpression);
        for (var target : targets) {
            activations.add(new ActivationAttempt(r, target));
        }
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public record RuleRecording(Rule rule, ActiveRecording recording) {
        public RuleRecording {
            Objects.requireNonNull(rule);
            Objects.requireNonNull(recording);
        }
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public record ActivationAttempt(long ruleId, long targetId, AtomicInteger attempts) {
        public ActivationAttempt(Rule rule, Target target) {
            this(rule.id, target.id, new AtomicInteger(0));
        }

        public ActivationAttempt {
            Objects.requireNonNull(attempts);
            if (ruleId < 0) {
                throw new IllegalArgumentException();
            }
            if (targetId < 0) {
                throw new IllegalArgumentException();
            }
            if (attempts.get() < 0) {
                throw new IllegalArgumentException();
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ActivationAttempt other)) return false;
            return ruleId == other.ruleId && targetId == other.targetId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(ruleId, targetId);
        }
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public record CleanupAttempt(
            String ruleName, long targetId, String recordingName, AtomicInteger attempts) {
        public CleanupAttempt(Rule rule, Target target) {
            this(rule.name, target.id, rule.getRecordingName(), new AtomicInteger(0));
        }

        public CleanupAttempt {
            Objects.requireNonNull(ruleName);
            Objects.requireNonNull(recordingName);
            Objects.requireNonNull(attempts);
            if (targetId < 0) {
                throw new IllegalArgumentException();
            }
            if (attempts.get() < 0) {
                throw new IllegalArgumentException();
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof CleanupAttempt other)) return false;
            return targetId == other.targetId && Objects.equals(recordingName, other.recordingName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(targetId, recordingName);
        }

        public int incrementAndGet() {
            return this.attempts.incrementAndGet();
        }
    }
}
