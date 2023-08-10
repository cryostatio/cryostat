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
import java.net.URI;
import java.util.Map;
import java.util.Objects;

import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.rjmx.ServiceNotAvailableException;
import org.openjdk.jmc.rjmx.services.jfr.FlightRecorderException;
import org.openjdk.jmc.rjmx.services.jfr.IRecordingDescriptor;

import io.cryostat.recordings.Recordings.LinkedRecordingDescriptor;
import io.cryostat.recordings.Recordings.Metadata;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jdk.jfr.RecordingState;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jboss.logging.Logger;

@Entity
@EntityListeners(ActiveRecording.Listener.class)
@Table(
        uniqueConstraints = {
            @UniqueConstraint(columnNames = {"target_id", "name"}),
            @UniqueConstraint(columnNames = {"target_id", "remoteId"})
        })
public class ActiveRecording extends PanacheEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_id")
    public Target target;

    @Column(nullable = false)
    public String name;

    public long remoteId;
    public RecordingState state;
    public long duration;
    public long startTime;
    public boolean continuous;
    public boolean toDisk;
    public long maxSize;
    public long maxAge;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    public Metadata metadata;

    public static ActiveRecording from(Target target, LinkedRecordingDescriptor descriptor) {
        ActiveRecording recording = new ActiveRecording();

        recording.target = target;
        recording.remoteId = descriptor.id();
        recording.name = descriptor.name();
        recording.state = RecordingState.RUNNING;
        recording.duration = descriptor.duration();
        recording.startTime = descriptor.startTime();
        recording.continuous = descriptor.continuous();
        recording.toDisk = descriptor.toDisk();
        recording.maxSize = descriptor.maxSize();
        recording.maxAge = descriptor.maxAge();
        recording.metadata = descriptor.metadata();

        return recording;
    }

    public static ActiveRecording from(
            Target target, IRecordingDescriptor descriptor, Metadata metadata) {
        ActiveRecording recording = new ActiveRecording();

        recording.target = target;
        recording.remoteId = descriptor.getId();
        recording.name = descriptor.getName();
        switch (descriptor.getState()) {
            case CREATED:
                recording.state = RecordingState.NEW;
                break;
            case RUNNING:
                recording.state = RecordingState.RUNNING;
                break;
            case STOPPING:
                recording.state = RecordingState.RUNNING;
                break;
            case STOPPED:
                recording.state = RecordingState.STOPPED;
                break;
            default:
                break;
        }
        recording.duration = descriptor.getDuration().in(UnitLookup.MILLISECOND).longValue();
        recording.startTime = descriptor.getStartTime().in(UnitLookup.EPOCH_MS).longValue();
        recording.continuous = descriptor.isContinuous();
        recording.toDisk = descriptor.getToDisk();
        recording.maxSize = descriptor.getMaxSize().in(UnitLookup.BYTE).longValue();
        recording.maxAge = descriptor.getMaxAge().in(UnitLookup.MILLISECOND).longValue();
        recording.metadata = new Metadata(metadata);

        return recording;
    }

    public static ActiveRecording getByName(String name) {
        return find("name", name).singleResult();
    }

    // Default implementation of delete is bulk so does not trigger lifecycle events
    public static boolean deleteByName(String name) {
        return delete("name", name) > 0;
    }

    public static boolean deleteFromTarget(Target target, String recordingName) {
        ActiveRecording recordingToDelete =
                find("target = ?1 and name = ?2", target, recordingName).firstResult();
        if (recordingToDelete != null) {
            recordingToDelete.delete();
            return true;
        }
        return false; // Recording not found or already deleted.
    }

    @ApplicationScoped
    static class Listener {

        @Inject Logger logger;
        @Inject EventBus bus;
        @Inject TargetConnectionManager connectionManager;
        @Inject RecordingHelper recordingHelper;

        @PostPersist
        public void postPersist(ActiveRecording activeRecording) {
            notify("ActiveRecordingCreated", activeRecording);
        }

        @PreUpdate
        public void preUpdate(ActiveRecording activeRecording) throws Exception {
            if (RecordingState.STOPPED.equals(activeRecording.state)) {
                connectionManager.executeConnectedTask(
                        activeRecording.target,
                        conn -> {
                            RecordingHelper.getDescriptorById(conn, activeRecording.remoteId)
                                    .ifPresent(
                                            d -> {
                                                try {
                                                    conn.getService().stop(d);
                                                } catch (FlightRecorderException
                                                        | IOException
                                                        | ServiceNotAvailableException e) {
                                                    logger.warn(
                                                            "Failed to stop remote recording", e);
                                                }
                                            });
                            return null;
                        });
            }
        }

        @PostUpdate
        public void postUpdate(ActiveRecording activeRecording) {
            if (RecordingState.STOPPED.equals(activeRecording.state)) {
                notify("ActiveRecordingStopped", activeRecording);
            }
        }

        @PreRemove
        public void preRemove(ActiveRecording activeRecording) throws Exception {
            activeRecording.target.activeRecordings.remove(activeRecording);
            connectionManager.executeConnectedTask(
                    activeRecording.target,
                    conn -> {
                        RecordingHelper.getDescriptor(conn, activeRecording)
                                .ifPresent(rec -> Recordings.safeCloseRecording(conn, rec, logger));
                        return null;
                    });
        }

        @PostRemove
        public void postRemove(ActiveRecording activeRecording) {
            notify("ActiveRecordingDeleted", activeRecording);
        }

        private void notify(String category, ActiveRecording recording) {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(
                            category,
                            new RecordingEvent(
                                    recording.target.connectUrl,
                                    recordingHelper.toExternalForm(recording))));
        }

        public record RecordingEvent(URI target, Object recording) {
            public RecordingEvent {
                Objects.requireNonNull(target);
                Objects.requireNonNull(recording);
            }
        }
    }
}
