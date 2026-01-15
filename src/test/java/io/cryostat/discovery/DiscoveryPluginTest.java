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
package io.cryostat.discovery;

import static io.restassured.RestAssured.given;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.cryostat.AbstractTransactionalTestBase;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@QuarkusTest
public class DiscoveryPluginTest extends AbstractTransactionalTestBase {

    private static final String DISCOVERY_HEADER = "Cryostat-Discovery-Authentication";

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"invalid uri", "no.protocol.example.com"})
    void rejectsInvalidCallback(String callback) {
        var payload = new HashMap<>();
        payload.put("callback", callback);
        payload.put("realm", "test");
        given().log()
                .all()
                .when()
                .body(payload)
                .contentType(ContentType.JSON)
                .post("/api/v4/discovery")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsInvalidRealmName(String realm) {
        var payload = new HashMap<>();
        payload.put("callback", "http://example.com");
        payload.put("realm", realm);
        given().log()
                .all()
                .when()
                .body(payload)
                .contentType(ContentType.JSON)
                .post("/api/v4/discovery")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void rejectsPublishForUnregisteredPlugin() {
        given().log()
                .all()
                .when()
                .body(List.of())
                .contentType(ContentType.JSON)
                .post("/api/v4/discovery/abcd1234")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @Test
    void workflow() {
        // store credentials
        var credentialId =
                given().log()
                        .all()
                        .when()
                        .formParams(
                                Map.of(
                                        "username",
                                        "user",
                                        "password",
                                        "pass",
                                        "matchExpression",
                                        "target.connectUrl =="
                                                + " 'http://localhost:8081/health/liveness'"))
                        .contentType(ContentType.URLENC)
                        .post("/api/v4/credentials")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .contentType(ContentType.JSON)
                        .extract()
                        .jsonPath()
                        .getLong("id");

        // register
        var realmName = "test_realm";
        var callback =
                String.format(
                        "http://storedcredentials:%d@localhost:8081/health/liveness", credentialId);
        var registration =
                given().log()
                        .all()
                        .when()
                        .body(Map.of("realm", realmName, "callback", callback))
                        .contentType(ContentType.JSON)
                        .post("/api/v4/discovery")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .extract()
                        .jsonPath();
        var pluginId = registration.getString("id");
        var pluginToken = registration.getString("token");
        MatcherAssert.assertThat(pluginId, Matchers.is(Matchers.not(Matchers.emptyOrNullString())));
        MatcherAssert.assertThat(
                pluginToken, Matchers.is(Matchers.not(Matchers.emptyOrNullString())));

        // test what happens if we publish an update that we have no discoverable targets
        given().log()
                .all()
                .when()
                .body(List.of())
                .contentType(ContentType.JSON)
                .header(DISCOVERY_HEADER, pluginToken)
                .post(String.format("/api/v4/discovery/%s", pluginId))
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);

        // test what happens if we try to publish an invalid node - in this case, one containing no
        // target definition
        var node = new Node(null, NodeType.BaseNodeType.JVM.name(), null);
        given().log()
                .all()
                .when()
                .body(List.of(node))
                .contentType(ContentType.JSON)
                .header(DISCOVERY_HEADER, pluginToken)
                .post(String.format("/api/v4/discovery/%s", pluginId))
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);

        // test what happens if we publish an acceptable singleton list
        var target = new Target(URI.create("http://localhost:8081"), "test-node");
        node = new Node("test-node", NodeType.BaseNodeType.JVM.name(), target);
        given().log()
                .all()
                .when()
                .body(List.of(node))
                .contentType(ContentType.JSON)
                .header(DISCOVERY_HEADER, pluginToken)
                .post(String.format("/api/v4/discovery/%s", pluginId))
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);

        // refresh
        var refreshedRegistration =
                given().log()
                        .all()
                        .when()
                        .body(
                                Map.of(
                                        "id",
                                        pluginId,
                                        "token",
                                        pluginToken,
                                        "realm",
                                        realmName,
                                        "callback",
                                        callback))
                        .contentType(ContentType.JSON)
                        .post("/api/v4/discovery")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .extract()
                        .jsonPath();
        var refreshedPluginId = refreshedRegistration.getString("id");
        var refreshedPluginToken = refreshedRegistration.getString("token");
        MatcherAssert.assertThat(refreshedPluginId, Matchers.equalTo(pluginId));
        MatcherAssert.assertThat(refreshedPluginToken, Matchers.not(Matchers.equalTo(pluginToken)));

        // deregister
        given().log()
                .all()
                .when()
                .header(DISCOVERY_HEADER, pluginToken)
                .delete(String.format("/api/v4/discovery/%s", pluginId))
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);

        // double deregister
        given().log()
                .all()
                .when()
                .header(DISCOVERY_HEADER, pluginToken)
                .delete(String.format("/api/v4/discovery/%s", pluginId))
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);

        // publish update when not registered
        given().log()
                .all()
                .when()
                .body(List.of(node))
                .contentType(ContentType.JSON)
                .header(DISCOVERY_HEADER, pluginToken)
                .post(String.format("/api/v4/discovery/%s", pluginId))
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @Test
    void testPublishFlatNodeList() {
        // store credentials
        var credentialId =
                given().log()
                        .all()
                        .when()
                        .formParams(
                                Map.of(
                                        "username",
                                        "user",
                                        "password",
                                        "pass",
                                        "matchExpression",
                                        "target.connectUrl =="
                                                + " 'http://localhost:8081/health/liveness'"))
                        .contentType(ContentType.URLENC)
                        .post("/api/v4/credentials")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .contentType(ContentType.JSON)
                        .extract()
                        .jsonPath()
                        .getLong("id");

        // register
        var realmName = "flat_test_realm";
        var callback =
                String.format(
                        "http://storedcredentials:%d@localhost:8081/health/liveness", credentialId);
        var registration =
                given().log()
                        .all()
                        .when()
                        .body(Map.of("realm", realmName, "callback", callback))
                        .contentType(ContentType.JSON)
                        .post("/api/v4/discovery")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .extract()
                        .jsonPath();
        var pluginId = registration.getString("id");
        var pluginToken = registration.getString("token");

        var target1 = new Target(URI.create("http://localhost:8081"), "flat-node-1");
        var target2 = new Target(URI.create("http://localhost:8082"), "flat-node-2");
        var target3 = new Target(URI.create("http://localhost:8083"), "flat-node-3");
        var node1 = new Node("flat-node-1", NodeType.BaseNodeType.AGENT.name(), target1);
        var node2 = new Node("flat-node-2", NodeType.BaseNodeType.AGENT.name(), target2);
        var node3 = new Node("flat-node-3", NodeType.BaseNodeType.AGENT.name(), target3);

        given().log()
                .all()
                .when()
                .body(List.of(node1, node2, node3))
                .contentType(ContentType.JSON)
                .header(DISCOVERY_HEADER, pluginToken)
                .post(String.format("/api/v4/discovery/%s", pluginId))
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);

        // verify the plugin has the expected children
        var plugin =
                given().log()
                        .all()
                        .when()
                        .get(String.format("/api/v4/discovery_plugins/%s", pluginId))
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .extract()
                        .jsonPath();

        var children = plugin.getList("realm.children");
        MatcherAssert.assertThat(children, Matchers.hasSize(3));

        // cleanup
        given().log()
                .all()
                .when()
                .header(DISCOVERY_HEADER, pluginToken)
                .delete(String.format("/api/v4/discovery/%s", pluginId))
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);
    }

    @Test
    void testPublishHierarchicalNodeList() {
        // store credentials
        var credentialId =
                given().log()
                        .all()
                        .when()
                        .formParams(
                                Map.of(
                                        "username",
                                        "user",
                                        "password",
                                        "pass",
                                        "matchExpression",
                                        "target.connectUrl =="
                                                + " 'http://localhost:8081/health/liveness'"))
                        .contentType(ContentType.URLENC)
                        .post("/api/v4/credentials")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .contentType(ContentType.JSON)
                        .extract()
                        .jsonPath()
                        .getLong("id");

        // register
        var realmName = "hierarchical_test_realm";
        var callback =
                String.format(
                        "http://storedcredentials:%d@localhost:8081/health/liveness", credentialId);
        var registration =
                given().log()
                        .all()
                        .when()
                        .body(Map.of("realm", realmName, "callback", callback))
                        .contentType(ContentType.JSON)
                        .post("/api/v4/discovery")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .extract()
                        .jsonPath();
        var pluginId = registration.getString("id");
        var pluginToken = registration.getString("token");

        // publish a hierarchical Kubernetes structure:
        // Namespace -> Deployment -> ReplicaSet -> Pod -> Agent
        // (using AGENT type for HTTP URLs)
        var agent1 = new Target(URI.create("http://localhost:8081"), "agent-1");
        var agent2 = new Target(URI.create("http://localhost:8082"), "agent-2");

        var agentNode1 = new Node("agent-1", NodeType.BaseNodeType.AGENT.name(), agent1);
        var agentNode2 = new Node("agent-2", NodeType.BaseNodeType.AGENT.name(), agent2);

        // Pod level - contains Agent nodes
        var pod1 = new Node("pod-1", "Pod", null, List.of(agentNode1));
        var pod2 = new Node("pod-2", "Pod", null, List.of(agentNode2));

        // ReplicaSet level - contains Pod nodes
        var replicaSet1 = new Node("replicaset-1", "ReplicaSet", null, List.of(pod1, pod2));

        // Deployment level - contains ReplicaSet nodes
        var deployment1 = new Node("deployment-1", "Deployment", null, List.of(replicaSet1));

        // Namespace level - contains Deployment nodes
        var namespace1 = new Node("namespace-1", "Namespace", null, List.of(deployment1));

        given().log()
                .all()
                .when()
                .body(List.of(namespace1))
                .contentType(ContentType.JSON)
                .header(DISCOVERY_HEADER, pluginToken)
                .post(String.format("/api/v4/discovery/%s", pluginId))
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);

        // verify the plugin has the expected hierarchical structure
        var plugin =
                given().log()
                        .all()
                        .when()
                        .get(String.format("/api/v4/discovery_plugins/%s", pluginId))
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .extract()
                        .jsonPath();

        // Verify the entire hierarchy structure matches what was published
        // Level 1: Realm should have 1 Namespace child
        var realmChildren = plugin.getList("realm.children");
        MatcherAssert.assertThat(realmChildren, Matchers.hasSize(1));
        MatcherAssert.assertThat(
                plugin.getString("realm.children[0].name"), Matchers.equalTo("namespace-1"));
        MatcherAssert.assertThat(
                plugin.getString("realm.children[0].nodeType"), Matchers.equalTo("Namespace"));

        // Level 2: Namespace should have 1 Deployment child
        var namespaceChildren = plugin.getList("realm.children[0].children");
        MatcherAssert.assertThat(namespaceChildren, Matchers.hasSize(1));
        MatcherAssert.assertThat(
                plugin.getString("realm.children[0].children[0].name"),
                Matchers.equalTo("deployment-1"));
        MatcherAssert.assertThat(
                plugin.getString("realm.children[0].children[0].nodeType"),
                Matchers.equalTo("Deployment"));

        // Level 3: Deployment should have 1 ReplicaSet child
        var deploymentChildren = plugin.getList("realm.children[0].children[0].children");
        MatcherAssert.assertThat(deploymentChildren, Matchers.hasSize(1));
        MatcherAssert.assertThat(
                plugin.getString("realm.children[0].children[0].children[0].name"),
                Matchers.equalTo("replicaset-1"));
        MatcherAssert.assertThat(
                plugin.getString("realm.children[0].children[0].children[0].nodeType"),
                Matchers.equalTo("ReplicaSet"));

        // Level 4: ReplicaSet should have 2 Pod children
        var replicaSetChildren =
                plugin.getList("realm.children[0].children[0].children[0].children");
        MatcherAssert.assertThat(replicaSetChildren, Matchers.hasSize(2));

        // Verify both pods exist (order may vary)
        var podNames =
                List.of(
                        plugin.getString(
                                "realm.children[0].children[0].children[0].children[0].name"),
                        plugin.getString(
                                "realm.children[0].children[0].children[0].children[1].name"));
        MatcherAssert.assertThat(podNames, Matchers.containsInAnyOrder("pod-1", "pod-2"));

        // Level 5: Each Pod should have 1 Agent child (target node)
        var pod1Children =
                plugin.getList("realm.children[0].children[0].children[0].children[0].children");
        MatcherAssert.assertThat(pod1Children, Matchers.hasSize(1));
        MatcherAssert.assertThat(
                plugin.getString(
                        "realm.children[0].children[0].children[0].children[0].children[0].nodeType"),
                Matchers.equalTo("AGENT"));
        MatcherAssert.assertThat(
                plugin.get(
                        "realm.children[0].children[0].children[0].children[0].children[0].target"),
                Matchers.notNullValue());

        var pod2Children =
                plugin.getList("realm.children[0].children[0].children[0].children[1].children");
        MatcherAssert.assertThat(pod2Children, Matchers.hasSize(1));
        MatcherAssert.assertThat(
                plugin.getString(
                        "realm.children[0].children[0].children[0].children[1].children[0].nodeType"),
                Matchers.equalTo("AGENT"));
        MatcherAssert.assertThat(
                plugin.get(
                        "realm.children[0].children[0].children[0].children[1].children[0].target"),
                Matchers.notNullValue());

        // Verify the agent targets have correct URLs
        var agentUrls =
                List.of(
                        plugin.getString(
                                "realm.children[0].children[0].children[0].children[0].children[0].target.connectUrl"),
                        plugin.getString(
                                "realm.children[0].children[0].children[0].children[1].children[0].target.connectUrl"));
        MatcherAssert.assertThat(
                agentUrls,
                Matchers.containsInAnyOrder("http://localhost:8081", "http://localhost:8082"));

        // cleanup
        given().log()
                .all()
                .when()
                .header(DISCOVERY_HEADER, pluginToken)
                .delete(String.format("/api/v4/discovery/%s", pluginId))
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);
    }

    record Node(String name, String nodeType, Target target, List<?> children) {
        Node(String name, String nodeType, Target target) {
            this(name, nodeType, target, null);
        }
    }

    record Target(URI connectUrl, String alias) {}
}
