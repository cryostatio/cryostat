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
import io.cryostat.diagnostic.DiagnosticsHelper;
import io.cryostat.diagnostic.DiagnosticsHelper.EventCategory;
import io.cryostat.diagnostic.DiagnosticsHelper.ThreadDumpEvent;
import io.cryostat.recordings.ArchivedRecordings.ArchivedRecording;
import io.cryostat.reports.AnalysisReportAggregator;
import io.cryostat.reports.ReportsService;
import io.cryostat.targets.Target;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Message consumer and emitter for long-running batch processing tasks. Jobs such as copying active
 * recording data into archives, or generating automated analysis reports, can take some time to
 * complete due to computational complexity, network bandwidth constraints or latency, etc. This
 * class is responsible for marshalling messages relating to these sorts of activities and
 * delegating task execution to various implementing services.
 */
@ApplicationScoped
public class LongRunningRequestGenerator {

    public static final String ARCHIVE_REQUEST_ADDRESS =
            "io.cryostat.recordings.LongRunningRequestGenerator.ArchiveRequest";
    public static final String GRAFANA_ARCHIVE_REQUEST_ADDRESS =
            "io.cryostat.recordings.LongRunningRequestGenerator.GrafanaArchiveUploadRequest";
    public static final String GRAFANA_ACTIVE_REQUEST_ADDRESS =
            "io.cryostat.recordings.LongRunningRequestGenerator.GrafanaActiveUploadRequest";
    public static final String ARCHIVE_REPORT_REQUEST_ADDRESS =
            "io.cryostat.recordings.LongRunningRequestGenerator.ArchiveReportRequest";
    public static final String ACTIVE_REPORT_REQUEST_ADDRESS =
            "io.cryostat.recordings.LongRunningRequestGenerator.ActiveReportRequest";

    public static final String ACTIVE_REPORT_COMPLETE_ADDRESS =
            "io.cryostat.recording.LongRunningRequestGenerator.ActiveReportComplete";
    public static final String ARCHIVED_REPORT_COMPLETE_ADDRESS =
            "io.cryostat.recording.LongRunningRequestGenerator.ArchivedReportComplete";

    public static final String HEAP_DUMP_REQUEST_ADDRESS =
            "io.cryostat.recordings.LongRunningRequestGenerator.HeapDumpRequest";
    public static final String THREAD_DUMP_ADDRESS =
            "io.cryostat.recordings.LongRunningRequestGenerator.ThreadDump";

    private static final String ARCHIVE_RECORDING_SUCCESS = "ArchiveRecordingSuccess";
    private static final String ARCHIVE_RECORDING_FAIL = "ArchiveRecordingFailure";
    private static final String GRAFANA_UPLOAD_SUCCESS = "GrafanaUploadSuccess";
    private static final String GRAFANA_UPLOAD_FAIL = "GrafanaUploadFailure";
    private static final String REPORT_SUCCESS = "ReportSuccess";
    private static final String REPORT_FAILURE = "ReportFailure";
    private static final String HEAP_DUMP_FAILURE = "HeapDumpFailure";
    private static final String HEAP_DUMP_SUCCESS = "HeapDumpSuccess";
    private static final String THREAD_DUMP_FAILURE = "ThreadDumpFailure";

