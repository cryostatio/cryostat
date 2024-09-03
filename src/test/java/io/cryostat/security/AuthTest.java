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
package io.cryostat.security;

import static io.restassured.RestAssured.given;

import io.cryostat.AbstractTransactionalTestBase;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@QuarkusTest
@TestHTTPEndpoint(Auth.class)
public class AuthTest extends AbstractTransactionalTestBase {

    @ParameterizedTest
    @ValueSource(strings = {"user", "foo", "hello there"})
    void testLoginWithForwardedHeader(String user) {
        given().log()
                .all()
                .when()
                .header("X-Forwarded-User", user)
                .post("/api/v4/auth")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("username", Matchers.equalTo(user));
    }

    @Test
    void testLogoutRedirect() {
        given().redirects()
                .follow(false)
                .log()
                .all()
                .when()
                .post("/api/v4/logout")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(308)
                .header("Location", "http://localhost:8081/oauth2/sign_out");
    }
}
