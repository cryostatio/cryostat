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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
                invalidate(event.rule().matchExpression);
                break;
            case UPDATED:
                break;
            default:
                break;
        }
    }

    private Script createScript(String matchExpression) throws ScriptCreateException {
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

    public boolean applies(String matchExpression, Target target) throws ScriptException {
        MatchExpressionAppliesEvent evt = new MatchExpressionAppliesEvent(matchExpression);
        try {
            evt.begin();
            return load(matchExpression, target);
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

    public List<Target> getMatchedTargets(String matchExpression) {
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

        MatchExpressionAppliesEvent(String matchExpression) {
            this.matchExpression = matchExpression;
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
