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
package io.cryostat.credentials;

import static io.restassured.RestAssured.given;

import java.util.List;

import io.cryostat.AbstractTransactionalTestBase;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestHTTPEndpoint(Credentials.class)
public class CredentialsTest extends AbstractTransactionalTestBase {

    @Test
    public void testListEmpty() {
        given().when()
                .get()
                .then()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("", Matchers.equalTo(List.of()));
    }

    @Test
    public void testGetNone() {
        given().when().get("1").then().assertThat().statusCode(404);
    }

    @Test
    public void testCreate() {
        given().contentType(ContentType.URLENC)
                .formParam("matchExpression", "true")
                .formParam("username", "user")
                .formParam("password", "pass")
                .when()
                .post()
                .then()
                .assertThat()
                .statusCode(201)
                .and()
                .header(
                        "Location",
                        Matchers.matchesRegex(
                                "https?://[\\.\\w]+:[\\d]+/api/v4/credentials/[\\d]+"))
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("id", Matchers.instanceOf(Integer.class))
                .body("id", Matchers.greaterThanOrEqualTo(1))
                .body("matchExpression", Matchers.instanceOf(String.class))
                .body("matchExpression", Matchers.equalTo("true"));
    }

    @Test
    public void testGet() throws InterruptedException {
        int id = createTestCredential();

        given().when()
                .get(Integer.toString(id))
                .then()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("id", Matchers.instanceOf(Integer.class))
                .body("id", Matchers.equalTo(id))
                .body("matchExpression", Matchers.instanceOf(String.class))
                .body("matchExpression", Matchers.equalTo("true"))
                .body("targets", Matchers.instanceOf(List.class))
                .body("targets", Matchers.hasSize(0));
    }

    @Test
    public void testDelete() throws InterruptedException {
        int id = createTestCredential();

        given().when()
                .get(Integer.toString(id))
                .then()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("id", Matchers.instanceOf(Integer.class))
                .body("id", Matchers.equalTo(id))
                .body("matchExpression", Matchers.instanceOf(String.class))
                .body("matchExpression", Matchers.equalTo("true"))
                .body("targets", Matchers.instanceOf(List.class))
                .body("targets", Matchers.hasSize(0));
    }

    private int createTestCredential() {
        return given().contentType(ContentType.URLENC)
                .formParam("matchExpression", "true")
                .formParam("username", "user")
                .formParam("password", "pass")
                .when()
                .post()
                .then()
                .extract()
                .jsonPath()
                .getInt("id");
    }
}
