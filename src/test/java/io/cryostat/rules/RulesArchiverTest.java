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
package io.cryostat.rules;

import static io.restassured.RestAssured.given;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.websocket.DeploymentException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
public class RulesArchiverTest extends AbstractTransactionalTestBase {

    static String RULE_NAME = "periodic_archiver";

    static final String rule =
            String.format(
                    """
                    {
                      "name": "%s",
                      "description": "",
                      "matchExpression": "true",
                      "eventSpecifier": "template=Continuous,type=TARGET",
                      "archivalPeriodSeconds": 10,
                      "initialDelaySeconds": 0,
                      "preservedArchives": 3,
                      "maxAgeSeconds": 0,
                      "maxSizeBytes": 10485760,
                      "enabled": true
                    }
                    """,
                    RULE_NAME);

    private static final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void cleanupRulesArchiverTest() {
        cleanupSelfActiveAndArchivedRecordings();
    }

    @Test
    public void test()
            throws TimeoutException, InterruptedException, IOException, DeploymentException {
        int id = defineSelfCustomTarget();

        given().log()
                .all()
                .when()
                .pathParams(Map.of("targetId", id))
                .get("/api/v4/targets/{targetId}/recordings")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(0));

        given().log()
                .all()
                .when()
                .pathParams(Map.of("jvmId", this.selfJvmId))
                .get("/api/beta/recordings/{jvmId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(0));

        given().body(rule)
                .contentType(ContentType.JSON)
                .post("/api/v4/rules")
                .then()
                .statusCode(201);

        // Wait for archives to be created. With preservedArchives=3 and archivalPeriodSeconds=10:
        // Note: initialDelaySeconds=0 is converted to archivalPeriodSeconds (10s) in RuleExecutor
        // - 1st archive at ~10s (initial delay)
        // - 2nd archive at ~20s
        // - 3rd archive at ~30s
        // - 4th archive at ~40s (triggers deletion of 1st, then creates 4th)

        // Wait for first 3 archives to be created (allow time for rule activation + recording
        // start)
        webSocketClient.expectNotification("ArchivedRecordingCreated", Duration.ofSeconds(20));
        webSocketClient.expectNotification("ArchivedRecordingCreated", Duration.ofSeconds(15));
        webSocketClient.expectNotification("ArchivedRecordingCreated", Duration.ofSeconds(15));

        // Wait for deletion that occurs with 4th archive job
        webSocketClient.expectNotification("ArchivedRecordingDeleted", Duration.ofSeconds(15));

        // Wait for the 4th archive to be created (happens after deletion in same job)
        webSocketClient.expectNotification("ArchivedRecordingCreated", Duration.ofSeconds(5));

        // Stop further background jobs before checking results
        given().log()
                .all()
                // do not clean, or else Cryostat will archive the recording on stop and
                // create an additional copy
                .queryParam("clean", false)
                .pathParam("ruleName", RULE_NAME)
                .delete("/api/v4/rules/{ruleName}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204)
                .body(Matchers.emptyOrNullString());

        webSocketClient.expectNotification("RuleDeleted", Duration.ofSeconds(5));

        given().log()
                .all()
                .when()
                .pathParams(Map.of("jvmId", this.selfJvmId))
                .get("/api/beta/recordings/{jvmId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("size()", Matchers.equalTo(3));
    }
}
