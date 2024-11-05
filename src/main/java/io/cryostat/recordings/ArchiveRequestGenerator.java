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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import io.cryostat.ConfigProperties;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.quarkus.vertx.ConsumeEvent;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class ArchiveRequestGenerator {

    public static final String ARCHIVE_ADDRESS = "io.cryostat.recordings.ArchiveRequestGenerator";
    public static final String GRAFANA_ARCHIVE_ADDRESS =
            "io.cryostat.recordings.ArchiveRequestGenerator.GrafanaArchiveUploadRequest";
    public static final String GRAFANA_ACTIVE_ADDRESS =
            "io.cryostat.recordings.ArchiveRequestGenerator.GrafanaActiveUploadRequest";
    private static final String ARCHIVE_RECORDING_SUCCESS = "ArchiveRecordingSuccess";
    private static final String ARCHIVE_RECORDING_FAIL = "ArchiveRecordingFailed";
    private static final String GRAFANA_UPLOAD_SUCCESS = "GrafanaUploadSuccess";
    private static final String GRAFANA_UPLOAD_FAIL = "GrafanaUploadFailed";
    private final ExecutorService executor;
    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Inject private EventBus bus;
    @Inject private RecordingHelper recordingHelper;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration timeout;

    public ArchiveRequestGenerator(ExecutorService executor) {
        this.executor = executor;
    }

    public Future<String> performArchive(ArchiveRequest request) {
        Objects.requireNonNull(request.getRecording());
        return executor.submit(
                () -> {
                    logger.info("Job ID: " + request.getId() + " submitted.");
                    try {
                        recordingHelper.archiveRecording(request.getRecording(), null, null).name();
                        logger.info("Recording archived, firing notification");
                        bus.publish(
                                MessagingServer.class.getName(),
                                new Notification(
                                        ARCHIVE_RECORDING_SUCCESS,
                                        Map.of("jobId", request.getId())));
                        return request.getId();
                    } catch (Exception e) {
                        logger.info("Archiving failed");
                        bus.publish(
                                MessagingServer.class.getName(),
                                new Notification(
                                        ARCHIVE_RECORDING_FAIL, Map.of("jobId", request.getId())));
                        throw new CompletionException(e);
                    }
                });
    }

    @ConsumeEvent(value = ARCHIVE_ADDRESS)
    public void onMessage(ArchiveRequest request) {
        try {
            performArchive(request);
        } catch (Exception e) {
            logger.warn("Exception thrown while servicing request: ", e);
        }
    }

    @ConsumeEvent(value = GRAFANA_ARCHIVE_ADDRESS)
    public void onMessage(GrafanaArchiveUploadRequest request) {
        try {
            logger.info("Job ID: " + request.getId() + " submitted.");
            String result =
                    recordingHelper
                            .uploadToJFRDatasource(request.getPair())
                            .await()
                            .atMost(timeout);
            logger.info("Grafana upload complete, firing notification");
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

    @ConsumeEvent(value = GRAFANA_ACTIVE_ADDRESS)
    public void onMessage(GrafanaActiveUploadRequest request) {
        try {
            logger.info("Job ID: " + request.getId() + " submitted.");
            String result =
                    recordingHelper
                            .uploadToJFRDatasource(request.getTargetId(), request.getRemoteId())
                            .await()
                            .atMost(timeout);
            logger.info("Grafana upload complete, firing notification");
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

    public record ArchiveRequest(String id, ActiveRecording recording) {

        public ArchiveRequest {
            Objects.requireNonNull(id);
            Objects.requireNonNull(recording);
        }

        public String getId() {
            return id;
        }

        public ActiveRecording getRecording() {
            return recording;
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
}
