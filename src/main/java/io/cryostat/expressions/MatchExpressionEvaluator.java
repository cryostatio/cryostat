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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.cryostat.rules.Rule;
import io.cryostat.rules.Rule.RuleEvent;
import io.cryostat.targets.Target;

import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheInvalidate;
import io.quarkus.cache.CacheManager;
import io.quarkus.cache.CacheResult;
import io.quarkus.cache.CaffeineCache;
import io.quarkus.cache.CompositeCacheKey;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import org.jboss.logging.Logger;
import org.projectnessie.cel.checker.Decls;
import org.projectnessie.cel.tools.Script;
import org.projectnessie.cel.tools.ScriptCreateException;
import org.projectnessie.cel.tools.ScriptException;
import org.projectnessie.cel.tools.ScriptHost;

@ApplicationScoped
public class MatchExpressionEvaluator {

    private static final String CACHE_NAME = "match-expression-cache";

    @Inject ScriptHost scriptHost;
    @Inject Logger logger;
    @Inject CacheManager cacheManager;

    @Transactional
    @Blocking
    @ConsumeEvent(Rule.RULE_ADDRESS)
    void onMessage(RuleEvent event) {
        switch (event.category()) {
            case CREATED:
                break;
            case DELETED:
                invalidate(event.rule().matchExpression.script);
                break;
            case UPDATED:
                break;
            default:
                break;
        }
    }

    Script createScript(String matchExpression) throws ScriptCreateException {
        ScriptCreationEvent evt = new ScriptCreationEvent();
        try {
            evt.begin();
            return scriptHost
                    .buildScript(matchExpression)
                    .withDeclarations(
                            Decls.newVar("target", Decls.newObjectType(Target.class.getName())))
                    .withTypes(Target.class)
                    .build();
        } finally {
            evt.end();
            if (evt.shouldCommit()) {
                evt.commit();
            }
        }
    }

    @CacheResult(cacheName = CACHE_NAME)
    boolean load(String matchExpression, Target serviceRef) throws ScriptException {
        Script script = createScript(matchExpression);
        return script.execute(Boolean.class, Map.of("target", serviceRef));
    }

    @CacheInvalidate(cacheName = CACHE_NAME)
    void invalidate(String matchExpression, Target serviceRef) {}

    void invalidate(String matchExpression) {
        Optional<Cache> cache = cacheManager.getCache(CACHE_NAME);
        if (cache.isPresent()) {
            var it = cache.get().as(CaffeineCache.class).keySet().iterator();
            while (it.hasNext()) {
                CompositeCacheKey entry = (CompositeCacheKey) it.next();
                String matchExpressionKey = (String) entry.getKeyElements()[0];
                if (Objects.equals(matchExpression, matchExpressionKey)) {
                    cache.get()
                            .invalidate(entry)
                            .invoke(
                                    () -> {
                                        logger.debugv(
                                                "Expression {0} invalidated in cache {1}",
                                                matchExpression, CACHE_NAME);
                                    })
                            .subscribe()
                            .with(
                                    (v) -> {},
                                    (e) -> {
                                        logger.errorv(
                                                "Error invalidating expression {0} in cache {1}:"
                                                        + " {2}",
                                                matchExpression, CACHE_NAME, e);
                                    });
                }
            }
        }
    }

    public boolean applies(MatchExpression matchExpression, Target target) throws ScriptException {
        MatchExpressionAppliesEvent evt = new MatchExpressionAppliesEvent(matchExpression);
        try {
            evt.begin();
            return load(matchExpression.script, target);
        } catch (CompletionException e) {
            if (e.getCause() instanceof ScriptException) {
                throw (ScriptException) e.getCause();
            }
            throw e;
        } finally {
            evt.end();
            if (evt.shouldCommit()) {
                evt.commit();
            }
        }
    }

    public List<Target> getMatchedTargets(MatchExpression matchExpression) {
        try (Stream<Target> targets = Target.streamAll()) {
            return targets.filter(
                            target -> {
                                try {
                                    return applies(matchExpression, target);
                                } catch (ScriptException e) {
                                    logger.error(
                                            "Error while processing expression: " + matchExpression,
                                            e);
                                    return false;
                                }
                            })
                    .collect(Collectors.toList());
        }
    }

    @Name("io.cryostat.rules.MatchExpressionEvaluator.MatchExpressionAppliesEvent")
    @Label("Match Expression Evaluation")
    @Category("Cryostat")
    // @SuppressFBWarnings(
    //         value = "URF_UNREAD_FIELD",
    //         justification = "The event fields are recorded with JFR instead of accessed
    // directly")
    public static class MatchExpressionAppliesEvent extends Event {

        String matchExpression;

        MatchExpressionAppliesEvent(MatchExpression matchExpression) {
            this.matchExpression = matchExpression.script;
        }
    }

    @Name("io.cryostat.rules.MatchExpressionEvaluator.ScriptCreationEvent")
    @Label("Match Expression Script Creation")
    @Category("Cryostat")
    // @SuppressFBWarnings(
    //         value = "URF_UNREAD_FIELD",
    //         justification = "The event fields are recorded with JFR instead of accessed
    // directly")
    public static class ScriptCreationEvent extends Event {}
}
