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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import itest.util.http.StoredCredential;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

@QuarkusTest
@WithTestResource(
        value = S3StorageResource.class,
        scope = TestResourceScope.MATCHING_RESOURCES,
        parallel = true)
public class CustomTargetsTest extends AbstractTransactionalTestBase {

    private static final int REQUEST_TIMEOUT_SECONDS = 30;

    private final ExecutorService worker = Executors.newCachedThreadPool();

    private String getJvmId() {
        // Create a temporary target to get the jvmId, then delete it
        Response createResponse =
                given().basePath("/")
                        .contentType(ContentType.JSON)
                        .body(Map.of("connectUrl", SELF_JMX_URL, "alias", "temp"))
                        .when()
                        .post("/api/v4/targets")
                        .then()
                        .statusCode(201)
                        .extract()
                        .response();

        JsonObject body = new JsonObject(createResponse.body().asString());
        String jvmId = body.getString("jvmId");
        Long tempId = body.getLong("id");

        // Delete the temporary target
        given().basePath("/").when().delete("/api/v4/targets/{id}", tempId).then().statusCode(204);

        return jvmId;
    }

    @Test
    void shouldBeAbleToTestTargetConnection()
            throws InterruptedException, ExecutionException, TimeoutException {
        String jvmId = getJvmId();

        Response response =
                given().basePath("/")
                        .contentType(ContentType.JSON)
                        .body(Map.of("connectUrl", SELF_JMX_URL, "alias", "self"))
                        .queryParam("dryrun", true)
                        .when()
                        .post("/api/v4/targets")
                        .then()
                        .statusCode(202)
                        .extract()
                        .response();

        JsonObject body = new JsonObject(response.body().asString());
        MatcherAssert.assertThat(body.getString("connectUrl"), Matchers.equalTo(SELF_JMX_URL));
        MatcherAssert.assertThat(body.getString("alias"), Matchers.equalTo("self"));
        MatcherAssert.assertThat(body.getString("jvmId"), Matchers.equalTo(jvmId));

        Response listResponse =
                given().basePath("/")
                        .when()
                        .get("/api/v4/targets")
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();
        JsonArray list = new JsonArray(listResponse.body().asString());
        MatcherAssert.assertThat(list, Matchers.notNullValue());
        MatcherAssert.assertThat(list.size(), Matchers.equalTo(0));
    }

    @Test
    void shouldBeAbleToDefineTarget()
            throws TimeoutException, ExecutionException, InterruptedException {
        // Get jvmId first, before creating the actual target
        String jvmId = getJvmId();
        String alias = UUID.randomUUID().toString();

        CountDownLatch latch = new CountDownLatch(2);

        Future<JsonObject> resultFuture1 =
                worker.submit(
                        (Callable<JsonObject>)
                                () -> {
                                    try {
                                        return expectWebSocketNotification(
                                                "CredentialsStored",
                                                Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS));
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    } finally {
                                        latch.countDown();
                                    }
                                });

