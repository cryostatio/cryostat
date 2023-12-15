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
package itest.bases;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.cryostat.util.HttpStatusCodeIdentifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.AsyncResult;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.FileSystem;
import io.vertx.core.http.WebSocket;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.ext.web.handler.HttpException;
import itest.util.ITestCleanupFailedException;
import itest.util.Utils;
import itest.util.Utils.TestWebClient;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

public abstract class StandardSelfTest {

    public static final String SELF_JMX_URL = "service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi";
    public static final String SELFTEST_ALIAS = "selftest";
    private static final ExecutorService WORKER = Executors.newCachedThreadPool();
    public static final Logger logger = Logger.getLogger(StandardSelfTest.class);
    public static final ObjectMapper mapper = new ObjectMapper();
    public static final int REQUEST_TIMEOUT_SECONDS = 15;
    public static final int DISCOVERY_DEADLINE_SECONDS = 10;
    public static final TestWebClient webClient = Utils.getWebClient();
    public static volatile String selfCustomTargetLocation;

    @BeforeAll
    public static void waitForDiscovery() {
        waitForDiscovery(0);
    }

    @BeforeAll
    public static void assertPreconditions() throws Exception {
        assertNoRecordings();
    }

    @AfterAll
    public static void assertPostconditions() throws Exception {
        assertNoRecordings();
    }

    public static void assertNoRecordings() throws Exception {
        JsonArray listResp =
                webClient
                        .extensions()
                        .get(
                                String.format(
                                        "/api/v1/targets/%s/recordings",
                                        getSelfReferenceConnectUrlEncoded()),
                                true,
                                REQUEST_TIMEOUT_SECONDS)
                        .bodyAsJsonArray();
        if (!listResp.isEmpty()) {
            throw new ITestCleanupFailedException(
                    String.format("Unexpected recordings:\n%s", listResp.encodePrettily()));
        }
    }

    @AfterAll
    public static void deleteSelfCustomTarget()
            throws InterruptedException, ExecutionException, TimeoutException {
        if (!selfCustomTargetExists()) {
            return;
        }
        logger.infov("Deleting self custom target at {0}", selfCustomTargetLocation);
        String path = URI.create(selfCustomTargetLocation).getPath();
        HttpResponse<Buffer> resp =
                webClient.extensions().delete(path, true, REQUEST_TIMEOUT_SECONDS);
        logger.infov(
                "DELETE {0} -> HTTP {1} {2}: [{3}]",
                path, resp.statusCode(), resp.statusMessage(), resp.headers());
        selfCustomTargetLocation = null;
    }

