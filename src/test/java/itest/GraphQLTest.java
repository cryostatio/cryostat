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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import io.cryostat.discovery.DiscoveryNode;
import io.cryostat.graphql.ActiveRecordings;
import io.cryostat.graphql.ArchivedRecordings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import itest.GraphQLTest.ActiveMutationResponse;
import itest.GraphQLTest.ActiveRecording.DoPutMetadata;
import itest.GraphQLTest.DeleteMutationResponse;
import itest.GraphQLTest.TargetNode;
import itest.bases.StandardSelfTest;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@QuarkusTest
@TestMethodOrder(OrderAnnotation.class)
class GraphQLTest extends StandardSelfTest {

    private final ExecutorService worker = ForkJoinPool.commonPool();

    static final String TEST_RECORDING_NAME = "archivedRecording";

    @Test
    @Order(0)
    void testEnvironmentNodeListing() throws Exception {
        JsonObject query = new JsonObject();
        query.put(
                "query",
                "query { environmentNodes(filter: { name: \"Custom Targets\" }) { name nodeType"
                        + " children { name nodeType } } }");

        HttpResponse<Buffer> resp =
                webClient
                        .extensions()
                        .post("/api/v2.2/graphql", query.toBuffer(), REQUEST_TIMEOUT_SECONDS);
        MatcherAssert.assertThat(
                resp.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));

        TypeReference<EnvironmentNodesResponse> typeRef =
                new TypeReference<EnvironmentNodesResponse>() {};
        EnvironmentNodesResponse actual = mapper.readValue(resp.bodyAsString(), typeRef);

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

