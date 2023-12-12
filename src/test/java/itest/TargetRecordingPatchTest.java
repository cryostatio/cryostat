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
import java.util.concurrent.TimeUnit;

import io.cryostat.util.HttpStatusCodeIdentifier;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.client.HttpResponse;
import itest.bases.StandardSelfTest;
import itest.util.ITestCleanupFailedException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class TargetRecordingPatchTest extends StandardSelfTest {

    static final String TEST_RECORDING_NAME = "someRecording";

    String recordingRequestUrl() {
        return String.format("/api/v1/targets/%s/recordings", getSelfReferenceConnectUrlEncoded());
    }

    String archivesRequestUrl() {
        return "/api/v1/recordings";
    }

    String optionsRequestUrl() {
        return String.format(
                "/api/v1/targets/%s/recordingOptions", getSelfReferenceConnectUrlEncoded());
    }

    @Test
    void testSaveEmptyRecordingDoesNotArchiveRecordingFile() throws Exception {
        try {
            MultiMap optionsForm = MultiMap.caseInsensitiveMultiMap();
            optionsForm.add("toDisk", "false");
            optionsForm.add("maxSize", "0");
            HttpResponse<Buffer> optionsResponse =
                    webClient.extensions().patch(optionsRequestUrl(), true, null, optionsForm, 5);
            MatcherAssert.assertThat(optionsResponse.statusCode(), Matchers.equalTo(200));

            // Create an empty recording
            MultiMap form = MultiMap.caseInsensitiveMultiMap();
            form.add("recordingName", TEST_RECORDING_NAME);
            form.add("duration", "5");
            form.add("events", "template=ALL");
            HttpResponse<Buffer> postResponse =
                    webClient.extensions().post(recordingRequestUrl(), true, form, 5);
            MatcherAssert.assertThat(postResponse.statusCode(), Matchers.equalTo(201));

            // Attempt to save the recording to archive
            HttpResponse<Buffer> saveResponse =
                    webClient
                            .extensions()
                            .patch(
                                    String.format(
                                            "%s/%s", recordingRequestUrl(), TEST_RECORDING_NAME),
                                    true,
                                    null,
                                    Buffer.buffer("SAVE"),
                                    5);
            MatcherAssert.assertThat(saveResponse.statusCode(), Matchers.equalTo(204));
            MatcherAssert.assertThat(saveResponse.body(), Matchers.equalTo(null));

            // Assert that no recording was archived
            CompletableFuture<JsonArray> listRespFuture1 = new CompletableFuture<>();
            webClient
                    .get(archivesRequestUrl())
                    .send(
                            ar -> {
                                if (assertRequestStatus(ar, listRespFuture1)) {
                                    listRespFuture1.complete(ar.result().bodyAsJsonArray());
                                }
                            });
            JsonArray listResp = listRespFuture1.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Assertions.assertTrue(listResp.isEmpty());

        } finally {
            // Clean up recording
            HttpResponse<Buffer> deleteResponse =
                    webClient
                            .extensions()
                            .delete(
                                    String.format(
                                            "%s/%s", recordingRequestUrl(), TEST_RECORDING_NAME),
                                    true,
                                    5);
            if (!HttpStatusCodeIdentifier.isSuccessCode(deleteResponse.statusCode())) {
                throw new ITestCleanupFailedException();
            }

            // Reset default target recording options
            MultiMap optionsForm = MultiMap.caseInsensitiveMultiMap();
            optionsForm.add("toDisk", "unset");
            optionsForm.add("maxSize", "unset");
            webClient.extensions().patch(optionsRequestUrl(), true, null, optionsForm, 5);
        }
    }
}
