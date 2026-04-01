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
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.validation.constraints.NotNull;
import org.hibernate.envers.Audited;

/**
 * Represents a garbage collection operation triggered on a remote target JVM. This entity serves as
 * an audit trigger for GC operations, which are transient actions that don't have persistent state
 * beyond the audit log entry itself.
 *
 * <p>Note: This entity does not emit WebSocket notifications as GC operations are considered
 * low-level diagnostic actions that don't require real-time UI updates.
 */
@Audited
@Entity
public class GarbageCollection extends PanacheEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_id")
    @NotNull
    public Target target;

    @NotNull public long triggeredAt;

    public static GarbageCollection of(Target target) {
        GarbageCollection gc = new GarbageCollection();
        gc.target = target;
        gc.triggeredAt = System.currentTimeMillis();
        return gc;
    }
}
