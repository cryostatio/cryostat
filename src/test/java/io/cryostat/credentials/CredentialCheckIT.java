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

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import itest.bases.StandardSelfTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

@QuarkusIntegrationTest
@TestHTTPEndpoint(Credentials.class)
public class CredentialCheckIT extends StandardSelfTest {

    @Test
    void testSelf() {
        given().contentType(ContentType.URLENC)
                .formParam("username", "user")
                .formParam("password", "pass")
                .when()
                .post("/test/{targetId}", getSelfReferenceTargetId())
                .then()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body(
                        Matchers.instanceOf(String.class),
                        Matchers.matchesRegex("[\\s]*\"NA\"[\\s]*"));
    }
}