        for (int i = 0; i < actualNode.children.size(); i++) {
            DiscoveryNode actualChild = actualNode.children.get(i);
            DiscoveryNode expectedChildNode = expectedNode.children.get(i);
            assertThat(actualChild.name, is(expectedChildNode.name));
            assertThat(actualChild.nodeType, is(expectedChildNode.nodeType));
        }
    }

    @Test
    @Order(1)
    void testOtherContainersFound() throws Exception {
        JsonObject query = new JsonObject();
        query.put(
                "query",
                "query { targetNodes { name nodeType labels { key value } target { alias connectUrl"
                        + " annotations { cryostat { key value } platform { key value } } } } }");
        HttpResponse<Buffer> resp =
                webClient
                        .extensions()
                        .post("/api/v3/graphql", query.toBuffer(), REQUEST_TIMEOUT_SECONDS);
        MatcherAssert.assertThat(
                resp.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));

        TargetNodesQueryResponse actual =
                mapper.readValue(resp.bodyAsString(), TargetNodesQueryResponse.class);

        MatcherAssert.assertThat(actual.data.targetNodes, Matchers.hasSize(1));

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
        MatcherAssert.assertThat(actual.data.targetNodes, Matchers.hasItem(cryostat));
        assertThat(actual.data.targetNodes.get(0).name, is(cryostat.name));
        assertThat(actual.data.targetNodes.get(0).nodeType, is(cryostat.nodeType));
    }

    @Test
    @Order(2)
    void testQueryForSpecificTargetWithSpecificFields() throws Exception {
        JsonObject query = new JsonObject();
        query.put(
                "query",
                "query { targetNodes(filter: { annotations: [\"REALM = Custom Targets\"] })"
                        + " { name nodeType target { connectUrl annotations { cryostat(key:"
                        + " [\"REALM\"]) { key value } } } } }");
        HttpResponse<Buffer> resp =
                webClient
                        .extensions()
                        .post("/api/v3/graphql", query.toBuffer(), REQUEST_TIMEOUT_SECONDS);
        MatcherAssert.assertThat(
                resp.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));

        TargetNodesQueryResponse actual =
                mapper.readValue(resp.bodyAsString(), TargetNodesQueryResponse.class);
        MatcherAssert.assertThat(actual.data.targetNodes, Matchers.hasSize(1));
        TargetNode ext = new TargetNode();
        Target extTarget = new Target();
        extTarget.setConnectUrl("service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi");
        ext.setName(extTarget.getConnectUrl());
        ext.setTarget(extTarget);
        ext.setNodeType("JVM");
        Annotations extAnnotations = new Annotations();
        extAnnotations.setCryostat(Arrays.asList(new KeyValue("REALM", "Custom Targets")));
        extTarget.setAnnotations(extAnnotations);
        MatcherAssert.assertThat(actual.data.targetNodes, Matchers.hasItem(ext));
    }

    @Test
    @Order(3)
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
                                return expectNotification(
                                                "ActiveRecordingCreated", 15, TimeUnit.SECONDS)
                                        .get();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                latch.countDown();
                            }
                        });

        Thread.sleep(5000); // Sleep to setup notification listening before query resolves

        HttpResponse<Buffer> resp =
                webClient
                        .extensions()
                        .post("/api/v3/graphql", query.toBuffer(), REQUEST_TIMEOUT_SECONDS);
        System.out.println("+++StartRecording: " + resp.bodyAsString());
        MatcherAssert.assertThat(
                resp.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));
        CreateRecordingMutationResponse actual =
                mapper.readValue(resp.bodyAsString(), CreateRecordingMutationResponse.class);
        System.out.println("+++StartRecording: " + actual);
        latch.await(30, TimeUnit.SECONDS);

        // Ensure ActiveRecordingCreated notification emitted matches expected values
        JsonObject notification = f.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        JsonObject notificationRecording =
                notification.getJsonObject("message").getJsonObject("recording");
        MatcherAssert.assertThat(notificationRecording.getString("name"), Matchers.equalTo("test"));
        MatcherAssert.assertThat(
                notification.getJsonObject("message").getString("target"),
                Matchers.equalTo(
                        String.format(
                                "service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi", "localhost", 0)));
        JsonArray notificationLabels =
                notificationRecording.getJsonObject("metadata").getJsonArray("labels");
        Map<String, String> expectedLabels =
                Map.of("template.name", "Profiling", "template.type", "TARGET");
        MatcherAssert.assertThat(
                notificationLabels,
                Matchers.containsInAnyOrder(
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
        recording.name = "test";
        recording.duration = 30_000L;
        recording.state = "RUNNING";
        recording.metadata = RecordingMetadata.of(expectedLabels);

        MatcherAssert.assertThat(actual.data.recordings, Matchers.equalTo(List.of(recording)));

        // delete recording
        deleteRecording();
    }

    @Test
    @Order(4)
    void testArchiveMutation() throws Exception {
        Thread.sleep(5000);
        JsonObject notificationRecording = createRecording();
        Assertions.assertEquals("test", notificationRecording.getString("name"));
        Assertions.assertEquals("RUNNING", notificationRecording.getString("state"));

        JsonObject query = new JsonObject();
        query.put(
                "query",
                "mutation { archiveRecording (nodes: { annotations: [\"REALM = Custom Targets\"]},"
                        + " recordings: { name: \"test\"}) { name downloadUrl } }");
        HttpResponse<Buffer> resp =
                webClient
                        .extensions()
                        .post("/api/v3/graphql", query.toBuffer(), REQUEST_TIMEOUT_SECONDS);
        MatcherAssert.assertThat(
                resp.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));

        String jsonResponse = resp.bodyAsString();
        System.out.println("+++Archive Resp: " + jsonResponse);

        ArchiveMutationResponse archiveResponse =
                mapper.readValue(jsonResponse, ArchiveMutationResponse.class);
        System.out.println("+++Actual Object: " + archiveResponse.toString());

        List<ArchivedRecording> archivedRecordings =
                archiveResponse.getData().getArchivedRecording();
        MatcherAssert.assertThat(archivedRecordings, Matchers.not(Matchers.empty()));
        MatcherAssert.assertThat(archivedRecordings, Matchers.hasSize(1));

        String archivedRecordingName = archivedRecordings.get(0).getName();
        MatcherAssert.assertThat(
                archivedRecordingName,
                Matchers.matchesRegex("^selftest_test_[0-9]{8}T[0-9]{6}Z\\.jfr$"));

        deleteRecording();
    }

    @Test
    @Order(5)
    void testActiveRecordingMetadataMutation() throws Exception {
        Thread.sleep(5000);
        JsonObject notificationRecording = createRecording();
        Assertions.assertEquals("test", notificationRecording.getString("name"));

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
                "query ($metadataInput: MetadataLabelsInput!) { targetNodes(filter: { annotations:"
                    + " [\"REALM = Custom Targets\"] }) { name target { recordings{ active(filter:"
                    + " {name: \"test\"}) { data { name id doPutMetadata(metadataInput:"
                    + " $metadataInput) { metadata { labels { key value } } } } } } } } }");
        query.put("variables", variables);

        HttpResponse<Buffer> resp =
                webClient
                        .extensions()
                        .post("/api/v3/graphql", query.toBuffer(), REQUEST_TIMEOUT_SECONDS);
        MatcherAssert.assertThat(
                resp.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));
        String jsonResponse = resp.bodyAsString();
        System.out.println("+++ActiveMutation Resp here: " + jsonResponse);

        TypeReference<ActiveMutationResponse> typeRef =
                new TypeReference<ActiveMutationResponse>() {};
        ActiveMutationResponse actual = mapper.readValue(jsonResponse, typeRef);
        System.out.println("+++ActualMutation Object here: " + actual.toString());

        List<TargetNode> targetNodes = actual.getData().getTargetNodes();
        System.out.println("+++targetNodes: " + targetNodes);
        MatcherAssert.assertThat(targetNodes, Matchers.not(Matchers.empty()));
        MatcherAssert.assertThat(targetNodes, Matchers.hasSize(1));

        TargetNode targetNode = targetNodes.get(0);
        List<ActiveRecording> activeRecordings =
                targetNode.getTarget().getRecordings().getActive().getData();
        System.out.println("+++activeRecordings: " + activeRecordings);
        MatcherAssert.assertThat(activeRecordings, Matchers.not(Matchers.empty()));
        MatcherAssert.assertThat(activeRecordings, Matchers.hasSize(1));

        ActiveRecording updatedRecording = activeRecordings.get(0);

        List<KeyValue> expectedLabels =
                List.of(
                        new KeyValue("template.name", "Profiling"),
                        new KeyValue("template.type", "TARGET"),
                        new KeyValue("newLabel", "newValue"),
                        new KeyValue("newKey", "anotherValue"));

        MatcherAssert.assertThat(
                updatedRecording.getDoPutMetadata().getMetadata().labels,
                Matchers.containsInAnyOrder(expectedLabels.toArray()));

        deleteRecording();
    }

    @Test
    @Order(6)
    void testArchivedRecordingMetadataMutation() throws Exception {
        Thread.sleep(5000);
        // Create a Recording
        JsonObject notificationRecording = createRecording();
        Assertions.assertEquals("test", notificationRecording.getString("name"));
        // Archive it
        JsonObject query1 = new JsonObject();
        query1.put(
                "query",
                "mutation { archiveRecording (nodes: { annotations: [\"REALM = Custom Targets\"]},"
                        + " recordings: { name: \"test\"}) { name downloadUrl } }");
        HttpResponse<Buffer> resp1 =
                webClient
                        .extensions()
                        .post("/api/v3/graphql", query1.toBuffer(), REQUEST_TIMEOUT_SECONDS);
        MatcherAssert.assertThat(
                resp1.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));

        String jsonResponse1 = resp1.bodyAsString();
        System.out.println("+++ArchiveMetadata Resp: " + jsonResponse1);

        // Deserialize the response to get the archived recording name
        ArchiveMutationResponse archiveResponse =
                mapper.readValue(jsonResponse1, ArchiveMutationResponse.class);
        List<ArchivedRecording> archivedRecordings =
                archiveResponse.getData().getArchivedRecording();
        MatcherAssert.assertThat(archivedRecordings, Matchers.not(Matchers.empty()));
        MatcherAssert.assertThat(archivedRecordings, Matchers.hasSize(1));

        String archivedRecordingName = archivedRecordings.get(0).getName();
        System.out.println("+++ArchivedRecordingName: " + archivedRecordingName);

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
                        + " [\"REALM = Custom Targets\"] }) { name target { recordings{ archived"
                        + " (filter: {name: \""
                        + archivedRecordingName
                        + "\"}) { data { name doPutMetadata(metadataInput:"
                        + " $metadataInput) { metadata { labels { key value } } } } } } } } }");
        query2.put("variables", variables);
        HttpResponse<Buffer> resp2 =
                webClient
                        .extensions()
                        .post("/api/v3/graphql", query2.toBuffer(), REQUEST_TIMEOUT_SECONDS);
        MatcherAssert.assertThat(
                resp2.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));

        String jsonResponse2 = resp2.bodyAsString();
        System.out.println("+++ArchiveMutation Resp here: " + jsonResponse2);

        MetadataUpdateResponse actual =
                mapper.readValue(jsonResponse2, MetadataUpdateResponse.class);
        System.out.println("+++ArchiveMutation(Actual) Resp here: " + actual.toString());

        List<TargetNode> targetNodes = actual.getData().getTargetNodes();
        MatcherAssert.assertThat(targetNodes, Matchers.not(Matchers.empty()));
        MatcherAssert.assertThat(targetNodes, Matchers.hasSize(1));

        List<ArchivedRecording> updatedArchivedRecordings =
                targetNodes.get(0).getTarget().getRecordings().getArchived().getData();
        MatcherAssert.assertThat(updatedArchivedRecordings, Matchers.not(Matchers.empty()));
        MatcherAssert.assertThat(updatedArchivedRecordings, Matchers.hasSize(1));
        ArchivedRecording updatedArchivedRecording = updatedArchivedRecordings.get(0);
        MatcherAssert.assertThat(
                updatedArchivedRecording.getName(),
                Matchers.matchesRegex("^selftest_test_[0-9]{8}T[0-9]{6}Z\\.jfr$"));

        List<KeyValue> expectedLabels =
                List.of(
                        new KeyValue("template.name", "Profiling"),
                        new KeyValue("template.type", "TARGET"),
                        new KeyValue("newArchivedLabel", "newArchivedValue"));

        MatcherAssert.assertThat(
                updatedArchivedRecording.getDoPutMetadata().getMetadata().labels,
                Matchers.containsInAnyOrder(expectedLabels.toArray()));

        deleteRecording();
    }

    @Test
    @Order(7)
    void testDeleteMutation() throws Exception {
        // this will delete all Active and Archived recordings that match the filter input.
        Thread.sleep(5000);
        // Create a Recording
        JsonObject notificationRecording = createRecording();
        Assertions.assertEquals("test", notificationRecording.getString("name"));
        // Archive it
        JsonObject query1 = new JsonObject();
        query1.put(
                "query",
                "mutation { archiveRecording (nodes: { annotations: [\"REALM = Custom Targets\"]},"
                        + " recordings: { name: \"test\"}) { name downloadUrl } }");
        HttpResponse<Buffer> resp1 =
                webClient
                        .extensions()
                        .post("/api/v3/graphql", query1.toBuffer(), REQUEST_TIMEOUT_SECONDS);
        MatcherAssert.assertThat(
                resp1.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));

        String jsonResponse1 = resp1.bodyAsString();
        System.out.println("+++ArchiveMetadata Resp: " + jsonResponse1);

        // Deserialize the response to get the archived recording name
        ArchiveMutationResponse archiveResponse =
                mapper.readValue(jsonResponse1, ArchiveMutationResponse.class);
        List<ArchivedRecording> archivedRecordings =
                archiveResponse.getData().getArchivedRecording();
        MatcherAssert.assertThat(archivedRecordings, Matchers.not(Matchers.empty()));
        MatcherAssert.assertThat(archivedRecordings, Matchers.hasSize(1));

        String archivedRecordingName = archivedRecordings.get(0).getName();
        System.out.println("+++ArchivedRecordingName: " + archivedRecordingName);

        // Delete
        JsonObject query2 = new JsonObject();
        query2.put(
                "query",
                "query { targetNodes(filter: { annotations: [\"REALM = Custom Targets\"] }) { name"
                    + " target { recordings { active { data { name doDelete { name } } aggregate {"
                    + " count } } archived { data { name doDelete { name } } aggregate { count size"
                    + " } } } } } }");
        HttpResponse<Buffer> resp2 =
                webClient
                        .extensions()
                        .post("/api/v3/graphql", query2.toBuffer(), REQUEST_TIMEOUT_SECONDS);
        MatcherAssert.assertThat(
                resp2.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));
        System.out.println("+++DeleteMetadata Resp: " + resp2.bodyAsString());

        DeleteMutationResponse actual =
                mapper.readValue(resp2.bodyAsString(), DeleteMutationResponse.class);
        System.out.println("+++Delete Metadata(Actual) Resp: " + actual.toString());

        MatcherAssert.assertThat(actual.getData().getTargetNodes(), Matchers.hasSize(1));

        TargetNode node = actual.getData().getTargetNodes().get(0);
        System.out.println("+++TargetNode: " + node.toString());

        MatcherAssert.assertThat(
                node.getTarget().getRecordings().getActive().getData(), Matchers.hasSize(1));
        MatcherAssert.assertThat(
                node.getTarget().getRecordings().getArchived().getData(), Matchers.hasSize(1));
        MatcherAssert.assertThat(
                node.getTarget().getRecordings().getArchived().aggregate.count,
                Matchers.equalTo(1L));
        MatcherAssert.assertThat(
                node.getTarget().getRecordings().getArchived().aggregate.size,
                Matchers.greaterThan(0L));

        ActiveRecording activeRecording =
                node.getTarget().getRecordings().getActive().getData().get(0);
        ArchivedRecording archivedRecording =
                node.getTarget().getRecordings().getArchived().getData().get(0);

        MatcherAssert.assertThat(activeRecording.name, Matchers.equalTo("test"));
        MatcherAssert.assertThat(activeRecording.doDelete.name, Matchers.equalTo("test"));

        MatcherAssert.assertThat(
                archivedRecording.name,
                Matchers.matchesRegex("^selftest_test_[0-9]{8}T[0-9]{6}Z\\.jfr$"));
    }

    @Test
    @Order(8)
    void testNodesHaveIds() throws Exception {
        JsonObject query = new JsonObject();
        query.put(
                "query",
                "query { environmentNodes(filter: { name: \"Custom Targets\" }) { id"
                        + " descendantTargets { id } } }");
        HttpResponse<Buffer> resp =
                webClient
                        .extensions()
                        .post("/api/v3/graphql", query.toBuffer(), REQUEST_TIMEOUT_SECONDS);
        MatcherAssert.assertThat(
                resp.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));
        System.out.println("+++NodesResp: " + resp.bodyAsString());

        // Parse the response
        EnvironmentNodesResponse actual =
                mapper.readValue(resp.bodyAsString(), EnvironmentNodesResponse.class);
        System.out.println("+++Nodes(Actual) Resp: " + actual.toString());

        Set<Long> observedIds = new HashSet<>();
        for (var env : actual.getData().getEnvironmentNodes()) {
            // ids should be unique
            MatcherAssert.assertThat(
                    observedIds, Matchers.not(Matchers.contains(env.id.longValue())));
            observedIds.add(env.id.longValue());
            System.out.println("+++Environment ID: " + env.id.longValue());

            for (var target : env.getDescendantTargets()) {
                MatcherAssert.assertThat(
                        observedIds, Matchers.not(Matchers.contains(target.id.longValue())));
                observedIds.add(target.id.longValue());
                System.out.println("+++Target ID: " + target.id.longValue());

                // Assert that target IDs do not match environment node IDs
                MatcherAssert.assertThat(
                        env.id.longValue(), Matchers.not(Matchers.equalTo(target.id.longValue())));
            }
        }

        // Check if response IDs and actual IDs are the same
        Set<Long> respIds = new HashSet<>();
        EnvironmentNodesResponse respParsed =
                mapper.readValue(resp.bodyAsString(), EnvironmentNodesResponse.class);
        for (var env : respParsed.getData().getEnvironmentNodes()) {
            respIds.add(env.id.longValue());
            for (var target : env.getDescendantTargets()) {
                respIds.add(target.id.longValue());
            }
        }

        // Assert that the actual IDs match the response IDs
        MatcherAssert.assertThat(respIds, Matchers.equalTo(observedIds));
    }

    @Test
    @Order(9)
    void testQueryForSpecificTargetsByNames() throws Exception {
        JsonObject query = new JsonObject();
        query.put(
                "query",
                String.format(
                        "query { targetNodes(filter: { names:"
                                + " [\"service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi\","
                                + " \"service:jmx:rmi:///jndi/rmi://localhost:9091/jmxrmi\"] }) {"
                                + " name nodeType } }"));
        HttpResponse<Buffer> resp =
                webClient
                        .extensions()
                        .post("/api/v3/graphql", query.toBuffer(), REQUEST_TIMEOUT_SECONDS);

        System.out.println("+++QueryForSpecificTargetsByNames Resp: " + resp.bodyAsString());

        MatcherAssert.assertThat(
                resp.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));

        TargetNodesQueryResponse actual =
                mapper.readValue(resp.bodyAsString(), TargetNodesQueryResponse.class);

        System.out.println("+++TargetNodesQueryResponse: " + actual.toString());
        List<TargetNode> targetNodes = actual.getData().getTargetNodes();

        int expectedSize = 1;
        // only one target should be returned
        assertThat(targetNodes.size(), is(expectedSize));

        TargetNode target1 = new TargetNode();
        target1.name = "service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi";
        target1.nodeType = "JVM";

        assertThat(targetNodes, hasItem(target1));
    }

    @Test
    @Order(10)
    public void testQueryForFilteredActiveRecordingsByNames() throws Exception {
        Thread.sleep(5000);
        // Create a Recording 1 name (test)
        JsonObject notificationRecording = createRecording();
        Assertions.assertEquals("test", notificationRecording.getString("name"));

        // create recording 2 name test2
        CountDownLatch latch = new CountDownLatch(2);

        JsonObject query = new JsonObject();
        query.put(
                "query",
                "mutation { createRecording( nodes:{annotations: ["
                        + "\"REALM = Custom Targets\""
                        + "]}, recording: { name: \"test2\", template:"
                        + " \"Profiling\", templateType: \"TARGET\", duration: 30, continuous:"
                        + " false, archiveOnStop: true, toDisk: true }) { name state duration"
                        + " continuous metadata { labels { key value } } } }");
        Future<JsonObject> f =
                worker.submit(
                        () -> {
                            try {
                                return expectNotification(
                                                "ActiveRecordingCreated", 15, TimeUnit.SECONDS)
                                        .get();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                latch.countDown();
                            }
                        });

        Thread.sleep(5000);

        HttpResponse<Buffer> resp =
                webClient
                        .extensions()
                        .post("/api/v3/graphql", query.toBuffer(), REQUEST_TIMEOUT_SECONDS);
        MatcherAssert.assertThat(
                resp.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));
        CreateRecordingMutationResponse actual =
                mapper.readValue(resp.bodyAsString(), CreateRecordingMutationResponse.class);
        System.out.println("+++StartRecording10: " + actual);
        latch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        System.out.println("+++RESP CREATE: " + resp.bodyAsString());
        JsonObject notification = f.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        JsonObject test2 = notification.getJsonObject("message").getJsonObject("recording");
        MatcherAssert.assertThat(test2.getString("name"), Matchers.equalTo("test2"));

        // GraphQL Query to filter Active recordings by names
        JsonObject query2 = new JsonObject();
        query2.put(
                "query",
                "query { targetNodes (filter: { annotations:"
                        + " [\"REALM = Custom Targets\"]}){ target { recordings"
                        + " { active (filter: { names: [\"test\", \"test2\",\"Recording3\"]"
                        + " }) {data {name}}}}}}");
        HttpResponse<Buffer> resp2 =
                webClient
                        .extensions()
                        .post("/api/v3/graphql", query2.toBuffer(), REQUEST_TIMEOUT_SECONDS);

        System.out.println(
                "+++QueryForFilteredActiveRecordingsByNames Resp: " + resp2.bodyAsString());
        MatcherAssert.assertThat(
                resp2.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));

        TargetNodesQueryResponse graphqlResp =
                mapper.readValue(resp2.bodyAsString(), TargetNodesQueryResponse.class);

        System.out.println("+++TargetNodesQueryResponse: " + graphqlResp.toString());

        List<String> filterNames = Arrays.asList("test", "test2");

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
        System.out.println("+++FilteredRecordings: " + filteredRecordings);

        MatcherAssert.assertThat(filteredRecordings.size(), Matchers.equalTo(2));
        ActiveRecording r1 = new ActiveRecording();
        r1.name = "test";
        ActiveRecording r2 = new ActiveRecording();
        r2.name = "test2";

        assertThat(filteredRecordings, hasItem(r1));
        assertThat(filteredRecordings, hasItem(r2));

        // Delete the Recordings
        deleteRecording();
    }

    @Test
    @Order(11)
    public void shouldReturnArchivedRecordingsFilteredByNames() throws Exception {
        // Check preconditions
        CompletableFuture<JsonArray> listRespFuture1 = new CompletableFuture<>();
        webClient
                .get(
                        String.format(
                                "/api/v1/targets/%s/recordings",
                                getSelfReferenceConnectUrlEncoded()))
                .send(
                        ar -> {
                            if (assertRequestStatus(ar, listRespFuture1)) {
                                listRespFuture1.complete(ar.result().bodyAsJsonArray());
                            }
                        });
        JsonArray listResp = listRespFuture1.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Assertions.assertTrue(listResp.isEmpty());

        // Create a new recording
        Thread.sleep(5000);
        JsonObject notificationRecording = createRecording();
        Assertions.assertEquals("test", notificationRecording.getString("name"));

        // Archive the recording
        JsonObject query1 = new JsonObject();
        query1.put(
                "query",
                "mutation { archiveRecording (nodes: { annotations: [\"REALM = Custom Targets\"]},"
                        + " recordings: { name: \"test\"}) { name downloadUrl } }");
        HttpResponse<Buffer> resp1 =
                webClient
                        .extensions()
                        .post("/api/v3/graphql", query1.toBuffer(), REQUEST_TIMEOUT_SECONDS);
        MatcherAssert.assertThat(
                resp1.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));

        String jsonResponse1 = resp1.bodyAsString();
        System.out.println("+++ArchiveMetadata Resp: " + jsonResponse1);

        // Deserialize the response to get the archived recording name
        ArchiveMutationResponse archiveResponse =
                mapper.readValue(jsonResponse1, ArchiveMutationResponse.class);
        List<ArchivedRecording> archivedRecordings =
                archiveResponse.getData().getArchivedRecording();
        MatcherAssert.assertThat(archivedRecordings, Matchers.not(Matchers.empty()));
        MatcherAssert.assertThat(archivedRecordings, Matchers.hasSize(1));

        // retrieve to match the exact name
        CompletableFuture<JsonArray> archivedRecordingsFuture2 = new CompletableFuture<>();
        webClient
                .get(String.format("/api/v1/recordings"))
                .send(
                        ar -> {
                            if (assertRequestStatus(ar, archivedRecordingsFuture2)) {
                                archivedRecordingsFuture2.complete(ar.result().bodyAsJsonArray());
                            }
                        });
        JsonArray retrivedArchivedRecordings =
                archivedRecordingsFuture2.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        JsonObject retrievedArchivedrecordings = retrivedArchivedRecordings.getJsonObject(0);
        System.out.println("+++retrievedArchivedrecordings: " + retrievedArchivedrecordings);
        String retrievedArchivedRecordingsName = retrievedArchivedrecordings.getString("name");

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
        HttpResponse<Buffer> resp =
                webClient
                        .extensions()
                        .post("/api/v3/graphql", query.toBuffer(), REQUEST_TIMEOUT_SECONDS);
        MatcherAssert.assertThat(
                resp.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));
        System.out.println("+++graphqlResp: " + resp.bodyAsString());

        TargetNodesQueryResponse graphqlResp =
                mapper.readValue(resp.bodyAsString(), TargetNodesQueryResponse.class);
        System.out.println("+++graphqlResp Actual: " + graphqlResp);

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
        Assertions.assertEquals(
                1, filteredRecordingsCount, "Number of filtered recordings should be 1");

        ArchivedRecording archivedRecording = archivedRecordings2.get(0);
        String filteredName = archivedRecording.name;
        Assertions.assertEquals(
                filteredName,
                retrievedArchivedRecordingsName,
                "Filtered name should match the archived recording name");

        // Delete archived recording by name
        deleteRecording();

        /* // Retrieve the list of updated archived recordings to verify that the targeted
        // recordings
        // have been deleted
        CompletableFuture<JsonArray> updatedArchivedRecordingsFuture = new CompletableFuture<>();
        webClient
                .get("/api/v1/recordings")
                .send(
                        ar -> {
                            if (assertRequestStatus(ar, updatedArchivedRecordingsFuture)) {
                                updatedArchivedRecordingsFuture.complete(
                                        ar.result().bodyAsJsonArray());
                            }
                        });

        JsonArray updatedArchivedRecordings =
                updatedArchivedRecordingsFuture.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Assert that the targeted recordings have been deleted
        boolean recordingsDeleted =
                updatedArchivedRecordings.stream()
                        .noneMatch(
                                json -> {
                                    JsonObject recording = (JsonObject) json;
                                    return recording.getString("name").equals(TEST_RECORDING_NAME);
                                });

        Assertions.assertTrue(
                recordingsDeleted, "The targeted archived recordings should be deleted"); */
    }

    @Test
    @Order(12)
    public void testQueryforFilteredEnvironmentNodesByNames() throws Exception {
        JsonObject query = new JsonObject();
        query.put(
                "query",
                "query { environmentNodes(filter: { names: [\"anotherName1\","
                        + " \"JDP\",\"anotherName2\"] }) { name nodeType } }");
        HttpResponse<Buffer> resp =
                webClient
                        .extensions()
                        .post("/api/v3/graphql", query.toBuffer(), REQUEST_TIMEOUT_SECONDS);
        MatcherAssert.assertThat(
                resp.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));

        System.out.println("+++graphqlResp env by names: " + resp.bodyAsString());
        EnvironmentNodesResponse actual =
                mapper.readValue(resp.bodyAsString(), EnvironmentNodesResponse.class);
        List<DiscoveryNode> environmentNodes = actual.getData().getEnvironmentNodes();

        System.out.println("+++graphqlResp env by names Actual: " + environmentNodes);
        Assertions.assertEquals(1, environmentNodes.size(), "The list filtered should be 1");

        boolean nameExists = false;
        for (DiscoveryNode environmentNode : environmentNodes) {
            if (environmentNode.name.matches("JDP")) {
                nameExists = true;
                break;
            }
        }
        Assertions.assertTrue(nameExists, "Name not found");
    }

    @Test
    @Order(13)
    void testReplaceAlwaysOnStoppedRecording() throws Exception {
        try {
            // Start a Recording
            JsonObject notificationRecording = createRecording();
            Assertions.assertEquals("test", notificationRecording.getString("name"));
            Assertions.assertEquals("RUNNING", notificationRecording.getString("state"));

            // Stop the Recording
            notificationRecording = stopRecording();
            Assertions.assertEquals("test", notificationRecording.getString("name"));
            Assertions.assertEquals("STOPPED", notificationRecording.getString("state"));

            // Restart the recording with replace:ALWAYS
            notificationRecording = restartRecording("ALWAYS");
            Assertions.assertEquals("test", notificationRecording.getString("name"));
            Assertions.assertEquals("RUNNING", notificationRecording.getString("state"));
        } finally {
            // Delete the Recording
            deleteRecording();
        }
    }

    @Test
    @Order(14)
    void testReplaceNeverOnStoppedRecording() throws Exception {
        try {
            // Start a Recording
            JsonObject notificationRecording = createRecording();
            Assertions.assertEquals("test", notificationRecording.getString("name"));
            Assertions.assertEquals("RUNNING", notificationRecording.getString("state"));

            // Stop the Recording
            notificationRecording = stopRecording();
            Assertions.assertEquals("test", notificationRecording.getString("name"));
            Assertions.assertEquals("STOPPED", notificationRecording.getString("state"));

            // Restart the recording with replace:NEVER
            JsonObject error = restartRecordingWithError("NEVER");
            System.out.println("+++graphqlResp error: " + error);
            Assertions.assertTrue(
                    error.getString("message").contains("System error"),
                    "Expected error message to contain 'System error'");
        } finally {
            // Delete the Recording
            deleteRecording();
        }
    }

    @Test
    @Order(15)
    void testReplaceStoppedOnStoppedRecording() throws Exception {
        try {
            // Start a Recording
            JsonObject notificationRecording = createRecording();
            Assertions.assertEquals("test", notificationRecording.getString("name"));
            Assertions.assertEquals("RUNNING", notificationRecording.getString("state"));

            // Stop the Recording
            notificationRecording = stopRecording();
            Assertions.assertEquals("test", notificationRecording.getString("name"));
            Assertions.assertEquals("STOPPED", notificationRecording.getString("state"));

            // Restart the recording with replace:STOPPED
            notificationRecording = restartRecording("STOPPED");
            Assertions.assertEquals("test", notificationRecording.getString("name"));
            Assertions.assertEquals("RUNNING", notificationRecording.getString("state"));
        } finally {
            // Delete the Recording
            deleteRecording();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"STOPPED", "NEVER"})
    @Order(16)
    void testReplaceStoppedOrNeverOnRunningRecording(String replace) throws Exception {
        try {
            // Start a Recording
            JsonObject notificationRecording = createRecording();
            Assertions.assertEquals("test", notificationRecording.getString("name"));
            Assertions.assertEquals("RUNNING", notificationRecording.getString("state"));

            // Restart the recording with the provided string values above
            JsonObject error = restartRecordingWithError(replace);
            Assertions.assertTrue(
                    error.getString("message").contains("System error"),
                    "Expected error message to contain 'System error'");
        } finally {
            // Delete the Recording
            deleteRecording();
        }
    }

    @Test
    @Order(17)
    void testReplaceAlwaysOnRunningRecording() throws Exception {
        try {
            // Start a Recording
            JsonObject notificationRecording = createRecording();
            Assertions.assertEquals("test", notificationRecording.getString("name"));
            Assertions.assertEquals("RUNNING", notificationRecording.getString("state"));

            // Restart the recording with replace:ALWAYS
            notificationRecording = restartRecording("ALWAYS");
            Assertions.assertEquals("test", notificationRecording.getString("name"));
            Assertions.assertEquals("RUNNING", notificationRecording.getString("state"));
        } finally {
            // Delete the Recording
            deleteRecording();
        }
    }

    // restart has been deprecated and is no added longer a field in RecordingSettingsInput (see 3.0
    // schema)
    /* @Test
    @Order(18)
    void testRestartTrueOnRunningRecording() throws Exception {
        try {
            // Start a Recording
            JsonObject notificationRecording = createRecording();
            Assertions.assertEquals("test", notificationRecording.getString("name"));
            Assertions.assertEquals("RUNNING", notificationRecording.getString("state"));

            // Restart the recording with replace:ALWAYS
            notificationRecording = restartRecording(true);
            Assertions.assertEquals("test", notificationRecording.getString("name"));
            Assertions.assertEquals("RUNNING", notificationRecording.getString("state"));
        } finally {
            // Delete the Recording
            deleteRecording();
        }
    }

    @Disabled
    @Test
    @Order(19)
    void testRestartFalseOnRunningRecording() throws Exception {
        try {
            // Start a Recording
            JsonObject notificationRecording = createRecording();
            Assertions.assertEquals("test", notificationRecording.getString("name"));
            Assertions.assertEquals("RUNNING", notificationRecording.getString("state"));

            // Restart the recording with replace:NEVER
            JsonObject error = restartRecordingWithError(false);
            Assertions.assertTrue(
                    error.getString("message")
                            .contains("Recording with name \"test\" already exists"),
                    "Expected error message to contain 'Recording with name \"test\" already"
                            + " exists'");
        } finally {
            // Delete the Recording
            deleteRecording();
        }
    }

    @Disabled
    @Test
    @Order(20)
    void testRestartTrueOnStoppedRecording() throws Exception {
        try {
            // Start a Recording
            JsonObject notificationRecording = createRecording();
            Assertions.assertEquals("test", notificationRecording.getString("name"));
            Assertions.assertEquals("RUNNING", notificationRecording.getString("state"));

            // Stop the Recording
            notificationRecording = stopRecording();
            Assertions.assertEquals("test", notificationRecording.getString("name"));
            Assertions.assertEquals("STOPPED", notificationRecording.getString("state"));

            // Restart the recording with replace:ALWAYS
            notificationRecording = restartRecording(true);
            Assertions.assertEquals("test", notificationRecording.getString("name"));
            Assertions.assertEquals("RUNNING", notificationRecording.getString("state"));
        } finally {
            // Delete the Recording
            deleteRecording();
        }
    }

    @Disabled
    @Test
    @Order(21)
    void testRestartFalseOnStoppedRecording() throws Exception {
        try {
            // Start a Recording
            JsonObject notificationRecording = createRecording();
            Assertions.assertEquals("test", notificationRecording.getString("name"));
            Assertions.assertEquals("RUNNING", notificationRecording.getString("state"));

            // Stop the Recording
            notificationRecording = stopRecording();
            Assertions.assertEquals("test", notificationRecording.getString("name"));
            Assertions.assertEquals("STOPPED", notificationRecording.getString("state"));

            // Restart the recording with replace:NEVER
            JsonObject error = restartRecordingWithError(false);
            Assertions.assertTrue(
                    error.getString("message")
                            .contains("Recording with name \"test\" already exists"),
                    "Expected error message to contain 'Recording with name \"test\" already"
                            + " exists'");
        } finally {
            // Delete the Recording
            deleteRecording();
        }
    } */

    @ParameterizedTest
    @ValueSource(strings = {"ALWAYS", "STOPPED", "NEVER"})
    @Order(18)
    void testStartingNewRecordingWithAllReplaceValues(String replace) throws Exception {
        try {
            JsonObject notificationRecording = restartRecording(replace);
            Assertions.assertEquals("test", notificationRecording.getString("name"));
            Assertions.assertEquals("RUNNING", notificationRecording.getString("state"));
        } finally {
            // Delete the Recording
            deleteRecording();
        }
    }

    // restart is deprecated on (3.0 schema)
    /*     @Disabled
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Order(23)
    void testRestartRecordingWithReplaceTrue(boolean restart) throws Exception {
        try {
            JsonObject notificationRecording = restartRecording(restart);
            Assertions.assertEquals("test", notificationRecording.getString("name"));
            Assertions.assertEquals("RUNNING", notificationRecording.getString("state"));
        } finally {
            // Delete the recording
            deleteRecording();
        }
    } */

    static class Target {
        String alias;
        String connectUrl;
        String jvmId;
        Annotations annotations;
        Recordings recordings;

        public String getAlias() {
            return alias;
        }

        public String getJvmId() {
            return jvmId;
        }

        public void setJvmId(String jvmId) {
            this.jvmId = jvmId;
        }

        public void setAlias(String alias) {
            this.alias = alias;
        }

        public String getConnectUrl() {
            return connectUrl;
        }

        public void setConnectUrl(String connectUrl) {
            this.connectUrl = connectUrl;
        }

        public Annotations getAnnotations() {
            return annotations;
        }

        public void setAnnotations(Annotations annotations) {
            this.annotations = annotations;
        }

        public Recordings getRecordings() {
            return recordings;
        }

        public void setRecordings(Recordings recordings) {
            this.recordings = recordings;
        }

        @Override
        public int hashCode() {
            return Objects.hash(alias, connectUrl, annotations, recordings);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            Target other = (Target) obj;
            return Objects.equals(alias, other.alias)
                    && Objects.equals(connectUrl, other.connectUrl)
                    && Objects.equals(jvmId, other.jvmId)
                    && Objects.equals(annotations, other.annotations)
                    && Objects.equals(recordings, other.recordings);
        }

        @Override
        public String toString() {
            return "Target [alias="
                    + alias
                    + ", connectUrl="
                    + connectUrl
                    + ", jvmId="
                    + jvmId
                    + ", annotations="
                    + annotations
                    + ", recordings="
                    + recordings
                    + "]";
        }
    }

    public static class KeyValue {
        private String key;
        private String value;

        public KeyValue() {}

        public KeyValue(String key, String value) {
            this.key = key;
            this.value = value;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            KeyValue keyValue = (KeyValue) o;
            return Objects.equals(key, keyValue.key) && Objects.equals(value, keyValue.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, value);
        }

        @Override
        public String toString() {
            return "KeyValue{" + "key='" + key + '\'' + ", value='" + value + '\'' + '}';
        }
    }

    public static class Annotations {
        private List<KeyValue> cryostat;
        private List<KeyValue> platform;

        public List<KeyValue> getCryostat() {
            return cryostat;
        }

        public void setCryostat(List<KeyValue> cryostat) {
            this.cryostat = cryostat;
        }

        public List<KeyValue> getPlatform() {
            return platform;
        }

        public void setPlatform(List<KeyValue> platform) {
            this.platform = platform;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Annotations that = (Annotations) o;
            return Objects.equals(cryostat, that.cryostat)
                    && Objects.equals(platform, that.platform);
        }

        @Override
        public int hashCode() {
            return Objects.hash(cryostat, platform);
        }

        @Override
        public String toString() {
            return "Annotations{" + "cryostat=" + cryostat + ", platform=" + platform + '}';
        }
    }

    static class ArchivedRecording {
        String name;
        String reportUrl;
        String downloadUrl;
        RecordingMetadata metadata;
        long size;
        long archivedTime;
        List<KeyValue> labels;

        ArchivedRecording doDelete;

        private DoPutMetadata doPutMetadata;

        public static class DoPutMetadata {
            private RecordingMetadata metadata;

            public RecordingMetadata getMetadata() {
                return metadata;
            }

            public void setMetadata(RecordingMetadata metadata) {
                this.metadata = metadata;
            }

            @Override
            public String toString() {
                return "DoPutMetadata [metadata=" + metadata + "]";
            }

            @Override
            public int hashCode() {
                return Objects.hash(metadata);
            }

            @Override
            public boolean equals(Object obj) {
                if (this == obj) return true;
                if (obj == null) return false;
                if (getClass() != obj.getClass()) return false;
                DoPutMetadata other = (DoPutMetadata) obj;
                return Objects.equals(metadata, other.metadata);
            }
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getReportUrl() {
            return reportUrl;
        }

        public void setReportUrl(String reportUrl) {
            this.reportUrl = reportUrl;
        }

        public String getDownloadUrl() {
            return downloadUrl;
        }

        public void setDownloadUrl(String downloadUrl) {
            this.downloadUrl = downloadUrl;
        }

        public RecordingMetadata getMetadata() {
            return metadata;
        }

        public void setMetadata(RecordingMetadata metadata) {
            this.metadata = metadata;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }

        public long getArchivedTime() {
            return archivedTime;
        }

        public void setArchivedTime(long archivedTime) {
            this.archivedTime = archivedTime;
        }

        public List<KeyValue> getLabels() {
            return labels;
        }

        public void setLabels(List<KeyValue> labels) {
            this.labels = labels;
        }

        public DoPutMetadata getDoPutMetadata() {
            return doPutMetadata;
        }

        public void setDoPutMetadata(DoPutMetadata doPutMetadata) {
            this.doPutMetadata = doPutMetadata;
        }

        @Override
        public String toString() {
            return "ArchivedRecording [archivedTime="
                    + archivedTime
                    + ", doDelete="
                    + doDelete
                    + ", downloadUrl="
                    + downloadUrl
                    + ", labels="
                    + labels
                    + ", metadata="
                    + metadata
                    + ", name="
                    + name
                    + ", reportUrl="
                    + reportUrl
                    + ", size="
                    + size
                    + ", doPutMetadata="
                    + doPutMetadata
                    + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    archivedTime,
                    doDelete,
                    downloadUrl,
                    labels,
                    metadata,
                    name,
                    reportUrl,
                    size,
                    doPutMetadata);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            ArchivedRecording other = (ArchivedRecording) obj;
            return Objects.equals(archivedTime, other.archivedTime)
                    && Objects.equals(doDelete, other.doDelete)
                    && Objects.equals(downloadUrl, other.downloadUrl)
                    && Objects.equals(labels, other.labels)
                    && Objects.equals(metadata, other.metadata)
                    && Objects.equals(name, other.name)
                    && Objects.equals(reportUrl, other.reportUrl)
                    && Objects.equals(size, other.size)
                    && Objects.equals(doPutMetadata, other.doPutMetadata);
        }
    }

    static class AggregateInfo {
        long count;
        long size;

        @Override
        public String toString() {
            return "AggregateInfo [count=" + count + ", size=" + size + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(count, size);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;
            AggregateInfo other = (AggregateInfo) obj;
            if (count != other.count) return false;
            if (size != other.size) return false;
            return true;
        }
    }

    static class ArchivedRecordings {
        List<ArchivedRecording> data;
        AggregateInfo aggregate;

        public List<ArchivedRecording> getData() {
            return data;
        }

        public void setData(List<ArchivedRecording> data) {
            this.data = data;
        }

        @Override
        public String toString() {
            return "Archived [data=" + data + ", aggregate=" + aggregate + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(data, aggregate);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            ArchivedRecordings other = (ArchivedRecordings) obj;
            return Objects.equals(data, other.data) && Objects.equals(aggregate, other.aggregate);
        }
    }

    static class Recordings {
        private ActiveRecordings active;
        private ArchivedRecordings archived;

        public ActiveRecordings getActive() {
            return active;
        }

        public void setActive(ActiveRecordings active) {
            this.active = active;
        }

        public ArchivedRecordings getArchived() {
            return archived;
        }

        public void setArchived(ArchivedRecordings archived) {
            this.archived = archived;
        }

        @Override
        public String toString() {
            return "Recordings [active=" + active + ", archived=" + archived + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(active, archived);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            Recordings other = (Recordings) obj;
            return Objects.equals(active, other.active) && Objects.equals(archived, other.archived);
        }
    }

    static class TargetNode {
        private String name;
        private BigInteger id;
        private String nodeType;
        private List<Label> labels;
        private Target target;
        private Recordings recordings;
        private ActiveRecording doStartRecording;

        public String getName() {
            return name;
        }

        public BigInteger getId() {
            return id;
        }

        public void setId(BigInteger id) {
            this.id = id;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getNodeType() {
            return nodeType;
        }

        public void setNodeType(String nodeType) {
            this.nodeType = nodeType;
        }

        public List<Label> getLabels() {
            return labels;
        }

        public void setLabels(List<Label> labels) {
            this.labels = labels;
        }

        public Target getTarget() {
            return target;
        }

        public void setTarget(Target target) {
            this.target = target;
        }

        public Recordings getRecordings() {
            return recordings;
        }

        public void setRecordings(Recordings recordings) {
            this.recordings = recordings;
        }

        public ActiveRecording getDoStartRecording() {
            return doStartRecording;
        }

        public void setDoStartRecording(ActiveRecording doStartRecording) {
            this.doStartRecording = doStartRecording;
        }

        @Override
        public String toString() {
            return "TargetNode [doStartRecording="
                    + doStartRecording
                    + ", labels="
                    + labels
                    + ", name="
                    + name
                    + ", id="
                    + id
                    + ", nodeType="
                    + nodeType
                    + ", target="
                    + target
                    + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(doStartRecording, labels, name, id, nodeType, recordings, target);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            TargetNode other = (TargetNode) obj;
            return Objects.equals(doStartRecording, other.doStartRecording)
                    && Objects.equals(labels, other.labels)
                    && Objects.equals(name, other.name)
                    && Objects.equals(id, other.id)
                    && Objects.equals(nodeType, other.nodeType)
                    && Objects.equals(target, other.target);
        }
    }

    public static class Label {
        private String key;
        private String value;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Label label = (Label) o;
            return Objects.equals(key, label.key) && Objects.equals(value, label.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, value);
        }
    }

    static class TargetNodes {
        @JsonProperty("targetNodes")
        private List<TargetNode> targetNodes;

        public List<TargetNode> getTargetNodes() {
            return targetNodes;
        }

        public void setTargetNodes(List<TargetNode> targetNodes) {
            this.targetNodes = targetNodes;
        }

        @Override
        public int hashCode() {
            return Objects.hash(targetNodes);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            TargetNodes other = (TargetNodes) obj;
            return Objects.equals(targetNodes, other.targetNodes);
        }

        @Override
        public String toString() {
            return "TargetNodes [targetNodes=" + targetNodes + "]";
        }
    }

    static class TargetNodesQueryResponse {
        private Data data;

        public Data getData() {
            return data;
        }

        public void setData(Data data) {
            this.data = data;
        }

        @Override
        public int hashCode() {
            return Objects.hash(data);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            TargetNodesQueryResponse other = (TargetNodesQueryResponse) obj;
            return Objects.equals(data, other.data);
        }

        @Override
        public String toString() {
            return "TargetNodesQueryResponse{" + "data=" + data + '}';
        }

        public static class Data {
            private List<TargetNode> targetNodes;

            public List<TargetNode> getTargetNodes() {
                return targetNodes;
            }

            public void setTargetNodes(List<TargetNode> targetNodes) {
                this.targetNodes = targetNodes;
            }

            @Override
            public String toString() {
                return "Data{" + "targetNodes=" + targetNodes + '}';
            }

            @Override
            public int hashCode() {
                return Objects.hash(targetNodes);
            }

            @Override
            public boolean equals(Object obj) {
                if (this == obj) {
                    return true;
                }
                if (obj == null) {
                    return false;
                }
                if (getClass() != obj.getClass()) {
                    return false;
                }
                Data other = (Data) obj;
                return Objects.equals(targetNodes, other.targetNodes);
            }
        }
    }

    static class ActiveRecordings {
        private List<ActiveRecording> data;
        AggregateInfo aggregate;

        public List<ActiveRecording> getData() {
            return data;
        }

        public void setData(List<ActiveRecording> data) {
            this.data = data;
        }

        @Override
        public String toString() {
            return "Active [data=" + data + ", aggregate=" + aggregate + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(data, aggregate);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            ActiveRecordings other = (ActiveRecordings) obj;
            return Objects.equals(data, other.data) && Objects.equals(aggregate, other.aggregate);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ActiveRecording {
        long id;
        long remoteId;
        String name;
        String reportUrl;
        String downloadUrl;
        RecordingMetadata metadata;
        String state;
        long startTime;
        long duration;
        boolean continuous;
        boolean toDisk;
        long maxSize;
        long maxAge;
        List<KeyValue> labels;

        ArchivedRecording doArchive;
        ActiveRecording doDelete;

        private DoPutMetadata doPutMetadata;

        public static class DoPutMetadata {
            private RecordingMetadata metadata;

            public RecordingMetadata getMetadata() {
                return metadata;
            }

            public void setMetadata(RecordingMetadata metadata) {
                this.metadata = metadata;
            }

            @Override
            public String toString() {
                return "DoPutMetadata [metadata=" + metadata + "]";
            }

            @Override
            public int hashCode() {
                return Objects.hash(metadata);
            }

            @Override
            public boolean equals(Object obj) {
                if (this == obj) return true;
                if (obj == null) return false;
                if (getClass() != obj.getClass()) return false;
                DoPutMetadata other = (DoPutMetadata) obj;
                return Objects.equals(metadata, other.metadata);
            }
        }

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public long getRemoteId() {
            return remoteId;
        }

        public void setRemoteId(long remoteId) {
            this.remoteId = remoteId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getReportUrl() {
            return reportUrl;
        }

        public void setReportUrl(String reportUrl) {
            this.reportUrl = reportUrl;
        }

        public String getDownloadUrl() {
            return downloadUrl;
        }

        public void setDownloadUrl(String downloadUrl) {
            this.downloadUrl = downloadUrl;
        }

        public RecordingMetadata getMetadata() {
            return metadata;
        }

        public void setMetadata(RecordingMetadata metadata) {
            this.metadata = metadata;
        }

        public String getState() {
            return state;
        }

        public void setState(String state) {
            this.state = state;
        }

        public long getStartTime() {
            return startTime;
        }

        public void setStartTime(long startTime) {
            this.startTime = startTime;
        }

        public long getDuration() {
            return duration;
        }

        public void setDuration(long duration) {
            this.duration = duration;
        }

        public boolean isContinuous() {
            return continuous;
        }

        public void setContinuous(boolean continuous) {
            this.continuous = continuous;
        }

        public boolean isToDisk() {
            return toDisk;
        }

        public void setToDisk(boolean toDisk) {
            this.toDisk = toDisk;
        }

        public long getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(long maxSize) {
            this.maxSize = maxSize;
        }

        public long getMaxAge() {
            return maxAge;
        }

        public void setMaxAge(long maxAge) {
            this.maxAge = maxAge;
        }

        public List<KeyValue> getLabels() {
            return labels;
        }

        public void setLabels(List<KeyValue> labels) {
            this.labels = labels;
        }

        public DoPutMetadata getDoPutMetadata() {
            return doPutMetadata;
        }

        public void setDoPutMetadata(DoPutMetadata doPutMetadata) {
            this.doPutMetadata = doPutMetadata;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    id,
                    remoteId,
                    name,
                    reportUrl,
                    downloadUrl,
                    metadata,
                    state,
                    startTime,
                    duration,
                    continuous,
                    toDisk,
                    maxSize,
                    maxAge,
                    labels,
                    doPutMetadata);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;
            ActiveRecording other = (ActiveRecording) obj;
            return id == other.id
                    && remoteId == other.remoteId
                    && duration == other.duration
                    && continuous == other.continuous
                    && toDisk == other.toDisk
                    && maxSize == other.maxSize
                    && maxAge == other.maxAge
                    && startTime == other.startTime
                    && Objects.equals(name, other.name)
                    && Objects.equals(reportUrl, other.reportUrl)
                    && Objects.equals(downloadUrl, other.downloadUrl)
                    && Objects.equals(metadata, other.metadata)
                    && Objects.equals(state, other.state)
                    && Objects.equals(labels, other.labels)
                    && Objects.equals(doPutMetadata, other.doPutMetadata);
        }

        @Override
        public String toString() {
            return "ActiveRecording{"
                    + "id="
                    + id
                    + ", remoteId="
                    + remoteId
                    + ", name='"
                    + name
                    + '\''
                    + ", reportUrl='"
                    + reportUrl
                    + '\''
                    + ", downloadUrl='"
                    + downloadUrl
                    + '\''
                    + ", metadata="
                    + metadata
                    + ", state='"
                    + state
                    + '\''
                    + ", startTime="
                    + startTime
                    + ", duration="
                    + duration
                    + ", continuous="
                    + continuous
                    + ", toDisk="
                    + toDisk
                    + ", maxSize="
                    + maxSize
                    + ", maxAge="
                    + maxAge
                    + ", labels="
                    + labels
                    + ", doPutMetadata="
                    + doPutMetadata
                    + '}';
        }
    }

    static class RecordingMetadata {
        List<KeyValue> labels;

        public static RecordingMetadata of(Map<String, String> of) {
            var list = new ArrayList<KeyValue>();
            of.forEach((k, v) -> list.add(new KeyValue(k, v)));
            var rm = new RecordingMetadata();
            rm.labels = list;
            return rm;
        }

        @Override
        public int hashCode() {
            return Objects.hash(labels);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            RecordingMetadata other = (RecordingMetadata) obj;
            return labels.size() == other.labels.size()
                    && (Objects.equals(new HashSet<>(labels), new HashSet<>(other.labels)));
        }

        @Override
        public String toString() {
            return "RecordingMetadata [labels=" + labels + "]";
        }
    }

    static class StartRecording {
        ActiveRecording doStartRecording;
        ArchivedRecording doArchive;
        ActiveRecording doPutMetadata;

        @Override
        public int hashCode() {
            return Objects.hash(doArchive, doStartRecording, doPutMetadata);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            StartRecording other = (StartRecording) obj;
            return Objects.equals(doArchive, other.doArchive)
                    && Objects.equals(doStartRecording, other.doStartRecording)
                    && Objects.equals(doPutMetadata, other.doPutMetadata);
        }

        @Override
        public String toString() {
            return "StartRecording [doArchive="
                    + doArchive
                    + ", doStartRecording="
                    + doStartRecording
                    + ", doPutMetadata="
                    + doPutMetadata
                    + "]";
        }
    }

    static class RecordingNodes {
        List<StartRecording> targetNodes;

        @Override
        public int hashCode() {
            return Objects.hash(targetNodes);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            RecordingNodes other = (RecordingNodes) obj;
            return Objects.equals(targetNodes, other.targetNodes);
        }

        @Override
        public String toString() {
            return "RecordingNodes [targetNodes=" + targetNodes + "]";
        }
    }

    static class CreateRecording {
        @JsonProperty("createRecording")
        List<ActiveRecording> recordings;

        public List<ActiveRecording> getRecordings() {
            return recordings;
        }

        public void setData(List<ActiveRecording> recordings) {
            this.recordings = recordings;
        }

        @Override
        public int hashCode() {
            return Objects.hash(recordings);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            CreateRecording other = (CreateRecording) obj;
            return Objects.equals(recordings, other.recordings);
        }

        @Override
        public String toString() {
            return "CreateRecording [recordings=" + recordings + "]";
        }
    }

    static class CreateRecordingMutationResponse {
        @JsonProperty("data")
        CreateRecording data;

        public CreateRecording getData() {
            return data;
        }

        public void setData(CreateRecording data) {
            this.data = data;
        }

        @Override
        public int hashCode() {
            return Objects.hash(data);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            CreateRecordingMutationResponse other = (CreateRecordingMutationResponse) obj;
            return Objects.equals(data, other.data);
        }

        @Override
        public String toString() {
            return "CreateRecordingMutationResponse [data=" + data + "]";
        }
    }

    static class Node {
        int id;
        String name;
        String nodeType;

        @Override
        public String toString() {
            return "Node [id=" + id + ", name=" + name + ", nodeType=" + nodeType + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, name, nodeType);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            Node other = (Node) obj;
            return id == other.id
                    && Objects.equals(name, other.name)
                    && Objects.equals(nodeType, other.nodeType);
        }
    }

    static class EnvironmentNode extends Node {
        List<Node> children;

        @Override
        public String toString() {
            return "EnvironmentNode [children ="
                    + children
                    + ", name="
                    + name
                    + ", nodeType="
                    + nodeType
                    + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(children);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            EnvironmentNode other = (EnvironmentNode) obj;
            return Objects.equals(children, other.children);
        }
    }

    public class EnvironmentNodes {
        private List<EnvironmentNode> environmentNodes;

        public List<EnvironmentNode> getEnvironmentNodes() {
            return environmentNodes;
        }

        public void setEnvironmentNodes(List<EnvironmentNode> environmentNodes) {
            this.environmentNodes = environmentNodes;
        }

        @Override
        public String toString() {
            return "EnvironmentNodes [environmentNodes=" + environmentNodes + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(environmentNodes);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            EnvironmentNodes other = (EnvironmentNodes) obj;
            return Objects.equals(environmentNodes, other.environmentNodes);
        }
    }

    public static class EnvironmentNodesResponse {
        private Data data;

        public Data getData() {
            return data;
        }

        public void setData(Data data) {
            this.data = data;
        }

        public static class Data {
            private List<DiscoveryNode> environmentNodes;

            public List<DiscoveryNode> getEnvironmentNodes() {
                return environmentNodes;
            }

            public void setEnvironmentNodes(List<DiscoveryNode> environmentNodes) {
                this.environmentNodes = environmentNodes;
            }
        }

        @Override
        public String toString() {
            return "EnvironmentNodesResponse [data=" + data + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(data);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            EnvironmentNodesResponse other = (EnvironmentNodesResponse) obj;
            return Objects.equals(data, other.data);
        }
    }

    static class ArchiveMutationResponse {
        protected Data data;

        public static class Data {
            private List<ArchivedRecording> archiveRecording;

            public List<ArchivedRecording> getArchivedRecording() {
                return archiveRecording;
            }

            public void setArchiveRecording(List<ArchivedRecording> archiveRecording) {
                this.archiveRecording = archiveRecording;
            }

            @Override
            public String toString() {
                return "Data{" + "archiveRecording=" + archiveRecording + '}';
            }
        }

        public Data getData() {
            return data;
        }

        public void setData(Data data) {
            this.data = data;
        }

        @Override
        public String toString() {
            return "ArchiveMutationResponse [data=" + data + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(data);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            ArchiveMutationResponse other = (ArchiveMutationResponse) obj;
            return Objects.equals(data, other.data);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ActiveMutationResponse {
        @JsonProperty("data")
        private ActiveData data;

        public ActiveData getData() {
            return data;
        }

        public void setData(ActiveData data) {
            this.data = data;
        }

        @Override
        public String toString() {
            return "ActiveMutationResponse [data=" + data + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(data);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            ActiveMutationResponse other = (ActiveMutationResponse) obj;
            return Objects.equals(data, other.data);
        }

        static class ActiveData {
            private List<TargetNode> targetNodes;

            public List<TargetNode> getTargetNodes() {
                return targetNodes;
            }

            public void setTargetNodes(List<TargetNode> targetNodes) {
                this.targetNodes = targetNodes;
            }

            @Override
            public String toString() {
                return "ActiveData{" + "targetNodes=" + targetNodes + '}';
            }

            @Override
            public int hashCode() {
                return Objects.hash(targetNodes);
            }

            @Override
            public boolean equals(Object obj) {
                if (this == obj) {
                    return true;
                }
                if (obj == null) {
                    return false;
                }
                if (getClass() != obj.getClass()) {
                    return false;
                }
                ActiveData other = (ActiveData) obj;
                return Objects.equals(targetNodes, other.targetNodes);
            }
        }
    }

    static class MetadataUpdateResponse {
        private Data data;

        public Data getData() {
            return data;
        }

        public void setData(Data data) {
            this.data = data;
        }

        @Override
        public String toString() {
            return "MetadataUpdateResponse [data=" + data + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(data);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            MetadataUpdateResponse other = (MetadataUpdateResponse) obj;
            return Objects.equals(data, other.data);
        }

        static class Data {
            private List<TargetNode> targetNodes;

            public List<TargetNode> getTargetNodes() {
                return targetNodes;
            }

            public void setTargetNodes(List<TargetNode> targetNodes) {
                this.targetNodes = targetNodes;
            }

            @Override
            public String toString() {
                return "Data{" + "targetNodes=" + targetNodes + '}';
            }

            @Override
            public int hashCode() {
                return Objects.hash(targetNodes);
            }

            @Override
            public boolean equals(Object obj) {
                if (this == obj) {
                    return true;
                }
                if (obj == null) {
                    return false;
                }
                if (getClass() != obj.getClass()) {
                    return false;
                }
                Data other = (Data) obj;
                return Objects.equals(targetNodes, other.targetNodes);
            }
        }
    }

    static class DeleteMutationResponse {
        private Data data;

        public Data getData() {
            return data;
        }

        public void setData(Data data) {
            this.data = data;
        }

        @Override
        public String toString() {
            return "DeleteMutationResponse{" + "data=" + data + '}';
        }

        @Override
        public int hashCode() {
            return Objects.hash(data);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            DeleteMutationResponse other = (DeleteMutationResponse) obj;
            return Objects.equals(data, other.data);
        }

        static class Data {
            private List<TargetNode> targetNodes;

            public List<TargetNode> getTargetNodes() {
                return targetNodes;
            }

            public void setTargetNodes(List<TargetNode> targetNodes) {
                this.targetNodes = targetNodes;
            }

            @Override
            public String toString() {
                return "Data{" + "targetNodes=" + targetNodes + '}';
            }

            @Override
            public int hashCode() {
                return Objects.hash(targetNodes);
            }

            @Override
            public boolean equals(Object obj) {
                if (this == obj) {
                    return true;
                }
                if (obj == null) {
                    return false;
                }
                if (getClass() != obj.getClass()) {
                    return false;
                }
                Data other = (Data) obj;
                return Objects.equals(targetNodes, other.targetNodes);
            }
        }
    }

    // start recording
    private JsonObject createRecording() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

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
                                return expectNotification(
                                                "ActiveRecordingCreated", 15, TimeUnit.SECONDS)
                                        .get();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                latch.countDown();
                            }
                        });

        Thread.sleep(5000);

        HttpResponse<Buffer> resp =
                webClient
                        .extensions()
                        .post("/api/v3/graphql", query.toBuffer(), REQUEST_TIMEOUT_SECONDS);
        MatcherAssert.assertThat(
                resp.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));

        latch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        System.out.println("+++RESP CREATE: " + resp.bodyAsString());
        JsonObject notification = f.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return notification.getJsonObject("message").getJsonObject("recording");
    }

    // Stop the Recording
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
                                return expectNotification(
                                                "ActiveRecordingStopped", 15, TimeUnit.SECONDS)
                                        .get();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                latch.countDown();
                            }
                        });

        Thread.sleep(5000);

        HttpResponse<Buffer> resp =
                webClient
                        .extensions()
                        .post("/api/v3/graphql", query.toBuffer(), REQUEST_TIMEOUT_SECONDS);
        MatcherAssert.assertThat(
                resp.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));

        latch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        System.out.println("+++RESP STOP: " + resp.bodyAsString());
        JsonObject notification = f2.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return notification.getJsonObject("message").getJsonObject("recording");
    }

    // Delete the Recording
    private void deleteRecording() throws Exception {
        JsonObject query = new JsonObject();
        query.put(
                "query",
                "query { targetNodes(filter: { annotations: [\"REALM = Custom Targets\"] }) { name"
                    + " target { recordings { active { data { name doDelete { name } } aggregate {"
                    + " count } } archived { data { name doDelete { name } } aggregate { count size"
                    + " } } } } } }");
        HttpResponse<Buffer> resp =
                webClient
                        .extensions()
                        .post("/api/v3/graphql", query.toBuffer(), REQUEST_TIMEOUT_SECONDS);
        MatcherAssert.assertThat(
                resp.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));
        System.out.println("+++Delete Resp: " + resp.bodyAsString());
    }

    // Restart the recording with given replacement policy
    private JsonObject restartRecording(String replace) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        JsonObject query = new JsonObject();
        query.put(
                "query",
                String.format(
                        "query { targetNodes(filter: { annotations: [\"REALM = Custom Targets\"] })"
                            + " { name target { doStartRecording ( recording: { name: \"test\","
                            + " template: \"Profiling\", templateType: \"TARGET\", replace: \"%s\""
                            + " }) { name state } } } }",
                        replace));
        Future<JsonObject> f =
                worker.submit(
                        () -> {
                            try {
                                return expectNotification(
                                                "ActiveRecordingCreated", 15, TimeUnit.SECONDS)
                                        .get();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            } finally {
                                latch.countDown();
                            }
                        });

        Thread.sleep(5000);

        HttpResponse<Buffer> resp =
                webClient
                        .extensions()
                        .post("/api/v3/graphql", query.toBuffer(), REQUEST_TIMEOUT_SECONDS);
        MatcherAssert.assertThat(
                resp.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));

        latch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        System.out.println("+++Restart Resp: " + resp.bodyAsString());
        JsonObject notification = f.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return notification.getJsonObject("message").getJsonObject("recording");
    }

    /*     private JsonObject restartRecording(boolean restart) throws Exception {
           CountDownLatch latch = new CountDownLatch(1);

           JsonObject query = new JsonObject();
           query.put(
                   "query",
                   String.format(
                           "query { targetNodes(filter: { annotations: [\"REALM = Custom Targets\"] })"
                                   + " { name target { doStartRecording ( recording: { name: \"test\","
                                   + " template: \"Profiling\", templateType: \"TARGET\", replace: %b"
                                   + " }) { name state } } } }",
                           restart));
           Future<JsonObject> f =
                   worker.submit(
                           () -> {
                               try {
                                   return expectNotification(
                                                   "ActiveRecordingCreated", 15, TimeUnit.SECONDS)
                                           .get();
                               } catch (Exception e) {
                                   throw new RuntimeException(e);
                               } finally {
                                   latch.countDown();
                               }
                           });

           Thread.sleep(5000);

           HttpResponse<Buffer> resp =
                   webClient
                           .extensions()
                           .post("/api/v3/graphql", query.toBuffer(), REQUEST_TIMEOUT_SECONDS);
           MatcherAssert.assertThat(
                   resp.statusCode(),
                   Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));

           latch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

           JsonObject notification = f.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
           return notification.getJsonObject("message").getJsonObject("recording");
       }
    */
    private JsonObject restartRecordingWithError(String replace) throws Exception {
        JsonObject query = new JsonObject();

        query.put(
                "query",
                String.format(
                        "query { targetNodes(filter: { annotations: [\"REALM = Custom Targets\"] })"
                            + " { name target { doStartRecording ( recording: { name: \"test\","
                            + " template: \"Profiling\", templateType: \"TARGET\", replace: \"%s\""
                            + " }) { name state } } } }",
                        replace));
        Thread.sleep(5000);
        HttpResponse<Buffer> resp =
                webClient
                        .extensions()
                        .post("/api/v3/graphql", query.toBuffer(), REQUEST_TIMEOUT_SECONDS);
        MatcherAssert.assertThat(
                resp.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)));

        JsonObject response = resp.bodyAsJsonObject();
        System.out.println("+++GraphQL Response: " + response.encodePrettily());

        JsonArray errors = response.getJsonArray("errors");
        return errors.getJsonObject(0);
    }

    /* private JsonObject restartRecordingWithError(boolean restart) throws Exception {
        JsonObject query = new JsonObject();
        query.put(
                "query",
                String.format(
                        "query { targetNodes(filter: { annotations: [\"REALM = Custom Targets\"] })"
                                + " { name target { doStartRecording ( recording: { name: \"test\","
                                + " template: \"Profiling\", templateType: \"TARGET\", replace: %b"
                                + " }) { name state } } } }",
                        restart));
        Thread.sleep(5000);
        HttpResponse<Buffer> resp =
                webClient
                        .extensions()
                        .post("/api/v3/graphql", query.toBuffer(), REQUEST_TIMEOUT_SECONDS);
        MatcherAssert.assertThat(
                resp.statusCode(),
                Matchers.both(Matchers.greaterThanOrEqualTo(400)).and(Matchers.lessThan(600)));

        JsonObject response = resp.bodyAsJsonObject();
        JsonArray errors = response.getJsonArray("errors");
        return errors.getJsonObject(0);
    } */
}
