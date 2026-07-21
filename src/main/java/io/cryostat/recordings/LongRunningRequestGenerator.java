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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import io.cryostat.ConfigProperties;
import io.cryostat.core.diagnostic.HeapDumpAnalysis;
import io.cryostat.core.reports.InterruptibleReportGenerator.AnalysisResult;
import io.cryostat.diagnostic.DiagnosticsHelper;
import io.cryostat.diagnostic.DiagnosticsHelper.EventCategory;
import io.cryostat.diagnostic.DiagnosticsHelper.ThreadDumpEvent;
import io.cryostat.diagnostic.HeapDumpReportsService;
import io.cryostat.recordings.ArchivedRecordings.ArchivedRecording;
import io.cryostat.reports.AnalysisReportAggregator;
import io.cryostat.reports.ReportsService;
import io.cryostat.targets.Target;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;
import io.cryostat.ws.notifications.NotificationPayloads.ArchiveRecordingSuccessPayload;
import io.cryostat.ws.notifications.NotificationPayloads.HeapDumpAnalysisFailurePayload;
import io.cryostat.ws.notifications.NotificationPayloads.HeapDumpAnalysisSuccessPayload;
import io.cryostat.ws.notifications.NotificationPayloads.HeapDumpSuccessPayload;
import io.cryostat.ws.notifications.NotificationPayloads.JobIdPayload;
import io.cryostat.ws.notifications.NotificationPayloads.ReportSuccessPayload;
import io.cryostat.ws.notifications.NotificationPayloads.SynthesisCompletePayload;
import io.cryostat.ws.notifications.NotificationPayloads.ThreadDumpFailurePayload;

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
    public static final String HEAP_DUMP_ANALYSIS_REQUEST_ADDRESS =
            "io.cryostat.recording.LongRunningRequestGenerator.HeapDumpAnalysisRequest";

    public static final String ACTIVE_REPORT_COMPLETE_ADDRESS =
            "io.cryostat.recording.LongRunningRequestGenerator.ActiveReportComplete";
    public static final String ARCHIVED_REPORT_COMPLETE_ADDRESS =
            "io.cryostat.recording.LongRunningRequestGenerator.ArchivedReportComplete";

    public static final String HEAP_DUMP_REQUEST_ADDRESS =
            "io.cryostat.recordings.LongRunningRequestGenerator.HeapDumpRequest";
    public static final String THREAD_DUMP_ADDRESS =
            "io.cryostat.recordings.LongRunningRequestGenerator.ThreadDump";
    public static final String SYNTHESIS_REQUEST_ADDRESS =
            "io.cryostat.recordings.LongRunningRequestGenerator.SynthesisRequest";

    private static final String ARCHIVE_RECORDING_SUCCESS = "ArchiveRecordingSuccess";
    private static final String ARCHIVE_RECORDING_FAIL = "ArchiveRecordingFailure";
    private static final String GRAFANA_UPLOAD_SUCCESS = "GrafanaUploadSuccess";
    private static final String GRAFANA_UPLOAD_FAIL = "GrafanaUploadFailure";
    private static final String REPORT_SUCCESS = "ReportSuccess";
    private static final String REPORT_FAILURE = "ReportFailure";
    private static final String HEAP_DUMP_FAILURE = "HeapDumpFailure";
    private static final String HEAP_DUMP_SUCCESS = "HeapDumpSuccess";
    private static final String HEAP_DUMP_ANALYSIS_SUCCESS = "HeapDumpAnalysisSuccess";
    private static final String HEAP_DUMP_ANALYSIS_FAILURE = "HeapDumpAnalysisFailure";
    private static final String THREAD_DUMP_FAILURE = "ThreadDumpFailure";
    private static final String SYNTHESIS_SUCCESS = "RecordingSynthesisComplete";
    private static final String SYNTHESIS_FAILURE = "RecordingSynthesisFailure";

    @Inject Logger logger;
    @Inject private EventBus bus;
    @Inject private RecordingHelper recordingHelper;
    @Inject private ReportsService reportsService;
    @Inject private HeapDumpReportsService heapDumpReportsService;
    @Inject private DiagnosticsHelper diagnosticsHelper;
    @Inject AnalysisReportAggregator analysisReportAggregator;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_UPLOAD_TIMEOUT)
    Duration uploadFailedTimeout;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration connectionFailedTimeout;

    public LongRunningRequestGenerator() {}

    @ConsumeEvent(value = THREAD_DUMP_ADDRESS, blocking = true)
    @Transactional
    @SuppressFBWarnings("DLS_DEAD_LOCAL_STORE")
    public void onMessage(ThreadDumpRequest request) {
        logger.tracev("Job ID: {0} submitted.", request.id());
        try {
            var target = Target.getTargetById(request.targetId);
            var dump = diagnosticsHelper.dumpThreads(target, request.format, request.id());

            io.cryostat.diagnostic.ThreadDump.<io.cryostat.diagnostic.ThreadDump>find(
                            "jobId", request.id())
                    .firstResultOptional()
                    .ifPresent(
                            td -> {
                                td.markCompleted(dump.threadDumpId(), dump.size());
                                td.persist();
                            });

            var event =
                    new ThreadDumpEvent(
                            EventCategory.CREATED, ThreadDumpEvent.Payload.of(dump, request.id()));
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(event.category().category(), event.payload()));
        } catch (Exception e) {
            logger.warn("Failed to dump threads");

            io.cryostat.diagnostic.ThreadDump.<io.cryostat.diagnostic.ThreadDump>find(
                            "jobId", request.id())
                    .firstResultOptional()
                    .ifPresent(
                            td -> {
                                td.markFailed();
                                td.persist();
                            });

            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(
                            THREAD_DUMP_FAILURE,
                            new ThreadDumpFailurePayload(request.id(), request.targetId)));
            throw new CompletionException(e);
        }
    }

    @ConsumeEvent(value = HEAP_DUMP_ANALYSIS_REQUEST_ADDRESS, blocking = true)
    @Transactional
    public Uni<HeapDumpAnalysis> onMessage(HeapDumpAnalysisRequest request) {
        logger.tracev("Job ID: {0} submitted.", request.id());
        try {
            var target = Target.getTargetByJvmId(request.jvmId).get();
            logger.tracev("Generating Heap Dump Report");
            return heapDumpReportsService
                    .reportFor(request.jvmId, request.heapDumpId)
                    .onItem()
                    .invoke(
                            () -> {
                                logger.tracev("Report generation complete, firing notification");
                                bus.publish(
                                        MessagingServer.class.getName(),
                                        new Notification(
                                                HEAP_DUMP_ANALYSIS_SUCCESS,
                                                new HeapDumpAnalysisSuccessPayload(
                                                        request.id(),
                                                        target.jvmId,
                                                        request.heapDumpId())));
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
                                        new HeapDumpAnalysisFailurePayload(
                                                request.id(), request.heapDumpId));
                            });
        } catch (Exception e) {
            logger.warn("Failed to analyze heap dump");
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(
                            HEAP_DUMP_ANALYSIS_FAILURE,
                            new HeapDumpAnalysisFailurePayload(request.id(), request.heapDumpId)));
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
                            new ArchiveRecordingSuccessPayload(
                                    request.id(), rec.name(), rec.reportUrl(), rec.downloadUrl())));
            if (request.deleteOnCompletion) {
                recordingHelper.deleteRecording(recording).await().atMost(connectionFailedTimeout);
            }
            return rec;
        } catch (Exception e) {
            logger.warn("Archiving failed");
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(ARCHIVE_RECORDING_FAIL, new JobIdPayload(request.id())));
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
                                                new JobIdPayload(request.id())));
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
                                                new JobIdPayload(request.id())));
                            });
        } catch (Exception e) {
            logger.error("Exception thrown while preparing request: ", e);
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(GRAFANA_UPLOAD_FAIL, new JobIdPayload(request.id())));
            return Uni.createFrom().failure(e);
        }
    }

    @ConsumeEvent(value = GRAFANA_ACTIVE_REQUEST_ADDRESS, blocking = true)
    @Transactional
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
                                                new JobIdPayload(request.id())));
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
                                                new JobIdPayload(request.id())));
                            });
        } catch (Exception e) {
            logger.error("Exception thrown while preparing request: ", e);
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(GRAFANA_UPLOAD_FAIL, new JobIdPayload(request.id())));
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
                                            new ReportSuccessPayload(
                                                    request.id(), request.recording.target.jvmId)));
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
                                            REPORT_FAILURE, new JobIdPayload(request.id())));
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
                                            REPORT_FAILURE, new JobIdPayload(request.id())));
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
                            new HeapDumpSuccessPayload(request.id(), target.alias)));
        } catch (Exception e) {
            logger.warn("Failed to dump heap");

            io.cryostat.diagnostic.HeapDump.<io.cryostat.diagnostic.HeapDump>find(
                            "jobId", request.id())
                    .firstResultOptional()
                    .ifPresent(
                            hd -> {
                                hd.markFailed();
                                hd.persist();
                            });

            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(HEAP_DUMP_FAILURE, new JobIdPayload(request.id())));
            throw new CompletionException(e);
        }
    }

    @ConsumeEvent(value = SYNTHESIS_REQUEST_ADDRESS, blocking = true)
    @Transactional
    public void onMessage(SynthesisRequest request) {
        logger.tracev("Synthesis job ID: {0} submitted.", request.id());
        long fromMs = request.fromTimestamp() * 1000L;
        long toMs = request.toTimestamp() * 1000L;

        List<ArchivedRecording> current = recordingHelper.listArchivedRecordings(request.jvmId());
        Optional<ArchivedRecording> existing =
                current.stream()
                        .filter(RecordingsSynthesis.completeFilter(fromMs, toMs))
                        .max(Comparator.comparingDouble(RecordingsSynthesis::density));
        if (existing.isPresent()) {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(
                            SYNTHESIS_SUCCESS,
                            new SynthesisCompletePayload(request.id(), existing.get())));
            return;
        }

        List<ArchivedRecording> candidates = request.candidates();
        long minStart = Long.MAX_VALUE;
        long maxEnd = Long.MIN_VALUE;
        for (ArchivedRecording r : candidates) {
            long st = Long.parseLong(r.metadata().labels().get(RecordingHelper.START_TIME_LABEL));
            long dur = Long.parseLong(r.metadata().labels().get(RecordingHelper.DURATION_LABEL));
            if (st < minStart) minStart = st;
            if (st + dur > maxEnd) maxEnd = st + dur;
        }
        long syntheticDuration = maxEnd - minStart;

        String isoStart =
                Instant.ofEpochMilli(minStart).toString().replace(':', '-').replace('.', '-');
        String humanDur = formatDuration(Duration.ofMillis(syntheticDuration));
        String filename = request.tag() + "_" + isoStart + "_" + humanDur + ".jfr";

        long totalSize = candidates.stream().mapToLong(ArchivedRecording::size).sum();
        long[] offsets = new long[candidates.size()];
        offsets[0] = 0;
        for (int i = 1; i < candidates.size(); i++) {
            offsets[i] = offsets[i - 1] + candidates.get(i - 1).size();
        }

        Path tempFile = null;
        FileChannel channel = null;
        try {
            tempFile = Files.createTempFile("synthesis-", ".jfr");
            channel = FileChannel.open(tempFile, StandardOpenOption.READ, StandardOpenOption.WRITE);
            // allocate tempFile large enough to hold concatenated contents of all candidate
            // recordings, and fail fast if the required space is not available
            try {
                channel.write(ByteBuffer.allocate(1), totalSize - 1);
            } catch (IOException e) {
                throw e;
            }

            final FileChannel fc = channel;
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                final String name = candidates.get(i).name();
                final long offset = offsets[i];
                futures.add(
                        CompletableFuture.runAsync(
                                () -> {
                                    try (var in =
                                            recordingHelper.getArchivedRecordingStream(
                                                    request.jvmId(), name)) {
                                        byte[] buf = new byte[8192];
                                        int read;
                                        long pos = offset;
                                        while ((read = in.read(buf)) != -1) {
                                            fc.write(ByteBuffer.wrap(buf, 0, read), pos);
                                            pos += read;
                                        }
                                    } catch (Exception ex) {
                                        throw new CompletionException(ex);
                                    }
                                },
                                recordingHelper.partUploader));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            channel.close();
            channel = null;

            Map<String, String> labelsMap = new HashMap<>();
            labelsMap.put("jvmId", request.jvmId());
            labelsMap.put(RecordingHelper.START_TIME_LABEL, String.valueOf(minStart));
            labelsMap.put(RecordingHelper.DURATION_LABEL, String.valueOf(syntheticDuration));
            labelsMap.put(AnalysisReportAggregator.AUTOANALYZE_LABEL, "true");
            labelsMap.put("synthetic", "true");
            ActiveRecordings.Metadata metadata = new ActiveRecordings.Metadata(labelsMap);

            ArchivedRecording result =
                    recordingHelper.uploadSynthesizedRecording(
                            request.jvmId(), tempFile, filename, metadata);
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(
                            SYNTHESIS_SUCCESS, new SynthesisCompletePayload(request.id(), result)));
        } catch (Exception e) {
            logger.warnv("Synthesis job {0} failed: {1}", request.id(), e.getMessage());
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(SYNTHESIS_FAILURE, new JobIdPayload(request.id())));
            throw new CompletionException(e);
        } finally {
            if (channel != null) {
                try {
                    channel.close();
                } catch (Exception ignored) {
                }
            }
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String formatDuration(Duration d) {
        long seconds = d.getSeconds();
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (hours > 0) sb.append(hours).append('h');
        if (minutes > 0) sb.append(minutes).append('m');
        if (secs > 0 || sb.length() == 0) sb.append(secs).append('s');
        return sb.toString();
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

    public record HeapDumpAnalysisRequest(String id, String jvmId, String heapDumpId) {
        public HeapDumpAnalysisRequest {
            Objects.requireNonNull(id);
            Objects.requireNonNull(jvmId);
            Objects.requireNonNull(heapDumpId);
        }
    }

    public record SynthesisRequest(
            String id,
            String jvmId,
            long fromTimestamp,
            long toTimestamp,
            String tag,
            List<ArchivedRecording> candidates) {
        public SynthesisRequest {
            Objects.requireNonNull(id);
            Objects.requireNonNull(jvmId);
            Objects.requireNonNull(tag);
            Objects.requireNonNull(candidates);
        }
    }
}
