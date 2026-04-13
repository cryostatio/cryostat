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
package io.cryostat.targets.events;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

import io.cryostat.events.EntityCreatedEvent;
import io.cryostat.events.EntityDeletedEvent;
import io.cryostat.events.EntityUpdatedEvent;
import io.cryostat.targets.Target;

public class TargetEvents {

    public record TargetSnapshot(
            long id,
            URI connectUrl,
            String alias,
            String jvmId,
            Map<String, String> labels,
            Target.Annotations annotations) {
        public TargetSnapshot {
            Objects.requireNonNull(connectUrl);
            Objects.requireNonNull(alias);
            Objects.requireNonNull(labels);
            Objects.requireNonNull(annotations);
            // Defensive copies to prevent external modification
            labels = Map.copyOf(labels);
            annotations =
                    new Target.Annotations(
                            Map.copyOf(annotations.platform()), Map.copyOf(annotations.cryostat()));
        }
    }

    public static class TargetCreated extends EntityCreatedEvent<Target, TargetSnapshot> {
        public TargetCreated(TargetSnapshot snapshot) {
            super(snapshot.id(), snapshot);
        }

        @Override
        public String getCategory() {
            return Target.TARGET_JVM_DISCOVERY;
        }

        @Override
        public String getEntityType() {
            return "Target";
        }
    }

    public static class TargetUpdated extends EntityUpdatedEvent<Target, TargetSnapshot> {
        public TargetUpdated(TargetSnapshot snapshot) {
            super(snapshot.id(), snapshot);
        }

        @Override
        public String getCategory() {
            return Target.TARGET_JVM_DISCOVERY;
        }

        @Override
        public String getEntityType() {
            return "Target";
        }
    }

    public static class TargetDeleted extends EntityDeletedEvent<Target, TargetSnapshot> {
        public TargetDeleted(TargetSnapshot snapshot) {
            super(snapshot.id(), snapshot);
        }

        @Override
        public String getCategory() {
            return Target.TARGET_JVM_DISCOVERY;
        }

        @Override
        public String getEntityType() {
            return "Target";
        }
    }
}
