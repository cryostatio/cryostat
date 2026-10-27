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
package io.cryostat.storage;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.cryostat.AgentTestBase;
import io.cryostat.resources.AgentApplicationResource;
import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = AgentApplicationResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class ConsolidatedBucketsTest extends AgentTestBase {

    @Test
    void testUnifiedLogsQueriesAreFilteredCorrectly() throws Exception {
        String jvmId = target.jvmId();

        // Create a heap dump (different blob type)
        given().log()
                .all()
                .pathParam("jvmId", jvmId)
                .when()
                .post("/api/v5/targets/{jvmId}/diagnostics/heap-dump")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200);

        // Wait for heap dump upload to complete
        webSocketClient.expectNotification(
                "HeapDumpUploaded",
                Duration.ofMinutes(2),
                o -> jvmId.equals(o.getJsonObject("message").getString("jvmId")));

        // Create a thread dump (different blob type)
        given().log()
                .all()
                .pathParam("jvmId", jvmId)
                .when()
                .post("/api/v5/targets/{jvmId}/diagnostics/thread-dump")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200);

        // Wait for thread dump to complete
        webSocketClient.expectNotification("ThreadDumpSuccess", Duration.ofMinutes(2));

        // Query unified logs endpoint - should be empty since we didn't create any
        List<Map<String, Object>> unifiedLogs =
                given().log()
                        .all()
                        .pathParam("jvmId", jvmId)
                        .when()
                        .get("/api/v5/targets/{jvmId}/diagnostics/unified-logs")
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        // Verify no unified logs are returned (key assertion - with bug, heap dumps
        // and thread dumps would be returned here)
        assertThat(unifiedLogs, empty());

        // Query heap dumps endpoint - should have 1
        List<Map<String, Object>> heapDumps =
                given().log()
                        .all()
                        .pathParam("jvmId", jvmId)
                        .when()
                        .get("/api/v5/targets/{jvmId}/diagnostics/heap-dump")
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        assertThat(heapDumps, hasSize(1));
        // Verify no unified logs in the heap dumps result
        for (Map<String, Object> dump : heapDumps) {
            String uuid = (String) dump.get("uuid");
            assertThat(uuid, not(containsString("log")));
            assertThat(uuid, not(containsString("thread")));
        }

        // Query thread dumps endpoint - should have 1
        List<Map<String, Object>> threadDumps =
                given().log()
                        .all()
                        .pathParam("jvmId", jvmId)
                        .when()
                        .get("/api/v5/targets/{jvmId}/diagnostics/thread-dump")
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        assertThat(threadDumps, hasSize(1));
        // Verify no heap dumps or unified logs in the thread dumps result
        for (Map<String, Object> dump : threadDumps) {
            String uuid = (String) dump.get("uuid");
            assertThat(uuid, not(containsString("log")));
            assertThat(uuid, not(containsString("heap")));
        }
    }

    @Test
    void testArchivedRecordingsQueriesAreFilteredCorrectly() throws Exception {
        String jvmId = target.jvmId();

        // Create a heap dump (different blob type)
        given().log()
                .all()
                .pathParam("jvmId", jvmId)
                .when()
                .post("/api/v5/targets/{jvmId}/diagnostics/heap-dump")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200);

        // Wait for heap dump upload to complete
        webSocketClient.expectNotification(
                "HeapDumpUploaded",
                Duration.ofMinutes(2),
                o -> jvmId.equals(o.getJsonObject("message").getString("jvmId")));

        // Query archived recordings endpoint - should be empty since no JFR recordings
        // were archived
        List<Map<String, Object>> recordings =
                given().log()
                        .all()
                        .basePath("")
                        .pathParam("jvmId", jvmId)
                        .when()
                        .get("/api/v5/recordings/{jvmId}")
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        // Verify no archived recordings are returned - with bug, heap dumps would appear here
        assertThat(recordings, empty());

        // Query heap dumps endpoint - should have 1
        List<Map<String, Object>> heapDumps =
                given().log()
                        .all()
                        .pathParam("jvmId", jvmId)
                        .when()
                        .get("/api/v5/targets/{jvmId}/diagnostics/heap-dump")
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        assertThat(heapDumps, hasSize(1));
    }

    @Test
    void testHeapDumpsQueriesReturnOnlyHeapDumps() throws Exception {
        String jvmId = target.jvmId();

        // Create a thread dump (different blob type)
        given().log()
                .all()
                .pathParam("jvmId", jvmId)
                .when()
                .post("/api/v5/targets/{jvmId}/diagnostics/thread-dump")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200);

        // Wait for thread dump to complete
        webSocketClient.expectNotification("ThreadDumpSuccess", Duration.ofMinutes(2));

        // Create a heap dump
        given().log()
                .all()
                .pathParam("jvmId", jvmId)
                .when()
                .post("/api/v5/targets/{jvmId}/diagnostics/heap-dump")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200);

        // Wait for heap dump upload to complete
        webSocketClient.expectNotification(
                "HeapDumpUploaded",
                Duration.ofMinutes(2),
                o -> jvmId.equals(o.getJsonObject("message").getString("jvmId")));

        // Query heap dumps endpoint
        List<Map<String, Object>> heapDumps =
                given().log()
                        .all()
                        .pathParam("jvmId", jvmId)
                        .when()
                        .get("/api/v5/targets/{jvmId}/diagnostics/heap-dump")
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        // Should only have 1 heap dump, not the thread dump as well (key assertion - with
        // bug bucket consolidation, both would be returned)
        assertThat(heapDumps, hasSize(1));
        for (Map<String, Object> dump : heapDumps) {
            String uuid = (String) dump.get("uuid");
            assertThat(uuid, not(containsString("thread")));
        }
    }

    @Test
    void testThreadDumpsQueriesReturnOnlyThreadDumps() throws Exception {
        String jvmId = target.jvmId();

        // Create a heap dump (different blob type)
        given().log()
                .all()
                .pathParam("jvmId", jvmId)
                .when()
                .post("/api/v5/targets/{jvmId}/diagnostics/heap-dump")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200);

        // Wait for heap dump upload to complete
        webSocketClient.expectNotification(
                "HeapDumpUploaded",
                Duration.ofMinutes(2),
                o -> jvmId.equals(o.getJsonObject("message").getString("jvmId")));

        // Create a thread dump
        given().log()
                .all()
                .pathParam("jvmId", jvmId)
                .when()
                .post("/api/v5/targets/{jvmId}/diagnostics/thread-dump")
                .then()
                .log()
                .all()
                .assertThat()
                .statusCode(200);

        // Wait for thread dump to complete
        webSocketClient.expectNotification("ThreadDumpSuccess", Duration.ofMinutes(2));

        // Query thread dumps endpoint
        List<Map<String, Object>> threadDumps =
                given().log()
                        .all()
                        .pathParam("jvmId", jvmId)
                        .when()
                        .get("/api/v5/targets/{jvmId}/diagnostics/thread-dump")
                        .then()
                        .log()
                        .all()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        // Should only have 1 thread dump, not the heap dump as well (key assertion - with
        // bug, both would be returned)
        assertThat(threadDumps, hasSize(1));
        for (Map<String, Object> dump : threadDumps) {
            String uuid = (String) dump.get("uuid");
            assertThat(uuid, not(containsString("heap")));
        }
    }
}
