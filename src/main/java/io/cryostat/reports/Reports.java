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
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;

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

    @GET
    @Blocking
    @Path("/api/v4/reports/{encodedKey}")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("read")
    public Uni<Map<String, AnalysisResult>> get(@RestPath String encodedKey) {
        // TODO implement query parameter for evaluation predicate
        var pair = helper.decodedKey(encodedKey);
        return reportsService.reportFor(pair.getKey(), pair.getValue());
    }

    @GET
    @Blocking
    @Path("/api/v4/targets/{targetId}/reports/{recordingId}")
    @Produces({MediaType.APPLICATION_JSON})
    @RolesAllowed("read")
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
