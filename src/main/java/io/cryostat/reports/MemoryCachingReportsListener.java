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
import io.cryostat.discovery.DiscoveryNode;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.ActiveRecordings;
import io.cryostat.recordings.ArchivedRecordings.ArchivedRecording;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.targets.Target;
import io.cryostat.targets.Target.TargetDiscovery;

import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PreRemove;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** Event listener for pruning cached automated analysis reports when sources disappear. */
@ApplicationScoped
class MemoryCachingReportsListener {

    @ConfigProperty(name = "quarkus.cache.enabled")
    boolean quarkusCache;

    @ConfigProperty(name = ConfigProperties.REPORTS_MEMORY_CACHE_ENABLED)
    boolean memoryCache;

    @Inject
    @CacheName(MemoryCachingReportsService.ACTIVE_REPORTS_MEMORY_CACHE_NAME)
    Cache activeCache;

    @Inject
    @CacheName(MemoryCachingReportsService.ARCHIVED_REPORTS_MEMORY_CACHE_NAME)
    Cache archivedCache;

    @Inject RecordingHelper recordingHelper;

    @Inject Logger logger;

    @ConsumeEvent(value = ActiveRecordings.ARCHIVED_RECORDING_DELETED)
    public void handleArchivedRecordingDeletion(ArchivedRecording recording) {
        logger.tracev("archived recording cache invalidation: {0}", recording.name());
        if (!quarkusCache || !memoryCache) {
            return;
        }
        String jvmId = recording.metadata().labels().get("jvmId");
        if (StringUtils.isBlank(jvmId)) {
            return;
        }
        String key = recordingHelper.archivedRecordingKey(jvmId, recording.name());
        logger.tracev("Picked up deletion of archived recording: {0}", key);
        archivedCache.invalidate(key);
    }

    @ConsumeEvent(value = Target.TARGET_JVM_DISCOVERY)
    public void handleTargetDiscovery(TargetDiscovery evt) {
        logger.tracev(
                "target active recording cache invalidation: {0} {1} [{2}]",
                evt.kind(), evt.serviceRef().connectUrl, evt.serviceRef().activeRecordings);
        if (!quarkusCache || !memoryCache) {
            return;
        }
        if (!Target.EventKind.LOST.equals(evt.kind())) {
            return;
        }
        preRemoveTarget(evt.serviceRef());
    }

    @PreRemove
    @ConsumeEvent(value = ActiveRecordings.ACTIVE_RECORDING_DELETED)
    void handleActiveRecordingDeletion(ActiveRecording recording) {
        logger.tracev(
                "active recording cache invalidation: {0} {1}",
                recording.target.connectUrl, recording.name);
        if (!quarkusCache || !memoryCache) {
            return;
        }
        String key = ReportsService.key(recording);
        logger.tracev(
                "Picked up deletion of active recording: {0} / {1} ({2})",
                recording.target.alias, recording.name, key);
        activeCache.invalidate(key);
    }

    @PreRemove
    void preRemoveTarget(Target target) {
        logger.tracev("Deleting target: {0} {1}", target.connectUrl, target.activeRecordings);
        target.activeRecordings.forEach(this::handleActiveRecordingDeletion);
    }

    @PreRemove
    void preRemoveDiscoveryNode(DiscoveryNode node) {
        logger.tracev("Lost discovery node: {0} {1}", node.name, node.target);
        if (node.target != null) {
            preRemoveTarget(node.target);
        }
    }
}
