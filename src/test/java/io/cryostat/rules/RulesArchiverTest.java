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
package io.cryostat.rules;

import static io.restassured.RestAssured.given;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.cryostat.AbstractTransactionalTestBase;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.websocket.DeploymentException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class RulesArchiverTest extends AbstractTransactionalTestBase {

    static String RULE_NAME = "periodic_archiver";

    static final String rule =
            String.format(
                    """
                    {
                      "name": "%s",
                      "description": "",
                      "matchExpression": "true",
                      "eventSpecifier": "template=Continuous,type=TARGET",
                      "archivalPeriodSeconds": 10,
                      "initialDelaySeconds": 0,
                      "preservedArchives": 3,
                      "maxAgeSeconds": 0,
                      "maxSizeBytes": 10485760,
                      "enabled": true
                    }
                    """,
                    RULE_NAME);

    private static final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor();

    @Test
    public void test()
            throws TimeoutException, InterruptedException, IOException, DeploymentException {
        int id = defineSelfCustomTarget();

        given().log()
                .all()
                .when()
                .pathParams(Map.of("targetId", id))
                .get("/api/v4/targets/{targetId}/recordings")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(0));

        given().log()
                .all()
                .when()
                .pathParams(Map.of("jvmId", this.selfJvmId))
                .get("/api/beta/recordings/{jvmId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(0));

        given().body(rule)
                .contentType(ContentType.JSON)
                .post("/api/v4/rules")
                .then()
                .statusCode(201);

        // stop further background jobs before checking results
        // this is enough time for 4 copies to be made, but we expect the oldest to get rolled over
        // so 3 should remain
        worker.schedule(
                () -> {
                    given().log()
                            .all()
                            // do not clean, or else Cryostat will archive the recording on stop and
                            // create an additional copy
                            .queryParam("clean", false)
                            .pathParam("ruleName", RULE_NAME)
                            .delete("/api/v4/rules/{ruleName}")
                            .then()
                            .log()
                            .all()
                            .and()
                            .assertThat()
                            .statusCode(204)
                            .body(Matchers.emptyOrNullString());
                },
                45,
                TimeUnit.SECONDS);

        expectWebSocketNotification("RuleDeleted", Duration.ofSeconds(60));

        given().log()
                .all()
                .when()
                .pathParams(Map.of("jvmId", this.selfJvmId))
                .get("/api/beta/recordings/{jvmId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(3));

        given().body(
                        Map.of(
                                "query",
                                String.format(
                                        """
                                        query RulesArchiverTestCleanup {
                                          targetNodes(filter: { targetIds: [%d] }) {
                                            descendantTargets {
                                              target {
                                                recordings {
                                                  active {
                                                    data {
                                                      doDelete {
                                                        name
                                                      }
                                                    }
                                                  }
                                                  archived {
                                                    data {
                                                      doDelete {
                                                        name
                                                      }
                                                    }
                                                  }
                                                }
                                              }
                                            }
                                          }
                                        }
                                        """,
                                        id)))
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
