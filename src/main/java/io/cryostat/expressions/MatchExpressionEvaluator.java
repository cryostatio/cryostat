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

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import io.cryostat.expressions.MatchExpression.ExpressionEvent;
import io.cryostat.targets.Target;
import io.cryostat.targets.Target.Annotations;
import io.cryostat.targets.Target.TargetDiscovery;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.cache.CacheManager;
import io.quarkus.cache.CacheResult;
import io.quarkus.cache.CompositeCacheKey;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.Nullable;
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

    private static final String CACHE_NAME = "matchexpressions";

    @Inject ScriptHost scriptHost;
    @Inject Logger logger;
    @Inject CacheManager cacheManager;

    @ConsumeEvent(value = MatchExpression.EXPRESSION_ADDRESS, blocking = true)
    void onMessage(ExpressionEvent event) {
        switch (event.category()) {
            case CREATED:
                break;
            case DELETED:
                invalidate(event.expression().script);
                break;
            case UPDATED:
                // expression scripts aren't meant to be updatable, but handle them by invalidating
                // cached results just in case
                invalidate(event.expression().script);
                break;
            default:
                break;
        }
    }

    @Transactional
    @Blocking
    @ConsumeEvent(value = Target.TARGET_JVM_DISCOVERY, blocking = true)
    void onMessage(TargetDiscovery event) {
        var target = Target.<Target>find("id", event.serviceRef().id).singleResultOptional();
        switch (event.kind()) {
            case LOST:
            // fall-through
            case FOUND:
            // fall-through
            case MODIFIED:
                target.ifPresent(this::invalidate);
                break;
            default:
                // no-op
                break;
        }
    }

    Script createScript(String matchExpression) throws ScriptCreateException {
        ScriptCreation evt = new ScriptCreation();
        try {
            evt.begin();
            return scriptHost
                    .buildScript(matchExpression)
                    .withTypes(SimplifiedTarget.class)
                    .withDeclarations(
                            Decls.newVar(
                                    "target",
                                    Decls.newObjectType(SimplifiedTarget.class.getName())))
                    .build();
        } finally {
            evt.end();
            if (evt.shouldCommit()) {
                evt.commit();
            }
        }
    }

    @CacheResult(cacheName = CACHE_NAME)
    boolean load(String matchExpression, Target target) throws ScriptException {
        Script script = createScript(matchExpression);
        return script.execute(Boolean.class, Map.of("target", SimplifiedTarget.from(target)));
    }

    void invalidate(String matchExpression) {
        var cache = cacheManager.getCache(CACHE_NAME).orElseThrow();
        // 0-index is important here. the argument order of the load() method determines the
        // composite key order
        cache.invalidateIf(
                        k ->
                                Objects.equals(
                                        (String) ((CompositeCacheKey) k).getKeyElements()[0],
                                        matchExpression))
                .subscribe()
                .with((v) -> {}, logger::warn);
    }

    void invalidate(Target target) {
        var cache = cacheManager.getCache(CACHE_NAME).orElseThrow();
        // 0-index is important here. the argument order of the load() method determines the
        // composite key order
        cache.invalidateIf(
                        k ->
                                Objects.equals(
                                        (Target) ((CompositeCacheKey) k).getKeyElements()[1],
                                        target))
                .subscribe()
                .with((v) -> {}, logger::warn);
    }

    public boolean applies(MatchExpression matchExpression, Target target) throws ScriptException {
        MatchExpressionApplies evt = new MatchExpressionApplies(matchExpression);
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
        var targets =
                Target.<Target>listAll().stream()
                        .filter(
                                target -> {
                                    try {
                                        return applies(matchExpression, target);
                                    } catch (ScriptException e) {
                                        logger.error(
                                                "Error while processing expression: "
                                                        + matchExpression,
                                                e);
                                        return false;
                                    }
                                })
                        .collect(Collectors.toList());

        var ids = new HashSet<>();
        var it = targets.iterator();
        while (it.hasNext()) {
            var t = it.next();
            if (ids.contains(t.jvmId)) {
                it.remove();
                continue;
            }
            ids.add(t.jvmId);
        }

        return targets;
    }

    @Name("io.cryostat.rules.MatchExpressionEvaluator.MatchExpressionApplies")
    @Label("Match Expression Evaluation")
    @Category("Cryostat")
    @SuppressFBWarnings(value = {"EI_EXPOSE_REP", "URF_UNREAD_FIELD"})
    public static class MatchExpressionApplies extends Event {

        String matchExpression;

        MatchExpressionApplies(MatchExpression matchExpression) {
            this.matchExpression = matchExpression.script;
        }
    }

    @Name("io.cryostat.rules.MatchExpressionEvaluator.ScriptCreation")
    @Label("Match Expression Script Creation")
    @Category("Cryostat")
    // @SuppressFBWarnings(
    //         value = "URF_UNREAD_FIELD",
    //         justification = "The event fields are recorded with JFR instead of accessed
    // directly")
    public static class ScriptCreation extends Event {}

    /**
     * Restricted view of a {@link io.cryostat.targets.Target} with only particular
     * expression-relevant fields exposed, connection URI exposed as a String, etc.
     */
    private static record SimplifiedTarget(
            boolean agent,
            String connectUrl,
            String alias,
            @Nullable String jvmId,
            Map<String, String> labels,
            Target.Annotations annotations) {
        SimplifiedTarget {
            Objects.requireNonNull(connectUrl);
            Objects.requireNonNull(alias);
            if (labels == null) {
                labels = Collections.emptyMap();
            }
            if (annotations == null) {
                annotations = new Annotations();
            }
        }

        static SimplifiedTarget from(Target target) {
            return new SimplifiedTarget(
                    target.isAgent(),
                    target.connectUrl.toString(),
                    target.alias,
                    target.jvmId,
                    target.labels,
                    target.annotations);
        }
    }
}
