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
package itest;

import static io.restassured.RestAssured.given;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.cryostat.resources.ExternalApplicationResource;
import io.cryostat.resources.S3StorageResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import itest.bases.StandardSelfTest;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@QuarkusIntegrationTest
@QuarkusTestResource(S3StorageResource.class)
@QuarkusTestResource(value = ExternalApplicationResource.class, restrictToAnnotatedClass = true)
@Disabled
public class ExternalRecordingIT extends StandardSelfTest {

    @Test
    void test() throws Exception {
        // Agent will dual-register via JMX and HTTP
        waitForDiscovery(2);

        var discoveryBody =
                given().when()
                        .log()
                        .all()
                        .get("/api/v4/targets")
                        .then()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .body();
        var targets = new JsonArray(discoveryBody.asString());
        MatcherAssert.assertThat(targets.size(), Matchers.equalTo(3));
        JsonObject target = null;
        for (int i = 0; i < targets.size(); i++) {
            var obj = targets.getJsonObject(i);
            // we're just concerned with the external application JMX case
            if (String.format(
                            "service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi",
                            ExternalApplicationResource.ALIAS, ExternalApplicationResource.JMX_PORT)
                    .equals(obj.getString("connectUrl"))) {
                target = obj;
            }
        }
        MatcherAssert.assertThat(target, Matchers.notNullValue());
        var targetId = target.getLong("id");

        var recordings =
                new JsonArray(
                        given().when()
                                .log()
                                .all()
                                .get("/api/v4/targets/{targetId}/recordings", targetId)
                                .then()
                                .log()
                                .all()
                                .and()
                                .assertThat()
                                .statusCode(200)
                                .extract()
                                .body()
                                .asString());
        MatcherAssert.assertThat(recordings.size(), Matchers.equalTo(1));
        var recording = recordings.getJsonObject(0);
        MatcherAssert.assertThat(
                recording.getString("name"),
                Matchers.equalTo(ExternalApplicationResource.RECORDING_NAME));
        MatcherAssert.assertThat(recording.getBoolean("continuous"), Matchers.equalTo(false));
        MatcherAssert.assertThat(recording.getInteger("duration"), Matchers.equalTo(30_000));
        MatcherAssert.assertThat(
                recording.getJsonObject("metadata"),
                Matchers.equalTo(
                        new JsonObject(
                                Map.of(
                                        "labels",
                                        new JsonArray(
                                                List.of(
                                                        Map.of(
                                                                "key",
                                                                "autoanalyze",
                                                                "value",
                                                                "true")))))));

        expectWebSocketNotification("ActiveRecordingStopped", Duration.ofSeconds(60));
    }
}
