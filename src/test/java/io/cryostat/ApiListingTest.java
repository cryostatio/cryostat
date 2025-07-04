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
package io.cryostat;

import static io.restassured.RestAssured.given;

import java.util.regex.Pattern;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class ApiListingTest extends AbstractTestBase {

    @Test
    void shouldDefaultToYamlResponse() {
        given().when()
                .get("/api")
                .then()
                .assertThat()
                .contentType("application/yaml")
                .statusCode(200);
    }

    @Nested
    class JsonResponses {

        ValidatableResponse resp;

        @BeforeEach
        void setup() {
            resp = given().accept(ContentType.JSON).when().get("/api").then();
        }

        @Test
        void shouldBeOk() {
            resp.statusCode(200);
        }

        @Test
        void shouldRespondWithJson() {
            resp.contentType(ContentType.JSON);
        }

        @Test
        void shouldIncludeOpenApiInfo() {
            resp.body("openapi", Matchers.matchesRegex("^\\d+\\.\\d+\\.\\d+$"));
        }

        @Test
        void shouldIncludeApplicationInfo() {
            resp.body("info.title", Matchers.equalTo("Cryostat API (test)"))
                    .body("info.description", Matchers.equalTo("Cloud-Native JDK Flight Recorder"))
                    .body("info.contact.name", Matchers.equalTo("Cryostat Community"))
                    .body("info.contact.url", Matchers.equalTo("https://cryostat.io"))
                    .body(
                            "info.contact.email",
                            Matchers.equalTo("cryostat-development@googlegroups.com"))
                    .body("info.license.name", Matchers.equalTo("Apache 2.0"))
                    .body(
                            "info.license.url",
                            Matchers.equalTo(
                                    "https://github.com/cryostatio/cryostat/blob/main/LICENSE"))
                    .body(
                            "info.version",
                            Matchers.matchesRegex(
                                    Pattern.compile(
                                            "^\\d+\\.\\d+\\.\\d+(?:-snapshot)?$",
                                            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE)))
                    .body("paths", Matchers.not(Matchers.blankOrNullString()));
        }
    }
}
