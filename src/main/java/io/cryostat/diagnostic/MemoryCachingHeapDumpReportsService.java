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
package io.cryostat.diagnostic;

import io.cryostat.ConfigProperties;
import io.cryostat.core.diagnostic.HeapDumpAnalysis;

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
class MemoryCachingHeapDumpReportsService implements HeapDumpReportsService {

    static final String HEAP_DUMP_REPORTS_MEMORY_CACHE_NAME = "heapdumpreports";

    @ConfigProperty(name = "quarkus.cache.enabled")
    boolean quarkusCache;

    @ConfigProperty(name = ConfigProperties.HEAP_DUMP_REPORTS_MEMORY_CACHE_ENABLED)
    boolean memoryCache;

    @Inject
    @CacheName(HEAP_DUMP_REPORTS_MEMORY_CACHE_NAME)
    Cache archivedCache;

    @Inject @Delegate @Any HeapDumpReportsService delegate;

    @Inject Logger logger;

    @Override
    public Uni<HeapDumpAnalysis> reportFor(String jvmId, String heapDumpId) {
        if (!quarkusCache || !memoryCache) {
            logger.trace("cache disabled, delegating...");
            return delegate.reportFor(jvmId, heapDumpId);
        }
        String key = DiagnosticsHelper.storageKey(jvmId, heapDumpId);
        logger.tracev("reportFor {0}", key);
        return archivedCache.getAsync(
                key,
                k -> {
                    logger.tracev("reportFor {0} cache miss", k);
                    return delegate.reportFor(jvmId, heapDumpId);
                });
    }

    @Override
    public boolean keyExists(String jvmId, String heapDumpId) {
        String key = DiagnosticsHelper.storageKey(jvmId, heapDumpId);
        return (quarkusCache && memoryCache)
                && (archivedCache.as(CaffeineCache.class).keySet().contains(key)
                        || delegate.keyExists(jvmId, heapDumpId));
    }
}
