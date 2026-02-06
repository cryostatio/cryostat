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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.cryostat.resources.S3StorageResource;
import io.cryostat.util.HttpMimeType;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import itest.bases.StandardSelfTest;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
@WithTestResource(
        value = S3StorageResource.class,
        scope = TestResourceScope.MATCHING_RESOURCES,
        parallel = true)
public class RecordingWorkflowTest extends StandardSelfTest {

    static String TEST_RECORDING_NAME = "workflow_itest";
    static long TEST_REMOTE_ID;
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void cleanup()
            throws InterruptedException,
                    TimeoutException,
                    ExecutionException,
                    JsonProcessingException {
        cleanupSelfActiveAndArchivedRecordings();
    }

    @Test
    public void testWorkflow() throws Exception {
        // Check preconditions
        CompletableFuture<JsonArray> listRespFuture1 = new CompletableFuture<>();
        webClient
                .get(String.format("/api/v4/targets/%d/recordings", getSelfReferenceTargetId()))
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
        executor.schedule(
                () -> {
                    try {
                        // create an in-memory recording
                        MultiMap form = MultiMap.caseInsensitiveMultiMap();
                        form.add("recordingName", TEST_RECORDING_NAME);
                        form.add("duration", "20");
                        form.add("events", "template=ALL");
                        webClient
                                .extensions()
                                .post(
                                        String.format(
                                                "/api/v4/targets/%d/recordings",
                                                getSelfReferenceTargetId()),
                                        form,
                                        REQUEST_TIMEOUT_SECONDS);
                    } catch (InterruptedException | TimeoutException | ExecutionException e) {
                        throw new RuntimeException(e);
                    }
                },
                1,
                TimeUnit.SECONDS);
        expectWebSocketNotification("ActiveRecordingCreated");
        Thread.sleep(1000); // allow time for things to settle

        // verify in-memory recording created
        CompletableFuture<JsonArray> listRespFuture2 = new CompletableFuture<>();
        webClient
                .get(String.format("/api/v4/targets/%d/recordings", getSelfReferenceTargetId()))
                .followRedirects(true)
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
        long remoteId = recordingInfo.getLong("remoteId");
        TEST_REMOTE_ID = remoteId;
        MatcherAssert.assertThat(
                recordingInfo.getString("name"), Matchers.equalTo(TEST_RECORDING_NAME));
        MatcherAssert.assertThat(recordingInfo.getString("state"), Matchers.equalTo("RUNNING"));

        Thread.sleep(4_000L); // wait some time to save a portion of the recording

        // save a copy of the partial recording dump
        executor.schedule(
                () -> {
                    try {
                        MultiMap saveHeaders = MultiMap.caseInsensitiveMultiMap();
                        saveHeaders.add(
                                HttpHeaders.CONTENT_TYPE.toString(), HttpMimeType.PLAINTEXT.mime());
                        webClient
                                .extensions()
                                .patch(
                                        String.format(
                                                "/api/v4/targets/%d/recordings/%d",
                                                getSelfReferenceTargetId(), TEST_REMOTE_ID),
                                        saveHeaders,
                                        Buffer.buffer("SAVE"),
                                        REQUEST_TIMEOUT_SECONDS)
                                .bodyAsString();
                    } catch (InterruptedException | TimeoutException | ExecutionException e) {
                        throw new RuntimeException(e);
                    }
                },
                1,
                TimeUnit.SECONDS);
        // Wait for the archive request to conclude, the server won't block the client
        // while it performs the archive so we need to wait.
        JsonObject archiveNotification = expectWebSocketNotification("ArchiveRecordingSuccess");
        archivedRecordingFilenames.add(
                archiveNotification.getJsonObject("message").getString("recording").toString());

        // check that the in-memory recording list hasn't changed
        CompletableFuture<JsonArray> listRespFuture3 = new CompletableFuture<>();
        webClient
                .get(String.format("/api/v4/targets/%d/recordings", getSelfReferenceTargetId()))
                .followRedirects(true)
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
                .get("/api/v4/recordings")
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
                        SELFTEST_ALIAS + "_" + TEST_RECORDING_NAME + "_[\\d]{8}T[\\d]{6}Z.jfr"));
        String savedDownloadUrl = recordingInfo.getString("downloadUrl");

        expectWebSocketNotification(
                "ActiveRecordingStopped", Duration.ofMinutes(1)); // wait for the dump to complete
        Thread.sleep(1000); // allow time for things to settle

        // verify the in-memory recording list has not changed, except recording is now stopped
        CompletableFuture<JsonArray> listRespFuture5 = new CompletableFuture<>();
        webClient
                .get(String.format("/api/v4/targets/%d/recordings", getSelfReferenceTargetId()))
                .followRedirects(true)
                .send(
                        ar -> {
                            if (assertRequestStatus(ar, listRespFuture5)) {
                                listRespFuture5.complete(ar.result().bodyAsJsonArray());
                            }
                        });
        listResp = listRespFuture5.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
                "list should have size 1 after wait period", listResp.size(), Matchers.equalTo(1));
        recordingInfo = listResp.getJsonObject(0);
        MatcherAssert.assertThat(
                recordingInfo.getString("name"), Matchers.equalTo(TEST_RECORDING_NAME));
        MatcherAssert.assertThat(recordingInfo.getString("state"), Matchers.equalTo("STOPPED"));
        MatcherAssert.assertThat(recordingInfo.getInteger("duration"), Matchers.equalTo(20_000));

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
        MatcherAssert.assertThat(savedDownloadPath.toFile().length(), Matchers.greaterThan(0L));
        MatcherAssert.assertThat(
                inMemoryDownloadPath.toFile().length(),
                Matchers.greaterThan(savedDownloadPath.toFile().length()));

        List<RecordedEvent> inMemoryEvents = RecordingFile.readAllEvents(inMemoryDownloadPath);
        List<RecordedEvent> savedEvents = RecordingFile.readAllEvents(savedDownloadPath);

        MatcherAssert.assertThat(inMemoryEvents.size(), Matchers.greaterThan(savedEvents.size()));

        String reportUrl = recordingInfo.getString("reportUrl");
        executor.schedule(
                () -> {
                    try {
                        HttpResponse<Buffer> reportResponse =
                                webClient
                                        .get(reportUrl)
                                        .send()
                                        .toCompletionStage()
                                        .toCompletableFuture()
                                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        MatcherAssert.assertThat(
                                reportResponse.statusCode(),
                                Matchers.both(Matchers.greaterThanOrEqualTo(200))
                                        .and(Matchers.lessThan(300)));
                        MatcherAssert.assertThat(
                                reportResponse.getHeader("Location"), Matchers.notNullValue());
                        MatcherAssert.assertThat(
                                reportResponse.bodyAsString(), Matchers.notNullValue());
                    } catch (InterruptedException | TimeoutException | ExecutionException e) {
                        throw new RuntimeException(e);
                    }
                },
                1,
                TimeUnit.SECONDS);

        // Check that report generation concludes
        JsonObject notification = expectWebSocketNotification("ReportSuccess");
        MatcherAssert.assertThat(notification.getJsonObject("message"), Matchers.notNullValue());
    }
}
