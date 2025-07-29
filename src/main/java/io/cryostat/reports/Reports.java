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
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import org.openjdk.jmc.flightrecorder.rules.IRule;
import org.openjdk.jmc.flightrecorder.rules.RuleRegistry;
import org.openjdk.jmc.flightrecorder.rules.util.RulesToolkit.EventAvailability;

import io.cryostat.ConfigProperties;
import io.cryostat.StorageBuckets;
import io.cryostat.core.reports.InterruptibleReportGenerator.AnalysisResult;
import io.cryostat.recordings.LongRunningRequestGenerator;
import io.cryostat.recordings.LongRunningRequestGenerator.ActiveReportRequest;
import io.cryostat.recordings.LongRunningRequestGenerator.ArchiveRequest;
import io.cryostat.recordings.LongRunningRequestGenerator.ArchivedReportRequest;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.targets.Target;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;

@Path("")
public class Reports {

    private static final String TARGET_ANALYSIS_LABEL_KEY = "source";
    private static final String TARGET_ANALYSIS_LABEL_VALUE = "target-analysis";

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
    @Operation(
            summary = "Get an automated analysis report",
            description =
                    """
                    Given an encoded key from another endpoint, request an actual automated analysis report. If the
                    requested report already exists in one of the tiered caching layers then the report will be
                    directly returned as a JSON response body. If the report does not yet exist then the response will
                    contain a Job UUID as a plain text response body. Cryostat will emit a WebSocket notification later
                    using the same Job UUID to indicate that the report generation has been completed and the document
                    is now available. The client may then re-issue a request to this endpoint with the same encoded key
                    to retrieve the report document.
                    """)
    // Response isn't strongly typed which allows us to return either the Analysis result
    // or a job ID String along with setting different Status codes.
    // TODO: Is there a cleaner way to accomplish this?
    public Response get(
            HttpServerResponse response,
            @RestPath String encodedKey,
            @QueryParam("filter") @DefaultValue("") String filter) {
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
                new ArchivedReportRequest(UUID.randomUUID().toString(), pair, filter);
        response.bodyEndHandler(
                (e) ->
                        bus.publish(
                                LongRunningRequestGenerator.ARCHIVE_REPORT_REQUEST_ADDRESS,
                                request));
        return Response.ok(request.id(), MediaType.TEXT_PLAIN)
                .status(202)
                .location(
                        UriBuilder.fromUri(String.format("/api/v4/reports/%s", encodedKey)).build())
                .build();
    }

    @GET
    @Path("/api/v4.1/reports_rules")
    @RolesAllowed("read")
    public Stream<ReportRule> listReportRules() {
        return RuleRegistry.getRules().stream()
                .map(ReportRule::new)
                .sorted((a, b) -> StringUtils.compare(a.id(), b.id()));
    }

    @POST
    @Blocking
    @Transactional
    @Path("/api/v4.1/targets/{targetId}/reports")
    @RolesAllowed("write")
    @Operation(
            summary = "Perform \"target analysis\" on the specified target",
            description =
                    """
                    Composite action that 1) creates a Snapshot active recording on the specified target, 2) archives
                    that Snapshot immediately, 3) performs automated analysis report generation on the archived file.
                    The response will include a Location header pointing the client to an endpoint where the report can
                    be retrieved, which may require the client to wait for a Job UUID notification.
                    """)
    public Response analyze(
            HttpServerResponse resp,
            @RestPath long targetId,
            @QueryParam("clean") @DefaultValue("true") boolean clean) {
        var target = Target.getTargetById(targetId);
        var jobId = UUID.randomUUID().toString();
        resp.bodyEndHandler(
                (v) -> {
                    helper.createSnapshot(
                                    target,
                                    Map.of(
                                            AnalysisReportAggregator.AUTOANALYZE_LABEL,
                                            "true",
                                            TARGET_ANALYSIS_LABEL_KEY,
                                            TARGET_ANALYSIS_LABEL_VALUE))
                            .runSubscriptionOn(Infrastructure.getDefaultExecutor())
                            .subscribe()
                            .with(
                                    recording -> {
                                        var request = new ArchiveRequest(jobId, recording, clean);
                                        bus.publish(
                                                LongRunningRequestGenerator.ARCHIVE_REQUEST_ADDRESS,
                                                request);
                                    });
                });
        return Response.ok(jobId, MediaType.TEXT_PLAIN)
                .status(Response.Status.ACCEPTED)
                .location(
                        UriBuilder.fromUri(String.format("/api/v4.1/targets/%d/reports", targetId))
                                .build())
                .build();
    }

