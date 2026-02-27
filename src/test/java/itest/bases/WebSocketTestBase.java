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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.cryostat.util.WebSocketTestClient;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import jakarta.websocket.DeploymentException;
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

    protected static WebSocketTestClient webSocketClient;

    @BeforeAll
    static void setupTestBase() throws IOException, DeploymentException {
        // Integration tests don't have Quarkus injection, so construct URI from environment
        int port = Integer.parseInt(System.getenv().getOrDefault("QUARKUS_HTTP_PORT", "8081"));
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = port;

        webSocketClient =
                new WebSocketTestClient(
                        () ->
                                URI.create(
                                        String.format(
                                                "ws://localhost:%d/api/notifications", port)));
        webSocketClient.connect();
    }

    @BeforeEach
    void clearWebSocketNotifications() {
        webSocketClient.clearMessages();
    }

    @AfterAll
    static void tearDownWebSocketClient() throws IOException {
        if (webSocketClient != null) {
            webSocketClient.disconnect();
        }
    }
}
