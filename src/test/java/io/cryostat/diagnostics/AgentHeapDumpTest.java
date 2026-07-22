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
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import io.cryostat.AbstractTestBase;
import io.cryostat.AgentTestBase;
import io.cryostat.resources.AgentApplicationResource;
import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = AgentApplicationResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class AgentHeapDumpTest extends AgentTestBase {

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

    private static final String GRAPHQL_LIST_HEAP_DUMPS_QUERY =
            """
            query AllTargetsHeapDumps {
                      targetNodes {
                        target {
                          agent
                          id
                          connectUrl
                          alias
                          jvmId
                          heapDumps {
                            data {
                              jvmId
                              downloadUrl
                              heapDumpId
                              lastModified
                              size
                              metadata {
                                labels {
                                  key
                                  value
                                }
                              }
                            }
                            aggregate {
                              count
                            }
                          }
                        }
                      }
                    }
            """;

    @AfterEach
    void cleanupHeapDumps() {
        var variables = Map.<String, Object>of("targetIds", List.of(target.id()));
        Response response =
                given().basePath("/")
                        .body(
                                Map.of(
                                        "query",
                                        GRAPHQL_HEAP_DUMP_CLEANUP_QUERY,
                                        "variables",
                                        variables))
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
                        .statusCode(200)
                        .extract()
                        .response();
        AbstractTestBase.assertNoGraphQLErrors(response);
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
                Duration.ofMinutes(2),
                o -> jobId.equals(o.getJsonObject("message").getString("jobId")));

        JsonObject uploadedNotification =
                webSocketClient.expectNotification(
                        "HeapDumpUploaded",
                        Duration.ofMinutes(5),
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
    void testCreateListAndAnalyzeHeapDump()
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
                Duration.ofMinutes(2),
                o -> jobId.equals(o.getJsonObject("message").getString("jobId")));

        JsonObject uploadedNotification =
                webSocketClient.expectNotification(
                        "HeapDumpUploaded",
                        Duration.ofMinutes(5),
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
        String jvmId = (String) heapDumps.get(0).get("jvmId");
        MatcherAssert.assertThat(heapDumpId, Matchers.equalTo(notificationHeapDumpId));
        MatcherAssert.assertThat(heapDumps.get(0).get("jvmId"), Matchers.equalTo(target.jvmId()));
        MatcherAssert.assertThat(
                ((Number) heapDumps.get(0).get("size")).longValue(), Matchers.greaterThan(0L));

        var analysisJobId =
                given().log()
                        .all()
                        .pathParams("jvmId", jvmId, "heapDumpId", heapDumpId)
                        .when()
                        .post("/api/beta/diagnostics/targets/{jvmId}/heapdump/{heapDumpId}/analyze")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(202)
                        .extract()
                        .body()
                        .asString();

        webSocketClient.expectNotification(
                "HeapDumpAnalysisSuccess",
                Duration.ofMinutes(5),
                o -> analysisJobId.equals(o.getJsonObject("message").getString("jobId")));

        Response r =
                given().log()
                        .all()
                        .pathParams("jvmId", jvmId, "heapDumpId", heapDumpId)
                        .when()
                        .post("/api/beta/diagnostics/targets/{jvmId}/heapdump/{heapDumpId}/analyze")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .response();

        assertThat(r.body().print().length(), not(0));
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
                Duration.ofMinutes(2),
                o -> jobId1.equals(o.getJsonObject("message").getString("jobId")));

        webSocketClient.expectNotification(
                "HeapDumpUploaded",
                Duration.ofMinutes(5),
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
                Duration.ofMinutes(2),
                o -> jobId2.equals(o.getJsonObject("message").getString("jobId")));

        webSocketClient.expectNotification(
                "HeapDumpUploaded",
                Duration.ofMinutes(5),
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
    void testAnalyzeNonExistentHeapDump() {
        given().log()
                .all()
                .pathParams("jvmId", target.jvmId(), "heapDumpId", "nonexistent")
                .when()
                .post("/api/beta/diagnostics/targets/{jvmId}/heapdump/{heapDumpId}/analyze")
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
                Duration.ofMinutes(2),
                o -> jobId.equals(o.getJsonObject("message").getString("jobId")));

        webSocketClient.expectNotification(
                "HeapDumpUploaded",
                Duration.ofMinutes(5),
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

    @Test
    @Disabled
    void testGraphQLListHeapDumps() throws Exception {
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
                Duration.ofMinutes(2),
                o -> jobId.equals(o.getJsonObject("message").getString("jobId")));

        JsonObject uploadedNotification =
                webSocketClient.expectNotification(
                        "HeapDumpUploaded",
                        Duration.ofMinutes(5),
                        o -> target.jvmId().equals(o.getJsonObject("message").getString("jvmId")));

        JsonObject message = uploadedNotification.getJsonObject("message");
        String notificationJvmId = message.getString("jvmId");
        JsonObject heapDumpObj = message.getJsonObject("heapDump");
        String notificationHeapDumpId = heapDumpObj.getString("heapDumpId");

        assertThat(notificationJvmId, equalTo(target.jvmId()));
        assertThat(notificationHeapDumpId, notNullValue());

        var variables = Map.<String, Object>of("targetIds", List.of(targetId));
        Response resp =
                given().basePath("/")
                        .body(
                                Map.of(
                                        "query",
                                        GRAPHQL_LIST_HEAP_DUMPS_QUERY,
                                        "variables",
                                        variables))
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
                        .statusCode(200)
                        .extract()
                        .response();

        AbstractTestBase.assertNoGraphQLErrors(resp);
        JsonObject retrievedResponse = new JsonObject(resp.asString());
        assertThat(retrievedResponse, notNullValue());
        assertThat(retrievedResponse.getMap().size(), not(0));
    }

    @Test
    @Disabled
    void testCaptureHeapDumpMutation() throws Exception {
        JsonObject query = new JsonObject();
        query.put(
                "query",
                "mutation { createHeapDump( nodes:{annotations: [\"REALM = Custom"
                        + " Targets\"]})}");

        Response response =
                given().contentType(ContentType.JSON)
                        .body(query.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        webSocketClient.expectNotification("HeapDumpSuccess", Duration.ofMinutes(2));

        webSocketClient.expectNotification(
                "HeapDumpUploaded",
                Duration.ofMinutes(5),
                o -> target.jvmId().equals(o.getJsonObject("message").getString("jvmId")));

        assertThat(response.getStatusCode(), equalTo(200));
        assertThat(response.getBody(), notNullValue());
    }

    @Test
    @Disabled
    void testGraphQLDeleteMutation() throws Exception {
        JsonObject createQuery = new JsonObject();
        createQuery.put(
                "query",
                "mutation { createHeapDump(nodes:{annotations:[\"REALM = Custom"
                        + " Targets\"]})}");

        Response response =
                given().contentType(ContentType.JSON)
                        .body(createQuery.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        webSocketClient.expectNotification("HeapDumpSuccess", Duration.ofMinutes(2));

        assertThat(response.getStatusCode(), equalTo(200));

        JsonObject uploadedNotification =
                webSocketClient.expectNotification(
                        "HeapDumpUploaded",
                        Duration.ofMinutes(5),
                        o -> target.jvmId().equals(o.getJsonObject("message").getString("jvmId")));

        JsonObject message = uploadedNotification.getJsonObject("message");
        String notificationHeapDumpId = message.getJsonObject("heapDump").getString("heapDumpId");

        JsonObject deleteQuery = new JsonObject();
        deleteQuery.put(
                "query",
                String.format(
                        "mutation { deleteHeapDump (nodes: { annotations: [\"REALM = Custom"
                                + " Targets\"]}, filter: { name: \"%s\"}) { name downloadUrl } }",
                        notificationHeapDumpId));

        Response deleteResponse =
                given().contentType(ContentType.JSON)
                        .body(deleteQuery.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        assertThat(deleteResponse.getStatusCode(), equalTo(200));
    }

    protected void cleanupHeapDumpsForTarget(List<Integer> ids) {
        var variables = Map.<String, Object>of("targetIds", ids);
        io.restassured.response.Response response =
                given().basePath("/")
                        .body(
                                Map.of(
                                        "query",
                                        GRAPHQL_HEAP_DUMP_CLEANUP_QUERY,
                                        "variables",
                                        variables))
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
                        .statusCode(200)
                        .extract()
                        .response();
        AbstractTestBase.assertNoGraphQLErrors(response);
    }
}