    @GET
    @Blocking
    @Path("/api/v4.1/targets/{targetId}/reports")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("read")
    @Operation(
            summary = "Retrieve current automated analysis report for a target",
            description =
                    """
                    Get the current cached automated analysis report for the specified target, if any. If no such
                    report currently exists for the specified target then the response will be an HTTP 404 Not Found,
                    and automated analysis report generation will not be triggered.
                    """)
    public Uni<RestResponse<Map<String, AnalysisResult>>> getCached(@RestPath long targetId) {
        var target = Target.getTargetById(targetId);
        return reportAggregator
                .getEntry(target.jvmId)
                .onItem()
                .transform(
                        e -> {
                            var builder =
                                    RestResponse.ResponseBuilder
                                            .<Map<String, AnalysisResult>>create(200)
                                            .entity(e.report());
                            var timestamp = e.timestamp();
                            if (timestamp > 0) {
                                builder.lastModified(Date.from(Instant.ofEpochSecond(timestamp)));
                            }
                            return builder.build();
                        });
    }

    @GET
    @Blocking
    @Path("/api/v4/targets/{targetId}/reports/{recordingId}")
    @RolesAllowed("read")
    @Operation(
            summary =
                    "Get an automated analysis report for a particular recording on the specified"
                            + " target",
            description =
                    """
                    Request an automated analysis report for a particular active recording on the specified target. If
                    such a report already exists it will be returned directly as a JSON response body. If the report
                    does not yet exist then an asynchronous task for it will be started, and a Job UUID will be
                    sent as the plaintext response body.
                    """)
    // Response isn't strongly typed which allows us to return either the Analysis result
    // or a job ID String along with setting different Status codes.
    // TODO: Is there a cleaner way to accomplish this?
    public Response getActive(
            HttpServerResponse response,
            @RestPath long targetId,
            @RestPath long recordingId,
            @QueryParam("filter") @DefaultValue("") String filter)
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
                    .status(Response.Status.OK)
                    .build();
        }

        // If there isn't a cached result available, delegate to the ArchiveRequestGenerator
        // and return the job ID with a location header.
        logger.trace("Cache miss. Creating active reports request");
        ActiveReportRequest request =
                new ActiveReportRequest(UUID.randomUUID().toString(), recording, filter);
        response.bodyEndHandler(
                (e) ->
                        bus.publish(
                                LongRunningRequestGenerator.ACTIVE_REPORT_REQUEST_ADDRESS,
                                request));
        return Response.ok(request.id(), MediaType.TEXT_PLAIN)
                .status(Response.Status.ACCEPTED)
                .location(
                        UriBuilder.fromUri(
                                        String.format(
                                                "/api/v4/targets/%d/reports/%d",
                                                target.id, recordingId))
                                .build())
                .build();
    }

    private record ReportRule(
            String id, String name, String topic, Map<String, EventAvailability> requiredEvents) {
        ReportRule {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
            Objects.requireNonNull(topic);
            Objects.requireNonNull(requiredEvents);
        }

        ReportRule(IRule rule) {
            this(rule.getId(), rule.getName(), rule.getTopic(), rule.getRequiredEvents());
        }
    }
}
