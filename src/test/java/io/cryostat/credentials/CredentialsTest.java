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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.List;

import io.cryostat.AbstractTransactionalTestBase;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestHTTPEndpoint(Credentials.class)
public class CredentialsTest extends AbstractTransactionalTestBase {

    @Test
    public void testListEmpty() {
        given().log()
                .all()
                .when()
                .get()
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("", Matchers.equalTo(List.of()));
    }

    @Test
    public void testGetNone() {
        given().log().all().when().get("1").then().log().all().assertThat().statusCode(404);
    }

    @Test
    public void testCreate() {
        given().log()
                .all()
                .contentType(ContentType.URLENC)
                .formParam("matchExpression", "true")
                .formParam("username", "user")
                .formParam("password", "pass")
                .when()
                .post()
                .then()
                .log()
                .all()
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

        given().log()
                .all()
                .when()
                .get(Integer.toString(id))
                .then()
                .log()
                .all()
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

        given().log()
                .all()
                .when()
                .get(Integer.toString(id))
                .then()
                .log()
                .all()
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
    public void testCredentialCheck() {
        // Ensure self target exists
        if (selfId < 1) {
            defineSelfCustomTarget();
        }

        given().log()
                .all()
                .contentType(ContentType.URLENC)
                .formParam("username", "user")
                .formParam("password", "pass")
                .when()
                .post("/test/{targetId}", selfId)
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body(
                        Matchers.instanceOf(String.class),
                        Matchers.matchesRegex("[\\s]*\"NA\"[\\s]*"));
    }

    @Test
    void testCreatedCredentialsDoNotExpireByDefault() {
        int id = createTestCredential();

        var credential =
                QuarkusTransaction.requiringNew()
                        .call(() -> Credential.<Credential>findById((long) id));

        assertNull(credential.expiresAt);
        assertNull(credential.lastUsedAt);
    }

    @Test
    void testPluginCredentialGetsDefaultExpiryWhenAssociated() {
        var credentialId =
                given().log()
                        .all()
                        .contentType(ContentType.URLENC)
                        .formParam(
                                "matchExpression",
                                "target.connectUrl == 'http://localhost:8081/health/liveness'")
                        .formParam("username", "user")
                        .formParam("password", "pass")
                        .when()
                        .post()
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .contentType(ContentType.JSON)
                        .extract()
                        .jsonPath()
                        .getLong("id");

        var callback =
                String.format(
                        "http://storedcredentials:%d@localhost:8081/health/liveness", credentialId);

        given().log()
                .all()
                .when()
                .body(java.util.Map.of("realm", "expiry_test_realm", "callback", callback))
                .contentType(ContentType.JSON)
                .post("http://localhost:8081/api/v4/discovery")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        var credential =
                QuarkusTransaction.requiringNew()
                        .call(() -> Credential.<Credential>findById(credentialId));

        Assertions.assertNotNull(credential.expiresAt);
        Assertions.assertTrue(
                !credential.expiresAt.isBefore(Instant.now().plusSeconds(3500))
                        && !credential.expiresAt.isAfter(Instant.now().plusSeconds(3700)));
        assertNull(credential.lastUsedAt);
    }

    private int createTestCredential() {
        return given().log()
                .all()
                .contentType(ContentType.URLENC)
                .formParam("matchExpression", "true")
                .formParam("username", "user")
                .formParam("password", "pass")
                .when()
                .post()
                .then()
                .log()
                .all()
                .extract()
                .jsonPath()
                .getInt("id");
    }
}
