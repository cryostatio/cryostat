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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.cryostat.util.HttpStatusCodeIdentifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.security.auth.module.UnixSystem;
import io.vertx.core.AsyncResult;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.FileSystem;
import io.vertx.core.http.WebSocket;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.ext.web.handler.HttpException;
import itest.util.Utils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeAll;

public abstract class StandardSelfTest {

    public static final Logger logger = Logger.getLogger(StandardSelfTest.class);
    public static final ObjectMapper mapper = new ObjectMapper();
    public static final int REQUEST_TIMEOUT_SECONDS = 30;
    public static final WebClient webClient = Utils.getWebClient();

    @BeforeAll
    public static void waitForDiscovery() {
        boolean found = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!found && System.nanoTime() < deadline) {
            logger.infov("Waiting for discovery to see at least one target...");
            CompletableFuture<Boolean> queryFound = new CompletableFuture<>();
            ForkJoinPool.commonPool()
                    .submit(
                            () -> {
                                webClient
                                        .get("/api/v3/targets")
                                        .basicAuthentication("user", "pass")
                                        .as(BodyCodec.jsonArray())
                                        .timeout(500)
                                        .send(
                                                ar -> {
                                                    if (ar.failed()) {
                                                        logger.error(ar.cause());
                                                        return;
                                                    }
                                                    JsonArray arr = ar.result().body();
                                                    queryFound.complete(arr.size() >= 1);
                                                });
                            });
            try {
                found |= queryFound.get(500, TimeUnit.MILLISECONDS);
                if (!found) {
                    tryDefineSelfCustomTarget();
                }
                Thread.sleep(2000);
            } catch (Exception e) {
                logger.warn(e);
            }
        }
        if (!found) {
            throw new RuntimeException("Timed out waiting for discovery");
        }
    }

    private static void tryDefineSelfCustomTarget() {
        logger.info("Trying to define self-referential custom target...");
        try {
            JsonObject self =
                    new JsonObject(
                            Map.of(
                                    "connectUrl",
                                    "service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi",
                                    "alias",
                                    "self"));
            ForkJoinPool.commonPool()
                    .submit(
                            () -> {
                                webClient
                                        .post("/api/v2/targets")
                                        .basicAuthentication("user", "pass")
                                        .timeout(500)
                                        .sendJson(
                                                self,
                                                ar -> {
                                                    if (ar.failed()) {
                                                        logger.error(ar.cause());
                                                        return;
                                                    }
                                                    HttpResponse<Buffer> resp = ar.result();
                                                    logger.infov(
                                                            "HTTP {0} {1}: {2} [{3}]",
                                                            resp.statusCode(),
                                                            resp.statusMessage(),
                                                            resp.bodyAsString(),
                                                            resp.headers());
                                                });
                            });
        } catch (Exception e) {
            logger.warn(e);
        }
    }

    public static String getSelfReferenceConnectUrl() {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        ForkJoinPool.commonPool()
                .submit(
                        () -> {
                            webClient
                                    .get("/api/v3/targets")
                                    .basicAuthentication("user", "pass")
                                    .as(BodyCodec.jsonArray())
                                    .timeout(500)
                                    .send(
                                            ar -> {
                                                if (ar.failed()) {
                                                    logger.error(ar.cause());
                                                    return;
                                                }
                                                JsonArray arr = ar.result().body();
                                                future.complete(arr.getJsonObject(0));
                                            });
                        });
        try {
            JsonObject obj = future.get(1000, TimeUnit.MILLISECONDS);
            return obj.getString("connectUrl");
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
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        Utils.HTTP_CLIENT.webSocket(
                getNotificationsUrl().get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                ar -> {
                    if (ar.failed()) {
                        future.completeExceptionally(ar.cause());
                        return;
                    }
                    WebSocket ws = ar.result();

                    ws.handler(
                                    m -> {
                                        JsonObject resp = m.toJsonObject();
                                        JsonObject meta = resp.getJsonObject("meta");
                                        String c = meta.getString("category");
                                        if (Objects.equals(c, category)) {
                                            ws.end(unused -> future.complete(resp));
                                            ws.close();
                                        }
                                    })
                            // just to initialize the connection - Cryostat expects
                            // clients to send a message after the connection opens
                            // to authenticate themselves, but in itests we don't
                            // use auth
                            .writeTextMessage("");
                });

        return future.orTimeout(timeout, unit);
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

    private static Future<String> getNotificationsUrl() {
        CompletableFuture<String> future = new CompletableFuture<>();
        webClient
                .get("/api/v1/notifications_url")
                .send(
                        ar -> {
                            if (ar.succeeded()) {
                                future.complete(
                                        ar.result()
                                                .bodyAsJsonObject()
                                                .getString("notificationsUrl"));
                            } else {
                                future.completeExceptionally(ar.cause());
                            }
                        });
        return future;
    }

    public static CompletableFuture<Path> downloadFile(String url, String name, String suffix) {
        return fireDownloadRequest(
                webClient.get(url), name, suffix, MultiMap.caseInsensitiveMultiMap());
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
        request.putHeaders(headers)
                .basicAuthentication("user", "pass")
                .followRedirects(true)
                .send(
                        ar -> {
                            if (ar.failed()) {
                                future.completeExceptionally(ar.cause());
                                return;
                            }
                            HttpResponse<Buffer> resp = ar.result();
                            if (resp.statusCode() != 200) {
                                future.completeExceptionally(
                                        new Exception(String.format("HTTP %d", resp.statusCode())));
                                return;
                            }
                            FileSystem fs = Utils.getFileSystem();
                            String file = fs.createTempFileBlocking(filename, fileSuffix);
                            fs.writeFileBlocking(file, ar.result().body());
                            future.complete(Paths.get(file));
                        });
        return future;
    }

    private static String getSocketPath() {
        long uid = new UnixSystem().getUid();
        String socketPath = String.format("/run/user/%d/podman/podman.sock", uid);
        return socketPath;
    }

    private static SocketAddress getSocket() {
        return SocketAddress.domainSocketAddress(getSocketPath());
    }
}
