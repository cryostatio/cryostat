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

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import io.cryostat.recordings.LongRunningRequestGenerator;
import io.cryostat.recordings.LongRunningRequestGenerator.ThreadDumpRequest;
import io.cryostat.targets.Target;
import io.cryostat.util.ResponseDispatch;

import io.quarkus.security.PermissionsAllowed;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;

@Path("/api/v5/targets/{jvmId}/diagnostics/threaddump")
public class TargetThreadDumpEndpoint {

    @Inject Logger log;
    @Inject LongRunningRequestGenerator generator;
    @Inject EventBus bus;
    @Inject DiagnosticsHelper helper;

    @PermissionsAllowed(
            value = {"targets:read", "threaddumps:write"},
            inclusive = true)
    @Blocking
    @Transactional
    @POST
    public String threadDump(
            HttpServerResponse response,
            @RestPath String jvmId,
            @QueryParam("format") @DefaultValue(DiagnosticsHelper.DUMP_THREADS) String format) {
        log.tracev("Creating new thread dump request for target: {0}", jvmId);
        Target target = Target.getTargetByJvmId(jvmId).orElseThrow();
        String jobId = UUID.randomUUID().toString();

        io.cryostat.diagnostic.ThreadDump.requested(target, jobId, format).persist();

        ThreadDumpRequest request = new ThreadDumpRequest(jobId, target.id, format);
        ResponseDispatch.onComplete(
                response,
                () -> bus.publish(LongRunningRequestGenerator.THREAD_DUMP_ADDRESS, request));
        return request.id();
    }

    @PermissionsAllowed(
            value = {"targets:read", "threaddumps:read"},
            inclusive = true)
    @Blocking
    @Transactional
    @GET
    public List<Diagnostics.ThreadDump> getThreadDumps(@RestPath String jvmId) {
        log.tracev("Fetching thread dumps for target: {0}", jvmId);
        return helper.getThreadDumps(jvmId);
    }

    @Path("/{threadDumpId}")
    @DELETE
    @Blocking
    @Transactional
    @PermissionsAllowed(
            value = {"targets:read", "threaddumps:delete"},
            inclusive = true)
    public void deleteThreadDump(@RestPath String jvmId, @RestPath String threadDumpId) {
        log.tracev("Deleting thread dump with ID: {0}", threadDumpId);
        helper.deleteThreadDump(jvmId, threadDumpId);
    }

    @Path("/{threadDumpId}/analyze")
    @POST
    @Blocking
    @Transactional
    @PermissionsAllowed(
            value = {"targets:read", "threaddumps:write"},
            inclusive = true)
    public ThreadDumpAnalysis analyzeThreadDump(
            @RestPath String jvmId, @RestPath String threadDumpId) throws IOException {
        return helper.analyzeThreadDump(jvmId, threadDumpId);
    }
}