    public static void waitForDiscovery(int otherTargetsCount) {
        final int totalTargets = otherTargetsCount + 1;
        boolean found = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DISCOVERY_DEADLINE_SECONDS);
        while (!found && System.nanoTime() < deadline) {
            logger.infov("Waiting for discovery to see at least {0} target(s)...", totalTargets);
            CompletableFuture<Boolean> queryFound = new CompletableFuture<>();
            WORKER.submit(
                    () -> {
                        webClient
                                .get("/api/v3/targets")
                                .as(BodyCodec.jsonArray())
                                .timeout(TimeUnit.SECONDS.toMillis(REQUEST_TIMEOUT_SECONDS))
                                .send(
                                        ar -> {
                                            if (ar.failed()) {
                                                logger.error(ar.cause());
                                                queryFound.completeExceptionally(ar.cause());
                                                return;
                                            }
                                            HttpResponse<JsonArray> resp = ar.result();
                                            JsonArray arr = resp.body();
                                            logger.infov(
                                                    "GET /api/v3/targets -> HTTP {1} {2}: [{3}] ->"
                                                            + " {4}",
                                                    selfCustomTargetLocation,
                                                    resp.statusCode(),
                                                    resp.statusMessage(),
                                                    resp.headers(),
                                                    arr);
                                            logger.infov(
                                                    "Discovered {0} targets: {1}", arr.size(), arr);
                                            queryFound.complete(arr.size() >= totalTargets);
                                        });
                    });
            try {
                found |= queryFound.get(5000, TimeUnit.MILLISECONDS);
                if (!found) {
                    tryDefineSelfCustomTarget();
                    Thread.sleep(TimeUnit.SECONDS.toMillis(DISCOVERY_DEADLINE_SECONDS) / 4);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        if (!found) {
            throw new RuntimeException("Timed out waiting for discovery");
        }
    }

    private static boolean selfCustomTargetExists() {
        if (StringUtils.isBlank(selfCustomTargetLocation)) {
            return false;
        }
        try {
            HttpResponse<Buffer> resp =
                    webClient
                            .extensions()
                            .get(selfCustomTargetLocation, true, REQUEST_TIMEOUT_SECONDS);
            logger.infov(
                    "POST /api/v2/targets -> HTTP {0} {1}: [{2}]",
                    resp.statusCode(), resp.statusMessage(), resp.headers());
            boolean result = HttpStatusCodeIdentifier.isSuccessCode(resp.statusCode());
            if (!result) {
                selfCustomTargetLocation = null;
            }
            return result;
        } catch (Exception e) {
            selfCustomTargetLocation = null;
            logger.error(e);
            return false;
        }
    }

    private static void tryDefineSelfCustomTarget() {
        if (selfCustomTargetExists()) {
            return;
        }
        logger.info("Trying to define self-referential custom target...");
        JsonObject self =
                new JsonObject(Map.of("connectUrl", SELF_JMX_URL, "alias", SELFTEST_ALIAS));
        HttpResponse<Buffer> resp;
        try {
            resp =
                    webClient
                            .extensions()
                            .post(
                                    "/api/v2/targets",
                                    true,
                                    Buffer.buffer(self.encode()),
                                    REQUEST_TIMEOUT_SECONDS);
            logger.infov(
                    "POST /api/v2/targets -> HTTP {0} {1}: [{2}]",
                    resp.statusCode(), resp.statusMessage(), resp.headers());
            if (!HttpStatusCodeIdentifier.isSuccessCode(resp.statusCode())) {
                throw new IllegalStateException(Integer.toString(resp.statusCode()));
            }
            selfCustomTargetLocation =
                    URI.create(resp.headers().get(HttpHeaders.LOCATION)).getPath();
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String getSelfReferenceConnectUrl() {
        try {
            tryDefineSelfCustomTarget();
            String path = URI.create(selfCustomTargetLocation).getPath();
            HttpResponse<Buffer> resp =
                    webClient.extensions().get(path, true, REQUEST_TIMEOUT_SECONDS);
            JsonObject body = resp.bodyAsJsonObject();
            logger.infov(
                    "GET {0} -> HTTP {1} {2}: [{3}] = {4}",
                    path, resp.statusCode(), resp.statusMessage(), resp.headers(), body);
            if (!HttpStatusCodeIdentifier.isSuccessCode(resp.statusCode())) {
                throw new IllegalStateException(Integer.toString(resp.statusCode()));
            }
            return body.getString("connectUrl");
        } catch (Exception e) {
            throw new RuntimeException("Could not determine own connectUrl", e);
        }
    }

    public static String getSelfReferenceConnectUrlEncoded() {
        return URLEncodedUtils.formatSegments(getSelfReferenceConnectUrl()).substring(1);
    }

    public static final Pair<String, String> VERTX_FIB_CREDENTIALS =
            Pair.of("admin", "adminpass123");

    public static CompletableFuture<JsonObject> expectNotification(
            String category, long timeout, TimeUnit unit)
            throws TimeoutException, ExecutionException, InterruptedException {
        logger.infov(
                "Waiting for a \"{0}\" message within the next {1} {2} ...",
                category, timeout, unit.name());
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        var a = new WebSocket[1];
        Utils.HTTP_CLIENT.webSocket(
                "ws://localhost/api/notifications",
                ar -> {
                    if (ar.failed()) {
                        future.completeExceptionally(ar.cause());
                        return;
                    }
                    a[0] = ar.result();
                    var ws = a[0];

                    ws.handler(
                                    m -> {
                                        JsonObject resp = m.toJsonObject();
                                        JsonObject meta = resp.getJsonObject("meta");
                                        String c = meta.getString("category");
                                        if (Objects.equals(c, category)) {
                                            logger.infov(
                                                    "Received expected \"{0}\" message", category);
                                            ws.end(unused -> future.complete(resp));
                                            ws.close();
                                        }
                                    })
                            // FIXME in the cryostat3 itests we DO use auth. The message below is
                            // copy-pasted from the old codebase, however cryostat3 does not yet
                            // perform authentication when websocket clients connect.

                            // just to initialize the connection - Cryostat expects
                            // clients to send a message after the connection opens
                            // to authenticate themselves, but in itests we don't
                            // use auth
                            .writeTextMessage("");
                });

        return future.orTimeout(timeout, unit).whenComplete((o, t) -> a[0].close());
    }

    public static <T> boolean assertRequestStatus(
            AsyncResult<HttpResponse<T>> result, CompletableFuture<?> future) {
        if (result.failed()) {
            result.cause().printStackTrace();
            future.completeExceptionally(result.cause());

            return false;
        }
        HttpResponse<T> response = result.result();
        if (!HttpStatusCodeIdentifier.isSuccessCode(response.statusCode())
                && !HttpStatusCodeIdentifier.isRedirectCode(response.statusCode())) {
            System.err.println("HTTP " + response.statusCode() + ": " + response.statusMessage());
            future.completeExceptionally(
                    new HttpException(response.statusCode(), response.statusMessage()));
            return false;
        }
        return true;
    }

    public static CompletableFuture<Path> downloadFile(String url, String name, String suffix) {
        return fireDownloadRequest(
                webClient.get(url), name, suffix, MultiMap.caseInsensitiveMultiMap());
    }

    public static CompletableFuture<Path> downloadFile(
            String url, String name, String suffix, MultiMap headers) {
        return fireDownloadRequest(webClient.get(url), name, suffix, headers);
    }

    public static CompletableFuture<Path> downloadFileAbs(String url, String name, String suffix) {
        return fireDownloadRequest(
                webClient.getAbs(url), name, suffix, MultiMap.caseInsensitiveMultiMap());
    }

    public static CompletableFuture<Path> downloadFileAbs(
            String url, String name, String suffix, MultiMap headers) {
        return fireDownloadRequest(webClient.getAbs(url), name, suffix, headers);
    }

    private static CompletableFuture<Path> fireDownloadRequest(
            HttpRequest<Buffer> request, String filename, String fileSuffix, MultiMap headers) {
        CompletableFuture<Path> future = new CompletableFuture<>();
        WORKER.submit(
                () -> {
                    request.putHeaders(headers)
                            .followRedirects(true)
                            .send(
                                    ar -> {
                                        if (ar.failed()) {
                                            future.completeExceptionally(ar.cause());
                                            return;
                                        }
                                        HttpResponse<Buffer> resp = ar.result();
                                        logger.infov(
                                                "GET {0} -> HTTP {1} {2}: [{3}]",
                                                request.uri(),
                                                resp.statusCode(),
                                                resp.statusMessage(),
                                                resp.headers());
                                        if (!(HttpStatusCodeIdentifier.isSuccessCode(
                                                resp.statusCode()))) {
                                            future.completeExceptionally(
                                                    new Exception(
                                                            String.format(
                                                                    "HTTP %d", resp.statusCode())));
                                            return;
                                        }
                                        FileSystem fs = Utils.getFileSystem();
                                        String file =
                                                fs.createTempFileBlocking(filename, fileSuffix);
                                        fs.writeFileBlocking(file, ar.result().body());
                                        future.complete(Paths.get(file));
                                    });
                });
        return future;
    }
}
