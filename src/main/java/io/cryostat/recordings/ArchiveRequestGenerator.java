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

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.vertx.mutiny.core.eventbus.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArchiveRequestGenerator {

    private static final String ARCHIVE_RECORDING_SUCCESS = "ArchiveRecordingSuccess";
    private static final String ARCHIVE_RECORDING_FAIL = "ArchiveRecordingFailed";
    private final ExecutorService archiveThread = Executors.newCachedThreadPool();
    private final ExecutorService executor;
    private final Logger logger = LoggerFactory.getLogger(getClass());

    public ArchiveRequestGenerator(ExecutorService executor) {
        this.executor = executor;
    }

    public Future<String> performArchive(
            ArchiveRequest request, EventBus bus, RecordingHelper recordingHelper) {
        Objects.requireNonNull(request.getRecording());
        return archiveThread.submit(
                () -> {
                    logger.debug("Job ID: " + request.getId() + " submitted.");
                    try {
                        recordingHelper.archiveRecording(request.getRecording(), null, null).name();
                        bus.publish(
                                MessagingServer.class.getName(),
                                new Notification(
                                        ARCHIVE_RECORDING_SUCCESS,
                                        Map.of("jobId", request.getId())));
                        return request.getId();
                    } catch (Exception e) {
                        bus.publish(
                                MessagingServer.class.getName(),
                                new Notification(
                                        ARCHIVE_RECORDING_FAIL, Map.of("jobId", request.getId())));
                        throw new CompletionException(e);
                    }
                });
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
}
