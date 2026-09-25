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
package io.cryostat.diagnostic;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.cryostat.ConfigProperties;
import io.cryostat.recordings.LongRunningRequestGenerator;
import io.cryostat.recordings.LongRunningRequestGenerator.HeapDumpAnalysisRequest;
import io.cryostat.recordings.LongRunningRequestGenerator.HeapDumpRequest;
import io.cryostat.targets.Target;
import io.cryostat.util.ResponseDispatch;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.security.PermissionsAllowed;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

@Path("/api/v5/targets/{jvmId}/diagnostics/heap-dump")
public class TargetHeapDumpEndpoint {

    @Inject S3Client storage;
    @Inject Logger log;
    @Inject LongRunningRequestGenerator generator;
    @Inject HeapDumpReportsService reportService;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_HEAP_DUMPS)
    String heapDumpsBucket;

    @Inject EventBus bus;
    @Inject DiagnosticsHelper helper;

    @PermissionsAllowed(
            value = {"targets:read", "heapdumps:write"},
            inclusive = true)
    @POST
    @Blocking
    @Transactional
    @Operation(
            summary = "Initiates a heap dump on the specified target",
            description =
                    """
                    Request the remote target to perform a heap dump.
                    """)
    public String heapDump(HttpServerResponse response, @RestPath String jvmId) {
        log.tracev("Initiating heap dump for target: {0}", jvmId);
        Target target = Target.getTargetByJvmId(jvmId).orElseThrow();
        if (!target.isAgent()) {
            // While we can trigger a heap dump in a JMX target, without the agent
            // we can't retrieve it. We should fail here.
            throw new BadRequestException("Target is not an agent connection.");
        }
        String jobId = UUID.randomUUID().toString();

        io.cryostat.diagnostic.HeapDump.requested(target, jobId).persist();

        HeapDumpRequest request = new HeapDumpRequest(jobId, target.id);
        ResponseDispatch.onComplete(
                response,
                () -> bus.publish(LongRunningRequestGenerator.HEAP_DUMP_REQUEST_ADDRESS, request));
        return request.id();
    }

    @Path("/upload")
    @PermissionsAllowed(value = "heapdumps:write", inclusive = true)
    @Blocking
    @POST
    public void uploadHeapDump(
            @RestPath String jvmId,
            @Parameter(required = true) @RestForm("heapDump") FileUpload heapDump,
            @Parameter(required = true) @RestForm("jobId") String jobId,
            @Parameter(required = false) @RestForm("labels") JsonObject rawLabels) {
        log.tracev(
                "Received heap dump upload request for target: {0} with job ID {1}", jvmId, jobId);
        jvmId = jvmId.strip();
        doUpload(heapDump, jvmId, jobId);
    }

    @Blocking
    @Transactional
    @SuppressFBWarnings("DLS_DEAD_LOCAL_STORE")
    Map<String, Object> doUpload(FileUpload heapDump, String jvmId, String jobId) {
        var dump = helper.addHeapDump(jvmId, heapDump, jobId);

        io.cryostat.diagnostic.HeapDump.<io.cryostat.diagnostic.HeapDump>find("jobId", jobId)
                .firstResultOptional()
                .ifPresent(
                        hd -> {
                            hd.markCompleted(dump.heapDumpId(), dump.size());
                            hd.persist();
                        });

        return Map.of("name", dump.heapDumpId());
    }

    @PermissionsAllowed(
            value = {"targets:read", "heapdumps:read"},
            inclusive = true)
    @Blocking
    @Transactional
    @GET
    public List<Diagnostics.HeapDump> getHeapDumps(@RestPath String jvmId) {
        log.tracev("Fetching heap dumps for target: {0}", jvmId);
        return helper.getHeapDumps(jvmId);
    }

    @Path("/{heapDumpId}")
    @DELETE
    @Blocking
    @PermissionsAllowed(
            value = {"targets:read", "heapdumps:delete"},
            inclusive = true)
    public void deleteHeapDump(@RestPath String jvmId, @RestPath String heapDumpId) {
        log.tracev("Deleting heap dump with ID: {0}", heapDumpId);
        helper.deleteHeapDump(jvmId, heapDumpId);
    }

    @Path("/{heapDumpId}/analyze")
    @PermissionsAllowed(
            value = {"targets:read", "heapdumps:read"},
            inclusive = true)
    @Blocking
    @Transactional
    @POST
    public Response analyzeHeapDump(
            HttpServerResponse response, @RestPath String jvmId, @RestPath String heapDumpId) {
        String key = DiagnosticsHelper.storageKey(jvmId, heapDumpId);
        storage.headObject(HeadObjectRequest.builder().bucket(heapDumpsBucket).key(key).build())
                .sdkHttpResponse();
        if (reportService.keyExists(jvmId, heapDumpId)) {
            return Response.ok(
                            reportService.reportFor(jvmId, heapDumpId).await().indefinitely(),
                            MediaType.APPLICATION_JSON)
                    .status(200)
                    .build();
        }

        log.trace("Cache miss. Creating heap dump reports request");
        var jobId = UUID.randomUUID().toString();
        HeapDumpAnalysisRequest request = new HeapDumpAnalysisRequest(jobId, jvmId, heapDumpId);
        ResponseDispatch.onComplete(
                response,
                () ->
                        bus.publish(
                                LongRunningRequestGenerator.HEAP_DUMP_ANALYSIS_REQUEST_ADDRESS,
                                request));
        return Response.ok(request.id(), MediaType.TEXT_PLAIN)
                .status(202)
                .location(
                        UriBuilder.fromUri(
                                        String.format(
                                                "/api/v5/targets/%s/diagnostics/heapdump/%s/analyze",
                                                jvmId, heapDumpId))
                                .build())
                .build();
    }
}
