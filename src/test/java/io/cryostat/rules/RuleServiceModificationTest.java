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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import io.cryostat.expressions.MatchExpression;
import io.cryostat.expressions.MatchExpressionEvaluator;
import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.rules.Rule.RuleEvent;
import io.cryostat.rules.Rule.RuleEventCategory;
import io.cryostat.targets.Target;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.Trigger;

/**
 * Re-activating a rule stops and re-creates its recording. That is only warranted when the edit
 * changes what the recording is or which Targets it belongs on. Edits that only concern the
 * archival schedule are carried into the existing archival job by the RuleExecutor, so they must
 * not queue an activation. Rule creation and enabling are unaffected.
 */
public class RuleServiceModificationTest {

    Scheduler quartz;
    MatchExpressionEvaluator evaluator;
    RuleService service;
    Rule rule;

    @BeforeEach
    void setUp() throws Exception {
        quartz = mock(Scheduler.class);
        evaluator = mock(MatchExpressionEvaluator.class);

        service = new RuleService();
        service.logger = Logger.getLogger(RuleService.class);
        service.quartz = quartz;
        service.evaluator = evaluator;

        Target target = new Target();
        target.jvmId = "test-jvm-id";
        target.alias = "test-target";
        when(evaluator.getMatchedTargets(any())).thenReturn(List.of(target));

        rule = new Rule();
        rule.id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        rule.name = "test_rule";
        rule.description = "";
        rule.matchExpression = new MatchExpression("true");
        rule.eventSpecifier = "template=Continuous,type=TARGET";
        rule.archivalPeriodSeconds = 60;
        rule.preservedArchives = 3;
        rule.metadata = new Metadata(new HashMap<>());
        rule.enabled = true;
    }

    @Test
    void updateThatDoesNotAffectRecordingDoesNotActivateRule() throws Exception {
        service.handleRuleModification(new RuleEvent(RuleEventCategory.UPDATED, rule, false));

        verify(quartz, never()).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    @Test
    void updateThatAffectsRecordingActivatesRule() throws Exception {
        service.handleRuleModification(new RuleEvent(RuleEventCategory.UPDATED, rule, true));

        verify(quartz).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    @Test
    void createdRuleIsActivated() throws Exception {
        service.handleRuleModification(new RuleEvent(RuleEventCategory.CREATED, rule, true));

        verify(quartz).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    @Test
    void updateOfDisabledRuleDoesNotActivateRule() throws Exception {
        rule.enabled = false;

        service.handleRuleModification(new RuleEvent(RuleEventCategory.UPDATED, rule, true));

        verify(quartz, never()).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }
}