    @Inject Logger logger;
    @Inject private EventBus bus;
    @Inject private RecordingHelper recordingHelper;
    @Inject private ReportsService reportsService;
    @Inject private DiagnosticsHelper diagnosticsHelper;
    @Inject AnalysisReportAggregator analysisReportAggregator;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration timeout;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_UPLOAD_TIMEOUT)
    Duration uploadFailedTimeout;

    public LongRunningRequestGenerator() {}

    @ConsumeEvent(value = THREAD_DUMP_ADDRESS, blocking = true)
    @Transactional
    public void onMessage(ThreadDumpRequest request) {
        logger.tracev("Job ID: {0} submitted.", request.id());
        try {
            var target = Target.getTargetById(request.targetId);
            var dump = diagnosticsHelper.dumpThreads(target, request.format, request.id());
            var event =
                    new ThreadDumpEvent(
                            EventCategory.CREATED,
                            ThreadDumpEvent.Payload.of(target, dump, request.id()));
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(event.category().category(), event.payload()));
        } catch (Exception e) {
            logger.warn("Failed to dump threads");
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(
                            THREAD_DUMP_FAILURE,
                            Map.of("jobId", request.id(), "targetId", request.targetId)));
            throw new CompletionException(e);
        }
    }

    @ConsumeEvent(value = ARCHIVE_REQUEST_ADDRESS, blocking = true)
    @Transactional
    public ArchivedRecording onMessage(ArchiveRequest request) {
        logger.trace("Job ID: " + request.id() + " submitted.");
        try {
            var target = Target.<Target>findById(request.recording.target.id);
            var recording = target.getRecordingById(request.recording.remoteId);
            var rec = recordingHelper.archiveRecording(recording);
            logger.trace("Recording archived, firing notification");
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(
                            ARCHIVE_RECORDING_SUCCESS,
                            Map.of(
                                    "jobId",
                                    request.id(),
                                    "recording",
                                    rec.name(),
                                    "reportUrl",
                                    rec.reportUrl(),
                                    "downloadUrl",
                                    rec.downloadUrl())));
            if (request.deleteOnCompletion) {
                recordingHelper.deleteRecording(recording).await().atMost(timeout);
            }
            return rec;
        } catch (Exception e) {
            logger.warn("Archiving failed");
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(ARCHIVE_RECORDING_FAIL, Map.of("jobId", request.id())));
            throw new CompletionException(e);
        }
    }

    @ConsumeEvent(value = GRAFANA_ARCHIVE_REQUEST_ADDRESS, blocking = true)
    public Uni<Void> onMessage(GrafanaArchiveUploadRequest request) {
        try {
            logger.trace("Job ID: " + request.id() + " submitted.");
            return recordingHelper
                    .uploadToJFRDatasource(request.pair())
                    .onItem()
                    .<Void>transform((v) -> null)
                    .invoke(
                            () -> {
                                logger.trace("Grafana upload complete, firing notification");
                                bus.publish(
                                        MessagingServer.class.getName(),
                                        new Notification(
                                                GRAFANA_UPLOAD_SUCCESS,
                                                Map.of("jobId", request.id())));
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
                                                GRAFANA_UPLOAD_FAIL,
                                                Map.of("jobId", request.id())));
                            });
        } catch (Exception e) {
            logger.error("Exception thrown while preparing request: ", e);
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(GRAFANA_UPLOAD_FAIL, Map.of("jobId", request.id())));
            return Uni.createFrom().failure(e);
        }
    }

    @ConsumeEvent(value = GRAFANA_ACTIVE_REQUEST_ADDRESS, blocking = true)
    public Uni<Void> onMessage(GrafanaActiveUploadRequest request) {
        try {
            logger.trace("Job ID: " + request.id() + " submitted.");
            return recordingHelper
                    .uploadToJFRDatasource(request.targetId(), request.remoteId())
                    .onItem()
                    .<Void>transform((v) -> null)
                    .invoke(
                            () -> {
                                logger.trace("Grafana upload complete, firing notification");
                                bus.publish(
                                        MessagingServer.class.getName(),
                                        new Notification(
                                                GRAFANA_UPLOAD_SUCCESS,
                                                Map.of("jobId", request.id())));
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
                                                GRAFANA_UPLOAD_FAIL,
                                                Map.of("jobId", request.id())));
                            });
        } catch (Exception e) {
            logger.error("Exception thrown while preparing request: ", e);
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(GRAFANA_UPLOAD_FAIL, Map.of("jobId", request.id())));
            return Uni.createFrom().failure(e);
        }
    }

    @ConsumeEvent(value = ACTIVE_REPORT_REQUEST_ADDRESS, blocking = true)
    public Uni<Map<String, AnalysisResult>> onMessage(ActiveReportRequest request) {
        logger.trace("Job ID: " + request.id() + " submitted.");
        return reportsService
                .reportFor(request.recording, request.filter)
                .onItem()
                .invoke(
                        (report) -> {
                            logger.trace("Report generation complete, firing notification");
                            bus.publish(
                                    MessagingServer.class.getName(),
                                    new Notification(
                                            REPORT_SUCCESS,
                                            Map.of(
                                                    "jobId",
                                                    request.id(),
                                                    "jvmId",
                                                    request.recording.target.jvmId)));
                            bus.publish(
                                    ACTIVE_REPORT_COMPLETE_ADDRESS,
                                    new ActiveReportCompletion(
                                            request.id(), request.recording(), report));
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
                                            REPORT_FAILURE, Map.of("jobId", request.id())));
                        });
    }

    @ConsumeEvent(value = ARCHIVE_REPORT_REQUEST_ADDRESS, blocking = true)
    public Uni<Map<String, AnalysisResult>> onMessage(ArchivedReportRequest request) {
        logger.tracev("Job ID: {0} submitted.", request.id());
        return reportsService
                .reportFor(request.pair().getKey(), request.pair().getValue(), request.filter)
                .onItem()
                .invoke(
                        (report) -> {
                            logger.trace("Report generation complete, firing notification");
                            bus.publish(
                                    MessagingServer.class.getName(),
                                    new Notification(
                                            REPORT_SUCCESS,
                                            Map.of(
                                                    "jobId",
                                                    request.id(),
                                                    "jvmId",
                                                    request.pair().getKey())));
                            bus.publish(
                                    ARCHIVED_REPORT_COMPLETE_ADDRESS,
                                    new ArchivedReportCompletion(
                                            request.id(),
                                            request.pair().getKey(),
                                            request.pair().getValue(),
                                            report));
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
                                            REPORT_FAILURE, Map.of("jobId", request.id())));
                        });
    }

    @ConsumeEvent(value = HEAP_DUMP_REQUEST_ADDRESS, blocking = true)
    @Transactional
    public void onMessage(HeapDumpRequest request) {
        logger.warnv("Job ID: {0} submitted.", request.id());
        try {
            var target = Target.getTargetById(request.targetId);
            diagnosticsHelper.dumpHeap(target, request.id());

            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(
                            HEAP_DUMP_SUCCESS,
                            Map.of("jobId", request.id(), "targetAlias", target.alias)));
        } catch (Exception e) {
            logger.warn("Failed to dump heap");
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(HEAP_DUMP_FAILURE, Map.of("jobId", request.id())));
            throw new CompletionException(e);
        }
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public record ArchiveRequest(String id, ActiveRecording recording, boolean deleteOnCompletion) {
        public ArchiveRequest {
            Objects.requireNonNull(id);
            Objects.requireNonNull(recording);
        }

        public ArchiveRequest(String id, ActiveRecording recording) {
            this(id, recording, false);
        }
    }

    public record GrafanaArchiveUploadRequest(String id, Pair<String, String> pair) {
        public GrafanaArchiveUploadRequest {
            Objects.requireNonNull(id);
            Objects.requireNonNull(pair);
        }
    }

    public record GrafanaActiveUploadRequest(String id, long remoteId, long targetId) {
        public GrafanaActiveUploadRequest {
            Objects.requireNonNull(id);
            Objects.requireNonNull(remoteId);
            Objects.requireNonNull(targetId);
        }
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public record ActiveReportRequest(String id, ActiveRecording recording, String filter) {
        public ActiveReportRequest {
            Objects.requireNonNull(id);
            Objects.requireNonNull(recording);
        }

        public ActiveReportRequest(String id, ActiveRecording recording) {
            this(id, recording, null);
        }
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public record ActiveReportCompletion(
            String id, ActiveRecording recording, Map<String, AnalysisResult> report) {
        public ActiveReportCompletion {
            Objects.requireNonNull(id);
            Objects.requireNonNull(recording);
            Objects.requireNonNull(report);
        }
    }

    public record ArchivedReportRequest(String id, Pair<String, String> pair, String filter) {
        public ArchivedReportRequest {
            Objects.requireNonNull(id);
            Objects.requireNonNull(pair);
        }

        public ArchivedReportRequest(String id, Pair<String, String> pair) {
            this(id, pair, null);
        }
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public record ArchivedReportCompletion(
            String id, String jvmId, String filename, Map<String, AnalysisResult> report) {
        public ArchivedReportCompletion {
            Objects.requireNonNull(id);
            Objects.requireNonNull(jvmId);
            Objects.requireNonNull(filename);
            Objects.requireNonNull(report);
        }
    }

    public record HeapDumpRequest(String id, long targetId) {
        public HeapDumpRequest {
            Objects.requireNonNull(id);
            Objects.requireNonNull(targetId);
        }
    }

    public record ThreadDumpRequest(String id, long targetId, String format) {
        public ThreadDumpRequest {
            Objects.requireNonNull(id);
            Objects.requireNonNull(targetId);
            Objects.requireNonNull(format);
        }
    }
}
