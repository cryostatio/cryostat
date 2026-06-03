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
package io.cryostat.monitoring;

import io.agroal.api.AgroalDataSource;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Monitors database connection pool health and logs metrics periodically.
 *
 * <p>This monitor tracks active, available, waiting, max used, and leak detection counts to help
 * identify connection pool exhaustion and potential leaks. Part of Risk 10 Phase 2 mitigation.
 */
@ApplicationScoped
public class ConnectionPoolMonitor {

    @Inject AgroalDataSource dataSource;
    @Inject Logger log;

    /**
     * Logs connection pool statistics every 30 seconds.
     *
     * <p>Logs at debug level for normal operation. Logs at warn level with full metrics if
     * connections are waiting (possible exhaustion) or leaks are detected.
     */
    @Scheduled(every = "30s")
    void logPoolStats() {
        try {
            var metrics = dataSource.getMetrics();

            long active = metrics.activeCount();
            long available = metrics.availableCount();
            long waiting = metrics.awaitingCount();
            long maxUsed = metrics.maxUsedCount();
            long leakDetection = metrics.leakDetectionCount();

            boolean hasIssues = waiting > 0 || leakDetection > 0;

            if (hasIssues) {
                log.warnf(
                        "Connection pool issue detected: active=%d, available=%d, waiting=%d,"
                                + " maxUsed=%d, leakDetection=%d",
                        active, available, waiting, maxUsed, leakDetection);
            } else {
                log.debugf(
                        "Connection pool: active=%d, available=%d, waiting=%d, maxUsed=%d,"
                                + " leakDetection=%d",
                        active, available, waiting, maxUsed, leakDetection);
            }
        } catch (Exception e) {
            log.errorf(e, "Failed to retrieve connection pool metrics");
        }
    }

    /**
     * Gets the current number of active connections.
     *
     * @return the number of active connections
     */
    public long getActiveCount() {
        try {
            return dataSource.getMetrics().activeCount();
        } catch (Exception e) {
            log.errorf(e, "Failed to retrieve active connection count");
            return -1;
        }
    }

    /**
     * Gets the current number of connections waiting for availability.
     *
     * @return the number of waiting connections
     */
    public long getAwaitingCount() {
        try {
            return dataSource.getMetrics().awaitingCount();
        } catch (Exception e) {
            log.errorf(e, "Failed to retrieve awaiting connection count");
            return -1;
        }
    }
}
