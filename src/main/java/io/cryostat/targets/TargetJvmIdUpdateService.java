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

    void onStart(@Observes StartupEvent evt) throws SchedulerException {
        logger.tracev("{0} started", getClass().getName());

        JobDetail jobDetail = JobBuilder.newJob(TargetJvmIdUpdateJob.class).build();

        Trigger trigger =
                TriggerBuilder.newTrigger()
                        .withSchedule(
                                SimpleScheduleBuilder.simpleSchedule()
                                        .withIntervalInSeconds(
                                                (int) (connectionTimeout.toSeconds() * 2))
                                        .repeatForever()
                                        .withMisfireHandlingInstructionNowWithExistingCount())
                        .startAt(
                                Date.from(
                                        Instant.now()
                                                .plusSeconds(
                                                        (int) (connectionTimeout.toSeconds() * 2))))
                        .build();
        scheduler.scheduleJob(jobDetail, trigger);
    }

    void onStop(@Observes ShutdownEvent evt) throws SchedulerException {
        scheduler.shutdown();
    }

    @ConsumeEvent(value = Target.TARGET_JVM_DISCOVERY)
    void onMessage(TargetDiscovery event) throws SchedulerException {
        switch (event.kind()) {
            case MODIFIED:
            // fall-through
            case FOUND:
                JobDetail jobDetail = JobBuilder.newJob(TargetJvmIdUpdateJob.class).build();
                Map<String, Object> data = jobDetail.getJobDataMap();
                data.put("targetId", event.serviceRef().id);

                Trigger trigger =
                        TriggerBuilder.newTrigger()
                                .startAt(Date.from(Instant.now().plusSeconds(3)))
                                .usingJobData(jobDetail.getJobDataMap())
                                .build();
                scheduler.scheduleJob(jobDetail, trigger);
                break;
            default:
                // no-op
                break;
        }
    }
}
