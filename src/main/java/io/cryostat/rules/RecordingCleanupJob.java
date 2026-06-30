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
package io.cryostat.rules;

import java.time.Duration;

import io.cryostat.ConfigProperties;

import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/**
 * Quartz job that delegates to RuleService for fault-tolerant recording cleanup.
 *
 * <p>This job is scheduled when a recording needs to be cleaned up (stopped). It delegates to
 * RuleService.cleanupRecording() which handles all retry logic, timeouts, and backpressure via
 * SmallRye Fault Tolerance annotations.
 *
 * <p>The job is one-time only and unscheduled after execution (success or failure).
 */
@DisallowConcurrentExecution
public class RecordingCleanupJob implements Job {

    @Inject Logger logger;
    @Inject RuleService ruleService;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration connectionFailedTimeout;

    @Override
    public void execute(JobExecutionContext ctx) throws JobExecutionException {
        String ruleName = ctx.getMergedJobDataMap().getString("ruleName");
        String jvmId = ctx.getMergedJobDataMap().getString("jvmId");
        String recordingName = ctx.getMergedJobDataMap().getString("recordingName");

        logger.debugv(
                "Executing cleanup job: rule={0} jvmId={1} recording={2}",
                ruleName, jvmId, recordingName);

        try {
            ruleService
                    .cleanupRecording(ruleName, jvmId, recordingName)
                    .await()
                    .atMost(connectionFailedTimeout);
            logger.debugv("Recording cleanup completed: rule={0} jvmId={1}", ruleName, jvmId);
            ctx.getScheduler().unscheduleJob(ctx.getTrigger().getKey());
        } catch (Exception e) {
            logger.errorv(
                    e,
                    "Recording cleanup failed after all retries: rule={0} jvmId={1}",
                    ruleName,
                    jvmId);
            JobExecutionException ex = new JobExecutionException(e);
            ex.setUnscheduleFiringTrigger(true);
            throw ex;
        }
    }
}
