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

import java.util.UUID;

import io.cryostat.ConfigProperties;
import io.cryostat.StorageBuckets;
import io.cryostat.recordings.ArchiveRequestGenerator;
import io.cryostat.recordings.ArchiveRequestGenerator.ActiveReportRequest;
import io.cryostat.recordings.ArchiveRequestGenerator.ArchivedReportRequest;
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
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("read")
    public String get(HttpServerResponse response, @RestPath String encodedKey) {
        // TODO implement query parameter for evaluation predicate
        logger.info("Creating archived reports request");
        var pair = helper.decodedKey(encodedKey);
        ArchivedReportRequest request =
                new ArchivedReportRequest(UUID.randomUUID().toString(), pair);
        response.endHandler(
                (e) -> bus.publish(ArchiveRequestGenerator.ARCHIVE_REPORT_ADDRESS, request));
        return request.getId();
        // return reportsService.reportFor(pair.getKey(), pair.getValue());
    }

    @GET
    @Blocking
    @Path("/api/v4/targets/{targetId}/reports/{recordingId}")
    @Produces({MediaType.APPLICATION_JSON})
    @RolesAllowed("read")
    public String getActive(
            HttpServerResponse response, @RestPath long targetId, @RestPath long recordingId)
            throws Exception {

        logger.info("Creating active reports request");
        var target = Target.getTargetById(targetId);
        var recording = target.getRecordingById(recordingId);
        if (recording == null) {
            throw new NotFoundException();
        }

        ActiveReportRequest request =
                new ActiveReportRequest(UUID.randomUUID().toString(), recording);
        response.endHandler(
                (e) -> bus.publish(ArchiveRequestGenerator.ACTIVE_REPORT_ADDRESS, request));
        // TODO implement query parameter for evaluation predicate
        return request.getId();
        // return reportsService.reportFor(recording);
    }
}
