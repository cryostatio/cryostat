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

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import io.cryostat.ConfigProperties;
import io.cryostat.StorageBuckets;
import io.cryostat.core.reports.InterruptibleReportGenerator.AnalysisResult;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.targets.Target;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;

@Path("")
public class Reports {

    @ConfigProperty(name = ConfigProperties.REPORTS_STORAGE_CACHE_ENABLED)
    boolean storageCacheEnabled;

    @ConfigProperty(name = ConfigProperties.ARCHIVED_REPORTS_STORAGE_CACHE_NAME)
    String bucket;

    @Inject StorageBuckets storageBuckets;
    @Inject RecordingHelper helper;
    @Inject ReportsService reportsService;
    @Inject Logger logger;

    // FIXME this observer cannot be declared on the StorageCachingReportsService decorator.
    // Refactor to put this somewhere more sensible
    void onStart(@Observes StartupEvent evt) {
        if (storageCacheEnabled) {
            storageBuckets.createIfNecessary(bucket);
        }
    }

    @Blocking
    @GET
    @Path("/api/v1/reports/{recordingName}")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("read")
    @Deprecated(since = "3.0", forRemoval = true)
    public Response getV1(@RestPath String recordingName) {
        var result = new HashMap<String, String>();
        helper.listArchivedRecordingObjects()
                .forEach(
                        item -> {
                            String objectName = item.key().strip();
                            String jvmId = objectName.split("/")[0];
                            String filename = objectName.split("/")[1];
                            result.put(jvmId, filename);
                        });
        if (result.size() == 0) {
            throw new NotFoundException();
        }
        if (result.size() > 1) {
            throw new ClientErrorException(Response.Status.CONFLICT);
        }
        var entry = result.entrySet().iterator().next();
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(
                        URI.create(
                                String.format(
                                        "/api/v3/reports/%s", entry.getKey(), entry.getValue())))
                .build();
    }

    @GET
    @Blocking
    @Path("/api/v3/reports/{encodedKey}")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("read")
    public Uni<Map<String, AnalysisResult>> get(@RestPath String encodedKey) {
        // TODO implement query parameter for evaluation predicate
        var pair = helper.decodedKey(encodedKey);
        return reportsService.reportFor(pair.getKey(), pair.getValue());
    }

    @GET
    @Blocking
    @Transactional
    @Path("/api/v1/targets/{targetId}/reports/{recordingName}")
    @Produces({MediaType.APPLICATION_JSON})
    @RolesAllowed("read")
    @Deprecated(since = "3.0", forRemoval = true)
    public Response getActiveV1(@RestPath String targetId, @RestPath String recordingName) {
        var target = Target.getTargetByConnectUrl(URI.create(targetId));
        var recording =
                helper.listActiveRecordings(target).stream()
                        .filter(r -> r.name.equals(recordingName))
                        .findFirst()
                        .orElseThrow();
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(
                        URI.create(
                                String.format(
                                        "/api/v3/targets/%d/reports/%d",
                                        target.id, recording.remoteId)))
                .build();
    }

    @GET
    @Blocking
    @Path("/api/v3/targets/{targetId}/reports/{recordingId}")
    @Produces({MediaType.APPLICATION_JSON})
    @RolesAllowed("read")
    @Deprecated(since = "3.0", forRemoval = true)
    public Uni<Map<String, AnalysisResult>> getActive(
            @RestPath long targetId, @RestPath long recordingId) throws Exception {
        var target = Target.getTargetById(targetId);
        var recording = target.getRecordingById(recordingId);
        if (recording == null) {
            throw new NotFoundException();
        }
        // TODO implement query parameter for evaluation predicate
        return reportsService.reportFor(recording);
    }
}
