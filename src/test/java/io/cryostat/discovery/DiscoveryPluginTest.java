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

    record Node(String name, String nodeType, Target target) {}

    record Target(URI connectUrl, String alias) {}
}
