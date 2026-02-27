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
package itest.agent;

import static io.restassured.RestAssured.given;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import io.cryostat.resources.AgentApplicationResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;
import itest.resources.S3StorageITResource;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusIntegrationTest
@QuarkusTestResource(value = AgentApplicationResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = S3StorageITResource.class, restrictToAnnotatedClass = true)
public class AgentHeapDumpIT extends AgentTestBase {

    private static final String GRAPHQL_HEAP_DUMP_CLEANUP_QUERY =
            """
            query HeapDumpCleanup($targetIds: [ BigInteger! ]) {
              targetNodes(filter: { targetIds: $targetIds }) {
                descendantTargets {
                  target {
                    heapDumps {
                      data {
                        doDelete {
                          heapDumpId
                        }
                      }
                    }
                  }
                }
              }
            }
            """;

    @AfterEach
    void cleanupHeapDumps() {
        var variables = Map.<String, Object>of("targetIds", List.of(target.id()));
        given().basePath("/")
                .body(Map.of("query", GRAPHQL_HEAP_DUMP_CLEANUP_QUERY, "variables", variables))
                .contentType(ContentType.JSON)
                .log()
                .all()
                .when()
                .post("/api/v4/graphql")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);
    }

    @Test
    void testListNoHeapDumps() {
        MatcherAssert.assertThat(
                (List<?>)
                        given().log()
                                .all()
                                .pathParams("targetId", target.id())
                                .when()
                                .get("/api/beta/diagnostics/targets/{targetId}/heapdump")
                                .then()
                                .log()
                                .all()
                                .and()
                                .assertThat()
                                .contentType(ContentType.JSON)
                                .statusCode(200)
                                .extract()
                                .body()
                                .as(List.class),
                Matchers.equalTo(List.of()));
    }

    @Test
    void testCreateListAndDeleteHeapDump()
            throws InterruptedException, ExecutionException, TimeoutException {
        long targetId = target.id();

        String jobId =
                given().log()
                        .all()
                        .pathParams("targetId", targetId)
                        .when()
                        .post("/api/beta/diagnostics/targets/{targetId}/heapdump")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.TEXT)
                        .body(Matchers.notNullValue())
                        .and()
                        .extract()
                        .body()
                        .asString();

        webSocketClient.expectNotification(
                "HeapDumpSuccess",
                o -> jobId.equals(o.getJsonObject("message").getString("jobId")));

        JsonObject uploadedNotification =
                webSocketClient.expectNotification(
                        "HeapDumpUploaded",
                        o -> target.jvmId().equals(o.getJsonObject("message").getString("jvmId")));

        JsonObject message = uploadedNotification.getJsonObject("message");
        String notificationJvmId = message.getString("jvmId");
        JsonObject heapDumpObj = message.getJsonObject("heapDump");
        String notificationHeapDumpId = heapDumpObj.getString("heapDumpId");

        MatcherAssert.assertThat(notificationJvmId, Matchers.equalTo(target.jvmId()));
        MatcherAssert.assertThat(notificationHeapDumpId, Matchers.notNullValue());

        List<Map<String, Object>> heapDumps =
                given().log()
                        .all()
                        .pathParams("targetId", targetId)
                        .when()
                        .get("/api/beta/diagnostics/targets/{targetId}/heapdump")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .contentType(ContentType.JSON)
                        .statusCode(200)
                        .body("$.size()", Matchers.equalTo(1))
                        .and()
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        String heapDumpId = (String) heapDumps.get(0).get("heapDumpId");
        MatcherAssert.assertThat(heapDumpId, Matchers.equalTo(notificationHeapDumpId));
        MatcherAssert.assertThat(heapDumps.get(0).get("jvmId"), Matchers.equalTo(target.jvmId()));
        MatcherAssert.assertThat(
                ((Number) heapDumps.get(0).get("size")).longValue(), Matchers.greaterThan(0L));

        given().log()
                .all()
                .pathParams("targetId", targetId, "heapDumpId", heapDumpId)
                .when()
                .delete("/api/beta/diagnostics/targets/{targetId}/heapdump/{heapDumpId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);

        testListNoHeapDumps();
    }

    @Test
    void testCreateMultipleHeapDumps()
            throws InterruptedException, ExecutionException, TimeoutException {
        long targetId = target.id();

        String jobId1 =
                given().log()
                        .all()
                        .pathParams("targetId", targetId)
                        .when()
                        .post("/api/beta/diagnostics/targets/{targetId}/heapdump")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .and()
                        .extract()
                        .body()
                        .asString();

        webSocketClient.expectNotification(
                "HeapDumpSuccess",
                o -> jobId1.equals(o.getJsonObject("message").getString("jobId")));

        webSocketClient.expectNotification(
                "HeapDumpUploaded",
                o -> target.jvmId().equals(o.getJsonObject("message").getString("jvmId")));

        String jobId2 =
                given().log()
                        .all()
                        .pathParams("targetId", targetId)
                        .when()
                        .post("/api/beta/diagnostics/targets/{targetId}/heapdump")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .and()
                        .extract()
                        .body()
                        .asString();

        webSocketClient.expectNotification(
                "HeapDumpSuccess",
                o -> jobId2.equals(o.getJsonObject("message").getString("jobId")));

        webSocketClient.expectNotification(
                "HeapDumpUploaded",
                o -> target.jvmId().equals(o.getJsonObject("message").getString("jvmId")));

        given().log()
                .all()
                .pathParams("targetId", targetId)
                .when()
                .get("/api/beta/diagnostics/targets/{targetId}/heapdump")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("$.size()", Matchers.equalTo(2));
    }

    @Test
    void testDeleteNonExistentHeapDump() {
        given().log()
                .all()
                .pathParams("targetId", target.id(), "heapDumpId", "nonexistent")
                .when()
                .delete("/api/beta/diagnostics/targets/{targetId}/heapdump/{heapDumpId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @Test
    void testListHeapDumpsForNonExistentTarget() {
        given().log()
                .all()
                .pathParams("targetId", Integer.MAX_VALUE)
                .when()
                .get("/api/beta/diagnostics/targets/{targetId}/heapdump")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @Test
    void testCreateHeapDumpForNonExistentTarget() {
        given().log()
                .all()
                .pathParams("targetId", Integer.MAX_VALUE)
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/heapdump")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @Test
    void testListAllHeapDumps() throws InterruptedException, ExecutionException, TimeoutException {
        long targetId = target.id();

        String jobId =
                given().log()
                        .all()
                        .pathParams("targetId", targetId)
                        .when()
                        .post("/api/beta/diagnostics/targets/{targetId}/heapdump")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .and()
                        .extract()
                        .body()
                        .asString();

        webSocketClient.expectNotification(
                "HeapDumpSuccess",
                o -> jobId.equals(o.getJsonObject("message").getString("jobId")));

        webSocketClient.expectNotification(
                "HeapDumpUploaded",
                o -> target.jvmId().equals(o.getJsonObject("message").getString("jvmId")));

        given().log()
                .all()
                .when()
                .get("/api/beta/diagnostics/fs/heapdumps")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("$.size()", Matchers.greaterThanOrEqualTo(1))
                .body(
                        "find { it.jvmId == '" + target.jvmId() + "' }.heapDumps.size()",
                        Matchers.greaterThanOrEqualTo(1));
    }

    protected void cleanupHeapDumpsForTarget(List<Integer> ids) {
        var variables = Map.<String, Object>of("targetIds", ids);
        given().basePath("/")
                .body(Map.of("query", GRAPHQL_HEAP_DUMP_CLEANUP_QUERY, "variables", variables))
                .contentType(ContentType.JSON)
                .log()
                .all()
                .when()
                .post("/api/v4/graphql")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);
    }
}
