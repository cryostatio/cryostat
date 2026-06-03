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

import io.cryostat.events.EntityNotificationObserver;
import io.cryostat.expressions.MatchExpression;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;

@ApplicationScoped
public class MatchExpressionNotificationObserver
        extends EntityNotificationObserver<MatchExpression> {

    void onMatchExpressionCreated(
            @Observes(during = TransactionPhase.AFTER_SUCCESS)
                    MatchExpressionEvents.MatchExpressionCreated event) {
        handleCreated(event);
    }

    void onMatchExpressionUpdated(
            @Observes(during = TransactionPhase.AFTER_SUCCESS)
                    MatchExpressionEvents.MatchExpressionUpdated event) {
        handleUpdated(event);
    }

    void onMatchExpressionDeleted(
            @Observes(during = TransactionPhase.AFTER_SUCCESS)
                    MatchExpressionEvents.MatchExpressionDeleted event) {
        handleDeleted(event);
    }

    @Override
    protected <S> Object buildCreatedPayload(S snapshot) {
        MatchExpressionEvents.MatchExpressionSnapshot expressionSnapshot =
                (MatchExpressionEvents.MatchExpressionSnapshot) snapshot;
        return new ExpressionPayload(expressionSnapshot.id(), expressionSnapshot.script());
    }

    @Override
    protected <S> Object buildUpdatedPayload(S snapshot) {
        MatchExpressionEvents.MatchExpressionSnapshot expressionSnapshot =
                (MatchExpressionEvents.MatchExpressionSnapshot) snapshot;
        return new ExpressionPayload(expressionSnapshot.id(), expressionSnapshot.script());
    }

    @Override
    protected <S> Object buildDeletedPayload(S snapshot) {
        MatchExpressionEvents.MatchExpressionSnapshot expressionSnapshot =
                (MatchExpressionEvents.MatchExpressionSnapshot) snapshot;
        return new ExpressionPayload(expressionSnapshot.id(), expressionSnapshot.script());
    }

    public record ExpressionPayload(long id, String script) {}
}
