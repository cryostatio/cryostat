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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.List;
import java.util.Map;

import io.cryostat.graphql.GraphQLTestModels.TargetNodesQueryResponse;
import io.cryostat.resources.S3StorageResource;
import io.cryostat.targets.Target;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(TargetNodesAuditEnabledTest.class)
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class TargetNodesAuditEnabledTest extends TargetNodesAuditTestBase
        implements QuarkusTestProfile {

    @BeforeAll
    public static void setupAuditEnvironment() {
        // ConditionalEnversIntegrator checks System.getenv("CRYOSTAT_AUDIT_ENABLED")
        // We can't set environment variables at runtime, but we can use a system property
        // and modify ConditionalEnversIntegrator to check both
        System.setProperty("CRYOSTAT_AUDIT_ENABLED", "true");
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.hibernate-orm.unsupported-properties.\"hibernate.envers.enabled\"",
                "true");
    }

    @Test
    public void testTargetNodesWithoutAuditLogReturnsActiveOnly() throws Exception {
        createTestTarget("test-active-only", "jvm-active-123");

        Response response = queryTargetNodes(null);
        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));

        TargetNodesQueryResponse actual =
                mapper.readValue(response.body().asString(), TargetNodesQueryResponse.class);

        // Should include the active target plus the default selftest target
        assertThat(actual.data.targetNodes, hasSize(greaterThanOrEqualTo(1)));

        boolean found =
                actual.data.targetNodes.stream()
                        .anyMatch(
                                node ->
                                        node.getTarget() != null
                                                && "jvm-active-123"
                                                        .equals(node.getTarget().getJvmId()));
        assertThat("Active target should be found", found, is(true));
    }

    @Test
    public void testTargetNodesWithAuditLogFalseReturnsActiveOnly() throws Exception {
        createTestTarget("test-audit-false", "jvm-audit-false-123");

        Response response = queryTargetNodes(false);
        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));

        TargetNodesQueryResponse actual =
                mapper.readValue(response.body().asString(), TargetNodesQueryResponse.class);

        // Should include the active target plus the default selftest target
        assertThat(actual.data.targetNodes, hasSize(greaterThanOrEqualTo(1)));

        boolean found =
                actual.data.targetNodes.stream()
                        .anyMatch(
                                node ->
                                        node.getTarget() != null
                                                && "jvm-audit-false-123"
                                                        .equals(node.getTarget().getJvmId()));
        assertThat("Active target should be found", found, is(true));
    }

    @Test
    public void testTargetNodesWithAuditLogTrueRetrievesHistoricalTargets() throws Exception {
        Target target = createTestTarget("test-historical", "jvm-historical-123");

        // Verify audit record was created
        List<Object[]> auditRecords = queryTargetAuditRecords("jvm-historical-123");
        assertThat("Audit record should exist for created target", auditRecords, hasSize(1));
        assertThat(
                "Audit record should be ADD type",
                ((Number) auditRecords.get(0)[2]).intValue(),
                equalTo(REVTYPE_ADD));

        deleteTarget(target);

        // Query with useAuditLog=false should NOT include deleted target
        Response response = queryTargetNodes(false);
        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));

        TargetNodesQueryResponse actual =
                mapper.readValue(response.body().asString(), TargetNodesQueryResponse.class);

        boolean foundInActive =
                actual.data.targetNodes.stream()
                        .anyMatch(
                                node ->
                                        node.getTarget() != null
                                                && "jvm-historical-123"
                                                        .equals(node.getTarget().getJvmId()));
        assertThat("Deleted target should NOT be in active results", foundInActive, is(false));

        // Query with useAuditLog=true SHOULD include the target from audit history
        // (retrieved from the ADD audit record that was created)
        response = queryTargetNodes(true);
        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));

        actual = mapper.readValue(response.body().asString(), TargetNodesQueryResponse.class);

        boolean foundInAudit =
                actual.data.targetNodes.stream()
                        .anyMatch(
                                node ->
                                        node.getTarget() != null
                                                && "jvm-historical-123"
                                                        .equals(node.getTarget().getJvmId()));
        assertThat(
                "Historical target SHOULD be retrievable from audit log", foundInAudit, is(true));
    }

    @Test
    public void testTargetNodesWithAuditLogReturnsLatestRevision() throws Exception {
        Target target = createTestTarget("test-updated", "jvm-updated-123");

        // Update the target
        target.alias = "test-updated-modified";
        updateTarget(target);

        // Verify multiple audit records exist
        List<Object[]> auditRecords = queryTargetAuditRecords("jvm-updated-123");
        assertThat("Should have 2 audit records (ADD + MOD)", auditRecords, hasSize(2));
        assertThat(
                "First record should be ADD",
                ((Number) auditRecords.get(0)[2]).intValue(),
                equalTo(REVTYPE_ADD));
        assertThat(
                "Second record should be MOD",
                ((Number) auditRecords.get(1)[2]).intValue(),
                equalTo(REVTYPE_MOD));

        Response response = queryTargetNodes(true);
        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));

        TargetNodesQueryResponse actual =
                mapper.readValue(response.body().asString(), TargetNodesQueryResponse.class);

        // Should return only the latest revision (modified alias)
        long count =
                actual.data.targetNodes.stream()
                        .filter(
                                node ->
                                        node.getTarget() != null
                                                && "jvm-updated-123"
                                                        .equals(node.getTarget().getJvmId()))
                        .count();
        assertThat("Should have exactly one result for this jvmId", count, equalTo(1L));

        boolean hasUpdatedAlias =
                actual.data.targetNodes.stream()
                        .anyMatch(
                                node ->
                                        node.getTarget() != null
                                                && "jvm-updated-123"
                                                        .equals(node.getTarget().getJvmId())
                                                && "test-updated-modified"
                                                        .equals(node.getTarget().getAlias()));
        assertThat("Should have the updated alias", hasUpdatedAlias, is(true));
    }

    @Test
    public void testTargetNodesWithAuditLogHandlesMultipleTargets() throws Exception {
        createTestTarget("test-multi-1", "jvm-multi-1");
        Target target2 = createTestTarget("test-multi-2", "jvm-multi-2");
        createTestTarget("test-multi-3", "jvm-multi-3");

        deleteTarget(target2);

        // Query with useAuditLog=true should include all targets from audit history
        // (retrieved from their ADD audit records)
        Response response = queryTargetNodes(true);
        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));

        TargetNodesQueryResponse actual =
                mapper.readValue(response.body().asString(), TargetNodesQueryResponse.class);

        long count =
                actual.data.targetNodes.stream()
                        .filter(
                                node ->
                                        node.getTarget() != null
                                                && node.getTarget().getJvmId() != null
                                                && node.getTarget()
                                                        .getJvmId()
                                                        .startsWith("jvm-multi-"))
                        .count();
        assertThat(
                "Should have all three test targets retrievable from audit history",
                count,
                equalTo(3L));

        // Query with useAuditLog=false should include only active targets
        response = queryTargetNodes(false);
        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));

        actual = mapper.readValue(response.body().asString(), TargetNodesQueryResponse.class);

        count =
                actual.data.targetNodes.stream()
                        .filter(
                                node ->
                                        node.getTarget() != null
                                                && node.getTarget().getJvmId() != null
                                                && node.getTarget()
                                                        .getJvmId()
                                                        .startsWith("jvm-multi-"))
                        .count();
        assertThat("Should have only two active targets", count, equalTo(2L));
    }

    @Test
    public void testBackwardCompatibility() throws Exception {
        createTestTarget("test-compat", "jvm-compat-123");

        Response response = queryTargetNodes(null);
        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));

        TargetNodesQueryResponse actual =
                mapper.readValue(response.body().asString(), TargetNodesQueryResponse.class);

        assertThat(actual.data.targetNodes, hasSize(greaterThanOrEqualTo(1)));

        boolean found =
                actual.data.targetNodes.stream()
                        .anyMatch(
                                node ->
                                        node.getTarget() != null
                                                && "jvm-compat-123"
                                                        .equals(node.getTarget().getJvmId()));
        assertThat("Target should be found with default behavior", found, is(true));
    }
}
