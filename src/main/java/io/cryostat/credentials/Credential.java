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

import io.cryostat.expressions.MatchExpression;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.hibernate.annotations.ColumnTransformer;
import org.projectnessie.cel.tools.ScriptException;

@Entity
@EntityListeners(Credential.Listener.class)
public class Credential extends PanacheEntity {

    public static final String CREDENTIALS_STORED = "CredentialsStored";
    public static final String CREDENTIALS_DELETED = "CredentialsDeleted";
    public static final String CREDENTIALS_UPDATED = "CredentialsUpdated";

    @OneToOne(fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    @JoinColumn(name = "matchExpression")
    public MatchExpression matchExpression;

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
        @Inject MatchExpression.TargetMatcher targetMatcher;

        @PostPersist
        public void postPersist(Credential credential) throws ScriptException {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(
                            CREDENTIALS_STORED, Credentials.notificationResult(credential)));
            bus.publish(CREDENTIALS_STORED, credential);
        }

        @PostUpdate
        public void postUpdate(Credential credential) throws ScriptException {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(
                            CREDENTIALS_UPDATED, Credentials.notificationResult(credential)));
            bus.publish(CREDENTIALS_UPDATED, credential);
        }

        @PostRemove
        public void postRemove(Credential credential) throws ScriptException {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(
                            CREDENTIALS_DELETED, Credentials.notificationResult(credential)));
            bus.publish(CREDENTIALS_DELETED, credential);
        }
    }
}
