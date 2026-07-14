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
package io.cryostat.discovery;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.cryostat.ConfigProperties;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;

/**
 * Startup plugin pinger that triggers existing Quartz jobs with delays between batches. This
 * prevents registration storms by limiting concurrent job executions and spreading them over time.
 */
@ApplicationScoped
public class StartupPluginPinger {

    @Inject Logger logger;
    @Inject Scheduler scheduler;

    @ConfigProperty(name = ConfigProperties.DISCOVERY_PLUGINS_PING_WORKER_POOL_SIZE)
    int workerPoolSize;

    @ConfigProperty(name = ConfigProperties.DISCOVERY_PLUGINS_PING_DELAY_MS)
    long delayMs;

    @ConfigProperty(name = ConfigProperties.DISCOVERY_PLUGINS_PING_TIMEOUT_MS)
    long timeoutMs;

    @ConfigProperty(name = ConfigProperties.DISCOVERY_PLUGINS_PING_STARTUP_GRACE_PERIOD_MS)
    long startupGracePeriodMs;

    private ExecutorService executorService;

    /**
     * Data transfer object to hold plugin information extracted from entities. This allows us to
     * pass data outside of transaction boundaries without risking detached entity issues.
     */
    record PluginData(UUID id, String realmName, String callback) {
        static PluginData from(DiscoveryPlugin plugin) {
            return new PluginData(
                    plugin.id,
                    plugin.realm.name,
                    plugin.callback != null ? plugin.callback.toString() : "unknown");
        }
    }

    /**
     * On startup, trigger existing Quartz jobs for all plugins sequentially to avoid registration
     * storm.
     */
    void onStart(@Observes StartupEvent evt) {
        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newFixedThreadPool(workerPoolSize);
        }

