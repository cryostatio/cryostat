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

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.CacheEnabledTestProfile;
import io.cryostat.reports.AnalysisReportAggregator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.websocket.DeploymentException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(CacheEnabledTestProfile.class)
public class GraphQlReportsTest extends AbstractTransactionalTestBase {

    @Inject ObjectMapper mapper;
    @Inject AnalysisReportAggregator reportAggregator;

    @AfterEach
    void reset() {
        reportAggregator.reset();
    }

    @Test
    public void testTargetReportWithNoSource() {
        int targetId = defineSelfCustomTarget();

        var jsonPath =
                given().log()
                        .all()
                        .when()
                        .contentType(ContentType.JSON)
                        .body(
                                Map.of(
                                        "query",
                                        """
                                        query {
                                            targetNodes {
                                                target {
                                                    id
                                                    report {
                                                        aggregate {
                                                            count
                                                            max
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        """))
                        .post("/api/v4/graphql")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .extract()
                        .body()
                        .jsonPath();
        MatcherAssert.assertThat(jsonPath.getString("errors"), Matchers.nullValue());
        MatcherAssert.assertThat(jsonPath.getMap("data"), Matchers.not(Matchers.nullValue()));
        MatcherAssert.assertThat(
                jsonPath.getList("data.targetNodes"), Matchers.not(Matchers.nullValue()));
        MatcherAssert.assertThat(jsonPath.getList("data.targetNodes"), Matchers.hasSize(1));
        MatcherAssert.assertThat(
                jsonPath.getInt("data.targetNodes[0].target.id"), Matchers.equalTo(targetId));
        MatcherAssert.assertThat(
                jsonPath.getLong("data.targetNodes[0].target.report.aggregate.count"),
                Matchers.equalTo(0L));
        MatcherAssert.assertThat(
                jsonPath.getDouble("data.targetNodes[0].target.report.aggregate.max"),
                Matchers.equalTo(-1.0));
    }

    @Test
    void testTargetReportWithSource()
            throws InterruptedException, IOException, DeploymentException, TimeoutException {
        int targetId = defineSelfCustomTarget();
        var recording =
                given().log()
                        .all()
                        .when()
                        .basePath("/")
                        .pathParams(Map.of("targetId", targetId))
                        .formParam("recordingName", "analysisReportAggregatorSingle")
                        .formParam("events", "template=Continuous")
                        .formParam(
                                "metadata",
                                mapper.writeValueAsString(
                                        Map.of("labels", Map.of("autoanalyze", "true"))))
                        .formParam("duration", 5)
                        .formParam("archiveOnStop", true)
                        .pathParam("targetId", targetId)
                        .post("/api/v4/targets/{targetId}/recordings")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .and()
                        .extract()
                        .body()
                        .jsonPath();
        int remoteId = recording.getInt("remoteId");

        try {
            expectWebSocketNotification("ReportSuccess");

            var jsonPath =
                    given().log()
                            .all()
                            .when()
                            .contentType(ContentType.JSON)
                            .body(
                                    Map.of(
                                            "query",
                                            """
                                            query {
                                                targetNodes {
                                                    target {
                                                        id
                                                        report {
                                                            aggregate {
                                                                count
                                                                max
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            """))
                            .post("/api/v4/graphql")
                            .then()
                            .log()
                            .all()
                            .and()
                            .assertThat()
                            .statusCode(200)
                            .contentType(ContentType.JSON)
                            .extract()
                            .body()
                            .jsonPath();
            MatcherAssert.assertThat(jsonPath.getString("errors"), Matchers.nullValue());
            MatcherAssert.assertThat(jsonPath.getMap("data"), Matchers.not(Matchers.nullValue()));
            MatcherAssert.assertThat(
                    jsonPath.getList("data.targetNodes"), Matchers.not(Matchers.nullValue()));
            MatcherAssert.assertThat(jsonPath.getList("data.targetNodes"), Matchers.hasSize(1));
            MatcherAssert.assertThat(
                    jsonPath.getInt("data.targetNodes[0].target.id"), Matchers.equalTo(targetId));
            MatcherAssert.assertThat(
                    jsonPath.getLong("data.targetNodes[0].target.report.aggregate.count"),
                    Matchers.greaterThan(0L));
            MatcherAssert.assertThat(
                    jsonPath.getDouble("data.targetNodes[0].target.report.aggregate.max"),
                    Matchers.both(Matchers.greaterThan(0.0))
                            .and(Matchers.lessThanOrEqualTo(100.0)));
        } finally {
            given().log()
                    .all()
                    .when()
                    .basePath("/")
                    .pathParams("targetId", targetId, "remoteId", remoteId)
                    .delete("/api/v4/targets/{targetId}/recordings/{remoteId}")
                    .then()
                    .log()
                    .all()
                    .and()
                    .assertThat()
                    .statusCode(204);
        }
    }
}
