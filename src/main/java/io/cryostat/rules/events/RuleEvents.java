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

import java.util.Map;
import java.util.Objects;

import io.cryostat.events.EntityCreatedEvent;
import io.cryostat.events.EntityDeletedEvent;
import io.cryostat.events.EntityUpdatedEvent;
import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.rules.Rule;

public class RuleEvents {

    public record RuleSnapshot(
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
            boolean enabled) {
        public RuleSnapshot {
            Objects.requireNonNull(name);
            Objects.requireNonNull(description);
            Objects.requireNonNull(eventSpecifier);
            Objects.requireNonNull(metadata);
            metadata = new Metadata(Map.copyOf(metadata.labels()));
        }
    }

    public static class RuleCreated extends EntityCreatedEvent<Rule, RuleSnapshot> {

        public RuleCreated(long id, RuleSnapshot snapshot) {
            super(id, snapshot);
        }

        @Override
        public String getCategory() {
            return "RuleCreated";
        }

        @Override
        public String getEntityType() {
            return "Rule";
        }
    }

    public static class RuleUpdated extends EntityUpdatedEvent<Rule, RuleSnapshot> {

        public RuleUpdated(long id, RuleSnapshot snapshot) {
            super(id, snapshot);
        }

        @Override
        public String getCategory() {
            return "RuleUpdated";
        }

        @Override
        public String getEntityType() {
            return "Rule";
        }
    }

    public static class RuleDeleted extends EntityDeletedEvent<Rule, RuleSnapshot> {

        public RuleDeleted(long id, RuleSnapshot snapshot) {
            super(id, snapshot);
        }

        @Override
        public String getCategory() {
            return "RuleDeleted";
        }

        @Override
        public String getEntityType() {
            return "Rule";
        }
    }
}
