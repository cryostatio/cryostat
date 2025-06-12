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
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.apache.commons.lang3.StringUtils;
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
    private final ExecutorService activator = Executors.newSingleThreadExecutor();

    void onStart(@Observes StartupEvent ev) {
        logger.trace("RuleService started");
        activator.submit(
                () -> {
                    while (!activator.isShutdown()) {
                        ActivationAttempt attempt = null;
                        try {
                            attempt = activations.take();
                            logger.tracev(
                                    "Attempting to activate rule \"{0}\" for target {1} -"
                                            + " attempt #{2}",
                                    attempt.rule.name, attempt.target.connectUrl, attempt.attempts);
                            bus.requestAndAwait(RuleExecutor.class.getName(), attempt);
                        } catch (InterruptedException ie) {
                            logger.trace(ie);
                            break;
                        } catch (Exception e) {
                            if (attempt != null) {
                                final ActivationAttempt fAttempt = attempt;
                                int count = attempt.incrementAndGet();
                                int delay = (int) Math.pow(2, count);
                                TimeUnit unit = TimeUnit.SECONDS;
                                int limit = 5;
                                if (count < limit) {
                                    logger.debugv(
                                            "Rule \"{0}\" activation attempt #{1} for target"
                                                    + " {2} failed, rescheduling in {3}{4} ...",
                                            attempt.rule.name,
                                            count - 1,
                                            attempt.target.connectUrl,
                                            delay,
                                            unit);
                                    Infrastructure.getDefaultWorkerPool()
                                            .schedule(() -> activations.add(fAttempt), delay, unit);
                                } else {
                                    logger.errorv(
                                            "Rule \"{0}\" activation attempt #{1} failed for"
                                                    + " target {2} - limit ({3}) reached! Will not"
                                                    + " retry...",
                                            attempt.rule.name,
                                            count,
                                            attempt.target.connectUrl,
                                            limit);
                                }
                            }
                            logger.error(e);
                        }
                    }
                });
        QuarkusTransaction.joiningExisting()
                .run(
                        () ->
                                Rule.<Rule>streamAll()
                                        .filter(r -> r.enabled)
                                        .forEach(this::applyRuleToMatchingTargets));
    }

    void onStop(@Observes ShutdownEvent evt) throws SchedulerException {
        activator.shutdown();
        activations.clear();
    }

    @ConsumeEvent(value = Target.TARGET_JVM_DISCOVERY, blocking = true)
    void onMessage(TargetDiscovery event) {
        switch (event.kind()) {
            case MODIFIED:
            // fall-through
            case FOUND:
                if (StringUtils.isBlank(event.serviceRef().jvmId)) {
                    break;
                }
                applyRulesToTarget(event.serviceRef());
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
        Rule rule = event.rule();
        switch (event.category()) {
            case CREATED:
            // fall-through
            case UPDATED:
                if (rule.enabled) {
                    applyRuleToMatchingTargets(rule);
                }
                break;
            default:
                break;
        }
    }

    @ConsumeEvent(value = Rule.RULE_ADDRESS + "?clean", blocking = true)
    @Transactional
    public void handleRuleRecordingCleanup(Rule rule) {
        var targets =
                evaluator.getMatchedTargets(rule.matchExpression).stream()
                        .collect(Collectors.toList());
        for (var target : targets) {
            recordingHelper
                    .getActiveRecording(
                            target, r -> Objects.equals(r.name, rule.getRecordingName()))
                    .ifPresent(
                            recording -> {
                                try {
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

    private void resetActivations(Rule rule) {
        Iterator<ActivationAttempt> it = activations.iterator();
        while (it.hasNext()) {
            ActivationAttempt attempt = it.next();
            if (attempt.rule.equals(rule)) {
                it.remove();
            }
        }
    }

    private void resetActivations(Target target) {
        Iterator<ActivationAttempt> it = activations.iterator();
        while (it.hasNext()) {
            ActivationAttempt attempt = it.next();
            if (attempt.target.equals(target)) {
                it.remove();
            }
        }
    }

    void applyRulesToTarget(Target target) {
        resetActivations(target);
        for (var rule : Rule.<Rule>find("enabled", true).list()) {
            try {
                if (!evaluator.applies(rule.matchExpression, target)) {
                    continue;
                }
                activations.add(new ActivationAttempt(rule, target));
            } catch (ScriptException se) {
                logger.error(se);
            }
        }
    }

    void applyRuleToMatchingTargets(Rule rule) {
        resetActivations(rule);
        var targets = evaluator.getMatchedTargets(rule.matchExpression);
        for (var target : targets) {
            activations.add(new ActivationAttempt(rule, target));
        }
    }

    @SuppressFBWarnings(value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"})
    public record RuleRecording(Rule rule, ActiveRecording recording) {
        public RuleRecording {
            Objects.requireNonNull(rule);
            Objects.requireNonNull(recording);
        }
    }

    @SuppressFBWarnings(value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"})
    public record ActivationAttempt(Rule rule, Target target, AtomicInteger attempts) {
        public ActivationAttempt(Rule rule, Target target) {
            this(rule, target, new AtomicInteger(0));
        }

        public ActivationAttempt {
            Objects.requireNonNull(rule);
            Objects.requireNonNull(target);
            Objects.requireNonNull(attempts);
            if (attempts.get() < 0) {
                throw new IllegalArgumentException();
            }
        }

        public int incrementAndGet() {
            return this.attempts.incrementAndGet();
        }
    }
}
