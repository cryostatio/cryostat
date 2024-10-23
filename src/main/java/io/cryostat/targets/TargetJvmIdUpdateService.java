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
package io.cryostat.targets;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import io.cryostat.ConfigProperties;
import io.cryostat.targets.Target.TargetDiscovery;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;

@ApplicationScoped
public class TargetJvmIdUpdateService {

    @Inject Logger logger;
    @Inject Scheduler scheduler;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration connectionTimeout;

    void onStart(@Observes StartupEvent evt) {
        logger.tracev("{0} started", getClass().getName());

        JobDetail jobDetail = JobBuilder.newJob(TargetJvmIdUpdateJob.class).build();

        Trigger trigger =
                TriggerBuilder.newTrigger()
                        .withSchedule(
                                SimpleScheduleBuilder.simpleSchedule()
                                        .withIntervalInSeconds(30)
                                        .repeatForever()
                                        .withMisfireHandlingInstructionNowWithExistingCount())
                        .startAt(Date.from(Instant.now().plusSeconds(30)))
                        .build();
        try {
            scheduler.scheduleJob(jobDetail, trigger);
        } catch (SchedulerException e) {
            logger.errorv(e, "Failed to schedule JVM ID updater job");
        }
    }

    void onStop(@Observes ShutdownEvent evt) throws SchedulerException {
        scheduler.shutdown();
    }

    @ConsumeEvent(value = Target.TARGET_JVM_DISCOVERY)
    void onMessage(TargetDiscovery event) {
        switch (event.kind()) {
            case LOST:
                // this should already be handled by the cascading deletion of the Target
                // TODO verify this
                break;
            case MODIFIED:
            // fall-through
            case FOUND:
                JobDetail jobDetail =
                        JobBuilder.newJob(TargetJvmIdUpdateJob.class)
                                .withIdentity(event.kind().name(), event.serviceRef().id.toString())
                                .build();
                Map<String, Object> data = jobDetail.getJobDataMap();
                data.put("targetId", event.serviceRef().id);

                Trigger trigger =
                        TriggerBuilder.newTrigger()
                                .startAt(Date.from(Instant.now().plusSeconds(1)))
                                .usingJobData(jobDetail.getJobDataMap())
                                .build();
                try {
                    scheduler.scheduleJob(jobDetail, trigger);
                } catch (SchedulerException e) {
                    logger.errorv(e, "Failed to schedule JVM ID updater job");
                }
                break;
            default:
                // no-op
                break;
        }
    }
}
