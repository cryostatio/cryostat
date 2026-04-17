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
package io.cryostat.credentials.events;

import java.util.Objects;

import io.cryostat.credentials.Credential;
import io.cryostat.events.EntityCreatedEvent;
import io.cryostat.events.EntityDeletedEvent;
import io.cryostat.events.EntityUpdatedEvent;

public class CredentialEvents {

    public record CredentialSnapshot(
            long id, long matchExpressionId, String matchExpressionScript) {
        public CredentialSnapshot {
            Objects.requireNonNull(matchExpressionId);
            Objects.requireNonNull(matchExpressionScript);
        }
    }

    public static class CredentialCreated
            extends EntityCreatedEvent<Credential, CredentialSnapshot> {

        public CredentialCreated(long id, CredentialSnapshot snapshot) {
            super(id, snapshot);
        }

        @Override
        public String getCategory() {
            return Credential.CREDENTIALS_STORED;
        }

        @Override
        public String getEntityType() {
            return "Credential";
        }
    }

    public static class CredentialUpdated
            extends EntityUpdatedEvent<Credential, CredentialSnapshot> {

        public CredentialUpdated(long id, CredentialSnapshot snapshot) {
            super(id, snapshot);
        }

        @Override
        public String getCategory() {
            return Credential.CREDENTIALS_UPDATED;
        }

        @Override
        public String getEntityType() {
            return "Credential";
        }
    }

    public static class CredentialDeleted
            extends EntityDeletedEvent<Credential, CredentialSnapshot> {

        public CredentialDeleted(long id, CredentialSnapshot snapshot) {
            super(id, snapshot);
        }

        @Override
        public String getCategory() {
            return Credential.CREDENTIALS_DELETED;
        }

        @Override
        public String getEntityType() {
            return "Credential";
        }
    }
}
