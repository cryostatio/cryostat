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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.cryostat.resources.LocalStackResource;
import io.cryostat.util.HttpMimeType;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.codec.BodyCodec;
import itest.bases.StandardSelfTest;
import itest.util.ITestCleanupFailedException;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(LocalStackResource.class)
public class RecordingWorkflowTest extends StandardSelfTest {

    static final String TEST_RECORDING_NAME = "workflow_itest";
    static final String TARGET_ALIAS = "selftest";

    @Test
    public void testWorkflow() throws Exception {
        // Check preconditions
        CompletableFuture<JsonArray> listRespFuture1 = new CompletableFuture<>();
        webClient
                .get(
                        String.format(
                                "/api/v1/targets/%s/recordings",
                                getSelfReferenceConnectUrlEncoded()))
                .basicAuthentication("user", "pass")
                .followRedirects(true)
                .send(
                        ar -> {
                            if (assertRequestStatus(ar, listRespFuture1)) {
                                listRespFuture1.complete(ar.result().bodyAsJsonArray());
                            }
                        });
        JsonArray listResp = listRespFuture1.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Assertions.assertTrue(listResp.isEmpty());

        List<String> archivedRecordingFilenames = new ArrayList<>();
        try {
            // create an in-memory recording
            MultiMap form = MultiMap.caseInsensitiveMultiMap();
            form.add("recordingName", TEST_RECORDING_NAME);
            form.add("duration", "5");
            form.add("events", "template=ALL");
            webClient
                    .extensions()
                    .post(
                            String.format(
                                    "/api/v1/targets/%s/recordings",
                                    getSelfReferenceConnectUrlEncoded()),
                            true,
                            form,
                            REQUEST_TIMEOUT_SECONDS);

            // verify in-memory recording created
            CompletableFuture<JsonArray> listRespFuture2 = new CompletableFuture<>();
            webClient
                    .get(
                            String.format(
                                    "/api/v1/targets/%s/recordings",
                                    getSelfReferenceConnectUrlEncoded()))
                    .followRedirects(true)
                    .basicAuthentication("user", "pass")
                    .send(
                            ar -> {
                                if (assertRequestStatus(ar, listRespFuture2)) {
                                    listRespFuture2.complete(ar.result().bodyAsJsonArray());
                                }
                            });
            listResp = listRespFuture2.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            MatcherAssert.assertThat(
                    "list should have size 1 after recording creation",
                    listResp.size(),
                    Matchers.equalTo(1));
            JsonObject recordingInfo = listResp.getJsonObject(0);
            MatcherAssert.assertThat(
                    recordingInfo.getString("name"), Matchers.equalTo(TEST_RECORDING_NAME));
            MatcherAssert.assertThat(recordingInfo.getString("state"), Matchers.equalTo("RUNNING"));

            Thread.sleep(2_000L); // wait some time to save a portion of the recording

            // save a copy of the partial recording dump
            MultiMap saveHeaders = MultiMap.caseInsensitiveMultiMap();
            saveHeaders.add(HttpHeaders.CONTENT_TYPE.toString(), HttpMimeType.PLAINTEXT.mime());
            String archivedRecordingFilename =
                    webClient
                            .extensions()
                            .patch(
                                    String.format(
                                            "/api/v1/targets/%s/recordings/%s",
                                            getSelfReferenceConnectUrlEncoded(),
                                            TEST_RECORDING_NAME),
                                    true,
                                    saveHeaders,
                                    Buffer.buffer("SAVE"),
                                    REQUEST_TIMEOUT_SECONDS)
                            .bodyAsString();
            archivedRecordingFilenames.add(archivedRecordingFilename);

            // check that the in-memory recording list hasn't changed
            CompletableFuture<JsonArray> listRespFuture3 = new CompletableFuture<>();
            webClient
                    .get(
                            String.format(
                                    "/api/v1/targets/%s/recordings",
                                    getSelfReferenceConnectUrlEncoded()))
                    .followRedirects(true)
                    .basicAuthentication("user", "pass")
                    .send(
                            ar -> {
                                if (assertRequestStatus(ar, listRespFuture3)) {
                                    listRespFuture3.complete(ar.result().bodyAsJsonArray());
                                }
                            });
            listResp = listRespFuture3.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            MatcherAssert.assertThat(
                    "list should have size 1 after recording creation",
                    listResp.size(),
                    Matchers.equalTo(1));
            recordingInfo = listResp.getJsonObject(0);
            MatcherAssert.assertThat(
                    recordingInfo.getString("name"), Matchers.equalTo(TEST_RECORDING_NAME));
            MatcherAssert.assertThat(recordingInfo.getString("state"), Matchers.equalTo("RUNNING"));

            // verify saved recording created
            CompletableFuture<JsonArray> listRespFuture4 = new CompletableFuture<>();
            webClient
                    .get("/api/v1/recordings")
                    .basicAuthentication("user", "pass")
                    .followRedirects(true)
                    .send(
                            ar -> {
                                if (assertRequestStatus(ar, listRespFuture4)) {
                                    listRespFuture4.complete(ar.result().bodyAsJsonArray());
                                }
                            });
            listResp = listRespFuture4.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            MatcherAssert.assertThat(
                    "list-saved should have size 1 after recording save",
                    listResp.size(),
                    Matchers.equalTo(1));
            recordingInfo = listResp.getJsonObject(0);
            MatcherAssert.assertThat(
                    recordingInfo.getString("name"),
                    Matchers.matchesRegex(
                            TARGET_ALIAS + "_" + TEST_RECORDING_NAME + "_[\\d]{8}T[\\d]{6}Z.jfr"));
            String savedDownloadUrl = recordingInfo.getString("downloadUrl");

            Thread.sleep(3_000L); // wait for the dump to complete

            // verify the in-memory recording list has not changed, except recording is now stopped
            CompletableFuture<JsonArray> listRespFuture5 = new CompletableFuture<>();
            webClient
                    .get(
                            String.format(
                                    "/api/v1/targets/%s/recordings",
                                    getSelfReferenceConnectUrlEncoded()))
                    .followRedirects(true)
                    .basicAuthentication("user", "pass")
                    .send(
                            ar -> {
                                if (assertRequestStatus(ar, listRespFuture5)) {
                                    listRespFuture5.complete(ar.result().bodyAsJsonArray());
                                }
                            });
            listResp = listRespFuture5.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                    "list should have size 1 after wait period",
                    listResp.size(),
                    Matchers.equalTo(1));
            recordingInfo = listResp.getJsonObject(0);
            MatcherAssert.assertThat(
                    recordingInfo.getString("name"), Matchers.equalTo(TEST_RECORDING_NAME));
            MatcherAssert.assertThat(recordingInfo.getString("state"), Matchers.equalTo("STOPPED"));
            MatcherAssert.assertThat(recordingInfo.getInteger("duration"), Matchers.equalTo(5_000));

            // verify in-memory and saved recordings can be downloaded successfully and yield
            // non-empty recording binaries containing events, and that
            // the fully completed in-memory recording is larger than the saved partial copy
            String inMemoryDownloadUrl = recordingInfo.getString("downloadUrl");
            Path inMemoryDownloadPath =
                    downloadFile(inMemoryDownloadUrl, TEST_RECORDING_NAME, ".jfr")
                            .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            Path savedDownloadPath =
                    downloadFile(savedDownloadUrl, TEST_RECORDING_NAME + "_saved", ".jfr")
                            .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                    inMemoryDownloadPath.toFile().length(), Matchers.greaterThan(0L));
            MatcherAssert.assertThat(savedDownloadPath.toFile().length(), Matchers.greaterThan(0L));

