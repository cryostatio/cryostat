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
package io.cryostat.diagnostic;

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
@Table(uniqueConstraints = {@UniqueConstraint(columnNames = {"target_id", "jobId"})})
public class HeapDump extends PanacheEntity {

    @NotNull @ManyToOne public Target target;

    @NotBlank public String jobId;

    @Column public String filename;

    @NotNull
    @Enumerated(EnumType.STRING)
    public Status status;

    @NotNull public Long requestedAt;

    @Column public Long completedAt;

    @Column public Long size;

    public enum Status {
        REQUESTED,
        COMPLETED,
        FAILED
    }

    public static HeapDump requested(Target target, String jobId) {
        HeapDump dump = new HeapDump();
        dump.target = target;
        dump.jobId = jobId;
        dump.status = Status.REQUESTED;
        dump.requestedAt = System.currentTimeMillis();
        return dump;
    }

    public void markCompleted(String filename, long size) {
        this.filename = filename;
        this.size = size;
        this.status = Status.COMPLETED;
        this.completedAt = System.currentTimeMillis();
    }

    public void markFailed() {
        this.status = Status.FAILED;
        this.completedAt = System.currentTimeMillis();
    }
}