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
import java.net.URISyntaxException;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.cryostat.util.WebSocketTestClient;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.websocket.DeploymentException;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public abstract class WebSocketTestBase {

    protected static volatile ExecutorService WORKER = Executors.newCachedThreadPool();
    public static final Logger logger = Logger.getLogger(WebSocketTestBase.class);
    public static final ObjectMapper mapper;
    public static final int REQUEST_TIMEOUT_SECONDS = 30;

    static {
        mapper =
                new ObjectMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                        .setVisibility(PropertyAccessor.ALL, Visibility.ANY);
    }

    @TestHTTPResource("/api/notifications")
    URL notificationsUrl;

    protected WebSocketTestClient webSocketClient;

    @BeforeEach
    void setupTestBase() throws IOException, DeploymentException, URISyntaxException {
        URI notificationsUri = notificationsUrl.toURI();
        webSocketClient = new WebSocketTestClient(() -> notificationsUri);
        if (!webSocketClient.isConnected()) {
            webSocketClient.connect();
        }
        webSocketClient.clearMessages();
    }

    @BeforeEach
    void clearWebSocketNotifications() {
        if (webSocketClient != null) {
            webSocketClient.clearMessages();
        }
    }

    @AfterEach
    void tearDownWebSocketClient() throws IOException {
        if (webSocketClient != null) {
            webSocketClient.disconnect();
        }
    }

    @AfterAll
    static void shutDownWorker() throws InterruptedException {
        WORKER.shutdownNow();
        WORKER.awaitTermination(10, TimeUnit.SECONDS);
        WORKER = Executors.newCachedThreadPool();
    }
}
