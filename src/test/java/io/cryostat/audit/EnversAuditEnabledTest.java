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
@TestProfile(EnversAuditEnabledTest.class)
@TestHTTPEndpoint(Rules.class)
public class EnversAuditEnabledTest extends EnversAuditTestBase implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.hibernate-orm.unsupported-properties.\"hibernate.envers.enabled\"",
                "true");
    }

    @Test
    public void testRuleCreateGeneratesAuditRecord() {
        JsonObject rule = createRuleJson("test-rule-create-audit");

        var response = createRuleViaApi(rule);
        Long ruleId = ((Number) response.get("id")).longValue();

        List<Object[]> auditRecords = queryAuditRecords(ruleId);

        MatcherAssert.assertThat(auditRecords, Matchers.hasSize(1));
        Object[] auditRecord = auditRecords.get(0);
        Number revtype = (Number) auditRecord[2];
        MatcherAssert.assertThat(revtype.intValue(), Matchers.equalTo(REVTYPE_ADD));
    }

    @Test
    public void testRuleUpdateGeneratesAuditRecord() {
        String ruleName = "test-rule-update-audit";
        JsonObject rule = createRuleJson(ruleName);

        var response = createRuleViaApi(rule);
        Long ruleId = ((Number) response.get("id")).longValue();

        JsonObject updateRule = createRuleJson(ruleName);
        updateRule.put("enabled", false);
        updateRuleViaApi(ruleName, updateRule);

        List<Object[]> auditRecords = queryAuditRecords(ruleId);

        MatcherAssert.assertThat(auditRecords, Matchers.hasSize(2));

        Object[] createRecord = auditRecords.get(0);
        Number createRevtype = (Number) createRecord[2];
        MatcherAssert.assertThat(createRevtype.intValue(), Matchers.equalTo(REVTYPE_ADD));

        Object[] updateRecord = auditRecords.get(1);
        Number updateRevtype = (Number) updateRecord[2];
        MatcherAssert.assertThat(updateRevtype.intValue(), Matchers.equalTo(REVTYPE_MOD));
    }

    @Test
    public void testRuleDeleteGeneratesAuditRecord() {
        String ruleName = "test-rule-delete-audit";
        JsonObject rule = createRuleJson(ruleName);

        var response = createRuleViaApi(rule);
        Long ruleId = ((Number) response.get("id")).longValue();

        deleteRuleViaApi(ruleName);

        List<Object[]> auditRecords = queryAuditRecords(ruleId);

        MatcherAssert.assertThat(auditRecords, Matchers.hasSize(2));

        Object[] createRecord = auditRecords.get(0);
        Number createRevtype = (Number) createRecord[2];
        MatcherAssert.assertThat(createRevtype.intValue(), Matchers.equalTo(REVTYPE_ADD));

        Object[] deleteRecord = auditRecords.get(1);
        Number deleteRevtype = (Number) deleteRecord[2];
        MatcherAssert.assertThat(deleteRevtype.intValue(), Matchers.equalTo(REVTYPE_DEL));
    }

    @Test
    public void testMultipleRuleOperationsGenerateCorrectAuditTrail() {
        String ruleAName = "test-rule-a-audit";
        JsonObject ruleA = createRuleJson(ruleAName);
        var responseA = createRuleViaApi(ruleA);
        Long ruleAId = ((Number) responseA.get("id")).longValue();

        String ruleBName = "test-rule-b-audit";
        JsonObject ruleB = createRuleJson(ruleBName);
        ruleB.put("enabled", false);
        var responseB = createRuleViaApi(ruleB);
        Long ruleBId = ((Number) responseB.get("id")).longValue();

        JsonObject updateRuleA = createRuleJson(ruleAName);
        updateRuleA.put("description", String.format("Updated %s description", ruleAName));
        updateRuleViaApi(ruleAName, updateRuleA);

        deleteRuleViaApi(ruleBName);

        JsonObject updateRuleA2 = createRuleJson(ruleAName);
        updateRuleA2.put("enabled", false);
        updateRuleViaApi(ruleAName, updateRuleA2);

        List<Object[]> auditRecordsA = queryAuditRecords(ruleAId);

        MatcherAssert.assertThat(auditRecordsA, Matchers.hasSize(3));
        MatcherAssert.assertThat(
                ((Number) auditRecordsA.get(0)[2]).intValue(), Matchers.equalTo(REVTYPE_ADD));
        MatcherAssert.assertThat(
                ((Number) auditRecordsA.get(1)[2]).intValue(), Matchers.equalTo(REVTYPE_MOD));
        MatcherAssert.assertThat(
                ((Number) auditRecordsA.get(2)[2]).intValue(), Matchers.equalTo(REVTYPE_MOD));

        List<Object[]> auditRecordsB = queryAuditRecords(ruleBId);

        MatcherAssert.assertThat(auditRecordsB, Matchers.hasSize(2));
        MatcherAssert.assertThat(
                ((Number) auditRecordsB.get(0)[2]).intValue(), Matchers.equalTo(REVTYPE_ADD));
        MatcherAssert.assertThat(
                ((Number) auditRecordsB.get(1)[2]).intValue(), Matchers.equalTo(REVTYPE_DEL));

        List<Object[]> allAuditRecords = queryAuditRecordsForMultipleRules(ruleAId, ruleBId);
        MatcherAssert.assertThat(allAuditRecords, Matchers.hasSize(5));
    }
}
