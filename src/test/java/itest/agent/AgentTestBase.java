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
package itest.agent;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import io.cryostat.resources.AgentApplicationResource;

import io.vertx.core.json.JsonObject;
import itest.bases.WebSocketTestBase;
import jakarta.websocket.DeploymentException;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;

public class AgentTestBase extends WebSocketTestBase {

    static final Duration DISCOVERY_TIMEOUT = Duration.ofMinutes(5);
    static final String CONTINUOUS_TEMPLATE = "template=Continuous,type=TARGET";

    public static final Logger logger = Logger.getLogger(AgentTestBase.class);

    protected Target target;

    @BeforeEach
    void getTarget() throws InterruptedException, TimeoutException, ExecutionException {
        target = waitForDiscovery();
    }

    Target waitForDiscovery() {
        return waitForDiscovery(t -> t.agent() && AgentApplicationResource.ALIAS.equals(t.alias()));
    }

    Target waitForDiscovery(Predicate<Target> p) {
        Target result = waitForDiscoveryViaWebSocket(p);
        logger.infov("Discovered target: {0}", result);
        return result;
    }

    private Target waitForDiscoveryViaWebSocket(Predicate<Target> p) {
        try {
            JsonObject notification =
                    expectWebSocketNotification(
                            "TargetJvmDiscovery", DISCOVERY_TIMEOUT, msg -> matchesAgentAlias(msg));

            Target target = extractTargetFromNotification(notification);

            if (!p.test(target)) {
                throw new IllegalStateException(
                        "Target discovered via WebSocket but does not match predicate");
            }

            return target;
        } catch (IOException | DeploymentException | InterruptedException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean matchesAgentAlias(JsonObject msg) {
        JsonObject event = msg.getJsonObject("message").getJsonObject("event");
        if (!"FOUND".equals(event.getString("kind"))) {
            return false;
        }
        JsonObject serviceRef = event.getJsonObject("serviceRef");
        if (serviceRef == null) {
            return false;
        }
        String alias = serviceRef.getString("alias");
        return alias != null && alias.equals(AgentApplicationResource.ALIAS);
    }

    private Target extractTargetFromNotification(JsonObject notification) {
        JsonObject event = notification.getJsonObject("message").getJsonObject("event");
        JsonObject serviceRef = event.getJsonObject("serviceRef");

        long id = serviceRef.getLong("id");
        String jvmId = serviceRef.getString("jvmId");
        String connectUrl = serviceRef.getString("connectUrl");
        String alias = serviceRef.getString("alias");

        List<KeyValue> labels = extractKeyValueArray(serviceRef.getValue("labels"));
        Annotations annotations = extractAnnotations(serviceRef.getValue("annotations"));
        boolean agent = serviceRef.getBoolean("agent", false);

        return new Target(id, jvmId, connectUrl, alias, labels, annotations, agent);
    }

    private List<KeyValue> extractKeyValueArray(Object arrayObj) {
        if (arrayObj == null) {
            return List.of();
        }
        if (arrayObj instanceof io.vertx.core.json.JsonArray) {
            io.vertx.core.json.JsonArray array = (io.vertx.core.json.JsonArray) arrayObj;
            return array.stream()
                    .map(
                            item -> {
                                JsonObject obj = (JsonObject) item;
                                return new KeyValue(obj.getString("key"), obj.getString("value"));
                            })
                    .toList();
        }
        return List.of();
    }

    private Annotations extractAnnotations(Object annotationsObj) {
        if (annotationsObj == null) {
            return new Annotations(List.of(), List.of());
        }
        if (!(annotationsObj instanceof JsonObject)) {
            return new Annotations(List.of(), List.of());
        }

        JsonObject annotationsJson = (JsonObject) annotationsObj;
        List<KeyValue> cryostatList = extractKeyValueArray(annotationsJson.getValue("cryostat"));
        List<KeyValue> platformList = extractKeyValueArray(annotationsJson.getValue("platform"));

        return new Annotations(cryostatList, platformList);
    }

    record Target(
            long id,
            String jvmId,
            String connectUrl,
            String alias,
            List<KeyValue> labels,
            Annotations annotations,
            boolean agent) {}

    record Annotations(List<KeyValue> cryostat, List<KeyValue> platform) {}

    record KeyValue(String key, String value) {}
}
