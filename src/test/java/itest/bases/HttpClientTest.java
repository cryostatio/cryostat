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

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
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
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.handler.HttpException;
import itest.util.Utils;
import itest.util.Utils.TestWebClient;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

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

    static WebSocketClient WS_CLIENT;
    static Session WS_SESSION;

    @BeforeAll
    static void setupWebSocketClient() throws IOException, DeploymentException {
        WS_CLIENT = new WebSocketClient();
        WS_SESSION =
                ContainerProvider.getWebSocketContainer()
                        .connectToServer(
                                WS_CLIENT, URI.create("ws://localhost:8081/api/notifications"));
    }

    @BeforeEach
    void clearWebSocketNotifications() {
        WS_CLIENT.msgQ.clear();
    }

    @AfterAll
    static void tearDownWebSocketClient() throws IOException {
        WS_SESSION.close();
    }

    protected JsonObject expectWebSocketNotification(String category)
            throws IOException, DeploymentException, InterruptedException, TimeoutException {
        return expectWebSocketNotification(category, Duration.ofSeconds(30), v -> true);
    }

    protected JsonObject expectWebSocketNotification(String category, Duration timeout)
            throws IOException, DeploymentException, InterruptedException, TimeoutException {
        return expectWebSocketNotification(category, timeout, v -> true);
    }

    protected JsonObject expectWebSocketNotification(
            String category, Predicate<JsonObject> predicate)
            throws IOException, DeploymentException, InterruptedException, TimeoutException {
        return expectWebSocketNotification(category, Duration.ofSeconds(30), predicate);
    }

    protected JsonObject expectWebSocketNotification(
            String category, Duration timeout, Predicate<JsonObject> predicate)
            throws IOException, DeploymentException, InterruptedException, TimeoutException {
        long now = System.nanoTime();
        long deadline = now + timeout.toNanos();
        logger.infov(
                "waiting up to {0} for a WebSocket notification with category={1}",
                timeout, category);
        do {
            now = System.nanoTime();
            String msg = WS_CLIENT.msgQ.poll(1, TimeUnit.SECONDS);
            if (msg == null) {
                continue;
            }
            JsonObject obj = new JsonObject(msg);
            String msgCategory = obj.getJsonObject("meta").getString("category");
            if (category.equals(msgCategory) && predicate.test(obj)) {
                return obj;
            }
            Thread.sleep(500);
            WS_CLIENT.msgQ.put(msg);
        } while (now < deadline);
        throw new TimeoutException();
    }

    @ClientEndpoint
    static class WebSocketClient {
        private final LinkedBlockingDeque<String> msgQ = new LinkedBlockingDeque<>();
        private final Logger logger = Logger.getLogger(getClass());

        @OnMessage
        void message(String msg) {
            logger.info(msg);
            msgQ.add(msg);
        }
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
        logger.infov("Attempting to download file \"{0}\"", filename);
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
                                            logger.infov(
                                                    "Failed to downloaded file \"{0}\": HTTP {1}"
                                                            + " {2}",
                                                    filename,
                                                    resp.statusCode(),
                                                    resp.statusMessage());
                                            future.completeExceptionally(
                                                    new Exception(
                                                            String.format(
                                                                    "HTTP %d %s",
                                                                    resp.statusCode(),
                                                                    resp.statusMessage())));
                                            return;
                                        }
                                        FileSystem fs = Utils.getFileSystem();
                                        String file =
                                                fs.createTempFileBlocking(filename, fileSuffix);
                                        fs.writeFileBlocking(file, ar.result().body());
                                        future.complete(Paths.get(file));
                                        logger.infov(
                                                "Successfully downloaded file \"{0}\" to {1}",
                                                filename, file);
                                    });
                });
        return future;
    }
}
