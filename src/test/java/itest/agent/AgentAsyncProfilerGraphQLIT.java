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
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import io.cryostat.resources.AgentApplicationResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonObject;
import itest.resources.S3StorageITResource;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusIntegrationTest
@QuarkusTestResource(value = AgentApplicationResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = S3StorageITResource.class, restrictToAnnotatedClass = true)
public class AgentAsyncProfilerGraphQLIT extends AgentTestBase {

    @AfterEach
    void cleanupAsyncProfiles() {
        if (target == null) {
            return;
        }

        List<Map<String, Object>> profiles =
                given().log()
                        .all()
                        .pathParams("targetId", target.id())
                        .when()
                        .get("/api/beta/targets/{targetId}/async-profiler")
                        .then()
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        for (Map<String, Object> profile : profiles) {
            String profileId = (String) profile.get("id");
            given().log()
                    .all()
                    .pathParams("targetId", target.id(), "profileId", profileId)
                    .when()
                    .delete("/api/beta/targets/{targetId}/async-profiler/{profileId}")
                    .then()
                    .log()
                    .all();
        }
    }

    @Test
    void testGraphQL() {
        long targetId = target.id();

        // Create a Profile
        Map<String, Object> requestBody = Map.of("events", List.of("alloc"), "duration", 5);

        given().log()
                .all()
                .pathParams("targetId", targetId)
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/api/beta/targets/{targetId}/async-profiler")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200)
                .contentType(ContentType.TEXT)
                .body(Matchers.notNullValue())
                .and()
                .extract()
                .body()
                .asString();
        JsonObject query = new JsonObject();
        query.put(
                "query",
                "query TargetAsyncProfiles {targetNodes(filter: { id: "
                        + targetId
                        + " }) {target {agent id connectUrl alias jvmId asyncProfiles { data {"
                        + " duration id startTime size } aggregate { count } } } } }");
        Response resp =
                given().basePath("/")
                        .contentType(ContentType.JSON)
                        .body(query.encode())
                        .log()
                        .all()
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .extract()
                        .response();

        JsonObject retrievedResponse = new JsonObject(resp.asString());
        assertThat(retrievedResponse, notNullValue());
        assertThat(retrievedResponse.getMap().size(), not(0));
    }

    @Test
    void testCreateMutation() throws InterruptedException, TimeoutException {
        long targetId = target.id();

        JsonObject query = new JsonObject();
        query.put(
                "query",
                "mutation {createAsyncProfile(nodes:{id:"
                        + targetId
                        + "},id: \"profile\",startTime: 1,events: [\"alloc\"],duration: 5)}");

        Response response =
                given().basePath("/")
                        .contentType(ContentType.JSON)
                        .body(query.encode())
                        .log()
                        .all()
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        webSocketClient.expectNotification("AsyncProfilerCreated", Duration.ofSeconds(10));

        assertThat(response.getStatusCode(), equalTo(200));
        assertThat(response.getBody(), notNullValue());
    }

    @Test
    void testDeleteMutation() throws Exception {
        long targetId = target.id();

        // Create a Profile
        Map<String, Object> requestBody = Map.of("events", List.of("alloc"), "duration", 5);

        String profileId =
                given().log()
                        .all()
                        .pathParams("targetId", targetId)
                        .contentType(ContentType.JSON)
                        .body(requestBody)
                        .when()
                        .post("/api/beta/targets/{targetId}/async-profiler")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .contentType(ContentType.TEXT)
                        .body(Matchers.notNullValue())
                        .and()
                        .extract()
                        .body()
                        .asString();

        webSocketClient.expectNotification("AsyncProfilerCreated", Duration.ofSeconds(10));
        // Wait for the profile to stop
        JsonObject stoppedNotification =
                webSocketClient.expectNotification(
                        "AsyncProfilerStopped",
                        Duration.ofMinutes(2),
                        o -> profileId.equals(o.getJsonObject("message").getString("id")));

        MatcherAssert.assertThat(
                stoppedNotification.getJsonObject("message").getString("id"),
                Matchers.equalTo(profileId));

        JsonObject deleteQuery = new JsonObject();
        deleteQuery.put(
                "query",
                String.format(
                        "mutation { deleteAsyncProfiles (nodes: { id:"
                                + targetId
                                + "}) { id size duration startTime }}",
                        profileId));

        Response deleteResponse =
                given().contentType(ContentType.JSON)
                        .body(deleteQuery.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .log()
                        .all()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        assertThat(deleteResponse.getStatusCode(), equalTo(200));

        // Verify the profile was deleted
        given().log()
                .all()
                .pathParams("targetId", targetId)
                .when()
                .get("/api/beta/targets/{targetId}/async-profiler")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .contentType(ContentType.JSON)
                .statusCode(200)
                .body("$.size()", Matchers.equalTo(0));
    }
}
