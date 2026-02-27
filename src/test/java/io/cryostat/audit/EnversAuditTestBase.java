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

import static io.restassured.RestAssured.given;

import java.util.List;
import java.util.Map;

import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;

public abstract class EnversAuditTestBase {

    protected static final int REVTYPE_ADD = 0;
    protected static final int REVTYPE_MOD = 1;
    protected static final int REVTYPE_DEL = 2;

    @Inject protected EntityManager entityManager;

    @AfterEach
    void cleanup() {
        given().get().then().extract().body().jsonPath().getList("$", Map.class).stream()
                .map(m -> (String) m.get("name"))
                .forEach(name -> given().delete("/" + name));
    }

    protected JsonObject createRuleJson(String name) {
        JsonObject rule = new JsonObject();
        rule.put("name", name);
        rule.put("description", String.format("%s description", name));
        rule.put("matchExpression", "false");
        rule.put("eventSpecifier", "template=Continuous");
        rule.put("enabled", true);
        return rule;
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> createRuleViaApi(JsonObject ruleJson) {
        return given().body(ruleJson.toString())
                .contentType(ContentType.JSON)
                .post()
                .then()
                .statusCode(201)
                .extract()
                .body()
                .as(Map.class);
    }

    protected void updateRuleViaApi(String ruleName, JsonObject updateJson) {
        given().body(updateJson.toString())
                .contentType(ContentType.JSON)
                .patch("/" + ruleName)
                .then()
                .statusCode(200);
    }

    protected void deleteRuleViaApi(String ruleName) {
        given().delete("/" + ruleName).then().statusCode(204);
    }

    @SuppressWarnings("unchecked")
    protected List<Object[]> queryAuditRecords(Long ruleId) {
        return entityManager
                .createNativeQuery(
                        "SELECT id, REV, REVTYPE FROM Rule_AUD WHERE id = :id ORDER BY REV")
                .setParameter("id", ruleId)
                .getResultList();
    }

    @SuppressWarnings("unchecked")
    protected List<Object[]> queryAuditRecordsForMultipleRules(Long... ruleIds) {
        StringBuilder query =
                new StringBuilder("SELECT id, REV, REVTYPE FROM Rule_AUD WHERE id IN (");
        for (int i = 0; i < ruleIds.length; i++) {
            query.append(":id").append(i);
            if (i < ruleIds.length - 1) {
                query.append(", ");
            }
        }
        query.append(") ORDER BY REV");

        var nativeQuery = entityManager.createNativeQuery(query.toString());
        for (int i = 0; i < ruleIds.length; i++) {
            nativeQuery.setParameter("id" + i, ruleIds[i]);
        }
        return nativeQuery.getResultList();
    }

    @SuppressWarnings("unchecked")
    protected List<Object[]> queryAllAuditRecords() {
        return entityManager
                .createNativeQuery("SELECT id, REV, REVTYPE FROM Rule_AUD ORDER BY REV")
                .getResultList();
    }
}
