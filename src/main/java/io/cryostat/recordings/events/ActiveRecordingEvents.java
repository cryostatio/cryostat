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
package io.cryostat.recordings.events;

import java.util.Objects;

import io.cryostat.recordings.ActiveRecordings;
import io.cryostat.recordings.RecordingNotifications.ActiveRecordingNotification;

/**
 * CDI events for ActiveRecording lifecycle changes. These are fired during transactional work and
 * observed after commit so that notification processing only sees committed database state.
 */
public class ActiveRecordingEvents {

    public abstract static class ActiveRecordingEvent {
        private final long recordingId;
        private final ActiveRecordings.RecordingEventCategory category;

        protected ActiveRecordingEvent(
                long recordingId, ActiveRecordings.RecordingEventCategory category) {
            this.recordingId = recordingId;
            this.category = Objects.requireNonNull(category);
        }

        public long getRecordingId() {
            return recordingId;
        }

        public ActiveRecordings.RecordingEventCategory getCategory() {
            return category;
        }
    }

    public static class ActiveRecordingCreated extends ActiveRecordingEvent {
        public ActiveRecordingCreated(long recordingId) {
            super(recordingId, ActiveRecordings.RecordingEventCategory.ACTIVE_CREATED);
        }
    }

    public static class ActiveRecordingStopped extends ActiveRecordingEvent {
        public ActiveRecordingStopped(long recordingId) {
            super(recordingId, ActiveRecordings.RecordingEventCategory.ACTIVE_STOPPED);
        }
    }

    public static class ActiveRecordingDeleted extends ActiveRecordingEvent {
        private final ActiveRecordingNotification payload;

        public ActiveRecordingDeleted(long recordingId, ActiveRecordingNotification payload) {
            super(recordingId, ActiveRecordings.RecordingEventCategory.ACTIVE_DELETED);
            this.payload = Objects.requireNonNull(payload);
        }

        public ActiveRecordingNotification getPayload() {
            return payload;
        }
    }

    public static class ActiveRecordingMetadataUpdated extends ActiveRecordingEvent {
        public ActiveRecordingMetadataUpdated(long recordingId) {
            super(recordingId, ActiveRecordings.RecordingEventCategory.METADATA_UPDATED);
        }
    }
}
