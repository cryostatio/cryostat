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

import static io.restassured.RestAssured.given;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.cryostat.discovery.KubeEndpointSlicesDiscovery.KubeDiscoveryNodeType;
import io.cryostat.targets.Target;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test for https://github.com/cryostatio/cryostat/issues/1571: GraphQL descendantTargets returns
 * only one target when querying Namespace nodes.
 *
 * <p>This test simulates the bug where multiple Namespace nodes with the same name were created in
 * the database, causing GraphQL queries to return incomplete results due to Set deduplication based
 * on equals() method.
 */
@QuarkusTest
public class Issue1571Test {

    private static final String GRAPHQL_QUERY =
            """
            query {
              environmentNodes(filter: { nodeTypes: ["Namespace"]} ) {
                name
                nodeType
                descendantTargets {
                  target {
                    alias
                  }
                }
              }
            }
            """;

    @Inject EntityManager entityManager;
    @Inject Flyway flyway;
    @Inject UserTransaction userTransaction;

    @Test
    public void testNamespaceDuplicationAndMigration() throws Exception {
        // Step 0: Set up database with migrations up to V4.2.0 (before the fix)
        flyway.clean();
        Flyway targetFlyway =
                Flyway.configure()
                        .configuration(flyway.getConfiguration())
                        .target(MigrationVersion.fromVersion("4.2.0"))
                        .load();
        targetFlyway.migrate();
        entityManager.clear();

        // Step 1: Simulate the bug by creating duplicate Namespace nodes
        userTransaction.begin();
        // This mimics what happened when multiple agents registered with KUBERNETES fill strategy

        // Get the Universe node
        DiscoveryNode universe = DiscoveryNode.getUniverse();
        Assertions.assertNotNull(universe, "Universe node should exist");

        // Create two separate Realm nodes (simulating agent registrations)
        DiscoveryNode realm1 = new DiscoveryNode();
        realm1.name = "test-realm-1";
        realm1.nodeType = "Realm";
        realm1.labels = new HashMap<>();
        realm1.children = new ArrayList<>();
        realm1.parent = universe;
        realm1.persist();

        DiscoveryNode realm2 = new DiscoveryNode();
        realm2.name = "test-realm-2";
        realm2.nodeType = "Realm";
        realm2.labels = new HashMap<>();
        realm2.children = new ArrayList<>();
        realm2.parent = universe;
        realm2.persist();

        // Create DUPLICATE Namespace nodes with the same name (this is the bug)
        DiscoveryNode namespace1 = new DiscoveryNode();
        namespace1.name = "test-namespace";
        namespace1.nodeType = KubeDiscoveryNodeType.NAMESPACE.getKind();
        namespace1.labels = new HashMap<>();
        namespace1.children = new ArrayList<>();
        namespace1.parent = realm1;
        namespace1.persist();

        DiscoveryNode namespace2 = new DiscoveryNode();
        namespace2.name = namespace1.name; // Same name!
        namespace2.nodeType = KubeDiscoveryNodeType.NAMESPACE.getKind();
        namespace2.labels = new HashMap<>();
        namespace2.children = new ArrayList<>();
        namespace2.parent = realm2;
        namespace2.persist();

        // Create Pod nodes under each namespace
        DiscoveryNode pod1 = new DiscoveryNode();
        pod1.name = "test-pod-1";
        pod1.nodeType = KubeDiscoveryNodeType.POD.getKind();
        pod1.labels = new HashMap<>();
        pod1.children = new ArrayList<>();
        pod1.parent = namespace1;
        pod1.persist();

        DiscoveryNode pod2 = new DiscoveryNode();
        pod2.name = "test-pod-2";
        pod2.nodeType = KubeDiscoveryNodeType.POD.getKind();
        pod2.labels = new HashMap<>();
        pod2.children = new ArrayList<>();
        pod2.parent = namespace2;
        pod2.persist();

        // Create target nodes under each pod
        Target target1 = new Target();
        target1.connectUrl = URI.create("service:jmx:rmi:///jndi/rmi://localhost:9091/jmxrmi");
        target1.alias = "target1";
        target1.labels = new HashMap<>();
        target1.annotations = new Target.Annotations();

        DiscoveryNode targetNode1 = new DiscoveryNode();
        targetNode1.name = "target-node-1";
        targetNode1.nodeType = "JVM";
        targetNode1.labels = new HashMap<>();
        targetNode1.children = new ArrayList<>();
        targetNode1.parent = pod1;
        targetNode1.target = target1;
        target1.discoveryNode = targetNode1;
        targetNode1.persist();
        target1.persist();

        Target target2 = new Target();
        target2.connectUrl = URI.create("service:jmx:rmi:///jndi/rmi://localhost:9092/jmxrmi");
        target2.alias = "target2";
        target2.labels = new HashMap<>();
        target2.annotations = new Target.Annotations();

        DiscoveryNode targetNode2 = new DiscoveryNode();
        targetNode2.name = "target-node-2";
        targetNode2.nodeType = "JVM";
        targetNode2.labels = new HashMap<>();
        targetNode2.children = new ArrayList<>();
        targetNode2.parent = pod2;
        targetNode2.target = target2;
        target2.discoveryNode = targetNode2;
        targetNode2.persist();
        target2.persist();

        entityManager.flush();
        userTransaction.commit();
        entityManager.clear();

        // Step 2: Verify the bug exists - we have duplicate Namespace nodes
        userTransaction.begin();
        List<DiscoveryNode> namespacesBefore =
                DiscoveryNode.<DiscoveryNode>find(
                                "nodeType = ?1 and name = ?2",
                                KubeDiscoveryNodeType.NAMESPACE.getKind(),
                                "test-namespace")
                        .list();

        Assertions.assertEquals(
                2,
                namespacesBefore.size(),
                "Should have 2 duplicate Namespace nodes before migration");

        Long namespace1Id = namespace1.id;
        Long namespace2Id = namespace2.id;
        Long keepId = Math.min(namespace1Id, namespace2Id);
        Long deleteId = Math.max(namespace1Id, namespace2Id);

        // Verify both targets exist
        List<Target> targetsBefore = Target.listAll();
        Assertions.assertEquals(2, targetsBefore.size(), "Should have 2 targets before migration");

        // Verify the bug exists via GraphQL - should only return 1 target due to Set deduplication
        JsonPath responseBefore =
                given().contentType(ContentType.JSON)
                        .body(Map.of("query", GRAPHQL_QUERY))
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(200)
                        .extract()
                        .jsonPath();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> environmentNodesBefore =
                (List<Map<String, Object>>)
                        (List<?>) responseBefore.getList("data.environmentNodes", Map.class);
        Assertions.assertEquals(
                1,
                environmentNodesBefore.size(),
                "GraphQL returns only 1 Namespace node before migration due to Set deduplication"
                        + " (the bug)");

        // Count total targets across all namespace nodes
        int totalTargetsBefore = 0;
        for (Map<String, Object> node : environmentNodesBefore) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> descendants =
                    (List<Map<String, Object>>) node.get("descendantTargets");
            if (descendants != null) {
                totalTargetsBefore += descendants.size();
            }
        }

