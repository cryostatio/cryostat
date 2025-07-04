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

import io.cryostat.ConfigProperties;
import io.cryostat.core.reports.InterruptibleReportGenerator.AnalysisResult;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingHelper;

import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.cache.CaffeineCache;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Tiered caching layer for automated analysis reports. Holds report results in an in-memory cache
 * for a short duration to improve report retrieval performance, since report generation can be
 * quite expensive while the resulting reports themselves are not particularly large.
 */
@Priority(10)
@Decorator
@Dependent
class MemoryCachingReportsService implements ReportsService {

    static final String ACTIVE_REPORTS_MEMORY_CACHE_NAME = "activereports";
    static final String ARCHIVED_REPORTS_MEMORY_CACHE_NAME = "archivedreports";

    @ConfigProperty(name = "quarkus.cache.enabled")
    boolean quarkusCache;

    @ConfigProperty(name = ConfigProperties.REPORTS_MEMORY_CACHE_ENABLED)
    boolean memoryCache;

    @Inject
    @CacheName(ACTIVE_REPORTS_MEMORY_CACHE_NAME)
    Cache activeCache;

    @Inject
    @CacheName(ARCHIVED_REPORTS_MEMORY_CACHE_NAME)
    Cache archivedCache;

    @Inject @Delegate @Any ReportsService delegate;

    @Inject Logger logger;

    @Override
    public Uni<Map<String, AnalysisResult>> reportFor(ActiveRecording recording, String filter) {
        if (!quarkusCache || !memoryCache) {
            logger.trace("cache disabled, delegating...");
            return delegate.reportFor(recording, filter);
        }
        String key = ReportsService.key(recording);
        logger.tracev("reportFor {0}", key);
        return activeCache.getAsync(
                key,
                k -> {
                    logger.tracev("reportFor {0} cache miss", k);
                    return delegate.reportFor(recording, filter);
                });
    }

    @Override
    public Uni<Map<String, AnalysisResult>> reportFor(
            String jvmId, String filename, String filter) {
        if (!quarkusCache || !memoryCache) {
            logger.trace("cache disabled, delegating...");
            return delegate.reportFor(jvmId, filename, filter);
        }
        String key = RecordingHelper.archivedRecordingKey(jvmId, filename);
        logger.tracev("reportFor {0}", key);
        return archivedCache.getAsync(
                key,
                k -> {
                    logger.tracev("reportFor {0} cache miss", k);
                    return delegate.reportFor(jvmId, filename, filter);
                });
    }

    @Override
    public Uni<Map<String, AnalysisResult>> reportFor(ActiveRecording recording) {
        return reportFor(recording, null);
    }

    @Override
    public Uni<Map<String, AnalysisResult>> reportFor(String jvmId, String filename) {
        return reportFor(jvmId, filename, null);
    }

    @Override
    public boolean keyExists(ActiveRecording recording) {
        String key = ReportsService.key(recording);
        return (quarkusCache && memoryCache)
                && (activeCache.as(CaffeineCache.class).keySet().contains(key)
                        || delegate.keyExists(recording));
    }

    @Override
    public boolean keyExists(String jvmId, String filename) {
        String key = RecordingHelper.archivedRecordingKey(jvmId, filename);
        return (quarkusCache && memoryCache)
                && (archivedCache.as(CaffeineCache.class).keySet().contains(key)
                        || delegate.keyExists(jvmId, filename));
    }
}
