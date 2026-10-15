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
import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Confirms that {@code asprofEvents} (the {@code jfrsync} opt-in) is rejected for a plain-JMX
 * target.
 */
@QuarkusTest
@TestHTTPEndpoint(ActiveRecordings.class)
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class JfrSyncJmxTargetRejectionTest extends AbstractTransactionalTestBase {

    @Test
    void testAsprofEventsRejectedForJmxTarget() {
        int targetId = defineSelfCustomTarget();

        given().log()
                .all()
                .when()
                .pathParams(Map.of("targetId", targetId))
                .formParam("recordingName", "jfrSyncJmxRejectionTest")
                .formParam("events", "template=Continuous")
                .formParam("asprofEvents", "cpu")
                .post()
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void testOrdinaryRecordingStillSucceedsWithoutAsprofEvents() {
        int targetId = defineSelfCustomTarget();

        int recordingId =
                given().log()
                        .all()
                        .when()
                        .pathParams(Map.of("targetId", targetId))
                        .formParam("recordingName", "jfrSyncJmxRejectionSanityTest")
                        .formParam("events", "template=Continuous")
                        .post()
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .contentType(ContentType.JSON)
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
