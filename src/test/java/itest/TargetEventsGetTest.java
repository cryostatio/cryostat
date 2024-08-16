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

import static org.hamcrest.Matchers.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.cryostat.util.HttpMimeType;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonArray;
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
        eventReqUrl = String.format("/api/v4/targets/%d/events", getSelfReferenceTargetId());
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
    public void testGetTargetEventsWithNoQueryReturnsListOfEvents() throws Exception {
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

        JsonArray events = response.bodyAsJsonArray();
        MatcherAssert.assertThat(events.size(), Matchers.greaterThan(0));

        events.forEach(
                event -> {
                    JsonObject eventObj = (JsonObject) event;
                    MatcherAssert.assertThat(eventObj.getString("name"), notNullValue());
                    MatcherAssert.assertThat(eventObj.getString("typeId"), notNullValue());
                    MatcherAssert.assertThat(eventObj.getString("description"), notNullValue());
                });
    }

    @Test
    public void testGetTargetEventsWithQueryReturnsRequestedEvents() throws Exception {
        CompletableFuture<HttpResponse<Buffer>> getResponse = new CompletableFuture<>();
        webClient
                .get(String.format("%s?q=TargetConnectionOpened", eventReqUrl))
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

        JsonArray results = response.bodyAsJsonArray();
        MatcherAssert.assertThat(results.size(), Matchers.greaterThan(0));

        JsonObject expectedEvent =
                new JsonObject()
                        .put("name", "Target Connection Opened")
                        .put(
                                "typeId",
                                "io.cryostat.targets.TargetConnectionManager.TargetConnectionOpened")
                        .put("description", "")
                        .put("category", new JsonArray().add("Cryostat"))
                        .put(
                                "options",
                                new JsonObject()
                                        .put(
                                                "enabled",
                                                new JsonObject()
                                                        .put("name", "Enabled")
                                                        .put("description", "Record event")
                                                        .put("defaultValue", "true"))
                                        .put(
                                                "threshold",
                                                new JsonObject()
                                                        .put("name", "Threshold")
                                                        .put(
                                                                "description",
                                                                "Record event with duration above"
                                                                        + " or equal to threshold")
                                                        .put("defaultValue", "0ns[ns]"))
                                        .put(
                                                "stackTrace",
                                                new JsonObject()
                                                        .put("name", "Stack Trace")
                                                        .put("description", "Record stack traces")
                                                        .put("defaultValue", "true")));

        JsonObject actualEvent = results.getJsonObject(0);
        MatcherAssert.assertThat(actualEvent, Matchers.equalTo(expectedEvent));
    }

    @Test
    public void testGetTargetEventsWithQueryReturnsEmptyListWhenNoEventsMatch() throws Exception {
        CompletableFuture<HttpResponse<Buffer>> getResponse = new CompletableFuture<>();
        webClient
                .get(String.format("%s?q=thisEventDoesNotExist", eventReqUrl))
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

        JsonArray results = response.bodyAsJsonArray();

        MatcherAssert.assertThat(results.size(), Matchers.is(0));
    }
}
