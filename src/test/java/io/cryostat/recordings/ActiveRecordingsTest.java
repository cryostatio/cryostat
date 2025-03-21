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

import java.util.Map;

import io.cryostat.AbstractTransactionalTestBase;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@QuarkusTest
@TestHTTPEndpoint(ActiveRecordings.class)
public class ActiveRecordingsTest extends AbstractTransactionalTestBase {

    @Test
    void testListNone() {
        int id = defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .pathParams(Map.of("targetId", id))
                .get()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(0));
    }

    @Test
    void testDeleteNone() {
        int id = defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .pathParams(Map.of("targetId", id))
                .delete("/1")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "test1 | template=N/A",
                "test1 | type=CUSTOM",
                "\t | template=Continuous",
                "test1 | \t",
            })
    void testCreateInvalid(String recordingName, String eventSpecifier) {
        int targetId = defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .pathParams(Map.of("targetId", targetId))
                .formParam("recordingName", recordingName)
                .formParam("events", eventSpecifier)
                .post()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void testCreateOnInvalidTargetId() {
        given().log()
                .all()
                .when()
                .pathParams(Map.of("targetId", Integer.MAX_VALUE))
                .formParam("recordingName", "irrelevant")
                .formParam("events", "template=ALL")
                .post()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @Test
    void testCreateWithUnknownEventTemplate() {
        int targetId = defineSelfCustomTarget();
        given().log()
                .all()
                .when()
                .pathParams(Map.of("targetId", targetId))
                .formParam("recordingName", "test")
                .formParam("events", "template=UNKNOWN")
                .post()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void testCreateListAndDelete() {
        int targetId = defineSelfCustomTarget();
        long startTime = System.currentTimeMillis();
        int recordingId =
                given().log()
                        .all()
                        .when()
                        .pathParams(Map.of("targetId", targetId))
                        .formParam("recordingName", "activeRecordingsTest")
                        .formParam("events", "template=Continuous")
                        .post()
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .body("id", Matchers.greaterThan(0))
                        .body("name", Matchers.equalTo("activeRecordingsTest"))
                        .body("remoteId", Matchers.greaterThan(0))
                        .body("state", Matchers.equalTo("RUNNING"))
                        .body("duration", Matchers.equalTo(0))
                        .body("startTime", Matchers.greaterThanOrEqualTo(startTime))
                        .body("continuous", Matchers.equalTo(true))
                        .body("toDisk", Matchers.equalTo(true))
                        .body("maxAge", Matchers.equalTo(0))
                        .body(
                                "downloadUrl",
                                Matchers.not(Matchers.blankOrNullString())) // TODO validate the URL
                        .body(
                                "reportUrl",
                                Matchers.not(Matchers.blankOrNullString())) // TODO validate the URL
                        .body("metadata.labels.size()", Matchers.equalTo(2))
                        // TODO label validation should not depend on the ordering
                        .body("metadata.labels[0].key", Matchers.equalTo("template.name"))
                        .body("metadata.labels[0].value", Matchers.equalTo("Continuous"))
                        .body("metadata.labels[1].key", Matchers.equalTo("template.type"))
                        .body("metadata.labels[1].value", Matchers.equalTo("TARGET"))
                        .and()
                        .extract()
                        .body()
                        .jsonPath()
                        .getInt("remoteId");

        given().log()
                .all()
                .when()
                .pathParams(Map.of("targetId", targetId))
                .get()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(1))
                .body("[0].id", Matchers.greaterThan(0))
                .body("[0].name", Matchers.equalTo("activeRecordingsTest"))
                .body("[0].remoteId", Matchers.greaterThan(0))
                .body("[0].state", Matchers.equalTo("RUNNING"))
                .body("[0].duration", Matchers.equalTo(0))
                .body("[0].startTime", Matchers.greaterThanOrEqualTo(startTime))
                .body("[0].continuous", Matchers.equalTo(true))
                .body("[0].toDisk", Matchers.equalTo(true))
                .body("[0].maxAge", Matchers.equalTo(0))
                .body(
                        "[0].downloadUrl",
                        Matchers.not(Matchers.blankOrNullString())) // TODO validate the URL
                .body(
                        "[0].reportUrl",
                        Matchers.not(Matchers.blankOrNullString())) // TODO validate the URL
                .body("[0].metadata.labels.size()", Matchers.equalTo(2))
                // TODO label validation should not depend on the ordering
                .body("[0].metadata.labels[0].key", Matchers.equalTo("template.name"))
                .body("[0].metadata.labels[0].value", Matchers.equalTo("Continuous"))
                .body("[0].metadata.labels[1].key", Matchers.equalTo("template.type"))
                .body("[0].metadata.labels[1].value", Matchers.equalTo("TARGET"));

        given().log()
                .all()
                .when()
                .pathParams(Map.of("targetId", targetId))
                .delete(Integer.toString(recordingId))
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);

        given().log()
                .all()
                .when()
                .pathParams(Map.of("targetId", targetId))
                .get()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(0));
    }

    @Test
    void testCreateDownloadAndDelete() {
        int targetId = defineSelfCustomTarget();
        int recordingId =
                given().log()
                        .all()
                        .when()
                        .pathParams(Map.of("targetId", targetId))
                        .formParam("recordingName", "activeRecordingsTest")
                        .formParam("events", "template=Continuous")
                        .post()
                        .then()
                        .log()
                        .all()
                        .and()
                        .extract()
                        .body()
                        .jsonPath()
                        .getInt("remoteId");

        given().log()
                .all()
                .when()
                .pathParams(Map.of("targetId", targetId))
                .get(Integer.toString(recordingId))
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200)
                .contentType(ContentType.BINARY);

        given().log()
                .all()
                .when()
                .pathParams(Map.of("targetId", targetId))
                .delete(Integer.toString(recordingId))
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);
    }

    @Test
    void testCreateStopAndDelete() {
        int targetId = defineSelfCustomTarget();
        long startTime = System.currentTimeMillis();
        int recordingId =
                given().log()
                        .all()
                        .when()
                        .pathParams(Map.of("targetId", targetId))
                        .formParam("recordingName", "activeRecordingsTest")
                        .formParam("events", "template=Continuous")
                        .post()
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .and()
                        .extract()
                        .body()
                        .jsonPath()
                        .getInt("remoteId");

        given().log()
                .all()
                .when()
                .pathParams(Map.of("targetId", targetId))
                .get()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(1))
                .body("[0].id", Matchers.greaterThan(0))
                .body("[0].name", Matchers.equalTo("activeRecordingsTest"))
                .body("[0].remoteId", Matchers.greaterThan(0))
                .body("[0].state", Matchers.equalTo("RUNNING"))
                .body("[0].duration", Matchers.equalTo(0))
                .body("[0].startTime", Matchers.greaterThanOrEqualTo(startTime))
                .body("[0].continuous", Matchers.equalTo(true))
                .body("[0].toDisk", Matchers.equalTo(true))
                .body("[0].maxAge", Matchers.equalTo(0))
                .body(
                        "[0].downloadUrl",
                        Matchers.not(Matchers.blankOrNullString())) // TODO validate the URL
                .body(
                        "[0].reportUrl",
                        Matchers.not(Matchers.blankOrNullString())) // TODO validate the URL
                .body("[0].metadata.labels.size()", Matchers.equalTo(2))
                // TODO label validation should not depend on the ordering
                .body("[0].metadata.labels[0].key", Matchers.equalTo("template.name"))
                .body("[0].metadata.labels[0].value", Matchers.equalTo("Continuous"))
                .body("[0].metadata.labels[1].key", Matchers.equalTo("template.type"))
                .body("[0].metadata.labels[1].value", Matchers.equalTo("TARGET"));

        given().log()
                .all()
                .when()
                .pathParams(Map.of("targetId", targetId))
                .body("stop")
                .patch(Integer.toString(recordingId))
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);

        given().log()
                .all()
                .when()
                .pathParams(Map.of("targetId", targetId))
                .get()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(1))
                .body("[0].id", Matchers.greaterThan(0))
                .body("[0].name", Matchers.equalTo("activeRecordingsTest"))
                .body("[0].remoteId", Matchers.greaterThan(0))
                .body("[0].state", Matchers.equalTo("STOPPED"))
                .body("[0].duration", Matchers.equalTo(0))
                .body("[0].startTime", Matchers.greaterThanOrEqualTo(startTime))
                .body("[0].continuous", Matchers.equalTo(true))
                .body("[0].toDisk", Matchers.equalTo(true))
                .body("[0].maxAge", Matchers.equalTo(0))
                .body(
                        "[0].downloadUrl",
                        Matchers.not(Matchers.blankOrNullString())) // TODO validate the URL
                .body(
                        "[0].reportUrl",
                        Matchers.not(Matchers.blankOrNullString())) // TODO validate the URL
                .body("[0].metadata.labels.size()", Matchers.equalTo(2))
                // TODO label validation should not depend on the ordering
                .body("[0].metadata.labels[0].key", Matchers.equalTo("template.name"))
                .body("[0].metadata.labels[0].value", Matchers.equalTo("Continuous"))
                .body("[0].metadata.labels[1].key", Matchers.equalTo("template.type"))
                .body("[0].metadata.labels[1].value", Matchers.equalTo("TARGET"));

        given().log()
                .all()
                .when()
                .pathParams(Map.of("targetId", targetId))
                .delete(Integer.toString(recordingId))
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);
    }
}
