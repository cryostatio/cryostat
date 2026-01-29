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

import io.cryostat.discovery.DiscoveryPlugin;
import io.cryostat.expressions.MatchExpression;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.Cacheable;
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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.ColumnTransformer;
import org.projectnessie.cel.tools.ScriptException;

/**
 * Stored Credentials are used for communicating with secured remote targets. Target JMX servers may
 * (should) be configured to require authentication from clients like Cryostat. Cryostat needs to be
 * able to establish connections to these targets and pass their authentication checks, and needs to
 * be able to do so without prompting a user for credentials every time, therefore the credentials
 * for remote targets are stored in this encrypted keyring. Each Credential instance has a single
 * username and password, as well as a {@link io.cryostat.expressions.MatchExpression} which should
 * evaluate to match one or more target applications which Cryostat has discovered or will discover.
 * The entire database table containing these credentials is encrypted using the Postgres 'pgcrypto'
 * module and pgp symmetric encryption/decryption. The encryption key is set by configuration on the
 * database deployment. Whenever Cryostat attempts to open a network connection to a target (see
 * {@link io.cryostat.targets.TargetConnectionManager}) it will first check for any Credentials that
 * match the target, then use the first matching Credential (see
 * https://github.com/cryostatio/cryostat/issues/376)
 */
@Entity
@EntityListeners(Credential.Listener.class)
@Cacheable
public class Credential extends PanacheEntity {

    public static final String CREDENTIALS_STORED = "CredentialsStored";
    public static final String CREDENTIALS_DELETED = "CredentialsDeleted";
    public static final String CREDENTIALS_UPDATED = "CredentialsUpdated";

    @OneToOne(fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    @JoinColumn(name = "matchExpression")
    @NotNull
    @Cache(usage = CacheConcurrencyStrategy.READ_ONLY)
    public MatchExpression matchExpression;

    @ColumnTransformer(
            read =
                    """
                    pgp_sym_decrypt(username,
                        coalesce(
                            current_setting('encrypt.key', true),
                            'default_key')
                        )
                    """,
            write =
                    """
                    pgp_sym_encrypt(?,
                        coalesce(
                            current_setting('encrypt.key', true),
                            'default_key')
                        )
                    """)
    @Column(updatable = false, columnDefinition = "bytea")
    @NotBlank
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String username;

    @ColumnTransformer(
            read =
                    """
                    pgp_sym_decrypt(password,
                        coalesce(
                            current_setting('encrypt.key', true),
                            'default_key')
                        )
                    """,
            write =
                    """
                    pgp_sym_encrypt(?,
                        coalesce(
                            current_setting('encrypt.key', true),
                            'default_key')
                        )
                    """)
    @Column(updatable = false, columnDefinition = "bytea")
    @NotBlank
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String password;

    @OneToOne(
            optional = true,
            fetch = FetchType.LAZY,
            mappedBy = "credential",
            cascade = CascadeType.REMOVE)
    @JoinColumn(name = "discoveryPlugin_id")
    @JsonIgnore
    @Nullable
    public DiscoveryPlugin discoveryPlugin;

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
