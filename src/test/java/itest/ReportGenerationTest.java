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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import io.cryostat.resources.LocalStackResource;
import io.cryostat.util.HttpMimeType;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.handler.HttpException;
import itest.bases.StandardSelfTest;
import itest.util.ITestCleanupFailedException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(LocalStackResource.class)
public class ReportGenerationTest extends StandardSelfTest {

    static final String TEST_RECORDING_NAME = "someRecording";

    private String archivedReportRequestURL() {
        return String.format("/api/beta/reports/%s", getSelfReferenceConnectUrlEncoded());
    }

    private String recordingRequestURL() {
        return String.format("/api/v3/targets/%d/recordings", getSelfReferenceTargetId());
    }

    private String archiveListRequestURL() {
        return String.format("/api/beta/recordings/%s", getSelfReferenceConnectUrlEncoded());
    }

    @Test
    void testGetActiveReport() throws Exception {
        JsonObject activeRecording = null;
        try {
            // Create a recording
            CompletableFuture<JsonObject> postResponse = new CompletableFuture<>();
            MultiMap form = MultiMap.caseInsensitiveMultiMap();
            form.add("recordingName", TEST_RECORDING_NAME);
            form.add("duration", "5");
            form.add("events", "template=ALL");

            webClient
                    .post(recordingRequestURL())
                    .sendForm(
                            form,
                            ar -> {
                                if (assertRequestStatus(ar, postResponse)) {
                                    postResponse.complete(ar.result().bodyAsJsonObject());
                                }
                            });

            activeRecording = postResponse.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Wait some time to get more recording data
            Thread.sleep(5_000);

            // Get a report for the above recording
            CompletableFuture<JsonObject> reportResponse = new CompletableFuture<>();
            webClient
                    .get(activeRecording.getString("reportUrl"))
                    .putHeader(HttpHeaders.ACCEPT.toString(), HttpMimeType.JSON.mime())
                    .send(
                            ar -> {
                                if (assertRequestStatus(ar, reportResponse)) {
                                    MatcherAssert.assertThat(
                                            ar.result().statusCode(),
                                            Matchers.both(Matchers.greaterThanOrEqualTo(200))
                                                    .and(Matchers.lessThan(400)));
                                    MatcherAssert.assertThat(
                                            ar.result()
                                                    .getHeader(HttpHeaders.CONTENT_TYPE.toString()),
                                            Matchers.equalTo("application/json;charset=UTF-8"));
                                    reportResponse.complete(ar.result().bodyAsJsonObject());
                                }
                            });
            JsonObject jsonResponse = reportResponse.get();
            MatcherAssert.assertThat(jsonResponse, Matchers.notNullValue());
            MatcherAssert.assertThat(
                    jsonResponse.getMap(),
                    Matchers.is(Matchers.aMapWithSize(Matchers.greaterThan(0))));
        } finally {
            if (activeRecording != null) {
                // Clean up recording
                CompletableFuture<JsonObject> deleteActiveRecResponse = new CompletableFuture<>();
                webClient
                        .delete(
                                String.format(
                                        "%s/%d",
                                        recordingRequestURL(), activeRecording.getLong("remoteId")))
                        .send(
                                ar -> {
                                    if (assertRequestStatus(ar, deleteActiveRecResponse)) {
                                        deleteActiveRecResponse.complete(
                                                ar.result().bodyAsJsonObject());
                                    }
                                });
                deleteActiveRecResponse.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @Disabled
    void testGetArchivedReport() throws Exception {
        JsonObject activeRecording = null;
        CompletableFuture<String> saveRecordingResp = new CompletableFuture<>();
        String savedRecordingName = null;

        try {
            // Create a recording
            CompletableFuture<JsonObject> postResponse = new CompletableFuture<>();
            MultiMap form = MultiMap.caseInsensitiveMultiMap();
            form.add("recordingName", TEST_RECORDING_NAME);
            form.add("duration", "5");
            form.add("events", "template=ALL");

            webClient
                    .post(recordingRequestURL())
                    .sendForm(
                            form,
                            ar -> {
                                if (assertRequestStatus(ar, postResponse)) {
                                    postResponse.complete(ar.result().bodyAsJsonObject());
                                }
                            });

            activeRecording = postResponse.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Wait some time to get more recording data
            Thread.sleep(5_000);

            // Save the recording to archive
            webClient
                    .patch(
                            String.format(
                                    "%s/%d",
                                    recordingRequestURL(), activeRecording.getLong("remoteId")))
                    .putHeader(HttpHeaders.CONTENT_TYPE.toString(), "text/plain;charset=UTF-8")
                    .sendBuffer(
                            Buffer.buffer("SAVE"),
                            ar -> {
                                if (assertRequestStatus(ar, saveRecordingResp)) {
                                    MatcherAssert.assertThat(
                                            ar.result().statusCode(),
                                            Matchers.both(Matchers.greaterThanOrEqualTo(200))
                                                    .and(Matchers.lessThan(400)));
                                    MatcherAssert.assertThat(
                                            ar.result()
                                                    .getHeader(HttpHeaders.CONTENT_TYPE.toString()),
                                            Matchers.equalTo("text/plain;charset=UTF-8"));
                                    saveRecordingResp.complete(ar.result().bodyAsString());
                                }
                            });

            savedRecordingName = saveRecordingResp.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Get a report for the archived recording
            CompletableFuture<JsonObject> archiveResponse = new CompletableFuture<>();
            webClient
                    .get(String.format("%s/%s", "/api/v1/reports", savedRecordingName))
                    .putHeader(HttpHeaders.ACCEPT.toString(), HttpMimeType.JSON.mime())
                    .send(
                            ar -> {
                                if (assertRequestStatus(ar, archiveResponse)) {
                                    MatcherAssert.assertThat(
                                            ar.result().statusCode(),
                                            Matchers.both(Matchers.greaterThanOrEqualTo(200))
                                                    .and(Matchers.lessThan(400)));
                                    MatcherAssert.assertThat(
                                            ar.result()
                                                    .getHeader(HttpHeaders.CONTENT_TYPE.toString()),
                                            Matchers.equalTo("application/json;charset=UTF-8"));
                                    archiveResponse.complete(ar.result().bodyAsJsonObject());
                                }
                            });
            JsonObject jsonResponse = archiveResponse.get();
            MatcherAssert.assertThat(jsonResponse, Matchers.notNullValue());
            MatcherAssert.assertThat(
                    jsonResponse.getMap(),
                    Matchers.is(Matchers.aMapWithSize(Matchers.greaterThan(0))));
        } finally {
            if (activeRecording != null) {
                // Clean up recording
                CompletableFuture<JsonObject> deleteActiveRecResponse = new CompletableFuture<>();
                webClient
                        .delete(
                                String.format(
                                        "%s/%d",
                                        recordingRequestURL(), activeRecording.getLong("remoteId")))
                        .send(
                                ar -> {
                                    if (assertRequestStatus(ar, deleteActiveRecResponse)) {
                                        deleteActiveRecResponse.complete(
                                                ar.result().bodyAsJsonObject());
                                    }
                                });
                deleteActiveRecResponse.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }

            CompletableFuture<JsonObject> deleteArchivedRecResp = new CompletableFuture<>();
            webClient
                    .delete(String.format("%s/%s", archiveListRequestURL(), savedRecordingName))
                    .send(
                            ar -> {
                                if (assertRequestStatus(ar, deleteArchivedRecResp)) {
                                    MatcherAssert.assertThat(
                                            ar.result().statusCode(), Matchers.equalTo(200));
                                    deleteArchivedRecResp.complete(ar.result().bodyAsJsonObject());
                                }
                            });
            try {
                deleteArchivedRecResp.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException e) {
                logger.error(
                        new ITestCleanupFailedException(
                                String.format(
                                        "Failed to delete archived recording %s",
                                        savedRecordingName),
                                e));
            }
        }
    }

    @Test
    @Disabled("TODO query parameter filter is not implemented")
    void testGetFilteredActiveReport() throws Exception {
        JsonObject activeRecording = null;
        try {
            // Create a recording
            CompletableFuture<JsonObject> postResponse = new CompletableFuture<>();
            MultiMap form = MultiMap.caseInsensitiveMultiMap();
            form.add("recordingName", TEST_RECORDING_NAME);
            form.add("duration", "5");
            form.add("events", "template=ALL");

            webClient
                    .post(recordingRequestURL())
                    .sendForm(
                            form,
                            ar -> {
                                if (assertRequestStatus(ar, postResponse)) {
                                    postResponse.complete(ar.result().bodyAsJsonObject());
                                }
                            });

            activeRecording = postResponse.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Wait some time to get more recording data
            Thread.sleep(5_000);

            // Get a report for the above recording
            CompletableFuture<JsonObject> reportResponse = new CompletableFuture<>();
            webClient
                    .get(activeRecording.getString("reportUrl"))
                    .addQueryParam("filter", "heap")
                    .putHeader(HttpHeaders.ACCEPT.toString(), HttpMimeType.JSON.mime())
                    .send(
                            ar -> {
                                if (assertRequestStatus(ar, reportResponse)) {
                                    MatcherAssert.assertThat(
                                            ar.result().statusCode(),
                                            Matchers.both(Matchers.greaterThanOrEqualTo(200))
                                                    .and(Matchers.lessThan(400)));
                                    MatcherAssert.assertThat(
                                            ar.result()
                                                    .getHeader(HttpHeaders.CONTENT_TYPE.toString()),
                                            Matchers.equalTo("application/json;charset=UTF-8"));
                                    reportResponse.complete(ar.result().bodyAsJsonObject());
                                }
                            });
            JsonObject jsonResponse = reportResponse.get();
            MatcherAssert.assertThat(jsonResponse, Matchers.notNullValue());
            MatcherAssert.assertThat(jsonResponse.getMap(), Matchers.is(Matchers.aMapWithSize(8)));
            Assertions.assertTrue(jsonResponse.containsKey("HeapContent"));
            Assertions.assertTrue(jsonResponse.containsKey("StringDeduplication"));
            Assertions.assertTrue(jsonResponse.containsKey("PrimitiveToObjectConversion"));
            Assertions.assertTrue(jsonResponse.containsKey("GcFreedRatio"));
            Assertions.assertTrue(jsonResponse.containsKey("HighGc"));
            Assertions.assertTrue(jsonResponse.containsKey("HeapDump"));
            Assertions.assertTrue(jsonResponse.containsKey("Allocations.class"));
            Assertions.assertTrue(jsonResponse.containsKey("LowOnPhysicalMemory"));
            for (var obj : jsonResponse.getMap().entrySet()) {
                var value = JsonObject.mapFrom(obj.getValue());
                Assertions.assertTrue(value.containsKey("score"));
                MatcherAssert.assertThat(
                        value.getDouble("score"),
                        Matchers.anyOf(
                                Matchers.equalTo(-1d),
                                Matchers.equalTo(-2d),
                                Matchers.equalTo(-3d),
                                Matchers.both(Matchers.lessThanOrEqualTo(100d))
                                        .and(Matchers.greaterThanOrEqualTo(0d))));
                Assertions.assertTrue(value.containsKey("name"));
                MatcherAssert.assertThat(
                        value.getString("name"), Matchers.not(Matchers.emptyOrNullString()));
                Assertions.assertTrue(value.containsKey("topic"));
                MatcherAssert.assertThat(
                        value.getString("topic"), Matchers.not(Matchers.emptyOrNullString()));
                Assertions.assertTrue(value.containsKey("description"));
            }
        } finally {
            if (activeRecording != null) {
                // Clean up recording
                CompletableFuture<JsonObject> deleteActiveRecResponse = new CompletableFuture<>();
                webClient
                        .delete(
                                String.format(
                                        "%s/%d",
                                        recordingRequestURL(), activeRecording.getLong("id")))
                        .send(
                                ar -> {
                                    if (assertRequestStatus(ar, deleteActiveRecResponse)) {
                                        deleteActiveRecResponse.complete(
                                                ar.result().bodyAsJsonObject());
                                    }
                                });
                deleteActiveRecResponse.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void testGetReportThrowsWithNonExistentRecordingName() throws Exception {
        CompletableFuture<JsonObject> response = new CompletableFuture<>();
        webClient
                .get(String.format("%s/%s", archivedReportRequestURL(), TEST_RECORDING_NAME))
                .putHeader(HttpHeaders.ACCEPT.toString(), HttpMimeType.HTML.mime())
                .send(
                        ar -> {
                            assertRequestStatus(ar, response);
                        });
        ExecutionException ex =
                Assertions.assertThrows(
                        ExecutionException.class,
                        () -> response.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        MatcherAssert.assertThat(
                ((HttpException) ex.getCause()).getStatusCode(), Matchers.equalTo(404));
        MatcherAssert.assertThat(ex.getCause().getMessage(), Matchers.equalTo("Not Found"));
    }
}
