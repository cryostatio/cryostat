/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat;

import static io.cryostat.TestUtils.givenBasicAuth;
import static io.restassured.RestAssured.when;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.MediaType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@QuarkusTest
@TestHTTPEndpoint(Rules.class)
public class HealthTest {

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
