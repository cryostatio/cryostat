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
package io.cryostat.reports;

import java.util.Map;
import java.util.function.Predicate;

import org.openjdk.jmc.flightrecorder.rules.IRule;

import io.cryostat.ConfigProperties;
import io.cryostat.recordings.ActiveRecording;

import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@Priority(10)
@Decorator
@Dependent
class MemoryCachingReportsService implements ReportsService {

    @ConfigProperty(name = "quarkus.cache.enabled")
    boolean quarkusCache;

    @ConfigProperty(name = ConfigProperties.MEMORY_CACHE_ENABLED_CONFIG_PROPERTY)
    boolean memoryCache;

    @Inject
    @CacheName(ConfigProperties.ACTIVE_REPORTS_CACHE_NAME)
    Cache activeCache;

    @Inject
    @CacheName(ConfigProperties.ARCHIVED_REPORTS_CACHE_NAME)
    Cache archivedCache;

    @Inject @Delegate @Any ReportsService delegate;

    @Inject Logger logger;

    @Override
    public Uni<Map<String, RuleEvaluation>> reportFor(
            ActiveRecording recording, Predicate<IRule> predicate) {
        if (!quarkusCache || !memoryCache) {
            logger.info("cache disabled, delegating...");
            return delegate.reportFor(recording, predicate);
        }
        logger.infov("reportFor {0}", key(recording));
        return activeCache.getAsync(
                key(recording),
                key -> {
                    logger.infov("reportFor {0} cache miss", key(recording));
                    return delegate.reportFor(recording);
                });
    }

    @Override
    public Uni<Map<String, RuleEvaluation>> reportFor(
            String jvmId, String filename, Predicate<IRule> predicate) {
        if (!quarkusCache || !memoryCache) {
            logger.info("cache disabled, delegating...");
            return delegate.reportFor(jvmId, filename, predicate);
        }
        logger.infov("reportFor {0}", key(jvmId, filename));
        return archivedCache.getAsync(
                key(jvmId, filename),
                key -> {
                    logger.infov("reportFor {0} cache miss", key(jvmId, filename));
                    return delegate.reportFor(jvmId, filename);
                });
    }

    static String key(ActiveRecording recording) {
        return String.format("%s/%d", recording.target.jvmId, recording.remoteId);
    }

    static String key(String jvmId, String filename) {
        return String.format("%s/%s", jvmId, filename);
    }
}
