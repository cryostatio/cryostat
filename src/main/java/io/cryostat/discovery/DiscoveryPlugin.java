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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import io.cryostat.credentials.Credential;
import io.cryostat.discovery.KubeEndpointSlicesDiscovery.KubeDiscoveryNodeType;

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
import jakarta.persistence.Cacheable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
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
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.envers.Audited;
import org.jboss.logging.Logger;

/**
 * DiscoveryPlugin instances track registrations of discovery plugins. The reference implementation
 * for a discovery plugin is the Cryostat Agent. Plugins communicate with Cryostat via the {@link
 * io.cryostat.discovery.Discovery} API endpoints. Registration through that API generates a
 * DiscoveryPlugin record to place that plugin into the discovery tree.
 */
@Audited
@Entity
@EntityListeners(DiscoveryPlugin.Listener.class)
@Cacheable
@NamedQueries({
    @NamedQuery(
            name = "DiscoveryPlugin.getBuiltinRealmIds",
            query = "SELECT p.realm.id FROM DiscoveryPlugin p WHERE p.builtin = true"),
    @NamedQuery(
            name = "DiscoveryPlugin.findByCallbackAndRealmName",
            query =
                    "SELECT p FROM DiscoveryPlugin p JOIN FETCH p.realm r WHERE p.callback = ?1"
                            + " AND r.name = ?2")
})
public class DiscoveryPlugin extends PanacheEntityBase {

    @Id
    @Column(name = "id")
    @GeneratedValue
    @UuidGenerator
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
            cascade = {CascadeType.PERSIST, CascadeType.MERGE, CascadeType.REMOVE},
            orphanRemoval = true)
    @JsonIgnore
    @Nullable
    @Cache(usage = CacheConcurrencyStrategy.READ_ONLY)
    public Credential credential;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public boolean builtin;

    @Column(nullable = false)
    @JsonIgnore
    public int consecutiveFailures = 0;

    @Column(nullable = true)
    @Convert(converter = InstantConverter.class)
    @JsonIgnore
    public Instant lastSuccessfulPing;

    @Column(nullable = true)
    @Convert(converter = InstantConverter.class)
    @JsonIgnore
    public Instant lastFailedPing;

    @Column(nullable = false)
    @JsonIgnore
    public int backoffMultiplier = 1;

    @Column(nullable = true)
    @Convert(converter = InstantConverter.class)
    @JsonIgnore
    public Instant nextPingAt;

    public static Optional<DiscoveryPlugin> findByCallbackAndRealmName(
            URI callback, String realmName) {
        return DiscoveryPlugin.<DiscoveryPlugin>find(
                        "#DiscoveryPlugin.findByCallbackAndRealmName", callback, realmName)
                .singleResultOptional();
    }

    /**
     * Helper class for plugin cleanup operations in cases where nodes are not under the plugin's
     * Realm, such as Agent instances using the publication fill algorithm.
     */
    @ApplicationScoped
    static class PluginCleanupHelper {

        @Inject EntityManager entityManager;
        @Inject Logger logger;

        void cleanupPluginNodes(DiscoveryPlugin plugin) {
            cleanupPluginNodes(plugin.id);
        }

        void cleanupPluginNodes(UUID pluginId) {
            // Clean up target nodes that belong to this plugin
            // For KUBERNETES fill strategy, these are under KubernetesApi Realm, tagged with
            // plugin ID
            // For NONE fill strategy, cascade deletion handles cleanup when Agent Realm is
            // deleted
            List<DiscoveryNode> pluginNodes = DiscoveryNode.getByPluginId(pluginId);
            if (pluginNodes == null || pluginNodes.isEmpty()) {
                return;
            }
            for (DiscoveryNode node : pluginNodes) {
                try {
                    DiscoveryNode parent = node.parent;
                    if (parent != null) {
                        // Remove node from parent's collection before deleting
                        // This ensures parent.hasChildren() will be accurate
                        parent.children.remove(node);
                        node.parent = null;
                    }
                    node.delete();
                    // Recursively prune empty parent nodes up the tree
                    pruneEmptyAncestors(parent);
                } catch (Exception e) {
                    logger.warn(e);
                }
            }
        }

        void pruneEmptyAncestors(DiscoveryNode child) {
            // Walk up the hierarchy, removing childless nodes from their parents
            // Follow the same pattern as KubeEndpointSlicesDiscovery.pruneOwnerChain()
            while (child != null) {
                DiscoveryNode parent = child.parent;

                if (parent == null) {
                    logger.debugv(
                            "Reached orphaned node {0} (id={1}), relying on orphan removal",
                            child.name, child.id);
                    break;
                }

                entityManager.refresh(parent); // Reload from DB with current children

                // Remove child from parent's collection - orphan removal will delete it
                parent.children.remove(child);
                child.parent = null;

                boolean hasChildren = parent.hasChildren();
                boolean isNamespace =
                        parent.nodeType.equals(KubeDiscoveryNodeType.NAMESPACE.getKind());
                boolean isRealm = parent.nodeType.equals("Realm");

                logger.debugv(
                        "Parent node {0} (id={1}, type={2}): hasChildren={3}, isNamespace={4},"
                                + " isRealm={5}",
                        parent.name, parent.id, parent.nodeType, hasChildren, isNamespace, isRealm);

                // Stop pruning if:
                // 1. Parent is a Realm (always preserve Realms)
                // 2. Parent has children (preserve non-empty nodes)
                // Note: Empty Namespaces should be pruned, so we don't stop just because it's a
                // Namespace
                if (isRealm || hasChildren) {
                    logger.debugv("Stopping pruning at parent node {0}", parent.name);
                    break;
                }

                logger.debugv("Continuing to prune parent node {0}", parent.name);
                // Move up to check the parent too
                child = parent;
            }
        }
    }

    @ApplicationScoped
    static class Listener {

        @Inject Logger logger;
        @Inject PluginCallbackFactory callbackFactory;
        @Inject PluginCleanupHelper cleanupHelper;

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
                credential.discoveryPlugin = plugin;
                plugin.callback = UriBuilder.fromUri(plugin.callback).userInfo(null).build();
            }
            if (plugin.nextPingAt != null
                    || plugin.lastFailedPing != null
                    || plugin.lastSuccessfulPing != null) {
                logger.debugv(
                        "Skipping prePersist ping for plugin with existing state: {0} @ {1}",
                        plugin.realm.name, plugin.callback);
                return;
            }
            try {
                logger.debugv(
                        "Testing discovery plugin callback: {0} @ {1}",
                        plugin.realm.name, plugin.callback);
                callbackFactory.create(plugin).ping();
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

        @PreRemove
        @Transactional
        public void preRemove(DiscoveryPlugin plugin) {
            logger.debugv("PreRemove lifecycle hook triggered for plugin ID {0}", plugin.id);
            try {
                cleanupHelper.cleanupPluginNodes(plugin);
            } catch (Exception e) {
                logger.warn("Error during plugin cleanup", e);
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
