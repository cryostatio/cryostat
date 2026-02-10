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

package io.cryostat.reports;

import static io.restassured.RestAssured.given;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.resources.S3StorageResource;
import io.cryostat.util.HttpMimeType;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.restassured.response.Response;
import io.vertx.core.json.JsonObject;
import jakarta.websocket.DeploymentException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class ReportGenerationCacheEnabledTest extends AbstractTransactionalTestBase
        implements QuarkusTestProfile {

    final ExecutorService worker = ForkJoinPool.commonPool();

    static final String TEST_RECORDING_NAME = "reportGenerationCacheEnabled";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.cache.enabled", "false",
                "cryostat.services.reports.memory-cache.enabled", "false",
                "cryostat.services.reports.storage-cache.enabled", "false",
                "quarkus.cache.caffeine.matchexpressions.maximum-size", "0",
                "quarkus.cache.caffeine.activereports.maximum-size", "0",
                "quarkus.cache.caffeine.archivedreports.maximum-size", "0",
                "quarkus.cache.caffeine.reports-aggregator.maximum-size", "0");
    }

    @BeforeEach
    void setupReportGenerationTest() {
        cleanupSelfActiveAndArchivedRecordings();
    }

    @AfterEach
    void cleanupReportGenerationTest() {
        cleanupSelfActiveAndArchivedRecordings();
    }

    private long getSelfReferenceTargetId() {
        if (selfId < 1) {
            defineSelfCustomTarget();
        }
        return selfId;
    }

    @Test
    void testGetArchivedCachedReport() throws Exception {
        long targetId = getSelfReferenceTargetId();

        // Create a recording
        Response postResponse =
                given().log()
                        .all()
                        .when()
                        .basePath("/api/v4/targets/{targetId}/recordings")
                        .pathParam("targetId", targetId)
                        .formParam("recordingName", TEST_RECORDING_NAME)
                        .formParam("duration", "5")
                        .formParam("events", "template=ALL")
                        .post()
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .extract()
                        .response();

        JsonObject activeRecording = new JsonObject(postResponse.body().asString());

        // Wait some time to get more recording data
        Thread.sleep(5_000);

        // Check that recording archiving concludes
        Future<JsonObject> archiveFuture =
                worker.submit(
                        () -> {
                            try {
                                return expectWebSocketNotification(
                                        "ArchiveRecordingSuccess", Duration.ofSeconds(15));
                            } catch (IOException | DeploymentException | InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        });

        // Save the recording to archive
        Response saveResponse =
                given().log()
                        .all()
                        .when()
                        .basePath("/api/v4/targets/{targetId}/recordings/{remoteId}")
                        .pathParam("targetId", targetId)
                        .pathParam("remoteId", activeRecording.getLong("remoteId"))
                        .contentType("text/plain;charset=UTF-8")
                        .body("SAVE")
                        .patch()
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(
                                Matchers.both(Matchers.greaterThanOrEqualTo(200))
                                        .and(Matchers.lessThan(400)))
                        .contentType("text/plain;charset=UTF-8")
                        .extract()
                        .response();

        String archiveJobId = saveResponse.body().asString();
        JsonObject notification = archiveFuture.get();
        MatcherAssert.assertThat(
                notification.getJsonObject("message").getMap(),
                Matchers.hasEntry("jobId", archiveJobId));
        String archivedRecordingName = notification.getJsonObject("message").getString("recording");
        MatcherAssert.assertThat(archivedRecordingName, Matchers.not(Matchers.blankOrNullString()));

        // Request a report for the archived recording
        Response jobIdResponse =
                given().log()
                        .all()
                        .when()
                        .header("Accept", HttpMimeType.JSON.mime())
                        .get(activeRecording.getString("reportUrl"))
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(
                                Matchers.both(Matchers.greaterThanOrEqualTo(200))
                                        .and(Matchers.lessThan(400)))
                        .contentType("text/plain")
                        .extract()
                        .response();

        // Check that report generation concludes
        Future<JsonObject> reportFuture =
                worker.submit(
                        () -> {
                            try {
                                return expectWebSocketNotification(
                                        "ReportSuccess", Duration.ofSeconds(15));
                            } catch (IOException | DeploymentException | InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        });

        // wait for report generation to complete
        String reportJobId = jobIdResponse.body().asString();
        notification = reportFuture.get();
        MatcherAssert.assertThat(
                notification.getJsonObject("message").getMap(),
                Matchers.equalTo(Map.of("jobId", reportJobId, "jvmId", selfJvmId)));

        // Request the cached report for the archived recording
        Response reportResponse =
                given().log()
                        .all()
                        .when()
                        .header("Accept", HttpMimeType.JSON.mime())
                        .get(activeRecording.getString("reportUrl"))
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(
                                Matchers.both(Matchers.greaterThanOrEqualTo(200))
                                        .and(Matchers.lessThan(400)))
                        .contentType("application/json")
                        .extract()
                        .response();

        JsonObject jsonResponse = new JsonObject(reportResponse.body().asString());
        MatcherAssert.assertThat(jsonResponse, Matchers.notNullValue());
        MatcherAssert.assertThat(
                jsonResponse.getMap(), Matchers.is(Matchers.aMapWithSize(Matchers.greaterThan(0))));
    }
}
