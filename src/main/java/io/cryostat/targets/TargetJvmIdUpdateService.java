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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import io.cryostat.ConfigProperties;
import io.cryostat.core.net.JFRConnection;
import io.cryostat.credentials.Credential;
import io.cryostat.expressions.MatchExpressionEvaluator;
import io.cryostat.libcryostat.JvmIdentifier;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.targets.Target.TargetDiscovery;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.projectnessie.cel.tools.ScriptException;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;

@ApplicationScoped
public class TargetJvmIdUpdateService {

    @Inject Logger logger;
    @Inject TargetConnectionManager connectionManager;
    @Inject RecordingHelper recordingHelper;
    @Inject EntityManager entityManager;
    @Inject MatchExpressionEvaluator matchExpressionEvaluator;
    @Inject Scheduler scheduler;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration connectionTimeout;

    private final List<JobKey> jobs = new CopyOnWriteArrayList<>();

    void onStart(@Observes StartupEvent evt) {
        logger.tracev("{0} started", getClass().getName());

        JobDetail jobDetail = JobBuilder.newJob(TargetJvmIdUpdateJob.class).build();

        if (jobs.contains(jobDetail.getKey())) {
            return;
        }

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
        jobs.add(jobDetail.getKey());
    }

    void onStop(@Observes ShutdownEvent evt) throws SchedulerException {
        scheduler.shutdown();
    }

    @Transactional
    @ConsumeEvent(value = Target.TARGET_JVM_DISCOVERY, blocking = true)
    void onMessage(TargetDiscovery event) {
        var target = Target.<Target>find("id", event.serviceRef().id).singleResultOptional();
        switch (event.kind()) {
            case LOST:
                // this should already be handled by the cascading deletion of the Target
                // TODO verify this
                break;
            case MODIFIED:
            // fall-through
            case FOUND:
                target.ifPresent(
                        t -> {
                            try {
                                logger.debugv("Updating JVM ID for {0} ({1})", t.connectUrl, t.id);
                                if (StringUtils.isBlank(t.jvmId)) {
                                    updateTargetJvmId(t, null);
                                }
                            } catch (Exception e) {
                                logger.warn(e);
                            }
                        });
                target.ifPresent(recordingHelper::listActiveRecordings);
                break;
            default:
                // no-op
                break;
        }
    }

    @ConsumeEvent(value = Credential.CREDENTIALS_STORED, blocking = true)
    @Transactional
    void updateCredential(Credential credential) {
        Target.<Target>stream("#Target.unconnected")
                .forEach(
                        t -> {
                            try {
                                if (matchExpressionEvaluator.applies(
                                        credential.matchExpression, t)) {
                                    updateTargetJvmId(t, credential);
                                }
                            } catch (ScriptException e) {
                                logger.error(e);
                            } catch (Exception e) {
                                logger.warn(e);
                            }
                        });
    }

    private void updateTargetJvmId(Target t, Credential credential) {
        try {
            t.jvmId =
                    connectionManager
                            .executeDirect(
                                    t,
                                    Optional.ofNullable(credential),
                                    JFRConnection::getJvmIdentifier)
                            .map(JvmIdentifier::getHash)
                            .await()
                            .atMost(connectionTimeout);
            t.persist();
        } catch (Exception e) {
            logger.error(e);
        }
    }
}
