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
package io.cryostat.graphql;

import static io.restassured.RestAssured.given;

import java.net.URI;
import java.util.List;
import java.util.Map;

import io.cryostat.discovery.DiscoveryNode;
import io.cryostat.targets.Target;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;

public abstract class TargetNodesAuditTestBase extends AbstractGraphQLTestBase {

    protected static final int REVTYPE_ADD = 0;
    protected static final int REVTYPE_MOD = 1;
    protected static final int REVTYPE_DEL = 2;

    @Inject protected EntityManager entityManager;

    @AfterEach
    @Transactional
    void cleanup() throws Exception {
        // Clean up any custom targets created during tests
        List<Target> targets = Target.listAll();
        for (Target target : targets) {
            if (target.connectUrl != null
                    && !target.connectUrl.equals(
                            "service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi")) {
                target.delete();
            }
        }
    }

    @Transactional
    protected Target createTestTarget(String alias, String jvmId) {
        // Create a discovery node first
        DiscoveryNode node = new DiscoveryNode();
        node.name = String.format("service:jmx:rmi:///jndi/rmi://localhost:9999/%s", alias);
        node.nodeType = "JVM";
        node.labels = Map.of();
        node.persist();

        // Flush to get the node ID assigned
        entityManager.flush();

        // Now create the target with the node relationship
        Target target = new Target();
        target.alias = alias;
        target.jvmId = jvmId;
        target.connectUrl = URI.create(node.name);
        target.labels = Map.of();
        target.annotations = new Target.Annotations();
        target.discoveryNode = node;
        node.target = target;

        target.persist();

        // Flush to ensure audit records are created with proper relationships
        entityManager.flush();

        return target;
    }

    @Transactional
    protected void updateTarget(Target target) {
        Target managedTarget = Target.findById(target.id);
        if (managedTarget != null) {
            managedTarget.alias = target.alias;
            managedTarget.persist();
        }
    }

    @Transactional
    protected void deleteTarget(Target target) {
        // Refresh the target to ensure it's managed by the current persistence context
        Target managedTarget = Target.findById(target.id);
        if (managedTarget != null) {
            // Delete target ONLY - do NOT delete the DiscoveryNode
            // The audit log is append-only and should preserve historical DiscoveryNode data
            managedTarget.delete();

            // Flush and clear to ensure the delete is persisted and audit record is created
            entityManager.flush();
            entityManager.clear();
        }
    }

    protected Response queryTargetNodes(Boolean useAuditLog) {
        JsonObject query = new JsonObject();
        String queryStr =
                useAuditLog != null
                        ? String.format(
                                "query { targetNodes(useAuditLog: %b) { name nodeType target {"
                                        + " alias jvmId connectUrl } } }",
                                useAuditLog)
                        : "query { targetNodes { name nodeType target { alias jvmId connectUrl } }"
                                + " }";
        query.put("query", queryStr);

        return given().contentType(ContentType.JSON)
                .body(query.encode())
                .when()
                .post("/api/v4/graphql")
                .then()
                .extract()
                .response();
    }

    @SuppressWarnings("unchecked")
    protected List<Object[]> queryTargetAuditRecords(String jvmId) {
        return entityManager
                .createNativeQuery(
                        "SELECT id, REV, REVTYPE, jvmId FROM Target_AUD WHERE jvmId = :jvmId"
                                + " ORDER BY REV")
                .setParameter("jvmId", jvmId)
                .getResultList();
    }

    @SuppressWarnings("unchecked")
    protected List<Object[]> queryAllTargetAuditRecords() {
        return entityManager
                .createNativeQuery("SELECT id, REV, REVTYPE, jvmId FROM Target_AUD ORDER BY REV")
                .getResultList();
    }
}
