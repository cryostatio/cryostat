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

import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.recordings.RecordingNotifications.ActiveRecordingNotification;
import io.cryostat.targets.Target;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import jdk.jfr.RecordingState;
import org.jboss.logging.Logger;

/**
 * Observes transactional ActiveRecording lifecycle events and publishes the existing internal event
 * bus and WebSocket notifications only after the surrounding transaction commits successfully.
 */
@ApplicationScoped
public class RecordingNotificationObserver {

    @Inject Logger logger;
    @Inject EventBus bus;
    @Inject RecordingHelper recordingHelper;

    void onRecordingCreated(
            @Observes(during = TransactionPhase.AFTER_SUCCESS)
                    ActiveRecordingEvents.ActiveRecordingCreated event) {
        sendNotification(event);
    }

    void onRecordingStopped(
            @Observes(during = TransactionPhase.AFTER_SUCCESS)
                    ActiveRecordingEvents.ActiveRecordingStopped event) {
        sendNotification(event);
    }

    void onRecordingDeleted(
            @Observes(during = TransactionPhase.AFTER_SUCCESS)
                    ActiveRecordingEvents.ActiveRecordingDeleted event) {
        sendDeletedNotification(event);
    }

    void onRecordingMetadataUpdated(
            @Observes(during = TransactionPhase.AFTER_SUCCESS)
                    ActiveRecordingEvents.ActiveRecordingMetadataUpdated event) {
        sendNotification(event);
    }

    private void sendNotification(ActiveRecordingEvents.ActiveRecordingEvent event) {
        try {
            NotificationData data =
                    QuarkusTransaction.requiringNew()
                            .call(
                                    () -> {
                                        ActiveRecording recording =
                                                ActiveRecording.findById(event.getRecordingId());
                                        if (recording == null) {
                                            return null;
                                        }
                                        Target target = Target.findById(recording.target.id);
                                        return new NotificationData(
                                                recording,
                                                new ActiveRecordingNotification(
                                                        event.getCategory(),
                                                        new ActiveRecordingNotification.Payload(
                                                                target.connectUrl.toString(),
                                                                recordingHelper.toExternalForm(
                                                                        recording),
                                                                target.jvmId)));
                                    });
            if (data == null) {
                logger.warnv(
                        "Post-commit notification skipped because ActiveRecording id={0} was not"
                                + " found",
                        event.getRecordingId());
                return;
            }

            bus.publish(event.getCategory().category(), data.recording());
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(
                            data.notification().category().category(),
                            data.notification().payload()));

            if (RecordingState.STOPPED.equals(data.recording().state)) {
                logger.debugv(
                        "Post-commit ActiveRecording stop notification sent for id={0}",
                        event.getRecordingId());
            }
        } catch (Exception e) {
            logger.errorv(
                    e,
                    "Failed to process post-commit notification for ActiveRecording id={0}",
                    event.getRecordingId());
        }
    }

    private record NotificationData(
            ActiveRecording recording, ActiveRecordingNotification notification) {}

    private void sendDeletedNotification(ActiveRecordingEvents.ActiveRecordingDeleted event) {
        try {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(
                            event.getPayload().category().category(),
                            event.getPayload().payload()));
        } catch (Exception e) {
            logger.errorv(
                    e,
                    "Failed to process post-commit delete notification for ActiveRecording id={0}",
                    event.getRecordingId());
        }
    }
}
