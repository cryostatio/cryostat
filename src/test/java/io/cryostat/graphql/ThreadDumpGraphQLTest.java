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

package io.cryostat.graphql;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class ThreadDumpGraphQLTest extends AbstractGraphQLTestBase {

    private static final String LIST_THREAD_DUMPS_QUERY =
            """
            query AllTargetsThreadDumps {
                      targetNodes {
                        target {
                          agent
                          id
                          connectUrl
                          alias
                          jvmId
                          threadDumps {
                            data {
                              jvmId
                              downloadUrl
                              threadDumpId
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

    private static final String GRAPHQL_THREAD_DUMP_CLEANUP_QUERY =
            """
            query ThreadDumpCleanup($targetIds: [ BigInteger! ]) {
                targetNodes(filter: { targetIds: $targetIds }) {
                    descendantTargets {
                        target {
                            threadDumps {
                              data {
                                doDelete {
                                  threadDumpId
                                    }
                                }
                            }
                        }
                    }
                }
            }
            """;

    @AfterEach
    void cleanupThreadDumps() {
        var variables = Map.<String, Object>of("targetIds", List.of(selfId));
        given().basePath("/")
                .body(Map.of("query", GRAPHQL_THREAD_DUMP_CLEANUP_QUERY, "variables", variables))
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
    void testQuery() throws Exception {
        // Create a thread dump
        String jobId =
                given().log()
                        .all()
                        .when()
                        .pathParam("targetId", selfId)
                        .post("/api/beta/diagnostics/targets/{targetId}/threaddump")
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .asString();

        Assertions.assertNotNull(jobId, "Job ID should be returned");

        webSocketClient.expectNotification("ThreadDumpSuccess", Duration.ofSeconds(15));

        // Test the GraphQL Query
        var variables = Map.<String, Object>of("targetIds", List.of(selfId));
        Response resp =
                given().basePath("/")
                        .body(Map.of("query", LIST_THREAD_DUMPS_QUERY, "variables", variables))
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

        assertThat(resp.getStatusCode(), equalTo(200));
        JsonObject retrievedResponse = new JsonObject(resp.asString());
        assertThat(retrievedResponse, notNullValue());
        assertThat(retrievedResponse.getMap().size(), not(0));
    }

    @Test
    void testCaptureThreadDumpMutation() throws Exception {

        JsonObject query = new JsonObject();
        query.put(
                "query",
                "mutation { createThreadDump( nodes:{annotations: [\"REALM = Custom"
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

        webSocketClient.expectNotification("ThreadDumpSuccess", Duration.ofSeconds(15));

        assertThat(response.getStatusCode(), equalTo(200));
        assertThat(response.getBody(), notNullValue());
    }

    @Test
    void testDeleteMutation() throws Exception {
        JsonObject createQuery = new JsonObject();
        createQuery.put(
                "query",
                "mutation { createThreadDump(nodes:{annotations:[\"REALM = Custom"
                        + " Targets\"]})}");

        // Create a Thread Dump
        Response response =
                given().contentType(ContentType.JSON)
                        .body(createQuery.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        webSocketClient.expectNotification("ThreadDumpSuccess", Duration.ofSeconds(15));

        assertThat(response.getStatusCode(), equalTo(200));
        JsonObject retrieved = new JsonObject(response.body().asString());
        assertThat(retrieved, notNullValue());

        // Retrieve archived thread dump name via REST API
        Response archivedListResponse =
                given().when()
                        .get("/api/beta/diagnostics/targets/" + selfId + "/threaddump")
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();
        JsonArray retrievedThreadDumps = new JsonArray(archivedListResponse.body().asString());
        JsonObject retrievedThreadDump = retrievedThreadDumps.getJsonObject(0);
        String retrievedThreadDumpId = retrievedThreadDump.getString("threadDumpId");

        JsonObject deleteQuery = new JsonObject();
        deleteQuery.put(
                "query",
                String.format(
                        "mutation { deleteThreadDump (nodes: { annotations: [\"REALM = Custom"
                                + " Targets\"]}, filter: { name: \"%s\"}) { name downloadUrl } }",
                        retrievedThreadDumpId));

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
