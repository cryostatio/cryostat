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

import io.cryostat.events.EntityNotificationObserver;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingNotifications.ActiveRecordingNotification;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;

@ApplicationScoped
public class RecordingNotificationObserver
        extends EntityNotificationObserver.Simple<ActiveRecording> {

    void onRecordingCreated(
            @Observes(during = TransactionPhase.AFTER_SUCCESS)
                    ActiveRecordingEvents.ActiveRecordingCreated event) {
        handleCreated(event);
    }

    void onRecordingStopped(
            @Observes(during = TransactionPhase.AFTER_SUCCESS)
                    ActiveRecordingEvents.ActiveRecordingStopped event) {
        handleUpdated(event);
    }

    void onRecordingDeleted(
            @Observes(during = TransactionPhase.AFTER_SUCCESS)
                    ActiveRecordingEvents.ActiveRecordingDeleted event) {
        handleDeleted(event);
    }

    void onRecordingMetadataUpdated(
            @Observes(during = TransactionPhase.AFTER_SUCCESS)
                    ActiveRecordingEvents.ActiveRecordingMetadataUpdated event) {
        handleUpdated(event);
    }

    @Override
    protected <S> Object buildPayload(S snapshot) {
        ActiveRecordingEvents.ActiveRecordingSnapshot recordingSnapshot =
                (ActiveRecordingEvents.ActiveRecordingSnapshot) snapshot;
        return new ActiveRecordingNotification.Payload(
                recordingSnapshot.target(),
                recordingSnapshot.recording(),
                recordingSnapshot.jvmId());
    }
}
