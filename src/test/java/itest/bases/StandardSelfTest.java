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
package itest.bases;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.cryostat.util.HttpStatusCodeIdentifier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.security.auth.module.UnixSystem;
import io.vertx.core.AsyncResult;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.FileSystem;
import io.vertx.core.http.HttpMethod;
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

    public final Logger logger = Logger.getLogger(StandardSelfTest.class);
    public static final ObjectMapper mapper = new ObjectMapper();
    public static final int REQUEST_TIMEOUT_SECONDS = 30;
    public static final WebClient webClient = Utils.getWebClient();

    @BeforeAll
    public static void waitForJdp() {
        Logger logger = Logger.getLogger(StandardSelfTest.class);
        boolean found = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!found && System.nanoTime() < deadline) {
            logger.infov(
                    "Waiting for self-discovery at {0} via JDP...", getSelfReferenceConnectUrl());
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
                                                    queryFound.complete(
                                                            arr.size() == 1
                                                                    && Objects.equals(
                                                                            arr.getJsonObject(0)
                                                                                    .getString(
                                                                                            "connectUrl"),
                                                                            getSelfReferenceConnectUrl()));
                                                });
                            });
            try {
                found |= queryFound.get(500, TimeUnit.MILLISECONDS);
                Thread.sleep(1000);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                logger.warn(e);
            }
        }
        if (!found) {
            throw new RuntimeException();
        }
    }

    public static String getSelfReferenceConnectUrl() {
        URI listPath = URI.create("http://d/v3.0.0/libpod/containers/json");
        String query = "";
        try {

            query =
                    mapper.writeValueAsString(
                            Map.of("label", List.of("io.cryostat.component=cryostat3")));
        } catch (JsonProcessingException jpe) {
            throw new RuntimeException(jpe);
        }
        final String filter = query;
        try {
            CompletableFuture<String> hostnameFuture = new CompletableFuture<>();
            ForkJoinPool.commonPool()
                    .submit(
                            () -> {
                                webClient
                                        .request(
                                                HttpMethod.GET,
                                                getSocket(),
                                                80,
                                                "localhost",
                                                listPath.toString())
                                        .addQueryParam("filters", filter)
                                        .timeout(500)
                                        .as(BodyCodec.jsonArray())
                                        .send()
                                        .onSuccess(
                                                ar -> {
                                                    JsonArray response = ar.body();
                                                    JsonObject obj = response.getJsonObject(0);
                                                    // containerFuture.complete(obj);
                                                    String id = obj.getString("Id");
                                                    URI inspectPath =
                                                            URI.create(
                                                                    String.format(
                                                                            "http://d/v3.0.0/libpod/containers/%s/json",
                                                                            id));
                                                    webClient
                                                            .request(
                                                                    HttpMethod.GET,
                                                                    getSocket(),
                                                                    80,
                                                                    "localhost",
                                                                    inspectPath.toString())
                                                            .timeout(500)
                                                            .as(BodyCodec.jsonObject())
                                                            .send()
                                                            .onSuccess(
                                                                    ar2 -> {
                                                                        JsonObject json =
                                                                                ar2.body();
                                                                        JsonObject config =
                                                                                json.getJsonObject(
                                                                                        "Config");
                                                                        String hostname =
                                                                                config.getString(
                                                                                        "Hostname");
                                                                        hostnameFuture.complete(
                                                                                hostname);
                                                                    });
                                                });
                            });
            String hostname = hostnameFuture.get(2, TimeUnit.SECONDS);

            return String.format("service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi", hostname, 9091);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
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
