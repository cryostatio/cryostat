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
package io.cryostat.events;

import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

public abstract class EntityNotificationObserver<E extends PanacheEntity> {

    @Inject protected Logger logger;
    @Inject protected EventBus bus;

    protected <S> void handleCreated(EntityCreatedEvent<E, S> event) {
        logger.debugv(
                "Post-commit: {0} created, id={1}", event.getEntityType(), event.getEntityId());

        try {
            Object notificationPayload = buildCreatedPayload(event.getSnapshot());
            Object eventPayload = buildCreatedEventPayload(event.getSnapshot());
            publishNotification(event.getCategory(), notificationPayload);
            publishInternalEvent(event.getCategory(), eventPayload);
        } catch (Exception e) {
            logger.error("Failed to send notification for entity created event", e);
        }
    }

    protected <S> void handleUpdated(EntityUpdatedEvent<E, S> event) {
        logger.debugv(
                "Post-commit: {0} updated, id={1}", event.getEntityType(), event.getEntityId());

        try {
            Object notificationPayload = buildUpdatedPayload(event.getSnapshot());
            Object eventPayload = buildUpdatedEventPayload(event.getSnapshot());
            publishNotification(event.getCategory(), notificationPayload);
            publishInternalEvent(event.getCategory(), eventPayload);
        } catch (Exception e) {
            logger.error("Failed to send notification for entity updated event", e);
        }
    }

    protected <S> void handleDeleted(EntityDeletedEvent<E, S> event) {
        logger.debugv(
                "Post-commit: {0} deleted, id={1}", event.getEntityType(), event.getEntityId());

        try {
            Object notificationPayload = buildDeletedPayload(event.getSnapshot());
            Object eventPayload = buildDeletedEventPayload(event.getSnapshot());
            publishNotification(event.getCategory(), notificationPayload);
            publishInternalEvent(event.getCategory(), eventPayload);
        } catch (Exception e) {
            logger.error("Failed to send notification for entity deleted event", e);
        }
    }

    protected abstract <S> Object buildCreatedPayload(S snapshot);

    protected abstract <S> Object buildUpdatedPayload(S snapshot);

    protected abstract <S> Object buildDeletedPayload(S snapshot);

    protected <S> Object buildCreatedEventPayload(S snapshot) {
        return buildCreatedPayload(snapshot);
    }

    protected <S> Object buildUpdatedEventPayload(S snapshot) {
        return buildUpdatedPayload(snapshot);
    }

    protected <S> Object buildDeletedEventPayload(S snapshot) {
        return buildDeletedPayload(snapshot);
    }

    protected void publishNotification(String category, Object payload) {
        bus.publish(MessagingServer.class.getName(), new Notification(category, payload));
    }

    protected void publishInternalEvent(String category, Object payload) {
        bus.publish(category, payload);
    }

    public abstract static class Simple<E extends PanacheEntity>
            extends EntityNotificationObserver<E> {
        @Override
        protected <S> Object buildCreatedPayload(S snapshot) {
            return buildPayload(snapshot);
        }

        @Override
        protected <S> Object buildUpdatedPayload(S snapshot) {
            return buildPayload(snapshot);
        }

        @Override
        protected <S> Object buildDeletedPayload(S snapshot) {
            return buildPayload(snapshot);
        }

        protected abstract <S> Object buildPayload(S snapshot);

        protected <S> Object buildEventPayload(S snapshot) {
            return buildPayload(snapshot);
        }

        @Override
        protected <S> Object buildCreatedEventPayload(S snapshot) {
            return buildEventPayload(snapshot);
        }

        @Override
        protected <S> Object buildUpdatedEventPayload(S snapshot) {
            return buildEventPayload(snapshot);
        }

        @Override
        protected <S> Object buildDeletedEventPayload(S snapshot) {
            return buildEventPayload(snapshot);
        }
    }
}
