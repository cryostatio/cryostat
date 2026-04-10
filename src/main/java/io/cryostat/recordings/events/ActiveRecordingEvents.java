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

import io.cryostat.events.EntityCreatedEvent;
import io.cryostat.events.EntityDeletedEvent;
import io.cryostat.events.EntityUpdatedEvent;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.ActiveRecordings;
import io.cryostat.recordings.ActiveRecordings.LinkedRecordingDescriptor;

public class ActiveRecordingEvents {

    public record ActiveRecordingSnapshot(
            String target, LinkedRecordingDescriptor recording, String jvmId) {
        public ActiveRecordingSnapshot {
            Objects.requireNonNull(target);
            Objects.requireNonNull(recording);
        }
    }

    public static class ActiveRecordingCreated
            extends EntityCreatedEvent<ActiveRecording, ActiveRecordingSnapshot> {
        private final ActiveRecordings.RecordingEventCategory category;

        public ActiveRecordingCreated(long recordingId, ActiveRecordingSnapshot snapshot) {
            super(recordingId, snapshot);
            this.category = ActiveRecordings.RecordingEventCategory.ACTIVE_CREATED;
        }

        public long getRecordingId() {
            return getEntityId();
        }

        public ActiveRecordingSnapshot getPayload() {
            return getSnapshot();
        }

        @Override
        public String getCategory() {
            return category.category();
        }

        @Override
        public String getEntityType() {
            return ActiveRecording.class.getSimpleName();
        }
    }

    public static class ActiveRecordingStopped
            extends EntityUpdatedEvent<ActiveRecording, ActiveRecordingSnapshot> {
        private final ActiveRecordings.RecordingEventCategory category;

        public ActiveRecordingStopped(long recordingId, ActiveRecordingSnapshot snapshot) {
            super(recordingId, snapshot);
            this.category = ActiveRecordings.RecordingEventCategory.ACTIVE_STOPPED;
        }

        public long getRecordingId() {
            return getEntityId();
        }

        public ActiveRecordingSnapshot getPayload() {
            return getSnapshot();
        }

        @Override
        public String getCategory() {
            return category.category();
        }

        @Override
        public String getEntityType() {
            return ActiveRecording.class.getSimpleName();
        }
    }

    public static class ActiveRecordingDeleted
            extends EntityDeletedEvent<ActiveRecording, ActiveRecordingSnapshot> {
        private final ActiveRecordings.RecordingEventCategory category;

        public ActiveRecordingDeleted(long recordingId, ActiveRecordingSnapshot snapshot) {
            super(recordingId, snapshot);
            this.category = ActiveRecordings.RecordingEventCategory.ACTIVE_DELETED;
        }

        public long getRecordingId() {
            return getEntityId();
        }

        public ActiveRecordingSnapshot getPayload() {
            return getSnapshot();
        }

        @Override
        public String getCategory() {
            return category.category();
        }

        @Override
        public String getEntityType() {
            return ActiveRecording.class.getSimpleName();
        }
    }

    public static class ActiveRecordingMetadataUpdated
            extends EntityUpdatedEvent<ActiveRecording, ActiveRecordingSnapshot> {
        private final ActiveRecordings.RecordingEventCategory category;

        public ActiveRecordingMetadataUpdated(long recordingId, ActiveRecordingSnapshot snapshot) {
            super(recordingId, snapshot);
            this.category = ActiveRecordings.RecordingEventCategory.METADATA_UPDATED;
        }

        public long getRecordingId() {
            return getEntityId();
        }

        public ActiveRecordingSnapshot getPayload() {
            return getSnapshot();
        }

        @Override
        public String getCategory() {
            return category.category();
        }

        @Override
        public String getEntityType() {
            return ActiveRecording.class.getSimpleName();
        }
    }
}
