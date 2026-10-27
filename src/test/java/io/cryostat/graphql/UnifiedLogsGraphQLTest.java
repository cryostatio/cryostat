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

import io.cryostat.AgentTestBase;
import io.cryostat.resources.AgentApplicationResource;
import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = AgentApplicationResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class UnifiedLogsGraphQLTest extends AgentTestBase {

    private static final String LIST_UNIFIED_LOGS_QUERY =
            """
            query AllTargetsUnifiedLogs {
                      targetNodes {
                        target {
                          agent
                          id
                          connectUrl
                          alias
                          jvmId
                          unifiedLogs {
                            data {
                              jvmId
                              downloadUrl
                              unifiedLogId
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
                              size
                            }
                          }
                        }
                      }
                    }
            """;

    private static final String GRAPHQL_UNIFIED_LOG_CLEANUP_QUERY =
            """
            query UnifiedLogCleanup($targetIds: [ BigInteger! ]) {
                targetNodes(filter: { targetIds: $targetIds }) {
                    descendantTargets {
                        target {
                            unifiedLogs {
                              data {
                                doDelete {
                                  unifiedLogId
                                    }
                                }
                            }
                        }
                    }
                }
            }
            """;

    @AfterEach
    void cleanupUnifiedLogs() {
        if (target == null) {
            return;
        }
        var variables = Map.<String, Object>of("targetIds", List.of(target.id()));
        Response cleanupResponse =
                given().basePath("/")
                        .body(
                                Map.of(
                                        "query",
                                        GRAPHQL_UNIFIED_LOG_CLEANUP_QUERY,
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
        assertNoGraphQLErrors(cleanupResponse);
    }

    @Test
    void testQuery() throws Exception {
        // Enable unified logging
        given().log()
                .all()
                .when()
                .pathParam("targetId", target.id())
                .queryParam("what", "gc")
                .queryParam("decorators", "time,level")
                .post("/api/beta/diagnostics/targets/{targetId}/unified-logging")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200);

        // Generate some workload activity to create log entries
        generateWorkloadActivity(Duration.ofSeconds(3), Duration.ofMillis(100));

        // Trigger GC to create log entries
        triggerGcAndWait(target.id());

        // Pull a unified log
        given().log()
                .all()
                .when()
                .pathParam("targetId", target.id())
                .post("/api/beta/diagnostics/targets/{targetId}/unified-logging/pull")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200);

        // Test the GraphQL Query
        var variables = Map.<String, Object>of("targetIds", List.of(target.id()));
        Response resp =
                given().basePath("/")
                        .body(Map.of("query", LIST_UNIFIED_LOGS_QUERY, "variables", variables))
                        .contentType(ContentType.JSON)
                        .log()
                        .all()
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .response();

        assertNoGraphQLErrors(resp);

        JsonObject data = new JsonObject(resp.asString());
        assertThat(data, notNullValue());

        JsonObject queryData = data.getJsonObject("data");
        assertThat(queryData, notNullValue());

        JsonArray targetNodes = queryData.getJsonArray("targetNodes");
        assertThat(targetNodes, notNullValue());
        assertThat(targetNodes.size(), greaterThan(0));

        JsonObject targetNode = targetNodes.getJsonObject(0);
        JsonObject targetNode_target = targetNode.getJsonObject("target");
        assertThat(
                targetNode_target.getLong("id"), equalTo(UnifiedLogsGraphQLTest.this.target.id()));

        JsonObject unifiedLogs = targetNode_target.getJsonObject("unifiedLogs");
        assertThat(unifiedLogs, notNullValue());

        JsonArray logsData = unifiedLogs.getJsonArray("data");
        assertThat(logsData, notNullValue());
        assertThat(logsData.size(), greaterThan(0));

        JsonObject log = logsData.getJsonObject(0);
        assertThat(log.getString("jvmId"), notNullValue());
        assertThat(log.getString("downloadUrl"), notNullValue());
        assertThat(log.getString("unifiedLogId"), notNullValue());
        assertThat(log.getLong("lastModified"), greaterThan(0L));
        assertThat(log.getLong("size"), greaterThan(0L));

        JsonObject aggregate = unifiedLogs.getJsonObject("aggregate");
        assertThat(aggregate, notNullValue());
        assertThat(aggregate.getLong("count"), greaterThan(0L));
        assertThat(aggregate.getLong("size"), greaterThan(0L));
    }
}
