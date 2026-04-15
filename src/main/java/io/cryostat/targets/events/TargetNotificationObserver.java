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
package io.cryostat.targets.events;

import java.net.URI;
import java.util.Map;

import io.cryostat.events.EntityNotificationObserver;
import io.cryostat.targets.Target;
import io.cryostat.targets.Target.EventKind;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;

@ApplicationScoped
public class TargetNotificationObserver extends EntityNotificationObserver<Target> {

    void onTargetCreated(
            @Observes(during = TransactionPhase.AFTER_SUCCESS) TargetEvents.TargetCreated event) {
        handleCreated(event);
    }

    void onTargetUpdated(
            @Observes(during = TransactionPhase.AFTER_SUCCESS) TargetEvents.TargetUpdated event) {
        handleUpdated(event);
    }

    void onTargetDeleted(
            @Observes(during = TransactionPhase.AFTER_SUCCESS) TargetEvents.TargetDeleted event) {
        handleDeleted(event);
    }

    @Override
    protected <S> Object buildCreatedPayload(S snapshot) {
        TargetEvents.TargetSnapshot targetSnapshot = (TargetEvents.TargetSnapshot) snapshot;
        ServiceRef serviceRef = snapshotToServiceRef(targetSnapshot);
        return new NotificationPayload(
                new TargetDiscovery(EventKind.FOUND, serviceRef, targetSnapshot.jvmId()));
    }

    @Override
    protected <S> Object buildUpdatedPayload(S snapshot) {
        TargetEvents.TargetSnapshot targetSnapshot = (TargetEvents.TargetSnapshot) snapshot;
        ServiceRef serviceRef = snapshotToServiceRef(targetSnapshot);
        return new NotificationPayload(
                new TargetDiscovery(EventKind.MODIFIED, serviceRef, targetSnapshot.jvmId()));
    }

    @Override
    protected <S> Object buildDeletedPayload(S snapshot) {
        TargetEvents.TargetSnapshot targetSnapshot = (TargetEvents.TargetSnapshot) snapshot;
        ServiceRef serviceRef = snapshotToServiceRef(targetSnapshot);
        return new NotificationPayload(
                new TargetDiscovery(EventKind.LOST, serviceRef, targetSnapshot.jvmId()));
    }

    @Override
    protected <S> Object buildCreatedEventPayload(S snapshot) {
        TargetEvents.TargetSnapshot targetSnapshot = (TargetEvents.TargetSnapshot) snapshot;
        Target target = Target.findById(targetSnapshot.id());
        return new Target.TargetDiscovery(EventKind.FOUND, target, targetSnapshot.jvmId());
    }

    @Override
    protected <S> Object buildUpdatedEventPayload(S snapshot) {
        TargetEvents.TargetSnapshot targetSnapshot = (TargetEvents.TargetSnapshot) snapshot;
        Target target = Target.findById(targetSnapshot.id());
        return new Target.TargetDiscovery(EventKind.MODIFIED, target, targetSnapshot.jvmId());
    }

    @Override
    protected <S> Object buildDeletedEventPayload(S snapshot) {
        TargetEvents.TargetSnapshot targetSnapshot = (TargetEvents.TargetSnapshot) snapshot;
        // reconstruct a Target from the snapshot since the entity has been deleted
        Target target = snapshotToTarget(targetSnapshot);
        return new Target.TargetDiscovery(EventKind.LOST, target, targetSnapshot.jvmId());
    }

    public record NotificationPayload(TargetDiscovery event) {}

    public record TargetDiscovery(EventKind kind, ServiceRef serviceRef, String jvmId) {}

    public record ServiceRef(
            long id,
            URI connectUrl,
            String alias,
            String jvmId,
            Map<String, String> labels,
            Target.Annotations annotations,
            boolean agent) {
        public ServiceRef {
            labels = labels != null ? Map.copyOf(labels) : Map.of();
            annotations =
                    annotations != null
                            ? new Target.Annotations(
                                    annotations.platform() != null
                                            ? Map.copyOf(annotations.platform())
                                            : Map.of(),
                                    annotations.cryostat() != null
                                            ? Map.copyOf(annotations.cryostat())
                                            : Map.of())
                            : new Target.Annotations(Map.of(), Map.of());
        }
    }

    private ServiceRef snapshotToServiceRef(TargetEvents.TargetSnapshot snapshot) {
        return new ServiceRef(
                snapshot.id(),
                snapshot.connectUrl(),
                snapshot.alias(),
                snapshot.jvmId(),
                snapshot.labels(),
                snapshot.annotations(),
                true);
    }

    private Target snapshotToTarget(TargetEvents.TargetSnapshot snapshot) {
        Target target = new Target();
        target.id = snapshot.id();
        target.connectUrl = snapshot.connectUrl();
        target.alias = snapshot.alias();
        target.labels = snapshot.labels();
        target.annotations = snapshot.annotations();
        target.jvmId = snapshot.jvmId();
        return target;
    }
}
