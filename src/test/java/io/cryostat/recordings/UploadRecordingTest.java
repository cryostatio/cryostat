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
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.resources.GrafanaResource;
import io.cryostat.resources.JFRDatasourceResource;
import io.cryostat.resources.S3StorageResource;
import io.cryostat.util.HttpMimeType;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(GrafanaResource.class)
@QuarkusTestResource(JFRDatasourceResource.class)
public class UploadRecordingTest extends AbstractTransactionalTestBase {

    // TODO this should be a constant somewhere in the server sources
    public static final String DATASOURCE_FILENAME = "cryostat-analysis.jfr";
    static final String RECORDING_NAME = "upload_recording_it_rec";
    static final int RECORDING_DURATION_SECONDS = 10;

    long recordingRemoteId;

    @BeforeEach
    void setupUploadRecordingTest() throws Exception {
        if (selfId < 1) {
            defineSelfCustomTarget();
        }
        cleanupSelfActiveAndArchivedRecordings();
    }

    @AfterEach
    void cleanupUploadRecordingTest() {
        cleanupSelfActiveAndArchivedRecordings();
    }

    @Test
    public void shouldLoadRecordingToDatasource() throws Exception {
        // Create a recording for the test
        var jp =
                startSelfRecording(
                        RECORDING_NAME,
                        Map.of(
                                "duration",
                                String.valueOf(RECORDING_DURATION_SECONDS),
                                "events",
                                "template=ALL"));

        recordingRemoteId = jp.getLong("remoteId");

        // Wait for the recording to finish
        Thread.sleep(RECORDING_DURATION_SECONDS * 1000L);

        // Upload the recording to the datasource
        Response uploadResponse =
                given().log()
                        .all()
                        .when()
                        .basePath("")
                        .pathParams("targetId", selfId, "remoteId", recordingRemoteId)
                        .post("/api/v4/targets/{targetId}/recordings/{remoteId}/upload")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .response();

        // The endpoint should send back a job ID
        assertThat(uploadResponse.body().asString(), notNullValue());

        // Sleep for a bit to give the upload time to complete
        Thread.sleep(2000);

        // Get the datasource URL
        Response datasourceUrlResponse =
                given().log()
                        .all()
                        .when()
                        .basePath("")
                        .get("/api/v4/grafana_datasource_url")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .response();

        JsonObject datasourceUrlJson = new JsonObject(datasourceUrlResponse.body().asString());
        String datasourceUrl = datasourceUrlJson.getString("grafanaDatasourceUrl");

        // Confirm recording is loaded in Data Source
        Response listResponse =
                given().log()
                        .all()
                        .when()
                        .get(datasourceUrl + "/list")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(HttpMimeType.PLAINTEXT.mime())
                        .extract()
                        .response();

        assertThat(
                listResponse.body().asString().trim(),
                equalTo(String.format("**%s**", DATASOURCE_FILENAME)));

        // Query Data Source for recording metrics
        Instant toDate = Instant.now();
        Instant fromDate = toDate.minusSeconds(30);
        final String FROM = fromDate.toString();
        final String TO = toDate.toString();
        final String TARGET_METRIC = "jdk.CPULoad.machineTotal";

        final JsonObject query =
                new JsonObject(
                        Map.ofEntries(
                                Map.entry("app", "dashboard"),
                                Map.entry("dashboardId", 1),
                                Map.entry("panelId", 3),
                                Map.entry("requestId", "Q237"),
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

        Response queryResponse =
                given().log()
                        .all()
                        .when()
                        .contentType(ContentType.JSON)
                        .body(query.encode())
                        .post(datasourceUrl + "/query")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(HttpMimeType.JSON.mime())
                        .extract()
                        .response();

        JsonArray arrResponse = new JsonArray(queryResponse.body().asString());
        assertThat(arrResponse, notNullValue());
        assertThat(arrResponse.size(), equalTo(1)); // Single target

        JsonObject targetResponse = arrResponse.getJsonObject(0);
        assertThat(targetResponse.getString("target"), equalTo(TARGET_METRIC));

        JsonArray dataPoints = targetResponse.getJsonArray("datapoints");
        assertThat(dataPoints, notNullValue());

        for (Object dataPoint : dataPoints) {
            JsonArray datapointPair = (JsonArray) dataPoint;
            assertThat(datapointPair.size(), equalTo(2)); // [value, timestamp]
            assertThat(datapointPair.getDouble(0), notNullValue()); // value must not be null
            assertThat(
                    datapointPair.getLong(1),
                    Matchers.allOf(
                            notNullValue(),
                            Matchers.greaterThan(0L))); // timestamp must be non-null and positive
        }
    }
}
