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
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import io.cryostat.util.HttpStatusCodeIdentifier;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.AsyncResult;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.FileSystem;
import io.vertx.core.http.WebSocket;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.handler.HttpException;
import itest.util.Utils;
import itest.util.Utils.TestWebClient;
import org.jboss.logging.Logger;

public abstract class HttpClientTest {

    protected static final ExecutorService WORKER = Executors.newCachedThreadPool();
    public static final Logger logger = Logger.getLogger(HttpClientTest.class);
    public static final ObjectMapper mapper;
    public static final int REQUEST_TIMEOUT_SECONDS = 30;
    public static final TestWebClient webClient = Utils.getWebClient();

    static {
        mapper =
                new ObjectMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                        .setVisibility(PropertyAccessor.ALL, Visibility.ANY);
    }

    public static CompletableFuture<JsonObject> expectNotification(
            String category, long timeout, TimeUnit unit)
            throws TimeoutException, ExecutionException, InterruptedException {
        return expectNotification(category, o -> true, timeout, unit);
    }

    public static CompletableFuture<JsonObject> expectNotification(
            String category, Predicate<JsonObject> p, long timeout, TimeUnit unit)
            throws TimeoutException, ExecutionException, InterruptedException {
        logger.debugv(
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
                                        if (Objects.equals(c, category) && p.test(resp)) {
                                            logger.tracev(
                                                    "Received expected \"{0}\" message", category);
                                            ws.end(unused -> future.complete(resp));
                                            ws.close();
                                        }
                                    })
                            // FIXME in the cryostat itests we DO use auth. The message below is
                            // copy-pasted from the old codebase, however cryostat does not yet
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
                                        logger.tracev(
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
