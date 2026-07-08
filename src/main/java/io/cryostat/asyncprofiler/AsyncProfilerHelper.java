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
package io.cryostat.asyncprofiler;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import io.cryostat.asyncprofiler.AsyncProfiler.AgentConnectedTask;
import io.cryostat.asyncprofiler.AsyncProfiler.AsyncProfilerEvent;
import io.cryostat.asyncprofiler.AsyncProfiler.AsyncProfilerUpdateJob;
import io.cryostat.targets.AgentClient.AsyncProfile;
import io.cryostat.targets.AgentClient.AsyncProfilerStatus;
import io.cryostat.targets.AgentConnection;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import org.jboss.logging.Logger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;

@ApplicationScoped
public class AsyncProfilerHelper {

    @Inject TargetConnectionManager tcm;
    @Inject EventBus bus;
    @Inject Scheduler scheduler;
    @Inject Logger logger;

    public Uni<String> createAsyncProfile(Target target, List<String> events, Duration duration) {
        return executeUni(target, conn -> conn.dumpAsyncProfile(events, duration))
                .invoke(
                        (id) -> {
                            AsyncProfilerRecording.started(target, id, events, duration.toSeconds())
                                    .persist();

                            JobKey key =
                                    new JobKey(Long.toString(target.id), "async-profiler-update");
                            JobDetail job =
                                    JobBuilder.newJob(AsyncProfilerUpdateJob.class)
                                            .withIdentity(key)
                                            .usingJobData("id", id)
                                            .usingJobData("targetId", target.id)
                                            .usingJobData("duration", duration.toSeconds())
                                            .build();
                            Trigger trigger =
                                    TriggerBuilder.newTrigger()
                                            .withIdentity(
                                                    job.getKey().getName(), job.getKey().getGroup())
                                            // TODO make configurable
                                            .startAt(
                                                    Date.from(
                                                            Instant.now()
                                                                    .plus(duration)
                                                                    .plusSeconds(1)))
                                            // TODO make configurable
                                            .withSchedule(
                                                    SimpleScheduleBuilder.simpleSchedule()
                                                            .withIntervalInSeconds(2)
                                                            .withMisfireHandlingInstructionNextWithExistingCount()
                                                            .withRepeatCount(5))
                                            .build();
                            try {
                                if (!scheduler.checkExists(trigger.getKey())) {
                                    scheduler.scheduleJob(job, trigger);
                                }
                            } catch (SchedulerException se) {
                                logger.error(se);
                            }
                            var payload = AsyncProfilerEvent.Payload.of(target, id, duration);
                            notify(
                                    new AsyncProfilerEvent(
                                            AsyncProfiler.AsyncProfilerEventCategory.CREATED,
                                            payload));
                        });
    }

    public Uni<AsyncProfilerStatus> getStatus(Target target) {
        return executeUni(target, AgentConnection::asyncProfilerStatus);
    }

    public Uni<Void> deleteProfile(Target target, String profileId) {
        return executeUni(target, conn -> conn.deleteAsyncProfile(profileId))
                .invoke(
                        () -> {
                            AsyncProfilerRecording.<AsyncProfilerRecording>find(
                                            "target.id = ?1 and profileId = ?2",
                                            target.id,
                                            profileId)
                                    .firstResultOptional()
                                    .ifPresent(AsyncProfilerRecording::delete);

                            var payload = AsyncProfilerEvent.Payload.of(target, profileId, 0);
                            notify(
                                    new AsyncProfilerEvent(
                                            AsyncProfiler.AsyncProfilerEventCategory.DELETED,
                                            payload));
                        })
                .map(v -> null);
    }

    public Uni<List<AsyncProfile>> getProfiles(Target target) {
        return executeUni(target, AgentConnection::listAsyncProfiles);
    }

    public InputStream getAsyncProfile(Target target, String id) {
        return execute(target, conn -> conn.streamAsyncProfile(id));
    }

    private <T> Uni<T> executeUni(Target target, AgentConnectedTask<T> task) {
        if (!target.isAgent()) {
            throw new BadRequestException();
        }
        return tcm.executeConnectedTaskUni(
                target,
                conn -> {
                    if (!(conn instanceof AgentConnection)) {
                        throw new InternalServerErrorException();
                    }
                    return task.execute((AgentConnection) conn);
                });
    }

    private <T> T execute(Target target, AgentConnectedTask<T> task) {
        if (!target.isAgent()) {
            throw new BadRequestException();
        }
        return tcm.executeConnectedTask(
                target,
                conn -> {
                    if (!(conn instanceof AgentConnection)) {
                        throw new InternalServerErrorException();
                    }
                    return task.execute((AgentConnection) conn);
                });
    }

    private void notify(AsyncProfilerEvent event) {
        bus.publish(
                MessagingServer.class.getName(),
                new Notification(event.category().category(), event.payload()));
    }
}
