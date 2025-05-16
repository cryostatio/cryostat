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
package io.cryostat.expressions;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import io.cryostat.targets.Target;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PrePersist;
import jakarta.validation.ValidationException;
import jakarta.validation.constraints.NotBlank;
import org.jboss.logging.Logger;
import org.projectnessie.cel.tools.ScriptException;

/**
 * Match Expressions contain small snippets of Common Expression Language and are used to evaluate
 * other resources' applicability to {@link io.cryostat.target.Target}s. {@link
 * io.cryostat.rules.Rule}s and {@link io.cryostat.credentials.Credential}s use Match Expressions to
 * determine the set of Targets they should apply to. When evaluating a Match Expression, Cryostat
 * passes a slightly slimmed down and read-only representation of the Target instance into the
 * expression context so that the expression can make assertions about the Target's properties.
 */
@Entity
@EntityListeners(MatchExpression.Listener.class)
public class MatchExpression extends PanacheEntity {
    public static final String EXPRESSION_ADDRESS = "io.cryostat.expressions.MatchExpression";

    @Column(updatable = false, nullable = false)
    @NotBlank
    // TODO
    // when serializing matchExpressions (ex. as a field of Rules), just use the script as the
    // serialized form of the expression object. This is for 2.x compat only
    @JsonValue
    public String script;

    MatchExpression() {
        this.script = null;
    }

    @JsonCreator
    public MatchExpression(String script) {
        this.script = script;
    }

    @ApplicationScoped
    public static class TargetMatcher {
        @Inject MatchExpressionEvaluator evaluator;
        @Inject Logger logger;

        public MatchedExpression match(MatchExpression expr, Collection<Target> targets)
                throws ScriptException {
            Set<Target> matches =
                    new HashSet<>(Optional.ofNullable(targets).orElseGet(() -> Set.of()));
            var it = matches.iterator();
            while (it.hasNext()) {
                if (!evaluator.applies(expr, it.next())) {
                    it.remove();
                }
            }
            return new MatchedExpression(expr, matches);
        }

        public MatchedExpression match(MatchExpression expr) throws ScriptException {
            return match(expr, Target.listAll());
        }
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public static record MatchedExpression(
            @Nullable Long id, String expression, Collection<Target> targets) {
        public MatchedExpression {
            Objects.requireNonNull(expression);
            Objects.requireNonNull(targets);
        }

        MatchedExpression(MatchExpression expr, Collection<Target> targets) {
            this(expr.id, expr.script, targets);
        }
    }

    @ApplicationScoped
    static class Listener {
        @Inject EventBus bus;
        @Inject MatchExpressionEvaluator evaluator;
        @Inject Logger logger;

        @PrePersist
        public void prePersist(MatchExpression expr) throws ValidationException {
            try {
                evaluator.createScript(expr.script);
            } catch (Exception e) {
                logger.error("Invalid match expression", e);
                throw new ValidationException(e);
            }
        }

        @PostPersist
        public void postPersist(MatchExpression expr) {
            bus.publish(
                    EXPRESSION_ADDRESS, new ExpressionEvent(ExpressionEventCategory.CREATED, expr));
            notify(ExpressionEventCategory.CREATED, expr);
        }

        @PostUpdate
        public void postUpdate(MatchExpression expr) {
            bus.publish(
                    EXPRESSION_ADDRESS, new ExpressionEvent(ExpressionEventCategory.UPDATED, expr));
            notify(ExpressionEventCategory.UPDATED, expr);
        }

        @PostRemove
        public void postRemove(MatchExpression expr) {
            bus.publish(
                    EXPRESSION_ADDRESS, new ExpressionEvent(ExpressionEventCategory.DELETED, expr));
            notify(ExpressionEventCategory.DELETED, expr);
        }

        private void notify(ExpressionEventCategory category, MatchExpression expr) {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(category.getCategory(), expr));
        }
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public record ExpressionEvent(ExpressionEventCategory category, MatchExpression expression) {
        public ExpressionEvent {
            Objects.requireNonNull(category);
            Objects.requireNonNull(expression);
        }
    }

    public enum ExpressionEventCategory {
        CREATED("ExpressionCreated"),
        UPDATED("ExpressionUpdated"),
        DELETED("ExpressionDeleted");

        private final String name;

        ExpressionEventCategory(String name) {
            this.name = name;
        }

        public String getCategory() {
            return name;
        }
    }
}
