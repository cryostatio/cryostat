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

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.targets.Target;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;

@QuarkusTest
class DiscoveryTreeConsistencyTest extends AbstractTransactionalTestBase {

    @Inject EntityManager entityManager;

    @Test
    void shouldReplaceRealmChildrenAtomically() {
        UUID pluginId = QuarkusTransaction.requiringNew().call(() -> createPlugin("atomic-realm"));

        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            DiscoveryPlugin plugin =
                                    entityManager.find(DiscoveryPlugin.class, pluginId);
                            DiscoveryNode realm = plugin.realm;
                            DiscoveryNode stale =
                                    targetNode(
                                            "service:jmx:rmi:///jndi/rmi://127.0.0.1:9091/jmxrmi");
                            realm.children.add(stale);
                            stale.parent = realm;
                            entityManager.persist(stale);
                            entityManager.flush();
                        });

        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            DiscoveryPlugin plugin =
                                    entityManager.find(DiscoveryPlugin.class, pluginId);
                            DiscoveryNode realm = plugin.realm;
                            List<DiscoveryNode> replacement = new ArrayList<>();
                            replacement.add(
                                    targetNode(
                                            "service:jmx:rmi:///jndi/rmi://127.0.0.2:9091/jmxrmi"));
                            replacement.add(
                                    targetNode(
                                            "service:jmx:rmi:///jndi/rmi://127.0.0.3:9091/jmxrmi"));
                            List<DiscoveryNode> staleChildren = new ArrayList<>(realm.children);
                            realm.children.clear();
                            staleChildren.forEach(node -> node.parent = null);
                            for (DiscoveryNode node : replacement) {
                                node.parent = realm;
                                realm.children.add(node);
                                entityManager.persist(node);
                            }
                            entityManager.flush();
                        });

        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            DiscoveryPlugin plugin =
                                    entityManager.find(DiscoveryPlugin.class, pluginId);
                            DiscoveryNode realm = plugin.realm;
                            assertEquals(2, realm.children.size());
                            assertEquals(
                                    List.of(
                                            "service:jmx:rmi:///jndi/rmi://127.0.0.2:9091/jmxrmi",
                                            "service:jmx:rmi:///jndi/rmi://127.0.0.3:9091/jmxrmi"),
                                    realm.children.stream()
                                            .map(n -> n.target.connectUrl.toString())
                                            .sorted()
                                            .toList());
                        });
    }

    @Test
    void shouldAcquirePessimisticWriteLockForRealmMutation() {
        UUID pluginId = QuarkusTransaction.requiringNew().call(() -> createPlugin("locked-realm"));

        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            DiscoveryPlugin plugin =
                                    entityManager.find(DiscoveryPlugin.class, pluginId);
                            DiscoveryNode lockedRealm =
                                    entityManager.find(
                                            DiscoveryNode.class,
                                            plugin.realm.id,
                                            LockModeType.PESSIMISTIC_WRITE);
                            assertNotNull(lockedRealm);
                            assertEquals(plugin.realm.id, lockedRealm.id);
                        });
    }

    private UUID createPlugin(String realmName) {
        DiscoveryNode realm = new DiscoveryNode();
        realm.name = realmName;
        realm.nodeType = NodeType.BaseNodeType.REALM.getKind();
        realm.persist();

        DiscoveryPlugin plugin = new DiscoveryPlugin();
        plugin.realm = realm;
        plugin.callback = URI.create("http://storedcredentials:1@localhost:8181/callback");
        plugin.credential = null;
        plugin.builtin = true;
        plugin.persist();

        return plugin.id;
    }

    private DiscoveryNode targetNode(String connectUrl) {
        Target target = new Target();
        target.alias = connectUrl;
        target.connectUrl = URI.create(connectUrl);
        target.jvmId = UUID.randomUUID().toString();
        target.labels = new java.util.HashMap<>();
        target.activeRecordings = new ArrayList<>();

        DiscoveryNode node = new DiscoveryNode();
        node.name = connectUrl;
        node.nodeType = NodeType.BaseNodeType.JVM.getKind();
        node.target = target;
        node.children = new ArrayList<>();
        target.discoveryNode = node;
        return node;
    }
}
