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
package io.cryostat.graphql;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.discovery.DiscoveryNode;
import io.cryostat.graphql.GraphQLTestModels.*;
import io.cryostat.resources.S3StorageResource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.hamcrest.Matcher;
import org.hamcrest.collection.IsIterableContainingInOrder;
import org.hamcrest.comparator.ComparatorMatcherBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
class GraphQLTest extends AbstractTransactionalTestBase {

    @Inject ObjectMapper mapper;

    private final ExecutorService worker = ForkJoinPool.commonPool();

    static final long DATA_COLLECTION_DELAY_MS = 5_000L;
    static final long REQUEST_TIMEOUT_SECONDS = 30L;

    @BeforeEach
    public void setupGraphQLTest() throws Exception {
        if (selfId < 1) {
            defineSelfCustomTarget();
        }
        cleanupSelfActiveAndArchivedRecordings();
    }

    @AfterEach
    public void cleanupGraphQLTest() throws Exception {
        cleanupSelfActiveAndArchivedRecordings();
    }

    @Test
    void testEnvironmentNodeListing() throws Exception {
        JsonObject query = new JsonObject();
        query.put(
                "query",
                "query { environmentNodes(filter: { name: \"Custom Targets\" }) { name nodeType"
                        + " children { name nodeType } } }");

        Response response =
                given().contentType(ContentType.JSON)
                        .body(query.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        TypeReference<EnvironmentNodesResponse> typeRef =
                new TypeReference<EnvironmentNodesResponse>() {};
        EnvironmentNodesResponse actual = mapper.readValue(response.body().asString(), typeRef);

        List<DiscoveryNode> expectedChildren = new ArrayList<>();
        DiscoveryNode expectedChild = new DiscoveryNode();
        expectedChild.name = "service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi";
        expectedChild.nodeType = "JVM";
        expectedChildren.add(expectedChild);

        DiscoveryNode expectedNode = new DiscoveryNode();
        expectedNode.name = "Custom Targets";
        expectedNode.nodeType = "Realm";
        expectedNode.children = expectedChildren;

        assertThat(actual.getData().getEnvironmentNodes().size(), is(1));

        DiscoveryNode actualNode = actual.getData().getEnvironmentNodes().get(0);
        assertThat(actualNode.name, is(expectedNode.name));
        assertThat(actualNode.nodeType, is(expectedNode.nodeType));
        assertThat(actualNode.children.size(), is(expectedNode.children.size()));

        Comparator<DiscoveryNode> byNameAndType =
                Comparator.comparing((DiscoveryNode node) -> node.name)
                        .thenComparing(node -> node.nodeType);
        Matcher<Iterable<? extends DiscoveryNode>> listMatcher =
                IsIterableContainingInOrder.contains(
                        expectedChildren.stream()
                                .map(
                                        child ->
                                                ComparatorMatcherBuilder.comparedBy(byNameAndType)
                                                        .comparesEqualTo(child))
                                .collect(Collectors.toList()));
        assertThat(
                "Children nodes do not match expected configuration",
                actualNode.children,
                listMatcher);
    }

    @Test
    void testOtherContainersFound() throws Exception {
        JsonObject query = new JsonObject();
        query.put(
                "query",
                "query { targetNodes { name nodeType labels { key value } target { alias connectUrl"
                        + " annotations { cryostat { key value } platform { key value } } } } }");

        Response response =
                given().contentType(ContentType.JSON)
                        .body(query.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        TargetNodesQueryResponse actual =
                mapper.readValue(response.body().asString(), TargetNodesQueryResponse.class);

        assertThat(actual.data.targetNodes, hasSize(1));

        TargetNode cryostat = new TargetNode();
        Target cryostatTarget = new Target();
        cryostatTarget.setAlias("selftest");
        cryostatTarget.setConnectUrl("service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi");
        cryostat.setName(cryostatTarget.getConnectUrl());
        cryostat.setTarget(cryostatTarget);
        cryostat.setNodeType("JVM");
        Annotations cryostatAnnotations = new Annotations();
        cryostatAnnotations.setCryostat(Arrays.asList(new KeyValue("REALM", "Custom Targets")));
        cryostatAnnotations.setPlatform(new ArrayList<>());
        cryostatTarget.setAnnotations(cryostatAnnotations);
        cryostat.setLabels(new ArrayList<>());
        assertThat(actual.data.targetNodes, hasItem(cryostat));
        assertThat(actual.data.targetNodes.get(0).getName(), is(cryostat.getName()));
        assertThat(actual.data.targetNodes.get(0).getNodeType(), is(cryostat.getNodeType()));
    }

    @Test
    void testQueryForSpecificTargetWithSpecificFields() throws Exception {
        JsonObject query = new JsonObject();
        query.put(
                "query",
                "query { targetNodes(filter: { annotations: [\"REALM = Custom Targets\"] })"
                        + " { name nodeType target { connectUrl annotations { cryostat(key:"
                        + " [\"REALM\"]) { key value } } } } }");

        Response response =
                given().contentType(ContentType.JSON)
                        .body(query.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        TargetNodesQueryResponse actual =
                mapper.readValue(response.body().asString(), TargetNodesQueryResponse.class);
        assertThat(actual.data.targetNodes, hasSize(1));
        TargetNode ext = new TargetNode();
        Target extTarget = new Target();
        extTarget.setConnectUrl("service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi");
        ext.setName(extTarget.getConnectUrl());
        ext.setTarget(extTarget);
        ext.setNodeType("JVM");
        Annotations extAnnotations = new Annotations();
        extAnnotations.setCryostat(Arrays.asList(new KeyValue("REALM", "Custom Targets")));
        extTarget.setAnnotations(extAnnotations);
        assertThat(actual.data.targetNodes, hasItem(ext));
    }

    @Test
    void testStartRecordingMutationOnSpecificTarget() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);

        JsonObject query = new JsonObject();
        query.put(
                "query",
                "mutation { createRecording( nodes:{annotations: ["
                        + "\"REALM = Custom Targets\""
                        + "]}, recording: { name: \"test\", template:"
                        + " \"Profiling\", templateType: \"TARGET\", duration: 30, continuous:"
                        + " false, archiveOnStop: true, toDisk: true }) { name state duration"
                        + " continuous metadata { labels { key value } } } }");

        Future<JsonObject> f =
                worker.submit(
                        () -> {
                            try {
                                return expectWebSocketNotification(
                                        "ActiveRecordingCreated", Duration.ofSeconds(15));
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                latch.countDown();
                            }
                        });

        Thread.sleep(DATA_COLLECTION_DELAY_MS);

        Response response =
                given().contentType(ContentType.JSON)
                        .body(query.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        CreateRecordingMutationResponse actual =
                mapper.readValue(response.body().asString(), CreateRecordingMutationResponse.class);
        latch.await(30, TimeUnit.SECONDS);

        // Ensure ActiveRecordingCreated notification emitted matches expected values
        JsonObject notification = f.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        JsonObject notificationRecording =
                notification.getJsonObject("message").getJsonObject("recording");
        assertThat(notificationRecording.getString("name"), equalTo("test"));
        assertThat(
                notification.getJsonObject("message").getString("target"),
                equalTo(
                        String.format(
                                "service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi", "localhost", 0)));
        JsonArray notificationLabels =
                notificationRecording.getJsonObject("metadata").getJsonArray("labels");
        Map<String, String> expectedLabels =
                Map.of("template.name", "Profiling", "template.type", "TARGET");
        assertThat(
                notificationLabels,
                containsInAnyOrder(
                        expectedLabels.entrySet().stream()
                                .map(
                                        e ->
                                                new JsonObject(
                                                        Map.of(
                                                                "key",
                                                                e.getKey(),
                                                                "value",
                                                                e.getValue())))
                                .toArray()));

        ActiveRecording recording = new ActiveRecording();
        recording.id = 0;
        recording.remoteId = 0;
        recording.name = "test";
        recording.reportUrl = null;
        recording.downloadUrl = null;
        recording.metadata = RecordingMetadata.of(expectedLabels);
        recording.state = "RUNNING";
        recording.startTime = 0;
        recording.duration = 30_000L;
        recording.continuous = false;
        recording.toDisk = false;
        recording.maxSize = 0;
        recording.maxAge = 0;
        recording.labels = null;

        assertThat(actual.data.recordings, equalTo(List.of(recording)));

        // delete recording
        deleteRecording();
    }

    // Helper methods
    private JsonObject createRecording(String name) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        JsonObject query = new JsonObject();
        query.put(
                "query",
                String.format(
                        "mutation { createRecording( nodes:{annotations: [\"REALM = Custom"
                            + " Targets\"]}, recording: { name: \"%s\", template: \"Profiling\","
                            + " templateType: \"TARGET\", duration: 30, continuous: false,"
                            + " archiveOnStop: true, toDisk: true }) { name state duration"
                            + " continuous metadata { labels { key value } } } }",
                        name));
        Future<JsonObject> f =
                worker.submit(
                        () -> {
                            try {
                                return expectWebSocketNotification(
                                        "ActiveRecordingCreated", Duration.ofSeconds(15));
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                latch.countDown();
                            }
                        });

        Response response =
                given().contentType(ContentType.JSON)
                        .body(query.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        latch.await(30, TimeUnit.SECONDS);
        JsonObject notification = f.get(30, TimeUnit.SECONDS);
        return notification.getJsonObject("message").getJsonObject("recording");
    }

    private JsonObject stopRecording() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        JsonObject query = new JsonObject();
        query.put(
                "query",
                "query { targetNodes(filter: { annotations: [ \"REALM = Custom Targets\"] })  {"
                    + " name target { recordings { active { data { doStop { name state } } } } } }"
                    + " }");

        Future<JsonObject> f2 =
                worker.submit(
                        () -> {
                            try {
                                return expectWebSocketNotification(
                                        "ActiveRecordingStopped", Duration.ofSeconds(15));
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                latch.countDown();
                            }
                        });

        Response response =
                given().contentType(ContentType.JSON)
                        .body(query.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        latch.await(30, TimeUnit.SECONDS);
        JsonObject notification = f2.get(30, TimeUnit.SECONDS);
        return notification.getJsonObject("message").getJsonObject("recording");
    }

    private void deleteRecording() throws Exception {
        JsonObject query = new JsonObject();
        query.put(
                "query",
                "query { targetNodes(filter: { annotations: [\"REALM = Custom Targets\"] }) { name"
                    + " target { recordings { active { data { name doDelete { name } } aggregate {"
                    + " count } } archived { data { name doDelete { name } } aggregate { count size"
                    + " } } } } } }");

        given().contentType(ContentType.JSON)
                .body(query.encode())
                .when()
                .post("/api/v4/graphql")
                .then()
                .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)));
    }

    private JsonObject restartRecording(String name, String replace) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        JsonObject query = new JsonObject();
        query.put(
                "query",
                String.format(
                        "query { targetNodes(filter: { annotations: [\"REALM = Custom Targets\"] })"
                            + " { name target { doStartRecording ( recording: { name: \"%s\","
                            + " template: \"Profiling\", templateType: \"TARGET\", replace: \"%s\""
                            + " }) { name state } } } }",
                        name, replace));
        Future<JsonObject> f =
                worker.submit(
                        () -> {
                            try {
                                return expectWebSocketNotification(
                                        "ActiveRecordingCreated", Duration.ofSeconds(15));
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                latch.countDown();
                            }
                        });

        Response response =
                given().contentType(ContentType.JSON)
                        .body(query.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        latch.await(30, TimeUnit.SECONDS);
        JsonObject notification = f.get(30, TimeUnit.SECONDS);
        return notification.getJsonObject("message").getJsonObject("recording");
    }

    private JsonObject restartRecordingWithError(String name, String replace) throws Exception {
        JsonObject query = new JsonObject();

        query.put(
                "query",
                String.format(
                        "query { targetNodes(filter: { annotations: [\"REALM = Custom Targets\"] })"
                            + " { name target { doStartRecording ( recording: { name: \"%s\","
                            + " template: \"Profiling\", templateType: \"TARGET\", replace: \"%s\""
                            + " }) { name state } } } }",
                        name, replace));

        Response response =
                given().contentType(ContentType.JSON)
                        .body(query.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        JsonObject responseObj = new JsonObject(response.body().asString());
        io.vertx.core.json.JsonArray errors = responseObj.getJsonArray("errors");
        return errors.getJsonObject(0);
    }
}
