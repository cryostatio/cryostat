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
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import org.hibernate.envers.Audited;

/**
 * Represents an active GC logging session on a remote target JVM. One row exists per target while
 * GC logging is enabled; the row is deleted when GC logging is disabled. The full session lifecycle
 * (enable → reconfigure → pull(s) → disable) is preserved in {@code GcLog_AUD} via Envers.
 */
@Audited
@Entity
@Table(uniqueConstraints = {@UniqueConstraint(columnNames = {"target_id"})})
public class GcLog extends PanacheEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_id")
    @NotNull
    public Target target;

    @Column public String what;

    @Column public String decorators;

    @Column public String filename;

    @NotNull
    @Enumerated(EnumType.STRING)
    public Status status;

    @NotNull public long enabledAt;

    @Column public Long lastModifiedAt;

    @Column public Long size;

    public enum Status {
        ACTIVE,
        FAILED
    }

    public static GcLog enable(Target target, String what, String decorators) {
        GcLog log = new GcLog();
        log.target = target;
        log.what = what;
        log.decorators = decorators;
        log.status = Status.ACTIVE;
        log.enabledAt = System.currentTimeMillis();
        return log;
    }

    public void markReconfigured(String what, String decorators) {
        this.what = what;
        this.decorators = decorators;
        this.lastModifiedAt = System.currentTimeMillis();
    }

    public void markPulled(String filename, long size) {
        this.filename = filename;
        this.size = size;
        this.lastModifiedAt = System.currentTimeMillis();
    }

    public void markFailed() {
        this.status = Status.FAILED;
        this.lastModifiedAt = System.currentTimeMillis();
    }
}
