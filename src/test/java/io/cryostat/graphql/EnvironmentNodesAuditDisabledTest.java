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
@TestProfile(EnvironmentNodesAuditDisabledTest.class)
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class EnvironmentNodesAuditDisabledTest extends EnvironmentNodesAuditTestBase
        implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.hibernate-orm.unsupported-properties.\"hibernate.envers.enabled\"",
                "false");
    }

    @Test
    public void testEnvironmentNodesWithoutAuditLogReturnsActiveNodes() throws Exception {
        createTestEnvironmentNode("test-env-no-audit", "Realm", false);

        Response response = queryEnvironmentNodes("test-env-no-audit", null);
        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));

        EnvironmentNodesResponse actual =
                mapper.readValue(response.body().asString(), EnvironmentNodesResponse.class);

        boolean found =
                actual.getData().getEnvironmentNodes().stream()
                        .anyMatch(n -> "test-env-no-audit".equals(n.name));
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
    public void testEnvironmentNodesWithAuditLogTrueReturnsEmptyWhenAuditDisabled()
            throws Exception {
        createTestEnvironmentNode("test-env-audit-disabled", "Realm", false);

        // With audit disabled, useAuditLog=true gracefully returns empty (no _AUD records)
        Response response = queryEnvironmentNodes("test-env-audit-disabled", true);
        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));

        EnvironmentNodesResponse actual =
                mapper.readValue(response.body().asString(), EnvironmentNodesResponse.class);

        boolean found =
                actual.getData().getEnvironmentNodes().stream()
                        .anyMatch(n -> "test-env-audit-disabled".equals(n.name));
        assertThat(
                "Node should not be found via audit log when audit is disabled", found, is(false));
    }

    @Test
    public void testEnvironmentNodesWithAuditLogFalseReturnsActiveNodes() throws Exception {
        createTestEnvironmentNode("test-env-explicit-false", "Realm", false);

        Response response = queryEnvironmentNodes("test-env-explicit-false", false);
        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));

        EnvironmentNodesResponse actual =
                mapper.readValue(response.body().asString(), EnvironmentNodesResponse.class);

        boolean found =
                actual.getData().getEnvironmentNodes().stream()
                        .anyMatch(n -> "test-env-explicit-false".equals(n.name));
        assertThat("Active node should be found with useAuditLog=false", found, is(true));
    }
}
