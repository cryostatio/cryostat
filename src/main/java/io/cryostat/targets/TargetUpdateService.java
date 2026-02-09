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

import io.cryostat.ConfigProperties;
import io.cryostat.credentials.Credential;
import io.cryostat.expressions.MatchExpressionEvaluator;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.targets.Target.TargetDiscovery;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;

/**
 * Watch for {@link io.cryostat.target.Target} instances to be discovered, or matching {@link
 * io.cryostat.credentials.Credential} to be added, and schedule {@link
 * io.cryostat.targets.TargetUpdateJob} jobs to compute the target JVM hash IDs.
 */
@ApplicationScoped
public class TargetUpdateService {

    @Inject Logger logger;
    @Inject Scheduler scheduler;
    @Inject MatchExpressionEvaluator matchExpressionEvaluator;

    @ConfigProperty(name = ConfigProperties.EXTERNAL_RECORDINGS_DELAY)
    Duration externalRecordingDelay;

    @Transactional
    void onStart(@Observes StartupEvent evt) {
        logger.tracev("{0} started", getClass().getName());
        Target.<Target>listAll()
                .forEach(
                        t -> {
                            try {
                                fireTargetUpdate(t);
                            } catch (SchedulerException e) {
                                logger.warn(e);
                            }
                        });
    }

    void onStop(@Observes ShutdownEvent evt) throws SchedulerException {
        scheduler.shutdown();
    }

    @ConsumeEvent(value = Credential.CREDENTIALS_STORED, blocking = true)
    @Transactional
    void onCredentialsStored(Credential credential) {
        updateTargetsForExpression(credential);
    }

    @ConsumeEvent(value = Credential.CREDENTIALS_UPDATED, blocking = true)
    @Transactional
    void onCredentialsUpdated(Credential credential) {
        updateTargetsForExpression(credential);
    }

    @ConsumeEvent(value = Credential.CREDENTIALS_DELETED, blocking = true)
    @Transactional
    void onCredentialsDeleted(Credential credential) {
        updateTargetsForExpression(credential);
    }

    private void updateTargetsForExpression(Credential credential) {
        for (Target target :
                matchExpressionEvaluator.getMatchedTargets(credential.matchExpression)) {
            try {
                fireTargetUpdate(target);
            } catch (SchedulerException se) {
                logger.warn(se);
            }
        }
    }

    @ConsumeEvent(value = Target.TARGET_JVM_DISCOVERY)
    void onMessage(TargetDiscovery event) throws SchedulerException {
        switch (event.kind()) {
            case MODIFIED:
            // fall-through
            case FOUND:
                fireTargetUpdate(event.serviceRef());
                break;
            default:
                // no-op
                break;
        }
    }

    void fireTargetUpdate(Target target) throws SchedulerException {
        JobKey key = new JobKey(Long.toString(target.id), "target-update");
        JobDetail job =
                JobBuilder.newJob(TargetUpdateJob.class)
                        .withIdentity(key)
                        .usingJobData("targetId", target.id)
                        .build();
        Trigger trigger =
                TriggerBuilder.newTrigger()
                        .withIdentity(job.getKey().getName(), job.getKey().getGroup())
                        // TODO make configurable
                        .startAt(Date.from(Instant.now().plusSeconds(1)))
                        .withSchedule(
                                SimpleScheduleBuilder.simpleSchedule()
                                        // TODO make configurable
                                        .withIntervalInSeconds(120)
                                        .repeatForever()
                                        .withMisfireHandlingInstructionNextWithExistingCount())
                        .build();
        if (scheduler.checkExists(trigger.getKey())) {
            return;
        }
        scheduler.scheduleJob(job, trigger);
    }

    void fireActiveRecordingUpdate(ActiveRecording recording) throws SchedulerException {
        JobKey key =
                JobKey.jobKey(
                        String.format("%s.%d", recording.target.jvmId, recording.remoteId),
                        "recording-update");
        if (scheduler.checkExists(key)) {
            return;
        }
        JobDetail jobDetail =
                JobBuilder.newJob(ActiveRecordingUpdateJob.class)
                        .withIdentity(key)
                        .usingJobData("recordingId", recording.id)
                        .build();
        var when =
                Instant.ofEpochMilli(recording.startTime)
                        .plusMillis(recording.duration)
                        .plus(externalRecordingDelay);
        Trigger trigger = TriggerBuilder.newTrigger().startAt(Date.from(when)).build();
        scheduler.scheduleJob(jobDetail, trigger);
    }
}
