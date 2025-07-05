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
package io.cryostat.diagnostics;

import static io.restassured.RestAssured.given;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.diagnostic.Diagnostics;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.websocket.DeploymentException;
import org.hamcrest.Matchers;
import org.junit.Test;

@QuarkusTest
@TestHTTPEndpoint(Diagnostics.class)
public class ThreadDumpsTest extends AbstractTransactionalTestBase {

    @Test
    public void testListNone() {
        int id = defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .pathParams(Map.of("targetId", id))
                .get("targets/{targetId}/threaddump")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(0));
    }

    @Test
    public void testCreate()
            throws InterruptedException, IOException, DeploymentException, TimeoutException {
        int id = defineSelfCustomTarget();
        String jobId =
                given().log()
                        .all()
                        .when()
                        .pathParams(Map.of("targetId", id))
                        .post("targets/{targetId}/threaddump")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .contentType(ContentType.JSON)
                        .statusCode(200)
                        .body("size()", Matchers.equalTo(0))
                        .extract()
                        .body()
                        .asString();

        expectWebSocketNotification(
                "ThreadDumpSuccess",
                o -> jobId.equals(o.getJsonObject("message").getString("jobId")));
    }

    @Test
    public void testCreateAndDelete()
            throws InterruptedException, IOException, DeploymentException, TimeoutException {
        int id = defineSelfCustomTarget();
        String jobId =
                given().log()
                        .all()
                        .when()
                        .pathParams(Map.of("targetId", id))
                        .post("targets/{targetId}/threaddump")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .contentType(ContentType.JSON)
                        .statusCode(200)
                        .body("size()", Matchers.equalTo(0))
                        .extract()
                        .body()
                        .asString();

        expectWebSocketNotification(
                "ThreadDumpSuccess",
                o -> jobId.equals(o.getJsonObject("message").getString("jobId")));

        String threadDumpId =
                given().log()
                        .all()
                        .when()
                        .pathParams(Map.of("targetId", id))
                        .get("targets/{targetId}/threaddump")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .contentType(ContentType.JSON)
                        .statusCode(200)
                        .body("size()", Matchers.greaterThan(0))
                        .extract()
                        .asString()
                        .replace("[", "")
                        .replace("]", "");

        given().log()
                .all()
                .when()
                .pathParams(Map.of("targetId", id, "threadDumpId", threadDumpId))
                .delete("targets/{targetId}/threaddump/{threadDumpId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);
    }

    @Test
    public void testListInvalid() {
        given().log()
                .all()
                .when()
                .pathParams(Map.of("targetId", -1))
                .get("targets/{targetId}/threaddump")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(400);
    }

    @Test
    void testDeleteInvalid() {
        int id = defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .pathParams(Map.of("targetId", id, "threadDumpId", "foo"))
                .delete("targets/{targetId}/threaddump/{threadDumpId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void testDownloadInvalid() {
        given().log()
                .all()
                .when()
                .get("/threaddump/download/abcd1234")
                .then()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void testDownloadNotFound() {
        given().log()
                .all()
                .when()
                .get("/api/v4/download/Zm9vL2Jhcg==")
                .then()
                .assertThat()
                .statusCode(404);
    }
}
