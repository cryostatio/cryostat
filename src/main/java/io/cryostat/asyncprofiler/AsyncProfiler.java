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
import java.util.Objects;

import io.cryostat.targets.AgentClient.AsyncProfile;
import io.cryostat.targets.AgentClient.AsyncProfilerStatus;
import io.cryostat.targets.AgentClient.ProfilerStatus;
import io.cryostat.targets.AgentClient.StartProfileRequest;
import io.cryostat.targets.AgentConnection;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.util.HttpMimeType;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.ShutdownEvent;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.HttpHeaders;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;

@Path("/api/beta/targets/{targetId}/async-profiler")
public class AsyncProfiler {

    public static final String ASYNC_PROFILER_CREATED = "AsyncProfilerCreated";
    public static final String ASYNC_PROFILER_STOPPED = "AsyncProfilerStopped";
    public static final String ASYNC_PROFILER_DELETED = "AsyncProfilerDeleted";

    public enum AsyncProfilerEventCategory {
        CREATED(ASYNC_PROFILER_CREATED),
        STOPPED(ASYNC_PROFILER_STOPPED),
        DELETED(ASYNC_PROFILER_DELETED),
        ;

        private final String category;

        private AsyncProfilerEventCategory(String category) {
            this.category = category;
        }

        public String category() {
            return category;
        }
    }

    @Inject TargetConnectionManager tcm;
    @Inject Scheduler scheduler;
    @Inject EventBus bus;
    @Inject Logger logger;

    void onStop(@Observes ShutdownEvent evt) throws SchedulerException {
        scheduler.shutdown();
    }

    @POST
    @Blocking
    @Transactional
    @RolesAllowed("write")
    @Operation(summary = "Create a new async-profiler profile on the specified target")
    public Uni<String> create(@RestPath long targetId, StartProfileRequest req) {
        Target target = Target.find("id", targetId).singleResult();
        Duration duration = Duration.ofSeconds(req.duration());

        return executeUni(target, conn -> conn.dumpAsyncProfile(req.events(), duration))
                .invoke(
                        (id) -> {
                            JobKey key =
                                    new JobKey(Long.toString(target.id), "async-profiler-update");
                            JobDetail job =
                                    JobBuilder.newJob(AsyncProfilerUpdateJob.class)
                                            .withIdentity(key)
                                            .usingJobData("id", id)
                                            .usingJobData("targetId", target.id)
                                            .usingJobData("duration", req.duration())
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

    @GET
    @Path("/{profileId}")
    @Blocking
    @RolesAllowed("read")
    @Operation(summary = "Download an async-profiler binary file in JFR format")
    public RestResponse<InputStream> get(@RestPath long targetId, @RestPath String profileId)
            throws Exception {
        Target target = Target.find("id", targetId).singleResult();
        return ResponseBuilder.<InputStream>ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        String.format(
                                "attachment; filename=\"%s_%s.asprof.jfr\"",
                                target.alias, profileId))
                .header(HttpHeaders.CONTENT_TYPE, HttpMimeType.OCTET_STREAM.mime())
                .entity(execute(target, conn -> conn.streamAsyncProfile(profileId)))
                .build();
    }

    @GET
    @Path("/status")
    @Blocking
    @RolesAllowed("read")
    @Operation(summary = "Get specified target's async-profiler status")
    public Uni<AsyncProfilerStatus> getStatus(@RestPath long targetId) throws Exception {
        Target target = Target.find("id", targetId).singleResult();
        return executeUni(target, conn -> conn.asyncProfilerStatus());
    }

    @GET
    @Blocking
    @Transactional
    @RolesAllowed("read")
    @Operation(summary = "List existing async-profiler profiles on the specified target")
    public Uni<List<AsyncProfile>> list(@RestPath long targetId) throws Exception {
        Target target = Target.find("id", targetId).singleResult();
        return executeUni(target, AgentConnection::listAsyncProfiles);
    }

    @DELETE
    @Transactional
    @Blocking
    @Path("/{profileId}")
    @RolesAllowed("write")
    @Operation(summary = "Delete an async-profiler profile from the specified target")
    public Uni<Void> delete(@RestPath long targetId, @RestPath String profileId) throws Exception {
        Target target = Target.find("id", targetId).singleResult();
        return executeUni(target, conn -> conn.deleteAsyncProfile(profileId))
                .invoke(
                        () -> {
                            var payload = AsyncProfilerEvent.Payload.of(target, profileId, 0);
                            notify(
                                    new AsyncProfilerEvent(
                                            AsyncProfiler.AsyncProfilerEventCategory.DELETED,
                                            payload));
                        })
                .map(v -> null);
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

    interface AgentConnectedTask<T> {
        T execute(AgentConnection connection) throws Exception;
    }

    public record AsyncProfilerEvent(
            AsyncProfiler.AsyncProfilerEventCategory category, Payload payload) {
        public AsyncProfilerEvent {
            Objects.requireNonNull(category);
            Objects.requireNonNull(payload);
        }

        public record Payload(Target target, String id, long duration) {
            public Payload {
                Objects.requireNonNull(target);
                Objects.requireNonNull(id);
                Objects.requireNonNull(duration);
            }

            public static Payload of(Target target, String id, Duration duration) {
                return new Payload(target, id, duration.toSeconds());
            }

            public static Payload of(Target target, String id, long duration) {
                return new Payload(target, id, duration);
            }
        }
    }

    @DisallowConcurrentExecution
    static class AsyncProfilerUpdateJob implements Job {

        @Inject EventBus bus;
        @Inject Scheduler scheduler;
        @Inject TargetConnectionManager tcm;
        @Inject Logger logger;

        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            Target target =
                    QuarkusTransaction.joiningExisting()
                            .call(
                                    () ->
                                            Target.find(
                                                            "id",
                                                            context.getMergedJobDataMap()
                                                                    .getLong("targetId"))
                                                    .singleResult());
            String id = context.getMergedJobDataMap().getString("id");
            long duration = context.getMergedJobDataMap().getLong("duration");
            tcm.executeConnectedTaskUni(
                            target, conn -> ((AgentConnection) conn).asyncProfilerStatus())
                    .subscribe()
                    .with(
                            s -> {
                                if (s.status().equals(ProfilerStatus.STOPPED)) {
                                    var payload =
                                            AsyncProfilerEvent.Payload.of(target, id, duration);
                                    notify(
                                            new AsyncProfilerEvent(
                                                    AsyncProfiler.AsyncProfilerEventCategory
                                                            .STOPPED,
                                                    payload));
                                    cancelJob(context);
                                }
                            },
                            t -> {
                                logger.error(t);
                                cancelJob(context);
                            });
        }

        void cancelJob(JobExecutionContext context) {
            try {
                scheduler.deleteJob(context.getTrigger().getJobKey());
            } catch (SchedulerException se) {
                logger.error(se);
            }
        }

        void notify(AsyncProfilerEvent event) {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(event.category().category(), event.payload()));
        }
    }
}
