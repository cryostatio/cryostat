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
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.List;
import java.util.Map;

import io.cryostat.graphql.GraphQLTestModels.EnvironmentNodesResponse;
import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(EnvironmentNodesAuditEnabledTest.class)
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class EnvironmentNodesAuditEnabledTest extends EnvironmentNodesAuditTestBase
        implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.hibernate-orm.unsupported-properties.\"hibernate.envers.enabled\"",
                "true");
    }

    @Test
    public void testEnvironmentNodesWithoutAuditLogReturnsActiveOnly() throws Exception {
        createTestEnvironmentNode("test-env-active", "Realm", true);

        Response response = queryEnvironmentNodes("test-env-active", null);
        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));

        EnvironmentNodesResponse actual =
                mapper.readValue(response.body().asString(), EnvironmentNodesResponse.class);

        boolean found =
                actual.getData().getEnvironmentNodes().stream()
                        .anyMatch(n -> "test-env-active".equals(n.name));
        assertThat("Active environment node should be found", found, is(true));
    }

    @Test
    public void testEnvironmentNodesWithAuditLogTrueAndNullFilterThrowsError() throws Exception {
        JsonObject query = new JsonObject();
        query.put("query", "query { environmentNodes(useAuditLog: true) { name nodeType } }");

        Response response =
                given().contentType(ContentType.JSON)
                        .body(query.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .extract()
                        .response();

        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));
        String body = response.body().asString();
        assertThat("Response should contain an error", body, containsString("errors"));
        assertThat("Error should mention filter requirement", body, containsString("filter"));
    }

    @Test
    public void testEnvironmentNodesWithAuditLogTrueAndBlankFilterThrowsError() throws Exception {
        JsonObject query = new JsonObject();
        query.put(
                "query",
                "query { environmentNodes(filter: {}, useAuditLog: true) { name nodeType } }");

        Response response =
                given().contentType(ContentType.JSON)
                        .body(query.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .extract()
                        .response();

        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));
        String body = response.body().asString();
        assertThat("Response should contain an error", body, containsString("errors"));
    }

    @Test
    public void testEnvironmentNodesWithAuditLogRetrievesHistoricalNodes() throws Exception {
        createTestEnvironmentNode("test-env-historical", "Realm", true);

        List<Object[]> auditRecords = queryDiscoveryNodeAuditRecords("test-env-historical");
        assertThat(
                "Audit record should exist for created node",
                auditRecords,
                hasSize(greaterThanOrEqualTo(1)));
        assertThat(
                "Audit record should be ADD type",
                ((Number) auditRecords.get(0)[2]).intValue(),
                equalTo(REVTYPE_ADD));

        // Query with useAuditLog=true should return the node from the audit log
        Response response = queryEnvironmentNodes("test-env-historical", true);
        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));

        EnvironmentNodesResponse actual =
                mapper.readValue(response.body().asString(), EnvironmentNodesResponse.class);

        boolean foundInAudit =
                actual.getData().getEnvironmentNodes().stream()
                        .anyMatch(n -> "test-env-historical".equals(n.name));
        assertThat("Environment node SHOULD be retrievable from audit log", foundInAudit, is(true));
    }

    @Test
    public void testEnvironmentNodesWithAuditLogReturnsLatestRevision() throws Exception {
        long nodeId = createTestEnvironmentNode("test-env-label-update", "Realm", true);

        // Update labels (name/nodeType are updatable=false; labels can be updated)
        updateEnvironmentNodeLabel(nodeId, "test-key", "test-value");

        List<Object[]> auditRecords = queryDiscoveryNodeAuditRecords("test-env-label-update");
        assertThat(
                "Should have at least 2 audit records (ADD + MOD)",
                auditRecords,
                hasSize(greaterThanOrEqualTo(2)));
        assertThat(
                "First record should be ADD",
                ((Number) auditRecords.get(0)[2]).intValue(),
                equalTo(REVTYPE_ADD));
        assertThat(
                "Second record should be MOD",
                ((Number) auditRecords.get(1)[2]).intValue(),
                equalTo(REVTYPE_MOD));

        // Query via audit log — the latest revision should be returned (one entry per node)
        Response response = queryEnvironmentNodes("test-env-label-update", true);
        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));

        EnvironmentNodesResponse actual =
                mapper.readValue(response.body().asString(), EnvironmentNodesResponse.class);

        long count =
                actual.getData().getEnvironmentNodes().stream()
                        .filter(n -> "test-env-label-update".equals(n.name))
                        .count();
        assertThat("Should return exactly one entry for the node", count, equalTo(1L));
    }

    @Test
    public void testBackwardCompatibility() throws Exception {
        createTestEnvironmentNode("test-env-compat", "Realm", true);

        Response response = queryEnvironmentNodes("test-env-compat", null);
        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));

        EnvironmentNodesResponse actual =
                mapper.readValue(response.body().asString(), EnvironmentNodesResponse.class);

        boolean found =
                actual.getData().getEnvironmentNodes().stream()
                        .anyMatch(n -> "test-env-compat".equals(n.name));
        assertThat("Node should be found with default behavior", found, is(true));
    }
}
