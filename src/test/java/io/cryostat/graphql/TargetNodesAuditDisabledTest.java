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
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(TargetNodesAuditDisabledTest.class)
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class TargetNodesAuditDisabledTest extends TargetNodesAuditTestBase
        implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        // Envers disabled by default - explicitly set to false for clarity
        return Map.of(
                "quarkus.hibernate-orm.unsupported-properties.\"hibernate.envers.enabled\"",
                "false");
    }

    @Test
    public void testTargetNodesWithoutAuditLogReturnsActiveOnly() throws Exception {
        createTestTarget("test-no-audit-active", "jvm-no-audit-123");

        // Query without useAuditLog parameter (default behavior)
        Response response = queryTargetNodes(null);
        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));

        TargetNodesQueryResponse actual =
                mapper.readValue(response.body().asString(), TargetNodesQueryResponse.class);

        // Should include the active target plus the default selftest target
        assertThat(actual.data.targetNodes, hasSize(greaterThanOrEqualTo(1)));

        // Verify our test target is in the results
        boolean found =
                actual.data.targetNodes.stream()
                        .anyMatch(
                                node ->
                                        node.getTarget() != null
                                                && "jvm-no-audit-123"
                                                        .equals(node.getTarget().getJvmId()));
        assertThat("Active target should be found", found, is(true));

        // Verify no audit records were created (audit disabled)
        List<Object[]> auditRecords = queryTargetAuditRecords("jvm-no-audit-123");
        assertThat("No audit records should exist when audit is disabled", auditRecords, empty());
    }

    @Test
    public void testTargetNodesWithAuditLogTrueReturnsEmptyWhenAuditDisabled() throws Exception {
        // Create a test target
        createTestTarget("test-audit-disabled", "jvm-audit-disabled-123");

        // Verify no audit records exist
        List<Object[]> auditRecords = queryTargetAuditRecords("jvm-audit-disabled-123");
        assertThat("No audit records should exist when audit is disabled", auditRecords, empty());

        // Query with useAuditLog=true should return empty results (graceful degradation)
        Response response = queryTargetNodes(true);
        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));

        TargetNodesQueryResponse actual =
                mapper.readValue(response.body().asString(), TargetNodesQueryResponse.class);

        // When audit is disabled, useAuditLog=true returns empty list (audit service unavailable)
        // This is expected behavior - the queryAuditLogTargets() method catches
        // IllegalStateException and returns empty list
        boolean foundTestTarget =
                actual.data.targetNodes.stream()
                        .anyMatch(
                                node ->
                                        node.getTarget() != null
                                                && "jvm-audit-disabled-123"
                                                        .equals(node.getTarget().getJvmId()));

        // The test target should NOT be found when querying audit log with audit disabled
        assertThat(
                "Test target should not be in audit results when audit is disabled",
                foundTestTarget,
                is(false));
    }

    @Test
    public void testTargetNodesWithAuditLogFalseReturnsActiveTargets() throws Exception {
        createTestTarget("test-audit-false-disabled", "jvm-false-disabled-123");

        // Query with useAuditLog=false (explicit)
        Response response = queryTargetNodes(false);
        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));

        TargetNodesQueryResponse actual =
                mapper.readValue(response.body().asString(), TargetNodesQueryResponse.class);

        // Should include the active target
        boolean found =
                actual.data.targetNodes.stream()
                        .anyMatch(
                                node ->
                                        node.getTarget() != null
                                                && "jvm-false-disabled-123"
                                                        .equals(node.getTarget().getJvmId()));
        assertThat("Active target should be found with useAuditLog=false", found, is(true));

        // Verify no audit records were created
        List<Object[]> auditRecords = queryTargetAuditRecords("jvm-false-disabled-123");
        assertThat("No audit records should exist when audit is disabled", auditRecords, empty());
    }

    @Test
    public void testDeletedTargetNotReturnedWhenAuditDisabled() throws Exception {
        Target target = createTestTarget("test-deleted-no-audit", "jvm-deleted-no-audit-123");

        deleteTarget(target);

        // Verify no audit records exist
        List<Object[]> auditRecords = queryTargetAuditRecords("jvm-deleted-no-audit-123");
        assertThat("No audit records should exist when audit is disabled", auditRecords, empty());

        // Query with useAuditLog=false should NOT include deleted target
        Response response = queryTargetNodes(false);
        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));

        TargetNodesQueryResponse actual =
                mapper.readValue(response.body().asString(), TargetNodesQueryResponse.class);

        boolean found =
                actual.data.targetNodes.stream()
                        .anyMatch(
                                node ->
                                        node.getTarget() != null
                                                && "jvm-deleted-no-audit-123"
                                                        .equals(node.getTarget().getJvmId()));
        assertThat("Deleted target should NOT be in active results", found, is(false));

        // Query with useAuditLog=true should also NOT include deleted target (audit disabled)
        response = queryTargetNodes(true);
        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));

        actual = mapper.readValue(response.body().asString(), TargetNodesQueryResponse.class);

        found =
                actual.data.targetNodes.stream()
                        .anyMatch(
                                node ->
                                        node.getTarget() != null
                                                && "jvm-deleted-no-audit-123"
                                                        .equals(node.getTarget().getJvmId()));
        assertThat(
                "Deleted target should NOT be in audit results when audit is disabled",
                found,
                is(false));
    }

    @Test
    public void testBackwardCompatibilityWithAuditDisabled() throws Exception {
        createTestTarget("test-compat-disabled", "jvm-compat-disabled-123");

        // Query without any audit log parameter (original behavior)
        Response response = queryTargetNodes(null);
        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));

        TargetNodesQueryResponse actual =
                mapper.readValue(response.body().asString(), TargetNodesQueryResponse.class);

        // Should work exactly as before - return active targets only
        assertThat(actual.data.targetNodes, hasSize(greaterThanOrEqualTo(1)));

        boolean found =
                actual.data.targetNodes.stream()
                        .anyMatch(
                                node ->
                                        node.getTarget() != null
                                                && "jvm-compat-disabled-123"
                                                        .equals(node.getTarget().getJvmId()));
        assertThat("Target should be found with default behavior", found, is(true));

        // Verify no audit records were created
        List<Object[]> auditRecords = queryTargetAuditRecords("jvm-compat-disabled-123");
        assertThat("No audit records should exist when audit is disabled", auditRecords, empty());
    }

    @Test
    public void testMultipleTargetsWithAuditDisabled() throws Exception {
        createTestTarget("test-multi-disabled-1", "jvm-multi-disabled-1");
        createTestTarget("test-multi-disabled-2", "jvm-multi-disabled-2");

        Response response = queryTargetNodes(false);
        assertThat(response.statusCode(), allOf(greaterThanOrEqualTo(200), lessThan(300)));

        TargetNodesQueryResponse actual =
                mapper.readValue(response.body().asString(), TargetNodesQueryResponse.class);

        // Should include both active targets
        long count =
                actual.data.targetNodes.stream()
                        .filter(
                                node ->
                                        node.getTarget() != null
                                                && node.getTarget().getJvmId() != null
                                                && node.getTarget()
                                                        .getJvmId()
                                                        .startsWith("jvm-multi-disabled-"))
                        .count();
        assertThat("Should have both active targets", count, equalTo(2L));

        // Verify no audit records were created
        List<Object[]> allAuditRecords = queryAllTargetAuditRecords();
        assertThat(
                "No audit records should exist when audit is disabled", allAuditRecords, empty());
    }
}
