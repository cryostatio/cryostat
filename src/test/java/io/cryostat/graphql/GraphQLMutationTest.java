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

import io.cryostat.graphql.GraphQLTestModels.*;
import io.cryostat.resources.S3StorageResource;

import com.fasterxml.jackson.core.type.TypeReference;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
class GraphQLMutationTest extends AbstractGraphQLTestBase {

    @Test
    void testStartRecordingMutationOnSpecificTarget() throws Exception {
        String recordingName = "testStartRecordingMutationOnSpecificTarget";
        CountDownLatch latch = new CountDownLatch(2);

        JsonObject query = new JsonObject();
        query.put(
                "query",
                String.format(
                        "mutation { createRecording( nodes:{annotations: [\"REALM = Custom"
                            + " Targets\"]}, recording: { name: \"%s\", template: \"Profiling\","
                            + " templateType: \"TARGET\", duration: 30, continuous: false,"
                            + " archiveOnStop: true, toDisk: true }) { name state duration"
                            + " continuous metadata { labels { key value } } } }",
                        recordingName));

        Future<JsonObject> f =
                worker.submit(
                        () -> {
                            try {
                                return webSocketClient.expectNotification(
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
        assertThat(notificationRecording.getString("name"), equalTo(recordingName));
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
        recording.name = recordingName;
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

    @Test
    void testArchiveMutation() throws Exception {
        String recordingName = "testArchiveMutation";
        JsonObject notificationRecording = createRecording(recordingName);
        assertThat(notificationRecording.getString("name"), equalTo(recordingName));
        assertThat(notificationRecording.getString("state"), equalTo("RUNNING"));

        JsonObject query = new JsonObject();
        query.put(
                "query",
                String.format(
                        "mutation { archiveRecording (nodes: { annotations: [\"REALM = Custom"
                            + " Targets\"]}, recordings: { name: \"%s\"}) { name downloadUrl } }",
                        recordingName));

        Response response =
                given().contentType(ContentType.JSON)
                        .body(query.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        ArchiveMutationResponse archiveResponse =
                mapper.readValue(response.body().asString(), ArchiveMutationResponse.class);

        List<ArchivedRecording> archivedRecordings =
                archiveResponse.getData().getArchivedRecording();
        assertThat(archivedRecordings, not(empty()));
        assertThat(archivedRecordings, hasSize(1));

        String archivedRecordingName = archivedRecordings.get(0).name;
        assertThat(
                archivedRecordingName,
                matchesRegex(
                        String.format("^selftest_%s_[0-9]{8}T[0-9]{6}Z\\.jfr$", recordingName)));

        deleteRecording();
    }

    @Test
    void testActiveRecordingMetadataMutation() throws Exception {
        String recordingName = "testActiveRecordingMetadataMutation";
        JsonObject notificationRecording = createRecording(recordingName);
        assertThat(notificationRecording.getString("name"), equalTo(recordingName));

        JsonObject variables = new JsonObject();
        JsonArray labels = new JsonArray();
        labels.add(new JsonObject().put("key", "template.name").put("value", "Profiling"));
        labels.add(new JsonObject().put("key", "template.type").put("value", "TARGET"));
        labels.add(new JsonObject().put("key", "newLabel").put("value", "newValue"));
        labels.add(new JsonObject().put("key", "newKey").put("value", "anotherValue"));
        JsonObject metadataInput = new JsonObject().put("labels", labels);
        variables.put("metadataInput", metadataInput);

        JsonObject query = new JsonObject();
        query.put(
                "query",
                String.format(
                        "query ($metadataInput: MetadataLabelsInput!) { targetNodes(filter: {"
                            + " annotations: [\"REALM = Custom Targets\"] }) { name target {"
                            + " recordings{ active(filter: {name: \"%s\"}) { data { name id"
                            + " doPutMetadata(metadataInput: $metadataInput) { metadata { labels {"
                            + " key value } } } } } } } } }",
                        recordingName));
        query.put("variables", variables);

        Response response =
                given().contentType(ContentType.JSON)
                        .body(query.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        TypeReference<ActiveMutationResponse> typeRef =
                new TypeReference<ActiveMutationResponse>() {};
        ActiveMutationResponse actual = mapper.readValue(response.body().asString(), typeRef);

        List<TargetNode> targetNodes = actual.getData().getTargetNodes();
        assertThat(targetNodes, not(empty()));
        assertThat(targetNodes, hasSize(1));

        TargetNode targetNode = targetNodes.get(0);
        List<ActiveRecording> activeRecordings =
                targetNode.getTarget().getRecordings().getActive().getData();
        assertThat(activeRecordings, not(empty()));
        assertThat(activeRecordings, hasSize(1));

        ActiveRecording updatedRecording = activeRecordings.get(0);

        List<KeyValue> expectedLabels =
                List.of(
                        new KeyValue("template.name", "Profiling"),
                        new KeyValue("template.type", "TARGET"),
                        new KeyValue("newLabel", "newValue"),
                        new KeyValue("newKey", "anotherValue"));

        assertThat(
                updatedRecording.getDoPutMetadata().getMetadata().getLabels(),
                containsInAnyOrder(expectedLabels.toArray()));

        deleteRecording();
    }

    @Test
    void testArchivedRecordingMetadataMutation() throws Exception {
        String recordingName = "testArchivedRecordingMetadataMutation";
        // Create a Recording
        JsonObject notificationRecording = createRecording(recordingName);
        assertThat(notificationRecording.getString("name"), equalTo(recordingName));

        // Archive it
        JsonObject query1 = new JsonObject();
        query1.put(
                "query",
                String.format(
                        "mutation { archiveRecording (nodes: { annotations: [\"REALM = Custom"
                            + " Targets\"]}, recordings: { name: \"%s\"}) { name downloadUrl } }",
                        recordingName));

        Response response1 =
                given().contentType(ContentType.JSON)
                        .body(query1.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        ArchiveMutationResponse archiveResponse =
                mapper.readValue(response1.body().asString(), ArchiveMutationResponse.class);
        List<ArchivedRecording> archivedRecordings =
                archiveResponse.getData().getArchivedRecording();
        assertThat(archivedRecordings, not(empty()));
        assertThat(archivedRecordings, hasSize(1));

        String archivedRecordingName = archivedRecordings.get(0).getName();

        // Edit Labels
        JsonObject variables = new JsonObject();
        JsonArray labels = new JsonArray();
        labels.add(new JsonObject().put("key", "template.name").put("value", "Profiling"));
        labels.add(new JsonObject().put("key", "template.type").put("value", "TARGET"));
        labels.add(
                new JsonObject().put("key", "newArchivedLabel").put("value", "newArchivedValue"));
        JsonObject metadataInput = new JsonObject().put("labels", labels);
        variables.put("metadataInput", metadataInput);

        JsonObject query2 = new JsonObject();
        query2.put(
                "query",
                "query ($metadataInput: MetadataLabelsInput!) { targetNodes(filter: { annotations:"
                        + " [\"REALM = Custom Targets\"] }) { name target { recordings { archived"
                        + " (filter: {name: \""
                        + archivedRecordingName
                        + "\"}) { data { name doPutMetadata(metadataInput:"
                        + " $metadataInput) { metadata { labels { key value } } } } } } } } }");
        query2.put("variables", variables);

        Response response2 =
                given().contentType(ContentType.JSON)
                        .body(query2.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        MetadataUpdateResponse actual =
                mapper.readValue(response2.body().asString(), MetadataUpdateResponse.class);

        List<TargetNode> targetNodes = actual.getData().getTargetNodes();
        assertThat(targetNodes, not(empty()));
        assertThat(targetNodes, hasSize(1));

        List<ArchivedRecording> updatedArchivedRecordings =
                targetNodes.get(0).getTarget().getRecordings().getArchived().getData();
        assertThat(updatedArchivedRecordings, not(empty()));
        assertThat(updatedArchivedRecordings, hasSize(1));
        ArchivedRecording updatedArchivedRecording = updatedArchivedRecordings.get(0);
        assertThat(
                updatedArchivedRecording.getName(),
                matchesRegex(
                        String.format("^selftest_%s_[0-9]{8}T[0-9]{6}Z\\.jfr$", recordingName)));

        List<KeyValue> expectedLabels =
                List.of(
                        new KeyValue("template.name", "Profiling"),
                        new KeyValue("template.type", "TARGET"),
                        new KeyValue("newArchivedLabel", "newArchivedValue"));

        assertThat(
                updatedArchivedRecording.getDoPutMetadata().getMetadata().labels,
                containsInAnyOrder(expectedLabels.toArray()));

        deleteRecording();
    }

    @Test
    void testDeleteMutation() throws Exception {
        // this will delete all Active and Archived recordings that match the filter input.
        String recordingName = "testDeleteMutation";
        // Create a Recording
        JsonObject notificationRecording = createRecording(recordingName);
        assertThat(notificationRecording.getString("name"), equalTo(recordingName));

        // Archive it
        JsonObject query1 = new JsonObject();
        query1.put(
                "query",
                String.format(
                        "mutation { archiveRecording (nodes: { annotations: [\"REALM = Custom"
                            + " Targets\"]}, recordings: { name: \"%s\"}) { name downloadUrl } }",
                        recordingName));

        Response response1 =
                given().contentType(ContentType.JSON)
                        .body(query1.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        ArchiveMutationResponse archiveResponse =
                mapper.readValue(response1.body().asString(), ArchiveMutationResponse.class);
        List<ArchivedRecording> archivedRecordings =
                archiveResponse.getData().getArchivedRecording();
        assertThat(archivedRecordings, not(empty()));
        assertThat(archivedRecordings, hasSize(1));

        // Delete
        JsonObject query2 = new JsonObject();
        query2.put(
                "query",
                "query { targetNodes(filter: { annotations: [\"REALM = Custom Targets\"] }) { name"
                    + " target { recordings { active { data { name doDelete { name } } aggregate {"
                    + " count } } archived { data { name doDelete { name } } aggregate { count size"
                    + " } } } } } }");

        Response response2 =
                given().contentType(ContentType.JSON)
                        .body(query2.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        DeleteMutationResponse actual =
                mapper.readValue(response2.body().asString(), DeleteMutationResponse.class);
        assertThat(actual.getData().getTargetNodes(), hasSize(1));

        TargetNode node = actual.getData().getTargetNodes().get(0);
        assertThat(node.getTarget().getRecordings().getActive().getData(), hasSize(1));
        assertThat(node.getTarget().getRecordings().getArchived().getData(), hasSize(1));
        assertThat(node.getTarget().getRecordings().getArchived().aggregate.count, equalTo(1L));
        assertThat(node.getTarget().getRecordings().getArchived().aggregate.size, greaterThan(0L));

        ActiveRecording activeRecording =
                node.getTarget().getRecordings().getActive().getData().get(0);
        ArchivedRecording archivedRecording =
                node.getTarget().getRecordings().getArchived().getData().get(0);

        assertThat(activeRecording.name, equalTo(recordingName));
        assertThat(activeRecording.doDelete.name, equalTo(recordingName));
        assertThat(
                archivedRecording.name,
                matchesRegex(
                        String.format("^selftest_%s_[0-9]{8}T[0-9]{6}Z\\.jfr$", recordingName)));
    }
}
