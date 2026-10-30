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
package io.cryostat.recordings;

import static io.restassured.RestAssured.given;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.cryostat.AgentTestBase;
import io.cryostat.resources.AgentApplicationResource;
import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Covers starting a Flight Recording on a Cryostat Agent target with async-profiler's {@code
 * jfrsync} mode enabled, so that async-profiler-sourced stack samples are merged into the same
 * recording as the regular JDK JFR event set, rather than the standalone async-profiler-only
 * profile feature exercised by {@link io.cryostat.asyncprofiler.AgentAsyncProfilerTest}.
 */
@QuarkusTest
@QuarkusTestResource(value = AgentApplicationResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class AgentJfrSyncRecordingTest extends AgentTestBase {

    static final String CONTINUOUS_TEMPLATE = "template=Continuous,type=TARGET";
    static final String RECORDING_NAME = AgentJfrSyncRecordingTest.class.getSimpleName();

    @Test
    void testStartRecordingWithAsyncProfilerEnabled() {
        long targetId = target.id();

        int recordingId =
                given().log()
                        .all()
                        .pathParams("targetId", targetId)
                        .when()
                        .formParam("recordingName", RECORDING_NAME)
                        .formParam("events", CONTINUOUS_TEMPLATE)
                        .formParam("duration", 10)
                        .formParam("asprofEvents", "cpu")
                        .post("/api/v4/targets/{targetId}/recordings")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .contentType(ContentType.JSON)
                        .body("name", Matchers.equalTo(RECORDING_NAME))
                        .body("state", Matchers.equalTo("RUNNING"))
                        .body("duration", Matchers.equalTo(10_000))
                        .body(
                                "metadata.labels.find { it.key == 'asprof.events' }.value",
                                Matchers.equalTo("cpu"))
                        .and()
                        .extract()
                        .jsonPath()
                        .getInt("remoteId");

        try {
            given().log()
                    .all()
                    .pathParams("targetId", targetId)
                    .when()
                    .get("/api/v4/targets/{targetId}/recordings")
                    .then()
                    .log()
                    .all()
                    .and()
                    .assertThat()
                    .statusCode(200)
                    .body("$.size()", Matchers.equalTo(1))
                    .body("[0].name", Matchers.equalTo(RECORDING_NAME))
                    .body(
                            "[0].metadata.labels.find { it.key == 'asprof.events' }.value",
                            Matchers.equalTo("cpu"));
        } finally {
            given().log()
                    .all()
                    .pathParams("targetId", targetId, "recordingId", recordingId)
                    .when()
                    .delete("/api/v4/targets/{targetId}/recordings/{recordingId}")
                    .then()
                    .log()
                    .all()
                    .and()
                    .assertThat()
                    .statusCode(204);
        }

        given().log()
                .all()
                .pathParams("targetId", targetId)
                .when()
                .get("/api/v4/targets/{targetId}/recordings")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200)
                .body("$.size()", Matchers.equalTo(0));
    }

    /**
     * Verifies that a {@code jfrsync}-enabled recording actually produces a single merged {@code
     * .jfr} file containing both async-profiler-sourced samples and the regular JDK JFR event set,
     * rather than two independent streams. Relies on the existing {@code archiveOnStop} facility
     * (rather than manually downloading and re-uploading the recording) to get the finished, merged
     * file into the target's archives, asserts it actually lands there, and then drives
     * jfr-analytics ({@link io.cryostat.recordings.analysis.JfrAnalytics}, covered independently by
     * {@code JfrAnalyticsTest}) directly against that archived file to assert on the actual decoded
     * event content, rather than merely the recording's metadata/API shape.
     */
    @Test
    void testJfrSyncRecordingMergesAsyncProfilerAndJdkEvents() throws Exception {
        long targetId = target.id();
        String jvmId = target.jvmId();
        String recordingName = RECORDING_NAME + "Merge";

        int recordingId =
                given().log()
                        .all()
                        .pathParams("targetId", targetId)
                        .when()
                        .formParam("recordingName", recordingName)
                        .formParam("events", CONTINUOUS_TEMPLATE)
                        .formParam("duration", 30)
                        .formParam("archiveOnStop", true)
                        .formParam("asprofEvents", "alloc", "wall")
                        .post("/api/v4/targets/{targetId}/recordings")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .body(
                                "metadata.labels.find { it.key == 'asprof.events' }.value",
                                Matchers.equalTo("alloc,wall"))
                        .and()
                        .extract()
                        .jsonPath()
                        .getInt("remoteId");

        String archivedFilename = null;
        try {
            generateWorkloadActivity(Duration.ofSeconds(30), Duration.ofSeconds(5));

            given().log()
                    .all()
                    .pathParams("targetId", targetId)
                    .when()
                    .post("/api/v4.3/targets/{targetId}/recordings_sync")
                    .then()
                    .log()
                    .all()
                    .and()
                    .assertThat()
                    .statusCode(204);

            webSocketClient.expectNotification("ArchivedRecordingCreated", Duration.ofSeconds(90));

            // Assert the merged recording actually landed in the target's archives, and obtain its
            // filename from there rather than assuming a naming scheme.
            List<Map<String, Object>> archives =
                    given().log()
                            .all()
                            .pathParams("jvmId", jvmId)
                            .when()
                            .get("/api/beta/recordings/{jvmId}")
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
            List<Map<String, Object>> matching =
                    archives.stream()
                            .filter(a -> ((String) a.get("name")).contains(recordingName))
                            .toList();
            MatcherAssert.assertThat(matching, Matchers.hasSize(1));
            archivedFilename = (String) matching.get(0).get("name");

            // Evidence of the regular JDK JFR event set: "jdk.CPULoad" is a periodic event from
            // the standard FlightRecorder machinery, unaffected by jfrsync's "alloc" event mask.
            List<List<String>> jdkEventCount =
                    given().log()
                            .all()
                            .pathParams("jvmId", jvmId, "filename", archivedFilename)
                            .when()
                            .formParam(
                                    "query",
                                    """
                                    SELECT COUNT(*) FROM "JFR"."jdk.CPULoad"
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
            MatcherAssert.assertThat(
                    Integer.parseInt(jdkEventCount.get(0).get(0)), Matchers.greaterThan(0));

            // Evidence of async-profiler-sourced samples merged into the same file: asprof's
            // native JFR writer populates these event types directly when the corresponding asprof
            // event is requested, replacing (not supplementing) the JDK's own sampling producer for
            // them (which JfrSync disables for the duration of the recording). "alloc" samples land
            // in either the "in new TLAB" or "outside TLAB" event depending on object/TLAB sizing
            // isn't time-driven). "wall" is added alongside it and sampled into jdk.ExecutionSample
            // on a fixed timer regardless of target activity, giving a deterministic, time-driven
            // source of asprof-sourced evidence independent of the target's allocation behavior;
            // all three tables are summed so either source satisfies the assertion.
            List<List<String>> asprofEventCount =
                    given().log()
                            .all()
                            .pathParams("jvmId", jvmId, "filename", archivedFilename)
                            .when()
                            .formParam(
                                    "query",
                                    """
                                    SELECT
                                      (SELECT COUNT(*) FROM "JFR"."jdk.ObjectAllocationInNewTLAB")
                                      + (SELECT COUNT(*) FROM "JFR"."jdk.ObjectAllocationOutsideTLAB")
                                      + (SELECT COUNT(*) FROM "JFR"."jdk.ExecutionSample")
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
            MatcherAssert.assertThat(
                    Integer.parseInt(asprofEventCount.get(0).get(0)), Matchers.greaterThan(0));
        } finally {
            if (archivedFilename != null) {
                given().log()
                        .all()
                        .pathParams("connectUrl", target.connectUrl(), "filename", archivedFilename)
                        .when()
                        .delete("/api/beta/recordings/{connectUrl}/{filename}")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(204);
            }
            given().log()
                    .all()
                    .pathParams("targetId", targetId, "recordingId", recordingId)
                    .when()
                    .delete("/api/v4/targets/{targetId}/recordings/{recordingId}")
                    .then()
                    .log()
                    .all();
        }
    }

    /**
     * Verifies that a CUSTOM event template uploaded to the Cryostat server (not a TARGET template
     * already resident on the agent) can be used as the {@code jfrsync} configuration for an
     * Agent-HTTP recording. A CUSTOM template's XML is resolved server-side exactly as it would be
     * for an ordinary (non-jfrsync) recording and shipped to the agent, which materializes it as a
     * temp {@code .jfc} file and points async-profiler's {@code jfrsync=} option at it - this test
     * exercises that whole path end-to-end.
     */
    @Test
    void testJfrSyncRecordingWithUploadedCustomEventTemplate() throws Exception {
        File templateFile =
                new File(
                        getClass()
                                .getClassLoader()
                                .getResource("io/cryostat/recordings/MyTemplate.jfc")
                                .toURI());

        String templateName =
                given().log()
                        .all()
                        .contentType(ContentType.MULTIPART)
                        .multiPart("template", templateFile, "application/xml")
                        .when()
                        .post("/api/v4/event_templates")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .contentType(ContentType.JSON)
                        .body("type", Matchers.equalTo("CUSTOM"))
                        .and()
                        .extract()
                        .jsonPath()
                        .getString("name");

        try {
            long targetId = target.id();
            String recordingName = RECORDING_NAME + "CustomTemplate";

            int recordingId =
                    given().log()
                            .all()
                            .pathParams("targetId", targetId)
                            .when()
                            .formParam("recordingName", recordingName)
                            .formParam(
                                    "events",
                                    String.format("template=%s,type=CUSTOM", templateName))
                            .formParam("duration", 10)
                            .formParam("asprofEvents", "cpu")
                            .post("/api/v4/targets/{targetId}/recordings")
                            .then()
                            .log()
                            .all()
                            .and()
                            .assertThat()
                            .statusCode(201)
                            .contentType(ContentType.JSON)
                            .body("name", Matchers.equalTo(recordingName))
                            .body("state", Matchers.equalTo("RUNNING"))
                            .body(
                                    "metadata.labels.find { it.key == 'template.name' }.value",
                                    Matchers.equalTo(templateName))
                            .body(
                                    "metadata.labels.find { it.key == 'template.type' }.value",
                                    Matchers.equalTo("CUSTOM"))
                            .body(
                                    "metadata.labels.find { it.key == 'asprof.events' }.value",
                                    Matchers.equalTo("cpu"))
                            .and()
                            .extract()
                            .jsonPath()
                            .getInt("remoteId");

            try {
                given().log()
                        .all()
                        .pathParams("targetId", targetId)
                        .when()
                        .get("/api/v4/targets/{targetId}/recordings")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .body("$.size()", Matchers.equalTo(1))
                        .body("[0].name", Matchers.equalTo(recordingName))
                        .body(
                                "[0].metadata.labels.find { it.key == 'template.name' }.value",
                                Matchers.equalTo(templateName));
            } finally {
                given().log()
                        .all()
                        .pathParams("targetId", targetId, "recordingId", recordingId)
                        .when()
                        .delete("/api/v4/targets/{targetId}/recordings/{recordingId}")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(204);
            }
        } finally {
            given().log()
                    .all()
                    .pathParams("templateName", templateName)
                    .when()
                    .delete("/api/v4/event_templates/{templateName}")
                    .then()
                    .log()
                    .all();
        }
    }

    /**
     * Documents and validates the contract for a {@code jfrsync}-enabled recording with no fixed
     * duration ("Continuous" in Cryostat's usual terminology: the {@code duration} form parameter
     * is simply omitted). Per {@code cryostat-agent}'s {@code RecordingsContext}, this is already a
     * first-class case: {@code recording.setDuration(req.duration > 0 ? ... : null)} is applied
     * identically on the jfrsync path and the ordinary path, so {@code duration <= 0} means no JDK
     * duration timer is set at all - the recording simply runs until explicitly stopped.
     *
     * <p>This also exercises the *explicit* stop path (a {@code PATCH .../{id}} "stop" command)
     * rather than the duration-timer-elapsing path already covered by {@link
     * #testJfrSyncRecordingMergesAsyncProfilerAndJdkEvents()}.
     */
    @Test
    void testJfrSyncRecordingWithContinuousDuration() {
        long targetId = target.id();
        String recordingName = RECORDING_NAME + "Continuous";

        int recordingId =
                given().log()
                        .all()
                        .pathParams("targetId", targetId)
                        .when()
                        .formParam("recordingName", recordingName)
                        .formParam("events", CONTINUOUS_TEMPLATE)
                        .formParam("asprofEvents", "cpu")
                        // no "duration" param -- continuous/unbounded
                        .post("/api/v4/targets/{targetId}/recordings")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .contentType(ContentType.JSON)
                        .body("state", Matchers.equalTo("RUNNING"))
                        .body("continuous", Matchers.equalTo(true))
                        .body("duration", Matchers.equalTo(0))
                        .body(
                                "metadata.labels.find { it.key == 'asprof.events' }.value",
                                Matchers.equalTo("cpu"))
                        .and()
                        .extract()
                        .jsonPath()
                        .getInt("remoteId");

        try {
            given().log()
                    .all()
                    .pathParams("targetId", targetId, "recordingId", recordingId)
                    .when()
                    .body("stop")
                    .patch("/api/v4/targets/{targetId}/recordings/{recordingId}")
                    .then()
                    .log()
                    .all()
                    .and()
                    .assertThat()
                    .statusCode(204);

            given().log()
                    .all()
                    .pathParams("targetId", targetId)
                    .when()
                    .get("/api/v4/targets/{targetId}/recordings")
                    .then()
                    .log()
                    .all()
                    .and()
                    .assertThat()
                    .statusCode(200)
                    .body("$.size()", Matchers.equalTo(1))
                    .body("[0].name", Matchers.equalTo(recordingName))
                    .body("[0].state", Matchers.equalTo("STOPPED"));
        } finally {
            given().log()
                    .all()
                    .pathParams("targetId", targetId, "recordingId", recordingId)
                    .when()
                    .delete("/api/v4/targets/{targetId}/recordings/{recordingId}")
                    .then()
                    .log()
                    .all()
                    .and()
                    .assertThat()
                    .statusCode(204);
        }
    }

    @Test
    void testJfrSyncRecordingWithToDiskDisabled() {
        long targetId = target.id();
        String recordingName = RECORDING_NAME + "ToDiskFalse";

        int recordingId =
                given().log()
                        .all()
                        .pathParams("targetId", targetId)
                        .when()
                        .formParam("recordingName", recordingName)
                        .formParam("events", CONTINUOUS_TEMPLATE)
                        .formParam("duration", 10)
                        .formParam("toDisk", false)
                        .formParam("asprofEvents", "cpu")
                        .post("/api/v4/targets/{targetId}/recordings")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .contentType(ContentType.JSON)
                        .body("state", Matchers.equalTo("RUNNING"))
                        // Observed: JfrSync's own hardcoded setToDisk(true) wins -- the
                        // requested toDisk=false is not honoured for a jfrsync recording. This
                        // is consistent with jfrsync's native writer requiring a destination
                        // file to exist at all.
                        .body("toDisk", Matchers.equalTo(true))
                        .and()
                        .extract()
                        .jsonPath()
                        .getInt("remoteId");

        given().log()
                .all()
                .pathParams("targetId", targetId, "recordingId", recordingId)
                .when()
                .delete("/api/v4/targets/{targetId}/recordings/{recordingId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);
    }

    @Test
    void testJfrSyncRecordingWithMaxAgeAndMaxSizeBounds() {
        long targetId = target.id();
        String recordingName = RECORDING_NAME + "MaxAgeMaxSize";
        long maxAgeSeconds = 60;
        long maxSizeBytes = 1024 * 1024;

        int recordingId =
                given().log()
                        .all()
                        .pathParams("targetId", targetId)
                        .when()
                        .formParam("recordingName", recordingName)
                        .formParam("events", CONTINUOUS_TEMPLATE)
                        .formParam("toDisk", true)
                        .formParam("maxAge", maxAgeSeconds)
                        .formParam("maxSize", maxSizeBytes)
                        .formParam("asprofEvents", "cpu")
                        // no "duration" param -- continuous, bounded instead by maxAge/maxSize
                        .post("/api/v4/targets/{targetId}/recordings")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .contentType(ContentType.JSON)
                        .body("state", Matchers.equalTo("RUNNING"))
                        .body("continuous", Matchers.equalTo(true))
                        .body("toDisk", Matchers.equalTo(true))
                        .body("maxAge", Matchers.equalTo((int) (maxAgeSeconds * 1000)))
                        .body("maxSize", Matchers.equalTo((int) maxSizeBytes))
                        .and()
                        .extract()
                        .jsonPath()
                        .getInt("remoteId");

        try {
            given().log()
                    .all()
                    .pathParams("targetId", targetId)
                    .when()
                    .get("/api/v4/targets/{targetId}/recordings")
                    .then()
                    .log()
                    .all()
                    .and()
                    .assertThat()
                    .statusCode(200)
                    .body("[0].maxAge", Matchers.equalTo((int) (maxAgeSeconds * 1000)))
                    .body("[0].maxSize", Matchers.equalTo((int) maxSizeBytes));
        } finally {
            given().log()
                    .all()
                    .pathParams("targetId", targetId, "recordingId", recordingId)
                    .when()
                    .delete("/api/v4/targets/{targetId}/recordings/{recordingId}")
                    .then()
                    .log()
                    .all()
                    .and()
                    .assertThat()
                    .statusCode(204);
        }
    }

    @Test
    void testJfrSyncRecordingReplaceSemantics() {
        long targetId = target.id();
        String recordingName = RECORDING_NAME + "Replace";

        int firstRecordingId =
                given().log()
                        .all()
                        .pathParams("targetId", targetId)
                        .when()
                        .formParam("recordingName", recordingName)
                        .formParam("events", CONTINUOUS_TEMPLATE)
                        .formParam("duration", 30)
                        .formParam("asprofEvents", "cpu")
                        .post("/api/v4/targets/{targetId}/recordings")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .body(
                                "metadata.labels.find { it.key == 'asprof.events' }.value",
                                Matchers.equalTo("cpu"))
                        .and()
                        .extract()
                        .jsonPath()
                        .getInt("remoteId");

        int secondRecordingId = -1;
        try {
            secondRecordingId =
                    given().log()
                            .all()
                            .pathParams("targetId", targetId)
                            .when()
                            .formParam("recordingName", recordingName)
                            .formParam("events", CONTINUOUS_TEMPLATE)
                            .formParam("duration", 30)
                            .formParam("replace", "ALWAYS")
                            .formParam("asprofEvents", "alloc")
                            .post("/api/v4/targets/{targetId}/recordings")
                            .then()
                            .log()
                            .all()
                            .and()
                            .assertThat()
                            .statusCode(201)
                            .contentType(ContentType.JSON)
                            .body("name", Matchers.equalTo(recordingName))
                            .body("state", Matchers.equalTo("RUNNING"))
                            .body(
                                    "metadata.labels.find { it.key == 'asprof.events' }.value",
                                    Matchers.equalTo("alloc"))
                            .and()
                            .extract()
                            .jsonPath()
                            .getInt("remoteId");

            MatcherAssert.assertThat(secondRecordingId, Matchers.not(Matchers.equalTo(-1)));

            given().log()
                    .all()
                    .pathParams("targetId", targetId)
                    .when()
                    .get("/api/v4/targets/{targetId}/recordings")
                    .then()
                    .log()
                    .all()
                    .and()
                    .assertThat()
                    .statusCode(200)
                    .body("$.size()", Matchers.equalTo(1))
                    .body("[0].name", Matchers.equalTo(recordingName))
                    .body(
                            "[0].metadata.labels.find { it.key == 'asprof.events' }.value",
                            Matchers.equalTo("alloc"));
        } finally {
            int idToDelete = secondRecordingId >= 0 ? secondRecordingId : firstRecordingId;
            given().log()
                    .all()
                    .pathParams("targetId", targetId, "recordingId", idToDelete)
                    .when()
                    .delete("/api/v4/targets/{targetId}/recordings/{recordingId}")
                    .then()
                    .log()
                    .all();
        }
    }

    /**
     * Documents observed behavior when a STANDALONE async-profiler session (the feature exercised
     * by {@link io.cryostat.asyncprofiler.AgentAsyncProfilerTest}, started via {@code POST
     * /api/beta/targets/{id}/async-profiler}) is requested on a target that already has a {@code
     * jfrsync}-enabled recording running. async-profiler's native layer supports only one active
     * profiling session per target.
     */
    @Test
    void testStandaloneAsyncProfilerRejectedWhileJfrSyncRecordingRunning() throws Exception {
        long targetId = target.id();
        String recordingName = RECORDING_NAME + "ThenStandalone";

        int recordingId =
                given().log()
                        .all()
                        .pathParams("targetId", targetId)
                        .when()
                        .formParam("recordingName", recordingName)
                        .formParam("events", CONTINUOUS_TEMPLATE)
                        .formParam("duration", 30)
                        .formParam("asprofEvents", "cpu")
                        .post("/api/v4/targets/{targetId}/recordings")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .and()
                        .extract()
                        .jsonPath()
                        .getInt("remoteId");

        try {
            Map<String, Object> requestBody = Map.of("events", List.of("cpu"), "duration", 5);
            given().log()
                    .all()
                    .pathParams("targetId", targetId)
                    .contentType(ContentType.JSON)
                    .body(requestBody)
                    .when()
                    .post("/api/beta/targets/{targetId}/async-profiler")
                    .then()
                    .log()
                    .all()
                    .and()
                    .assertThat()
                    .statusCode(400);
        } finally {
            given().log()
                    .all()
                    .pathParams("targetId", targetId, "recordingId", recordingId)
                    .when()
                    .delete("/api/v4/targets/{targetId}/recordings/{recordingId}")
                    .then()
                    .log()
                    .all();
        }
    }

    /**
     * Reverse order of {@link #testStandaloneAsyncProfilerRejectedWhileJfrSyncRecordingRunning()}:
     * a standalone async-profiler session is started first, then a jfrsync-enabled recording is
     * requested on the same target.
     */
    @Test
    void testJfrSyncRecordingRejectedWhileStandaloneAsyncProfilerRunning() throws Exception {
        long targetId = target.id();
        String recordingName = RECORDING_NAME + "AfterStandalone";

        Map<String, Object> requestBody = Map.of("events", List.of("cpu"), "duration", 5);
        String profileId =
                given().log()
                        .all()
                        .pathParams("targetId", targetId)
                        .contentType(ContentType.JSON)
                        .body(requestBody)
                        .when()
                        .post("/api/beta/targets/{targetId}/async-profiler")
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

        try {
            given().log()
                    .all()
                    .pathParams("targetId", targetId)
                    .when()
                    .formParam("recordingName", recordingName)
                    .formParam("events", CONTINUOUS_TEMPLATE)
                    .formParam("duration", 10)
                    .formParam("asprofEvents", "cpu")
                    .post("/api/v4/targets/{targetId}/recordings")
                    .then()
                    .log()
                    .all()
                    .and()
                    .assertThat()
                    .statusCode(400);
        } finally {
            waitForStandaloneAsyncProfilerToStop(targetId, Duration.ofSeconds(30));
            given().log()
                    .all()
                    .pathParams("targetId", targetId, "profileId", profileId)
                    .when()
                    .delete("/api/beta/targets/{targetId}/async-profiler/{profileId}")
                    .then()
                    .log()
                    .all();
        }
    }

    @Test
    void testJfrSyncRecordingWithInvalidAsprofEventName() {
        long targetId = target.id();
        String recordingName = RECORDING_NAME + "InvalidEvent";

        given().log()
                .all()
                .pathParams("targetId", targetId)
                .when()
                .formParam("recordingName", recordingName)
                .formParam("events", CONTINUOUS_TEMPLATE)
                .formParam("duration", 10)
                .formParam("asprofEvents", "this-is-not-a-real-event")
                .post("/api/v4/targets/{targetId}/recordings")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    /**
     * Basic backward-compatibility/opt-in guard: omitting {@code asprofEvents} entirely must behave
     * exactly like an ordinary (non-jfrsync) recording, with no {@code asprof.events} label leaking
     * in.
     */
    @Test
    void testRecordingWithoutAsprofEventsHasNoAsprofLabel() {
        long targetId = target.id();
        String recordingName = RECORDING_NAME + "NoAsprof";

        int recordingId =
                given().log()
                        .all()
                        .pathParams("targetId", targetId)
                        .when()
                        .formParam("recordingName", recordingName)
                        .formParam("events", CONTINUOUS_TEMPLATE)
                        .formParam("duration", 10)
                        // no "asprofEvents" param at all
                        .post("/api/v4/targets/{targetId}/recordings")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .contentType(ContentType.JSON)
                        .body("name", Matchers.equalTo(recordingName))
                        .body("state", Matchers.equalTo("RUNNING"))
                        .body(
                                "metadata.labels.find { it.key == 'asprof.events' }",
                                Matchers.nullValue())
                        .and()
                        .extract()
                        .jsonPath()
                        .getInt("remoteId");

        given().log()
                .all()
                .pathParams("targetId", targetId, "recordingId", recordingId)
                .when()
                .delete("/api/v4/targets/{targetId}/recordings/{recordingId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);
    }

    @Test
    void testJfrSyncRecordingRejectedWhenOtherRecordingsActive() {
        long targetId = target.id();
        String firstRecordingName = RECORDING_NAME + "First";
        String jfrSyncRecordingName = RECORDING_NAME + "JfrSync";

        // Start a regular recording first
        int firstRecordingId =
                given().log()
                        .all()
                        .pathParams("targetId", targetId)
                        .when()
                        .formParam("recordingName", firstRecordingName)
                        .formParam("events", CONTINUOUS_TEMPLATE)
                        .formParam("duration", 20)
                        .post("/api/v4/targets/{targetId}/recordings")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .contentType(ContentType.JSON)
                        .body("state", Matchers.equalTo("RUNNING"))
                        .and()
                        .extract()
                        .jsonPath()
                        .getInt("remoteId");

        try {
            // Attempt to start a jfrsync recording while the first one is still active
            // This should be rejected with 400 Bad Request
            given().log()
                    .all()
                    .pathParams("targetId", targetId)
                    .when()
                    .formParam("recordingName", jfrSyncRecordingName)
                    .formParam("events", CONTINUOUS_TEMPLATE)
                    .formParam("duration", 10)
                    .formParam("asprofEvents", "cpu")
                    .post("/api/v4/targets/{targetId}/recordings")
                    .then()
                    .log()
                    .all()
                    .and()
                    .assertThat()
                    .statusCode(400);
        } finally {
            // Clean up the first recording
            given().log()
                    .all()
                    .pathParams("targetId", targetId, "recordingId", firstRecordingId)
                    .when()
                    .delete("/api/v4/targets/{targetId}/recordings/{recordingId}")
                    .then()
                    .log()
                    .all()
                    .and()
                    .assertThat()
                    .statusCode(204);
        }
    }

    /**
     * Polls the standalone async-profiler status endpoint until it reports a non-RUNNING status (or
     * the timeout elapses). Needed after deliberately leaving a standalone profiling session active
     * to provoke a conflict (see {@link
     * #testJfrSyncRecordingRejectedWhileStandaloneAsyncProfilerRunning()}): there is no API to
     * forcibly stop a still-RUNNING standalone profile (only a finished one's file can be deleted),
     * so the only way to ensure the native profiler is free for subsequent tests in this class is
     * to wait for its own duration to naturally elapse.
     */
    private static void waitForStandaloneAsyncProfilerToStop(long targetId, Duration timeout)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            String status =
                    given().pathParams("targetId", targetId)
                            .when()
                            .get("/api/beta/targets/{targetId}/async-profiler/status")
                            .then()
                            .extract()
                            .body()
                            .jsonPath()
                            .getString("status");
            if (!"RUNNING".equals(status)) {
                return;
            }
            Thread.sleep(1_000);
        }
    }

    private static void generateWorkloadActivity(Duration total, Duration interval)
            throws InterruptedException {
        int appPort = AgentApplicationResource.appPort;
        MatcherAssert.assertThat(
                "sample application HTTP port must be known to generate workload activity",
                appPort,
                Matchers.greaterThan(0));
        URI workUri = URI.create(String.format("http://localhost:%d/work", appPort));
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(workUri).GET().build();

        long deadline = System.currentTimeMillis() + total.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                client.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
            }
            Thread.sleep(interval.toMillis());
        }
    }
}
