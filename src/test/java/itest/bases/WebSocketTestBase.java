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
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.vertx.core.json.JsonObject;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

public abstract class WebSocketTestBase {

    protected static final ExecutorService WORKER = Executors.newCachedThreadPool();
    public static final Logger logger = Logger.getLogger(WebSocketTestBase.class);
    public static final ObjectMapper mapper;
    public static final int REQUEST_TIMEOUT_SECONDS = 30;

    static {
        mapper =
                new ObjectMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                        .setVisibility(PropertyAccessor.ALL, Visibility.ANY);
    }

    static WebSocketClient WS_CLIENT;
    static Session WS_SESSION;

    @BeforeAll
    static void configureRestAssured() {
        RestAssured.baseURI = "http://localhost";
        int port = Integer.parseInt(System.getenv().getOrDefault("QUARKUS_HTTP_PORT", "8081"));
        RestAssured.port = port;
    }

    @BeforeAll
    static void setupWebSocketClient() throws IOException, DeploymentException {
        int port = Integer.parseInt(System.getenv().getOrDefault("QUARKUS_HTTP_PORT", "8081"));
        WS_CLIENT = new WebSocketClient();
        WS_SESSION =
                ContainerProvider.getWebSocketContainer()
                        .connectToServer(
                                WS_CLIENT,
                                URI.create(
                                        String.format(
                                                "ws://localhost:%d/api/notifications", port)));
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
            JsonObject obj = WS_CLIENT.msgQ.poll(1, TimeUnit.SECONDS);
            if (obj == null) {
                continue;
            }
            String msgCategory = obj.getJsonObject("meta").getString("category");
            if (category.equals(msgCategory) && predicate.test(obj)) {
                return obj;
            }
            Thread.sleep(500);
            WS_CLIENT.msgQ.put(obj);
        } while (now < deadline);
        throw new TimeoutException();
    }

    @ClientEndpoint
    static class WebSocketClient {
        private final LinkedBlockingDeque<JsonObject> msgQ = new LinkedBlockingDeque<>();
        private final Logger logger = Logger.getLogger(getClass());

        @OnMessage
        void message(String msg) {
            JsonObject obj = new JsonObject(msg);
            logger.info(obj.encodePrettily());
            msgQ.add(obj);
        }
    }
}
