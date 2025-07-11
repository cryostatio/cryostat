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
package io.cryostat.ws;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.jboss.logging.Logger;

/**
 * Implements a WebSocket-based notifications channel. Cryostat uses this notifications channel to
 * emit updates about work being done. Many of these notifications will correspond 1:1 with HTTP API
 * responses, but not all. The Cryostat Web graphical UI client uses these notifications to update
 * its graphical state responsively. Other clients can mostly ignore WebSocket notifications and use
 * HTTP API responses alone, except in the case of long-running async jobs where the HTTP API
 * response may initially be a Job UUID which will later be included in a WebSocket notification
 * indicating job completion.
 *
 * @see io.cryostat.recordings.LongRunningRequestGenerator
 * @see io.cryostat.reports.Reports
 */
@ApplicationScoped
@ServerEndpoint("/api/notifications")
public class MessagingServer {

    private static final String CLIENT_ACTIVITY_CATEGORY = "WsClientActivity";
    private static final ByteBuffer PING_MSG =
            ByteBuffer.wrap("ping".getBytes(StandardCharsets.UTF_8));

    @Inject ObjectMapper mapper;
    @Inject Logger logger;
    private final Set<Session> sessions = new CopyOnWriteArraySet<>();

    @OnOpen
    public void onOpen(Session session) throws InterruptedException {
        logger.debugv("Adding session {0}", session.getId());
        sessions.add(session);
        broadcast(new Notification(CLIENT_ACTIVITY_CATEGORY, Map.of(session.getId(), "connected")));
    }

    @OnClose
    public void onClose(Session session) throws InterruptedException {
        logger.debugv("Removing session {0}", session.getId());
        sessions.remove(session);
        broadcast(
                new Notification(
                        CLIENT_ACTIVITY_CATEGORY, Map.of(session.getId(), "disconnected")));
    }

    @OnError
    public void onError(Session session, Throwable throwable) throws InterruptedException {
        logger.error("Session error", throwable);
        try {
            logger.debugv("Closing session {0}", session.getId());
            session.close();
        } catch (IOException ioe) {
            logger.error("Unable to close session", ioe);
        }
        sessions.remove(session);
        broadcast(
                new Notification(
                        CLIENT_ACTIVITY_CATEGORY, Map.of(session.getId(), "disconnected")));
    }

    @OnMessage
    public void onMessage(Session session, String message) {
        logger.debugv("{0} message: \"{1}\"", session.getId(), message);
    }

    @ConsumeEvent(blocking = true, ordered = true)
    void broadcast(Notification notification) {
        var map =
                Map.of(
                        "meta",
                        Map.of("category", notification.category()),
                        "message",
                        notification.message());
        String json;
        try {
            json = mapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            logger.errorv(e, "Unable to serialize message to JSON: {0}", notification);
            return;
        }
        logger.debugv("Broadcasting: {0}", json);
        sessions.forEach(
                s ->
                        s.getAsyncRemote()
                                .sendText(
                                        json,
                                        h -> {
                                            if (!h.isOK()) {
                                                logger.warn(h.getException());
                                            }
                                        }));
    }

    @Scheduled(every = "${cryostat.websocket.ping-period:20s}")
    void pingClients() {
        sessions.forEach(
                session -> {
                    try {
                        session.getBasicRemote().sendPing(PING_MSG);
                    } catch (IOException e) {
                        logger.debug(e);
                    }
                });
    }
}
