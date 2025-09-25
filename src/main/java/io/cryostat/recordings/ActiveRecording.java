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

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.configuration.IRecordingDescriptor;

import io.cryostat.recordings.ActiveRecordings.LinkedRecordingDescriptor;
import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.transaction.Transactional;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jdk.jfr.RecordingState;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jboss.logging.Logger;

@Entity
@EntityListeners(ActiveRecording.Listener.class)
@Table(
        uniqueConstraints = {
            // remoteId is the unique ID assigned by the JVM to its own recordings, so these IDs are
            // unique but only within the scope of each JVM. Since they are just sequential numeric
            // IDs, they will not be unique across different JVMs.
            @UniqueConstraint(columnNames = {"target_id", "remoteId"})
        })
public class ActiveRecording extends PanacheEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_id")
    @NotNull
    public Target target;

    @NotBlank public String name;

    @PositiveOrZero public long remoteId;
    @NotNull public RecordingState state;
    @PositiveOrZero public long duration;
    @PositiveOrZero public long startTime;
    public boolean continuous;
    public boolean toDisk;
    @PositiveOrZero public long maxSize;
    @PositiveOrZero public long maxAge;

    /**
     * true if the recording was discovered on the Target and must have been created by some
     * external process (not Cryostat), false if created by Cryostat.
     */
    @JsonIgnore public boolean external;

    @JdbcTypeCode(SqlTypes.JSON)
    @NotNull
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
                recording.state = RecordingState.DELAYED;
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
                recording.state = RecordingState.NEW;
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

    public void setMetadata(Metadata metadata) {
        this.metadata = metadata;
    }

    @ApplicationScoped
    static class Listener {

        @Inject Logger logger;
        @Inject EventBus bus;
        @Inject TargetConnectionManager connectionManager;
        @Inject RecordingHelper recordingHelper;

        @PostPersist
        @Transactional
        public void postPersist(ActiveRecording activeRecording) {
            if (activeRecording.external) {
                return;
            }
            bus.publish(
                    ActiveRecordings.RecordingEventCategory.ACTIVE_CREATED.category(),
                    activeRecording);
            notify(
                    new ActiveRecordingEvent(
                            ActiveRecordings.RecordingEventCategory.ACTIVE_CREATED,
                            ActiveRecordingEvent.Payload.of(recordingHelper, activeRecording)));
        }

        @PostUpdate
        @Transactional
        public void postUpdate(ActiveRecording activeRecording) {
            if (activeRecording.external) {
                return;
            }
            if (RecordingState.STOPPED.equals(activeRecording.state)) {
                bus.publish(
                        ActiveRecordings.RecordingEventCategory.ACTIVE_STOPPED.category(),
                        activeRecording);
                notify(
                        new ActiveRecordingEvent(
                                ActiveRecordings.RecordingEventCategory.ACTIVE_STOPPED,
                                ActiveRecordingEvent.Payload.of(recordingHelper, activeRecording)));
            }
        }

        @PostRemove
        @Transactional
        public void postRemove(ActiveRecording activeRecording) {
            if (activeRecording.external) {
                return;
            }
            bus.publish(
                    ActiveRecordings.RecordingEventCategory.ACTIVE_DELETED.category(),
                    activeRecording);
            notify(
                    new ActiveRecordingEvent(
                            ActiveRecordings.RecordingEventCategory.ACTIVE_DELETED,
                            ActiveRecordingEvent.Payload.of(recordingHelper, activeRecording)));
        }

        private void notify(ActiveRecordingEvent event) {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(event.category().category(), event.payload()));
        }

        public record ActiveRecordingEvent(
                ActiveRecordings.RecordingEventCategory category, Payload payload) {
            public ActiveRecordingEvent {
                Objects.requireNonNull(category);
                Objects.requireNonNull(payload);
            }

            public record Payload(
                    String target, LinkedRecordingDescriptor recording, String jvmId) {
                public Payload {
                    Objects.requireNonNull(target);
                    Objects.requireNonNull(recording);
                    Objects.requireNonNull(jvmId);
                }

                public static Payload of(RecordingHelper helper, ActiveRecording recording) {
                    return new Payload(
                            recording.target.connectUrl.toString(),
                            helper.toExternalForm(recording),
                            recording.target.jvmId);
                }
            }
        }

        public record ArchivedRecordingEvent(
                ActiveRecordings.RecordingEventCategory category, Payload payload) {
            public ArchivedRecordingEvent {
                Objects.requireNonNull(category);
                Objects.requireNonNull(payload);
            }

            // FIXME the target connectUrl URI may no longer be known if the target
            // has disappeared and we are emitting an event regarding an archived recording
            // originally sourced from that target, or if we are accepting a recording upload from a
            // client.
            public record Payload(
                    String target, String jvmId, ArchivedRecordings.ArchivedRecording recording) {
                public Payload {
                    Objects.requireNonNull(recording);
                }

                public static Payload of(
                        URI connectUrl, ArchivedRecordings.ArchivedRecording recording) {
                    return new Payload(
                            Optional.ofNullable(connectUrl).map(URI::toString).orElse(null),
                            Optional.ofNullable(connectUrl)
                                    .flatMap(
                                            url ->
                                                    Target.find("connectUrl", url)
                                                            .<Target>singleResultOptional())
                                    .map(t -> t.jvmId)
                                    .orElse(null),
                            recording);
                }
            }
        }
    }
}