        Assertions.assertEquals(
                1,
                totalTargetsBefore,
                "GraphQL should return only 1 target before migration (bug)");

        userTransaction.commit();

        // Step 3: Run the V4.2.1 migration
        flyway.migrate();
        // Clear the EntityManager cache to ensure we're reading fresh data from the database
        // after the migration has run
        entityManager.clear();
        // Also clear the Hibernate second-level cache if it exists
        entityManager.getEntityManagerFactory().getCache().evictAll();

        // Step 4: Verify the migration worked correctly
        userTransaction.begin();

        // Should now have only ONE Namespace node
        List<DiscoveryNode> namespacesAfter =
                DiscoveryNode.<DiscoveryNode>find(
                                "nodeType = ?1 and name = ?2",
                                KubeDiscoveryNodeType.NAMESPACE.getKind(),
                                "test-namespace")
                        .list();

        Assertions.assertEquals(
                1, namespacesAfter.size(), "Should have only 1 Namespace node after migration");

        DiscoveryNode keptNamespace = namespacesAfter.get(0);
        Assertions.assertEquals(
                keepId, keptNamespace.id, "Should keep the Namespace with the lowest ID");

        // Verify the Namespace is now parented to KubernetesApi Realm
        DiscoveryNode k8sRealm =
                DiscoveryNode.getRealm(KubeEndpointSlicesDiscovery.REALM)
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "KubernetesApi realm should exist after"
                                                        + " migration"));
        Assertions.assertNotNull(
                keptNamespace.parent, "Namespace should have a parent after migration");
        Assertions.assertEquals(
                k8sRealm.id,
                keptNamespace.parent.id,
                "Namespace should be parented to KubernetesApi Realm after migration");

        DiscoveryNode deletedNamespace = DiscoveryNode.findById(deleteId);
        Assertions.assertNull(
                deletedNamespace, "Duplicate Namespace node should have been deleted");

        List<Target> targetsAfter = Target.listAll();
        Assertions.assertEquals(
                2, targetsAfter.size(), "Should still have 2 targets after migration");

        // Verify both target nodes now point to the kept namespace (through their pod parents)
        DiscoveryNode targetNode1After = DiscoveryNode.findById(targetNode1.id);
        DiscoveryNode targetNode2After = DiscoveryNode.findById(targetNode2.id);

        Assertions.assertNotNull(targetNode1After, "Target node 1 should still exist");
        Assertions.assertNotNull(targetNode2After, "Target node 2 should still exist");

        // Both pods should now be children of the kept namespace
        DiscoveryNode pod1After = DiscoveryNode.findById(pod1.id);
        DiscoveryNode pod2After = DiscoveryNode.findById(pod2.id);

        Assertions.assertNotNull(pod1After, "Pod 1 should still exist");
        Assertions.assertNotNull(pod2After, "Pod 2 should still exist");
        Assertions.assertEquals(
                keepId, pod1After.parent.id, "Pod 1 should now be under the kept namespace");
        Assertions.assertEquals(
                keepId, pod2After.parent.id, "Pod 2 should now be under the kept namespace");

        // Verify the kept namespace has both pods as children
        DiscoveryNode keptNamespaceWithChildren = DiscoveryNode.findById(keepId);
        Assertions.assertEquals(
                2,
                keptNamespaceWithChildren.children.size(),
                "Kept namespace should have 2 children (both pods)");

        // Verify we can query all descendant targets through the single namespace
        List<DiscoveryNode> descendantTargets =
                keptNamespaceWithChildren.children.stream()
                        .flatMap(pod -> pod.children.stream())
                        .filter(node -> node.target != null)
                        .toList();

        Assertions.assertEquals(
                2,
                descendantTargets.size(),
                "Should be able to find both targets through the single namespace");

        // Step 5: Verify GraphQL API also returns both targets
        // This tests the actual API that users would call;
        JsonPath response =
                given().contentType(ContentType.JSON)
                        .body(Map.of("query", GRAPHQL_QUERY))
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(200)
                        .extract()
                        .jsonPath();

        // Verify we get exactly one Namespace node
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> environmentNodes =
                (List<Map<String, Object>>)
                        (List<?>) response.getList("data.environmentNodes", Map.class);
        Assertions.assertEquals(
                1, environmentNodes.size(), "GraphQL should return exactly 1 Namespace node");

        Map<String, Object> namespaceNode = environmentNodes.get(0);
        Assertions.assertEquals(
                "test-namespace", namespaceNode.get("name"), "Namespace name should match");
        Assertions.assertEquals(
                "Namespace", namespaceNode.get("nodeType"), "Node type should be Namespace");

        // Verify we get both targets through descendantTargets
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> descendantTargetsFromGraphQL =
                (List<Map<String, Object>>) namespaceNode.get("descendantTargets");
        Assertions.assertEquals(
                2,
                descendantTargetsFromGraphQL.size(),
                "GraphQL should return both targets through descendantTargets");

        // Verify the target aliases
        List<String> aliases =
                descendantTargetsFromGraphQL.stream()
                        .map(dt -> (Map<String, Object>) dt.get("target"))
                        .map(t -> (String) t.get("alias"))
                        .sorted()
                        .toList();

        Assertions.assertEquals(
                List.of("target1", "target2"),
                aliases,
                "Should have both target aliases in GraphQL response");

        userTransaction.commit();
    }
}
