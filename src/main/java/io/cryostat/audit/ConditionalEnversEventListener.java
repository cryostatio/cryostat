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

import java.util.function.Supplier;

import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.jboss.logging.Logger;

/**
 * Custom Hibernate event listeners that check the runtime audit enablement flag before delegating
 * to the actual Envers listeners. These listeners wrap the default Envers listeners and delegate to
 * them only when auditing is enabled at runtime.
 */
public class ConditionalEnversEventListener {

    private static final Logger logger = Logger.getLogger(ConditionalEnversEventListener.class);

    private static volatile Supplier<Boolean> auditEnabledSupplier = () -> false;

    public static void setAuditEnabledSupplier(Supplier<Boolean> supplier) {
        auditEnabledSupplier = supplier;
        logger.infof("Audit enabled supplier configured");
    }

    private static boolean isAuditEnabled() {
        try {
            return auditEnabledSupplier.get();
        } catch (Exception e) {
            logger.warnf(e, "Error checking audit enabled status, defaulting to disabled");
            return false;
        }
    }

    public static class ConditionalPostInsertEventListener implements PostInsertEventListener {

        private final PostInsertEventListener delegate;

        public ConditionalPostInsertEventListener(PostInsertEventListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onPostInsert(PostInsertEvent event) {
            if (isAuditEnabled() && delegate != null) {
                delegate.onPostInsert(event);
            }
        }

        @Override
        public boolean requiresPostCommitHandling(EntityPersister persister) {
            if (!isAuditEnabled() || delegate == null) {
                return false;
            }
            return delegate.requiresPostCommitHandling(persister);
        }
    }

    public static class ConditionalPostUpdateEventListener implements PostUpdateEventListener {

        private final PostUpdateEventListener delegate;

        public ConditionalPostUpdateEventListener(PostUpdateEventListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onPostUpdate(PostUpdateEvent event) {
            if (isAuditEnabled() && delegate != null) {
                delegate.onPostUpdate(event);
            }
        }

        @Override
        public boolean requiresPostCommitHandling(EntityPersister persister) {
            if (!isAuditEnabled() || delegate == null) {
                return false;
            }
            return delegate.requiresPostCommitHandling(persister);
        }
    }

    public static class ConditionalPostDeleteEventListener implements PostDeleteEventListener {

        private final PostDeleteEventListener delegate;

        public ConditionalPostDeleteEventListener(PostDeleteEventListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onPostDelete(PostDeleteEvent event) {
            if (isAuditEnabled() && delegate != null) {
                delegate.onPostDelete(event);
            }
        }

        @Override
        public boolean requiresPostCommitHandling(EntityPersister persister) {
            if (!isAuditEnabled() || delegate == null) {
                return false;
            }
            return delegate.requiresPostCommitHandling(persister);
        }
    }
}
