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
package io.cryostat;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.enterprise.context.ApplicationScoped;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
@ServerEndpoint("/api/v1/notifications")
public class MessagingServer {

    private final Logger LOG = LoggerFactory.getLogger(getClass());
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper = new ObjectMapper();

    // TODO implement authentication check
    @OnOpen
    public void onOpen(Session session) {
        LOG.debug("Adding session {}", session.getId());
        sessions.add(session);
    }

    @OnClose
    public void onClose(Session session) {
        LOG.debug("Removing session {}", session.getId());
        sessions.remove(session);
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        LOG.error("Session error", throwable);
        try {
            LOG.error("Closing session {}", session.getId());
            session.close();
        } catch (IOException ioe) {
            ioe.printStackTrace(System.err);
            LOG.error("Unable to close session", ioe);
        }
    }

    @OnMessage
    public void onMessage(Session session, String message) {
        LOG.debug("[{}] message: {}", session.getId(), message);
    }

    public void broadcast(String category, Object message) {
        var map = Map.of("meta", Map.of("category", category), "message", message);
        LOG.info("Broadcasting: {}", map);
        sessions.forEach(
                s -> {
                    try {
                        s.getAsyncRemote()
                                .sendObject(
                                        mapper.writeValueAsString(map),
                                        result -> {
                                            if (result.getException() != null) {
                                                LOG.warn(
                                                        "Unable to send message: "
                                                                + result.getException());
                                            }
                                        });
                    } catch (JsonProcessingException e) {
                        LOG.error("Unable to send message", e);
                    }
                });
    }
}
