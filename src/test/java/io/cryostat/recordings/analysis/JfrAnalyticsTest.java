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

import java.io.File;
import java.util.List;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestHTTPEndpoint(JfrAnalytics.class)
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class JfrAnalyticsTest extends AbstractTransactionalTestBase {

    private static final String RECORDING_FILENAME = "analytics-sample.jfr";

    @BeforeEach
    void setupRecording() throws Exception {
        if (selfId < 1) {
            defineSelfCustomTarget();
        }

        File recordingFile =
                new File(
                        getClass()
                                .getClassLoader()
                                .getResource(
                                        "io/cryostat/recordings/analysis/" + RECORDING_FILENAME)
                                .toURI());

        given().contentType(ContentType.MULTIPART)
                .multiPart("recording", recordingFile, "application/octet-stream")
                .pathParam("jvmId", selfJvmId)
                .post("/api/beta/recordings/{jvmId}")
                .then()
                .assertThat()
                .statusCode(204);
    }

    @AfterEach
    void cleanupRecording() {
        given().pathParams("jvmId", selfJvmId, "filename", RECORDING_FILENAME)
                .delete("/api/beta/fs/recordings/{jvmId}/{filename}")
                .then()
                .assertThat()
                .statusCode(204);
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
    void testExecuteInvalidQuery() {
        given().log()
                .all()
                .when()
                .pathParams("jvmId", selfJvmId, "filename", RECORDING_FILENAME)
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
                        .pathParams("jvmId", selfJvmId, "filename", RECORDING_FILENAME)
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
                        .pathParams("jvmId", selfJvmId, "filename", RECORDING_FILENAME)
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
                        .pathParams("jvmId", selfJvmId, "filename", RECORDING_FILENAME)
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
                        .pathParams("jvmId", selfJvmId, "filename", RECORDING_FILENAME)
                        .formParam(
                                "query",
                                """
                                SELECT "objectClass", COUNT(*) AS "count"
                                FROM "JFR"."jdk.ObjectAllocationSample"
                                GROUP BY "objectClass"
                                ORDER BY COUNT(*) DESC
                                LIMIT 10
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

        // Response is array of arrays, each row has [objectClass, count]
        MatcherAssert.assertThat(result, Matchers.notNullValue());
        MatcherAssert.assertThat(result, Matchers.instanceOf(List.class));
        MatcherAssert.assertThat(result.size(), Matchers.lessThanOrEqualTo(10));
        if (!result.isEmpty()) {
            // Each row should have exactly 2 columns: objectClass and count
            MatcherAssert.assertThat(result.get(0), Matchers.instanceOf(List.class));
            MatcherAssert.assertThat(result.get(0).size(), Matchers.equalTo(2));
            // First column is objectClass (string), second is count (numeric string)
            MatcherAssert.assertThat(result.get(0).get(0), Matchers.notNullValue());
            MatcherAssert.assertThat(result.get(0).get(1), Matchers.matchesRegex("\\d+"));
        }
    }

    @Test
    void testExecuteQueryWithCustomFunction() {
        List<List<String>> result =
                given().log()
                        .all()
                        .when()
                        .pathParams("jvmId", selfJvmId, "filename", RECORDING_FILENAME)
                        .formParam(
                                "query",
                                """
                                SELECT TRUNCATE_STACKTRACE("stackTrace", 10) AS "stacktrace",
                                       COUNT(*) AS "allocation_count"
                                FROM "JFR"."jdk.ObjectAllocationSample"
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

        // Response is array of arrays, each row has [stacktrace, allocation_count]
        MatcherAssert.assertThat(result, Matchers.notNullValue());
        MatcherAssert.assertThat(result, Matchers.instanceOf(List.class));
        MatcherAssert.assertThat(result.size(), Matchers.lessThanOrEqualTo(15));
        if (!result.isEmpty()) {
            // Each row should have exactly 2 columns: stacktrace and allocation_count
            MatcherAssert.assertThat(result.get(0), Matchers.instanceOf(List.class));
            MatcherAssert.assertThat(result.get(0).size(), Matchers.equalTo(2));
            // First column is stacktrace (string), second is count (numeric string)
            MatcherAssert.assertThat(result.get(0).get(0), Matchers.notNullValue());
            MatcherAssert.assertThat(result.get(0).get(1), Matchers.matchesRegex("\\d+"));
        }
    }

    @Test
    void testExecuteQueryWithNestedFieldAccessFails() {
        // Nested field access with dot notation is not supported. This test verifies the error is
        // handled correctly
        given().log()
                .all()
                .when()
                .pathParams("jvmId", selfJvmId, "filename", RECORDING_FILENAME)
                .formParam(
                        "query",
                        """
                        SELECT "objectClass"."name" AS "class_name",
                               COUNT(*) AS "allocation_count"
                        FROM "JFR"."jdk.ObjectAllocationSample"
                        GROUP BY "objectClass"."name"
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
    void testNestedFieldAccessWithFlattenedName() {
        // Nested field access with flattened name notation is not supported. This test verifies the
        // error is handled correctly
        given().log()
                .all()
                .when()
                .pathParams("jvmId", selfJvmId, "filename", RECORDING_FILENAME)
                .formParam(
                        "query",
                        """
                        SELECT "objectClass.name" AS "class_name",
                               COUNT(*) AS "allocation_count"
                        FROM "JFR"."jdk.ObjectAllocationSample"
                        GROUP BY "objectClass.name"
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
        // Nested field access with bracket notation is not supported. This test verifies the error
        // is handled correctly
        given().log()
                .all()
                .when()
                .pathParams("jvmId", selfJvmId, "filename", RECORDING_FILENAME)
                .formParam(
                        "query",
                        """
                        SELECT "objectClass"['name'] AS "class_name",
                               COUNT(*) AS "allocation_count"
                        FROM "JFR"."jdk.ObjectAllocationSample"
                        GROUP BY "objectClass"['name']
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
}
