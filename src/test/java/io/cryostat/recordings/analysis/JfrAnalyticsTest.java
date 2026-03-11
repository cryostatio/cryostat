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
        // Verify the exact count from the static recording
        MatcherAssert.assertThat(result.get(0).get(0), Matchers.equalTo("9391"));
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
        MatcherAssert.assertThat(result.size(), Matchers.equalTo(10));

        String[][] expectedData = {
            {"2025-10-16 07:29:07", "C1 CompilerThread0", null, "java/lang/String", "81232"},
            {
                "2025-10-16 07:29:07",
                "VirtualThreads",
                "io.quarkus.jfr.runtime.runtime.JfrRuntimeBean$ExtensionEventTask.run()",
                "io/quarkus/jfr/runtime/runtime/ExtensionEvent",
                "39750912"
            },
            {
                "2025-10-16 07:29:07",
                "VirtualThreads",
                "java.io.BufferedReader.<init>",
                "[C",
                "49216"
            },
            {
                "2025-10-16 07:29:07",
                "VirtualThreads",
                "sun.nio.ch.FileChannelImpl.open",
                "sun/nio/ch/FileChannelImpl",
                "38432"
            },
            {
                "2025-10-16 07:29:07",
                "VirtualThreads",
                "java.io.BufferedReader.<init>",
                "[C",
                "41008"
            },
            {
                "2025-10-16 07:29:07",
                "JFR Periodic Tasks",
                "java.util.Arrays.copyOf",
                "[Ljava/lang/Object;",
                "22568"
            },
            {
                "2025-10-16 07:29:07",
                "VirtualThreads",
                "java.util.HashMap.resize()",
                "[Ljava/util/HashMap$Node;",
                "62664"
            },
            {
                "2025-10-16 07:29:07",
                "JFR Periodic Tasks",
                "java.lang.StringBuilder.toString()",
                "java/lang/String",
                "2712"
            },
            {
                "2025-10-16 07:29:07",
                "VirtualThreads",
                "javax.management.openmbean.CompositeDataSupport.makeMap",
                "java/util/TreeMap",
                "40520"
            },
            {
                "2025-10-16 07:29:07",
                "VirtualThreads",
                "java.util.TreeMap.keyIterator()",
                "java/util/TreeMap$KeyIterator",
                "38496"
            }
        };

        for (int i = 0; i < expectedData.length; i++) {
            MatcherAssert.assertThat(result.get(i).get(0), Matchers.equalTo(expectedData[i][0]));
            MatcherAssert.assertThat(
                    result.get(i).get(1), Matchers.containsString(expectedData[i][1]));
            if (expectedData[i][2] == null) {
                MatcherAssert.assertThat(result.get(i).get(2), Matchers.nullValue());
            } else {
                MatcherAssert.assertThat(
                        result.get(i).get(2), Matchers.containsString(expectedData[i][2]));
            }
            MatcherAssert.assertThat(
                    result.get(i).get(3), Matchers.containsString(expectedData[i][3]));

            MatcherAssert.assertThat(result.get(i).get(4), Matchers.equalTo(expectedData[i][4]));
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
        // Verify the exact count from the static recording
        MatcherAssert.assertThat(result.get(0).get(0), Matchers.equalTo("9391"));
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
        MatcherAssert.assertThat(result.size(), Matchers.equalTo(10));

        MatcherAssert.assertThat(result.get(0), Matchers.instanceOf(List.class));
        MatcherAssert.assertThat(result.get(0).size(), Matchers.equalTo(2));

        MatcherAssert.assertThat(result.get(0).get(0), Matchers.containsString("name = \"[B\""));
        MatcherAssert.assertThat(result.get(0).get(1), Matchers.equalTo("2596"));

        MatcherAssert.assertThat(
                result.get(1).get(0), Matchers.containsString("name = \"[Ljava/lang/Object;\""));
        MatcherAssert.assertThat(result.get(1).get(1), Matchers.equalTo("599"));

        MatcherAssert.assertThat(
                result.get(2).get(0), Matchers.containsString("name = \"java/lang/String\""));
        MatcherAssert.assertThat(result.get(2).get(1), Matchers.equalTo("319"));
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

        MatcherAssert.assertThat(result, Matchers.notNullValue());
        MatcherAssert.assertThat(result, Matchers.instanceOf(List.class));
        MatcherAssert.assertThat(result.size(), Matchers.lessThanOrEqualTo(15));
        MatcherAssert.assertThat(result.size(), Matchers.greaterThan(0));

        List<String> row0 = result.get(0);
        MatcherAssert.assertThat(row0.size(), Matchers.equalTo(2));
        MatcherAssert.assertThat(
                row0.get(0),
                Matchers.containsString(
                        "io.netty.util.internal.PlatformDependent.allocateUninitializedArray(int):328"));
        MatcherAssert.assertThat(
                row0.get(0),
                Matchers.containsString(
                        "io.netty.buffer.UnpooledUnsafeHeapByteBuf.allocateArray(int):39"));
        MatcherAssert.assertThat(
                row0.get(0),
                Matchers.containsString(
                        "io.vertx.core.buffer.impl.PartialPooledByteBufAllocator.heapBuffer(int):69"));
        MatcherAssert.assertThat(row0.get(1), Matchers.equalTo("213"));

        List<String> row1 = result.get(1);
        MatcherAssert.assertThat(row1.size(), Matchers.equalTo(2));
        MatcherAssert.assertThat(
                row1.get(0),
                Matchers.containsString(
                        "io.netty.util.internal.PlatformDependent.allocateUninitializedArray(int):328"));
        MatcherAssert.assertThat(
                row1.get(0),
                Matchers.containsString(
                        "io.netty.buffer.UnpooledUnsafeHeapByteBuf.allocateArray(int):39"));
        MatcherAssert.assertThat(
                row1.get(0),
                Matchers.containsString(
                        "io.vertx.core.buffer.impl.PartialPooledByteBufAllocator.buffer(int):39"));
        MatcherAssert.assertThat(row1.get(1), Matchers.equalTo("95"));

        List<String> row2 = result.get(2);
        MatcherAssert.assertThat(row2.size(), Matchers.equalTo(2));
        MatcherAssert.assertThat(
                row2.get(0),
                Matchers.containsString("io.vertx.core.buffer.impl.BufferImpl.getBytes():216"));
        MatcherAssert.assertThat(
                row2.get(0),
                Matchers.containsString(
                        "io.fabric8.kubernetes.client.vertx.VertxHttpRequest.lambda$consumeBytes$0(AsyncBody$Consumer,"
                            + " AsyncBody, HttpClientResponse, Buffer):79"));
        MatcherAssert.assertThat(
                row2.get(0),
                Matchers.containsString(
                        "io.vertx.core.streams.impl.InboundBuffer.handleEvent(Handler,"
                                + " Object):279"));
        MatcherAssert.assertThat(row2.get(1), Matchers.equalTo("82"));
    }

    @Test
    void testExecuteQueryWithClassNameFunction() {
        List<List<String>> result =
                given().log()
                        .all()
                        .when()
                        .pathParams("jvmId", selfJvmId, "filename", RECORDING_FILENAME)
                        .formParam(
                                "query",
                                """
                                SELECT CLASS_NAME("objectClass") AS "class_name",
                                       COUNT(*) AS "allocation_count"
                                FROM "JFR"."jdk.ObjectAllocationSample"
                                GROUP BY CLASS_NAME("objectClass")
                                ORDER BY COUNT(*) DESC
                                LIMIT 20
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

        // Response is array of arrays, each row has [class_name, allocation_count]
        MatcherAssert.assertThat(result, Matchers.notNullValue());
        MatcherAssert.assertThat(result, Matchers.instanceOf(List.class));
        MatcherAssert.assertThat(result.size(), Matchers.equalTo(20));

        MatcherAssert.assertThat(result.get(0), Matchers.instanceOf(List.class));
        MatcherAssert.assertThat(result.get(0).size(), Matchers.equalTo(2));

        MatcherAssert.assertThat(result.get(0).get(0), Matchers.equalTo("[B"));
        MatcherAssert.assertThat(result.get(0).get(1), Matchers.equalTo("2596"));

        MatcherAssert.assertThat(result.get(1).get(0), Matchers.equalTo("[Ljava.lang.Object;"));
        MatcherAssert.assertThat(result.get(1).get(1), Matchers.equalTo("599"));

        MatcherAssert.assertThat(result.get(2).get(0), Matchers.equalTo("java.lang.String"));
        MatcherAssert.assertThat(result.get(2).get(1), Matchers.equalTo("319"));
    }

    @Test
    void testExecuteQueryWithHasMatchingFrameFunction() {
        List<List<String>> result =
                given().log()
                        .all()
                        .when()
                        .pathParams("jvmId", selfJvmId, "filename", RECORDING_FILENAME)
                        .formParam(
                                "query",
                                """
                                SELECT TRUNCATE_STACKTRACE("stackTrace", 5) AS "stacktrace",
                                       COUNT(*) AS "allocation_count"
                                FROM "JFR"."jdk.ObjectAllocationSample"
                                WHERE HAS_MATCHING_FRAME("stackTrace", '.*java\\.util\\..*')
                                GROUP BY TRUNCATE_STACKTRACE("stackTrace", 5)
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

        // Response is array of arrays, each row has [stacktrace, allocation_count]
        MatcherAssert.assertThat(result, Matchers.notNullValue());
        MatcherAssert.assertThat(result, Matchers.instanceOf(List.class));
        MatcherAssert.assertThat(result.size(), Matchers.equalTo(10));

        String[][] expectedResults = {
            {"java.util.Arrays.copyOfRangeByte", "97"},
            {"java.lang.StringUTF16.compress", "93"},
            {"java.util.HashMap.newNode", "88"},
            {
                "com.fasterxml.jackson.databind.deser.std.UntypedObjectDeserializerNR$Scope.finishBranchObject",
                "68"
            },
            {"jdk.internal.vm.Continuation.doYield", "67"},
            {
                "com.fasterxml.jackson.databind.deser.std.UntypedObjectDeserializerNR$Scope.childObject",
                "63"
            },
            {"io.fabric8.kubernetes.client.http.BufferUtil.toArray", "62"},
            {"java.util.LinkedHashMap.newNode", "59"},
            {"java.io.BufferedInputStream.getBufIfOpen", "56"},
            {"com.fasterxml.jackson.core.util.TextBuffer.setCurrentAndReturn", "51"}
        };

        for (int i = 0; i < expectedResults.length; i++) {
            MatcherAssert.assertThat(result.get(i).size(), Matchers.equalTo(2));
            MatcherAssert.assertThat(
                    result.get(i).get(0), Matchers.containsString(expectedResults[i][0]));
            MatcherAssert.assertThat(result.get(i).get(1), Matchers.equalTo(expectedResults[i][1]));
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

    @Test
    void testExecuteQuerySelectingMultipleColumns() {
        List<List<String>> result =
                given().log()
                        .all()
                        .when()
                        .pathParams("jvmId", selfJvmId, "filename", RECORDING_FILENAME)
                        .formParam(
                                "query",
                                """
                                SELECT "startTime", "objectClass", "weight", "stackTrace"
                                FROM jfr."jdk.ObjectAllocationSample"
                                ORDER by "startTime"
                                LIMIT 1
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

        MatcherAssert.assertThat(result, Matchers.notNullValue());
        MatcherAssert.assertThat(result, Matchers.instanceOf(List.class));
        MatcherAssert.assertThat(result.size(), Matchers.equalTo(1));

        List<String> firstRow = result.get(0);
        MatcherAssert.assertThat(firstRow, Matchers.instanceOf(List.class));
        MatcherAssert.assertThat(firstRow.size(), Matchers.equalTo(4));

        MatcherAssert.assertThat(firstRow.get(0), Matchers.equalTo("2025-10-16 07:29:07"));
        MatcherAssert.assertThat(
                firstRow.get(1), Matchers.containsString("name = \"java/lang/String\""));
        MatcherAssert.assertThat(firstRow.get(2), Matchers.equalTo("81232"));
        MatcherAssert.assertThat(firstRow.get(3), Matchers.nullValue());
    }

    @Test
    void testExecuteQueryWithClassNameFunctionInSelect() {
        List<List<String>> result =
                given().log()
                        .all()
                        .when()
                        .pathParams("jvmId", selfJvmId, "filename", RECORDING_FILENAME)
                        .formParam(
                                "query",
                                """
                                SELECT CLASS_NAME("objectClass") as className
                                FROM jfr."jdk.ObjectAllocationSample"
                                ORDER by "startTime"
                                LIMIT 1
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

        MatcherAssert.assertThat(result, Matchers.notNullValue());
        MatcherAssert.assertThat(result, Matchers.instanceOf(List.class));
        MatcherAssert.assertThat(result.size(), Matchers.equalTo(1));

        List<String> firstRow = result.get(0);
        MatcherAssert.assertThat(firstRow, Matchers.instanceOf(List.class));
        MatcherAssert.assertThat(firstRow.size(), Matchers.equalTo(1));

        MatcherAssert.assertThat(firstRow.get(0), Matchers.equalTo("java.lang.String"));
    }

    @Test
    void testThreadStartEndJoin() {
        List<List<String>> result =
                given().log()
                        .all()
                        .when()
                        .pathParams("jvmId", selfJvmId, "filename", RECORDING_FILENAME)
                        .formParam(
                                "query",
                                """
                                SELECT ts."parentThread"."javaName", ts."thread"."javaName", ts."thread"."javaThreadId", te."thread"."javaName", te."thread"."javaThreadId"
                                FROM jfr."jdk.ThreadStart" ts
                                LEFT JOIN jfr."jdk.ThreadEnd" te ON ts."thread"."javaThreadId" = te."thread"."javaThreadId"
                                ORDER BY ts."thread"."javaThreadId"
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

        // Response is array of arrays with thread information
        // Each row has 5 columns: [parentThread.javaName, thread.javaName,
        // thread.javaThreadId, te.thread.javaName, te.thread.javaThreadId]
        MatcherAssert.assertThat(result, Matchers.notNullValue());
        MatcherAssert.assertThat(result, Matchers.instanceOf(List.class));
        MatcherAssert.assertThat(result.size(), Matchers.greaterThan(0));

        // Verify first row: thread that hasn't ended (LEFT JOIN returns nulls)
        MatcherAssert.assertThat(result.get(0).get(0), Matchers.equalTo("executor-thread-30"));
        MatcherAssert.assertThat(
                result.get(0).get(1), Matchers.equalTo("executor-thread-0 (scheduler)"));
        MatcherAssert.assertThat(result.get(0).get(2), Matchers.equalTo("19"));
        MatcherAssert.assertThat(result.get(0).get(3), Matchers.nullValue());
        MatcherAssert.assertThat(result.get(0).get(4), Matchers.nullValue());

        // Verify a row where thread has ended (JOIN matches)
        MatcherAssert.assertThat(
                result.get(2).get(0), Matchers.equalTo("vert.x-eventloop-thread-0"));
        MatcherAssert.assertThat(result.get(2).get(1), Matchers.equalTo("Thread-8"));
        MatcherAssert.assertThat(result.get(2).get(2), Matchers.equalTo("422"));
        MatcherAssert.assertThat(result.get(2).get(3), Matchers.equalTo("Thread-8"));
        MatcherAssert.assertThat(result.get(2).get(4), Matchers.equalTo("422"));
    }
}
