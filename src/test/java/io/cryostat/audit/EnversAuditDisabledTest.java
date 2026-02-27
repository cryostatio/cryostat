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
package io.cryostat.audit;

import java.util.List;
import java.util.Map;

import io.cryostat.rules.Rules;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(EnversAuditDisabledTest.class)
@TestHTTPEndpoint(Rules.class)
public class EnversAuditDisabledTest extends EnversAuditTestBase implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        // Envers disabled by default - explicitly set to false for clarity
        return Map.of(
                "quarkus.hibernate-orm.unsupported-properties.\"hibernate.envers.enabled\"",
                "false");
    }

    @Test
    public void testRuleCreateDoesNotGenerateAuditRecord() {
        JsonObject rule = createRuleJson("test-rule-create-no-audit");

        var response = createRuleViaApi(rule);
        Long ruleId = ((Number) response.get("id")).longValue();

        List<Object[]> auditRecords = queryAuditRecords(ruleId);
        MatcherAssert.assertThat(auditRecords, Matchers.empty());
    }

    @Test
    public void testRuleUpdateDoesNotGenerateAuditRecord() {
        String ruleName = "test-rule-update-no-audit";
        JsonObject rule = createRuleJson(ruleName);

        var response = createRuleViaApi(rule);
        Long ruleId = ((Number) response.get("id")).longValue();

        JsonObject updateRule = createRuleJson(ruleName);
        updateRule.put("enabled", false);
        updateRuleViaApi(ruleName, updateRule);

        List<Object[]> auditRecords = queryAuditRecords(ruleId);
        MatcherAssert.assertThat(auditRecords, Matchers.empty());
    }

    @Test
    public void testRuleDeleteDoesNotGenerateAuditRecord() {
        String ruleName = "test-rule-delete-no-audit";
        JsonObject rule = createRuleJson(ruleName);

        var response = createRuleViaApi(rule);
        Long ruleId = ((Number) response.get("id")).longValue();

        deleteRuleViaApi(ruleName);

        List<Object[]> auditRecords = queryAuditRecords(ruleId);
        MatcherAssert.assertThat(auditRecords, Matchers.empty());
    }

    @Test
    public void testMultipleRuleOperationsGenerateNoAuditRecords() {
        String ruleAName = "test-rule-a-no-audit";
        JsonObject ruleA = createRuleJson(ruleAName);
        var responseA = createRuleViaApi(ruleA);
        Long ruleAId = ((Number) responseA.get("id")).longValue();

        String ruleBName = "test-rule-b-no-audit";
        JsonObject ruleB = createRuleJson(ruleBName);
        ruleB.put("enabled", false);
        var responseB = createRuleViaApi(ruleB);
        Long ruleBId = ((Number) responseB.get("id")).longValue();

        JsonObject updateRuleA = createRuleJson(ruleAName);
        updateRuleViaApi(ruleAName, updateRuleA);

        deleteRuleViaApi(ruleBName);

        JsonObject updateRuleA2 = createRuleJson(ruleAName);
        updateRuleA2.put("enabled", false);
        updateRuleViaApi(ruleAName, updateRuleA2);

        List<Object[]> auditRecordsA = queryAuditRecords(ruleAId);
        MatcherAssert.assertThat(auditRecordsA, Matchers.empty());

        List<Object[]> auditRecordsB = queryAuditRecords(ruleBId);
        MatcherAssert.assertThat(auditRecordsB, Matchers.empty());

        List<Object[]> allAuditRecords = queryAllAuditRecords();
        MatcherAssert.assertThat(allAuditRecords, Matchers.empty());
    }
}
