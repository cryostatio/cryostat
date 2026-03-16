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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import io.cryostat.resources.AgentApplicationResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;
import itest.resources.S3StorageITResource;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusIntegrationTest
@QuarkusTestResource(value = AgentApplicationResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = S3StorageITResource.class, restrictToAnnotatedClass = true)
public class AgentAsyncProfilerIT extends AgentTestBase {

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
    void testListNoProfiles() {
        MatcherAssert.assertThat(
                (List<?>)
                        given().log()
                                .all()
                                .pathParams("targetId", target.id())
                                .when()
                                .get("/api/beta/targets/{targetId}/async-profiler")
                                .then()
                                .log()
                                .all()
                                .and()
                                .assertThat()
                                .contentType(ContentType.JSON)
                                .statusCode(200)
                                .extract()
                                .body()
                                .as(List.class),
                Matchers.equalTo(List.of()));
    }

    @SuppressWarnings("unchecked")
    @Test
    void testGetInitialStatus() {
        Map<String, Object> status =
                given().log()
                        .all()
                        .pathParams("targetId", target.id())
                        .when()
                        .get("/api/beta/targets/{targetId}/async-profiler/status")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .contentType(ContentType.JSON)
                        .statusCode(200)
                        .extract()
                        .body()
                        .jsonPath()
                        .getMap("$");

        MatcherAssert.assertThat(status.get("status"), Matchers.equalTo("STOPPED"));
        MatcherAssert.assertThat(status.get("currentProfile"), Matchers.nullValue());

        Map<String, Object> availableEvents = (Map<String, Object>) status.get("availableEvents");
        MatcherAssert.assertThat(availableEvents, Matchers.notNullValue());

        List<String> basicEvents = (List<String>) availableEvents.get("basic_events");
        MatcherAssert.assertThat(
                basicEvents,
                Matchers.containsInAnyOrder(
                        "cpu",
                        "alloc",
                        "nativemem",
                        "lock",
                        "nativelock",
                        "wall",
                        "itimer",
                        "ctimer"));

        List<String> javaMethodCalls = (List<String>) availableEvents.get("java_method_calls");
        MatcherAssert.assertThat(javaMethodCalls, Matchers.notNullValue());
        MatcherAssert.assertThat(javaMethodCalls, Matchers.hasSize(1));
        MatcherAssert.assertThat(javaMethodCalls.get(0), Matchers.equalTo("ClassName.methodName"));
    }

    @Test
    void testCreateProfileWithCpu()
            throws InterruptedException, ExecutionException, TimeoutException {
        long targetId = target.id();

        Map<String, Object> requestBody = Map.of("events", List.of("cpu"), "duration", 5);

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

        webSocketClient.expectNotification(
                "AsyncProfilerCreated",
                Duration.ofSeconds(10),
                o -> profileId.equals(o.getJsonObject("message").getString("id")));

        webSocketClient.expectNotification(
                "AsyncProfilerStopped",
                Duration.ofMinutes(2),
                o -> profileId.equals(o.getJsonObject("message").getString("id")));

        List<Map<String, Object>> profiles =
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
                        .body("$.size()", Matchers.equalTo(1))
                        .and()
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        String listedProfileId = (String) profiles.get(0).get("id");
        MatcherAssert.assertThat(listedProfileId, Matchers.equalTo(profileId));
        MatcherAssert.assertThat(
                ((Number) profiles.get(0).get("size")).longValue(), Matchers.greaterThan(0L));
    }

    @Test
    void testCreateProfileWithCpuAlloc()
            throws InterruptedException, ExecutionException, TimeoutException {
        long targetId = target.id();

        Map<String, Object> requestBody = Map.of("events", List.of("cpu", "alloc"), "duration", 5);

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

        webSocketClient.expectNotification(
                "AsyncProfilerCreated",
                Duration.ofSeconds(10),
                o -> profileId.equals(o.getJsonObject("message").getString("id")));

        webSocketClient.expectNotification(
                "AsyncProfilerStopped",
                Duration.ofMinutes(2),
                o -> profileId.equals(o.getJsonObject("message").getString("id")));

        List<Map<String, Object>> profiles =
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
                        .body("$.size()", Matchers.equalTo(1))
                        .and()
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        MatcherAssert.assertThat(
                ((Number) profiles.get(0).get("size")).longValue(), Matchers.greaterThan(0L));
    }

