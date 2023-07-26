/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.cryostat.rules;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.common.unit.QuantityConversionException;
import org.openjdk.jmc.flightrecorder.configuration.recording.RecordingOptionsBuilder;
import org.openjdk.jmc.rjmx.ConnectionException;
import org.openjdk.jmc.rjmx.ServiceNotAvailableException;

import io.cryostat.core.net.JFRConnection;
import io.cryostat.core.templates.TemplateType;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.recordings.RecordingHelper.RecordingReplace;
import io.cryostat.recordings.RecordingOptionsBuilderFactory;
import io.cryostat.recordings.Recordings.LinkedRecordingDescriptor;
import io.cryostat.recordings.Recordings.Metadata;
import io.cryostat.rules.Rule.RuleEvent;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;

import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jdk.jfr.RecordingState;
import org.apache.commons.lang3.tuple.Pair;
import org.jboss.logging.Logger;
import org.projectnessie.cel.tools.ScriptException;

@ApplicationScoped
public class RuleService {

    @Inject Logger logger;
    @Inject MatchExpressionEvaluator evaluator;
    @Inject TargetConnectionManager connectionManager;
    @Inject RecordingOptionsBuilderFactory recordingOptionsBuilderFactory;
    @Inject RecordingHelper recordingHelper;
    @Inject EntityManager entityManager;
    @Inject ScheduledExecutorService executor;
    @Inject ScheduleArchiveTaskBuilder scheduledTaskBuilderFactory;

    Map<Long, CopyOnWriteArrayList<RuleRecording>> ruleMap = new ConcurrentHashMap<>();

    @ConsumeEvent(Rule.RULE_ADDRESS)
    @Blocking
    public void handleRuleModification(RuleEvent event) {
        Rule rule = event.rule();
        List<RuleRecording> ruleRecordings = ruleMap.get(rule.id);
        switch (event.category()) {
            case CREATED:
                if (!ruleMap.containsKey(rule.id)) {
                    ruleMap.put(rule.id, new CopyOnWriteArrayList<>());
                }
                if (rule.enabled) {
                    applyRuleToMatchingTargets(rule);
                }
                break;
            case UPDATED:
                if (rule.enabled) {
                    ruleRecordings.clear();
                    applyRuleToMatchingTargets(rule);
                } else {
                    cancelTasks(ruleRecordings);
                }
                break;
            case DELETED:
                cancelTasks(ruleRecordings);
                ruleRecordings.clear();
                ruleMap.remove(rule.id);
                break;
            default:
                break;
        }
    }

    @ConsumeEvent(Rule.RULE_ADDRESS + "?clean")
    @Blocking
    @Transactional
    public void handleRuleRecordingCleanup(Rule rule) {
        List<RuleRecording> ruleRecordings = ruleMap.get(rule.id);
        if (ruleRecordings == null) {
            throw new IllegalStateException("No tasks associated with rule " + rule.id);
        }
        ruleRecordings.forEach(
                p -> {
                    ActiveRecording attachedRecoding = entityManager.merge(p.getRecording());
                    attachedRecoding.state = RecordingState.STOPPED;
                    attachedRecoding.persist();
                    // the RULE_ADDRESS will handle the task cancellations + removal
                });
    }

    @Transactional
    public void activate(Rule rule, Target target) throws Exception {
        LinkedRecordingDescriptor descriptor =
                connectionManager.executeConnectedTask(
                        target,
                        connection -> {
                            var recordingOptions = createRecordingOptions(rule, connection);

                            Pair<String, TemplateType> template =
                                    recordingHelper.parseEventSpecifierToTemplate(
                                            rule.eventSpecifier);

                            Map<String, String> labels = new HashMap<>();
                            labels.put("rule", rule.name);
                            Metadata meta = new Metadata(labels);
                            return recordingHelper.startRecording(
                                    target,
                                    recordingOptions,
                                    template.getLeft(),
                                    template.getRight(),
                                    meta,
                                    false,
                                    RecordingReplace.STOPPED,
                                    connection);
                        });
        Target attachedTarget = entityManager.merge(target);

        ActiveRecording recording = ActiveRecording.from(target, descriptor);
        recording.persist();
        attachedTarget.activeRecordings.add(recording);
        attachedTarget.persist();

        if (rule.isArchiver()) {
            scheduleArchival(rule, attachedTarget, recording);
        } else {
            List<RuleRecording> ruleRecordings = ruleMap.get(rule.id);
            ruleRecordings.add(new RuleRecording(recording));
        }
    }

    private IConstrainedMap<String> createRecordingOptions(Rule rule, JFRConnection connection)
            throws ConnectionException, QuantityConversionException, IOException,
                    ServiceNotAvailableException {
        RecordingOptionsBuilder optionsBuilder =
                recordingOptionsBuilderFactory
                        .create(connection.getService())
                        .name(rule.getRecordingName());
        if (rule.maxAgeSeconds > 0) {
            optionsBuilder.maxAge(rule.maxAgeSeconds);
        }
        if (rule.maxSizeBytes > 0) {
            optionsBuilder.maxSize(rule.maxSizeBytes);
        }
        return optionsBuilder.build();
    }

    private void applyRuleToMatchingTargets(Rule rule) {
        try (Stream<Target> targets = Target.streamAll()) {
            targets.filter(
                            target -> {
                                try {
                                    return evaluator.applies(rule.matchExpression, target);
                                } catch (ScriptException e) {
                                    logger.error(e);
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
        int initialDelay = rule.initialDelaySeconds;
        int archivalPeriodSeconds = rule.archivalPeriodSeconds;
        if (initialDelay <= 0) {
            initialDelay = archivalPeriodSeconds;
        }

        ScheduledArchiveTask task = scheduledTaskBuilderFactory.build(rule, target, recording);
        ScheduledFuture<?> future =
                executor.scheduleAtFixedRate(
                        task, initialDelay, archivalPeriodSeconds, TimeUnit.SECONDS);

        List<RuleRecording> ruleRecordings = ruleMap.get(rule.id);
        ruleRecordings.add(new RuleRecording(recording, future));
    }

    private void cancelTasks(List<RuleRecording> ruleRecordings) {
        ruleRecordings.forEach(
                rr -> {
                    if (rr.getFuture().isPresent()) {
                        rr.getFuture().get().cancel(true);
                    }
                });
    }

    static class RuleRecording {
        private ActiveRecording recording;
        private Optional<ScheduledFuture<?>> future;

        RuleRecording(ActiveRecording recording, ScheduledFuture<?> future) {
            this.recording = recording;
            this.future = Optional.ofNullable(future);
        }

        RuleRecording(ActiveRecording recording) {
            this.recording = recording;
            this.future = Optional.empty();
        }

        public ActiveRecording getRecording() {
            return recording;
        }

        public Optional<ScheduledFuture<?>> getFuture() {
            return future;
        }
    }
}
