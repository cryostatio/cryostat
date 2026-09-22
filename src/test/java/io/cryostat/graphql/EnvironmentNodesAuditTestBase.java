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

import java.util.HashMap;
import java.util.List;

import io.cryostat.discovery.DiscoveryNode;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;

public abstract class EnvironmentNodesAuditTestBase extends AbstractGraphQLTestBase {

    protected static final int REVTYPE_ADD = 0;
    protected static final int REVTYPE_MOD = 1;
    protected static final int REVTYPE_DEL = 2;

    @Inject protected EntityManager entityManager;

    @AfterEach
    @Transactional
    void cleanup() throws Exception {
        entityManager
                .createNativeQuery(
                        "DELETE FROM DiscoveryNode WHERE name LIKE 'test-env-%'"
                                + " AND id NOT IN (SELECT DISTINCT discoveryNode FROM Target"
                                + " WHERE discoveryNode IS NOT NULL)")
                .executeUpdate();
    }

    /**
     * Inserts a DiscoveryNode via native SQL to avoid JPA cascade/orphan interactions with the
     * universe node's children collection. Sets parentNode to the Universe node's id so the node
     * appears in the active discovery tree.
     *
     * @param writeAuditRecord when true, also inserts an Envers ADD revision into
     *     DiscoveryNode_AUD, mirroring what V4.2.0 migration does for built-in realm nodes. Pass
     *     true only in tests where Envers is enabled.
     */
    @Transactional
    protected long createTestEnvironmentNode(
            String name, String nodeType, boolean writeAuditRecord) {
        Number universeId =
                (Number)
                        entityManager
                                .createNativeQuery(
                                        "SELECT id FROM DiscoveryNode WHERE nodeType = 'Universe'")
                                .getSingleResult();
        Number nodeId =
                (Number)
                        entityManager
                                .createNativeQuery("SELECT nextval('DiscoveryNode_SEQ')")
                                .getSingleResult();
        entityManager
                .createNativeQuery(
                        "INSERT INTO DiscoveryNode (id, labels, name, nodeType, parentNode)"
                                + " VALUES (:id, '{}'::jsonb, :name, :nodeType, :parentNode)")
                .setParameter("id", nodeId)
                .setParameter("name", name)
                .setParameter("nodeType", nodeType)
                .setParameter("parentNode", universeId)
                .executeUpdate();
        if (writeAuditRecord) {
            Number rev =
                    (Number)
                            entityManager
                                    .createNativeQuery("SELECT nextval('REVINFO_SEQ')")
                                    .getSingleResult();
            entityManager
                    .createNativeQuery(
                            "INSERT INTO REVINFO (REV, REVTSTMP, username)"
                                    + " VALUES (:rev, :ts, :user)")
                    .setParameter("rev", rev)
                    .setParameter("ts", System.currentTimeMillis())
                    .setParameter("user", "test")
                    .executeUpdate();
            entityManager
                    .createNativeQuery(
                            "INSERT INTO DiscoveryNode_AUD"
                                    + " (id, REV, REVTYPE, REVEND, REVEND_TSTMP, name, nodeType,"
                                    + " labels, parentNode)"
                                    + " VALUES (:id, :rev, 0, NULL, NULL, :name, :nodeType, '{}',"
                                    + " :parentNode)")
                    .setParameter("id", nodeId)
                    .setParameter("rev", rev)
                    .setParameter("name", name)
                    .setParameter("nodeType", nodeType)
                    .setParameter("parentNode", universeId)
                    .executeUpdate();
        }
        entityManager.flush();
        entityManager.clear();
        return nodeId.longValue();
    }

    @Transactional
    protected void updateEnvironmentNodeLabel(long nodeId, String labelKey, String labelValue) {
        DiscoveryNode managed = entityManager.find(DiscoveryNode.class, nodeId);
        if (managed != null) {
            managed.labels = new HashMap<>(managed.labels);
            managed.labels.put(labelKey, labelValue);
            entityManager.merge(managed);
            entityManager.flush();
        }
    }

    protected Response queryEnvironmentNodes(String nameFilter, Boolean useAuditLog) {
        JsonObject query = new JsonObject();
        String filterPart =
                nameFilter != null ? String.format("filter: { name: \"%s\" }", nameFilter) : "";
        String auditLogPart =
                useAuditLog != null ? String.format("useAuditLog: %b", useAuditLog) : "";
        String params = buildParamList(filterPart, auditLogPart);
        query.put(
                "query", String.format("query { environmentNodes(%s) { name nodeType } }", params));

        return given().contentType(ContentType.JSON)
                .body(query.encode())
                .when()
                .post("/api/v4/graphql")
                .then()
                .extract()
                .response();
    }

    private String buildParamList(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(part);
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    protected List<Object[]> queryDiscoveryNodeAuditRecords(String name) {
        return entityManager
                .createNativeQuery(
                        "SELECT id, REV, REVTYPE, name FROM DiscoveryNode_AUD WHERE name = :name"
                                + " ORDER BY REV")
                .setParameter("name", name)
                .getResultList();
    }
}
