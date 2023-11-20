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

import io.cryostat.ConfigProperties;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.recordings.Recordings;
import io.cryostat.recordings.Recordings.ArchivedRecording;

import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
class MemoryCachingReportsListener {

    @ConfigProperty(name = "quarkus.cache.enabled")
    boolean quarkusCache;

    @ConfigProperty(name = ConfigProperties.MEMORY_CACHE_ENABLED)
    boolean memoryCache;

    @Inject
    @CacheName(ConfigProperties.ACTIVE_REPORTS_MEMORY_CACHE_NAME)
    Cache activeCache;

    @Inject
    @CacheName(ConfigProperties.ARCHIVED_REPORTS_MEMORY_CACHE_NAME)
    Cache archivedCache;

    @Inject RecordingHelper recordingHelper;

    @Inject Logger logger;

    @ConsumeEvent(value = Recordings.ARCHIVED_RECORDING_DELETED)
    public void handleArchivedRecordingDeletion(ArchivedRecording recording) {
        if (!quarkusCache || !memoryCache) {
            return;
        }
        // FIXME if the jvmId is not properly persisted with the recording metadata then we cannot
        // clear the cache for that entry
        String key =
                recordingHelper.archivedRecordingKey(
                        recording.metadata().labels().get("jvmId"), recording.name());
        logger.tracev("Picked up deletion of archived recording: {0}", key);
        archivedCache.invalidate(key);
    }

    @ConsumeEvent(value = Recordings.ACTIVE_RECORDING_DELETED)
    public void handleActiveRecordingDeletion(ActiveRecording recording) {
        if (!quarkusCache || !memoryCache) {
            return;
        }
        // TODO verify that target lost cascades and causes active recording deletion events that we
        // observe here
        String key = ReportsService.key(recording);
        logger.tracev(
                "Picked up deletion of active recording: {0} / {1} ({2})",
                recording.target.alias, recording.name, key);
        activeCache.invalidate(key);
    }
}
