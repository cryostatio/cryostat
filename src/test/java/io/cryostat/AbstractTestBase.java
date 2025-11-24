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
package io.cryostat;

import static io.restassured.RestAssured.given;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import io.cryostat.resources.S3StorageResource;
import io.cryostat.util.HttpStatusCodeIdentifier;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

@QuarkusTestResource(S3StorageResource.class)
public abstract class AbstractTestBase {

    public static final String SELF_JMX_URL = "service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi";
    public static final String SELFTEST_ALIAS = "selftest";

    public static final String TEMPLATE_CONTINUOUS = "template=Continuous";

    @TestHTTPResource("/api/notifications")
    URI wsUri;

    @ConfigProperty(name = "storage.buckets.archives.name")
    String archivesBucket;

    @ConfigProperty(name = "test.storage.timeout", defaultValue = "5m")
    Duration storageTimeout;

    @ConfigProperty(name = "test.storage.retry", defaultValue = "5s")
    Duration storageRetry;

    @Inject S3Client storage;
    @Inject Logger logger;

    protected int selfId = -1;
    protected String selfJvmId = "";
    protected int selfRecordingId = -1;

    @BeforeEach
    void waitForStorage() throws InterruptedException {
        selfId = -1;
        selfJvmId = "";
        selfRecordingId = -1;
        long totalTime = 0;
        while (!bucketExists(archivesBucket)) {
            long start = System.nanoTime();
            Thread.sleep(storageRetry.toMillis());
            long elapsed = System.nanoTime() - start;
            totalTime += elapsed;
            if (Duration.ofNanos(totalTime).compareTo(storageTimeout) > 0) {
                throw new IllegalStateException("Storage took too long to become ready");
            }
        }
    }

    private boolean bucketExists(String bucket) {
        boolean exists = false;
        try {
            exists =
                    HttpStatusCodeIdentifier.isSuccessCode(
                            storage.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
                                    .sdkHttpResponse()
                                    .statusCode());
            logger.debugv("Storage bucket \"{0}\" exists? {1}", bucket, exists);
        } catch (Exception e) {
            logger.warn(e);
        }
        return exists;
    }

    protected int defineSelfCustomTarget() {
        var jp =
                given().basePath("/")
                        .log()
                        .all()
                        .contentType(ContentType.URLENC)
                        .formParam("connectUrl", SELF_JMX_URL)
                        .formParam("alias", SELFTEST_ALIAS)
                        .when()
                        .post("/api/v4/targets")
                        .then()
                        .log()
                        .all()
                        .extract()
                        .jsonPath();

        this.selfId = jp.getInt("id");
        this.selfJvmId = jp.getString("jvmId");

        return this.selfId;
    }

    protected JsonPath startSelfRecording(String name, String eventTemplate) {
        return startSelfRecording(name, Map.of("events", eventTemplate));
    }

    protected JsonPath startSelfRecording(String name, Map<String, Object> formParams) {
        // must have called defineSelfCustomTarget first!
        if (selfId < 1) {
            throw new IllegalStateException();
        }
        var spec = given().log().all().when().basePath("");
        formParams.forEach(spec::formParam);
        var jp =
                spec.pathParam("targetId", this.selfId)
                        .formParam("recordingName", name)
                        .post("/api/v4/targets/{targetId}/recordings")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .and()
                        .extract()
                        .body()
                        .jsonPath();
        this.selfRecordingId = jp.getInt("remoteId");
        return jp;
    }

    protected void cleanupSelfRecording() {
        if (selfId < 1 || selfRecordingId < 1) {
            throw new IllegalStateException();
        }
        given().log()
                .all()
                .when()
                .basePath("")
                .pathParams("targetId", selfId, "remoteId", selfRecordingId)
                .delete("/api/v4/targets/{targetId}/recordings/{remoteId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);
    }

    protected void cleanupSelfActiveAndArchivedRecordings() {
        if (selfId > 0) {
            cleanupActiveAndArchivedRecordingsForTarget(this.selfId);
        } else {
            cleanupSelfActiveAndArchivedRecordings();
        }
    }

    protected static void cleanupActiveAndArchivedRecordingsForTarget(int... ids) {
        cleanupActiveAndArchivedRecordingsForTarget(Arrays.stream(ids).boxed().toList());
    }

    protected static void cleanupActiveAndArchivedRecordingsForTarget(List<Integer> ids) {
        var variables = new HashMap<String, Object>();
        if (ids == null || ids.isEmpty()) {
            variables.put("targetIds", null);
        }
        given().basePath("/")
                .body(
                        Map.of(
                                "query",
                                """
                                query AbstractTestBaseCleanup($targetIds: [ BigInteger! ]) {
                                  targetNodes(filter: { targetIds: $targetIds }) {
                                    descendantTargets {
                                      target {
                                        recordings {
                                          active {
                                            data {
                                              doDelete {
                                                name
                                              }
                                            }
                                          }
                                          archived {
                                            data {
                                              doDelete {
                                                name
                                              }
                                            }
                                          }
                                        }
                                      }
                                    }
                                  }
                                }
                                """,
                                "variables",
                                variables))
                .contentType(ContentType.JSON)
                .log()
                .all()
                .when()
                .post("/api/v4/graphql")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);
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

    protected JsonObject expectWebSocketNotification(String category)
            throws IOException, DeploymentException, InterruptedException, TimeoutException {
        return expectWebSocketNotification(category, Duration.ofSeconds(30), v -> true);
    }

    protected JsonObject expectWebSocketNotification(String category, Duration timeout)
            throws IOException, DeploymentException, InterruptedException, TimeoutException {
        return expectWebSocketNotification(category, timeout, v -> true);
    }

    protected JsonObject expectWebSocketNotification(
            String category, Predicate<JsonObject> predicate)
            throws IOException, DeploymentException, InterruptedException, TimeoutException {
        return expectWebSocketNotification(category, Duration.ofSeconds(30), predicate);
    }

    protected JsonObject expectWebSocketNotification(
            String category, Duration timeout, Predicate<JsonObject> predicate)
            throws IOException, DeploymentException, InterruptedException, TimeoutException {
        long now = System.nanoTime();
        long deadline = now + timeout.toNanos();
        var client = new WebSocketClient();
        try (Session session =
                ContainerProvider.getWebSocketContainer().connectToServer(client, wsUri)) {
            do {
                now = System.nanoTime();
                String msg = client.wsMessages.poll(1, TimeUnit.SECONDS);
                if (msg == null) {
                    continue;
                }
                JsonObject obj = new JsonObject(msg);
                logger.infov("Received WebSocket message: {0}", obj.encodePrettily());
                String msgCategory = obj.getJsonObject("meta").getString("category");
                if (category.equals(msgCategory) && predicate.test(obj)) {
                    return obj;
                }
            } while (now < deadline);
        } finally {
            client.wsMessages.clear();
        }
        throw new TimeoutException();
    }

    @ClientEndpoint
    private class WebSocketClient {
        private final LinkedBlockingDeque<String> wsMessages = new LinkedBlockingDeque<>();

        @OnMessage
        void message(String msg) {
            wsMessages.add(msg);
        }
    }
}
