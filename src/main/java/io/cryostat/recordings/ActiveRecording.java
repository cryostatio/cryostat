/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat.recordings;

import java.net.URI;
import java.util.Map;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EntityListeners;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.PostPersist;
import javax.persistence.PostRemove;
import javax.persistence.PostUpdate;

import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.rjmx.services.jfr.IRecordingDescriptor;

import io.cryostat.recordings.Recordings.LinkedRecordingDescriptor;
import io.cryostat.recordings.Recordings.Metadata;
import io.cryostat.targets.Target;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.quarkiverse.hibernate.types.json.JsonBinaryType;
import io.quarkiverse.hibernate.types.json.JsonTypes;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.vertx.core.eventbus.EventBus;
import jdk.jfr.RecordingState;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

@Entity
@EntityListeners(ActiveRecording.Listener.class)
@TypeDef(name = JsonTypes.JSON_BIN, typeClass = JsonBinaryType.class)
public class ActiveRecording extends PanacheEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_id")
    private Target target;

    public long remoteId;
    public String name;
    public RecordingState state;
    public long duration;
    public long startTime;
    public boolean continuous;
    public boolean toDisk;
    public long maxSize;
    public long maxAge;

    @Type(type = JsonTypes.JSON_BIN)
    @Column(columnDefinition = JsonTypes.JSON_BIN, nullable = false)
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

    public static ActiveRecording from(Target target, IRecordingDescriptor descriptor) {
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
        // TODO is there any metadata we can or should attach?
        recording.metadata = new Metadata(Map.of());

        return recording;
    }

    public static ActiveRecording getByName(String name) {
        return find("name", name).singleResult();
    }

    public static boolean deleteByName(String name) {
        return delete("name", name) > 0;
    }

    public LinkedRecordingDescriptor toExternalForm() {
        return new LinkedRecordingDescriptor(
                this.remoteId,
                this.state,
                this.duration,
                this.startTime,
                this.continuous,
                this.toDisk,
                this.maxSize,
                this.maxAge,
                this.name,
                "TODO",
                "TODO",
                this.metadata);
    }

    @ApplicationScoped
    static class Listener {

        @Inject EventBus bus;

        @PostPersist
        public void postPersist(ActiveRecording activeRecording) {
            notify("ActiveRecordingCreated", activeRecording);
        }

        @PostUpdate
        public void postUpdate(ActiveRecording activeRecording) {
            if (RecordingState.STOPPED.equals(activeRecording.state)) {
                notify("ActiveRecordingStopped", activeRecording);
            }
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
                                    recording.target.connectUrl, recording.toExternalForm())));
        }

        private record RecordingEvent(URI target, Object recording) {}
    }
}
