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
package io.cryostat.audit;

import java.util.Map;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.config.spi.ConfigurationService;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.envers.boot.internal.EnversService;
import org.hibernate.envers.event.spi.EnversListenerDuplicationStrategy;
import org.hibernate.envers.event.spi.EnversPostCollectionRecreateEventListenerImpl;
import org.hibernate.envers.event.spi.EnversPostDeleteEventListenerImpl;
import org.hibernate.envers.event.spi.EnversPostInsertEventListenerImpl;
import org.hibernate.envers.event.spi.EnversPostUpdateEventListenerImpl;
import org.hibernate.envers.event.spi.EnversPreCollectionRemoveEventListenerImpl;
import org.hibernate.envers.event.spi.EnversPreCollectionUpdateEventListenerImpl;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;
import org.jboss.logging.Logger;

/**
 * Custom Hibernate Integrator that conditionally registers Envers event listeners based on runtime
 * configuration. This allows Envers auditing to be enabled or disabled without rebuilding the
 * application.
 *
 * <p>Configuration: Set {@code cryostat.audit.enabled=true} to enable auditing (disabled by
 * default).
 */
public class ConditionalEnversIntegrator implements Integrator {

    private static final Logger logger = Logger.getLogger(ConditionalEnversIntegrator.class);
    private static final String ENVERS_ENABLED_PROPERTY = "cryostat.audit.enabled";

    @Override
    public void integrate(
            Metadata metadata,
            BootstrapContext bootstrapContext,
            SessionFactoryImplementor sessionFactory) {

        ConfigurationService configService =
                bootstrapContext.getServiceRegistry().getService(ConfigurationService.class);
        Map<String, Object> settings = configService.getSettings();

        boolean enversEnabled =
                Boolean.parseBoolean(
                        String.valueOf(settings.getOrDefault(ENVERS_ENABLED_PROPERTY, "false")));

        if (!enversEnabled) {
            logger.trace("Hibernate Envers auditing is disabled");
            return;
        }

        logger.trace("Hibernate Envers auditing is enabled - registering event listeners");

        final EventListenerRegistry listenerRegistry =
                sessionFactory.getServiceRegistry().getService(EventListenerRegistry.class);
        final EnversService enversService =
                sessionFactory.getServiceRegistry().getService(EnversService.class);

        listenerRegistry.addDuplicationStrategy(EnversListenerDuplicationStrategy.INSTANCE);

        if (enversService.getEntitiesConfigurations().hasAuditedEntities()) {
            listenerRegistry.appendListeners(
                    EventType.POST_DELETE, new EnversPostDeleteEventListenerImpl(enversService));
            listenerRegistry.appendListeners(
                    EventType.POST_INSERT, new EnversPostInsertEventListenerImpl(enversService));
            listenerRegistry.appendListeners(
                    EventType.POST_UPDATE, new EnversPostUpdateEventListenerImpl(enversService));
            listenerRegistry.appendListeners(
                    EventType.POST_COLLECTION_RECREATE,
                    new EnversPostCollectionRecreateEventListenerImpl(enversService));
            listenerRegistry.appendListeners(
                    EventType.PRE_COLLECTION_REMOVE,
                    new EnversPreCollectionRemoveEventListenerImpl(enversService));
            listenerRegistry.appendListeners(
                    EventType.PRE_COLLECTION_UPDATE,
                    new EnversPreCollectionUpdateEventListenerImpl(enversService));
            logger.trace("Envers event listeners registered successfully");
        }
    }

    @Override
    public void disintegrate(
            SessionFactoryImplementor sessionFactory,
            SessionFactoryServiceRegistry serviceRegistry) {}
}
