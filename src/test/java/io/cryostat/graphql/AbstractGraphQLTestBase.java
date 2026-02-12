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
package io.cryostat.graphql;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;

import io.cryostat.AbstractTransactionalTestBase;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base class for GraphQL tests providing common functionality including: - Shared fields
 * (ObjectMapper, ExecutorService, constants) - Lifecycle methods (setup/cleanup) - Helper methods
 * for recording operations
 */
public abstract class AbstractGraphQLTestBase extends AbstractTransactionalTestBase {

    @Inject protected ObjectMapper mapper;

    protected final ExecutorService worker = ForkJoinPool.commonPool();

    protected static final long DATA_COLLECTION_DELAY_MS = 5_000L;
    protected static final long REQUEST_TIMEOUT_SECONDS = 30L;

    @BeforeEach
    public void setupGraphQLTest() throws Exception {
        if (selfId < 1) {
            defineSelfCustomTarget();
        }
    }

    protected JsonPath graphql(String query) {
        return given().log()
                .all()
                .when()
                .basePath("")
                .contentType(ContentType.JSON)
                .body(Map.of("query", query))
                .post("/api/v4/graphql")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .extract()
                .body()
                .jsonPath();
    }

    /**
     * Create a recording with the given name using GraphQL mutation. Waits for WebSocket
     * notification of recording creation.
     *
     * @param name the recording name
     * @return JsonObject containing the recording details from the notification
     */
    protected JsonObject createRecording(String name) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        JsonObject query = new JsonObject();
        query.put(
                "query",
                String.format(
                        "mutation { createRecording( nodes:{annotations: [\"REALM = Custom"
                            + " Targets\"]}, recording: { name: \"%s\", template: \"Profiling\","
                            + " templateType: \"TARGET\", duration: 30, continuous: false,"
                            + " archiveOnStop: true, toDisk: true }) { name state duration"
                            + " continuous metadata { labels { key value } } } }",
                        name));
        Future<JsonObject> f =
                worker.submit(
                        () -> {
                            try {
                                return webSocketClient.expectNotification(
                                        "ActiveRecordingCreated", Duration.ofSeconds(15));
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                latch.countDown();
                            }
                        });

        given().contentType(ContentType.JSON)
                .body(query.encode())
                .when()
                .post("/api/v4/graphql")
                .then()
                .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                .extract()
                .response();

        latch.await(30, TimeUnit.SECONDS);
        JsonObject notification = f.get(30, TimeUnit.SECONDS);
        return notification.getJsonObject("message").getJsonObject("recording");
    }

    /**
     * Stop the active recording using GraphQL query with doStop mutation. Waits for WebSocket
     * notification of recording stop.
     *
     * @return JsonObject containing the stopped recording details from the notification
     */
    protected JsonObject stopRecording() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        JsonObject query = new JsonObject();
        query.put(
                "query",
                "query { targetNodes(filter: { annotations: [ \"REALM = Custom Targets\"] })  {"
                    + " name target { recordings { active { data { doStop { name state } } } } } }"
                    + " }");

        Future<JsonObject> f2 =
                worker.submit(
                        () -> {
                            try {
                                return webSocketClient.expectNotification(
                                        "ActiveRecordingStopped", Duration.ofSeconds(15));
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                latch.countDown();
                            }
                        });

        given().contentType(ContentType.JSON)
                .body(query.encode())
                .when()
                .post("/api/v4/graphql")
                .then()
                .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                .extract()
                .response();

        latch.await(30, TimeUnit.SECONDS);
        JsonObject notification = f2.get(30, TimeUnit.SECONDS);
        return notification.getJsonObject("message").getJsonObject("recording");
    }

    /** Delete all active and archived recordings using GraphQL query with doDelete mutations. */
    protected void deleteRecording() throws Exception {
        JsonObject query = new JsonObject();
        query.put(
                "query",
                "query { targetNodes(filter: { annotations: [\"REALM = Custom Targets\"] }) { name"
                    + " target { recordings { active { data { name doDelete { name } } aggregate {"
                    + " count } } archived { data { name doDelete { name } } aggregate { count size"
                    + " } } } } } }");

        given().contentType(ContentType.JSON)
                .body(query.encode())
                .when()
                .post("/api/v4/graphql")
                .then()
                .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)));
    }

    /**
     * Restart a recording with the given name and replacement policy. Waits for WebSocket
     * notification of recording creation.
     *
     * @param name the recording name
     * @param replace the replacement policy (ALWAYS, NEVER, STOPPED)
     * @return JsonObject containing the recording details from the notification
     */
    protected JsonObject restartRecording(String name, String replace) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        JsonObject query = new JsonObject();
        query.put(
                "query",
                String.format(
                        "query { targetNodes(filter: { annotations: [\"REALM = Custom Targets\"] })"
                            + " { name target { doStartRecording ( recording: { name: \"%s\","
                            + " template: \"Profiling\", templateType: \"TARGET\", replace: \"%s\""
                            + " }) { name state } } } }",
                        name, replace));
        Future<JsonObject> f =
                worker.submit(
                        () -> {
                            try {
                                return webSocketClient.expectNotification(
                                        "ActiveRecordingCreated", Duration.ofSeconds(15));
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                latch.countDown();
                            }
                        });

        given().contentType(ContentType.JSON)
                .body(query.encode())
                .when()
                .post("/api/v4/graphql")
                .then()
                .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                .extract()
                .response();

        latch.await(30, TimeUnit.SECONDS);
        JsonObject notification = f.get(30, TimeUnit.SECONDS);
        return notification.getJsonObject("message").getJsonObject("recording");
    }

    /**
     * Attempt to restart a recording with the given name and replacement policy, expecting an error
     * response.
     *
     * @param name the recording name
     * @param replace the replacement policy (ALWAYS, NEVER, STOPPED)
     * @return JsonObject containing the error details
     */
    protected JsonObject restartRecordingWithError(String name, String replace) throws Exception {
        JsonObject query = new JsonObject();

        query.put(
                "query",
                String.format(
                        "query { targetNodes(filter: { annotations: [\"REALM = Custom Targets\"] })"
                            + " { name target { doStartRecording ( recording: { name: \"%s\","
                            + " template: \"Profiling\", templateType: \"TARGET\", replace: \"%s\""
                            + " }) { name state } } } }",
                        name, replace));

        Response response =
                given().contentType(ContentType.JSON)
                        .body(query.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        JsonObject responseObj = new JsonObject(response.body().asString());
        JsonArray errors = responseObj.getJsonArray("errors");
        return errors.getJsonObject(0);
    }
}
