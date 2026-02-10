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
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import io.cryostat.AbstractTransactionalTestBase;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestHTTPEndpoint(Rules.class)
public class RulesPostFormTest extends AbstractTransactionalTestBase {

    private static final String TEST_RULE_NAME = "Test_Rule";
    private static final String TEST_RULE_EVENT_SPECIFIER = "template=Continuous,type=TARGET";
    private static final String TEST_RULE_DESCRIPTION = "AutoRulesIT automated rule";
    private static final String TEST_RULE_MATCH_EXPRESSION =
            "target.alias == 'es.andrewazor.demo.Main'";

    @AfterEach
    void cleanupRulesPostFormTest() {
        // Delete test rule if it exists (204 if exists, 404 if not)
        given().when().delete("/{name}", TEST_RULE_NAME).then().statusCode(anyOf(is(204), is(404)));
    }

    @Test
    void testAddRuleThrowsWhenFormAttributesNull() {
        given().contentType(ContentType.URLENC).when().post().then().log().all().statusCode(400);
    }

    @Test
    void testAddRuleThrowsWhenRuleNameAlreadyExists() {
        // Create the rule first time - should succeed
        given().log()
                .all()
                .contentType(ContentType.URLENC)
                .formParam("name", TEST_RULE_NAME)
                .formParam("matchExpression", TEST_RULE_MATCH_EXPRESSION)
                .formParam("description", TEST_RULE_DESCRIPTION)
                .formParam("eventSpecifier", TEST_RULE_EVENT_SPECIFIER)
                .when()
                .post()
                .then()
                .log()
                .all()
                .statusCode(201)
                .body("id", notNullValue())
                .body("name", equalTo(TEST_RULE_NAME))
                .body("description", equalTo(TEST_RULE_DESCRIPTION))
                .body("matchExpression", equalTo(TEST_RULE_MATCH_EXPRESSION))
                .body("eventSpecifier", equalTo(TEST_RULE_EVENT_SPECIFIER))
                .body("archivalPeriodSeconds", equalTo(0))
                .body("initialDelaySeconds", equalTo(0))
                .body("preservedArchives", equalTo(0))
                .body("maxAgeSeconds", equalTo(0))
                .body("maxSizeBytes", equalTo(0))
                .body("enabled", equalTo(false))
                .body("metadata.labels.size()", equalTo(0));

        // Try to create the same rule again - should fail with 409
        given().log()
                .all()
                .contentType(ContentType.URLENC)
                .formParam("name", TEST_RULE_NAME)
                .formParam("matchExpression", TEST_RULE_MATCH_EXPRESSION)
                .formParam("description", TEST_RULE_DESCRIPTION)
                .formParam("eventSpecifier", TEST_RULE_EVENT_SPECIFIER)
                .when()
                .post()
                .then()
                .log()
                .all()
                .statusCode(409);
    }

    @Test
    void testAddRuleThrowsWhenIntegerAttributesNegative() {
        given().log()
                .all()
                .contentType(ContentType.URLENC)
                .formParam("name", TEST_RULE_NAME)
                .formParam("matchExpression", TEST_RULE_MATCH_EXPRESSION)
                .formParam("description", TEST_RULE_DESCRIPTION)
                .formParam("eventSpecifier", TEST_RULE_EVENT_SPECIFIER)
                .formParam("archivalPeriodSeconds", "-60")
                .formParam("preservedArchives", "-3")
                .when()
                .post()
                .then()
                .log()
                .all()
                .statusCode(400);
    }
}
