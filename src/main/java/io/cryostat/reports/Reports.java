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

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import io.cryostat.ConfigProperties;
import io.cryostat.StorageBuckets;
import io.cryostat.core.reports.InterruptibleReportGenerator.AnalysisResult;
import io.cryostat.recordings.LongRunningRequestGenerator;
import io.cryostat.recordings.LongRunningRequestGenerator.ActiveReportRequest;
import io.cryostat.recordings.LongRunningRequestGenerator.ArchivedReportRequest;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.targets.Target;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;

@Path("")
public class Reports {

    @ConfigProperty(name = ConfigProperties.REPORTS_STORAGE_CACHE_ENABLED)
    boolean storageCacheEnabled;

    @ConfigProperty(name = ConfigProperties.ARCHIVED_REPORTS_STORAGE_CACHE_NAME)
    String bucket;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration timeout;

    @Inject LongRunningRequestGenerator generator;
    @Inject StorageBuckets storageBuckets;
    @Inject RecordingHelper helper;
    @Inject ReportsService reportsService;
    @Inject AnalysisReportAggregator reportAggregator;
    @Inject EventBus bus;
    @Inject Logger logger;

    // FIXME this observer cannot be declared on the StorageCachingReportsService decorator.
    // Refactor to put this somewhere more sensible
    void onStart(@Observes StartupEvent evt) {
        if (storageCacheEnabled) {
            storageBuckets.createIfNecessary(bucket);
        }
    }

    @GET
    @Blocking
    @Path("/api/v4/reports/{encodedKey}")
    @RolesAllowed("read")
    // Response isn't strongly typed which allows us to return either the Analysis result
    // or a job ID String along with setting different Status codes.
    // TODO: Is there a cleaner way to accomplish this?
    public Response get(HttpServerResponse response, @RestPath String encodedKey) {
        // TODO implement query parameter for evaluation predicate
        var pair = helper.decodedKey(encodedKey);

        // Check if we have a cached result already for this report
        if (reportsService.keyExists(pair.getKey(), pair.getValue())) {
            return Response.ok(
                            reportsService
                                    .reportFor(pair.getKey(), pair.getValue())
                                    .await()
                                    .atMost(timeout),
                            MediaType.APPLICATION_JSON)
                    .status(200)
                    .build();
        }

        // If we don't have a cached result, delegate to the ArchiveRequestGenerator
        // and return the job ID with a location header.
        logger.trace("Cache miss. Creating archived reports request");
        ArchivedReportRequest request =
                new ArchivedReportRequest(UUID.randomUUID().toString(), pair);
        response.bodyEndHandler(
                (e) -> bus.publish(LongRunningRequestGenerator.ARCHIVE_REPORT_ADDRESS, request));
        return Response.ok(request.getId(), MediaType.TEXT_PLAIN)
                .status(202)
                .location(
                        UriBuilder.fromUri(String.format("/api/v4/reports/%s", encodedKey)).build())
                .build();
    }

    @GET
    @Path("/api/v4/targets/{targetId}/reports")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("read")
    public Map<String, AnalysisResult> getCached(@RestPath long targetId) {
        var target = Target.getTargetById(targetId);
        return reportAggregator.getReport(target.jvmId);
    }

    @GET
    @Blocking
    @Path("/api/v4/targets/{targetId}/reports/{recordingId}")
    @RolesAllowed("read")
    // Response isn't strongly typed which allows us to return either the Analysis result
    // or a job ID String along with setting different Status codes.
    // TODO: Is there a cleaner way to accomplish this?
    public Response getActive(
            HttpServerResponse response, @RestPath long targetId, @RestPath long recordingId)
            throws Exception {
        var target = Target.getTargetById(targetId);
        var recording = target.getRecordingById(recordingId);
        if (recording == null) {
            throw new NotFoundException();
        }

        // Check if we've already cached a result for this report, return it if so
        if (reportsService.keyExists(recording)) {
            return Response.ok(
                            reportsService.reportFor(recording).await().atMost(timeout),
                            MediaType.APPLICATION_JSON)
                    .status(200)
                    .build();
        }

        // If there isn't a cached result available, delegate to the ArchiveRequestGenerator
        // and return the job ID with a location header.
        logger.trace("Cache miss. Creating active reports request");
        ActiveReportRequest request =
                new ActiveReportRequest(UUID.randomUUID().toString(), recording);
        response.bodyEndHandler(
                (e) -> bus.publish(LongRunningRequestGenerator.ACTIVE_REPORT_ADDRESS, request));
        // TODO implement query parameter for evaluation predicate
        return Response.ok(request.getId(), MediaType.TEXT_PLAIN)
                .status(202)
                .location(
                        UriBuilder.fromUri(
                                        String.format(
                                                "/api/v4/targets/%d/reports/%d",
                                                target.id, recordingId))
                                .build())
                .build();
    }
}
