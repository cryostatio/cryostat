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
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import io.cryostat.ConfigProperties;
import io.cryostat.core.reports.InterruptibleReportGenerator;
import io.cryostat.core.reports.InterruptibleReportGenerator.AnalysisResult;
import io.cryostat.core.util.RuleFilterParser;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingHelper;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Implementation for automated analysis report generation. Handles direct report generation or
 * delegation to sidecar reports generators (see https://github.com/cryostatio/cryostat-reports),
 * for active or archived recordings. Results, whether directly generated or retrieved from
 * delegated sidecars, may be entered into tiered cache layers.
 *
 * @see io.cryostat.recordings.LongRunningRequestGenerator
 * @see io.cryostat.reports.MemoryCachingReportsService
 * @see io.cryostat.reports.StorageCachingReportsService
 */
@ApplicationScoped
class ReportsServiceImpl implements ReportsService {

    private static final String NO_SIDECAR_URL = "http://localhost/";

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_UPLOAD_TIMEOUT)
    Duration uploadFailedTimeout;

    @ConfigProperty(name = ConfigProperties.REPORTS_SIDECAR_URL)
    String sidecarUri;

    @ConfigProperty(name = ConfigProperties.REPORTS_USE_PRESIGNED_TRANSFER)
    boolean usePresignedTransfer;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_ARCHIVES)
    String archiveBucket;

    @ConfigProperty(name = ConfigProperties.STORAGE_EXT_URL)
    Optional<String> externalStorageUrl;

    @ConfigProperty(name = ConfigProperties.REPORTS_FILTER)
    Optional<String> configFilter;

    @Inject ObjectMapper mapper;
    @Inject RecordingHelper helper;
    @Inject InterruptibleReportGenerator reportGenerator;
    @Inject RuleFilterParser ruleFilterParser;
    @Inject @RestClient ReportSidecarService sidecar;
    @Inject S3Presigner presigner;
    @Inject Logger logger;

    @Override
    public Uni<Map<String, AnalysisResult>> reportFor(ActiveRecording recording, String filter) {
        InputStream stream;
        try {
            stream = helper.getActiveInputStream(recording, uploadFailedTimeout);
        } catch (Exception e) {
            throw new ReportGenerationException(e);
        }
        if (!useSidecar()) {
            logger.tracev(
                    "inprocess reportFor active recording {0} {1}",
                    recording.target.jvmId, recording.remoteId);
            return process(stream, filter);
        } else {
            logger.tracev(
                    "sidecar reportFor active recording {0} {1}",
                    recording.target.jvmId, recording.remoteId);
            return fireRequest(stream, filter);
        }
    }

    @Override
    public Uni<Map<String, AnalysisResult>> reportFor(
            String jvmId, String filename, String filter) {
        InputStream stream;
        try {
            stream = helper.getArchivedRecordingStream(jvmId, filename);
        } catch (Exception e) {
            throw new ReportGenerationException(e);
        }
        if (!useSidecar()) {
            logger.tracev("inprocess reportFor archived recording {0} {1}", jvmId, filename);
            return process(stream, filter);
        } else if (usePresignedSidecar()) {
            logger.tracev(
                    "sidecar reportFor presigned archived recording {0} {1}", jvmId, filename);
            try {
                var uri = getPresignedPath(jvmId, filename);
                return sidecar.generatePresigned(uri.getPath(), uri.getQuery(), filter);
            } catch (URISyntaxException e) {
                logger.error(e);
                throw new InternalServerErrorException(e);
            }
        } else {
            logger.tracev("sidecar reportFor archived recording {0} {1}", jvmId, filename);
            return fireRequest(stream, filter);
        }
    }

    @Override
    public Uni<Map<String, AnalysisResult>> reportFor(ActiveRecording recording) {
        return reportFor(recording, null);
    }

    @Override
    public Uni<Map<String, AnalysisResult>> reportFor(String jvmId, String filename) {
        return reportFor(jvmId, filename, null);
    }

    private String getEffectiveFilter(String requested) {
        // if the request has its own filter, combine it with the config filter by separating them
        // with a comma
        return Optional.ofNullable(requested)
                .flatMap(
                        s ->
                                configFilter
                                        .map(c -> String.format("%s,%s", c, s))
                                        .or(() -> Optional.of(s)))
                // if there is no request filter, fall back to the config filter
                .or(() -> configFilter)
                // if there is no config filter either, then use the wildcard filter to process all
                .orElse(RuleFilterParser.ALL_WILDCARD_TOKEN);
    }

    private Uni<Map<String, AnalysisResult>> process(InputStream stream, String filter) {
        return Uni.createFrom()
                .future(
                        reportGenerator.generateEvalMapInterruptibly(
                                new BufferedInputStream(stream),
                                ruleFilterParser.parse(getEffectiveFilter(filter))));
    }

    private Uni<Map<String, AnalysisResult>> fireRequest(InputStream stream, String filter) {
        return sidecar.generate(stream, getEffectiveFilter(filter));
    }

    @Override
    public boolean keyExists(ActiveRecording recording) {
        return false;
    }

    @Override
    public boolean keyExists(String jvmId, String filename) {
        return false;
    }

    private boolean useSidecar() {
        return sidecarUri != null && !sidecarUri.isBlank() && !NO_SIDECAR_URL.equals(sidecarUri);
    }

    private boolean usePresignedSidecar() {
        return useSidecar() && usePresignedTransfer;
    }

    private URI getPresignedPath(String jvmId, String filename) throws URISyntaxException {
        logger.infov("Handling presigned download request for {0}/{1}", jvmId, filename);
        GetObjectRequest getRequest =
                GetObjectRequest.builder()
                        .bucket(archiveBucket)
                        .key(RecordingHelper.archivedRecordingKey(Pair.of(jvmId, filename)))
                        .build();
        GetObjectPresignRequest presignRequest =
                GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(1))
                        .getObjectRequest(getRequest)
                        .build();
        return URI.create(presigner.presignGetObject(presignRequest).url().toString()).normalize();
    }

    public static class ReportGenerationException extends RuntimeException {
        public ReportGenerationException(Throwable cause) {
            super(cause);
        }
    }
}
