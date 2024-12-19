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
package itest;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import io.cryostat.util.HttpMimeType;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.handler.HttpException;
import itest.bases.StandardSelfTest;
import itest.util.ITestCleanupFailedException;
import org.apache.commons.lang3.tuple.Pair;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@QuarkusIntegrationTest
@TestMethodOrder(OrderAnnotation.class)
class RulesPostJsonIT extends StandardSelfTest {

    static JsonObject testRule;

    static final Map<String, String> NULL_RESULT = new HashMap<>();

    static final String TEST_RULE_NAME = "Test_Rule";

    static {
        NULL_RESULT.put("result", null);
    }

    @BeforeAll
    static void setup() throws Exception {
        testRule = new JsonObject();
        testRule.put("name", TEST_RULE_NAME);
        testRule.put("matchExpression", "target.alias == 'es.andrewazor.demo.Main'");
        testRule.put("description", "AutoRulesIT automated rule");
        testRule.put("eventSpecifier", "template=Continuous,type=TARGET");
    }

    @Test
    @Order(1)
    void testAddRuleThrowsWhenJsonAttributesNull() throws Exception {
        CompletableFuture<JsonObject> response = new CompletableFuture<>();

        webClient
                .post("/api/v4/rules")
                .putHeader(HttpHeaders.CONTENT_TYPE.toString(), HttpMimeType.JSON.mime())
                .sendJsonObject(
                        null,
                        ar -> {
                            assertRequestStatus(ar, response);
                        });
        ExecutionException ex =
                Assertions.assertThrows(
                        ExecutionException.class, () -> response.get(10, TimeUnit.SECONDS));
        MatcherAssert.assertThat(
                ((HttpException) ex.getCause()).getStatusCode(), Matchers.equalTo(400));
        MatcherAssert.assertThat(ex.getCause().getMessage(), Matchers.equalTo("Bad Request"));
    }

    @Test
    @Order(2)
    @Disabled("https://github.com/quarkusio/quarkus/issues/44976")
    void testAddRuleThrowsWhenMimeUnsupported() throws Exception {
        CompletableFuture<JsonObject> response = new CompletableFuture<>();

        webClient
                .post("/api/v4/rules")
                .putHeader(HttpHeaders.CONTENT_TYPE.toString(), "text/plain")
                .sendJsonObject(
                        testRule,
                        ar -> {
                            assertRequestStatus(ar, response);
                        });
        ExecutionException ex =
                Assertions.assertThrows(
                        ExecutionException.class, () -> response.get(10, TimeUnit.SECONDS));
        MatcherAssert.assertThat(
                ((HttpException) ex.getCause()).getStatusCode(), Matchers.equalTo(415));
        MatcherAssert.assertThat(
                ex.getCause().getMessage(), Matchers.equalTo("Unsupported Media Type"));
    }

    @Test
    @Order(3)
    @Disabled("https://github.com/quarkusio/quarkus/issues/44976")
    void testAddRuleThrowsWhenMimeInvalid() throws Exception {
        CompletableFuture<JsonObject> response = new CompletableFuture<>();

        webClient
                .post("/api/v4/rules")
                .putHeader(HttpHeaders.CONTENT_TYPE.toString(), "NOTAMIME")
                .sendJsonObject(
                        testRule,
                        ar -> {
                            assertRequestStatus(ar, response);
                        });
        ExecutionException ex =
                Assertions.assertThrows(
                        ExecutionException.class, () -> response.get(10, TimeUnit.SECONDS));
        MatcherAssert.assertThat(
                ((HttpException) ex.getCause()).getStatusCode(), Matchers.equalTo(400));
        MatcherAssert.assertThat(ex.getCause().getMessage(), Matchers.equalTo("Bad Request"));
    }

