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
package io.cryostat.targets;

import static io.restassured.RestAssured.given;

import io.cryostat.AbstractTransactionalTestBase;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestHTTPEndpoint(Targets.class)
public class TargetsTest extends AbstractTransactionalTestBase {

    @Test
    void testList() {
        given().when()
                .get("/api/v4/targets")
                .then()
                .assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", Matchers.equalTo(0));
    }

    @Test
    public void testGetNone() {
        given().when()
                .pathParams("id", Integer.MAX_VALUE)
                .get("/api/v4/targets/{id}")
                .then()
                .assertThat()
                .statusCode(404);
    }

    @Test
    void testCreateAndList() {
        defineSelfCustomTarget();
        given().when()
                .get("/api/v4/targets")
                .then()
                .assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", Matchers.equalTo(1));
    }

    @Test
    void testCreateAndGet() {
        int id = defineSelfCustomTarget();
        given().when()
                .pathParam("id", id)
                .get("/api/v4/targets/{id}")
                .then()
                .assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("id", Matchers.greaterThanOrEqualTo(1))
                .body("connectUrl", Matchers.equalTo(SELF_JMX_URL))
                .body("alias", Matchers.equalTo(SELFTEST_ALIAS));
    }
}
