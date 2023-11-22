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

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.handler.HttpException;
import itest.bases.StandardSelfTest;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class SnapshotTest extends StandardSelfTest {

    static final String TEST_RECORDING_NAME = "someRecording";
    static final Pattern SNAPSHOT_NAME_PATTERN = Pattern.compile("^snapshot-[0-9]+$");

    String v1RequestUrl() {
        return String.format("/api/v1/targets/%s", getSelfReferenceConnectUrlEncoded());
    }

    String v2RequestUrl() {
        return String.format("/api/v2/targets/%s", getSelfReferenceConnectUrlEncoded());
    }

    @Test
    void testPostV1ShouldHandleEmptySnapshot() throws Exception {
        // precondition, there should be no recordings before we start
        CompletableFuture<JsonArray> preListRespFuture = new CompletableFuture<>();
        webClient
                .get(String.format("%s/recordings", v1RequestUrl()))
                .basicAuthentication("user", "pass")
                .send(
                        ar -> {
                            if (assertRequestStatus(ar, preListRespFuture)) {
                                preListRespFuture.complete(ar.result().bodyAsJsonArray());
                            }
                        });
        JsonArray preListResp = preListRespFuture.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        MatcherAssert.assertThat(preListResp, Matchers.equalTo(new JsonArray()));

        CompletableFuture<Integer> result = new CompletableFuture<>();
        // Create an empty snapshot recording (no active recordings present)
        webClient
                .post(String.format("%s/snapshot", v1RequestUrl()))
                .basicAuthentication("user", "pass")
                .send(
                        ar -> {
                            if (assertRequestStatus(ar, result)) {
                                result.complete(ar.result().statusCode());
                            }
                        });
        MatcherAssert.assertThat(
                result.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS), Matchers.equalTo(202));

        // The empty snapshot should've been deleted (i.e. there should be no recordings
        // present)
        CompletableFuture<JsonArray> postListRespFuture = new CompletableFuture<>();
        webClient
                .get(String.format("%s/recordings", v1RequestUrl()))
                .basicAuthentication("user", "pass")
                .send(
                        ar -> {
                            if (assertRequestStatus(ar, postListRespFuture)) {
                                postListRespFuture.complete(ar.result().bodyAsJsonArray());
                            }
                        });
        JsonArray postListResp = postListRespFuture.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        MatcherAssert.assertThat(postListResp, Matchers.equalTo(new JsonArray()));
    }

    @Test
    void testPostV2ShouldHandleEmptySnapshot() throws Exception {
        // precondition, there should be no recordings before we start
        CompletableFuture<JsonArray> preListRespFuture = new CompletableFuture<>();
        webClient
                .get(String.format("%s/recordings", v1RequestUrl()))
                .basicAuthentication("user", "pass")
                .send(
                        ar -> {
                            if (assertRequestStatus(ar, preListRespFuture)) {
                                preListRespFuture.complete(ar.result().bodyAsJsonArray());
                            }
                        });
        JsonArray preListResp = preListRespFuture.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        MatcherAssert.assertThat(preListResp, Matchers.equalTo(new JsonArray()));

        CompletableFuture<Integer> result = new CompletableFuture<>();
        // Create an empty snapshot recording (no active recordings present)
        webClient
                .post(String.format("%s/snapshot", v2RequestUrl()))
                .basicAuthentication("user", "pass")
                .send(
                        ar -> {
                            if (assertRequestStatus(ar, result)) {
                                result.complete(ar.result().statusCode());
                            }
                        });
        MatcherAssert.assertThat(
                result.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS), Matchers.equalTo(202));

        // The empty snapshot should've been deleted (i.e. there should be no recordings
        // present)
        CompletableFuture<JsonArray> postListRespFuture = new CompletableFuture<>();
        webClient
                .get(String.format("%s/recordings", v1RequestUrl()))
                .basicAuthentication("user", "pass")
                .send(
                        ar -> {
                            if (assertRequestStatus(ar, postListRespFuture)) {
                                postListRespFuture.complete(ar.result().bodyAsJsonArray());
                            }
                        });
        JsonArray postListResp = postListRespFuture.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        MatcherAssert.assertThat(postListResp, Matchers.equalTo(new JsonArray()));
    }

    @Test
    void testPostV1ShouldCreateSnapshot() throws Exception {
        CompletableFuture<String> snapshotName = new CompletableFuture<>();

        // Create a recording
        MultiMap form = MultiMap.caseInsensitiveMultiMap();
        form.add("recordingName", TEST_RECORDING_NAME);
        form.add("duration", "5");
        form.add("events", "template=ALL");
        webClient.extensions().post(String.format("%s/recordings", v1RequestUrl()), true, form, 5);

        // Create a snapshot recording of all events at that time
        webClient
                .post(String.format("%s/snapshot", v1RequestUrl()))
                .basicAuthentication("user", "pass")
                .send(
                        ar -> {
                            if (assertRequestStatus(ar, snapshotName)) {
                                MatcherAssert.assertThat(
                                        ar.result().statusCode(), Matchers.equalTo(200));
                                MatcherAssert.assertThat(
                                        ar.result().getHeader(HttpHeaders.CONTENT_TYPE.toString()),
                                        Matchers.equalTo("text/plain;charset=UTF-8"));
                                snapshotName.complete(ar.result().bodyAsString());
                            }
                        });

        MatcherAssert.assertThat(
                snapshotName.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                Matchers.matchesPattern(SNAPSHOT_NAME_PATTERN));

        // Clean up recording and snapshot
        webClient
                .extensions()
                .delete(
                        String.format("%s/recordings/%s", v1RequestUrl(), TEST_RECORDING_NAME),
                        true,
                        5);
        webClient
                .extensions()
                .delete(
                        String.format(
                                "%s/recordings/%s",
                                v1RequestUrl(),
                                snapshotName.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)),
                        true,
                        5);
    }

    @Test
    void testPostV1SnapshotThrowsWithNonExistentTarget() throws Exception {
        CompletableFuture<String> snapshotResponse = new CompletableFuture<>();
        webClient
                .post("/api/v1/targets/notFound%2F9000/snapshot")
                .basicAuthentication("user", "pass")
                .send(
                        ar -> {
                            assertRequestStatus(ar, snapshotResponse);
                        });
        ExecutionException ex =
                Assertions.assertThrows(
                        ExecutionException.class,
                        () -> snapshotResponse.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        MatcherAssert.assertThat(
                ((HttpException) ex.getCause()).getStatusCode(), Matchers.equalTo(404));
        MatcherAssert.assertThat(ex.getCause().getMessage(), Matchers.equalTo("Not Found"));
    }

    @Test
    void testPostV2ShouldCreateSnapshot() throws Exception {
        CompletableFuture<String> snapshotName = new CompletableFuture<>();

        // Create a recording
        MultiMap form = MultiMap.caseInsensitiveMultiMap();
        form.add("recordingName", TEST_RECORDING_NAME);
        form.add("duration", "5");
        form.add("events", "template=ALL");
        webClient.extensions().post(String.format("%s/recordings", v1RequestUrl()), true, form, 5);

        // Create a snapshot recording of all events at that time
        CompletableFuture<JsonObject> createResponse = new CompletableFuture<>();
        webClient
                .post(String.format("%s/snapshot", v2RequestUrl()))
                .basicAuthentication("user", "pass")
                .send(
                        ar -> {
                            if (assertRequestStatus(ar, createResponse)) {
                                MatcherAssert.assertThat(
                                        ar.result().statusCode(), Matchers.equalTo(201));
                                MatcherAssert.assertThat(
                                        ar.result().getHeader(HttpHeaders.CONTENT_TYPE.toString()),
                                        Matchers.equalTo("application/json;charset=UTF-8"));
                                createResponse.complete(ar.result().bodyAsJsonObject());
                            }
                        });

        snapshotName.complete(
                createResponse
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .getJsonObject("data")
                        .getJsonObject("result")
                        .getString("name"));

        JsonObject json = createResponse.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        MatcherAssert.assertThat(
                json.getJsonObject("meta"),
                Matchers.equalTo(
                        new JsonObject(Map.of("type", "application/json", "status", "Created"))));
        MatcherAssert.assertThat(json.getMap(), Matchers.hasKey("data"));
        MatcherAssert.assertThat(json.getJsonObject("data").getMap(), Matchers.hasKey("result"));
        JsonObject result = json.getJsonObject("data").getJsonObject("result");
        MatcherAssert.assertThat(result.getString("state"), Matchers.equalTo("STOPPED"));
        MatcherAssert.assertThat(
                result.getLong("startTime"),
                Matchers.lessThanOrEqualTo(Instant.now().toEpochMilli()));
        MatcherAssert.assertThat(
                result.getString("name"),
                Matchers.equalTo(snapshotName.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)));
        MatcherAssert.assertThat(result.getLong("id"), Matchers.greaterThan(0L));
        MatcherAssert.assertThat(
                result.getString("downloadUrl"),
                Matchers.equalTo("/api/v3/activedownload/" + result.getLong("id")));
        MatcherAssert.assertThat(
                result.getString("reportUrl"),
                Matchers.equalTo("/api/v3/targets/1/reports/" + result.getLong("remoteId")));
        MatcherAssert.assertThat(result.getLong("expiry"), Matchers.nullValue());

        webClient
                .extensions()
                .delete(
                        String.format("%s/recordings/%s", v1RequestUrl(), TEST_RECORDING_NAME),
                        true,
                        5);
        webClient
                .extensions()
                .delete(
                        String.format(
                                "%s/recordings/%s",
                                v1RequestUrl(),
                                snapshotName.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)),
                        true,
                        5);
    }

    @Test
    void testPostV2SnapshotThrowsWithNonExistentTarget() throws Exception {
        CompletableFuture<String> snapshotName = new CompletableFuture<>();
        webClient
                .post("/api/v2/targets/notFound:9000/snapshot")
                .basicAuthentication("user", "pass")
                .send(
                        ar -> {
                            assertRequestStatus(ar, snapshotName);
                        });
        ExecutionException ex =
                Assertions.assertThrows(
                        ExecutionException.class,
                        () -> snapshotName.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        MatcherAssert.assertThat(
                ((HttpException) ex.getCause()).getStatusCode(), Matchers.equalTo(404));
        MatcherAssert.assertThat(ex.getCause().getMessage(), Matchers.equalTo("Not Found"));
    }
}
