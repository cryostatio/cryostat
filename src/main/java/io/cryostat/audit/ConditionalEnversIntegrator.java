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

import io.cryostat.ConfigProperties;

import org.eclipse.microprofile.config.ConfigProvider;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.envers.boot.internal.EnversService;
import org.hibernate.envers.event.spi.EnversListenerDuplicationStrategy;
import org.hibernate.envers.event.spi.EnversPostDeleteEventListenerImpl;
import org.hibernate.envers.event.spi.EnversPostInsertEventListenerImpl;
import org.hibernate.envers.event.spi.EnversPostUpdateEventListenerImpl;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.jboss.logging.Logger;

/**
 * Custom Hibernate integrator that wraps the default Envers event listeners with conditional ones
 * that check the runtime audit enablement flag. This integrator is automatically discovered and
 * registered by Hibernate through the Java ServiceLoader mechanism.
 */
public class ConditionalEnversIntegrator implements Integrator {

    private static final Logger logger = Logger.getLogger(ConditionalEnversIntegrator.class);

    @Override
    public void integrate(
            Metadata metadata,
            BootstrapContext bootstrapContext,
            SessionFactoryImplementor sessionFactory) {
        EnversService enversService =
                bootstrapContext.getServiceRegistry().getService(EnversService.class);

        if (enversService == null || !enversService.isEnabled()) {
            logger.trace(
                    "Envers service is not enabled, skipping conditional listener integration");
            return;
        }

        setupAuditEnabledSupplier();

        EventListenerRegistry listenerRegistry =
                bootstrapContext.getServiceRegistry().getService(EventListenerRegistry.class);

        listenerRegistry.addDuplicationStrategy(EnversListenerDuplicationStrategy.INSTANCE);

        EnversPostInsertEventListenerImpl insertListener =
                new EnversPostInsertEventListenerImpl(enversService);
        EnversPostUpdateEventListenerImpl updateListener =
                new EnversPostUpdateEventListenerImpl(enversService);
        EnversPostDeleteEventListenerImpl deleteListener =
                new EnversPostDeleteEventListenerImpl(enversService);

        listenerRegistry.appendListeners(
                EventType.POST_INSERT,
                new ConditionalEnversEventListener.ConditionalPostInsertEventListener(
                        insertListener));

        listenerRegistry.appendListeners(
                EventType.POST_UPDATE,
                new ConditionalEnversEventListener.ConditionalPostUpdateEventListener(
                        updateListener));

        listenerRegistry.appendListeners(
                EventType.POST_DELETE,
                new ConditionalEnversEventListener.ConditionalPostDeleteEventListener(
                        deleteListener));

        logger.trace("Conditional Envers event listeners integrated successfully");
    }

    private void setupAuditEnabledSupplier() {
        try {
            ConditionalEnversEventListener.setAuditEnabledSupplier(
                    () -> {
                        try {
                            return ConfigProvider.getConfig()
                                    .getOptionalValue(ConfigProperties.AUDIT_ENABLED, Boolean.class)
                                    .orElse(false);
                        } catch (Exception e) {
                            logger.warnf(
                                    e,
                                    "Error reading audit enabled configuration, defaulting to"
                                            + " disabled");
                            return false;
                        }
                    });
            boolean initialState =
                    ConfigProvider.getConfig()
                            .getOptionalValue(ConfigProperties.AUDIT_ENABLED, Boolean.class)
                            .orElse(false);
            logger.tracef("Audit enabled supplier configured. Initial state: %s", initialState);
        } catch (Exception e) {
            logger.errorf(e, "Failed to setup audit enabled supplier, auditing will be disabled");
            ConditionalEnversEventListener.setAuditEnabledSupplier(() -> false);
        }
    }
}
