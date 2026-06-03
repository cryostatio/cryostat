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
package io.cryostat.expressions.events;

import java.util.Objects;

import io.cryostat.events.EntityCreatedEvent;
import io.cryostat.events.EntityDeletedEvent;
import io.cryostat.events.EntityUpdatedEvent;
import io.cryostat.expressions.MatchExpression;

public class MatchExpressionEvents {

    public record MatchExpressionSnapshot(long id, String script) {
        public MatchExpressionSnapshot {
            Objects.requireNonNull(script);
        }
    }

    public static class MatchExpressionCreated
            extends EntityCreatedEvent<MatchExpression, MatchExpressionSnapshot> {

        public MatchExpressionCreated(long id, MatchExpressionSnapshot snapshot) {
            super(id, snapshot);
        }

        @Override
        public String getCategory() {
            return "ExpressionCreated";
        }

        @Override
        public String getEntityType() {
            return "MatchExpression";
        }
    }

    public static class MatchExpressionUpdated
            extends EntityUpdatedEvent<MatchExpression, MatchExpressionSnapshot> {

        public MatchExpressionUpdated(long id, MatchExpressionSnapshot snapshot) {
            super(id, snapshot);
        }

        @Override
        public String getCategory() {
            return "ExpressionUpdated";
        }

        @Override
        public String getEntityType() {
            return "MatchExpression";
        }
    }

    public static class MatchExpressionDeleted
            extends EntityDeletedEvent<MatchExpression, MatchExpressionSnapshot> {

        public MatchExpressionDeleted(long id, MatchExpressionSnapshot snapshot) {
            super(id, snapshot);
        }

        @Override
        public String getCategory() {
            return "ExpressionDeleted";
        }

        @Override
        public String getEntityType() {
            return "MatchExpression";
        }
    }
}
