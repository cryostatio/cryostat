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
package io.cryostat.discovery;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;
import java.util.function.Supplier;

import io.cryostat.credentials.Credential;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.rest.client.reactive.ReactiveClientHeadersFactory;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.auth.authentication.UsernamePasswordCredentials;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.transaction.Transactional;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriBuilder;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.annotations.GenericGenerator;
import org.jboss.logging.Logger;

/**
 * DiscoveryPlugin instances track registrations of discovery plugins. The reference implementation
 * for a discovery plugin is the Cryostat Agent. Plugins communicate with Cryostat via the {@link
 * io.cryostat.discovery.Discovery} API endpoints. Registration through that API generates a
 * DiscoveryPlugin record to place that plugin into the discovery tree.
 */
@Entity
@EntityListeners(DiscoveryPlugin.Listener.class)
public class DiscoveryPlugin extends PanacheEntityBase {

    @Id
    @Column(name = "id")
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @NotNull
    public UUID id;

    @OneToOne(
            optional = false,
            cascade = {CascadeType.ALL},
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    @NotNull
    public DiscoveryNode realm;

    @Column(nullable = true, unique = true, updatable = false)
    @Convert(converter = UriConverter.class)
    public URI callback;

    @OneToOne(
            optional = true, // only nullable for builtins
            fetch = FetchType.LAZY,
            cascade = CascadeType.REMOVE)
    @JsonIgnore
    @Nullable
    public Credential credential;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public boolean builtin;

    @ApplicationScoped
    static class Listener {

        @Inject Logger logger;

        @PrePersist
        @Transactional
        public void prePersist(DiscoveryPlugin plugin) {
            if (plugin.builtin) {
                return;
            }
            if (plugin.callback == null) {
                throw new IllegalArgumentException();
            }
            if (plugin.credential == null) {
                var credential = getCredential(plugin);
                plugin.credential = credential;
                plugin.callback = UriBuilder.fromUri(plugin.callback).userInfo(null).build();
            }
            try {
                logger.debugv(
                        "Testing discovery plugin callback: {0} @ {1}",
                        plugin.realm.name, plugin.callback);
                PluginCallback.create(plugin).ping();
                logger.debugv(
                        "Registered discovery plugin: {0} @ {1}",
                        plugin.realm.name, plugin.callback);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException(e);
            } catch (Exception e) {
                logger.error("Discovery Plugin ping failed", e);
                throw e;
            }
        }

        private Credential getCredential(DiscoveryPlugin plugin) {
            String userInfo = plugin.callback.getUserInfo();
            if (StringUtils.isBlank(userInfo)) {
                throw new IllegalArgumentException("No stored credentials specified");
            }

            if (!userInfo.contains(":")) {
                throw new IllegalArgumentException(
                        String.format(
                                "Unexpected non-basic credential format, found: %s", userInfo));
            }

            String[] parts = userInfo.split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        String.format("Unexpected basic credential format, found: %s", userInfo));
            }

            if (!"storedcredentials".equals(parts[0])) {
                throw new IllegalArgumentException(
                        String.format(
                                "Unexpected credential format, expected \"storedcredentials\" but"
                                        + " found: %s",
                                parts[0]));
            }

            return Credential.find("id", Long.parseLong(parts[1])).singleResult();
        }
    }

    @Path("")
    public interface PluginCallback {

        final Logger logger = Logger.getLogger(PluginCallback.class);

        @GET
        public void ping();

        @POST
        public void refresh();

        public static PluginCallback create(DiscoveryPlugin plugin) throws URISyntaxException {
            PluginCallback client =
                    QuarkusRestClientBuilder.newBuilder()
                            .baseUri(plugin.callback)
                            .clientHeadersFactory(
                                    new DiscoveryPluginAuthorizationHeaderFactory(plugin))
                            .build(PluginCallback.class);
            return client;
        }

        public static class DiscoveryPluginAuthorizationHeaderFactory
                extends ReactiveClientHeadersFactory {

            private final Supplier<UsernamePasswordCredentials> credentialSupplier;

            public DiscoveryPluginAuthorizationHeaderFactory(DiscoveryPlugin plugin) {
                this(
                        () ->
                                new UsernamePasswordCredentials(
                                        plugin.credential.username, plugin.credential.password));
            }

            public DiscoveryPluginAuthorizationHeaderFactory(
                    Supplier<UsernamePasswordCredentials> credentialSupplier) {
                this.credentialSupplier = credentialSupplier;
            }

            @Override
            public Uni<MultivaluedMap<String, String>> getHeaders(
                    MultivaluedMap<String, String> incomingHeaders,
                    MultivaluedMap<String, String> clientOutgoingHeaders) {
                return Uni.createFrom()
                        .item(
                                () -> {
                                    var result = new MultivaluedHashMap<String, String>();
                                    result.add(
                                            HttpHeaders.AUTHORIZATION,
                                            credentialSupplier.get().toHttpAuthorization());
                                    return result;
                                });
            }
        }
    }
}
