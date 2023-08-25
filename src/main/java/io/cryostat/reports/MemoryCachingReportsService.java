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

import io.cryostat.core.reports.InterruptibleReportGenerator.RuleEvaluation;
import io.cryostat.recordings.ActiveRecording;

import io.quarkus.cache.Cache;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

class MemoryCachingReportsService implements ReportsService {

    static final String MEMORY_CACHE_ENABLED_CONFIG_PROPERTY =
            "cryostat.services.reports.memory-cache.enabled";
    static final String ACTIVE_CACHE_NAME = "activereports";
    static final String ARCHIVED_CACHE_NAME = "archivedreports";

    private final ReportsService delegate;
    private final Cache activeCache;
    private final Cache archivedCache;
    private final Logger logger = Logger.getLogger(MemoryCachingReportsService.class);

    MemoryCachingReportsService(ReportsService delegate, Cache activeCache, Cache archivedCache) {
        this.delegate = delegate;
        this.activeCache = activeCache;
        this.archivedCache = archivedCache;
    }

    @Override
    public Uni<Map<String, RuleEvaluation>> reportFor(
            ActiveRecording recording, Predicate<IRule> predicate) {
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
        logger.infov("reportFor {0}", key(jvmId, filename));
        return archivedCache.getAsync(
                key(jvmId, filename),
                key -> {
                    logger.infov("reportFor {0} cache miss", key(jvmId, filename));
                    return delegate.reportFor(jvmId, filename);
                });
    }

    private String key(ActiveRecording recording) {
        return String.format("%s/%d", recording.target.jvmId, recording.remoteId);
    }

    private String key(String jvmId, String filename) {
        return String.format("%s/%s", jvmId, filename);
    }
}
