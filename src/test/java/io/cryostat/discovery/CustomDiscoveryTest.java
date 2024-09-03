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
package io.cryostat.discovery;

import static io.restassured.RestAssured.given;

import java.util.List;

import io.cryostat.AbstractTransactionalTestBase;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestHTTPEndpoint(CustomDiscovery.class)
public class CustomDiscoveryTest extends AbstractTransactionalTestBase {

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
        given().log().all().when().get("/1").then().log().all().assertThat().statusCode(404);
    }

    @Test
    public void testCreate() {
        int id =
                given().log()
                        .all()
                        .contentType(ContentType.URLENC)
                        .formParam("connectUrl", "service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi")
                        .formParam("alias", "CustomDiscoveryTest")
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
                                        "https?://[\\.\\w]+:[\\d]+/api/v4/targets/[\\d]+"))
                        .and()
                        .contentType(ContentType.JSON)
                        .and()
                        .body("id", Matchers.instanceOf(Integer.class))
                        .body("id", Matchers.greaterThanOrEqualTo(1))
                        .body("connectUrl", Matchers.instanceOf(String.class))
                        .body(
                                "connectUrl",
                                Matchers.equalTo(
                                        "service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi"))
                        .body("alias", Matchers.instanceOf(String.class))
                        .body("alias", Matchers.equalTo("CustomDiscoveryTest"))
                        .extract()
                        .jsonPath()
                        .getInt("id");

        given().log().all().when().delete("/{id}", id).then().assertThat().statusCode(204);
    }

    @Test
    public void testCreateDryRun() {
        given().log()
                .all()
                .contentType(ContentType.URLENC)
                .formParam("connectUrl", "service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi")
                .formParam("alias", "CustomDiscoveryTest")
                .queryParam("dryrun", true)
                .when()
                .post()
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(202)
                .and()
                .header("Location", Matchers.emptyOrNullString());
    }

    @Test
    public void testCreateInvalid() {
        given().log()
                .all()
                .contentType(ContentType.URLENC)
                .formParam("connectUrl", "service:jmx:rmi:///jndi/rmi://invalid-host:9999/jmxrmi")
                .formParam("alias", "CustomDiscoveryTest")
                .when()
                .post()
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(500);
    }

    @Test
    public void testGet() throws InterruptedException {
        int id = createTestTarget();

        given().log()
                .all()
                .when()
                .get("/{id}", id)
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
                .body("connectUrl", Matchers.instanceOf(String.class))
                .body(
                        "connectUrl",
                        Matchers.equalTo("service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi"))
                .body("alias", Matchers.instanceOf(String.class))
                .body("alias", Matchers.equalTo("CustomDiscoveryTest"));

        given().log().all().when().delete("/{id}", id).then().assertThat().statusCode(204);
    }

    @Test
    public void testDelete() throws InterruptedException {
        int id = createTestTarget();

        given().log()
                .all()
                .when()
                .delete("/{id}", id)
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(204);
    }

    private int createTestTarget() {
        return given().log()
                .all()
                .contentType(ContentType.URLENC)
                .formParam("connectUrl", "service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi")
                .formParam("alias", "CustomDiscoveryTest")
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
