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
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.websocket.DeploymentException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class ThreadDumpsTest extends AbstractTransactionalTestBase {

    @Test
    public void testListNone() {
        defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .pathParam("jvmId", selfJvmId)
                .get("/api/v5/targets/{jvmId}/diagnostics/threaddump")
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
    public void testCreate()
            throws InterruptedException, IOException, DeploymentException, TimeoutException {
        defineSelfCustomTarget();
        final String[] jobId = new String[1];
        final String[] threadDumpId = new String[1];
        try {
            Executors.newSingleThreadScheduledExecutor()
                    .schedule(
                            () -> {
                                var body =
                                        given().log()
                                                .all()
                                                .when()
                                                .pathParam("jvmId", selfJvmId)
                                                .post(
                                                        "/api/v5/targets/{jvmId}/diagnostics/threaddump")
                                                .then()
                                                .log()
                                                .all()
                                                .and()
                                                .assertThat()
                                                .contentType(ContentType.TEXT)
                                                .statusCode(200)
                                                .and()
                                                .extract()
                                                .body()
                                                .asString();
                                jobId[0] = body.strip();
                            },
                            1,
                            TimeUnit.SECONDS);
            var notification =
                    webSocketClient.expectNotification(
                            "ThreadDumpSuccess",
                            json ->
                                    Objects.equals(
                                            json.getJsonObject("message").getString("jobId"),
                                            jobId[0]));
            threadDumpId[0] =
                    notification
                            .getJsonObject("message")
                            .getJsonObject("threadDump")
                            .getString("threadDumpId")
                            .strip();
        } finally {
            given().log()
                    .all()
                    .when()
                    .pathParam("jvmId", selfJvmId)
                    .pathParam("threadDumpId", threadDumpId[0])
                    .delete("/api/v5/targets/{jvmId}/diagnostics/threaddump/{threadDumpId}")
                    .then()
                    .log()
                    .all()
                    .and()
                    .assertThat()
                    .statusCode(204);
        }
    }

    @Test
    public void testCreateAndList()
            throws IOException, DeploymentException, InterruptedException, TimeoutException {
        // Check that creating a thread dump works as expected
        defineSelfCustomTarget();
        final String[] jobId = new String[1];
        final String[] threadDumpId = new String[1];

        Executors.newSingleThreadScheduledExecutor()
                .schedule(
                        () -> {
                            var body =
                                    given().log()
                                            .all()
                                            .when()
                                            .pathParam("jvmId", selfJvmId)
                                            .post("/api/v5/targets/{jvmId}/diagnostics/threaddump")
                                            .then()
                                            .log()
                                            .all()
                                            .and()
                                            .assertThat()
                                            .contentType(ContentType.TEXT)
                                            .statusCode(200)
                                            .extract()
                                            .body()
                                            .asString();
                            jobId[0] = body.strip();
                        },
                        1,
                        TimeUnit.SECONDS);
        var notification =
                webSocketClient.expectNotification(
                        "ThreadDumpSuccess",
                        json ->
                                Objects.equals(
                                        json.getJsonObject("message").getString("jobId"),
                                        jobId[0]));
        threadDumpId[0] =
                notification
                        .getJsonObject("message")
                        .getJsonObject("threadDump")
                        .getString("threadDumpId")
                        .strip();

        // Check that the listing is non empty
        given().log()
                .all()
                .when()
                .pathParam("jvmId", selfJvmId)
                .get("/api/v5/targets/{jvmId}/diagnostics/threaddump")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(1));

        given().log()
                .all()
                .when()
                .pathParam("jvmId", selfJvmId)
                .pathParam("threadDumpId", threadDumpId[0])
                .delete("/api/v5/targets/{jvmId}/diagnostics/threaddump/{threadDumpId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);

        // Check that the listing is empty
        given().log()
                .all()
                .when()
                .pathParam("jvmId", selfJvmId)
                .get("/api/v5/targets/{jvmId}/diagnostics/threaddump")
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
    public void testCreateAndDelete()
            throws InterruptedException, IOException, DeploymentException, TimeoutException {
        defineSelfCustomTarget();
        Executors.newSingleThreadScheduledExecutor()
                .schedule(
                        () -> {
                            given().log()
                                    .all()
                                    .when()
                                    .pathParam("jvmId", selfJvmId)
                                    .post("/api/v5/targets/{jvmId}/diagnostics/threaddump")
                                    .then()
                                    .log()
                                    .all()
                                    .and()
                                    .assertThat()
                                    .contentType(ContentType.TEXT)
                                    .statusCode(200)
                                    .extract()
                                    .body()
                                    .asString();
                        },
                        1,
                        TimeUnit.SECONDS);

        webSocketClient.expectNotification("ThreadDumpSuccess");

        var listResponseJson =
                given().log()
                        .all()
                        .when()
                        .pathParam("jvmId", selfJvmId)
                        .get("/api/v5/targets/{jvmId}/diagnostics/threaddump")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .contentType(ContentType.JSON)
                        .statusCode(200)
                        .body("size()", Matchers.equalTo(1))
                        .extract()
                        .jsonPath();

        var threadDumpId = listResponseJson.getString("[0].threadDumpId");

        Executors.newSingleThreadScheduledExecutor()
                .schedule(
                        () -> {
                            given().log()
                                    .all()
                                    .when()
                                    .pathParam("jvmId", selfJvmId)
                                    .pathParam("threadDumpId", threadDumpId)
                                    .delete(
                                            "/api/v5/targets/{jvmId}/diagnostics/threaddump/{threadDumpId}")
                                    .then()
                                    .log()
                                    .all()
                                    .and()
                                    .assertThat()
                                    .statusCode(204);
                        },
                        1,
                        TimeUnit.SECONDS);

        webSocketClient.expectNotification(
                "ThreadDumpDeleted",
                json ->
                        Objects.equals(
                                json.getJsonObject("message")
                                        .getJsonObject("threadDump")
                                        .getString("threadDumpId"),
                                threadDumpId));
    }

    @Test
    public void testAnalysis() throws InterruptedException, TimeoutException {
        defineSelfCustomTarget();

        given().log()
                .all()
                .when()
                .pathParam("jvmId", selfJvmId)
                .post("/api/v5/targets/{jvmId}/diagnostics/threaddump")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.TEXT)
                .statusCode(200)
                .extract()
                .body()
                .asString();

        webSocketClient.expectNotification("ThreadDumpSuccess");

        var listResponseJson =
                given().log()
                        .all()
                        .when()
                        .pathParam("jvmId", selfJvmId)
                        .get("/api/v5/targets/{jvmId}/diagnostics/threaddump")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .contentType(ContentType.JSON)
                        .statusCode(200)
                        .body("size()", Matchers.equalTo(1))
                        .extract()
                        .jsonPath();

        var threadDumpId = listResponseJson.getString("[0].threadDumpId");

        given().log()
                .all()
                .when()
                .pathParam("jvmId", selfJvmId)
                .pathParam("threadDumpId", threadDumpId)
                .post("/api/v5/targets/{jvmId}/diagnostics/threaddump/{threadDumpId}/analyze")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        given().log()
                .all()
                .when()
                .pathParam("jvmId", selfJvmId)
                .pathParam("threadDumpId", threadDumpId)
                .delete("/api/v5/targets/{jvmId}/diagnostics/threaddump/{threadDumpId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);
    }

    @Test
    public void testListInvalid() {
        given().log()
                .all()
                .when()
                .pathParam("jvmId", "nonexistent")
                .get("/api/v5/targets/{jvmId}/diagnostics/threaddump")
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
    public void testDeleteInvalid() {
        defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .pathParam("jvmId", selfJvmId)
                .pathParam("threadDumpId", String.valueOf(Long.MAX_VALUE))
                .delete("/api/v5/targets/{jvmId}/diagnostics/threaddump/{threadDumpId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @Test
    public void testDownloadInvalid() {
        given().log()
                .all()
                .when()
                .get("/api/v5/diagnostics/threaddump/download/abcd1234")
                .then()
                .assertThat()
                .statusCode(400);
    }

    @Test
    public void testDownloadNotFound() {
        given().log()
                .all()
                .when()
                .get("/api/v5/download/Zm9vL2Jhcg==")
                .then()
                .assertThat()
                .statusCode(404);
    }
}
