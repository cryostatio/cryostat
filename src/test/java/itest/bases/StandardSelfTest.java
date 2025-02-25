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
package itest.bases;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.cryostat.util.HttpStatusCodeIdentifier;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.codec.BodyCodec;
import itest.util.ITestCleanupFailedException;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.utils.URLEncodedUtils;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

public abstract class StandardSelfTest extends HttpClientTest {

    public static final String SELF_JMX_URL = "service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi";
    public static String SELF_JMX_URL_ENCODED =
            URLEncodedUtils.formatSegments(SELF_JMX_URL).substring(1);
    public static final String SELFTEST_ALIAS = "selftest";
    public static final Logger logger = Logger.getLogger(StandardSelfTest.class);
    public static final int DISCOVERY_DEADLINE_SECONDS = 60;
    public static volatile String selfCustomTargetLocation;

    @BeforeAll
    public static void waitForDiscovery() {
        waitForDiscovery(0);
    }

    @BeforeAll
    public static void assertPreconditions() throws Exception {
        assertNoRecordings();
    }

    @AfterAll
    public static void assertPostconditions() throws Exception {
        assertNoRecordings();
    }

    public static void assertNoRecordings() throws Exception {
        JsonArray listResp =
                webClient
                        .extensions()
                        .get(
                                String.format(
                                        "/api/v4/targets/%d/recordings",
                                        getSelfReferenceTargetId()),
                                REQUEST_TIMEOUT_SECONDS)
                        .bodyAsJsonArray();
        if (!listResp.isEmpty()) {
            throw new ITestCleanupFailedException(
                    String.format("Unexpected recordings:\n%s", listResp.encodePrettily()));
        }
    }

    @AfterAll
    public static void deleteSelfCustomTarget()
            throws InterruptedException, ExecutionException, TimeoutException {
        if (!selfCustomTargetExists()) {
            return;
        }
        logger.debugv("Deleting self custom target at {0}", selfCustomTargetLocation);
        String path = URI.create(selfCustomTargetLocation).getPath();
        HttpResponse<Buffer> resp = webClient.extensions().delete(path, REQUEST_TIMEOUT_SECONDS);
        logger.tracev(
                "DELETE {0} -> HTTP {1} {2}: [{3}]",
                path, resp.statusCode(), resp.statusMessage(), resp.headers());
        selfCustomTargetLocation = null;
    }

