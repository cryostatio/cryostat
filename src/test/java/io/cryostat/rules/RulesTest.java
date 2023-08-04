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

import static io.cryostat.TestUtils.givenBasicAuth;
import static io.restassured.RestAssured.when;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.http.ContentType;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.MediaType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@QuarkusTest
@TestHTTPEndpoint(Rules.class)
public class RulesTest {

    @InjectSpy(convertScopes = true)
    EventBus bus;

    JsonObject rule;

    static String RULE_NAME = "my_rule";

    @BeforeEach
    public void setup() {
        rule = new JsonObject();
        rule.put("name", RULE_NAME);
        rule.put("matchExpression", "my_match_expression");
        rule.put("eventSpecifier", "my_event_specifier");
        rule.put("enabled", true);
    }

    @AfterEach
    @Transactional
    public void afterEach() {
        Rule.deleteAll();
    }

    @Test
    public void testUnauthorized() {
        when().get().then().statusCode(401);
    }

    @Test
    public void testListEmpty() {
        givenBasicAuth()
                .get()
                .then()
                .statusCode(200)
                .body(
                        "meta.type", is(MediaType.APPLICATION_JSON),
                        "meta.status", is("OK"),
                        "data.result", Matchers.empty());
    }

    @Test
    @Transactional
    public void testList() {
        givenBasicAuth()
                .body(rule.toString())
                .contentType(ContentType.JSON)
                .post()
                .then()
                .statusCode(201);

        givenBasicAuth()
                .get()
                .then()
                .statusCode(200)
                .body(
                        "meta.type", is(MediaType.APPLICATION_JSON),
                        "meta.status", is("OK"),
                        "data.result", Matchers.hasSize(1),
                        "data.result[0].name", is(RULE_NAME),
                        "data.result[0].matchExpression", is("my_match_expression"),
                        "data.result[0].eventSpecifier", is("my_event_specifier"));
    }

    @Test
    public void testUpdate() {
        var copy = rule.copy();
        copy.put("enabled", false);
        givenBasicAuth()
                .body(copy.toString())
                .contentType(ContentType.JSON)
                .post()
                .then()
                .statusCode(201)
                .body(
                        "meta.type", is(MediaType.APPLICATION_JSON),
                        "meta.status", is("Created"),
                        "data.result", is(RULE_NAME));

        givenBasicAuth()
                .get()
                .then()
                .statusCode(200)
                .body(
                        "meta.type", is(MediaType.APPLICATION_JSON),
                        "meta.status", is("OK"),
                        "data.result", Matchers.hasSize(1),
                        "data.result[0].name", is(RULE_NAME),
                        "data.result[0].enabled", is(false));

        givenBasicAuth()
                .body(new JsonObject().put("enabled", true).toString())
                .contentType(ContentType.JSON)
                .patch(RULE_NAME)
                .then()
                .statusCode(200)
                .body(
                        "meta.type", is(MediaType.APPLICATION_JSON),
                        "meta.status", is("OK"),
                        "data.result.name", is(RULE_NAME),
                        "data.result.enabled", is(true));
    }

    @Test
    public void testUpdateWithClean() {
        givenBasicAuth()
                .body(rule.toString())
                .contentType(ContentType.JSON)
                .post()
                .then()
                .statusCode(201);

        givenBasicAuth()
                .queryParam("clean", true)
                .body(new JsonObject().put("enabled", false).toString())
                .contentType(ContentType.JSON)
                .patch(RULE_NAME)
                .then()
                .statusCode(200)
                .body(
                        "meta.type", is(MediaType.APPLICATION_JSON),
                        "meta.status", is("OK"),
                        "data.result.name", is(RULE_NAME),
                        "data.result.enabled", is(false));

        Mockito.verify(bus, Mockito.times(1))
                .send(Mockito.eq(Rule.RULE_ADDRESS + "?clean"), Mockito.any(Rule.class));
    }

    @Test
    public void testCreateThrowsWhenRuleNameExists() {
        // Created: rule_name
        givenBasicAuth()
                .body(rule.toString())
                .contentType(ContentType.JSON)
                .post()
                .then()
                .statusCode(201);

        // Try to create again
        var conflictRule = new JsonObject();
        conflictRule.put("name", RULE_NAME);
        conflictRule.put("matchExpression", "some_other_match_expression");
        conflictRule.put("eventSpecifier", "some_other_event_specifier");

        givenBasicAuth()
                .body(rule.toString())
                .contentType(ContentType.JSON)
                .post()
                .then()
                .statusCode(409);
    }

    @Test
    public void testCreateThrowsWhenBodyNull() {
        givenBasicAuth().contentType(ContentType.JSON).post().then().statusCode(400);
    }

    @Test
    public void testCreateThrowsWhenMandatoryFieldsUnspecified() {
        var badRule = new JsonObject();
        badRule.put("name", RULE_NAME);
        badRule.put("matchExpression", "some_other_match_expression");
        // MISSING: badRule.put("eventSpecifier", "some_other_event_specifier");
        givenBasicAuth()
                .body(badRule.toString())
                .contentType(ContentType.JSON)
                .post()
                .then()
                .statusCode(400);
    }

    @Test
    public void testDeleteEmpty() {
        givenBasicAuth().delete(RULE_NAME).then().statusCode(404);
    }

    @Test
    public void testDelete() {
        givenBasicAuth()
                .body(rule.toString())
                .contentType(ContentType.JSON)
                .post()
                .then()
                .statusCode(201);

        givenBasicAuth()
                .delete(RULE_NAME)
                .then()
                .statusCode(200)
                .body(
                        "meta.type", is(MediaType.APPLICATION_JSON),
                        "meta.status", is("OK"),
                        "data.result", nullValue());
    }

    @Test
    public void testDeleteWithClean() {
        givenBasicAuth()
                .body(rule.toString())
                .contentType(ContentType.JSON)
                .post()
                .then()
                .statusCode(201);

        givenBasicAuth()
                .queryParam("clean", true)
                .delete(RULE_NAME)
                .then()
                .statusCode(200)
                .body(
                        "meta.type", is(MediaType.APPLICATION_JSON),
                        "meta.status", is("OK"),
                        "data.result", nullValue());

        Mockito.verify(bus, Mockito.times(1))
                .send(Mockito.eq(Rule.RULE_ADDRESS + "?clean"), Mockito.any(Rule.class));
    }
}
