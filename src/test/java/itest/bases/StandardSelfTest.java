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

import static io.restassured.RestAssured.given;

import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.cryostat.AbstractTestBase;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import itest.util.ITestCleanupFailedException;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.utils.URLEncodedUtils;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

public abstract class StandardSelfTest extends WebSocketTestBase {

    public static final String SELF_JMX_URL = "service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi";
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
    public static void postCleanup() throws Exception {
        cleanupSelfActiveAndArchivedRecordings();
        assertNoRecordings();
    }

    public static void assertNoRecordings() throws Exception {
        Response response =
                given().when()
                        .get("/api/v4/targets/{targetId}/recordings", getSelfReferenceTargetId())
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();
        JsonArray listResp = new JsonArray(response.body().asString());
        if (!listResp.isEmpty()) {
            throw new ITestCleanupFailedException(
                    String.format("Unexpected recordings:\n%s", listResp.encodePrettily()));
        }
    }

    @AfterAll
    public static void deleteSelfCustomTarget() {
        if (!selfCustomTargetExists()) {
            return;
        }
        logger.debugv("Deleting self custom target at {0}", selfCustomTargetLocation);
        String path = URI.create(selfCustomTargetLocation).getPath();
        given().when().delete(path).then().statusCode(204);
        logger.debugv("DELETE {0} -> HTTP 204", path);
        selfCustomTargetLocation = null;
    }

    public static void waitForDiscovery(int otherTargetsCount) {
        final int totalTargets = otherTargetsCount + 1;
        boolean found = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DISCOVERY_DEADLINE_SECONDS);
        while (!found && System.nanoTime() < deadline) {
            logger.debugv("Waiting for discovery to see at least {0} target(s)...", totalTargets);
            try {
                Response response =
                        given().when()
                                .get("/api/v4/targets")
                                .then()
                                .statusCode(200)
                                .extract()
                                .response();
                JsonArray arr = new JsonArray(response.body().asString());
                logger.debugv("Discovered {0} targets: {1}", arr.size(), arr);
                found = arr.size() >= totalTargets;
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
            Response response = given().when().get(selfCustomTargetLocation);
            logger.tracev("GET {0} -> HTTP {1}", selfCustomTargetLocation, response.statusCode());
            boolean result = response.statusCode() >= 200 && response.statusCode() < 300;
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
        Response response =
                given().contentType(ContentType.JSON)
                        .body(self.encode())
                        .when()
                        .post("/api/v4/targets")
                        .then()
                        .extract()
                        .response();
        logger.tracev("POST /api/v4/targets -> HTTP {0}", response.statusCode());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(String.format("HTTP %d", response.statusCode()));
        }
        selfCustomTargetLocation = URI.create(response.header("Location")).getPath();
    }

    public static long getSelfReferenceTargetId() {
        try {
            tryDefineSelfCustomTarget();
            String path = URI.create(selfCustomTargetLocation).getPath();
            Response response =
                    given().when().get(path).then().statusCode(200).extract().response();
            JsonObject body = new JsonObject(response.body().asString());
            logger.tracev("GET {0} -> HTTP {1} = {2}", path, response.statusCode(), body);
            return body.getLong("id");
        } catch (Exception e) {
            throw new RuntimeException("Could not determine own connectUrl", e);
        }
    }

    public static String getSelfReferenceJvmId() {
        try {
            tryDefineSelfCustomTarget();
            String path = URI.create(selfCustomTargetLocation).getPath();
            Response response =
                    given().when().get(path).then().statusCode(200).extract().response();
            JsonObject body = new JsonObject(response.body().asString());
            logger.tracev("GET {0} -> HTTP {1} = {2}", path, response.statusCode(), body);
            return body.getString("jvmId");
        } catch (Exception e) {
            throw new RuntimeException("Could not determine own connectUrl", e);
        }
    }

    public static String getSelfReferenceConnectUrl() {
        try {
            tryDefineSelfCustomTarget();
            String path = URI.create(selfCustomTargetLocation).getPath();
            Response response =
                    given().when().get(path).then().statusCode(200).extract().response();
            JsonObject body = new JsonObject(response.body().asString());
            logger.tracev("GET {0} -> HTTP {1} = {2}", path, response.statusCode(), body);
            return body.getString("connectUrl");
        } catch (Exception e) {
            throw new RuntimeException("Could not determine own connectUrl", e);
        }
    }

    public static String getSelfReferenceConnectUrlEncoded() {
        return URLEncodedUtils.formatSegments(getSelfReferenceConnectUrl()).substring(1);
    }

    protected static void cleanupSelfActiveAndArchivedRecordings() throws JsonProcessingException {
        if (selfCustomTargetExists()) {
            cleanupActiveAndArchivedRecordingsForTarget(getSelfReferenceTargetId());
        } else {
            cleanupActiveAndArchivedRecordingsForTarget();
        }
    }

    protected static void cleanupActiveAndArchivedRecordingsForTarget(long... ids)
            throws JsonProcessingException {
        cleanupActiveAndArchivedRecordingsForTarget(Arrays.stream(ids).boxed().toList());
    }

    protected static void cleanupActiveAndArchivedRecordingsForTarget(List<Long> ids)
            throws JsonProcessingException {
        var variables = new HashMap<String, Object>();
        if (ids == null || ids.isEmpty()) {
            variables.put("targetIds", null);
        } else {
            variables.put("targetIds", ids);
        }
        var payload = Map.of("variables", variables, "query", AbstractTestBase.cleanupQuery(true));
        given().contentType(ContentType.JSON)
                .body(mapper.writeValueAsString(payload))
                .when()
                .post("/api/v4/graphql")
                .then()
                .statusCode(200);
    }
}
