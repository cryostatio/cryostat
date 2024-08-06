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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.cryostat.util.HttpMimeType;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import itest.bases.StandardSelfTest;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class TargetEventsGetTest extends StandardSelfTest {

    String eventReqUrl;
    String searchReqUrl;

    @BeforeEach
    void setup() {
        eventReqUrl = String.format("/api/v3/targets/%d/events", getSelfReferenceTargetId());
        searchReqUrl =
                String.format("/api/v2/targets/%s/events", getSelfReferenceConnectUrlEncoded());
    }

    @Test
    public void testGetTargetEventsReturnsListOfEvents() throws Exception {
        CompletableFuture<HttpResponse<Buffer>> getResponse = new CompletableFuture<>();
        webClient
                .get(eventReqUrl)
                .send(
                        ar -> {
                            if (assertRequestStatus(ar, getResponse)) {
                                getResponse.complete(ar.result());
                            }
                        });
        HttpResponse<Buffer> response = getResponse.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        MatcherAssert.assertThat(response.statusCode(), Matchers.equalTo(200));
        MatcherAssert.assertThat(
                response.getHeader(HttpHeaders.CONTENT_TYPE.toString()),
                Matchers.startsWith(HttpMimeType.JSON.mime()));
        MatcherAssert.assertThat(
                getResponse.get().bodyAsJsonArray().size(), Matchers.greaterThan(0));
    }

    @Test
    public void testGetTargetEventsV2WithNoQueryReturnsListOfEvents() throws Exception {
        CompletableFuture<HttpResponse<Buffer>> getResponse = new CompletableFuture<>();
        webClient
                .get(searchReqUrl)
                .send(
                        ar -> {
                            if (assertRequestStatus(ar, getResponse)) {
                                getResponse.complete(ar.result());
                            }
                        });
        HttpResponse<Buffer> response = getResponse.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        MatcherAssert.assertThat(response.statusCode(), Matchers.equalTo(200));
        MatcherAssert.assertThat(
                response.getHeader(HttpHeaders.CONTENT_TYPE.toString()),
                Matchers.startsWith(HttpMimeType.JSON.mime()));

        MatcherAssert.assertThat(response.bodyAsJsonObject().size(), Matchers.greaterThan(0));
        MatcherAssert.assertThat(
                response.bodyAsJsonObject().getJsonObject("data").getJsonArray("result").size(),
                Matchers.greaterThan(0));
    }

    @Test
    public void testGetTargetEventsV2WithQueryReturnsRequestedEvents() throws Exception {
        CompletableFuture<HttpResponse<Buffer>> getResponse = new CompletableFuture<>();
        webClient
                .get(String.format("%s?q=TargetConnectionOpened", searchReqUrl))
                .send(
                        ar -> {
                            if (assertRequestStatus(ar, getResponse)) {
                                getResponse.complete(ar.result());
                            }
                        });
        HttpResponse<Buffer> response = getResponse.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        MatcherAssert.assertThat(response.statusCode(), Matchers.equalTo(200));
        MatcherAssert.assertThat(
                response.getHeader(HttpHeaders.CONTENT_TYPE.toString()),
                Matchers.startsWith(HttpMimeType.JSON.mime()));

        LinkedHashMap<String, Object> expectedResults = new LinkedHashMap<String, Object>();
        expectedResults.put("name", "Target Connection Opened");
        expectedResults.put(
                "typeId", "io.cryostat.targets.TargetConnectionManager.TargetConnectionOpened");
        expectedResults.put("description", "");
        expectedResults.put("category", List.of("Cryostat"));
        expectedResults.put(
                "options",
                Map.of(
                        "enabled",
                        Map.of(
                                "name",
                                "Enabled",
                                "description",
                                "Record event",
                                "defaultValue",
                                "true"),
                        "threshold",
                        Map.of(
                                "name",
                                "Threshold",
                                "description",
                                "Record event with duration above or equal to threshold",
                                "defaultValue",
                                "0ns[ns]"),
                        "stackTrace",
                        Map.of(
                                "name",
                                "Stack Trace",
                                "description",
                                "Record stack traces",
                                "defaultValue",
                                "true")));

        JsonObject expectedResponse =
                new JsonObject(
                        Map.of(
                                "meta", Map.of("type", HttpMimeType.JSON.mime(), "status", "OK"),
                                "data", Map.of("result", List.of(expectedResults))));

        MatcherAssert.assertThat(response.bodyAsJsonObject().size(), Matchers.greaterThan(0));
        MatcherAssert.assertThat(response.bodyAsJsonObject(), Matchers.equalTo(expectedResponse));
    }

    @Test
    public void testGetTargetEventsV2WithQueryReturnsEmptyListWhenNoEventsMatch() throws Exception {
        CompletableFuture<HttpResponse<Buffer>> getResponse = new CompletableFuture<>();
        webClient
                .get(String.format("%s?q=thisEventDoesNotExist", searchReqUrl))
                .send(
                        ar -> {
                            if (assertRequestStatus(ar, getResponse)) {
                                getResponse.complete(ar.result());
                            }
                        });
        HttpResponse<Buffer> response = getResponse.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        MatcherAssert.assertThat(response.statusCode(), Matchers.equalTo(200));
        MatcherAssert.assertThat(
                response.getHeader(HttpHeaders.CONTENT_TYPE.toString()),
                Matchers.startsWith(HttpMimeType.JSON.mime()));

        JsonObject expectedResponse =
                new JsonObject(
                        Map.of(
                                "meta", Map.of("type", HttpMimeType.JSON.mime(), "status", "OK"),
                                "data", Map.of("result", List.of())));

        MatcherAssert.assertThat(response.bodyAsJsonObject(), Matchers.equalTo(expectedResponse));
    }
}