    public static void waitForDiscovery(int otherTargetsCount) {
        final int totalTargets = otherTargetsCount + 1;
        boolean found = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DISCOVERY_DEADLINE_SECONDS);
        while (!found && System.nanoTime() < deadline) {
            logger.debugv("Waiting for discovery to see at least {0} target(s)...", totalTargets);
            CompletableFuture<Boolean> queryFound = new CompletableFuture<>();
            WORKER.submit(
                    () -> {
                        webClient
                                .get("/api/v4/targets")
                                .as(BodyCodec.jsonArray())
                                .timeout(TimeUnit.SECONDS.toMillis(REQUEST_TIMEOUT_SECONDS))
                                .send(
                                        ar -> {
                                            if (ar.failed()) {
                                                logger.error(ar.cause());
                                                queryFound.completeExceptionally(ar.cause());
                                                return;
                                            }
                                            HttpResponse<JsonArray> resp = ar.result();
                                            JsonArray arr = resp.body();
                                            logger.tracev(
                                                    "GET /api/v4/targets -> HTTP {1} {2}: [{3}] ->"
                                                            + " {4}",
                                                    selfCustomTargetLocation,
                                                    resp.statusCode(),
                                                    resp.statusMessage(),
                                                    resp.headers(),
                                                    arr);
                                            logger.debugv(
                                                    "Discovered {0} targets: {1}", arr.size(), arr);
                                            queryFound.complete(arr.size() >= totalTargets);
                                        });
                    });
            try {
                found |= queryFound.get(5000, TimeUnit.MILLISECONDS);
                if (!found) {
                    tryDefineSelfCustomTarget();
                    Thread.sleep(TimeUnit.SECONDS.toMillis(DISCOVERY_DEADLINE_SECONDS) / 4);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        if (!found) {
            throw new RuntimeException("Timed out waiting for discovery");
        }
    }

    private static boolean selfCustomTargetExists() {
        if (StringUtils.isBlank(selfCustomTargetLocation)) {
            return false;
        }
        try {
            HttpResponse<Buffer> resp =
                    webClient.extensions().get(selfCustomTargetLocation, REQUEST_TIMEOUT_SECONDS);
            logger.tracev(
                    "POST /api/v4/targets -> HTTP {0} {1}: [{2}]",
                    resp.statusCode(), resp.statusMessage(), resp.headers());
            boolean result = HttpStatusCodeIdentifier.isSuccessCode(resp.statusCode());
            if (!result) {
                selfCustomTargetLocation = null;
            }
            return result;
        } catch (Exception e) {
            selfCustomTargetLocation = null;
            logger.error(e);
            return false;
        }
    }

    private static void tryDefineSelfCustomTarget() {
        if (selfCustomTargetExists()) {
            return;
        }
        logger.debug("Trying to define self-referential custom target...");
        JsonObject self =
                new JsonObject(Map.of("connectUrl", SELF_JMX_URL, "alias", SELFTEST_ALIAS));
        HttpResponse<Buffer> resp;
        try {
            resp =
                    webClient
                            .extensions()
                            .post(
                                    "/api/v4/targets",
                                    Buffer.buffer(self.encode()),
                                    REQUEST_TIMEOUT_SECONDS);
            logger.tracev(
                    "POST /api/v4/targets -> HTTP {0} {1}: [{2}]",
                    resp.statusCode(), resp.statusMessage(), resp.headers());
            if (!HttpStatusCodeIdentifier.isSuccessCode(resp.statusCode())) {
                throw new IllegalStateException(Integer.toString(resp.statusCode()));
            }
            selfCustomTargetLocation =
                    URI.create(resp.headers().get(HttpHeaders.LOCATION)).getPath();
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    public static long getSelfReferenceTargetId() {
        try {
            tryDefineSelfCustomTarget();
            String path = URI.create(selfCustomTargetLocation).getPath();
            HttpResponse<Buffer> resp = webClient.extensions().get(path, REQUEST_TIMEOUT_SECONDS);
            JsonObject body = resp.bodyAsJsonObject();
            logger.tracev(
                    "GET {0} -> HTTP {1} {2}: [{3}] = {4}",
                    path, resp.statusCode(), resp.statusMessage(), resp.headers(), body);
            if (!HttpStatusCodeIdentifier.isSuccessCode(resp.statusCode())) {
                throw new IllegalStateException(Integer.toString(resp.statusCode()));
            }
            return body.getLong("id");
        } catch (Exception e) {
            throw new RuntimeException("Could not determine own connectUrl", e);
        }
    }

    public static String getSelfReferenceConnectUrl() {
        try {
            tryDefineSelfCustomTarget();
            String path = URI.create(selfCustomTargetLocation).getPath();
            HttpResponse<Buffer> resp = webClient.extensions().get(path, REQUEST_TIMEOUT_SECONDS);
            JsonObject body = resp.bodyAsJsonObject();
            logger.tracev(
                    "GET {0} -> HTTP {1} {2}: [{3}] = {4}",
                    path, resp.statusCode(), resp.statusMessage(), resp.headers(), body);
            if (!HttpStatusCodeIdentifier.isSuccessCode(resp.statusCode())) {
                throw new IllegalStateException(Integer.toString(resp.statusCode()));
            }
            return body.getString("connectUrl");
        } catch (Exception e) {
            throw new RuntimeException("Could not determine own connectUrl", e);
        }
    }

    public static String getSelfReferenceConnectUrlEncoded() {
        return URLEncodedUtils.formatSegments(getSelfReferenceConnectUrl()).substring(1);
    }
}
