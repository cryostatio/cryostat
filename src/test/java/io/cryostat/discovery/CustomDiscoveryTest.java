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
    public void testCreate() {
        String jvmId =
                given().log()
                        .all()
                        .contentType(ContentType.URLENC)
                        .formParam("connectUrl", "service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi")
                        .formParam("alias", "CustomDiscoveryTest")
                        .when()
                        .post("/api/v5/targets")
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(201)
                        .and()
                        .header(
                                "Location",
                                Matchers.matchesRegex(
                                        "https?://[\\.\\w]+:[\\d]+/api/v5/targets/[\\w-]+"))
                        .and()
                        .contentType(ContentType.JSON)
                        .and()
                        .body("id", Matchers.instanceOf(String.class))
                        .body("connectUrl", Matchers.instanceOf(String.class))
                        .body(
                                "connectUrl",
                                Matchers.equalTo(
                                        "service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi"))
                        .body("alias", Matchers.instanceOf(String.class))
                        .body("alias", Matchers.equalTo("CustomDiscoveryTest"))
                        .extract()
                        .jsonPath()
                        .getString("jvmId");

        given().log()
                .all()
                .when()
                .delete("/api/v5/targets/{jvmId}", jvmId)
                .then()
                .assertThat()
                .statusCode(204);
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
                .post("/api/v5/targets")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(202)
                .and()
                .header("Location", Matchers.emptyOrNullString());
    }

    @Test
    public void testCreateTargetOnlyNoCredentials() {
        String jvmId =
                given().log()
                        .all()
                        .contentType(ContentType.URLENC)
                        .formParam("connectUrl", "service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi")
                        .formParam("alias", "CustomDiscoveryTestTargetOnly")
                        .when()
                        .post("/api/v5/targets")
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(201)
                        .and()
                        .contentType(ContentType.JSON)
                        .and()
                        .body("id", Matchers.instanceOf(String.class))
                        .extract()
                        .jsonPath()
                        .getString("jvmId");

        given().log()
                .all()
                .when()
                .delete("/api/v5/targets/{jvmId}", jvmId)
                .then()
                .assertThat()
                .statusCode(204);
    }

    @Test
    public void testCreateWithCredentialsAndStoreCredentials() {
        String jvmId =
                given().log()
                        .all()
                        .contentType(ContentType.URLENC)
                        .formParam("connectUrl", "service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi")
                        .formParam("alias", "CustomDiscoveryTestWithCreds")
                        .formParam("username", "user")
                        .formParam("password", "pass")
                        .queryParam("storeCredentials", true)
                        .when()
                        .post("/api/v5/targets")
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(201)
                        .and()
                        .contentType(ContentType.JSON)
                        .and()
                        .body("id", Matchers.instanceOf(String.class))
                        .extract()
                        .jsonPath()
                        .getString("jvmId");

        given().log()
                .all()
                .when()
                .delete("/api/v5/targets/{jvmId}", jvmId)
                .then()
                .assertThat()
                .statusCode(204);
    }

    @Test
    public void testCreateDryRunNoCredentials() {
        given().log()
                .all()
                .contentType(ContentType.URLENC)
                .formParam("connectUrl", "service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi")
                .formParam("alias", "CustomDiscoveryTestDryRunNoCreds")
                .queryParam("dryrun", true)
                .when()
                .post("/api/v5/targets")
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
                .post("/api/v5/targets")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(400);
    }

    @Test
    public void testGet() throws InterruptedException {
        var jp =
                given().log()
                        .all()
                        .contentType(ContentType.URLENC)
                        .formParam("connectUrl", "service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi")
                        .formParam("alias", "CustomDiscoveryTest")
                        .when()
                        .post("/api/v5/targets")
                        .then()
                        .log()
                        .all()
                        .extract()
                        .jsonPath();
        String targetId = jp.getString("id");
        String jvmId = jp.getString("jvmId");

        given().log()
                .all()
                .when()
                .get("/api/v5/targets/{targetId}", targetId)
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .and()
                .contentType(ContentType.JSON)
                .and()
                .body("jvmId", Matchers.instanceOf(String.class))
                .body("jvmId", Matchers.equalTo(jvmId))
                .body("connectUrl", Matchers.instanceOf(String.class))
                .body(
                        "connectUrl",
                        Matchers.equalTo("service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi"))
                .body("alias", Matchers.instanceOf(String.class))
                .body("alias", Matchers.equalTo("CustomDiscoveryTest"));

        given().log()
                .all()
                .when()
                .delete("/api/v5/targets/{id}", jvmId)
                .then()
                .assertThat()
                .statusCode(204);
    }

    @Test
    public void testDelete() throws InterruptedException {
        String jvmId =
                given().log()
                        .all()
                        .contentType(ContentType.URLENC)
                        .formParam("connectUrl", "service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi")
                        .formParam("alias", "CustomDiscoveryTest")
                        .when()
                        .post("/api/v5/targets")
                        .then()
                        .log()
                        .all()
                        .extract()
                        .jsonPath()
                        .getString("jvmId");

        given().log()
                .all()
                .when()
                .delete("/api/v5/targets/{jvmId}", jvmId)
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(204);
    }
}