        List<PluginData> pluginDataList = fetchPluginData();
        applyStartupGracePeriod()
                .thenCompose(v -> triggerJobsSequentially(pluginDataList, scheduler))
                .whenComplete(
                        (v, ex) -> {
                            if (ex != null) {
                                logger.error("Error during sequential plugin ping", ex);
                            } else {
                                logger.debug("Sequential plugin ping on startup completed");
                            }
                        });
    }

    private List<PluginData> fetchPluginData() {
        return QuarkusTransaction.joiningExisting()
                .call(
                        () ->
                                DiscoveryPlugin.<DiscoveryPlugin>findAll().list().stream()
                                        .filter(p -> !p.builtin)
                                        .map(PluginData::from)
                                        .toList());
    }

    CompletableFuture<Void> applyStartupGracePeriod() {
        if (startupGracePeriodMs <= 0) {
            return CompletableFuture.completedFuture(null);
        }

        logger.debugv(
                "Applying startup grace period of {0}ms before triggering plugin ping jobs",
                startupGracePeriodMs);

        return CompletableFuture.runAsync(
                () -> {
                    try {
                        Thread.sleep(startupGracePeriodMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.warn("Interrupted during startup grace period", e);
                    }
                },
                executorService);
    }

    /**
     * Trigger existing Quartz jobs for all plugins sequentially using a worker pool with delays
     * between batches.
     *
     * @param pluginDataList List of plugin data whose jobs should be triggered
     * @param scheduler Quartz scheduler to trigger jobs
     * @return CompletableFuture that completes when all job triggers are done (successful or
     *     failed)
     */
    public CompletableFuture<Void> triggerJobsSequentially(
            List<PluginData> pluginDataList, Scheduler scheduler) {
        if (pluginDataList.isEmpty()) {
            logger.debug("No plugin jobs to trigger");
            return CompletableFuture.completedFuture(null);
        }

        logger.debugv(
                "Starting sequential plugin job triggering: {0} plugins, {1} workers, {2}ms delay"
                        + " between batches",
                pluginDataList.size(), workerPoolSize, delayMs);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicInteger processedCount = new AtomicInteger(0);

        CompletableFuture<Void> allTriggers = CompletableFuture.completedFuture(null);

        for (int i = 0; i < pluginDataList.size(); i++) {
            final PluginData pluginData = pluginDataList.get(i);
            final int pluginIndex = i;
            final int batchNumber = i / workerPoolSize;
            final int positionInBatch = i % workerPoolSize;

            if (positionInBatch == 0 && batchNumber > 0) {
                allTriggers =
                        allTriggers.thenCompose(
                                v -> {
                                    logger.debugv(
                                            "Batch {0} complete, waiting {1}ms before next batch",
                                            batchNumber - 1, delayMs);
                                    return CompletableFuture.runAsync(
                                            () -> {
                                                try {
                                                    Thread.sleep(delayMs);
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                    logger.warn(
                                                            "Interrupted while waiting between"
                                                                    + " batches",
                                                            e);
                                                }
                                            },
                                            executorService);
                                });
            }

            allTriggers =
                    allTriggers.thenCompose(
                            v ->
                                    CompletableFuture.runAsync(
                                                    () -> {
                                                        try {
                                                            JobKey jobKey =
                                                                    Discovery.getPeriodicJobKey(
                                                                            pluginData.id());
                                                            if (!scheduler.checkExists(jobKey)) {
                                                                logger.warnv(
                                                                        "Job not found for plugin"
                                                                            + " {0}/{1}: {2} @ {3}",
                                                                        pluginIndex + 1,
                                                                        pluginDataList.size(),
                                                                        pluginData.realmName(),
                                                                        pluginData.callback());
                                                                failureCount.incrementAndGet();
                                                                return;
                                                            }
                                                            logger.debugv(
                                                                    "Triggering job {0}/{1} for"
                                                                            + " plugin: {2} @ {3}",
                                                                    pluginIndex + 1,
                                                                    pluginDataList.size(),
                                                                    pluginData.realmName(),
                                                                    pluginData.callback());

                                                            scheduler.triggerJob(jobKey);

                                                            successCount.incrementAndGet();
                                                            logger.debugv(
                                                                    "Successfully triggered job"
                                                                        + " {0}/{1} for plugin: {2}"
                                                                        + " @ {3}",
                                                                    pluginIndex + 1,
                                                                    pluginDataList.size(),
                                                                    pluginData.realmName(),
                                                                    pluginData.callback());
                                                        } catch (SchedulerException e) {
                                                            failureCount.incrementAndGet();
                                                            logger.warnv(
                                                                    e,
                                                                    "Failed to trigger job for"
                                                                        + " plugin {0}/{1}: {2} @"
                                                                        + " {3}",
                                                                    pluginIndex + 1,
                                                                    pluginDataList.size(),
                                                                    pluginData.realmName(),
                                                                    pluginData.callback());
                                                        } finally {
                                                            int processed =
                                                                    processedCount
                                                                            .incrementAndGet();
                                                            if (processed % workerPoolSize == 0
                                                                    || processed
                                                                            == pluginDataList
                                                                                    .size()) {
                                                                logger.debugv(
                                                                        "Plugin job trigger"
                                                                            + " progress: {0}/{1}"
                                                                            + " processed ({2}"
                                                                            + " successful, {3}"
                                                                            + " failed)",
                                                                        processed,
                                                                        pluginDataList.size(),
                                                                        successCount.get(),
                                                                        failureCount.get());
                                                            }
                                                        }
                                                    },
                                                    executorService)
                                            .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                                            .exceptionally(
                                                    ex -> {
                                                        failureCount.incrementAndGet();
                                                        logger.warnv(
                                                                ex,
                                                                "Job trigger timed out or failed"
                                                                    + " for plugin {0}/{1}: {2} @"
                                                                    + " {3}",
                                                                pluginIndex + 1,
                                                                pluginDataList.size(),
                                                                pluginData.realmName(),
                                                                pluginData.callback());
                                                        return null;
                                                    }));
        }

        return allTriggers.thenRun(
                () ->
                        logger.debugv(
                                "Sequential plugin job triggering complete: {0} successful, {1}"
                                        + " failed out of {2} total",
                                successCount.get(), failureCount.get(), pluginDataList.size()));
    }

    /** Shutdown the executor service on application shutdown. */
    void onShutdown(@Observes ShutdownEvent evt) {
        if (executorService != null && !executorService.isShutdown()) {
            logger.trace("Shutting down SequentialPluginPinger executor service");
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    logger.warn(
                            "SequentialPluginPinger executor did not terminate in time, forcing"
                                    + " shutdown");
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while shutting down SequentialPluginPinger", e);
                executorService.shutdownNow();
            }
        }
    }
}
