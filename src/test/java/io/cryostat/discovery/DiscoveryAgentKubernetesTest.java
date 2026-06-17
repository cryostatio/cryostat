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
import static org.mockito.ArgumentMatchers.anyString;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.cryostat.AbstractTransactionalTestBase;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@QuarkusTest
public class DiscoveryAgentKubernetesTest extends AbstractTransactionalTestBase {

    @InjectMock KubeEndpointSlicesDiscovery k8sDiscovery;

    @BeforeEach
    void setupK8sMocks() {
        // No Kubernetes API in a unit test, so return a fixed single-Pod lineage and empty
        // metadata.
        Mockito.when(k8sDiscovery.getOwnershipLineage(anyString(), anyString(), anyString()))
                .thenAnswer(
                        inv -> {
                            DiscoveryNode pod = new DiscoveryNode();
                            pod.name = inv.getArgument(1);
                            pod.nodeType = "Pod";
                            pod.labels = new HashMap<>();
                            pod.children = new ArrayList<>();
                            pod.target = null;
                            return pod;
                        });
        Mockito.when(k8sDiscovery.getKubernetesMetadata(anyString(), anyString(), anyString()))
                .thenReturn(new KubeEndpointSlicesDiscovery.KubernetesMetadata(Map.of(), Map.of()));
    }

    @Test
    void testKubernetesAgentReregistrationPreservesTargetIdentity() {
        // cryostatio/cryostat#1604, KUBERNETES strategy (operator-injected Agents): re-registration
        // with an unchanged node set must preserve the Target, not recreate it.
        var realmName = "agent_k8s_identity_realm";
        var callback = "http://localhost:8081/health/liveness";
        var connectUrl = URI.create("http://localhost:8081");
        var target = new Target(connectUrl, "k8s-agent");
        var node = new Node("k8s-agent", NodeType.BaseNodeType.AGENT.getKind(), target);
        var requestBody =
                Map.of(
                        "realm",
                        realmName,
                        "callback",
                        callback,
                        "credential",
                        Map.of(
                                "matchExpression", "true",
                                "username", "user",
                                "password", "pass"),
                        "nodes",
                        List.of(node),
                        "fillStrategy",
                        "KUBERNETES",
                        "context",
                        Map.of("namespace", "test-ns", "nodetype", "Pod", "name", "test-pod"));

        var firstRegistration =
                given().log()
                        .all()
                        .when()
                        .body(requestBody)
                        .contentType(ContentType.JSON)
                        .post("/api/v4.3/discovery/agents")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .extract()
                        .jsonPath();
        var pluginId = firstRegistration.getString("id");
        MatcherAssert.assertThat(pluginId, Matchers.is(Matchers.not(Matchers.emptyOrNullString())));

        Long firstTargetId =
                QuarkusTransaction.requiringNew()
                        .call(
                                () ->
                                        io.cryostat.targets.Target.getTargetByConnectUrl(connectUrl)
                                                .id);
        Long firstCredentialId =
                QuarkusTransaction.requiringNew()
                        .call(
                                () ->
                                        DiscoveryPlugin.<DiscoveryPlugin>findById(
                                                        UUID.fromString(pluginId))
                                                .credential
                                                .id);

        // Re-register the same Agent with an identical node set (ex. a registration refresh).
        given().log()
                .all()
                .when()
                .body(requestBody)
                .contentType(ContentType.JSON)
                .post("/api/v4.3/discovery/agents")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        Long secondTargetId =
                QuarkusTransaction.requiringNew()
                        .call(
                                () ->
                                        io.cryostat.targets.Target.getTargetByConnectUrl(connectUrl)
                                                .id);
        Long secondCredentialId =
                QuarkusTransaction.requiringNew()
                        .call(
                                () ->
                                        DiscoveryPlugin.<DiscoveryPlugin>findById(
                                                        UUID.fromString(pluginId))
                                                .credential
                                                .id);

        MatcherAssert.assertThat(secondTargetId, Matchers.equalTo(firstTargetId));
        MatcherAssert.assertThat(secondCredentialId, Matchers.equalTo(firstCredentialId));
    }

    record Node(String name, String nodeType, Target target, List<?> children) {
        Node(String name, String nodeType, Target target) {
            this(name, nodeType, target, null);
        }
    }

    record Target(URI connectUrl, String alias) {}
}
