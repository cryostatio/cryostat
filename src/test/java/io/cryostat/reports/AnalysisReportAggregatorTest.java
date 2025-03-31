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
package io.cryostat.reports;

import static io.restassured.RestAssured.given;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.CacheEnabledTestProfile;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.websocket.DeploymentException;
import org.apache.commons.lang3.tuple.Pair;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@QuarkusTest
@TestHTTPEndpoint(AnalysisReportAggregator.class)
@TestMethodOrder(OrderAnnotation.class)
@TestProfile(CacheEnabledTestProfile.class)
public class AnalysisReportAggregatorTest extends AbstractTransactionalTestBase {

    @Inject ObjectMapper mapper;

    @Test
    @Order(1)
    void testGetNoSource() {
        int targetId = defineSelfCustomTarget();

        String scrape =
                given().log()
                        .all()
                        .when()
                        .get()
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.TEXT)
                        .and()
                        .extract()
                        .body()
                        .asString();
        MatcherAssert.assertThat(scrape, Matchers.is(Matchers.emptyString()));

        given().log()
                .all()
                .when()
                .basePath("/api/v4/targets/{targetId}/reports")
                .pathParams("targetId", targetId)
                .get()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @Test
    @Order(2)
    void testScrapeAll()
            throws InterruptedException, IOException, DeploymentException, TimeoutException {
        int targetId = defineSelfCustomTarget();
        var recording =
                given().log()
                        .all()
                        .when()
                        .basePath("/")
                        .pathParams(Map.of("targetId", targetId))
                        .formParam("recordingName", "analysisReportAggregatorAll")
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

        expectWebSocketNotification("ReportSuccess");

        String scrape =
                given().log()
                        .all()
                        .when()
                        .get()
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.TEXT)
                        .and()
                        .extract()
                        .body()
                        .asString();
        MatcherAssert.assertThat(scrape, Matchers.not(Matchers.emptyString()));
        Arrays.asList(scrape.split("\n")).stream()
                .map(String::strip)
                .map(
                        s ->
                                Pair.of(
                                        s.substring(0, s.lastIndexOf('=')),
                                        s.substring(s.lastIndexOf('=') + 1)))
                .forEach(
                        kv -> {
                            MatcherAssert.assertThat(
                                    kv.getKey(),
                                    Matchers.allOf(
                                            Matchers.containsString("Realm=\"Custom Targets\""),
                                            Matchers.containsString(
                                                    String.format("JVM=\"%s\"", SELF_JMX_URL))));
                            double score = Double.parseDouble(kv.getValue());
                            MatcherAssert.assertThat(
                                    score,
                                    Matchers.either(Matchers.equalTo(-1.0))
                                            .or(
                                                    Matchers.both(
                                                                    Matchers.greaterThanOrEqualTo(
                                                                            0.0))
                                                            .and(
                                                                    Matchers.lessThanOrEqualTo(
                                                                            100.0))));
                        });

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

    @Test
    @Order(3)
    void testScrapeSingle()
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

        expectWebSocketNotification("ReportSuccess");

        String scrape =
                given().log()
                        .all()
                        .when()
                        .get(selfJvmId)
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.TEXT)
                        .and()
                        .extract()
                        .body()
                        .asString();
        MatcherAssert.assertThat(scrape, Matchers.not(Matchers.emptyString()));
        Arrays.asList(scrape.split("\n")).stream()
                .map(String::strip)
                .map(
                        s ->
                                Pair.of(
                                        s.substring(0, s.lastIndexOf('=')),
                                        s.substring(s.lastIndexOf('=') + 1)))
                .forEach(
                        kv -> {
                            MatcherAssert.assertThat(
                                    kv.getKey(),
                                    Matchers.allOf(
                                            Matchers.containsString("Realm=\"Custom Targets\""),
                                            Matchers.containsString(
                                                    String.format("JVM=\"%s\"", SELF_JMX_URL))));
                            double score = Double.parseDouble(kv.getValue());
                            MatcherAssert.assertThat(
                                    score,
                                    Matchers.either(Matchers.equalTo(-1.0))
                                            .or(
                                                    Matchers.both(
                                                                    Matchers.greaterThanOrEqualTo(
                                                                            0.0))
                                                            .and(
                                                                    Matchers.lessThanOrEqualTo(
                                                                            100.0))));
                        });

        given().log()
                .all()
                .when()
                .basePath("/api/v4/targets/{targetId}/reports")
                .pathParams("targetId", targetId)
                .get()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);

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

    @Test
    @Order(4)
    void testScrapeSingleNonexistent() {
        given().log()
                .all()
                .when()
                .get("nonexistent")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }
}
