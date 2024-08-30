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

import io.cryostat.AbstractTransactionalTestBase;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestHTTPEndpoint(ArchivedRecordings.class)
// TODO add tests storing new archived recordings, listing, downloading, and deleting them - already
// largely handled by itest.RecordingWorkflowTest
public class ArchivedRecordingsTest extends AbstractTransactionalTestBase {

    @Test
    void testListNone() {
        given().log()
                .all()
                .when()
                .get("/api/v4/recordings")
                .then()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200);
    }

    @Test
    void testListFsNone() {
        given().log()
                .all()
                .when()
                .get("/api/beta/fs/recordings")
                .then()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200);
    }

    @Test
    void testListFsInvalid() {
        given().log()
                .all()
                .when()
                .get("/api/beta/fs/recordings/abcd1234")
                .then()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(0));
    }

    @Test
    void testDeleteNone() {
        given().log()
                .all()
                .when()
                .delete("/api/v4/recordings/nothing")
                .then()
                .assertThat()
                .statusCode(404);
    }

    @Test
    void testDeleteFsInvalid() {
        given().log()
                .all()
                .when()
                .delete("/api/beta/fs/recordings/abcd1234/nothing")
                .then()
                .assertThat()
                .statusCode(404);
    }

    @Test
    void testUploadGrafanaInvalid() {
        given().log()
                .all()
                .when()
                .post("/api/v4/grafana/abcd1234")
                .then()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void testUploadGrafanaNotFound() {
        given().log()
                .all()
                .when()
                .post("/api/v4/grafana/Zm9vL2Jhcg==")
                .then()
                .assertThat()
                .statusCode(404);
    }

    @Test
    void testDownloadInvalid() {
        given().log()
                .all()
                .when()
                .get("/api/v4/download/abcd1234")
                .then()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void testDownloadNotFound() {
        given().log()
                .all()
                .when()
                .get("/api/v4/download/Zm9vL2Jhcg==")
                .then()
                .assertThat()
                .statusCode(404);
    }
}