            List<RecordedEvent> inMemoryEvents = RecordingFile.readAllEvents(inMemoryDownloadPath);
            List<RecordedEvent> savedEvents = RecordingFile.readAllEvents(savedDownloadPath);

            MatcherAssert.assertThat(
                    inMemoryEvents.size(), Matchers.greaterThan(savedEvents.size()));

            String reportUrl = recordingInfo.getString("reportUrl");

            HttpResponse<JsonObject> reportResponse =
                    webClient
                            .get(reportUrl)
                            .basicAuthentication("user", "pass")
                            .as(BodyCodec.jsonObject())
                            .send()
                            .toCompletionStage()
                            .toCompletableFuture()
                            .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                    reportResponse.statusCode(),
                    Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));
            JsonObject report = reportResponse.body();

            Map<?, ?> response = report.getMap();
            MatcherAssert.assertThat(response, Matchers.notNullValue());
            MatcherAssert.assertThat(
                    response, Matchers.is(Matchers.aMapWithSize(Matchers.greaterThan(8))));
        } finally {
            // Clean up what we created
            try {

                webClient
                        .extensions()
                        .delete(
                                String.format(
                                        "/api/v1/targets/%s/recordings/%s",
                                        getSelfReferenceConnectUrlEncoded(), TEST_RECORDING_NAME),
                                true,
                                REQUEST_TIMEOUT_SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                throw new ITestCleanupFailedException(
                        String.format("Failed to delete target recording %s", TEST_RECORDING_NAME),
                        e);
            }

            for (String savedRecording : archivedRecordingFilenames) {
                try {
                    webClient
                            .extensions()
                            .delete(
                                    String.format(
                                            "/api/beta/recordings/%s/%s",
                                            getSelfReferenceConnectUrlEncoded(), savedRecording),
                                    true,
                                    REQUEST_TIMEOUT_SECONDS);
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    throw new ITestCleanupFailedException(
                            String.format("Failed to delete archived recording %s", savedRecording),
                            e);
                }
            }
        }
    }
}
