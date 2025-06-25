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
import io.cryostat.resources.S3StorageObjectMetadataResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestHTTPEndpoint(EventTemplates.class)
@QuarkusTestResource(value = S3StorageObjectMetadataResource.class, restrictToAnnotatedClass = true)
public class EventTemplatesObjectMetaTest extends AbstractTransactionalTestBase {

    @Test
    void testListNone() {
        given().log()
                .all()
                .when()
                .get()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", Matchers.equalTo(1))
                .body("[0].name", Matchers.equalTo("ALL"))
                .body("[0].type", Matchers.equalTo("TARGET"))
                .body("[0].provider", Matchers.equalTo("Cryostat"));
    }

    @Test
    void testListTargets() {
        given().log()
                .all()
                .when()
                .get("/TARGET")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void testListNonsense() {
        given().log()
                .all()
                .when()
                .get("/NONSENSE")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void testListCustoms() {
        given().log()
                .all()
                .when()
                .get("/CUSTOM")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", Matchers.equalTo(0));
    }

    @Test
    void testListPresets() {
        given().log()
                .all()
                .when()
                .get("/PRESET")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", Matchers.equalTo(0));
    }

    @Test
    void testDeleteInvalid() {
        given().log()
                .all()
                .when()
                .delete("none")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @Test
    void testDeleteHardcoded() {
        given().log()
                .all()
                .when()
                .delete("ALL")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void testCreateInvalid() {
        given().contentType(ContentType.MULTIPART)
                .multiPart("template", "INVALID")
                .log()
                .all()
                .when()
                .post()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void testCreateEmptyAndDelete() {
        var name =
                given().contentType(ContentType.MULTIPART)
                        .multiPart(
                                "template",
"""
<?xml version="1.0" encoding="UTF-8"?>
<configuration version="2.0" label="Empty" description="Empty Template" provider="EventTemplatesTest">
</configuration>
""")
                        .log()
                        .all()
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
                        .getString("name");

        given().log()
                .all()
                .when()
                .get()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200)
                .body("size()", Matchers.equalTo(2))
                .body("[0].name", Matchers.equalTo("ALL"))
                .body("[0].type", Matchers.equalTo("TARGET"))
                .body("[0].provider", Matchers.equalTo("Cryostat"))
                .body("[1].name", Matchers.equalTo("Empty"))
                .body("[1].type", Matchers.equalTo("CUSTOM"))
                .body("[1].provider", Matchers.equalTo("EventTemplatesTest"));

        given().log().all().when().delete(name);
    }

    @Test
    void testCreateListAndDelete() {
        var name =
                given().contentType(ContentType.MULTIPART)
                        .multiPart(
                                "template",
"""
<?xml version="1.0" encoding="UTF-8"?>
<configuration version="2.0" label="Empty" description="Empty Template" provider="EventTemplatesTest">
 <event name="io.cryostat.targets.TargetConnectionManager.TargetConnectionOpened">
  <setting name="enabled">
  true
  </setting>
  <setting name="threshold">
  0 ms
  </setting>
 </event>
</configuration>
""")
                        .log()
                        .all()
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
                        .getString("name");

        given().log().all().when().delete(name);
    }
}
