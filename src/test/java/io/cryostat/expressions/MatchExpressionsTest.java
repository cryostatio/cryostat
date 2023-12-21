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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.transaction.Transactional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestHTTPEndpoint(MatchExpressions.class)
public class MatchExpressionsTest {

    static final Map<String, String> ALL_MATCHING_EXPRESSION = Map.of("matchExpression", "true");

    @AfterEach
    @Transactional
    public void afterEach() {
        MatchExpression.deleteAll();
    }

    @Test
    public void testPostWithoutTargets() {
        var expectation = new HashMap<>();
        expectation.put("id", null);
        expectation.put("expression", "true");
        expectation.put("targets", List.of());
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
                .body("meta.type", Matchers.equalTo("application/json"))
                .body("meta.status", Matchers.equalTo("OK"))
                .body("data.result", Matchers.equalTo(expectation));
    }
}
