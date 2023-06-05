/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package itest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.cryostat.util.HttpMimeType;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import itest.bases.StandardSelfTest;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

@QuarkusIntegrationTest
public class TargetEventsGetIT extends StandardSelfTest {

    static final String EVENT_REQ_URL =
            String.format("/api/v1/targets/%s/events", getSelfReferenceConnectUrlEncoded());
    static final String SEARCH_REQ_URL =
            String.format("/api/v2/targets/%s/events", getSelfReferenceConnectUrlEncoded());

    @Test
    public void testGetTargetEventsReturnsListOfEvents() throws Exception {
        CompletableFuture<HttpResponse<Buffer>> getResponse = new CompletableFuture<>();
        webClient
                .get(EVENT_REQ_URL)
                .basicAuthentication("user", "pass")
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
                .get(SEARCH_REQ_URL)
                .basicAuthentication("user", "pass")
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
                .get(String.format("%s?q=TargetConnectionOpened", SEARCH_REQ_URL))
                .basicAuthentication("user", "pass")
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
        expectedResults.put("name", "Target Connection Status");
        expectedResults.put(
                "typeId", "io.cryostat.net.TargetConnectionManager.TargetConnectionOpened");
        expectedResults.put("description", null);
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
                .get(String.format("%s?q=thisEventDoesNotExist", SEARCH_REQ_URL))
                .basicAuthentication("user", "pass")
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
