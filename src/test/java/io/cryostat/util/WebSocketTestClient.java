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
package io.cryostat.util;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.vertx.core.json.JsonObject;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import org.jboss.logging.Logger;

public class WebSocketTestClient {

    private static final Logger logger = Logger.getLogger(WebSocketTestClient.class);

    private final WebSocketClient client;
    private final Supplier<URI> wsUriSupplier;
    private Session session;
    private boolean connected = false;

    public WebSocketTestClient(Supplier<URI> wsUriSupplier) {
        this.wsUriSupplier = wsUriSupplier;
        this.client = new WebSocketClient();
    }

    public WebSocketTestClient(URI wsUri) {
        this(() -> wsUri);
    }

    public void connect() throws IOException, DeploymentException {
        if (connected && session != null && session.isOpen()) {
            logger.warn("WebSocket already connected");
            return;
        }
        URI wsUri = wsUriSupplier.get();
        session = ContainerProvider.getWebSocketContainer().connectToServer(client, wsUri);
        connected = true;
        logger.infov("WebSocket connected to {0}", wsUri);
    }

    public void clearMessages() {
        int cleared = client.messageQueue.size();
        client.messageQueue.clear();
        logger.debugv("Cleared {0} messages from WebSocket queue", cleared);
    }

    public void disconnect() throws IOException {
        if (session != null && session.isOpen()) {
            session.close();
            logger.info("WebSocket disconnected");
        }
        client.messageQueue.clear();
        connected = false;
    }

    public boolean isConnected() {
        return connected && session != null && session.isOpen();
    }

    /**
     * Wait for a WebSocket notification with the specified category.
     *
     * @param category The notification category to wait for
     * @return The notification as a JsonObject
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws TimeoutException if the timeout is exceeded
     */
    public JsonObject expectNotification(String category)
            throws InterruptedException, TimeoutException {
        return expectNotification(category, Duration.ofSeconds(30), v -> true);
    }

    /**
     * Wait for a WebSocket notification with the specified category and timeout.
     *
     * @param category The notification category to wait for
     * @param timeout The maximum time to wait
     * @return The notification as a JsonObject
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws TimeoutException if the timeout is exceeded
     */
    public JsonObject expectNotification(String category, Duration timeout)
            throws InterruptedException, TimeoutException {
        return expectNotification(category, timeout, v -> true);
    }

    /**
     * Wait for a WebSocket notification with the specified category and predicate.
     *
     * @param category The notification category to wait for
     * @param predicate Additional filter for the notification
     * @return The notification as a JsonObject
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws TimeoutException if the timeout is exceeded
     */
    public JsonObject expectNotification(String category, Predicate<JsonObject> predicate)
            throws InterruptedException, TimeoutException {
        return expectNotification(category, Duration.ofSeconds(30), predicate);
    }

    /**
     * Wait for a WebSocket notification with the specified category, timeout, and predicate.
     *
     * @param category The notification category to wait for
     * @param timeout The maximum time to wait
     * @param predicate Additional filter for the notification
     * @return The notification as a JsonObject
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws TimeoutException if the timeout is exceeded
     */
    public JsonObject expectNotification(
            String category, Duration timeout, Predicate<JsonObject> predicate)
            throws InterruptedException, TimeoutException {

        if (!isConnected()) {
            throw new IllegalStateException(
                    "WebSocket not connected. Call connect() in @BeforeAll before using this"
                            + " method.");
        }

        long now = System.nanoTime();
        long deadline = now + timeout.toNanos();
        logger.infov(
                "Waiting up to {0} for WebSocket notification with category={1}",
                timeout, category);

        do {
            now = System.nanoTime();
            String msg = client.messageQueue.poll(1, TimeUnit.SECONDS);
            if (msg == null) {
                continue;
            }

            JsonObject obj = new JsonObject(msg);
            logger.debugv("Received WebSocket message: {0}", obj.encodePrettily());

            String msgCategory = obj.getJsonObject("meta").getString("category");
            if (category.equals(msgCategory) && predicate.test(obj)) {
                logger.infov("Found matching notification for category={0}", category);
                return obj;
            }
            Thread.sleep(500);
            client.messageQueue.put(msg);
        } while (now < deadline);

        throw new TimeoutException(
                String.format("Timeout waiting for WebSocket notification: category=%s", category));
    }

    @ClientEndpoint
    private static class WebSocketClient {
        private final LinkedBlockingDeque<String> messageQueue = new LinkedBlockingDeque<>();
        private final Logger logger = Logger.getLogger(getClass());

        @OnMessage
        void message(String msg) {
            logger.debugv("WebSocket message received: {0}", msg);
            messageQueue.add(msg);
        }
    }
}
