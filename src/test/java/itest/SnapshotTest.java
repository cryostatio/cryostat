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

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.handler.HttpException;
import itest.bases.StandardSelfTest;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class SnapshotTest extends StandardSelfTest {

    static final String TEST_RECORDING_NAME = "someRecording";
    static final Pattern SNAPSHOT_NAME_PATTERN = Pattern.compile("^snapshot-[0-9]+$");
    static long REMOTE_ID;
    static long SNAPSHOT_ID;
    private List<Long> recordingsToDelete;

    @BeforeEach
    void setup() throws Exception {
        recordingsToDelete = new ArrayList<>();
        cleanupRecordings();
    }

    @AfterEach
    void cleanup() throws Exception {
        cleanupRecordings();
    }

    private void cleanupRecordings() throws Exception {
        JsonArray recordings = fetchAllRecordings();
        for (Object obj : recordings) {
            if (obj instanceof JsonObject) {
                JsonObject recording = (JsonObject) obj;
                Long remoteId = recording.getLong("remoteId");
                if (remoteId != null) {
                    deleteRecording(remoteId);
                }
            }
        }
    }

    private JsonArray fetchAllRecordings() throws Exception {
        CompletableFuture<JsonArray> recordingsFuture = new CompletableFuture<>();
        webClient
                .get(String.format("%s/recordings", v4RequestUrl()))
                .send(
                        ar -> {
                            if (ar.succeeded()) {
                                recordingsFuture.complete(ar.result().bodyAsJsonArray());
                            } else {
                                recordingsFuture.completeExceptionally(
                                        new RuntimeException("Failed to fetch recordings"));
                            }
                        });
        return recordingsFuture.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void deleteRecording(Long remoteId) {
        webClient
                .delete(String.format("%s/recordings/%d", v4RequestUrl(), remoteId))
                .send(
                        ar -> {
                            if (!ar.succeeded()) {
                                System.err.println(
                                        "Failed to delete recording with remote ID: "
                                                + remoteId
                                                + ", cause: "
                                                + ar.cause());
                            }
                        });
    }

    String v1RequestUrl() {
        return String.format("/api/v1/targets/%s", getSelfReferenceConnectUrlEncoded());
    }

    String v4RequestUrl() {
        return String.format("/api/v4/targets/%d", getSelfReferenceTargetId());
    }

    @Test
    void testPostV1ShouldHandleEmptySnapshot() throws Exception {
        JsonArray preListResp = fetchPreTestRecordings();
        MatcherAssert.assertThat(preListResp, Matchers.equalTo(new JsonArray()));

        int statusCode = createEmptySnapshot(v1RequestUrl());
        MatcherAssert.assertThat(statusCode, Matchers.equalTo(202));

        JsonArray postListResp = fetchPostTestRecordings();
        MatcherAssert.assertThat(postListResp, Matchers.equalTo(new JsonArray()));
    }

    @Test
    void testPostv4ShouldHandleEmptySnapshot() throws Exception {
        JsonArray preListResp = fetchPreTestRecordings();
        MatcherAssert.assertThat(preListResp, Matchers.equalTo(new JsonArray()));

        int statusCode = createEmptySnapshot(v4RequestUrl());
        MatcherAssert.assertThat(statusCode, Matchers.equalTo(202));

        JsonArray postListResp = fetchPostTestRecordings();
        MatcherAssert.assertThat(postListResp, Matchers.equalTo(new JsonArray()));
    }

    @Test
    void testPostV1ShouldCreateSnapshot() throws Exception {
        CompletableFuture<String> snapshotName = new CompletableFuture<>();
        createRecording(snapshotName);

        Thread.sleep(5_000);

        createSnapshot(snapshotName);

        MatcherAssert.assertThat(
                snapshotName.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                Matchers.matchesPattern(SNAPSHOT_NAME_PATTERN));
    }

    @Test
    void testPostV1SnapshotThrowsWithNonExistentTarget() throws Exception {
        CompletableFuture<String> snapshotResponse = new CompletableFuture<>();
        webClient
                .post("/api/v1/targets/notFound%2F9000/snapshot")
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
    void testPostv4ShouldCreateSnapshot() throws Exception {
        CompletableFuture<String> snapshotName = new CompletableFuture<>();

        // Create a recording
        createRecording(snapshotName);

        Thread.sleep(5_000l);

        // Create a snapshot recording of all events at that time
        CompletableFuture<JsonObject> createResponse = new CompletableFuture<>();
        webClient
                .post(String.format("%s/snapshot", v4RequestUrl()))
                .send(
                        ar -> {
                            if (ar.succeeded()) {
                                HttpResponse<Buffer> response = ar.result();
                                if (response.statusCode() == 200) {
                                    JsonObject jsonResponse = response.bodyAsJsonObject();
                                    String name = jsonResponse.getString("name");
                                    long snapshotRemoteId = jsonResponse.getLong("remoteId");
                                    snapshotName.complete(name);
                                    createResponse.complete(jsonResponse);
                                    recordingsToDelete.add(snapshotRemoteId);
                                } else {
                                    System.err.println(
                                            "Failed to create snapshot, status code: "
                                                    + response.statusCode());
                                    createResponse.completeExceptionally(
                                            new RuntimeException(
                                                    "Failed to create snapshot, Status code: "
                                                            + response.statusCode()));
                                }
                            } else {
                                System.err.println("Request failed: " + ar.cause());
                                createResponse.completeExceptionally(ar.cause());
                            }
                        });

        JsonObject json = createResponse.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        String resolvedName = snapshotName.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Validate the JSON response
        MatcherAssert.assertThat(json.getString("name"), Matchers.equalTo(resolvedName));
        MatcherAssert.assertThat(json.getLong("remoteId"), Matchers.greaterThan(0L));
        MatcherAssert.assertThat(json.getString("state"), Matchers.equalTo("STOPPED"));
        MatcherAssert.assertThat(
                json.getLong("startTime"),
                Matchers.lessThanOrEqualTo(Instant.now().toEpochMilli()));
        MatcherAssert.assertThat(
                json.getString("downloadUrl"), Matchers.startsWith("/api/v4/activedownload/"));
        MatcherAssert.assertThat(
                json.getString("reportUrl"),
                Matchers.equalTo(
                        URI.create(
                                        String.format(
                                                "%s/reports/%d",
                                                selfCustomTargetLocation, json.getLong("remoteId")))
                                .getPath()));
        MatcherAssert.assertThat(json.containsKey("expiry"), Matchers.is(false));
    }

    @Test
    void testPostv4SnapshotThrowsWithNonExistentTarget() throws Exception {
        CompletableFuture<String> snapshotName = new CompletableFuture<>();
        webClient
                .post("/api/v4/targets/notFound:9000/snapshot")
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

    private JsonArray fetchPreTestRecordings() throws Exception {
        CompletableFuture<JsonArray> preListRespFuture = new CompletableFuture<>();
        webClient
                .get(String.format("%s/recordings", v4RequestUrl()))
                .send(
                        ar -> {
                            if (ar.succeeded()) {
                                preListRespFuture.complete(ar.result().bodyAsJsonArray());
                            } else {
                                preListRespFuture.completeExceptionally(
                                        new RuntimeException("Failed to fetch recordings"));
                            }
                        });
        return preListRespFuture.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private JsonArray fetchPostTestRecordings() throws Exception {
        CompletableFuture<JsonArray> postListRespFuture = new CompletableFuture<>();
        webClient
                .get(String.format("%s/recordings", v4RequestUrl()))
                .send(
                        ar -> {
                            if (ar.succeeded()) {
                                postListRespFuture.complete(ar.result().bodyAsJsonArray());
                            } else {
                                postListRespFuture.completeExceptionally(
                                        new RuntimeException("Failed to fetch recordings"));
                            }
                        });
        return postListRespFuture.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private int createEmptySnapshot(String requestUrl) throws Exception {
        CompletableFuture<Integer> result = new CompletableFuture<>();
        webClient
                .post(requestUrl + "/snapshot")
                .send(
                        ar -> {
                            if (ar.succeeded()) {
                                result.complete(ar.result().statusCode());
                            } else {
                                result.completeExceptionally(
                                        new RuntimeException("Failed to create snapshot"));
                            }
                        });
        return result.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void createRecording(CompletableFuture<String> snapshotName) throws Exception {
        MultiMap form = MultiMap.caseInsensitiveMultiMap();
        form.add("recordingName", TEST_RECORDING_NAME);
        form.add("duration", "5");
        form.add("events", "template=ALL");
        webClient
                .post(String.format("%s/recordings", v4RequestUrl()))
                .sendForm(
                        form,
                        ar -> {
                            if (ar.succeeded()) {
                                HttpResponse<Buffer> response = ar.result();
                                if (response.statusCode() == 201) {
                                    JsonObject jsonResponse = response.bodyAsJsonObject();
                                    long remoteId = jsonResponse.getLong("remoteId");
                                    REMOTE_ID = remoteId;
                                    recordingsToDelete.add(remoteId);
                                }
                            }
                        });
    }

    private void createSnapshot(CompletableFuture<String> snapshotName) throws Exception {
        webClient
                .post(String.format("%s/snapshot", v1RequestUrl()))
                .send(
                        ar -> {
                            if (ar.succeeded()) {
                                HttpResponse<Buffer> response = ar.result();
                                if (response.statusCode() == 200) {
                                    String name = response.bodyAsString();
                                    snapshotName.complete(name);
                                } else {
                                    snapshotName.completeExceptionally(
                                            new RuntimeException("Failed to create snapshot"));
                                }
                            } else {
                                snapshotName.completeExceptionally(ar.cause());
                            }
                        });
    }
}
