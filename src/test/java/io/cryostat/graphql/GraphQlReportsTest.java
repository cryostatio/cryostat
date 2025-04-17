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

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.CacheEnabledTestProfile;
import io.cryostat.reports.AnalysisReportAggregator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
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
                graphql(
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
                        """);
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
        try {
            startSelfRecording(
                    "analysisReportAggregatorSingle",
                    Map.of(
                            "events",
                            TEMPLATE_CONTINUOUS,
                            "duration",
                            5,
                            "archiveOnStop",
                            true,
                            "metadata",
                            mapper.writeValueAsString(
                                    Map.of("labels", Map.of("autoanalyze", "true")))));

            expectWebSocketNotification("ReportSuccess");

            var jsonPath =
                    graphql(
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
                            """);
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
            cleanupSelfRecording();
        }
    }
}
