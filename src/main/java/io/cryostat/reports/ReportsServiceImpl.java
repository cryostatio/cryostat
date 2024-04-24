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
import java.util.Map;
import java.util.function.Predicate;

import org.openjdk.jmc.flightrecorder.rules.IRule;

import io.cryostat.ConfigProperties;
import io.cryostat.core.reports.InterruptibleReportGenerator;
import io.cryostat.core.reports.InterruptibleReportGenerator.AnalysisResult;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingHelper;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

@ApplicationScoped
class ReportsServiceImpl implements ReportsService {

    private static final String NO_SIDECAR_URL = "http://localhost/";

    @ConfigProperty(name = ConfigProperties.REPORTS_SIDECAR_URL)
    String sidecarUri;

    @Inject ObjectMapper mapper;
    @Inject RecordingHelper helper;
    @Inject InterruptibleReportGenerator reportGenerator;
    @Inject @RestClient ReportSidecarService sidecar;
    @Inject Logger logger;

    @Override
    public Uni<Map<String, AnalysisResult>> reportFor(
            ActiveRecording recording, Predicate<IRule> predicate) {
        InputStream stream;
        try {
            stream = helper.getActiveInputStream(recording);
        } catch (Exception e) {
            throw new ReportGenerationException(e);
        }
        if (NO_SIDECAR_URL.equals(sidecarUri)) {
            logger.tracev(
                    "inprocess reportFor active recording {0} {1}",
                    recording.target.jvmId, recording.remoteId);
            return process(stream, predicate);
        } else {
            logger.tracev(
                    "sidecar reportFor active recording {0} {1}",
                    recording.target.jvmId, recording.remoteId);
            return fireRequest(stream);
        }
    }

    @Override
    public Uni<Map<String, AnalysisResult>> reportFor(
            String jvmId, String filename, Predicate<IRule> predicate) {
        InputStream stream;
        try {
            stream = helper.getArchivedRecordingStream(jvmId, filename);
        } catch (Exception e) {
            throw new ReportGenerationException(e);
        }
        if (NO_SIDECAR_URL.equals(sidecarUri)) {
            logger.tracev("inprocess reportFor archived recording {0} {1}", jvmId, filename);
            return process(stream, predicate);
        } else {
            logger.tracev("sidecar reportFor archived recording {0} {1}", jvmId, filename);
            return fireRequest(stream);
        }
    }

    @Override
    public Uni<Map<String, AnalysisResult>> reportFor(ActiveRecording recording) {
        return reportFor(recording, r -> true);
    }

    @Override
    public Uni<Map<String, AnalysisResult>> reportFor(String jvmId, String filename) {
        return reportFor(jvmId, filename, r -> true);
    }

    private Uni<Map<String, AnalysisResult>> process(
            InputStream stream, Predicate<IRule> predicate) {
        return Uni.createFrom()
                .future(
                        reportGenerator.generateEvalMapInterruptibly(
                                new BufferedInputStream(stream), predicate));
    }

    private Uni<Map<String, AnalysisResult>> fireRequest(InputStream stream) {
        return sidecar.generate(stream);
    }

    public static class ReportGenerationException extends RuntimeException {
        public ReportGenerationException(Throwable cause) {
            super(cause);
        }
    }
}
