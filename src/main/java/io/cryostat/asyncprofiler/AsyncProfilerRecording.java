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
package io.cryostat.asyncprofiler;

import java.util.List;

import io.cryostat.targets.Target;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.envers.Audited;

@Entity
@Audited
@Table(uniqueConstraints = {@UniqueConstraint(columnNames = {"target_id", "profileId"})})
public class AsyncProfilerRecording extends PanacheEntity {

    @NotNull @ManyToOne public Target target;

    @NotBlank public String profileId;

    @Column public String eventType;

    @Column public Long duration;

    @NotNull
    @Enumerated(EnumType.STRING)
    public Status status;

    @NotNull public Long startedAt;

    @Column public Long stoppedAt;

    public enum Status {
        STARTED,
        STOPPED,
        DELETED
    }

    public static AsyncProfilerRecording started(
            Target target, String profileId, List<String> events, Long duration) {
        AsyncProfilerRecording recording = new AsyncProfilerRecording();
        recording.target = target;
        recording.profileId = profileId;
        recording.eventType = events != null ? String.join(",", events) : null;
        recording.duration = duration;
        recording.status = Status.STARTED;
        recording.startedAt = System.currentTimeMillis();
        return recording;
    }

    public void markStopped() {
        this.status = Status.STOPPED;
        this.stoppedAt = System.currentTimeMillis();
    }

    public void markDeleted() {
        this.status = Status.DELETED;
        this.stoppedAt = System.currentTimeMillis();
    }
}
