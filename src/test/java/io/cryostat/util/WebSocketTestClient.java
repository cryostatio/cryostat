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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
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

    public WebSocketTestClient(Supplier<URI> wsUriSupplier) {
        this.wsUriSupplier = wsUriSupplier;
        this.client = new WebSocketClient();
    }

    public WebSocketTestClient(URI wsUri) {
        this(() -> wsUri);
    }

    public void connect() throws IOException, DeploymentException {
        if (session != null && session.isOpen()) {
            logger.warn("WebSocket already connected");
            return;
        }
        URI wsUri = wsUriSupplier.get();
        session = ContainerProvider.getWebSocketContainer().connectToServer(client, wsUri);
        logger.infov("WebSocket connected to {0}", wsUri);
    }

    public void clearMessages() {
        client.lock.lock();
        try {
            int cleared = client.messageHistory.size();
            client.messageHistory.clear();
            logger.debugv("Cleared {0} messages from WebSocket history", cleared);
        } finally {
            client.lock.unlock();
        }
    }

    public void disconnect() throws IOException {
        if (session != null && session.isOpen()) {
            session.close();
            logger.info("WebSocket disconnected");
        }
        session = null;
        clearMessages();
    }

    public boolean isConnected() {
        return session != null && session.isOpen();
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
     * Wait for a WebSocket notification matching the specified category and predicate. This method
     * checks both historical messages (already received) and waits for future messages until the
     * timeout expires.
     *
     * @param category The notification category to wait for
     * @param timeout The maximum time to wait
     * @param predicate Additional filter for the notification
     * @return The matching notification as a JsonObject
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws TimeoutException if the timeout is exceeded without finding a match
     */
    public JsonObject expectNotification(
            String category, Duration timeout, Predicate<JsonObject> predicate)
            throws InterruptedException, TimeoutException {

        if (!isConnected()) {
            throw new IllegalStateException(
                    "WebSocket not connected. Call connect() in @BeforeAll before using this"
                            + " method.");
        }

        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        logger.infov(
                "Waiting up to {0} for WebSocket notification with category={1}",
                timeout, category);

        client.lock.lock();
        try {
            int lastCheckedIndex = 0;

            while (true) {
                for (int i = lastCheckedIndex; i < client.messageHistory.size(); i++) {
                    JsonObject obj = client.messageHistory.get(i);

                    String msgCategory = obj.getJsonObject("meta").getString("category");
                    if (category.equals(msgCategory) && predicate.test(obj)) {
                        logger.infov("Found matching notification for category={0}", category);
                        return obj;
                    }
                }

                lastCheckedIndex = client.messageHistory.size();

                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    break;
                }

                client.newMessageCondition.await(remainingNanos, TimeUnit.NANOSECONDS);
            }
        } finally {
            client.lock.unlock();
        }

        throw new TimeoutException(
                String.format(
                        "Timeout waiting for WebSocket notification: category=%s, timeout=%s",
                        category, timeout));
    }

    @ClientEndpoint
    private static class WebSocketClient {
        private final List<JsonObject> messageHistory = new CopyOnWriteArrayList<>();
        private final Lock lock = new ReentrantLock();
        private final Condition newMessageCondition = lock.newCondition();
        private final Logger logger = Logger.getLogger(getClass());

        @OnMessage
        void message(String msg) {
            JsonObject obj = new JsonObject(msg);
            logger.infov("WebSocket message received: {0}", obj.encodePrettily());
            lock.lock();
            try {
                messageHistory.add(obj);
                newMessageCondition.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }
}
