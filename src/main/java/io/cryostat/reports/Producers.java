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

import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;

import io.cryostat.core.reports.InterruptibleReportGenerator;
import io.cryostat.recordings.RecordingHelper;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.DefaultBean;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheManager;
import io.quarkus.cache.CacheName;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

public class Producers {

    @Produces
    // RequestScoped so that each individual report generation request has its own interruptible
    // generator with an independent task queueing thread which dispatches to the shared common pool
    @RequestScoped
    @DefaultBean
    public static InterruptibleReportGenerator produceInterruptibleReportGenerator() {
        return new InterruptibleReportGenerator(
                io.cryostat.core.log.Logger.INSTANCE, Set.of(), ForkJoinPool.commonPool());
    }

    @Produces
    @RequestScoped
    @DefaultBean
    public static ReportsService produceReportsService(
            @ConfigProperty(name = "quarkus.cache.enabled") boolean quarkusCache,
            @ConfigProperty(name = MemoryCachingReportsService.MEMORY_CACHE_ENABLED_CONFIG_PROPERTY)
                    boolean memoryCache,
            @ConfigProperty(name = SidecarReportsService.URL_CONFIG_PROPERTY)
                    Optional<URI> sidecarUri,
            @CacheName(MemoryCachingReportsService.ACTIVE_CACHE_NAME) Cache activeCache,
            @CacheName(MemoryCachingReportsService.ARCHIVED_CACHE_NAME) Cache archivedCache,
            RecordingHelper helper,
            ObjectMapper mapper,
            InterruptibleReportGenerator reportGenerator,
            CacheManager cacheManager) {
        // TODO switch inProcess vs remote, add s3 caching wrapper for archives
        ReportsService svc =
                sidecarUri
                        .<ReportsService>map(uri -> new SidecarReportsService(uri, mapper, helper))
                        .orElseGet(() -> new InProcessReportsService(helper, reportGenerator));
        if (quarkusCache && memoryCache) {
            svc = new MemoryCachingReportsService(svc, activeCache, archivedCache);
        }
        return svc;
    }
}
