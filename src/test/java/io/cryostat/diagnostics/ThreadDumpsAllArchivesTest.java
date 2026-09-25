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
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.websocket.DeploymentException;
import org.hamcrest.Matchers;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class ThreadDumpsAllArchivesTest extends AbstractTransactionalTestBase {

    @Inject Logger logger;

    @BeforeEach
    void cleanup() {
        String response =
                given().when()
                        .basePath("/")
                        .get("/api/v5/diagnostics/thread-dump")
                        .then()
                        .extract()
                        .body()
                        .asString();
        if (response != null && !response.isEmpty() && !response.equals("[]")) {
            JsonArray dirs = new JsonArray(response);
            dirs.forEach(
                    dir -> {
                        JsonObject directory = (JsonObject) dir;
                        String jvmId = directory.getString("jvmId");
                        JsonArray threadDumps = directory.getJsonArray("threadDumps");
                        if (threadDumps != null) {
                            threadDumps.forEach(
                                    dump -> {
                                        JsonObject threadDump = (JsonObject) dump;
                                        String threadDumpId = threadDump.getString("threadDumpId");
                                        if (threadDumpId != null && !threadDumpId.isEmpty()) {
                                            given().when()
                                                    .basePath("/")
                                                    .pathParams(
                                                            "jvmId",
                                                            jvmId,
                                                            "threadDumpId",
                                                            threadDumpId)
                                                    .delete(
                                                            "/api/v5/targets/{jvmId}/diagnostics/thread-dump/{threadDumpId}")
                                                    .then()
                                                    .statusCode(204);
                                        }
                                    });
                        }
                    });
        }
    }

    @Test
    public void testListNone() {
        given().log()
                .all()
                .when()
                .get("/api/v5/diagnostics/thread-dump")
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
    public void testCreateListAndDelete()
            throws InterruptedException, IOException, DeploymentException, TimeoutException {
        UUID id = defineSelfCustomTarget();
        Executors.newSingleThreadScheduledExecutor()
                .schedule(
                        () -> {
                            given().log()
                                    .all()
                                    .when()
                                    .pathParam("jvmId", this.selfJvmId)
                                    .post("/api/v5/targets/{jvmId}/diagnostics/thread-dump")
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
                        3,
                        TimeUnit.SECONDS);

        String threadDumpId =
                webSocketClient
                        .expectNotification("ThreadDumpSuccess")
                        .getJsonObject("message")
                        .getJsonObject("threadDump")
                        .getString("threadDumpId");

        given().log()
                .all()
                .when()
                .get("/api/v5/diagnostics/thread-dump")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(1));

        logger.infov("Created threadDumpId: {0}", threadDumpId);
    }
}
