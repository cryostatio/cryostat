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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.script.ScriptException;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.flightrecorder.configuration.recording.RecordingOptionsBuilder;
import org.openjdk.jmc.rjmx.services.jfr.IRecordingDescriptor;
import org.openjdk.jmc.rjmx.services.jfr.IRecordingDescriptor.RecordingState;

import io.cryostat.core.templates.TemplateType;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.recordings.RecordingOptionsBuilderFactory;
import io.cryostat.recordings.Recordings;
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
import jakarta.ws.rs.BadRequestException;

import org.apache.commons.collections.MultiMap;
import org.apache.commons.collections.map.MultiValueMap;
import org.apache.commons.lang3.tuple.Pair;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RuleService {
  public static final String RULE_ADDRESS = "cryostat.rules.RuleService";

  @Inject
  Logger logger;
  @Inject
  MatchExpressionEvaluator evaluator;
  @Inject
  TargetConnectionManager connectionManager;
  @Inject
  RecordingOptionsBuilderFactory recordingOptionsBuilderFactory;
  @Inject
  RecordingHelper recordingHelper;
  @Inject
  EntityManager entityManager;
  @Inject
  ScheduledExecutorService executor;

  Map<Long, List<Pair<ScheduledFuture<?>, ActiveRecording>>> tasks = new ConcurrentHashMap<>();

  @ConsumeEvent(RULE_ADDRESS)
  @Blocking
  public void handleRuleModification(RuleEvent event) {
    Rule rule = event.getRule();
    switch (event.getCategory()) {
      case CREATED:
      case UPDATED:
        if (rule.enabled) {
          try (Stream<Target> targets = Target.streamAll()) {
            targets.filter(
                t -> {
                  try {
                    return evaluator.applies(rule.matchExpression, t);
                  } catch (ScriptException e) {
                    logger.error(e);
                    return false;
                  }
                })
                .forEach(
                    t -> {
                      try {
                        activate(rule, t);
                      } catch (Exception e) {
                        logger.error(e);
                      }
                    });
          }
        } else {

        }
        break;
      case DELETED:
        var ruleAssociatedTasks = tasks.get(rule.id);
        if (ruleAssociatedTasks != null) {
          ruleAssociatedTasks.forEach(pair -> pair.getLeft().cancel(true));
        }
        break;
      default:
        break;
    }
  }


  @ConsumeEvent(RULE_ADDRESS + "?clean")
  @Blocking
  @Transactional
  public void handleRuleRecordingCleanup(Rule rule) {
    var taskAndRecording = tasks.get(rule.id);
    if (taskAndRecording == null) {
      throw new IllegalStateException("No tasks associated with rule " + rule.id);
    }
    taskAndRecording.forEach(p -> {
      ActiveRecording recording = p.getRight();
      recording.state = RecordingState.STOPPED;
      recording.persist();
      // the DELETED case will handle the task cancellations + removal
    });
  }

  @Transactional
  public void activate(Rule rule, Target target) throws Exception {
    String recordingName = rule.getRecordingName();
    LinkedRecordingDescriptor descriptor = connectionManager.executeConnectedTask(
        target,
        connection -> {
          Optional<IRecordingDescriptor> previous = RecordingHelper.getDescriptorByName(connection, recordingName);
          if (previous.isPresent()) {
            throw new BadRequestException(
                String.format(
                    "Recording with name \"%s\" already exists",
                    recordingName));
          }

          RecordingOptionsBuilder optionsBuilder = recordingOptionsBuilderFactory
              .create(connection.getService())
              .name(recordingName);
          if (rule.maxAgeSeconds > 0) {
            optionsBuilder.maxAge(rule.maxAgeSeconds);
          }
          if (rule.maxSizeBytes > 0) {
            optionsBuilder.maxSize(rule.maxSizeBytes);
          }

          IConstrainedMap<String> recordingOptions = optionsBuilder.build();

          Pair<String, TemplateType> template = recordingHelper.parseEventSpecifierToTemplate(
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
              connection);
        });
    Target attachedTarget = entityManager.merge(target);

    ActiveRecording recording = ActiveRecording.from(target, descriptor);
    recording.persist();
    attachedTarget.activeRecordings.add(recording);
    attachedTarget.persist();

    if (rule.isArchiver()) {
      ScheduledArchiveTask task = new ScheduledArchiveTask(rule);
      ScheduledFuture<?> future = executor.scheduleAtFixedRate(task, rule.initialDelaySeconds,
          rule.archivalPeriodSeconds, TimeUnit.SECONDS);
      tasks.put(target.jvmId, Pair.of(future, recording));
    }
  }

  public void deactivate(Rule rule, Target target) {

  }
}
