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
package io.cryostat.expressions;

import static io.restassured.RestAssured.given;

import java.util.List;
import java.util.Map;

import io.cryostat.AbstractTransactionalTestBase;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@QuarkusTest
@TestHTTPEndpoint(MatchExpressions.class)
public class MatchExpressionsTest extends AbstractTransactionalTestBase {

    static final Map<String, String> ALL_MATCHING_EXPRESSION = Map.of("matchExpression", "true");

    @Test
    public void testListNone() {
        given().log()
                .all()
                .when()
                .get()
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .body("size()", Matchers.equalTo(0));
    }

    @Test
    public void testGetNone() {
        given().log().all().when().get("1").then().log().all().assertThat().statusCode(404);
    }

    @Test
    public void testPostWithoutTargets() {
        given().contentType(ContentType.JSON)
                .body(ALL_MATCHING_EXPRESSION)
                .when()
                .post()
                .then()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("expression", Matchers.equalTo("true"))
                .body("targets.size()", Matchers.is(0));
    }

    @ParameterizedTest
    @CsvSource(value = {"true, 200, true", "false, 200, false", "this is garbage, 400, false"})
    public void testExpressionTest(String expr, int status, boolean expectTargets) {
        int id = defineSelfCustomTarget();

        var body = new JsonObject();
        body.put("matchExpression", expr);
        body.put("targetIds", List.of(id));
        var result =
                given().log()
                        .all()
                        .contentType(ContentType.JSON)
                        .body(body.encodePrettily())
                        .when()
                        .post()
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(status);

        var ok = 200 <= status && status < 300;
        if (ok) {
            result.contentType(ContentType.JSON)
                    .body("id", Matchers.nullValue())
                    .body("expression", Matchers.equalTo(expr));
        }

        if (ok && expectTargets) {
            result.body("targets.size()", Matchers.equalTo(1))
                    .body("targets[0].alias", Matchers.equalTo(SELFTEST_ALIAS))
                    .body("targets[0].connectUrl", Matchers.equalTo(SELF_JMX_URL));
        } else if (ok) {
            result.body("targets.size()", Matchers.equalTo(0));
        }
    }
}
