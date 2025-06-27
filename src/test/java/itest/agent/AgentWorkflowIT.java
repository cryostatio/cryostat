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
package itest.agent;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@QuarkusIntegrationTest
@TestMethodOrder(OrderAnnotation.class)
public class AgentWorkflowIT extends AgentTestBase {

    static final String RECORDING_NAME = AgentWorkflowIT.class.getSimpleName();
    static final String CONTINUOUS_TEMPLATE = "template=Continuous,type=TARGET";

    @Order(0)
    @Test
    void testListNoRecordings() {
        given().log()
                .all()
                .pathParams("targetId", target.id())
                .when()
                .get("/api/v4/targets/{targetId}/recordings")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200)
                .and()
                .body("$.size()", Matchers.equalTo(0));
    }

    @Order(1)
    @Test
    void testStartListAndDeleteRecording() {
        var recordingId =
                given().log()
                        .all()
                        .pathParams("targetId", target.id())
                        .when()
                        .formParam("recordingName", RECORDING_NAME)
                        .formParam("events", CONTINUOUS_TEMPLATE)
                        .formParam("duration", 10)
                        .post("/api/v4/targets/{targetId}/recordings")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(201)
                        .and()
                        .body("name", Matchers.equalTo(RECORDING_NAME))
                        .body("state", Matchers.equalTo("RUNNING"))
                        .body("duration", Matchers.equalTo(10_000))
                        .and()
                        .extract()
                        .jsonPath()
                        .getLong("remoteId");

        given().log()
                .all()
                .pathParams("targetId", target.id())
                .when()
                .get("/api/v4/targets/{targetId}/recordings")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200)
                .and()
                .body("$.size()", Matchers.equalTo(1))
                .and()
                .body("[0].name", Matchers.equalTo(RECORDING_NAME))
                .body("[0].state", Matchers.equalTo("RUNNING"))
                .body("[0].duration", Matchers.equalTo(10_000));

        given().log()
                .all()
                .pathParams("targetId", target.id(), "recordingId", recordingId)
                .when()
                .delete("/api/v4/targets/{targetId}/recordings/{recordingId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);

        testListNoRecordings();
    }
}