        Future<JsonObject> resultFuture2 =
                worker.submit(
                        (Callable<JsonObject>)
                                () -> {
                                    try {
                                        return expectWebSocketNotification(
                                                "TargetJvmDiscovery",
                                                Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS),
                                                o ->
                                                        "FOUND"
                                                                .equals(
                                                                        o.getJsonObject("message")
                                                                                .getJsonObject(
                                                                                        "event")
                                                                                .getString(
                                                                                        "kind")));
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    } finally {
                                        latch.countDown();
                                    }
                                });

        Thread.sleep(500);

        Response response =
                given().basePath("/")
                        .contentType(ContentType.URLENC)
                        .formParam("connectUrl", SELF_JMX_URL)
                        .formParam("alias", alias)
                        .formParam("username", "username")
                        .formParam("password", "password")
                        .queryParam("storeCredentials", true)
                        .when()
                        .post("/api/v4/targets")
                        .then()
                        .statusCode(201)
                        .extract()
                        .response();

        JsonObject body = new JsonObject(response.body().asString());
        latch.await(30, TimeUnit.SECONDS);

        MatcherAssert.assertThat(body.getString("connectUrl"), Matchers.equalTo(SELF_JMX_URL));
        MatcherAssert.assertThat(body.getString("alias"), Matchers.equalTo(alias));

        JsonObject result1 = resultFuture1.get();

        JsonObject message = result1.getJsonObject("message");

        StoredCredential storedCredential =
                new StoredCredential(
                        message.getInteger("id"), message.getString("matchExpression"));

        MatcherAssert.assertThat(storedCredential.id, Matchers.any(Integer.class));
        MatcherAssert.assertThat(
                storedCredential.matchExpression,
                Matchers.equalTo(String.format("target.connectUrl == \"%s\"", SELF_JMX_URL)));

        JsonObject result2 = resultFuture2.get();
        JsonObject foundDiscoveryEvent = result2.getJsonObject("message").getJsonObject("event");
        MatcherAssert.assertThat(foundDiscoveryEvent.getString("kind"), Matchers.equalTo("FOUND"));
        MatcherAssert.assertThat(
                foundDiscoveryEvent.getJsonObject("serviceRef").getString("connectUrl"),
                Matchers.equalTo(SELF_JMX_URL));
        MatcherAssert.assertThat(
                foundDiscoveryEvent.getJsonObject("serviceRef").getString("alias"),
                Matchers.equalTo(alias));

        Response listResponse =
                given().basePath("/")
                        .when()
                        .get("/api/v4/targets")
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();
        JsonArray list = new JsonArray(listResponse.body().asString());
        MatcherAssert.assertThat(list, Matchers.notNullValue());
        MatcherAssert.assertThat(list.size(), Matchers.equalTo(1));
        JsonObject item = list.getJsonObject(0);
        MatcherAssert.assertThat(item.getString("jvmId"), Matchers.equalTo(jvmId));
        MatcherAssert.assertThat(item.getString("alias"), Matchers.equalTo(alias));
        MatcherAssert.assertThat(item.getString("connectUrl"), Matchers.equalTo(SELF_JMX_URL));
        MatcherAssert.assertThat(item.getJsonArray("labels"), Matchers.equalTo(new JsonArray()));
        MatcherAssert.assertThat(
                item.getJsonObject("annotations"),
                Matchers.equalTo(
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

        // Clean up the credential
        given().basePath("/")
                .when()
                .delete("/api/v4/credentials/{id}", storedCredential.id)
                .then()
                .statusCode(204);
    }

    @Test
    void shouldBeAbleToDeleteTarget()
            throws TimeoutException, ExecutionException, InterruptedException {
        // First create a target to delete
        String alias = UUID.randomUUID().toString();

        Response createResponse =
                given().basePath("/")
                        .contentType(ContentType.JSON)
                        .body(Map.of("connectUrl", SELF_JMX_URL, "alias", alias))
                        .when()
                        .post("/api/v4/targets")
                        .then()
                        .statusCode(201)
                        .extract()
                        .response();

        JsonObject createBody = new JsonObject(createResponse.body().asString());
        long targetId = createBody.getLong("id");

        CountDownLatch latch = new CountDownLatch(1);

        worker.submit(
                () -> {
                    try {
                        JsonObject notification =
                                expectWebSocketNotification(
                                        "TargetJvmDiscovery",
                                        Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS));
                        JsonObject event =
                                notification.getJsonObject("message").getJsonObject("event");
                        MatcherAssert.assertThat(event.getString("kind"), Matchers.equalTo("LOST"));
                        MatcherAssert.assertThat(
                                event.getJsonObject("serviceRef").getString("connectUrl"),
                                Matchers.equalTo(SELF_JMX_URL));
                        MatcherAssert.assertThat(
                                event.getJsonObject("serviceRef").getString("alias"),
                                Matchers.equalTo(alias));
                        latch.countDown();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        given().basePath("/")
                .when()
                .delete("/api/v4/targets/{id}", targetId)
                .then()
                .statusCode(204);

        latch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Verify that the target was deleted
        Response listResponse =
                given().basePath("/")
                        .when()
                        .get("/api/v4/targets")
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();
        JsonArray list = new JsonArray(listResponse.body().asString());
        MatcherAssert.assertThat(list, Matchers.notNullValue());
        // Should be 0 because transaction rollback will clean up
        MatcherAssert.assertThat(list.size(), Matchers.equalTo(0));
    }
}
