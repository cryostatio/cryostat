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
package io.cryostat.recordings;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;

import io.cryostat.ConfigProperties;
import io.cryostat.core.reports.InterruptibleReportGenerator.AnalysisResult;
import io.cryostat.reports.AnalysisReportAggregator;
import io.cryostat.reports.ReportsService;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class LongRunningRequestGenerator {

    public static final String ARCHIVE_ADDRESS =
            "io.cryostat.recordings.ArchiveRequestGenerator.ArchiveRequest";
    public static final String GRAFANA_ARCHIVE_ADDRESS =
            "io.cryostat.recordings.ArchiveRequestGenerator.GrafanaArchiveUploadRequest";
    public static final String GRAFANA_ACTIVE_ADDRESS =
            "io.cryostat.recordings.ArchiveRequestGenerator.GrafanaActiveUploadRequest";
    public static final String ARCHIVE_REPORT_ADDRESS =
            "io.cryostat.recordings.ArchiveRequestGenerator.ArchiveReportRequest";
    public static final String ACTIVE_REPORT_ADDRESS =
            "io.cryostat.recordings.ArchiveRequestGenerator.ActiveReportRequest";
    private static final String ARCHIVE_RECORDING_SUCCESS = "ArchiveRecordingSuccess";
    private static final String ARCHIVE_RECORDING_FAIL = "ArchiveRecordingFailure";
    private static final String GRAFANA_UPLOAD_SUCCESS = "GrafanaUploadSuccess";
    private static final String GRAFANA_UPLOAD_FAIL = "GrafanaUploadFailure";
    private static final String REPORT_SUCCESS = "ReportSuccess";
    private static final String REPORT_FAILURE = "ReportFailure";

    @Inject Logger logger;
    @Inject private EventBus bus;
    @Inject private RecordingHelper recordingHelper;
    @Inject private ReportsService reportsService;
    @Inject AnalysisReportAggregator analysisReportAggregator;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration timeout;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_UPLOAD_TIMEOUT)
    Duration uploadFailedTimeout;

    public LongRunningRequestGenerator() {}

    @ConsumeEvent(value = ARCHIVE_ADDRESS, blocking = true)
    public void onMessage(ArchiveRequest request) {
        logger.trace("Job ID: " + request.getId() + " submitted.");
        try {
            String rec = recordingHelper.archiveRecording(request.recording, null, null).name();
            logger.trace("Recording archived, firing notification");
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(
                            ARCHIVE_RECORDING_SUCCESS,
                            Map.of("jobId", request.getId(), "recording", rec)));
        } catch (Exception e) {
            logger.warn("Archiving failed");
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(ARCHIVE_RECORDING_FAIL, Map.of("jobId", request.getId())));
            throw new CompletionException(e);
        }
    }

    @ConsumeEvent(value = GRAFANA_ARCHIVE_ADDRESS, blocking = true)
    public void onMessage(GrafanaArchiveUploadRequest request) {
        try {
            logger.trace("Job ID: " + request.getId() + " submitted.");
            recordingHelper
                    .uploadToJFRDatasource(request.getPair())
                    .await()
                    .atMost(uploadFailedTimeout);
            logger.trace("Grafana upload complete, firing notification");
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(GRAFANA_UPLOAD_SUCCESS, Map.of("jobId", request.getId())));
        } catch (Exception e) {
            logger.warn("Exception thrown while servicing request: ", e);
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(GRAFANA_UPLOAD_FAIL, Map.of("jobId", request.getId())));
        }
    }

    @ConsumeEvent(value = GRAFANA_ACTIVE_ADDRESS, blocking = true)
    public void onMessage(GrafanaActiveUploadRequest request) {
        try {
            logger.trace("Job ID: " + request.getId() + " submitted.");
            recordingHelper
                    .uploadToJFRDatasource(request.getTargetId(), request.getRemoteId())
                    .await()
                    .atMost(uploadFailedTimeout);
            logger.trace("Grafana upload complete, firing notification");
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(GRAFANA_UPLOAD_SUCCESS, Map.of("jobId", request.getId())));
        } catch (Exception e) {
            logger.warn("Exception thrown while servicing request: ", e);
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(GRAFANA_UPLOAD_FAIL, Map.of("jobId", request.getId())));
        }
    }

    @ConsumeEvent(value = ACTIVE_REPORT_ADDRESS, blocking = true)
    public Uni<Map<String, AnalysisResult>> onMessage(ActiveReportRequest request) {
        logger.trace("Job ID: " + request.getId() + " submitted.");
        return reportsService
                .reportFor(request.recording)
                .onItem()
                .invoke(
                        () -> {
                            logger.trace("Report generation complete, firing notification");
                            bus.publish(
                                    MessagingServer.class.getName(),
                                    new Notification(
                                            REPORT_SUCCESS, Map.of("jobId", request.getId())));
                        })
                .ifNoItem()
                .after(uploadFailedTimeout)
                .fail()
                .onFailure()
                .invoke(
                        (e) -> {
                            logger.warn("Exception thrown while servicing request: ", e);
                            bus.publish(
                                    MessagingServer.class.getName(),
                                    new Notification(
                                            REPORT_FAILURE, Map.of("jobId", request.getId())));
                        });
    }

    @ConsumeEvent(value = ARCHIVE_REPORT_ADDRESS, blocking = true)
    public Uni<Map<String, AnalysisResult>> onMessage(ArchivedReportRequest request) {
        logger.tracev("Job ID: {0} submitted.", request.id());
        return reportsService
                .reportFor(request.getPair().getKey(), request.getPair().getValue())
                .onItem()
                .invoke(
                        () -> {
                            logger.trace("Report generation complete, firing notification");
                            bus.publish(
                                    MessagingServer.class.getName(),
                                    new Notification(
                                            REPORT_SUCCESS, Map.of("jobId", request.getId())));
                        })
                .ifNoItem()
                .after(uploadFailedTimeout)
                .fail()
                .onFailure()
                .invoke(
                        (e) -> {
                            logger.warn("Exception thrown while servicing request: ", e);
                            bus.publish(
                                    MessagingServer.class.getName(),
                                    new Notification(
                                            REPORT_FAILURE, Map.of("jobId", request.getId())));
                        });
    }

    // Spotbugs doesn't like us storing an ActiveRecording here as part
    // of the record. It shouldn't be a problem and we do similar things
    // elswhere with other records.
    @SuppressFBWarnings(value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"})
    public record ArchiveRequest(String id, ActiveRecording recording) {

        public ArchiveRequest {
            Objects.requireNonNull(id);
            Objects.requireNonNull(recording);
        }

        public String getId() {
            return id;
        }
    }

    public record GrafanaArchiveUploadRequest(String id, Pair<String, String> pair) {

        public GrafanaArchiveUploadRequest {
            Objects.requireNonNull(id);
            Objects.requireNonNull(pair);
        }

        public String getId() {
            return id;
        }

        public Pair<String, String> getPair() {
            return pair;
        }
    }

    public record GrafanaActiveUploadRequest(String id, long remoteId, long targetId) {

        public GrafanaActiveUploadRequest {
            Objects.requireNonNull(id);
            Objects.requireNonNull(remoteId);
            Objects.requireNonNull(targetId);
        }

        public String getId() {
            return id;
        }

        public long getRemoteId() {
            return remoteId;
        }

        public long getTargetId() {
            return targetId;
        }
    }

    public record ArchivedReportRequest(String id, Pair<String, String> pair) {

        public ArchivedReportRequest {
            Objects.requireNonNull(id);
            Objects.requireNonNull(pair);
        }

        public String getId() {
            return id;
        }

        public Pair<String, String> getPair() {
            return pair;
        }
    }

    // Spotbugs doesn't like us storing an ActiveRecording here as part
    // of the record. It shouldn't be a problem and we do similar things
    // elswhere with other records.
    @SuppressFBWarnings(value = {"EI_EXPOSE_REP2", "EI_EXPOSE_REP"})
    public record ActiveReportRequest(String id, ActiveRecording recording) {

        public ActiveReportRequest {
            Objects.requireNonNull(id);
            Objects.requireNonNull(recording);
        }

        public String getId() {
            return id;
        }
    }
}
