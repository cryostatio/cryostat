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
package io.cryostat.discovery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.cryostat.AbstractTransactionalTestBase;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectReference;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.discovery.v1.Endpoint;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointConditions;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointPort;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.MockitoConfig;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(KubeEndpointSlicesDiscoveryTest.TestProfile.class)
class KubeEndpointSlicesDiscoveryTest extends AbstractTransactionalTestBase {

    public static class TestProfile implements io.quarkus.test.junit.QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "cryostat.discovery.kubernetes.enabled", "true",
                    "cryostat.discovery.kubernetes.port-numbers", "9091",
                    "cryostat.discovery.kubernetes.port-names", "jfr-jmx,other-port");
        }
    }

    @InjectMock
    @MockitoConfig(convertScopes = true)
    KubernetesClient client;

    @InjectMock
    @MockitoConfig(convertScopes = true)
    EventBus bus;

    @Inject KubeEndpointSlicesDiscovery discovery;

    @Test
    void testGetTargetTuplesFromReturnsEmptyListWhenPortsAreNull() {
        EndpointSlice slice = mock(EndpointSlice.class);
        when(slice.getAddressType()).thenReturn("ipv4");
        when(slice.getPorts()).thenReturn(null);

        List<?> result = discovery.getTargetTuplesFrom(slice);

        assertTrue(result.isEmpty());
    }

    @Test
    void testGetTargetTuplesFromReturnsEmptyListWhenEndpointsAreNull() {
        EndpointSlice slice = mock(EndpointSlice.class);
        when(slice.getAddressType()).thenReturn("ipv4");
        EndpointPort port = mock(EndpointPort.class);
        lenient().when(port.getName()).thenReturn("jfr-jmx");
        lenient().when(port.getPort()).thenReturn(9091);
        when(slice.getPorts()).thenReturn(List.of(port));
        when(slice.getEndpoints()).thenReturn(null);

        List<?> result = discovery.getTargetTuplesFrom(slice);

        assertTrue(result.isEmpty());
    }

    @Test
    void testGetTargetTuplesFromSkipsEndpointsWithoutTargetRef() {
        EndpointSlice slice = mock(EndpointSlice.class);
        when(slice.getAddressType()).thenReturn("ipv4");

        EndpointPort port = mock(EndpointPort.class);
        lenient().when(port.getName()).thenReturn("jfr-jmx");
        lenient().when(port.getPort()).thenReturn(9091);
        when(slice.getPorts()).thenReturn(List.of(port));

        Endpoint endpoint = mock(Endpoint.class);
        when(endpoint.getAddresses()).thenReturn(List.of("192.168.1.100"));
        when(endpoint.getTargetRef()).thenReturn(null);

        when(slice.getEndpoints()).thenReturn(List.of(endpoint));

        List<?> result = discovery.getTargetTuplesFrom(slice);

        assertTrue(result.isEmpty());
    }

    @Test
    void testGetTargetTuplesFromSkipsEndpointsWithEmptyAddresses() {
        EndpointSlice slice = mock(EndpointSlice.class);
        when(slice.getAddressType()).thenReturn("ipv4");

        EndpointPort port = mock(EndpointPort.class);
        lenient().when(port.getName()).thenReturn("jfr-jmx");
        lenient().when(port.getPort()).thenReturn(9091);
        when(slice.getPorts()).thenReturn(List.of(port));

        Endpoint endpoint = mock(Endpoint.class);
        when(endpoint.getAddresses()).thenReturn(List.of());

        ObjectReference targetRef = mock(ObjectReference.class);
        lenient().when(targetRef.getNamespace()).thenReturn("test-namespace");
        lenient().when(targetRef.getName()).thenReturn("test-pod");
        lenient().when(targetRef.getKind()).thenReturn("Pod");
        lenient().when(endpoint.getTargetRef()).thenReturn(targetRef);

        when(slice.getEndpoints()).thenReturn(List.of(endpoint));

        List<?> result = discovery.getTargetTuplesFrom(slice);

        assertTrue(result.isEmpty());
    }

    @Test
    void testGetTargetTuplesFromReturnsValidTargetTuplesWithSingleEndpoint() {
        EndpointSlice slice = mock(EndpointSlice.class);
        when(slice.getAddressType()).thenReturn("ipv4");

        EndpointPort port = mock(EndpointPort.class);
        when(port.getName()).thenReturn("jfr-jmx");
        when(port.getPort()).thenReturn(9091);
        when(slice.getPorts()).thenReturn(List.of(port));

        Endpoint endpoint = mock(Endpoint.class);
        when(endpoint.getAddresses()).thenReturn(List.of("192.168.1.100"));

        ObjectReference targetRef = mock(ObjectReference.class);
        when(targetRef.getNamespace()).thenReturn("test-namespace");
        when(targetRef.getName()).thenReturn("test-pod");
        when(targetRef.getKind()).thenReturn("Pod");
        when(endpoint.getTargetRef()).thenReturn(targetRef);

        EndpointConditions conditions = mock(EndpointConditions.class);
        when(conditions.getReady()).thenReturn(true);
        when(conditions.getServing()).thenReturn(true);
        when(conditions.getTerminating()).thenReturn(false);
        when(endpoint.getConditions()).thenReturn(conditions);

        when(slice.getEndpoints()).thenReturn(List.of(endpoint));

        Pod pod = mock(Pod.class);
        ObjectMeta podMeta = mock(ObjectMeta.class);
        when(podMeta.getLabels()).thenReturn(Map.of("app", "test-app"));
        when(pod.getMetadata()).thenReturn(podMeta);

        MixedOperation podOp = mock(MixedOperation.class);
        NonNamespaceOperation nsOp = mock(NonNamespaceOperation.class);
        PodResource podResource = mock(PodResource.class);
        when(client.pods()).thenReturn(podOp);
        when(podOp.inNamespace("test-namespace")).thenReturn(nsOp);
        when(nsOp.withName("test-pod")).thenReturn(podResource);
        when(podResource.get()).thenReturn(pod);

        var result = discovery.getTargetTuplesFrom(slice);

        assertNotNull(result);
        assertEquals(
                1,
                result.size(),
                "Should return exactly one TargetTuple for one endpoint with one compatible port");
    }

    @Test
    void testGetTargetTuplesFromWithMultipleEndpointsAndPorts() {
        EndpointSlice slice = mock(EndpointSlice.class);
        when(slice.getAddressType()).thenReturn("ipv4");

        EndpointPort port1 = mock(EndpointPort.class);
        when(port1.getName()).thenReturn("jfr-jmx");
        when(port1.getPort()).thenReturn(9091);

        EndpointPort port2 = mock(EndpointPort.class);
        when(port2.getName()).thenReturn("other-port");
        when(port2.getPort()).thenReturn(8080);

        when(slice.getPorts()).thenReturn(List.of(port1, port2));

        Endpoint endpoint1 = mock(Endpoint.class);
        when(endpoint1.getAddresses()).thenReturn(List.of("192.168.1.100"));
        ObjectReference ref1 = mock(ObjectReference.class);
        when(ref1.getNamespace()).thenReturn("test-namespace");
        when(ref1.getName()).thenReturn("pod-1");
        when(ref1.getKind()).thenReturn("Pod");
        when(endpoint1.getTargetRef()).thenReturn(ref1);
        EndpointConditions cond1 = mock(EndpointConditions.class);
        when(endpoint1.getConditions()).thenReturn(cond1);

        Endpoint endpoint2 = mock(Endpoint.class);
        when(endpoint2.getAddresses()).thenReturn(List.of("192.168.1.101"));
        ObjectReference ref2 = mock(ObjectReference.class);
        when(ref2.getNamespace()).thenReturn("test-namespace");
        when(ref2.getName()).thenReturn("pod-2");
        when(ref2.getKind()).thenReturn("Pod");
        when(endpoint2.getTargetRef()).thenReturn(ref2);
        EndpointConditions cond2 = mock(EndpointConditions.class);
        when(endpoint2.getConditions()).thenReturn(cond2);

        when(slice.getEndpoints()).thenReturn(List.of(endpoint1, endpoint2));

        Pod pod1 = mock(Pod.class);
        ObjectMeta meta1 = mock(ObjectMeta.class);
        when(meta1.getLabels()).thenReturn(new HashMap<>());
        when(pod1.getMetadata()).thenReturn(meta1);

        Pod pod2 = mock(Pod.class);
        ObjectMeta meta2 = mock(ObjectMeta.class);
        when(meta2.getLabels()).thenReturn(new HashMap<>());
        when(pod2.getMetadata()).thenReturn(meta2);

        MixedOperation podOp = mock(MixedOperation.class);
        NonNamespaceOperation nsOp = mock(NonNamespaceOperation.class);
        PodResource podResource1 = mock(PodResource.class);
        PodResource podResource2 = mock(PodResource.class);

        when(client.pods()).thenReturn(podOp);
        when(podOp.inNamespace("test-namespace")).thenReturn(nsOp);
        when(nsOp.withName("pod-1")).thenReturn(podResource1);
        when(nsOp.withName("pod-2")).thenReturn(podResource2);
        when(podResource1.get()).thenReturn(pod1);
        when(podResource2.get()).thenReturn(pod2);

        var result = discovery.getTargetTuplesFrom(slice);

        assertNotNull(result);
        assertEquals(4, result.size(), "Should return 4 tuples (2 endpoints × 2 ports)");
    }

    @Test
    void testBuildOwnershipHierarchyWithPodOnly() {
        Pod pod = mock(Pod.class);
        ObjectMeta podMeta = mock(ObjectMeta.class);
        when(podMeta.getOwnerReferences()).thenReturn(List.of());
        when(podMeta.getNamespace()).thenReturn("test-namespace");
        when(podMeta.getLabels()).thenReturn(Map.of("app", "test-app"));
        when(pod.getMetadata()).thenReturn(podMeta);

        DiscoveryNode podNode = new DiscoveryNode();
        podNode.name = "test-pod";
        podNode.nodeType = "Pod";
        podNode.labels = new HashMap<>();
        podNode.labels.put(
                KubeEndpointSlicesDiscovery.DISCOVERY_NAMESPACE_LABEL_KEY, "test-namespace");

        DiscoveryNode root = discovery.buildOwnershipHierarchy(pod, podNode);

        assertNotNull(root);
        assertEquals(podNode, root, "Pod should be the root since it has no owners");
        assertNull(podNode.parent, "Pod should have no parent");
    }

    @Test
    void testBuildOwnershipHierarchyWithPodToReplicaSet() {
        Pod pod = mock(Pod.class);
        ObjectMeta podMeta = mock(ObjectMeta.class);
        OwnerReference rsOwner = mock(OwnerReference.class);
        when(rsOwner.getKind()).thenReturn("ReplicaSet");
        when(rsOwner.getName()).thenReturn("test-rs");
        when(podMeta.getOwnerReferences()).thenReturn(List.of(rsOwner));
        when(podMeta.getNamespace()).thenReturn("test-namespace");
        when(podMeta.getLabels()).thenReturn(Map.of("app", "test-app"));
        when(pod.getMetadata()).thenReturn(podMeta);

        ReplicaSet rs = mock(ReplicaSet.class);
        ObjectMeta rsMeta = mock(ObjectMeta.class);
        when(rsMeta.getOwnerReferences()).thenReturn(List.of());
        when(rsMeta.getNamespace()).thenReturn("test-namespace");
        when(rsMeta.getLabels()).thenReturn(Map.of("app", "test-app"));
        when(rs.getMetadata()).thenReturn(rsMeta);

        AppsAPIGroupDSL appsApi = mock(AppsAPIGroupDSL.class);
        MixedOperation rsOp = mock(MixedOperation.class);
        NonNamespaceOperation rsNsOp = mock(NonNamespaceOperation.class);
        RollableScalableResource rsResource = mock(RollableScalableResource.class);

        when(client.apps()).thenReturn(appsApi);
        when(appsApi.replicaSets()).thenReturn(rsOp);
        when(rsOp.inNamespace("test-namespace")).thenReturn(rsNsOp);
        when(rsNsOp.withName("test-rs")).thenReturn(rsResource);
        when(rsResource.get()).thenReturn(rs);

        DiscoveryNode podNode = new DiscoveryNode();
        podNode.name = "test-pod";
        podNode.nodeType = "Pod";
        podNode.labels = new HashMap<>();
        podNode.labels.put(
                KubeEndpointSlicesDiscovery.DISCOVERY_NAMESPACE_LABEL_KEY, "test-namespace");

        DiscoveryNode root = discovery.buildOwnershipHierarchy(pod, podNode);

        assertNotNull(root, "Should return a non-null root node");
        assertEquals("test-rs", root.name, "Root should be the ReplicaSet");
        assertEquals("ReplicaSet", root.nodeType, "Root should be of type ReplicaSet");
        assertNotNull(podNode.parent, "Pod should have a parent");
        assertEquals(root, podNode.parent, "Pod's parent should be the ReplicaSet");
    }

    @Test
    void testBuildOwnershipHierarchyWithFullChain() {
        Pod pod = mock(Pod.class);
        ObjectMeta podMeta = mock(ObjectMeta.class);
        OwnerReference rsOwner = mock(OwnerReference.class);
        when(rsOwner.getKind()).thenReturn("ReplicaSet");
        when(rsOwner.getName()).thenReturn("test-rs-abc123");
        when(podMeta.getOwnerReferences()).thenReturn(List.of(rsOwner));
        when(podMeta.getNamespace()).thenReturn("test-namespace");
        when(podMeta.getLabels()).thenReturn(Map.of("app", "test-app"));
        when(pod.getMetadata()).thenReturn(podMeta);

        ReplicaSet rs = mock(ReplicaSet.class);
        ObjectMeta rsMeta = mock(ObjectMeta.class);
        OwnerReference deployOwner = mock(OwnerReference.class);
        when(deployOwner.getKind()).thenReturn("Deployment");
        when(deployOwner.getName()).thenReturn("test-deployment");
        when(rsMeta.getOwnerReferences()).thenReturn(List.of(deployOwner));
        when(rsMeta.getNamespace()).thenReturn("test-namespace");
        when(rsMeta.getLabels()).thenReturn(Map.of("app", "test-app"));
        when(rs.getMetadata()).thenReturn(rsMeta);

        Deployment deployment = mock(Deployment.class);
        ObjectMeta deployMeta = mock(ObjectMeta.class);
        when(deployMeta.getOwnerReferences()).thenReturn(List.of());
        when(deployMeta.getNamespace()).thenReturn("test-namespace");
        when(deployMeta.getLabels()).thenReturn(Map.of("app", "test-app", "version", "v1"));
        when(deployment.getMetadata()).thenReturn(deployMeta);

        AppsAPIGroupDSL appsApi = mock(AppsAPIGroupDSL.class);

        MixedOperation rsOp = mock(MixedOperation.class);
        NonNamespaceOperation rsNsOp = mock(NonNamespaceOperation.class);
        RollableScalableResource rsResource = mock(RollableScalableResource.class);

        MixedOperation deployOp = mock(MixedOperation.class);
        NonNamespaceOperation deployNsOp = mock(NonNamespaceOperation.class);
        RollableScalableResource deployResource = mock(RollableScalableResource.class);

        when(client.apps()).thenReturn(appsApi);
        when(appsApi.replicaSets()).thenReturn(rsOp);
        when(rsOp.inNamespace("test-namespace")).thenReturn(rsNsOp);
        when(rsNsOp.withName("test-rs-abc123")).thenReturn(rsResource);
        when(rsResource.get()).thenReturn(rs);

        when(appsApi.deployments()).thenReturn(deployOp);
        when(deployOp.inNamespace("test-namespace")).thenReturn(deployNsOp);
        when(deployNsOp.withName("test-deployment")).thenReturn(deployResource);
        when(deployResource.get()).thenReturn(deployment);

        DiscoveryNode podNode = new DiscoveryNode();
        podNode.name = "test-pod";
        podNode.nodeType = "Pod";
        podNode.labels = new HashMap<>();
        podNode.labels.put(
                KubeEndpointSlicesDiscovery.DISCOVERY_NAMESPACE_LABEL_KEY, "test-namespace");

        DiscoveryNode root = discovery.buildOwnershipHierarchy(pod, podNode);

        assertNotNull(root, "Should return a non-null root node");
        assertEquals("test-deployment", root.name, "Root should be the Deployment");
        assertEquals("Deployment", root.nodeType, "Root should be of type Deployment");

        assertNotNull(podNode.parent, "Pod should have a parent");
        assertEquals("test-rs-abc123", podNode.parent.name, "Pod's parent should be ReplicaSet");
        assertNotNull(podNode.parent.parent, "ReplicaSet should have a parent");
        assertEquals(root, podNode.parent.parent, "ReplicaSet's parent should be Deployment");
    }

    @Test
    void testKubeDiscoveryNodeTypeFromKubernetesKindReturnsCorrectType() {
        assertEquals(
                KubeEndpointSlicesDiscovery.KubeDiscoveryNodeType.POD,
                KubeEndpointSlicesDiscovery.KubeDiscoveryNodeType.fromKubernetesKind("Pod"));
        assertEquals(
                KubeEndpointSlicesDiscovery.KubeDiscoveryNodeType.DEPLOYMENT,
                KubeEndpointSlicesDiscovery.KubeDiscoveryNodeType.fromKubernetesKind("Deployment"));
        assertEquals(
                KubeEndpointSlicesDiscovery.KubeDiscoveryNodeType.STATEFULSET,
                KubeEndpointSlicesDiscovery.KubeDiscoveryNodeType.fromKubernetesKind(
                        "StatefulSet"));
        assertEquals(
                KubeEndpointSlicesDiscovery.KubeDiscoveryNodeType.REPLICASET,
                KubeEndpointSlicesDiscovery.KubeDiscoveryNodeType.fromKubernetesKind("ReplicaSet"));
    }

    @Test
    void testKubeDiscoveryNodeTypeFromKubernetesKindReturnsNullForUnknown() {
        assertNull(
                KubeEndpointSlicesDiscovery.KubeDiscoveryNodeType.fromKubernetesKind(
                        "UnknownKind"));
    }

    @Test
    void testKubeDiscoveryNodeTypeFromKubernetesKindReturnsNullForNull() {
        assertNull(KubeEndpointSlicesDiscovery.KubeDiscoveryNodeType.fromKubernetesKind(null));
    }

    @Test
    void testKubeDiscoveryNodeTypeGetKindReturnsKubernetesKind() {
        assertEquals("Pod", KubeEndpointSlicesDiscovery.KubeDiscoveryNodeType.POD.getKind());
        assertEquals(
                "Deployment",
                KubeEndpointSlicesDiscovery.KubeDiscoveryNodeType.DEPLOYMENT.getKind());
    }

    @Test
    void testKubeDiscoveryNodeTypeToStringReturnsKind() {
        assertEquals("Pod", KubeEndpointSlicesDiscovery.KubeDiscoveryNodeType.POD.toString());
        assertEquals(
                "Namespace",
                KubeEndpointSlicesDiscovery.KubeDiscoveryNodeType.NAMESPACE.toString());
    }

    @Test
    void testGetOwnershipLineageWithStringParametersForPod() {
        Pod pod = mock(Pod.class);
        ObjectMeta podMeta = mock(ObjectMeta.class);
        when(podMeta.getOwnerReferences()).thenReturn(List.of());
        when(podMeta.getNamespace()).thenReturn("test-namespace");
        when(podMeta.getLabels()).thenReturn(Map.of("app", "test-app"));
        when(pod.getMetadata()).thenReturn(podMeta);

        MixedOperation podOp = mock(MixedOperation.class);
        NonNamespaceOperation nsOp = mock(NonNamespaceOperation.class);
        PodResource podResource = mock(PodResource.class);
        when(client.pods()).thenReturn(podOp);
        when(podOp.inNamespace("test-namespace")).thenReturn(nsOp);
        when(nsOp.withName("test-pod")).thenReturn(podResource);
        when(podResource.get()).thenReturn(pod);

        DiscoveryNode result = discovery.getOwnershipLineage("test-namespace", "test-pod", "Pod");

        assertNotNull(result);
        assertEquals("test-pod", result.name);
        assertEquals("Pod", result.nodeType);
        assertEquals(
                "test-namespace",
                result.labels.get(KubeEndpointSlicesDiscovery.DISCOVERY_NAMESPACE_LABEL_KEY));
        assertNull(result.parent);
    }

    @Test
    void testGetOwnershipLineageWithStringParametersForPodWithReplicaSetOwner() {
        Pod pod = mock(Pod.class);
        ObjectMeta podMeta = mock(ObjectMeta.class);
        OwnerReference rsOwner = mock(OwnerReference.class);
        when(rsOwner.getKind()).thenReturn("ReplicaSet");
        when(rsOwner.getName()).thenReturn("test-rs");
        when(podMeta.getOwnerReferences()).thenReturn(List.of(rsOwner));
        when(podMeta.getNamespace()).thenReturn("test-namespace");
        when(podMeta.getLabels()).thenReturn(Map.of("app", "test-app"));
        when(pod.getMetadata()).thenReturn(podMeta);

        ReplicaSet rs = mock(ReplicaSet.class);
        ObjectMeta rsMeta = mock(ObjectMeta.class);
        when(rsMeta.getOwnerReferences()).thenReturn(List.of());
        when(rsMeta.getNamespace()).thenReturn("test-namespace");
        when(rsMeta.getLabels()).thenReturn(Map.of("app", "test-app"));
        when(rs.getMetadata()).thenReturn(rsMeta);

        MixedOperation podOp = mock(MixedOperation.class);
        NonNamespaceOperation podNsOp = mock(NonNamespaceOperation.class);
        PodResource podResource = mock(PodResource.class);
        when(client.pods()).thenReturn(podOp);
        when(podOp.inNamespace("test-namespace")).thenReturn(podNsOp);
        when(podNsOp.withName("test-pod")).thenReturn(podResource);
        when(podResource.get()).thenReturn(pod);

        AppsAPIGroupDSL appsApi = mock(AppsAPIGroupDSL.class);
        MixedOperation rsOp = mock(MixedOperation.class);
        NonNamespaceOperation rsNsOp = mock(NonNamespaceOperation.class);
        RollableScalableResource rsResource = mock(RollableScalableResource.class);
        when(client.apps()).thenReturn(appsApi);
        when(appsApi.replicaSets()).thenReturn(rsOp);
        when(rsOp.inNamespace("test-namespace")).thenReturn(rsNsOp);
        when(rsNsOp.withName("test-rs")).thenReturn(rsResource);
        when(rsResource.get()).thenReturn(rs);

        DiscoveryNode root = discovery.getOwnershipLineage("test-namespace", "test-pod", "Pod");

        assertNotNull(root);
        assertEquals("test-rs", root.name);
        assertEquals("ReplicaSet", root.nodeType);
        assertNotNull(root.children);
        assertEquals(1, root.children.size());
        DiscoveryNode podNode = root.children.get(0);
        assertEquals("test-pod", podNode.name);
        assertEquals("Pod", podNode.nodeType);
        assertEquals(root, podNode.parent);
    }

    @Test
    void testGetOwnershipLineageWithStringParametersForEndpointSlice() {
        EndpointSlice slice = mock(EndpointSlice.class);
        ObjectMeta sliceMeta = mock(ObjectMeta.class);
        when(sliceMeta.getOwnerReferences()).thenReturn(List.of());
        when(sliceMeta.getNamespace()).thenReturn("test-namespace");
        when(sliceMeta.getLabels()).thenReturn(Map.of());
        when(slice.getMetadata()).thenReturn(sliceMeta);

        io.fabric8.kubernetes.client.dsl.DiscoveryAPIGroupDSL discoveryApi =
                mock(io.fabric8.kubernetes.client.dsl.DiscoveryAPIGroupDSL.class);
        io.fabric8.kubernetes.client.dsl.V1DiscoveryAPIGroupDSL v1Api =
                mock(io.fabric8.kubernetes.client.dsl.V1DiscoveryAPIGroupDSL.class);
        MixedOperation sliceOp = mock(MixedOperation.class);
        NonNamespaceOperation sliceNsOp = mock(NonNamespaceOperation.class);
        io.fabric8.kubernetes.client.dsl.Resource sliceResource =
                mock(io.fabric8.kubernetes.client.dsl.Resource.class);

        when(client.discovery()).thenReturn(discoveryApi);
        when(discoveryApi.v1()).thenReturn(v1Api);
        when(v1Api.endpointSlices()).thenReturn(sliceOp);
        when(sliceOp.inNamespace("test-namespace")).thenReturn(sliceNsOp);
        when(sliceNsOp.withName("test-slice")).thenReturn(sliceResource);
        when(sliceResource.get()).thenReturn(slice);

        DiscoveryNode result =
                discovery.getOwnershipLineage("test-namespace", "test-slice", "EndpointSlice");

        assertNotNull(result);
        assertEquals("test-slice", result.name);
        assertEquals("EndpointSlice", result.nodeType);
        assertEquals(
                "test-namespace",
                result.labels.get(KubeEndpointSlicesDiscovery.DISCOVERY_NAMESPACE_LABEL_KEY));
        assertNull(result.parent);
    }

    @Test
    void testGetOwnershipLineageWithStringParametersForUnknownNodeType() {
        DiscoveryNode result =
                discovery.getOwnershipLineage("test-namespace", "test-node", "UnknownType");

        assertNotNull(result);
        assertEquals("test-node", result.name);
        assertEquals("UnknownType", result.nodeType);
        assertEquals(
                "test-namespace",
                result.labels.get(KubeEndpointSlicesDiscovery.DISCOVERY_NAMESPACE_LABEL_KEY));
        assertNull(result.parent);
    }

    @Test
    void testGetOwnershipLineageWithStringParametersForNonExistentResource() {
        MixedOperation podOp = mock(MixedOperation.class);
        NonNamespaceOperation nsOp = mock(NonNamespaceOperation.class);
        PodResource podResource = mock(PodResource.class);
        when(client.pods()).thenReturn(podOp);
        when(podOp.inNamespace("test-namespace")).thenReturn(nsOp);
        when(nsOp.withName("non-existent-pod")).thenReturn(podResource);
        when(podResource.get()).thenReturn(null);

        DiscoveryNode result =
                discovery.getOwnershipLineage("test-namespace", "non-existent-pod", "Pod");

        assertNotNull(result);
        assertEquals("non-existent-pod", result.name);
        assertEquals("Pod", result.nodeType);
        assertEquals(
                "test-namespace",
                result.labels.get(KubeEndpointSlicesDiscovery.DISCOVERY_NAMESPACE_LABEL_KEY));
        assertNull(result.parent);
    }
}
