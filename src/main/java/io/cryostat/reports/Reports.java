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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.openjdk.jmc.flightrecorder.CouldNotLoadRecordingException;

import io.cryostat.ConfigProperties;
import io.cryostat.Producers;
import io.cryostat.core.reports.InterruptibleReportGenerator;
import io.cryostat.core.reports.InterruptibleReportGenerator.RuleEvaluation;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.recordings.RemoteRecordingInputStreamFactory;
import io.cryostat.targets.Target;

import io.quarkus.cache.CacheResult;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.codec.binary.Base64;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import software.amazon.awssdk.services.s3.S3Client;

@Path("")
public class Reports {

    static final String ACTIVE_CACHE = "active-reports";
    static final String ARCHIVED_CACHE = "archived-reports";

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_ARCHIVES)
    String archiveBucket;

    @Inject
    @Named(Producers.BASE64_URL)
    Base64 base64Url;

    @Inject S3Client storage;
    @Inject RecordingHelper helper;
    @Inject RemoteRecordingInputStreamFactory remoteStreamFactory;
    @Inject InterruptibleReportGenerator reportGenerator;
    @Inject Logger logger;

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
        if (result.size() != 1) {
            throw new NotFoundException();
        }
        var entry = result.entrySet().iterator().next();
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(
                        URI.create(
                                String.format(
                                        "/api/v3/reports/%s", entry.getKey(), entry.getValue())))
                .build();
    }

    @Blocking
    // TODO proactively invalidate cache when recording is deleted
    @CacheResult(cacheName = ARCHIVED_CACHE)
    @GET
    @Path("/api/v3/reports/{encodedKey}")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("read")
    public Uni<Map<String, RuleEvaluation>> get(@RestPath String encodedKey)
            throws IOException, CouldNotLoadRecordingException {
        // TODO implement query parameter for evaluation predicate
        return Uni.createFrom()
                .future(
                        reportGenerator.generateEvalMapInterruptibly(
                                new BufferedInputStream(
                                        helper.getArchivedRecordingStream(encodedKey)),
                                r -> true));
    }

    @Blocking
    @GET
    @Path("/api/v1/targets/{targetId}/reports/{recordingName}")
    @Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_HTML, MediaType.TEXT_PLAIN})
    @RolesAllowed("read")
    @Deprecated(since = "3.0", forRemoval = true)
    public Response getActiveV1(@RestPath String targetId, @RestPath String recordingName) {
        var target = Target.getTargetByConnectUrl(URI.create(targetId));
        var recording =
                target.activeRecordings.stream()
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

    @Blocking
    // TODO proactively invalidate cache when recording is deleted or target disappears
    @CacheResult(cacheName = ACTIVE_CACHE)
    @GET
    @Path("/api/v3/targets/{targetId}/reports/{recordingId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("read")
    @Deprecated(since = "3.0", forRemoval = true)
    public Uni<Map<String, RuleEvaluation>> getActive(
            @RestPath long targetId, @RestPath long recordingId) throws Exception {
        var target = Target.<Target>findById(targetId);
        var recording = target.getRecordingById(recordingId);
        // TODO implement query parameter for evaluation predicate
        return Uni.createFrom()
                .future(
                        reportGenerator.generateEvalMapInterruptibly(
                                new BufferedInputStream(helper.getActiveInputStream(recording)),
                                r -> true));
    }
}
