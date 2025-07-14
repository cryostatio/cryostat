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
package io.cryostat.recordings;

import static io.restassured.RestAssured.given;

import java.util.Map;

import io.cryostat.AbstractTransactionalTestBase;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@QuarkusTest
@TestHTTPEndpoint(RecordingOptions.class)
public class RecordingOptionsTest extends AbstractTransactionalTestBase {

    @Test
    void testGetStandard() {
        int targetId = defineSelfCustomTarget();
        given().when()
                .pathParams(Map.of("targetId", targetId))
                .get()
                .then()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(3))
                .body("maxAge", Matchers.equalTo(0))
                .body("maxSize", Matchers.equalTo(0))
                .body("toDisk", Matchers.equalTo(false));
    }

    @Test
    void testSetGetUnset() {
        int targetId = defineSelfCustomTarget();

        given().when()
                .pathParams(Map.of("targetId", targetId))
                .formParams(Map.of("maxAge", 1234, "maxSize", 5678, "toDisk", true))
                .patch()
                .then()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(3))
                .body("maxAge", Matchers.equalTo(1234))
                .body("maxSize", Matchers.equalTo(5678))
                .body("toDisk", Matchers.equalTo(true));

        given().when()
                .pathParams(Map.of("targetId", targetId))
                .get()
                .then()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(3))
                .body("maxAge", Matchers.equalTo(1234))
                .body("maxSize", Matchers.equalTo(5678))
                .body("toDisk", Matchers.equalTo(true));

        given().when()
                .pathParams(Map.of("targetId", targetId))
                .formParams(Map.of("maxAge", "unset", "maxSize", "unset", "toDisk", "unset"))
                .patch()
                .then()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(3))
                .body("maxAge", Matchers.equalTo(0))
                .body("maxSize", Matchers.equalTo(0))
                .body("toDisk", Matchers.equalTo(false));

        given().when()
                .pathParams(Map.of("targetId", targetId))
                .get()
                .then()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(3))
                .body("maxAge", Matchers.equalTo(0))
                .body("maxSize", Matchers.equalTo(0))
                .body("toDisk", Matchers.equalTo(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"maxAge", "maxSize", "toDisk"})
    void testSetInvalid(String key) {
        int targetId = defineSelfCustomTarget();
        given().when()
                .pathParams(Map.of("targetId", targetId))
                .formParams(Map.of(key, "invalid"))
                .patch()
                .then()
                .assertThat()
                .statusCode(400);
    }
}
