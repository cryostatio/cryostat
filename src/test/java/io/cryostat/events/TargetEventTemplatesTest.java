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
package io.cryostat.events;

import static io.restassured.RestAssured.given;

import io.cryostat.AbstractTransactionalTestBase;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class TargetEventTemplatesTest extends AbstractTransactionalTestBase {

    @Test
    void testList() {
        int id = defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .get("/api/v4/targets/{id}/event_templates", id)
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", Matchers.equalTo(3))
                .body("[0].name", Matchers.equalTo("ALL"))
                .body("[0].type", Matchers.equalTo("TARGET"))
                .body("[0].provider", Matchers.equalTo("Cryostat"))
                .body("[1].name", Matchers.equalTo("Continuous"))
                .body("[1].type", Matchers.equalTo("TARGET"))
                .body("[1].provider", Matchers.equalTo("Oracle"))
                .body("[2].name", Matchers.equalTo("Profiling"))
                .body("[2].type", Matchers.equalTo("TARGET"))
                .body("[2].provider", Matchers.equalTo("Oracle"));
    }

    @Test
    void testGetInvalid() {
        int id = defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .get(
                        "/api/v4/targets/{id}/event_templates/{templateType}/{templateName}",
                        id,
                        "TARGET",
                        "ALL")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void testGetNotFound() {
        int id = defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .get(
                        "/api/v4/targets/{id}/event_templates/{templateType}/{templateName}",
                        id,
                        "CUSTOM",
                        "None")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @Test
    void testGet() {
        int id = defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .get(
                        "/api/v4/targets/{id}/event_templates/{templateType}/{templateName}",
                        id,
                        "TARGET",
                        "Profiling")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200)
                .contentType(ContentType.XML)
                .body(Matchers.not(Matchers.blankOrNullString()));
    }
}
