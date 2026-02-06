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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.resources.GrafanaResource;
import io.cryostat.resources.JFRDatasourceResource;
import io.cryostat.resources.S3StorageResource;
import io.cryostat.util.HttpMimeType;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@WithTestResource(
        value = S3StorageResource.class,
        scope = TestResourceScope.MATCHING_RESOURCES,
        parallel = true)
@WithTestResource(value = GrafanaResource.class, scope = TestResourceScope.GLOBAL, parallel = true)
@WithTestResource(
        value = JFRDatasourceResource.class,
        scope = TestResourceScope.GLOBAL,
        parallel = true)
public class UploadRecordingTest extends AbstractTransactionalTestBase {

    // TODO this should be a constant somewhere in the server sources
    public static final String DATASOURCE_FILENAME = "cryostat-analysis.jfr";
    static final String RECORDING_NAME = "upload_recording_it_rec";
    static final int RECORDING_DURATION_SECONDS = 10;
    static final int REQUEST_TIMEOUT_SECONDS = 30;

    @BeforeEach
    void setupUploadRecordingTest() throws Exception {
        // MUST call getSelfReferenceTargetId() BEFORE cleanup to ensure target exists
        getSelfReferenceTargetId();
        cleanupSelfActiveAndArchivedRecordings();
    }

    @AfterEach
    void cleanupUploadRecordingTest() throws Exception {
        cleanupSelfActiveAndArchivedRecordings();
    }

    @Test
    public void shouldLoadRecordingToDatasource() throws Exception {
        long targetId = getSelfReferenceTargetId();

        // Create a recording
        String createRecordingUrl = String.format("/api/v4/targets/%d/recordings", targetId);
        Response createResp =
                given().formParam("recordingName", RECORDING_NAME)
                        .formParam("duration", String.valueOf(RECORDING_DURATION_SECONDS))
                        .formParam("events", "template=ALL")
                        .when()
                        .post(createRecordingUrl)
                        .then()
                        .statusCode(201)
                        .extract()
                        .response();

        long recordingRemoteId = createResp.jsonPath().getLong("remoteId");

        // Wait for the recording to finish
        Thread.sleep(RECORDING_DURATION_SECONDS * 1000L);

        // Upload recording to datasource
        String uploadRecordingUrl =
                String.format(
                        "/api/v4/targets/%d/recordings/%d/upload", targetId, recordingRemoteId);
        Response uploadResp =
                given().when().post(uploadRecordingUrl).then().statusCode(200).extract().response();

        // The endpoint should send back a job ID, while it kicks off the upload.
        MatcherAssert.assertThat(uploadResp.body().asString(), Matchers.notNullValue());

        // Sleep for a bit to give the upload time to complete
        Thread.sleep(2000);

        // Get Grafana datasource URL
        Response datasourceUrlResp =
                given().when()
                        .get("/api/v4/grafana_datasource_url")
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();

        String datasourceUrl =
                new JsonObject(datasourceUrlResp.body().asString())
                        .getString("grafanaDatasourceUrl");

        // Confirm recording is loaded in Data Source
        Response listResp =
                given().when()
                        .get(datasourceUrl + "/list")
                        .then()
                        .statusCode(200)
                        .contentType(HttpMimeType.PLAINTEXT.mime())
                        .extract()
                        .response();

        MatcherAssert.assertThat(
                listResp.body().asString().trim(),
                Matchers.equalTo(String.format("**%s**", DATASOURCE_FILENAME)));

        // Query Data Source for recording metrics
        Instant toDate = Instant.now(); // Capture the current moment in UTC
        Instant fromDate = toDate.plusSeconds(-REQUEST_TIMEOUT_SECONDS);
        final String FROM = fromDate.toString();
        final String TO = toDate.toString();
        final String TARGET_METRIC = "jdk.CPULoad.machineTotal";

        final JsonObject query =
                new JsonObject(
                        Map.ofEntries(
                                Map.entry("app", "dashboard"),
                                Map.entry("dashboardId", 1), // Main Dashboard
                                Map.entry("panelId", 3), // Any ID
                                Map.entry("requestId", "Q237"), // Some request ID
                                Map.entry("timezone", "browser"),
                                Map.entry(
                                        "range",
                                        Map.of(
                                                "from",
                                                FROM,
                                                "to",
                                                TO,
                                                "raw",
                                                Map.of("from", FROM, "to", TO))),
                                Map.entry("interval", "1s"),
                                Map.entry("intervalMs", "1000"),
                                Map.entry(
                                        "targets",
                                        List.of(
                                                Map.of(
                                                        "target",
                                                        TARGET_METRIC,
                                                        "refId",
                                                        "A",
                                                        "type",
                                                        "timeserie"))),
                                Map.entry("maxDataPoints", 1000),
                                Map.entry("adhocFilters", List.of())));

        Response queryResp =
                given().contentType(ContentType.JSON)
                        .body(query.encode())
                        .when()
                        .post(datasourceUrl + "/query")
                        .then()
                        .statusCode(200)
                        .contentType(HttpMimeType.JSON.mime())
                        .extract()
                        .response();

        final JsonArray arrResponse = new JsonArray(queryResp.body().asString());
        MatcherAssert.assertThat(arrResponse, Matchers.notNullValue());
        MatcherAssert.assertThat(arrResponse.size(), Matchers.equalTo(1)); // Single target

        JsonObject targetResponse = arrResponse.getJsonObject(0);
        MatcherAssert.assertThat(
                targetResponse.getString("target"), Matchers.equalTo(TARGET_METRIC));

        JsonArray dataPoints = targetResponse.getJsonArray("datapoints");
        MatcherAssert.assertThat(dataPoints, Matchers.notNullValue());

        for (Object dataPoint : dataPoints) {
            JsonArray datapointPair = (JsonArray) dataPoint;
            MatcherAssert.assertThat(
                    datapointPair.size(), Matchers.equalTo(2)); // [value, timestamp]
            MatcherAssert.assertThat(
                    datapointPair.getDouble(0), Matchers.notNullValue()); // value must not be null
            MatcherAssert.assertThat(
                    datapointPair.getLong(1),
                    Matchers.allOf(
                            Matchers.notNullValue(),
                            Matchers.greaterThan(
                                    Long.valueOf(0)))); // timestamp must be non-null and positive
        }
    }
}
