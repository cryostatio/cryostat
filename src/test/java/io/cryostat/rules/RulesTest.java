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
package io.cryostat.rules;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.*;

import io.cryostat.BaseTest;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.transaction.Transactional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@QuarkusTest
@TestHTTPEndpoint(Rules.class)
public class RulesTest extends BaseTest {

    private static final String EXPR_1 = "true";
    private static final String EXPR_2 = "false";

    @InjectSpy(convertScopes = true)
    EventBus bus;

    JsonObject rule;

    {
        rule = new JsonObject();
        rule.put("name", RULE_NAME);
        rule.put("matchExpression", EXPR_1);
        rule.put("eventSpecifier", "my_event_specifier");
        rule.put("enabled", true);
    }

    static String RULE_NAME = "my_rule";

    @Test
    public void testListEmpty() {
        given().log()
                .all()
                .get()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", is(0));
    }

    @Test
    @Transactional
    public void testList() {
        given().log()
                .all()
                .body(rule.toString())
                .contentType(ContentType.JSON)
                .post()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(201);

        given().log()
                .all()
                .get()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body(
                        "size()", is(1),
                        "[0].name", is(RULE_NAME),
                        "[0].matchExpression", is(EXPR_1),
                        "[0].eventSpecifier", is("my_event_specifier"),
                        "[0].archivalPeriodSeconds", is(0),
                        "[0].initialDelaySeconds", is(0),
                        "[0].preservedArchives", is(0),
                        "[0].maxAgeSeconds", is(0),
                        "[0].maxSizeBytes", is(0),
                        "[0].enabled", is(true));
    }

    @Test
    public void testUpdate() {
        var copy = rule.copy();
        copy.put("enabled", false);
        given().log()
                .all()
                .body(copy.toString())
                .contentType(ContentType.JSON)
                .post()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(201)
                .body(
                        "id", notNullValue(),
                        "name", is(RULE_NAME));

        given().log()
                .all()
                .get()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body(
                        "size()", is(1),
                        "[0].name", is(RULE_NAME),
                        "[0].enabled", is(false));

        given().log()
                .all()
                .body(new JsonObject().put("enabled", true).toString())
                .contentType(ContentType.JSON)
                .patch(RULE_NAME)
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body(
                        "name", is(RULE_NAME),
                        "enabled", is(true));
    }

    @Test
    public void testUpdateWithClean() {
        given().log()
                .all()
                .body(rule.toString())
                .contentType(ContentType.JSON)
                .post()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(201);

        given().log()
                .all()
                .queryParam("clean", true)
                .body(new JsonObject().put("enabled", false).toString())
                .contentType(ContentType.JSON)
                .patch(RULE_NAME)
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body(
                        "name", is(RULE_NAME),
                        "enabled", is(false));

        Mockito.verify(bus, Mockito.times(1))
                .send(Mockito.eq(Rule.RULE_ADDRESS + "?clean"), Mockito.any(Rule.class));
    }

    @Test
    public void testCreateThrowsWhenRuleNameExists() {
        // Created: rule_name
        given().log()
                .all()
                .body(rule.toString())
                .contentType(ContentType.JSON)
                .post()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(201);

        // Try to create again
        var conflictRule = new JsonObject();
        conflictRule.put("name", RULE_NAME);
        conflictRule.put("matchExpression", EXPR_2);
        conflictRule.put("eventSpecifier", "some_other_event_specifier");

        given().body(rule.toString())
                .contentType(ContentType.JSON)
                .post()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(409);
    }

    @Test
    public void testCreateThrowsWhenBodyNull() {
        given().log()
                .all()
                .contentType(ContentType.JSON)
                .post()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    public void testCreateThrowsWhenMandatoryFieldsUnspecified() {
        var badRule = new JsonObject();
        badRule.put("name", RULE_NAME);
        badRule.put("matchExpression", EXPR_2);
        // MISSING: badRule.put("eventSpecifier", "some_other_event_specifier");
        given().log()
                .all()
                .body(badRule.toString())
                .contentType(ContentType.JSON)
                .post()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    public void testDeleteEmpty() {
        given().log().all().delete(RULE_NAME).then().log().all().and().assertThat().statusCode(404);
    }

    @Test
    public void testDelete() {
        given().log()
                .all()
                .body(rule.toString())
                .contentType(ContentType.JSON)
                .post()
                .then()
                .log()
                .all()
                .statusCode(201);

        given().log()
                .all()
                .delete(RULE_NAME)
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204)
                .body(Matchers.emptyOrNullString());
    }

    @Test
    public void testDeleteWithClean() {
        given().body(rule.toString()).contentType(ContentType.JSON).post().then().statusCode(201);

        given().log()
                .all()
                .queryParam("clean", true)
                .delete(RULE_NAME)
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204)
                .body(Matchers.emptyOrNullString());

        Mockito.verify(bus, Mockito.times(1))
                .send(Mockito.eq(Rule.RULE_ADDRESS + "?clean"), Mockito.any(Rule.class));
    }
}
