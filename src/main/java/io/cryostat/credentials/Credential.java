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
package io.cryostat.credentials;

import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.hibernate.annotations.ColumnTransformer;

@Entity
@EntityListeners(Credential.Listener.class)
public class Credential extends PanacheEntity {

    @Column(nullable = false, updatable = false)
    public String matchExpression;

    @ColumnTransformer(
            read = "pgp_sym_decrypt(username, current_setting('encrypt.key'))",
            write = "pgp_sym_encrypt(?, current_setting('encrypt.key'))")
    @Column(nullable = false, updatable = false, columnDefinition = "bytea")
    public String username;

    @ColumnTransformer(
            read = "pgp_sym_decrypt(password, current_setting('encrypt.key'))",
            write = "pgp_sym_encrypt(?, current_setting('encrypt.key'))")
    @Column(nullable = false, updatable = false, columnDefinition = "bytea")
    public String password;

    @ApplicationScoped
    static class Listener {

        @Inject EventBus bus;

        // TODO prePersist validate the matchExpression syntax

        @PostPersist
        public void postPersist(Credential credential) {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification("CredentialsStored", Credentials.safeResult(credential)));
        }

        @PostUpdate
        public void postUpdate(Credential credential) {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification("CredentialsUpdated", Credentials.safeResult(credential)));
        }

        @PostRemove
        public void postRemove(Credential credential) {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification("CredentialsDeleted", Credentials.safeResult(credential)));
        }
    }
}
