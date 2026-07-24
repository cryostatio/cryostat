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
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.cryostat.AbstractTestBase;
import io.cryostat.resources.AgentApplicationResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonObject;
import itest.resources.S3StorageITResource;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

@QuarkusIntegrationTest
@QuarkusTestResource(value = AgentApplicationResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = S3StorageITResource.class, restrictToAnnotatedClass = true)
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
@Disabled(
        "Runtime java.lang.IllegalStateException: Unable to determine the status of the running"
                + " proces")
public class AgentHeapDumpGraphQLIT extends AgentTestBase {

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

    private static final String LIST_HEAP_DUMPS_QUERY =
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
                        .body(Map.of("query", LIST_HEAP_DUMPS_QUERY, "variables", variables))
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
    @Disabled("GraphQL createHeapDump mutation does not defer bus.publish past transaction commit")
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
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
    @Disabled("GraphQL createHeapDump mutation does not defer bus.publish past transaction commit")
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
    void testDeleteMutation() throws Exception {
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
}
