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
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Optional;

import io.cryostat.ConfigProperties;
import io.cryostat.core.diagnostic.HeapDumpAnalysis;
import io.cryostat.core.diagnostic.InterruptibleHeapDumpReportGenerator;

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
 * Implementation for heap dump analysis report generation. Handles direct report generation or
 * delegation to sidecar reports generators (see https://github.com/cryostatio/cryostat-reports),
 * for heap dumps. Results, whether directly generated or retrieved from delegated sidecars, may be
 * entered into tiered cache layers.
 *
 * @see io.cryostat.recordings.LongRunningRequestGenerator
 * @see io.cryostat.diagnostic.MemoryCachingHeapDumpReportsService
 * @see io.cryostat.diagnostic.StorageCachingHeapDumpReportsService
 */
@ApplicationScoped
class HeapDumpReportsServiceImpl implements HeapDumpReportsService {

    private static final String NO_SIDECAR_URL = "http://localhost/";

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_UPLOAD_TIMEOUT)
    Duration uploadFailedTimeout;

    @ConfigProperty(name = ConfigProperties.REPORTS_SIDECAR_URL)
    String sidecarUri;

    @ConfigProperty(name = ConfigProperties.REPORTS_USE_PRESIGNED_TRANSFER)
    boolean usePresignedTransfer;

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_HEAP_DUMPS)
    String archiveBucket;

    @ConfigProperty(name = ConfigProperties.STORAGE_EXT_URL)
    Optional<String> externalStorageUrl;

    @ConfigProperty(name = ConfigProperties.REPORTS_FILTER)
    Optional<String> configFilter;

    @Inject ObjectMapper mapper;
    @Inject DiagnosticsHelper helper;
    @Inject @RestClient HeapDumpReportsSidecarService sidecar;
    @Inject S3Presigner presigner;
    @Inject Logger logger;
    @Inject InterruptibleHeapDumpReportGenerator reportGenerator;

    @Override
    public Uni<HeapDumpAnalysis> reportFor(String jvmId, String heapDumpId) {
        try {
            if (!useSidecar()) {
                InputStream stream = helper.getHeapDumpStream(jvmId, heapDumpId);
                logger.tracev("inprocess reportFor heap dump {0} {1}", jvmId, heapDumpId);
                return process(jvmId, heapDumpId, stream);
            } else if (usePresignedSidecar()) {
                logger.tracev("sidecar reportFor presigned heap dump {0} {1}", jvmId, heapDumpId);
                var uri = getPresignedPath(jvmId, heapDumpId);
                return sidecar.generatePresigned(uri.toString(), jvmId, heapDumpId);
            } else {
                InputStream stream = helper.getHeapDumpStream(jvmId, heapDumpId);
                logger.tracev("sidecar reportFor heap dump {0} {1}", jvmId, heapDumpId);
                return fireRequest(stream, jvmId, heapDumpId).eventually(safeClose(stream));
            }
        } catch (URISyntaxException e) {
            logger.error(e);
            throw new InternalServerErrorException(e);
        } catch (Exception e) {
            throw new ReportGenerationException(e);
        }
    }

    private Uni<HeapDumpAnalysis> process(String jvmId, String heapDumpId, InputStream stream) {
        return Uni.createFrom()
                .future(reportGenerator.generateInterruptibly(jvmId, heapDumpId, stream));
    }

    private Uni<HeapDumpAnalysis> fireRequest(InputStream stream, String jvmId, String heapDumpId) {
        return sidecar.generate(stream, jvmId, heapDumpId);
    }

    @Override
    public boolean keyExists(String jvmId, String heapDumpId) {
        return false;
    }

    private boolean useSidecar() {
        return sidecarUri != null && !sidecarUri.isBlank() && !NO_SIDECAR_URL.equals(sidecarUri);
    }

    private boolean usePresignedSidecar() {
        return useSidecar() && usePresignedTransfer;
    }

    private URI getPresignedPath(String jvmId, String heapDumpId) throws URISyntaxException {
        logger.infov("Handling presigned download request for {0}/{1}", jvmId, heapDumpId);
        GetObjectRequest getRequest =
                GetObjectRequest.builder()
                        .bucket(archiveBucket)
                        .key(helper.storageKey(Pair.of(jvmId, heapDumpId)))
                        .build();
        GetObjectPresignRequest presignRequest =
                GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(1))
                        .getObjectRequest(getRequest)
                        .build();
        return URI.create(presigner.presignGetObject(presignRequest).url().toString()).normalize();
    }

    private Runnable safeClose(InputStream stream) {
        return () -> {
            try {
                stream.close();
            } catch (IOException e) {
                logger.warn(e);
            }
        };
    }

    public static class ReportGenerationException extends RuntimeException {
        public ReportGenerationException(Throwable cause) {
            super(cause);
        }
    }
}
