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

import io.restassured.http.ContentType;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public abstract class AbstractJfrViewTest {

    private static final String RECORDING_FILENAME = "analytics-sample.jfr";
    private static final String VIEW_PATH = "/api/beta/targets/{jvmId}/recordings/{filename}/view";
    private static final String VIEWS_PATH =
            "/api/beta/targets/{jvmId}/recordings/{filename}/views";

    @BeforeEach
    void setupRecording() throws Exception {
        File recordingFile =
                new File(
                        getClass()
                                .getClassLoader()
                                .getResource(
                                        "io/cryostat/recordings/analysis/" + RECORDING_FILENAME)
                                .toURI());

        given().contentType(ContentType.MULTIPART)
                .multiPart("recording", recordingFile, "application/octet-stream")
                .post("/api/v4/recordings");
    }

    @AfterEach
    void cleanupRecording() {
        given().pathParam("filename", RECORDING_FILENAME).delete("/api/v4/recordings/{filename}");
    }

    @Test
    void testDefaultViewRendersRecordingInformation() {
        String body =
                given().log()
                        .all()
                        .when()
                        .pathParams("jvmId", "uploads", "filename", RECORDING_FILENAME)
                        .get(VIEW_PATH)
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.TEXT)
                        .and()
                        .extract()
                        .body()
                        .asString();

        MatcherAssert.assertThat(body, Matchers.containsString("Recording Information"));
        MatcherAssert.assertThat(body, Matchers.containsString("Event Count"));
    }

    @Test
    void testNamedViewRenders() {
        String body =
                given().log()
                        .all()
                        .when()
                        .pathParams("jvmId", "uploads", "filename", RECORDING_FILENAME)
                        .queryParam("view", "hot-methods")
                        .get(VIEW_PATH)
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.TEXT)
                        .and()
                        .extract()
                        .body()
                        .asString();

        MatcherAssert.assertThat(
                body, Matchers.containsString("Java Methods that Execute the Most"));
    }

    @Test
    void testViewHonorsWidth() {
        String body =
                given().log()
                        .all()
                        .when()
                        .pathParams("jvmId", "uploads", "filename", RECORDING_FILENAME)
                        .queryParam("view", "hot-methods")
                        .queryParam("width", 60)
                        .get(VIEW_PATH)
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.TEXT)
                        .and()
                        .extract()
                        .body()
                        .asString();

        // Every rendered line must fit within the requested width.
        for (String line : body.split("\n")) {
            MatcherAssert.assertThat(line.length(), Matchers.lessThanOrEqualTo(60));
        }
    }

    @Test
    void testUnknownViewReturnsBadRequest() {
        given().log()
                .all()
                .when()
                .pathParams("jvmId", "uploads", "filename", RECORDING_FILENAME)
                .queryParam("view", "no-such-view")
                .get(VIEW_PATH)
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void testInvalidWidthReturnsBadRequest() {
        given().log()
                .all()
                .when()
                .pathParams("jvmId", "uploads", "filename", RECORDING_FILENAME)
                .queryParam("width", 0)
                .get(VIEW_PATH)
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void testViewMissingRecordingReturnsNotFound() {
        given().log()
                .all()
                .when()
                .pathParams("jvmId", "uploads", "filename", "nonexistent.jfr")
                .get(VIEW_PATH)
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @Test
    void testViewsListsCategorizedViews() {
        var response =
                given().log()
                        .all()
                        .when()
                        .pathParams("jvmId", "uploads", "filename", RECORDING_FILENAME)
                        .get(VIEWS_PATH)
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
                        .jsonPath();

        List<String> vm = response.getList("vm");
        List<String> env = response.getList("env");
        List<String> app = response.getList("app");

        MatcherAssert.assertThat(vm, Matchers.notNullValue());
        MatcherAssert.assertThat(env, Matchers.notNullValue());
        MatcherAssert.assertThat(app, Matchers.notNullValue());

        // Spot-check a representative view from each category (from `jfr help view`).
        MatcherAssert.assertThat(vm, Matchers.hasItem("blocked-by-system-gc"));
        MatcherAssert.assertThat(vm, Matchers.hasItem("gc"));
        MatcherAssert.assertThat(env, Matchers.hasItem("active-recordings"));
        MatcherAssert.assertThat(env, Matchers.hasItem("recording"));
        MatcherAssert.assertThat(app, Matchers.hasItem("allocation-by-class"));
        MatcherAssert.assertThat(app, Matchers.hasItem("hot-methods"));

        // Categories must be disjoint and non-trivial.
        MatcherAssert.assertThat(vm.size(), Matchers.greaterThan(0));
        MatcherAssert.assertThat(env.size(), Matchers.greaterThan(0));
        MatcherAssert.assertThat(app.size(), Matchers.greaterThan(0));
        MatcherAssert.assertThat(vm, Matchers.not(Matchers.hasItem("allocation-by-class")));
        MatcherAssert.assertThat(app, Matchers.not(Matchers.hasItem("blocked-by-system-gc")));
    }

    @Test
    void testViewsMissingRecordingReturnsNotFound() {
        given().log()
                .all()
                .when()
                .pathParams("jvmId", "uploads", "filename", "nonexistent.jfr")
                .get(VIEWS_PATH)
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }
}
