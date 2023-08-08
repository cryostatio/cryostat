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
import java.util.Set;

import io.cryostat.targets.Target;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.PrePersist;
import jakarta.validation.ValidationException;
import jakarta.validation.constraints.NotBlank;
import org.jboss.logging.Logger;
import org.projectnessie.cel.tools.ScriptException;

@Entity
@EntityListeners(MatchExpression.Listener.class)
public class MatchExpression extends PanacheEntity {

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
    static class TargetMatcher {
        @Inject MatchExpressionEvaluator evaluator;
        @Inject Logger logger;

        MatchedExpression match(MatchExpression expr, Collection<Target> targets)
                throws ScriptException {
            Set<Target> matches = new HashSet<>(targets);
            var it = matches.iterator();
            while (it.hasNext()) {
                if (!evaluator.applies(expr, it.next())) {
                    it.remove();
                }
            }
            return new MatchedExpression(expr, matches);
        }

        MatchedExpression match(MatchExpression expr) throws ScriptException {
            return match(expr, Target.listAll());
        }
    }

    public static record MatchedExpression(
            @Nullable Long id, String expression, Collection<Target> targets) {
        MatchedExpression(MatchExpression expr, Collection<Target> targets) {
            this(expr.id, expr.script, targets);
        }
    }

    @ApplicationScoped
    static class Listener {
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
    }
}