    @Test
    void testCreateProfileWithCpuNativemem()
            throws InterruptedException, ExecutionException, TimeoutException {
        long targetId = target.id();

        Map<String, Object> requestBody =
                Map.of("events", List.of("cpu", "nativemem"), "duration", 5);

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

        webSocketClient.expectNotification(
                "AsyncProfilerCreated",
                Duration.ofSeconds(10),
                o -> profileId.equals(o.getJsonObject("message").getString("id")));

        webSocketClient.expectNotification(
                "AsyncProfilerStopped",
                Duration.ofMinutes(2),
                o -> profileId.equals(o.getJsonObject("message").getString("id")));

        List<Map<String, Object>> profiles =
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
                        .body("$.size()", Matchers.equalTo(1))
                        .and()
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        MatcherAssert.assertThat(
                ((Number) profiles.get(0).get("size")).longValue(), Matchers.greaterThan(0L));
    }

    @Test
    void testCreateListDownloadAndDeleteProfile()
            throws InterruptedException, ExecutionException, TimeoutException {
        long targetId = target.id();

        Map<String, Object> requestBody = Map.of("events", List.of("cpu"), "duration", 5);

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

        JsonObject createdNotification =
                webSocketClient.expectNotification(
                        "AsyncProfilerCreated",
                        Duration.ofSeconds(10),
                        o -> profileId.equals(o.getJsonObject("message").getString("id")));

        MatcherAssert.assertThat(
                createdNotification.getJsonObject("message").getString("id"),
                Matchers.equalTo(profileId));

        JsonObject stoppedNotification =
                webSocketClient.expectNotification(
                        "AsyncProfilerStopped",
                        Duration.ofMinutes(2),
                        o -> profileId.equals(o.getJsonObject("message").getString("id")));

        MatcherAssert.assertThat(
                stoppedNotification.getJsonObject("message").getString("id"),
                Matchers.equalTo(profileId));

        List<Map<String, Object>> profiles =
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
                        .body("$.size()", Matchers.equalTo(1))
                        .and()
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        String listedProfileId = (String) profiles.get(0).get("id");
        MatcherAssert.assertThat(listedProfileId, Matchers.equalTo(profileId));

        given().log()
                .all()
                .pathParams("targetId", targetId, "profileId", profileId)
                .when()
                .get("/api/beta/targets/{targetId}/async-profiler/{profileId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200)
                .contentType("application/octet-stream")
                .header(
                        "Content-Disposition",
                        Matchers.containsString(
                                String.format("%s_%s.asprof.jfr", target.alias(), profileId)));

        given().log()
                .all()
                .pathParams("targetId", targetId, "profileId", profileId)
                .when()
                .delete("/api/beta/targets/{targetId}/async-profiler/{profileId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(204);

        webSocketClient.expectNotification(
                "AsyncProfilerDeleted",
                Duration.ofSeconds(10),
                o -> profileId.equals(o.getJsonObject("message").getString("id")));

        testListNoProfiles();
    }

    @Test
    void testDeleteNonExistentProfile() {
        given().log()
                .all()
                .pathParams("targetId", target.id(), "profileId", "nonexistent")
                .when()
                .delete("/api/beta/targets/{targetId}/async-profiler/{profileId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void testDownloadNonExistentProfile() {
        given().log()
                .all()
                .pathParams("targetId", target.id(), "profileId", "nonexistent")
                .when()
                .get("/api/beta/targets/{targetId}/async-profiler/{profileId}")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(200);
    }

    @Test
    void testListProfilesForNonExistentTarget() {
        given().log()
                .all()
                .pathParams("targetId", Integer.MAX_VALUE)
                .when()
                .get("/api/beta/targets/{targetId}/async-profiler")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @Test
    void testGetStatusForNonExistentTarget() {
        given().log()
                .all()
                .pathParams("targetId", Integer.MAX_VALUE)
                .when()
                .get("/api/beta/targets/{targetId}/async-profiler/status")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @Test
    void testCreateProfileForNonExistentTarget() {
        Map<String, Object> requestBody = Map.of("events", List.of("cpu"), "duration", 5);

        given().log()
                .all()
                .pathParams("targetId", Integer.MAX_VALUE)
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/api/beta/targets/{targetId}/async-profiler")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(404);
    }

    @Test
    void testCreateProfileWithEmptyEvents() {
        Map<String, Object> requestBody = Map.of("events", List.of(), "duration", 5);

        given().log()
                .all()
                .pathParams("targetId", target.id())
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/api/beta/targets/{targetId}/async-profiler")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void testCreateProfileWithZeroDuration() {
        Map<String, Object> requestBody = Map.of("events", List.of("cpu"), "duration", 0);

        given().log()
                .all()
                .pathParams("targetId", target.id())
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/api/beta/targets/{targetId}/async-profiler")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void testCreateProfileWithNegativeDuration() {
        Map<String, Object> requestBody = Map.of("events", List.of("cpu"), "duration", -1);

        given().log()
                .all()
                .pathParams("targetId", target.id())
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/api/beta/targets/{targetId}/async-profiler")
                .then()
                .log()
                .all()
                .and()
                .assertThat()
                .statusCode(400);
    }

    @Test
    void testCreateProfileWithMultipleEvents() {
        long targetId = target.id();

        // cpu+itimer+ctimer is an invalid combination - the API returns 400
        Map<String, Object> requestBody =
                Map.of("events", List.of("cpu", "itimer", "ctimer"), "duration", 5);

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
                .statusCode(400);
    }

    @Test
    void testCreateProfileWithAllocOnly()
            throws InterruptedException, ExecutionException, TimeoutException {
        long targetId = target.id();

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

        webSocketClient.expectNotification(
                "AsyncProfilerCreated",
                Duration.ofSeconds(10),
                o -> profileId.equals(o.getJsonObject("message").getString("id")));

        webSocketClient.expectNotification(
                "AsyncProfilerStopped",
                Duration.ofMinutes(2),
                o -> profileId.equals(o.getJsonObject("message").getString("id")));

        List<Map<String, Object>> profiles =
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
                        .body("$.size()", Matchers.equalTo(1))
                        .and()
                        .extract()
                        .body()
                        .jsonPath()
                        .getList("$");

        MatcherAssert.assertThat(
                ((Number) profiles.get(0).get("size")).longValue(), Matchers.greaterThan(0L));
    }

    @Test
    void testCreateMultipleProfilesSequentially()
            throws InterruptedException, ExecutionException, TimeoutException {
        long targetId = target.id();

        Map<String, Object> requestBody1 = Map.of("events", List.of("cpu"), "duration", 3);

        String profileId1 =
                given().log()
                        .all()
                        .pathParams("targetId", targetId)
                        .contentType(ContentType.JSON)
                        .body(requestBody1)
                        .when()
                        .post("/api/beta/targets/{targetId}/async-profiler")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .and()
                        .extract()
                        .body()
                        .asString();

        webSocketClient.expectNotification(
                "AsyncProfilerCreated",
                Duration.ofSeconds(10),
                o -> profileId1.equals(o.getJsonObject("message").getString("id")));

        webSocketClient.expectNotification(
                "AsyncProfilerStopped",
                Duration.ofMinutes(2),
                o -> profileId1.equals(o.getJsonObject("message").getString("id")));

        Map<String, Object> requestBody2 = Map.of("events", List.of("alloc"), "duration", 3);

        String profileId2 =
                given().log()
                        .all()
                        .pathParams("targetId", targetId)
                        .contentType(ContentType.JSON)
                        .body(requestBody2)
                        .when()
                        .post("/api/beta/targets/{targetId}/async-profiler")
                        .then()
                        .log()
                        .all()
                        .and()
                        .assertThat()
                        .statusCode(200)
                        .and()
                        .extract()
                        .body()
                        .asString();

        webSocketClient.expectNotification(
                "AsyncProfilerCreated",
                Duration.ofSeconds(10),
                o -> profileId2.equals(o.getJsonObject("message").getString("id")));

        webSocketClient.expectNotification(
                "AsyncProfilerStopped",
                Duration.ofMinutes(2),
                o -> profileId2.equals(o.getJsonObject("message").getString("id")));

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
                .body("$.size()", Matchers.equalTo(2));
    }
}
