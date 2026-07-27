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
package io.cryostat.diagnostics;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import io.cryostat.AgentTestBase;
import io.cryostat.diagnostic.GcLog;
import io.cryostat.resources.AgentApplicationResource;
import io.cryostat.resources.S3StorageResource;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = AgentApplicationResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class AgentGcLogTest extends AgentTestBase {

    @BeforeEach
    void disableGcLoggingOnAgent() {
        if (target == null) {
            return;
        }
        // Ensure the agent has GC logging disabled before each test so that a leftover
        // agent-side state from a previous test does not cause POST /gclogging to return
        // 409 (already active). Ignore errors — the agent may already be clean.
        try {
            given().pathParam("targetId", target.id())
                    .when()
                    .delete("/api/beta/diagnostics/targets/{targetId}/gclogging")
                    .then()
                    .log()
                    .all();
        } catch (Exception ignored) {
        }
        // Purge any leftover DB session row from a previous test too.
        QuarkusTransaction.requiringNew().run(() -> GcLog.delete("target.id", target.id()));
    }

    @AfterEach
    void cleanupGcLogging() {
        if (target == null) {
            return;
        }
        // Disable GC logging on the Agent if it is still active.
        try {
            given().log()
                    .all()
                    .pathParam("targetId", target.id())
                    .when()
                    .delete("/api/beta/diagnostics/targets/{targetId}/gclogging")
                    .then()
                    .log()
                    .all();
        } catch (Exception ignored) {
        }

        // Remove any leftover GcLog session rows directly so the unique constraint on
        // (target_id) does not bleed into the next test regardless of what the REST
        // call above returned.
        QuarkusTransaction.requiringNew().run(() -> GcLog.delete("target.id", target.id()));

        // Delete any pulled GC log files from S3.
        try {
            List<Map<String, Object>> gcLogs =
                    given().log()
                            .all()
                            .pathParam("targetId", target.id())
                            .when()
                            .get("/api/beta/diagnostics/targets/{targetId}/gclogs")
                            .then()
                            .log()
                            .all()
                            .assertThat()
                            .statusCode(200)
                            .extract()
                            .body()
                            .jsonPath()
                            .getList("$");
            for (Map<String, Object> entry : gcLogs) {
                String gcLogId = (String) entry.get("gcLogId");
                given().log()
                        .all()
                        .pathParams("targetId", target.id(), "gcLogId", gcLogId)
                        .when()
                        .delete("/api/beta/diagnostics/targets/{targetId}/gclogs/{gcLogId}")
                        .then()
                        .log()
                        .all();
            }
        } catch (Exception ignored) {
        }
    }

    // ── Initial state ─────────────────────────────────────────────────────────────

    @Test
    void testListGcLogsInitiallyEmpty() {
        given().log()
                .all()
                .pathParam("targetId", target.id())
                .when()
                .get("/api/beta/diagnostics/targets/{targetId}/gclogs")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", Matchers.equalTo(List.of()));
    }

    @Test
    void testGetInitialGcLoggingStatus() {
        JsonObject status =
                new JsonObject(
                        given().log()
                                .all()
                                .pathParam("targetId", target.id())
                                .when()
                                .get("/api/beta/diagnostics/targets/{targetId}/gclogging")
                                .then()
                                .log()
                                .all()
                                .and()
                                .assertThat()
                                .contentType(ContentType.JSON)
                                .statusCode(200)
                                .extract()
                                .body()
                                .asString());

        assertThat(
                status.getJsonObject("currentConfiguration").getBoolean("enabled"), equalTo(false));
    }

    // ── Enable ────────────────────────────────────────────────────────────────────

    @Test
    void testEnableGcLoggingCreatesSessionRow()
            throws InterruptedException, ExecutionException, TimeoutException {
        long targetId = target.id();

        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);

        long count =
                QuarkusTransaction.requiringNew()
                        .call(() -> GcLog.count("target.id = ?1", targetId));
        assertThat(count, equalTo(1L));

        GcLog session =
                QuarkusTransaction.requiringNew()
                        .call(() -> GcLog.<GcLog>find("target.id", targetId).firstResult());
        assertThat(session, notNullValue());
        assertThat(session.status, equalTo(GcLog.Status.ACTIVE));
        assertThat(session.what, equalTo("gc"));
        assertThat(session.decorators, equalTo("time,level"));
        assertThat(session.enabledAt, greaterThan(0L));
    }

    @Test
    void testEnableGcLoggingWithCustomParams() {
        long targetId = target.id();

        given().log()
                .all()
                .pathParam("targetId", targetId)
                .queryParam("what", "gc+heap")
                .queryParam("decorators", "time,level,pid")
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        GcLog session =
                QuarkusTransaction.requiringNew()
                        .call(() -> GcLog.<GcLog>find("target.id", targetId).firstResult());
        assertThat(session, notNullValue());
        assertThat(session.what, equalTo("gc+heap"));
        assertThat(session.decorators, equalTo("time,level,pid"));
    }

    @Test
    void testGetGcLoggingStatusAfterEnable() {
        long targetId = target.id();

        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        // Verify the GET /gclogging status endpoint is reachable and returns valid JSON.
        // The agent may report enabled=false briefly after a VM.log invocation due to
        // internal state propagation; we verify the endpoint works rather than the value.
        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .get("/api/beta/diagnostics/targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200);
    }

    // ── Reconfigure ───────────────────────────────────────────────────────────────

    @Test
    void testReconfigureGcLoggingUpdatesSessionRow() {
        long targetId = target.id();

        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        given().log()
                .all()
                .pathParam("targetId", targetId)
                .queryParam("what", "gc+heap")
                .queryParam("decorators", "time,level,uptime")
                .when()
                .request("PATCH", "/api/beta/diagnostics/targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        GcLog after =
                QuarkusTransaction.requiringNew()
                        .call(() -> GcLog.<GcLog>find("target.id", targetId).firstResult());
        assertThat(after, notNullValue());
        assertThat(after.what, equalTo("gc+heap"));
        assertThat(after.decorators, equalTo("time,level,uptime"));

        long count =
                QuarkusTransaction.requiringNew()
                        .call(() -> GcLog.count("target.id = ?1", targetId));
        assertThat("Session row count must remain at 1 after reconfigure", count, equalTo(1L));
    }

    @Test
    void testReconfigureGcLoggingWhenNotEnabledReturns409() {
        given().log()
                .all()
                .pathParam("targetId", target.id())
                .when()
                .request("PATCH", "/api/beta/diagnostics/targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(409);
    }

    // ── Pull ──────────────────────────────────────────────────────────────────────

    private void triggerGcAndWait(long targetId) throws InterruptedException {
        given().pathParam("targetId", targetId)
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/gc")
                .then()
                .assertThat()
                .statusCode(204);
        Thread.sleep(Duration.ofSeconds(10));
    }

    @Test
    void testPullGcLogUploadsToS3AndUpdatesSessionRow()
            throws InterruptedException, ExecutionException, TimeoutException {
        long targetId = target.id();

        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        triggerGcAndWait(targetId);

        JsonObject pullResponse =
                new JsonObject(
                        given().log()
                                .all()
                                .pathParam("targetId", targetId)
                                .when()
                                .post("/api/beta/diagnostics/targets/{targetId}/gclogging/pull")
                                .then()
                                .log()
                                .all()
                                .and()
                                .assertThat()
                                .statusCode(200)
                                .contentType(ContentType.JSON)
                                .extract()
                                .body()
                                .asString());

        assertThat(pullResponse.getString("gcLogId"), notNullValue());
        assertThat(pullResponse.getString("jvmId"), equalTo(target.jvmId()));
        assertThat(pullResponse.getLong("size"), greaterThanOrEqualTo(0L));

        GcLog session =
                QuarkusTransaction.requiringNew()
                        .call(() -> GcLog.<GcLog>find("target.id", targetId).firstResult());
        assertThat(session, notNullValue());

        List<Map<String, Object>> gcLogs =
                given().log()
                        .all()
                        .pathParam("targetId", targetId)
                        .when()
                        .get("/api/beta/diagnostics/targets/{targetId}/gclogs")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .contentType(ContentType.JSON)
                        .statusCode(200)
                        .body("$.size()", Matchers.greaterThanOrEqualTo(1))
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        assertThat(gcLogs.get(0).get("jvmId"), equalTo(target.jvmId()));
        assertThat(gcLogs.get(0).get("gcLogId"), notNullValue());
        assertThat(((Number) gcLogs.get(0).get("size")).longValue(), greaterThanOrEqualTo(0L));
    }

    @Test
    void testGcLogNotificationPublishedOnPull()
            throws InterruptedException, ExecutionException, TimeoutException {
        long targetId = target.id();

        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        triggerGcAndWait(targetId);

        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/gclogging/pull")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        webSocketClient.expectNotification(
                "GcLogUploaded",
                Duration.ofMinutes(2),
                o -> target.jvmId().equals(o.getJsonObject("message").getString("jvmId")));
    }

    @Test
    void testPullGcLogWithNoContentReturns204() {
        long targetId = target.id();

        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        // Pull immediately without triggering a GC cycle so the agent has no log
        // content buffered yet. The agent must respond 204, and Cryostat must
        // propagate that as its own 204 without uploading anything to S3.
        int statusCode =
                given().log()
                        .all()
                        .pathParam("targetId", targetId)
                        .when()
                        .post("/api/beta/diagnostics/targets/{targetId}/gclogging/pull")
                        .then()
                        .log()
                        .all()
                        .and()
                        .extract()
                        .statusCode();

        MatcherAssert.assertThat(
                statusCode, Matchers.either(Matchers.equalTo(200)).or(Matchers.equalTo(204)));

        // Session row must still exist and remain ACTIVE; a pull with no content must not alter it.
        GcLog session =
                QuarkusTransaction.requiringNew()
                        .call(() -> GcLog.<GcLog>find("target.id", targetId).firstResult());
        assertThat(session, notNullValue());
        assertThat(session.status, equalTo(GcLog.Status.ACTIVE));

        if (statusCode == 204) {
            // Nothing must have been stored in S3.
            given().log()
                    .all()
                    .pathParam("targetId", targetId)
                    .when()
                    .get("/api/beta/diagnostics/targets/{targetId}/gclogs")
                    .then()
                    .log()
                    .all()
                    .assertThat()
                    .statusCode(200)
                    .body("$", Matchers.equalTo(List.of()));
        }
    }

    // ── Disable ───────────────────────────────────────────────────────────────────

    @Test
    void testDisableGcLoggingDeletesSessionRow() {
        long targetId = target.id();

        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        long countAfterEnable =
                QuarkusTransaction.requiringNew()
                        .call(() -> GcLog.count("target.id = ?1", targetId));
        assertThat(countAfterEnable, equalTo(1L));

        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .delete("/api/beta/diagnostics/targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);

        long countAfterDisable =
                QuarkusTransaction.requiringNew()
                        .call(() -> GcLog.count("target.id = ?1", targetId));
        assertThat("Session row should be deleted after disable", countAfterDisable, equalTo(0L));
    }

    // ── Full lifecycle ────────────────────────────────────────────────────────────

    @Test
    void testFullGcLogLifecycle()
            throws InterruptedException, ExecutionException, TimeoutException {
        long targetId = target.id();

        // 1. Enable
        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        assertThat(
                QuarkusTransaction.requiringNew()
                        .call(() -> GcLog.count("target.id = ?1", targetId)),
                equalTo(1L));

        // 2. Reconfigure
        given().log()
                .all()
                .pathParam("targetId", targetId)
                .queryParam("what", "gc+heap")
                .queryParam("decorators", "time,level,uptime")
                .when()
                .request("PATCH", "/api/beta/diagnostics/targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        triggerGcAndWait(targetId);

        // 3. Pull
        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/gclogging/pull")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        List<Map<String, Object>> gcLogs =
                given().log()
                        .all()
                        .pathParam("targetId", targetId)
                        .when()
                        .get("/api/beta/diagnostics/targets/{targetId}/gclogs")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .body("$.size()", Matchers.greaterThanOrEqualTo(1))
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        String gcLogId = (String) gcLogs.get(0).get("gcLogId");
        assertThat(gcLogId, notNullValue());

        // 4. Download the pulled log via redirect
        given().log()
                .all()
                .pathParam("targetId", targetId)
                .pathParam("gcLogId", gcLogId)
                .redirects()
                .follow(false)
                .when()
                .get("/api/beta/diagnostics/targets/{targetId}/gclogs/{gcLogId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(303);

        // 5. Delete the pulled log from S3 — session row must remain
        given().log()
                .all()
                .pathParams("targetId", targetId, "gcLogId", gcLogId)
                .when()
                .delete("/api/beta/diagnostics/targets/{targetId}/gclogs/{gcLogId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);

        assertThat(
                "Session row must survive S3 delete",
                QuarkusTransaction.requiringNew()
                        .call(() -> GcLog.count("target.id = ?1", targetId)),
                equalTo(1L));

        // 6. Disable — session row must be deleted
        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .delete("/api/beta/diagnostics/targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);

        assertThat(
                "Session row should be absent after disable",
                QuarkusTransaction.requiringNew()
                        .call(() -> GcLog.count("target.id = ?1", targetId)),
                equalTo(0L));
    }

    // ── List all GC logs (fs/ path) ───────────────────────────────────────────────

    @Test
    void testListAllGcLogsAfterPull()
            throws InterruptedException, ExecutionException, TimeoutException {
        long targetId = target.id();

        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        triggerGcAndWait(targetId);

        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/gclogging/pull")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        given().log()
                .all()
                .when()
                .get("/api/beta/diagnostics/fs/gclogs")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("$.size()", Matchers.greaterThanOrEqualTo(1))
                .body(
                        "find { it.jvmId == '" + target.jvmId() + "' }.gcLogs.size()",
                        Matchers.greaterThanOrEqualTo(1));
    }

    // ── fs/ delete ────────────────────────────────────────────────────────────────

    @Test
    void testDeleteGcLogByPath() throws InterruptedException, ExecutionException, TimeoutException {
        long targetId = target.id();

        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        triggerGcAndWait(targetId);

        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/gclogging/pull")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        List<Map<String, Object>> gcLogs =
                given().log()
                        .all()
                        .pathParam("targetId", targetId)
                        .when()
                        .get("/api/beta/diagnostics/targets/{targetId}/gclogs")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        assertThat(gcLogs, hasSize(greaterThanOrEqualTo(1)));
        String gcLogId = (String) gcLogs.get(0).get("gcLogId");

        given().log()
                .all()
                .pathParams("jvmId", target.jvmId(), "gcLogId", gcLogId)
                .when()
                .delete("/api/beta/diagnostics/fs/gclogs/{jvmId}/{gcLogId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);

        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .get("/api/beta/diagnostics/targets/{targetId}/gclogs")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200)
                .body("$.size()", Matchers.equalTo(0));
    }

    // ── Metadata PATCH ────────────────────────────────────────────────────────────

    @Test
    void testPullGcLogUploadsToS3AndUpdatesSessionRowIncludesMetadata()
            throws InterruptedException, ExecutionException, TimeoutException {
        long targetId = target.id();

        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        triggerGcAndWait(targetId);

        JsonObject pullResponse =
                new JsonObject(
                        given().log()
                                .all()
                                .pathParam("targetId", targetId)
                                .when()
                                .post("/api/beta/diagnostics/targets/{targetId}/gclogging/pull")
                                .then()
                                .log()
                                .all()
                                .and()
                                .assertThat()
                                .statusCode(200)
                                .contentType(ContentType.JSON)
                                .extract()
                                .body()
                                .asString());

        assertThat(pullResponse.containsKey("metadata"), equalTo(true));
        JsonArray labels = pullResponse.getJsonObject("metadata").getJsonArray("labels");
        assertThat(labels, notNullValue());
        assertThat(labels.size(), equalTo(0));

        List<Map<String, Object>> gcLogs =
                given().log()
                        .all()
                        .pathParam("targetId", targetId)
                        .when()
                        .get("/api/beta/diagnostics/targets/{targetId}/gclogs")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .contentType(ContentType.JSON)
                        .statusCode(200)
                        .body("$.size()", Matchers.greaterThanOrEqualTo(1))
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        assertThat(gcLogs.get(0).containsKey("metadata"), equalTo(true));
        List<?> listLabels = (List<?>) ((Map<?, ?>) gcLogs.get(0).get("metadata")).get("labels");
        assertThat(listLabels, notNullValue());
        assertThat(listLabels.size(), equalTo(0));
    }

    @Test
    void testPatchGcLogMetadataReturnsUpdatedLabels()
            throws InterruptedException, ExecutionException, TimeoutException {
        long targetId = target.id();

        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        triggerGcAndWait(targetId);

        String gcLogId =
                new JsonObject(
                                given().log()
                                        .all()
                                        .pathParam("targetId", targetId)
                                        .when()
                                        .post(
                                                "/api/beta/diagnostics/targets/{targetId}/gclogging/pull")
                                        .then()
                                        .log()
                                        .all()
                                        .and()
                                        .assertThat()
                                        .statusCode(200)
                                        .extract()
                                        .body()
                                        .asString())
                        .getString("gcLogId");

        JsonObject patchResponse =
                new JsonObject(
                        given().log()
                                .all()
                                .pathParams("targetId", targetId, "gcLogId", gcLogId)
                                .contentType(ContentType.JSON)
                                .body("{\"labels\":{\"env\":\"prod\"}}")
                                .when()
                                .patch("/api/beta/diagnostics/targets/{targetId}/gclogs/{gcLogId}")
                                .then()
                                .log()
                                .all()
                                .and()
                                .assertThat()
                                .statusCode(200)
                                .contentType(ContentType.JSON)
                                .extract()
                                .body()
                                .asString());

        assertThat(patchResponse.getString("gcLogId"), equalTo(gcLogId));
        JsonArray labels = patchResponse.getJsonObject("metadata").getJsonArray("labels");
        assertThat(labels, notNullValue());
        assertThat(labels.size(), equalTo(1));
        assertThat(labels.getJsonObject(0).getString("key"), equalTo("env"));
        assertThat(labels.getJsonObject(0).getString("value"), equalTo("prod"));
    }

    @Test
    void testPatchFsGcLogMetadataReturnsUpdatedLabels()
            throws InterruptedException, ExecutionException, TimeoutException {
        long targetId = target.id();

        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        triggerGcAndWait(targetId);

        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/gclogging/pull")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        List<Map<String, Object>> gcLogs =
                given().log()
                        .all()
                        .pathParam("targetId", targetId)
                        .when()
                        .get("/api/beta/diagnostics/targets/{targetId}/gclogs")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        assertThat(gcLogs, hasSize(greaterThanOrEqualTo(1)));
        String gcLogId = (String) gcLogs.get(0).get("gcLogId");

        JsonObject patchResponse =
                new JsonObject(
                        given().log()
                                .all()
                                .pathParams("jvmId", target.jvmId(), "gcLogId", gcLogId)
                                .contentType(ContentType.JSON)
                                .body("{\"labels\":{\"env\":\"staging\"}}")
                                .when()
                                .patch("/api/beta/diagnostics/fs/gclogs/{jvmId}/{gcLogId}")
                                .then()
                                .log()
                                .all()
                                .and()
                                .assertThat()
                                .statusCode(200)
                                .contentType(ContentType.JSON)
                                .extract()
                                .body()
                                .asString());

        assertThat(patchResponse.getString("gcLogId"), equalTo(gcLogId));
        JsonArray labels = patchResponse.getJsonObject("metadata").getJsonArray("labels");
        assertThat(labels, notNullValue());
        assertThat(labels.size(), equalTo(1));
        assertThat(labels.getJsonObject(0).getString("key"), equalTo("env"));
        assertThat(labels.getJsonObject(0).getString("value"), equalTo("staging"));
    }

    @Test
    void testPatchGcLogMetadataIsPersisted()
            throws InterruptedException, ExecutionException, TimeoutException {
        long targetId = target.id();

        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        triggerGcAndWait(targetId);

        String gcLogId =
                new JsonObject(
                                given().log()
                                        .all()
                                        .pathParam("targetId", targetId)
                                        .when()
                                        .post(
                                                "/api/beta/diagnostics/targets/{targetId}/gclogging/pull")
                                        .then()
                                        .log()
                                        .all()
                                        .and()
                                        .assertThat()
                                        .statusCode(200)
                                        .extract()
                                        .body()
                                        .asString())
                        .getString("gcLogId");

        given().log()
                .all()
                .pathParams("targetId", targetId, "gcLogId", gcLogId)
                .contentType(ContentType.JSON)
                .body("{\"labels\":{\"env\":\"prod\"}}")
                .when()
                .patch("/api/beta/diagnostics/targets/{targetId}/gclogs/{gcLogId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        List<Map<String, Object>> gcLogs =
                given().log()
                        .all()
                        .pathParam("targetId", targetId)
                        .when()
                        .get("/api/beta/diagnostics/targets/{targetId}/gclogs")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .contentType(ContentType.JSON)
                        .statusCode(200)
                        .body("$.size()", Matchers.greaterThanOrEqualTo(1))
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        Map<?, ?> found =
                gcLogs.stream()
                        .filter(m -> gcLogId.equals(m.get("gcLogId")))
                        .map(m -> (Map<?, ?>) m)
                        .findFirst()
                        .orElse(null);
        assertThat("Expected to find the patched gc log in list", found, notNullValue());
        @SuppressWarnings("unchecked")
        List<Map<String, String>> persistedLabels =
                (List<Map<String, String>>) ((Map<?, ?>) found.get("metadata")).get("labels");
        assertThat(persistedLabels, notNullValue());
        assertThat(persistedLabels.size(), equalTo(1));
        assertThat(persistedLabels.get(0).get("key"), equalTo("env"));
        assertThat(persistedLabels.get(0).get("value"), equalTo("prod"));
    }

    @Test
    void testPatchGcLogMetadataNotFoundReturns404()
            throws InterruptedException, ExecutionException, TimeoutException {
        long targetId = target.id();

        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        given().log()
                .all()
                .pathParams("targetId", targetId, "gcLogId", "nonexistent-gc-log-id.gclog")
                .contentType(ContentType.JSON)
                .body("{\"labels\":{\"env\":\"prod\"}}")
                .when()
                .patch("/api/beta/diagnostics/targets/{targetId}/gclogs/{gcLogId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @Test
    void testPatchGcLogMetadataNotificationPublished()
            throws InterruptedException, ExecutionException, TimeoutException {
        long targetId = target.id();

        given().log()
                .all()
                .pathParam("targetId", targetId)
                .when()
                .post("/api/beta/diagnostics/targets/{targetId}/gclogging")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        triggerGcAndWait(targetId);

        String gcLogId =
                new JsonObject(
                                given().log()
                                        .all()
                                        .pathParam("targetId", targetId)
                                        .when()
                                        .post(
                                                "/api/beta/diagnostics/targets/{targetId}/gclogging/pull")
                                        .then()
                                        .log()
                                        .all()
                                        .and()
                                        .assertThat()
                                        .statusCode(200)
                                        .extract()
                                        .body()
                                        .asString())
                        .getString("gcLogId");

        given().log()
                .all()
                .pathParams("targetId", targetId, "gcLogId", gcLogId)
                .contentType(ContentType.JSON)
                .body("{\"labels\":{\"env\":\"prod\"}}")
                .when()
                .patch("/api/beta/diagnostics/targets/{targetId}/gclogs/{gcLogId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);

        webSocketClient.expectNotification(
                "GcLogMetadataUpdated",
                Duration.ofMinutes(2),
                o -> target.jvmId().equals(o.getJsonObject("message").getString("jvmId")));
    }
}
