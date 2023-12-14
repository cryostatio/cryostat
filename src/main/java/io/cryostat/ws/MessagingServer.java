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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.jboss.logging.Logger;

@ApplicationScoped
@ServerEndpoint("/api/notifications")
public class MessagingServer {

    private static final String CLIENT_ACTIVITY_CATEGORY = "WsClientActivity";

    @Inject Logger logger;
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper = new ObjectMapper();

    @OnOpen
    public void onOpen(Session session) {
        logger.debugv("Adding session {0}", session.getId());
        sessions.add(session);
        broadcast(new Notification(CLIENT_ACTIVITY_CATEGORY, Map.of(session.getId(), "connected")));
    }

    @OnClose
    public void onClose(Session session) {
        logger.debugv("Removing session {0}", session.getId());
        sessions.remove(session);
        broadcast(
                new Notification(
                        CLIENT_ACTIVITY_CATEGORY, Map.of(session.getId(), "disconnected")));
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        logger.error("Session error", throwable);
        try {
            logger.errorv("Closing session {0}", session.getId());
            session.close();
        } catch (IOException ioe) {
            ioe.printStackTrace(System.err);
            logger.error("Unable to close session", ioe);
        }
        broadcast(
                new Notification(
                        CLIENT_ACTIVITY_CATEGORY, Map.of(session.getId(), "disconnected")));
    }

    @OnMessage
    public void onMessage(Session session, String message) {
        logger.debugv("[{0}] message: {1}", session.getId(), message);
    }

    @ConsumeEvent
    @Blocking
    void broadcast(Notification notification) {
        var map =
                Map.of(
                        "meta",
                        Map.of("category", notification.category()),
                        "message",
                        notification.message());
        logger.debugv("Broadcasting: {0}", map);
        sessions.forEach(
                s -> {
                    try {
                        s.getAsyncRemote()
                                .sendObject(
                                        mapper.writeValueAsString(map),
                                        result -> {
                                            if (result.getException() != null) {
                                                logger.warn(
                                                        "Unable to send message: "
                                                                + result.getException());
                                            }
                                        });
                    } catch (JsonProcessingException e) {
                        logger.error("Unable to send message", e);
                    }
                });
    }
}
