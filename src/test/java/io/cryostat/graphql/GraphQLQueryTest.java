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

import java.util.*;
import java.util.stream.Collectors;

import io.cryostat.discovery.DiscoveryNode;
import io.cryostat.graphql.GraphQLTestModels.*;
import io.cryostat.resources.S3StorageResource;

import com.fasterxml.jackson.core.type.TypeReference;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.hamcrest.Matcher;
import org.hamcrest.collection.IsIterableContainingInOrder;
import org.hamcrest.comparator.ComparatorMatcherBuilder;
import org.junit.jupiter.api.Test;

/**
 * Tests for GraphQL read-only queries. These tests verify query syntax, filtering, response
 * structure, and data retrieval accuracy.
 */
@QuarkusTest
@QuarkusTestResource(value = S3StorageResource.class, restrictToAnnotatedClass = true)
class GraphQLQueryTest extends AbstractGraphQLTestBase {

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
    void testQueryForSpecificTargetsByNames() throws Exception {
        JsonObject query = new JsonObject();
        query.put(
                "query",
                "query { targetNodes(filter: { names:"
                        + " [\"service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi\","
                        + " \"service:jmx:rmi:///jndi/rmi://localhost:9091/jmxrmi\"] }) {"
                        + " name nodeType } }");

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

        List<TargetNode> targetNodes = actual.getData().getTargetNodes();

        int expectedSize = 1;
        assertThat(targetNodes.size(), is(expectedSize));

        TargetNode target1 = new TargetNode();
        target1.setName("service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi");
        target1.setNodeType("JVM");

        assertThat(targetNodes, hasItem(target1));
    }

    @Test
    void testQueryForFilteredActiveRecordingsByNames() throws Exception {
        String recordingName1 = "test";
        // Create Recording 1
        JsonObject notificationRecording = createRecording(recordingName1);
        assertThat(notificationRecording.getString("name"), equalTo(recordingName1));

        // Wait for first recording to fully complete before creating second
        Thread.sleep(DATA_COLLECTION_DELAY_MS);

        // Create Recording 2
        String recordingName2 = "test2";
        JsonObject notificationRecording2 = createRecording(recordingName2);
        assertThat(notificationRecording2.getString("name"), equalTo(recordingName2));

        // GraphQL Query to filter Active recordings by names
        JsonObject query2 = new JsonObject();
        query2.put(
                "query",
                String.format(
                        "query { targetNodes (filter: { annotations:"
                                + " [\"REALM = Custom Targets\"]}){ target { recordings"
                                + " { active (filter: { names: [\"%s\", \"%s\",\"Recording3\"]"
                                + " }) {data {name}}}}}}",
                        recordingName1, recordingName2));

        Response response2 =
                given().contentType(ContentType.JSON)
                        .body(query2.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        TargetNodesQueryResponse graphqlResp =
                mapper.readValue(response2.body().asString(), TargetNodesQueryResponse.class);

        List<String> filterNames = Arrays.asList(recordingName1, recordingName2);

        List<ActiveRecording> filteredRecordings =
                graphqlResp.getData().getTargetNodes().stream()
                        .flatMap(
                                targetNode ->
                                        targetNode
                                                .getTarget()
                                                .getRecordings()
                                                .getActive()
                                                .getData()
                                                .stream())
                        .filter(recording -> filterNames.contains(recording.name))
                        .collect(Collectors.toList());

        assertThat(filteredRecordings.size(), equalTo(2));
        ActiveRecording r1 = new ActiveRecording();
        r1.name = recordingName1;
        ActiveRecording r2 = new ActiveRecording();
        r2.name = recordingName2;

        assertThat(filteredRecordings, hasItem(r1));
        assertThat(filteredRecordings, hasItem(r2));

        // Delete the Recordings
        deleteRecording();
    }

    @Test
    void shouldReturnArchivedRecordingsFilteredByNames() throws Exception {
        // Create a new recording
        String recordingName = "test";
        JsonObject notificationRecording = createRecording(recordingName);
        assertThat(notificationRecording.getString("name"), equalTo(recordingName));

        // Archive the recording
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

        // Retrieve archived recording name via REST API
        Response archivedListResponse =
                given().when()
                        .get("/api/v4/recordings")
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();
        JsonArray retrievedArchivedRecordings =
                new JsonArray(archivedListResponse.body().asString());
        JsonObject retrievedArchivedRecording = retrievedArchivedRecordings.getJsonObject(0);
        String retrievedArchivedRecordingsName = retrievedArchivedRecording.getString("name");

        // GraphQL Query to filter Archived recordings by names
        JsonObject query = new JsonObject();
        query.put(
                "query",
                "query { targetNodes { name target {"
                        + "recordings {"
                        + "archived(filter: { names: [\""
                        + retrievedArchivedRecordingsName
                        + "\",\"someOtherName\"] }) {"
                        + "data {"
                        + "name"
                        + "}"
                        + "}"
                        + "}"
                        + "}"
                        + "}"
                        + "}");

        Response response =
                given().contentType(ContentType.JSON)
                        .body(query.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        TargetNodesQueryResponse graphqlResp =
                mapper.readValue(response.body().asString(), TargetNodesQueryResponse.class);

        List<ArchivedRecording> archivedRecordings2 =
                graphqlResp.getData().getTargetNodes().stream()
                        .flatMap(
                                targetNode ->
                                        targetNode
                                                .getTarget()
                                                .getRecordings()
                                                .getArchived()
                                                .getData()
                                                .stream())
                        .collect(Collectors.toList());

        int filteredRecordingsCount = archivedRecordings2.size();
        assertThat(
                "Number of filtered recordings should be 1", filteredRecordingsCount, equalTo(1));

        ArchivedRecording archivedRecording = archivedRecordings2.get(0);
        String filteredName = archivedRecording.name;
        assertThat(
                "Filtered name should match the archived recording name",
                filteredName,
                equalTo(retrievedArchivedRecordingsName));

        // Delete archived recording
        deleteRecording();
    }

    @Test
    void testQueryforFilteredEnvironmentNodesByNames() throws Exception {
        JsonObject query = new JsonObject();
        query.put(
                "query",
                "query { environmentNodes(filter: { names: [\"anotherName1\","
                        + " \"JDP\",\"anotherName2\"] }) { name nodeType } }");

        Response response =
                given().contentType(ContentType.JSON)
                        .body(query.encode())
                        .when()
                        .post("/api/v4/graphql")
                        .then()
                        .statusCode(allOf(greaterThanOrEqualTo(200), lessThan(300)))
                        .extract()
                        .response();

        EnvironmentNodesResponse actual =
                mapper.readValue(response.body().asString(), EnvironmentNodesResponse.class);
        List<DiscoveryNode> environmentNodes = actual.getData().getEnvironmentNodes();

        assertThat("The list filtered should be 1", environmentNodes.size(), equalTo(1));

        boolean nameExists = false;
        for (DiscoveryNode environmentNode : environmentNodes) {
            if (environmentNode.name.matches("JDP")) {
                nameExists = true;
                break;
            }
        }
        assertThat("Name not found", nameExists, is(true));
    }
}
