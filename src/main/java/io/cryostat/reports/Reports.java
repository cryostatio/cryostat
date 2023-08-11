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
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import org.openjdk.jmc.flightrecorder.CouldNotLoadRecordingException;

import io.cryostat.ConfigProperties;
import io.cryostat.ProgressInputStream;
import io.cryostat.core.reports.InterruptibleReportGenerator;
import io.cryostat.core.reports.InterruptibleReportGenerator.RuleEvaluation;
import io.cryostat.recordings.RemoteRecordingInputStreamFactory;
import io.cryostat.targets.Target;

import com.fasterxml.jackson.annotation.JsonValue;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

@Path("")
public class Reports {

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_ARCHIVES)
    String archiveBucket;

    @Inject S3Client storage;
    @Inject RemoteRecordingInputStreamFactory remoteStreamFactory;
    @Inject InterruptibleReportGenerator reportGenerator;
    @Inject Logger logger;

    @Blocking
    @GET
    @Path("/api/v1/reports/{recordingName}")
    @Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_HTML, MediaType.TEXT_PLAIN})
    @RolesAllowed("read")
    @Deprecated(since = "3.0", forRemoval = true)
    public Response getV1(@RestPath String recordingName) {
        var result = new HashMap<String, String>();
        storage.listObjectsV2(ListObjectsV2Request.builder().bucket(archiveBucket).build())
                .contents()
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
    @GET
    @Path("/api/v3/reports/{encodedKey}")
    @Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_HTML, MediaType.TEXT_PLAIN})
    @RolesAllowed("read")
    public Report get(@RestPath String encodedKey)
            throws IOException, CouldNotLoadRecordingException {
        final Base64 base64Url = new Base64(0, null, true);
        String key = new String(base64Url.decode(encodedKey), StandardCharsets.UTF_8);

        GetObjectRequest getRequest =
                GetObjectRequest.builder().bucket(archiveBucket).key(key).build();
        var stream = storage.getObject(getRequest);
        var progress =
                new ProgressInputStream(
                        stream,
                        n ->
                                logger.infov(
                                        "Streamed {0} of JFR data from S3...",
                                        FileUtils.byteCountToDisplaySize(n)));
        return new Report(reportGenerator, new BufferedInputStream(progress), logger);
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
    @GET
    @Path("/api/v3/targets/{targetId}/reports/{recordingId}")
    @Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_HTML, MediaType.TEXT_PLAIN})
    @RolesAllowed("read")
    @Deprecated(since = "3.0", forRemoval = true)
    public Report getActive(@RestPath long targetId, @RestPath long recordingId) throws Exception {
        var target = Target.<Target>findById(targetId);
        var recording = target.getRecordingById(recordingId);
        var stream = remoteStreamFactory.open(target, recording);
        var progress =
                new ProgressInputStream(
                        stream,
                        n ->
                                logger.infov(
                                        "Streamed {0} of JFR data from target...",
                                        FileUtils.byteCountToDisplaySize(n)));
        return new Report(reportGenerator, new BufferedInputStream(progress), logger);
    }

    private record Report(
            InterruptibleReportGenerator generator, InputStream stream, Logger logger) {
        Report {
            Objects.requireNonNull(generator);
            Objects.requireNonNull(stream);
            Objects.requireNonNull(logger);
        }

        @JsonValue
        Map<String, RuleEvaluation> asJson()
                throws IOException, InterruptedException, ExecutionException {
            try (stream) {
                return generator.generateEvalMapInterruptibly(stream, r -> true).get();
            }
        }

        @Override
        public String toString() {
            try (stream) {
                return generator.generateReportInterruptibly(stream).get().getHtml();
            } catch (IOException | InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
