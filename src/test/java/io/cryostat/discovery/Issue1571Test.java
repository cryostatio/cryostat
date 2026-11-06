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

import java.util.List;

import io.cryostat.discovery.KubeEndpointSlicesDiscovery.KubeDiscoveryNodeType;

import io.quarkus.test.junit.QuarkusTest;
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
 * on equals() method, and verifies that the V4.2.1 migration's deduplication logic correctly
 * resolves the duplicates.
 *
 * <p>The V4.2.1 migration operates on a schema version where DiscoveryNode/Target ids are still
 * BIGINT (the numeric->UUID conversion doesn't happen until V5.0.0). The current JPA entity classes
 * (e.g. {@link DiscoveryNode}, {@link io.cryostat.targets.Target}) map their id fields as UUID to
 * match the current/head schema, so they cannot be used to read or write rows while the database is
 * pinned to this older, pre-UUID schema version - doing so would throw a type conversion error.
 * This test therefore seeds and verifies data using raw SQL (native queries) matching the actual
 * column types at each schema version under test, rather than going through Hibernate/Panache.
 */
@QuarkusTest
public class Issue1571Test {

    @Inject EntityManager entityManager;
    @Inject Flyway flyway;
    @Inject UserTransaction userTransaction;

    @Test
    public void testNamespaceDuplicationAndMigration() throws Exception {
        // Step 0: Set up database with migrations up to V4.2.0 (before the dedup fix), where
        // DiscoveryNode/Target ids are still BIGINT
        flyway.clean();
        Flyway.configure()
                .configuration(flyway.getConfiguration())
                .target(MigrationVersion.fromVersion("4.2.0"))
                .load()
                .migrate();

        // Step 1: Simulate the bug by creating duplicate Namespace nodes via raw SQL, matching
        // the BIGINT-id schema in effect at this migration version. This mimics what happened
        // when multiple agents registered with KUBERNETES fill strategy.
        userTransaction.begin();

        long universeId =
                ((Number)
                                entityManager
                                        .createNativeQuery(
                                                "SELECT id FROM DiscoveryNode WHERE nodeType ="
                                                        + " 'Universe'")
                                        .getSingleResult())
                        .longValue();

        long realm1Id = insertDiscoveryNode("test-realm-1", "Realm", universeId);
        long realm2Id = insertDiscoveryNode("test-realm-2", "Realm", universeId);

        // Create DUPLICATE Namespace nodes with the same name (this is the bug)
        long namespace1Id =
                insertDiscoveryNode(
                        "test-namespace", KubeDiscoveryNodeType.NAMESPACE.getKind(), realm1Id);
        long namespace2Id =
                insertDiscoveryNode(
                        "test-namespace", KubeDiscoveryNodeType.NAMESPACE.getKind(), realm2Id);

        // Create DUPLICATE Deployment nodes with the same name under each namespace
        long deployment1Id =
                insertDiscoveryNode(
                        "test-deployment",
                        KubeDiscoveryNodeType.DEPLOYMENT.getKind(),
                        namespace1Id);
        long deployment2Id =
                insertDiscoveryNode(
                        "test-deployment",
                        KubeDiscoveryNodeType.DEPLOYMENT.getKind(),
                        namespace2Id);

        // Create DUPLICATE ReplicaSet nodes with the same name under each deployment
        long replicaSet1Id =
                insertDiscoveryNode(
                        "test-replicaset",
                        KubeDiscoveryNodeType.REPLICASET.getKind(),
                        deployment1Id);
        long replicaSet2Id =
                insertDiscoveryNode(
                        "test-replicaset",
                        KubeDiscoveryNodeType.REPLICASET.getKind(),
                        deployment2Id);

        // Create Pod nodes under each replicaset (different names since they're replicas)
        long pod1Id =
                insertDiscoveryNode(
                        "test-pod-1", KubeDiscoveryNodeType.POD.getKind(), replicaSet1Id);
        long pod2Id =
                insertDiscoveryNode(
                        "test-pod-2", KubeDiscoveryNodeType.POD.getKind(), replicaSet2Id);

        // Create target nodes under each pod, and the Targets they represent
        long targetNode1Id = insertDiscoveryNode("target-node-1", "JVM", pod1Id);
        long targetNode2Id = insertDiscoveryNode("target-node-2", "JVM", pod2Id);

        insertTarget(
                "service:jmx:rmi:///jndi/rmi://localhost:9091/jmxrmi", "target1", targetNode1Id);
        insertTarget(
                "service:jmx:rmi:///jndi/rmi://localhost:9092/jmxrmi", "target2", targetNode2Id);

        entityManager.flush();
        userTransaction.commit();

        // Step 2: Verify the bug exists - we have duplicate Namespace nodes
        userTransaction.begin();

        long namespaceCountBefore =
                ((Number)
                                entityManager
                                        .createNativeQuery(
                                                "SELECT COUNT(*) FROM DiscoveryNode WHERE"
                                                        + " nodeType = 'Namespace' AND name ="
                                                        + " 'test-namespace'")
                                        .getSingleResult())
                        .longValue();
        Assertions.assertEquals(
                2,
                namespaceCountBefore,
                "Should have 2 duplicate Namespace nodes before migration");

        long keepId = Math.min(namespace1Id, namespace2Id);
        long deleteId = Math.max(namespace1Id, namespace2Id);

        long targetCountBefore =
                ((Number)
                                entityManager
                                        .createNativeQuery("SELECT COUNT(*) FROM Target")
                                        .getSingleResult())
                        .longValue();
        Assertions.assertEquals(2, targetCountBefore, "Should have 2 targets before migration");

        userTransaction.commit();

        // Step 3: Run the V4.2.1 migration, which deduplicates k8s lineage DiscoveryNodes
        Flyway.configure()
                .configuration(flyway.getConfiguration())
                .target(MigrationVersion.fromVersion("4.2.1"))
                .load()
                .migrate();

        // Step 4: Verify the migration worked correctly, still using raw SQL since the schema is
        // still BIGINT-based at V4.2.1 (the UUID conversion happens later, in V5.0.0)
        userTransaction.begin();

        List<Object[]> namespacesAfter =
                entityManager
                        .createNativeQuery(
                                "SELECT id, parentNode FROM DiscoveryNode WHERE nodeType ="
                                        + " 'Namespace' AND name = 'test-namespace'")
                        .getResultList();
        Assertions.assertEquals(
                1, namespacesAfter.size(), "Should have only 1 Namespace node after migration");

        Object[] keptNamespace = namespacesAfter.get(0);
        long keptNamespaceId = ((Number) keptNamespace[0]).longValue();
        long keptNamespaceParentId = ((Number) keptNamespace[1]).longValue();
        Assertions.assertEquals(
                keepId, keptNamespaceId, "Should keep the Namespace with the lowest ID");

        long k8sRealmId =
                ((Number)
                                entityManager
                                        .createNativeQuery(
                                                "SELECT id FROM DiscoveryNode WHERE nodeType ="
                                                        + " 'Realm' AND name = 'KubernetesApi'")
                                        .getSingleResult())
                        .longValue();
        Assertions.assertEquals(
                k8sRealmId,
                keptNamespaceParentId,
                "Namespace should be parented to KubernetesApi Realm after migration");

        long deletedNamespaceCount =
                ((Number)
                                entityManager
                                        .createNativeQuery(
                                                "SELECT COUNT(*) FROM DiscoveryNode WHERE id = "
                                                        + deleteId)
                                        .getSingleResult())
                        .longValue();
        Assertions.assertEquals(
                0, deletedNamespaceCount, "Duplicate Namespace node should have been deleted");

        long targetCountAfter =
                ((Number)
                                entityManager
                                        .createNativeQuery("SELECT COUNT(*) FROM Target")
                                        .getSingleResult())
                        .longValue();
        Assertions.assertEquals(2, targetCountAfter, "Should still have 2 targets after migration");

        // Verify Deployment deduplication and re-parenting under the kept Namespace
        List<Object[]> deploymentsAfter =
                entityManager
                        .createNativeQuery(
                                "SELECT id, parentNode FROM DiscoveryNode WHERE nodeType ="
                                        + " 'Deployment' AND name = 'test-deployment'")
                        .getResultList();
        Assertions.assertEquals(
                1, deploymentsAfter.size(), "Should have only 1 Deployment node after migration");
        Object[] keptDeployment = deploymentsAfter.get(0);
        long keptDeploymentId = ((Number) keptDeployment[0]).longValue();
        long keptDeploymentParentId = ((Number) keptDeployment[1]).longValue();
        Assertions.assertEquals(
                keptNamespaceId,
                keptDeploymentParentId,
                "Deployment should be under the kept namespace");

        // Verify ReplicaSet deduplication and re-parenting under the kept Deployment
        List<Object[]> replicaSetsAfter =
                entityManager
                        .createNativeQuery(
                                "SELECT id, parentNode FROM DiscoveryNode WHERE nodeType ="
                                        + " 'ReplicaSet' AND name = 'test-replicaset'")
                        .getResultList();
        Assertions.assertEquals(
                1, replicaSetsAfter.size(), "Should have only 1 ReplicaSet node after migration");
        Object[] keptReplicaSet = replicaSetsAfter.get(0);
        long keptReplicaSetId = ((Number) keptReplicaSet[0]).longValue();
        long keptReplicaSetParentId = ((Number) keptReplicaSet[1]).longValue();
        Assertions.assertEquals(
                keptDeploymentId,
                keptReplicaSetParentId,
                "ReplicaSet should be under the kept deployment");

        // Both pods should now be children of the kept replicaset
        List<Object[]> podsAfter =
                entityManager
                        .createNativeQuery(
                                "SELECT id, parentNode FROM DiscoveryNode WHERE nodeType = 'Pod'"
                                        + " AND name IN ('test-pod-1', 'test-pod-2') ORDER BY"
                                        + " name")
                        .getResultList();
        Assertions.assertEquals(2, podsAfter.size(), "Both pods should still exist");
        for (Object[] pod : podsAfter) {
            long podParentId = ((Number) pod[1]).longValue();
            Assertions.assertEquals(
                    keptReplicaSetId, podParentId, "Pod should now be under the kept replicaset");
        }

        // Verify we can still reach both target nodes, unaffected by the dedup (they were never
        // duplicated), through their respective (now shared) pod->replicaset->deployment->
        // namespace lineage
        long targetNode1ParentPodId =
                ((Number)
                                entityManager
                                        .createNativeQuery(
                                                "SELECT parentNode FROM DiscoveryNode WHERE id = "
                                                        + targetNode1Id)
                                        .getSingleResult())
                        .longValue();
        long targetNode2ParentPodId =
                ((Number)
                                entityManager
                                        .createNativeQuery(
                                                "SELECT parentNode FROM DiscoveryNode WHERE id = "
                                                        + targetNode2Id)
                                        .getSingleResult())
                        .longValue();
        Assertions.assertEquals(
                pod1Id, targetNode1ParentPodId, "Target node 1 should still be under pod 1");
        Assertions.assertEquals(
                pod2Id, targetNode2ParentPodId, "Target node 2 should still be under pod 2");

        userTransaction.commit();

        // Step 5: Migrate the schema the rest of the way to head (including the numeric->UUID
        // conversion in V5.0.0) so that the shared test database is left in the state that the
        // rest of the test suite expects. V5.0.0 truncates DiscoveryNode/Target entirely as part
        // of the id conversion, so there is nothing left to assert about the data seeded above
        // once this completes - this step exists purely to restore a consistent, current-schema
        // database for whichever test runs next.
        flyway.migrate();
    }

    private long insertDiscoveryNode(String name, String nodeType, long parentId) {
        return ((Number)
                        entityManager
                                .createNativeQuery(
                                        "INSERT INTO DiscoveryNode(id, labels, name, nodeType,"
                                            + " parentNode) VALUES (nextval('DiscoveryNode_SEQ'),"
                                            + " '{}'::jsonb, :name, :nodeType, :parentId) RETURNING"
                                            + " id")
                                .setParameter("name", name)
                                .setParameter("nodeType", nodeType)
                                .setParameter("parentId", parentId)
                                .getSingleResult())
                .longValue();
    }

    private long insertTarget(String connectUrl, String alias, long discoveryNodeId) {
        return ((Number)
                        entityManager
                                .createNativeQuery(
                                        "INSERT INTO Target(id, alias, annotations, connectUrl,"
                                                + " labels, discoveryNode) VALUES"
                                                + " (nextval('Target_SEQ'), :alias, '{}'::jsonb,"
                                                + " convert_to(:connectUrl, 'UTF8'), '{}'::jsonb,"
                                                + " :discoveryNodeId) RETURNING id")
                                .setParameter("alias", alias)
                                .setParameter("connectUrl", connectUrl)
                                .setParameter("discoveryNodeId", discoveryNodeId)
                                .getSingleResult())
                .longValue();
    }
}
