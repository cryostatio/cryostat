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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.cryostat.AbstractTransactionalTestBase;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class CustomTargetsTest extends AbstractTransactionalTestBase {

    private final ExecutorService worker = Executors.newCachedThreadPool();
    private String testJvmId;
    private Integer storedCredentialId;

    @BeforeEach
    void setupCustomTargetsTest() throws Exception {
        testJvmId = getJvmId(SELF_JMX_URL);
        storedCredentialId = null;
    }

    @AfterEach
    void cleanupCustomTargetsTest() throws Exception {
        // Clean up any credentials created during tests
        if (storedCredentialId != null) {
            given().basePath("/")
                    .when()
                    .delete("/api/v4/credentials/" + storedCredentialId)
                    .then()
                    .statusCode(204);
        }

        // Clean up any custom targets
        Response response =
                given().basePath("/").when().get("/api/v4/targets").then().extract().response();
        JsonArray targets = new JsonArray(response.body().asString());
        for (int i = 0; i < targets.size(); i++) {
            JsonObject target = targets.getJsonObject(i);
            if (SELF_JMX_URL.equals(target.getString("connectUrl"))) {
                given().basePath("/")
                        .when()
                        .delete("/api/v4/targets/" + target.getLong("id"))
                        .then()
                        .statusCode(204);
            }
        }
    }

    @Test
    void shouldBeAbleToTestTargetConnection() throws Exception {
        // Test dry-run target creation
        Response response =
                given().basePath("/")
                        .contentType(ContentType.JSON)
                        .body(
                                new JsonObject()
                                        .put("connectUrl", SELF_JMX_URL)
                                        .put("alias", "self")
                                        .encode())
                        .when()
                        .post("/api/v4/targets?dryrun=true")
                        .then()
                        .statusCode(202)
                        .extract()
                        .response();

        JsonObject body = new JsonObject(response.body().asString());
        MatcherAssert.assertThat(body.getString("connectUrl"), equalTo(SELF_JMX_URL));
        MatcherAssert.assertThat(body.getString("alias"), equalTo("self"));
        MatcherAssert.assertThat(body.getString("jvmId"), equalTo(testJvmId));

        // Verify no target was actually created
        Response listResponse =
                given().basePath("/").when().get("/api/v4/targets").then().extract().response();
        JsonArray list = new JsonArray(listResponse.body().asString());
        MatcherAssert.assertThat(list, notNullValue());
        MatcherAssert.assertThat(list.size(), equalTo(0));
    }

    @Test
    void shouldBeAbleToDefineTarget() throws Exception {
        String alias = UUID.randomUUID().toString();
        CountDownLatch latch = new CountDownLatch(2);

        // Set up WebSocket listeners for notifications
        Future<JsonObject> credentialsFuture =
                worker.submit(
                        () -> {
                            try {
                                return webSocketClient.expectNotification(
                                        "CredentialsStored", Duration.ofSeconds(30));
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                latch.countDown();
                            }
                        });

        Future<JsonObject> discoveryFuture =
                worker.submit(
                        () -> {
                            try {
                                return webSocketClient.expectNotification(
                                        "TargetJvmDiscovery",
                                        Duration.ofSeconds(30),
                                        o ->
                                                "FOUND"
                                                        .equals(
                                                                o.getJsonObject("message")
                                                                        .getJsonObject("event")
                                                                        .getString("kind")));
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                latch.countDown();
                            }
                        });

        Thread.sleep(500);

        // Create target with credentials
        Response response =
                given().basePath("/")
                        .contentType(ContentType.URLENC)
                        .formParam("connectUrl", SELF_JMX_URL)
                        .formParam("alias", alias)
                        .formParam("username", "username")
                        .formParam("password", "password")
                        .when()
                        .post("/api/v4/targets?storeCredentials=true")
                        .then()
                        .statusCode(201)
                        .extract()
                        .response();

        JsonObject body = new JsonObject(response.body().asString());
        latch.await(30, TimeUnit.SECONDS);

        MatcherAssert.assertThat(body.getString("connectUrl"), equalTo(SELF_JMX_URL));
        MatcherAssert.assertThat(body.getString("alias"), equalTo(alias));

        // Verify credentials notification
        JsonObject credentialsResult = credentialsFuture.get();
        JsonObject credentialsMessage = credentialsResult.getJsonObject("message");
        storedCredentialId = credentialsMessage.getInteger("id");

        MatcherAssert.assertThat(storedCredentialId, Matchers.any(Integer.class));
        MatcherAssert.assertThat(
                credentialsMessage.getString("matchExpression"),
                equalTo(String.format("target.connectUrl == \"%s\"", SELF_JMX_URL)));

        // Verify discovery notification
        JsonObject discoveryResult = discoveryFuture.get();
        JsonObject foundDiscoveryEvent =
                discoveryResult.getJsonObject("message").getJsonObject("event");
        MatcherAssert.assertThat(foundDiscoveryEvent.getString("kind"), equalTo("FOUND"));
        MatcherAssert.assertThat(
                foundDiscoveryEvent.getJsonObject("serviceRef").getString("connectUrl"),
                equalTo(SELF_JMX_URL));
        MatcherAssert.assertThat(
                foundDiscoveryEvent.getJsonObject("serviceRef").getString("alias"), equalTo(alias));

        // Verify target appears in list
        Response listResponse =
                given().basePath("/")
                        .when()
                        .get("/api/v4/targets")
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();
        JsonArray list = new JsonArray(listResponse.body().asString());
        MatcherAssert.assertThat(list, notNullValue());
        MatcherAssert.assertThat(list.size(), equalTo(1));

        JsonObject item = list.getJsonObject(0);
        MatcherAssert.assertThat(item.getString("jvmId"), equalTo(testJvmId));
        MatcherAssert.assertThat(item.getString("alias"), equalTo(alias));
        MatcherAssert.assertThat(item.getString("connectUrl"), equalTo(SELF_JMX_URL));
        MatcherAssert.assertThat(item.getJsonArray("labels"), equalTo(new JsonArray()));
        MatcherAssert.assertThat(
                item.getJsonObject("annotations"),
                equalTo(
                        new JsonObject(
                                Map.of(
                                        "platform",
                                        List.of(),
                                        "cryostat",
                                        List.of(
                                                Map.of(
                                                        "key",
                                                        "REALM",
                                                        "value",
                                                        "Custom Targets"))))));
    }

    @Test
    void shouldBeAbleToDeleteTarget() throws Exception {
        // First create a target to delete
        String alias = UUID.randomUUID().toString();
        Response createResponse =
                given().basePath("/")
                        .contentType(ContentType.URLENC)
                        .formParam("connectUrl", SELF_JMX_URL)
                        .formParam("alias", alias)
                        .when()
                        .post("/api/v4/targets")
                        .then()
                        .statusCode(201)
                        .extract()
                        .response();

        JsonObject createBody = new JsonObject(createResponse.body().asString());
        long targetId = createBody.getLong("id");

        CountDownLatch latch = new CountDownLatch(1);

        // Set up WebSocket listener for LOST notification
        worker.submit(
                () -> {
                    try {
                        JsonObject notification =
                                webSocketClient.expectNotification(
                                        "TargetJvmDiscovery", Duration.ofSeconds(30));
                        JsonObject event =
                                notification.getJsonObject("message").getJsonObject("event");
                        MatcherAssert.assertThat(event.getString("kind"), equalTo("LOST"));
                        MatcherAssert.assertThat(
                                event.getJsonObject("serviceRef").getString("connectUrl"),
                                equalTo(SELF_JMX_URL));
                        MatcherAssert.assertThat(
                                event.getJsonObject("serviceRef").getString("alias"),
                                equalTo(alias));
                        latch.countDown();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        // Delete the target
        given().basePath("/").when().delete("/api/v4/targets/" + targetId).then().statusCode(204);

        latch.await(30, TimeUnit.SECONDS);

        // Verify that no targets remain
        Response listResponse =
                given().basePath("/")
                        .when()
                        .get("/api/v4/targets")
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();
        JsonArray list = new JsonArray(listResponse.body().asString());
        MatcherAssert.assertThat(list, notNullValue());
        MatcherAssert.assertThat(list.size(), equalTo(0));
    }

    private String getJvmId(String connectUrl) {
        Response response =
                given().basePath("/")
                        .contentType(ContentType.JSON)
                        .body(
                                new JsonObject()
                                        .put("connectUrl", connectUrl)
                                        .put("alias", "temp")
                                        .encode())
                        .when()
                        .post("/api/v4/targets?dryrun=true")
                        .then()
                        .statusCode(202)
                        .extract()
                        .response();

        JsonObject body = new JsonObject(response.body().asString());
        return body.getString("jvmId");
    }
}