    @Test
    @Order(4)
    void testAddRuleThrowsWhenRuleNameAlreadyExists() throws Exception {
        CompletableFuture<Pair<Integer, JsonObject>> response = new CompletableFuture<>();

        try {
            webClient
                    .post("/api/v4/rules")
                    .putHeader(HttpHeaders.CONTENT_TYPE.toString(), HttpMimeType.JSON.mime())
                    .sendJsonObject(
                            testRule,
                            ar -> {
                                if (assertRequestStatus(ar, response)) {
                                    String[] location =
                                            ar.result().headers().get("Location").split("/");
                                    response.complete(
                                            Pair.of(
                                                    Integer.valueOf(location[location.length - 1]),
                                                    ar.result().bodyAsJsonObject()));
                                }
                            });
            Pair<Integer, JsonObject> firstResponse = response.get(10, TimeUnit.SECONDS);
            Integer firstResponseId = firstResponse.getLeft();
            // Define the expected response structure based on the first creation
            JsonObject expectedCreationResponse =
                    new JsonObject()
                            .put("id", firstResponseId)
                            .put("name", "Test_Rule")
                            .put("description", "AutoRulesIT automated rule")
                            .put(
                                    "matchExpression",
                                    "target.alias ==" + " 'es.andrewazor.demo.Main'")
                            .put("eventSpecifier", "template=Continuous,type=TARGET")
                            .put("archivalPeriodSeconds", 0)
                            .put("initialDelaySeconds", 0)
                            .put("preservedArchives", 0)
                            .put("maxAgeSeconds", 0)
                            .put("maxSizeBytes", 0)
                            .put("enabled", false);
            MatcherAssert.assertThat(
                    firstResponse.getRight(), Matchers.equalTo(expectedCreationResponse));
            CompletableFuture<JsonObject> duplicatePostResponse = new CompletableFuture<>();
            webClient
                    .post("/api/v4/rules")
                    .putHeader(HttpHeaders.CONTENT_TYPE.toString(), HttpMimeType.JSON.mime())
                    .sendJsonObject(
                            testRule,
                            ar -> {
                                assertRequestStatus(ar, duplicatePostResponse);
                            });

            ExecutionException ex =
                    Assertions.assertThrows(
                            ExecutionException.class,
                            () -> duplicatePostResponse.get(10, TimeUnit.SECONDS));
            MatcherAssert.assertThat(
                    ((HttpException) ex.getCause()).getStatusCode(), Matchers.equalTo(409));
            MatcherAssert.assertThat(ex.getCause().getMessage(), Matchers.equalTo("Conflict"));

        } finally {
            // clean up rule before running next test
            CompletableFuture<Integer> deleteResponse = new CompletableFuture<>();
            webClient
                    .delete(String.format("/api/v4/rules/%s", TEST_RULE_NAME))
                    .putHeader(HttpHeaders.CONTENT_TYPE.toString(), HttpMimeType.JSON.mime())
                    .send(
                            ar -> {
                                if (assertRequestStatus(ar, deleteResponse)) {
                                    deleteResponse.complete(ar.result().statusCode());
                                }
                            });

            try {
                MatcherAssert.assertThat(
                        deleteResponse.get(10, TimeUnit.SECONDS),
                        Matchers.both(Matchers.greaterThanOrEqualTo(200))
                                .and(Matchers.lessThan(300)));
            } catch (InterruptedException | ExecutionException e) {
                logger.error(
                        new ITestCleanupFailedException(
                                String.format("Failed to delete rule %s", TEST_RULE_NAME), e));
            }
        }
    }

    @Test
    @Order(5)
    void testAddRuleThrowsWhenIntegerAttributesNegative() throws Exception {
        CompletableFuture<JsonObject> response = new CompletableFuture<>();

        testRule.put("archivalPeriodSeconds", -60);
        testRule.put("preservedArchives", -3);
        try {
            webClient
                    .post("/api/v4/rules")
                    .putHeader(HttpHeaders.CONTENT_TYPE.toString(), HttpMimeType.JSON.mime())
                    .sendJsonObject(
                            testRule,
                            ar -> {
                                assertRequestStatus(ar, response);
                                response.complete(ar.result().bodyAsJsonObject());
                            });
        } finally {
            testRule.put("archivalPeriodSeconds", 60);
            testRule.put("preservedArchives", 3);
        }
        ExecutionException ex =
                Assertions.assertThrows(
                        ExecutionException.class, () -> response.get(10, TimeUnit.SECONDS));
        MatcherAssert.assertThat(
                ((HttpException) ex.getCause()).getStatusCode(), Matchers.equalTo(400));
        MatcherAssert.assertThat(ex.getCause().getMessage(), Matchers.equalTo("Bad Request"));
    }
}
