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
package io.cryostat.rules.events;

import io.cryostat.events.EntityNotificationObserver;
import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.rules.Rule;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;

@ApplicationScoped
public class RuleNotificationObserver extends EntityNotificationObserver.Simple<Rule> {

    void onRuleCreated(
            @Observes(during = TransactionPhase.AFTER_SUCCESS) RuleEvents.RuleCreated event) {
        handleCreated(event);
    }

    void onRuleUpdated(
            @Observes(during = TransactionPhase.AFTER_SUCCESS) RuleEvents.RuleUpdated event) {
        handleUpdated(event);
    }

    void onRuleDeleted(
            @Observes(during = TransactionPhase.AFTER_SUCCESS) RuleEvents.RuleDeleted event) {
        handleDeleted(event);
    }

    @Override
    protected <S> Object buildPayload(S snapshot) {
        RuleEvents.RuleSnapshot ruleSnapshot = (RuleEvents.RuleSnapshot) snapshot;
        return new RulePayload(
                ruleSnapshot.id(),
                ruleSnapshot.name(),
                ruleSnapshot.description(),
                ruleSnapshot.matchExpressionId(),
                ruleSnapshot.eventSpecifier(),
                ruleSnapshot.archivalPeriodSeconds(),
                ruleSnapshot.initialDelaySeconds(),
                ruleSnapshot.preservedArchives(),
                ruleSnapshot.maxAgeSeconds(),
                ruleSnapshot.maxSizeBytes(),
                ruleSnapshot.metadata(),
                ruleSnapshot.enabled());
    }

    public record RulePayload(
            long id,
            String name,
            String description,
            long matchExpressionId,
            String eventSpecifier,
            int archivalPeriodSeconds,
            int initialDelaySeconds,
            int preservedArchives,
            int maxAgeSeconds,
            int maxSizeBytes,
            Metadata metadata,
            boolean enabled) {}
}
