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
package io.cryostat.recordings.analysis;

import static io.restassured.RestAssured.given;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;
import itest.util.ITestCleanupFailedException;
import jakarta.websocket.DeploymentException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestHTTPEndpoint(JfrAnalytics.class)
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class JfrAnalyticsTest extends AbstractTransactionalTestBase {

    private static boolean recordingSetupComplete = false;
    private static String sharedArchivedRecordingName;

    @BeforeEach
    void setupRecording()
            throws InterruptedException, IOException, DeploymentException, TimeoutException {
        if (recordingSetupComplete) {
            return;
        }

        if (selfId < 1) {
            defineSelfCustomTarget();
        }

        startSelfRecording("jfrAnalyticsTest", TEMPLATE_CONTINUOUS);

        Thread.sleep(5_000);

        String archiveJobId =
                given().log()
                        .all()
                        .when()
                        .pathParams("targetId", selfId, "remoteId", selfRecordingId)
                        .body("SAVE")
                        .patch("/api/v4/targets/{targetId}/recordings/{remoteId}")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .and()
                        .extract()
                        .body()
                        .asString();

        JsonObject archiveMessage =
                webSocketClient.expectNotification(
                        "ArchiveRecordingSuccess",
                        o -> archiveJobId.equals(o.getJsonObject("message").getString("jobId")));
        sharedArchivedRecordingName =
                archiveMessage.getJsonObject("message").getString("recording");

        recordingSetupComplete = true;
    }

    @AfterEach
    void cleanupRecording() throws ITestCleanupFailedException {
        if (!recordingSetupComplete) {
            return;
        }

        if (selfId > 0 && selfRecordingId > 0) {
            try {
                cleanupSelfRecording();
            } catch (Exception e) {
                throw new ITestCleanupFailedException(e);
            }
        }

        if (sharedArchivedRecordingName != null) {
            try {
                given().log()
                        .all()
                        .when()
                        .pathParams(
                                "connectUrl", SELF_JMX_URL, "filename", sharedArchivedRecordingName)
                        .delete("/api/beta/recordings/{connectUrl}/{filename}")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(204);
            } catch (Exception e) {
                throw new ITestCleanupFailedException(e);
            }
            sharedArchivedRecordingName = null;
        }

        recordingSetupComplete = false;
    }

    @Test
    void testExecuteQueryWithoutRecording() {
        given().log()
                .all()
                .when()
                .pathParams("jvmId", selfJvmId, "filename", "nonexistent.jfr")
                .formParam(
                        "query",
                        """
                        SELECT COUNT(*) FROM "JFR"."jdk.ObjectAllocationSample"
                        """)
                .post("/api/beta/recording_analytics/{jvmId}/{filename}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @Test
    void testExecuteQueryWithInvalidQuery() {
        given().log()
                .all()
                .when()
                .pathParams("jvmId", selfJvmId, "filename", sharedArchivedRecordingName)
                .formParam(
                        "query",
                        """
                        INVALID SQL QUERY
                        """)
                .post("/api/beta/recording_analytics/{jvmId}/{filename}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void testExecuteSimpleQuery() {
        List<List<String>> result =
                given().log()
                        .all()
                        .when()
                        .pathParams("jvmId", selfJvmId, "filename", sharedArchivedRecordingName)
                        .formParam(
                                "query",
                                """
                                SELECT COUNT(*) FROM "JFR"."jdk.ObjectAllocationSample" LIMIT 1
                                """)
                        .post("/api/beta/recording_analytics/{jvmId}/{filename}")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .and()
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        // Response is array of arrays: [[count_value]]
        MatcherAssert.assertThat(result, Matchers.notNullValue());
        MatcherAssert.assertThat(result, Matchers.instanceOf(List.class));
        MatcherAssert.assertThat(result.size(), Matchers.equalTo(1));
        MatcherAssert.assertThat(result.get(0), Matchers.instanceOf(List.class));
        MatcherAssert.assertThat(result.get(0).size(), Matchers.equalTo(1));
        // Count value should be a numeric string
        MatcherAssert.assertThat(result.get(0).get(0), Matchers.matchesRegex("\\d+"));
    }

    @Test
    void testExecuteQueryWithMultipleRows() {
        List<List<String>> result =
                given().log()
                        .all()
                        .when()
                        .pathParams("jvmId", selfJvmId, "filename", sharedArchivedRecordingName)
                        .formParam(
                                "query",
                                """
                                SELECT * FROM "JFR"."jdk.ObjectAllocationSample" LIMIT 10
                                """)
                        .post("/api/beta/recording_analytics/{jvmId}/{filename}")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .and()
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        // Response is array of arrays, each row contains multiple columns
        MatcherAssert.assertThat(result, Matchers.notNullValue());
        MatcherAssert.assertThat(result, Matchers.instanceOf(List.class));
        MatcherAssert.assertThat(result.size(), Matchers.lessThanOrEqualTo(10));
        if (!result.isEmpty()) {
            // Each row should have multiple columns (startTime, sampledThread, stackTrace,
            // objectClass, weight)
            MatcherAssert.assertThat(result.get(0), Matchers.instanceOf(List.class));
            MatcherAssert.assertThat(result.get(0).size(), Matchers.greaterThan(1));
        }
    }

    @Test
    void testExecuteQueryWithAggregation() {
        List<List<String>> result =
                given().log()
                        .all()
                        .when()
                        .pathParams("jvmId", selfJvmId, "filename", sharedArchivedRecordingName)
                        .formParam(
                                "query",
                                """
                                SELECT COUNT(*) as "total" FROM "JFR"."jdk.ObjectAllocationSample"
                                """)
                        .post("/api/beta/recording_analytics/{jvmId}/{filename}")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .and()
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        // Response is array with single row containing aggregated count
        MatcherAssert.assertThat(result, Matchers.notNullValue());
        MatcherAssert.assertThat(result, Matchers.instanceOf(List.class));
        MatcherAssert.assertThat(result.size(), Matchers.equalTo(1));
        MatcherAssert.assertThat(result.get(0), Matchers.instanceOf(List.class));
        MatcherAssert.assertThat(result.get(0).size(), Matchers.equalTo(1));
        // Count value should be a numeric string
        MatcherAssert.assertThat(result.get(0).get(0), Matchers.matchesRegex("\\d+"));
    }

    @Test
    void testExecuteQueryWithGroupByAndOrderBy() {
        List<List<String>> result =
                given().log()
                        .all()
                        .when()
                        .pathParams("jvmId", selfJvmId, "filename", sharedArchivedRecordingName)
                        .formParam(
                                "query",
                                """
                                SELECT "state", COUNT(*) AS "count"
                                FROM "JFR"."jdk.ExecutionSample"
                                GROUP BY "state"
                                ORDER BY COUNT(*) DESC
                                """)
                        .post("/api/beta/recording_analytics/{jvmId}/{filename}")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .and()
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        // Response is array of arrays, each row has [state, count]
        MatcherAssert.assertThat(result, Matchers.notNullValue());
        MatcherAssert.assertThat(result, Matchers.instanceOf(List.class));
        if (!result.isEmpty()) {
            // Each row should have exactly 2 columns: state and count
            MatcherAssert.assertThat(result.get(0), Matchers.instanceOf(List.class));
            MatcherAssert.assertThat(result.get(0).size(), Matchers.equalTo(2));
            // First column is state (string), second is count (numeric string)
            MatcherAssert.assertThat(result.get(0).get(0), Matchers.notNullValue());
            MatcherAssert.assertThat(result.get(0).get(1), Matchers.matchesRegex("\\d+"));
        }
    }

    @Test
    void testExecuteQueryWithNestedFieldAccessFails() {
        // Nested field access with dot notation is not supported by Apache Calcite
        // for JFR events - this test verifies the error is handled correctly
        given().log()
                .all()
                .when()
                .pathParams("jvmId", selfJvmId, "filename", sharedArchivedRecordingName)
                .formParam(
                        "query",
                        """
                        SELECT "sampledThread"."javaName" AS "thread_name",
                               COUNT(*) AS "sample_count"
                        FROM "JFR"."jdk.ExecutionSample"
                        GROUP BY "sampledThread"."javaName"
                        ORDER BY COUNT(*) DESC
                        LIMIT 20
                        """)
                .post("/api/beta/recording_analytics/{jvmId}/{filename}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void testExecuteQueryWithCustomFunction() {
        List<List<String>> result =
                given().log()
                        .all()
                        .when()
                        .pathParams("jvmId", selfJvmId, "filename", sharedArchivedRecordingName)
                        .formParam(
                                "query",
                                """
                                SELECT TRUNCATE_STACKTRACE("stackTrace", 10) AS "stacktrace",
                                       COUNT(*) AS "sample_count"
                                FROM "JFR"."jdk.ExecutionSample"
                                GROUP BY TRUNCATE_STACKTRACE("stackTrace", 10)
                                ORDER BY COUNT(*) DESC
                                LIMIT 15
                                """)
                        .post("/api/beta/recording_analytics/{jvmId}/{filename}")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .and()
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        // Response is array of arrays, each row has [stacktrace, sample_count]
        MatcherAssert.assertThat(result, Matchers.notNullValue());
        MatcherAssert.assertThat(result, Matchers.instanceOf(List.class));
        MatcherAssert.assertThat(result.size(), Matchers.lessThanOrEqualTo(15));
        if (!result.isEmpty()) {
            // Each row should have exactly 2 columns: stacktrace and sample_count
            MatcherAssert.assertThat(result.get(0), Matchers.instanceOf(List.class));
            MatcherAssert.assertThat(result.get(0).size(), Matchers.equalTo(2));
            // First column is stacktrace (string), second is count (numeric string)
            MatcherAssert.assertThat(result.get(0).get(0), Matchers.notNullValue());
            MatcherAssert.assertThat(result.get(0).get(1), Matchers.matchesRegex("\\d+"));
        }
    }

    @Test
    void testDiscoverSchemaStructure() {
        List<List<String>> result =
                given().log()
                        .all()
                        .when()
                        .pathParams("jvmId", selfJvmId, "filename", sharedArchivedRecordingName)
                        .formParam(
                                "query",
                                """
                                SELECT * FROM "JFR"."jdk.ExecutionSample" LIMIT 1
                                """)
                        .post("/api/beta/recording_analytics/{jvmId}/{filename}")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.JSON)
                        .and()
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        // Response is array of arrays with single row containing all columns
        MatcherAssert.assertThat(result, Matchers.notNullValue());
        MatcherAssert.assertThat(result, Matchers.instanceOf(List.class));
        MatcherAssert.assertThat(result.size(), Matchers.lessThanOrEqualTo(1));
        if (!result.isEmpty()) {
            // ExecutionSample has multiple columns (startTime, sampledThread, stackTrace, state)
            MatcherAssert.assertThat(result.get(0), Matchers.instanceOf(List.class));
            MatcherAssert.assertThat(result.get(0).size(), Matchers.greaterThan(1));
        }
    }

    @Test
    void testNestedFieldAccessWithFlattenedName() {
        given().log()
                .all()
                .when()
                .pathParams("jvmId", selfJvmId, "filename", sharedArchivedRecordingName)
                .formParam(
                        "query",
                        """
                        SELECT "sampledThread.javaName" AS "thread_name",
                               COUNT(*) AS "sample_count"
                        FROM "JFR"."jdk.ExecutionSample"
                        GROUP BY "sampledThread.javaName"
                        ORDER BY COUNT(*) DESC
                        LIMIT 20
                        """)
                .post("/api/beta/recording_analytics/{jvmId}/{filename}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void testNestedFieldAccessWithBracketNotation() {
        given().log()
                .all()
                .when()
                .pathParams("jvmId", selfJvmId, "filename", sharedArchivedRecordingName)
                .formParam(
                        "query",
                        """
                        SELECT "sampledThread"['javaName'] AS "thread_name",
                               COUNT(*) AS "sample_count"
                        FROM "JFR"."jdk.ExecutionSample"
                        GROUP BY "sampledThread"['javaName']
                        ORDER BY COUNT(*) DESC
                        LIMIT 20
                        """)
                .post("/api/beta/recording_analytics/{jvmId}/{filename}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);
    }
}
