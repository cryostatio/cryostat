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
import static org.mockito.Mockito.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.cryostat.AbstractTransactionalTestBase;
import io.cryostat.targets.Target;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectReference;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetList;
import io.fabric8.kubernetes.api.model.discovery.v1.Endpoint;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointConditions;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointPort;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSliceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.quarkus.panache.common.Parameters;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.MockitoConfig;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.apache.commons.lang3.tuple.Pair;
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

    @Inject EntityManager entityManager;

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
    @SuppressWarnings("unchecked") // Mockito mock() requires unchecked cast for generic types
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

        MixedOperation<Pod, PodList, PodResource> podOp = mock(MixedOperation.class);
        NonNamespaceOperation<Pod, PodList, PodResource> nsOp = mock(NonNamespaceOperation.class);
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
    @SuppressWarnings("unchecked") // Mockito mock() requires unchecked cast for generic types
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

        MixedOperation<Pod, PodList, PodResource> podOp = mock(MixedOperation.class);
        NonNamespaceOperation<Pod, PodList, PodResource> nsOp = mock(NonNamespaceOperation.class);
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
    @SuppressWarnings("unchecked") // Mockito mock() requires unchecked cast for generic types
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
        MixedOperation<ReplicaSet, ReplicaSetList, RollableScalableResource<ReplicaSet>> rsOp =
                mock(MixedOperation.class);
        NonNamespaceOperation<ReplicaSet, ReplicaSetList, RollableScalableResource<ReplicaSet>>
                rsNsOp = mock(NonNamespaceOperation.class);
        RollableScalableResource<ReplicaSet> rsResource = mock(RollableScalableResource.class);

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
    @SuppressWarnings("unchecked") // Mockito mock() requires unchecked cast for generic types
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

        MixedOperation<ReplicaSet, ReplicaSetList, RollableScalableResource<ReplicaSet>> rsOp =
                mock(MixedOperation.class);
        NonNamespaceOperation<ReplicaSet, ReplicaSetList, RollableScalableResource<ReplicaSet>>
                rsNsOp = mock(NonNamespaceOperation.class);
        RollableScalableResource<ReplicaSet> rsResource = mock(RollableScalableResource.class);

        MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployOp =
                mock(MixedOperation.class);
        NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>>
                deployNsOp = mock(NonNamespaceOperation.class);
        RollableScalableResource<Deployment> deployResource = mock(RollableScalableResource.class);

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
    @SuppressWarnings("unchecked") // Mockito mock() requires unchecked cast for generic types
    void testGetOwnershipLineageWithStringParametersForPod() {
        Pod pod = mock(Pod.class);
        ObjectMeta podMeta = mock(ObjectMeta.class);
        when(podMeta.getOwnerReferences()).thenReturn(List.of());
        when(podMeta.getNamespace()).thenReturn("test-namespace");
        when(podMeta.getLabels()).thenReturn(Map.of("app", "test-app"));
        when(pod.getMetadata()).thenReturn(podMeta);

        MixedOperation<Pod, PodList, PodResource> podOp = mock(MixedOperation.class);
        NonNamespaceOperation<Pod, PodList, PodResource> nsOp = mock(NonNamespaceOperation.class);
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
    @SuppressWarnings("unchecked") // Mockito mock() requires unchecked cast for generic types
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

        MixedOperation<Pod, PodList, PodResource> podOp = mock(MixedOperation.class);
        NonNamespaceOperation<Pod, PodList, PodResource> podNsOp =
                mock(NonNamespaceOperation.class);
        PodResource podResource = mock(PodResource.class);
        when(client.pods()).thenReturn(podOp);
        when(podOp.inNamespace("test-namespace")).thenReturn(podNsOp);
        when(podNsOp.withName("test-pod")).thenReturn(podResource);
        when(podResource.get()).thenReturn(pod);

        AppsAPIGroupDSL appsApi = mock(AppsAPIGroupDSL.class);
        MixedOperation<ReplicaSet, ReplicaSetList, RollableScalableResource<ReplicaSet>> rsOp =
                mock(MixedOperation.class);
        NonNamespaceOperation<ReplicaSet, ReplicaSetList, RollableScalableResource<ReplicaSet>>
                rsNsOp = mock(NonNamespaceOperation.class);
        RollableScalableResource<ReplicaSet> rsResource = mock(RollableScalableResource.class);
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
    @SuppressWarnings("unchecked") // Mockito mock() requires unchecked cast for generic types
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
        MixedOperation<
                        EndpointSlice,
                        EndpointSliceList,
                        io.fabric8.kubernetes.client.dsl.Resource<EndpointSlice>>
                sliceOp = mock(MixedOperation.class);
        NonNamespaceOperation<
                        EndpointSlice,
                        EndpointSliceList,
                        io.fabric8.kubernetes.client.dsl.Resource<EndpointSlice>>
                sliceNsOp = mock(NonNamespaceOperation.class);
        io.fabric8.kubernetes.client.dsl.Resource<EndpointSlice> sliceResource =
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
    @SuppressWarnings("unchecked") // Mockito mock() requires unchecked cast for generic types
    void testGetOwnershipLineageWithStringParametersForNonExistentResource() {
        MixedOperation<Pod, PodList, PodResource> podOp = mock(MixedOperation.class);
        NonNamespaceOperation<Pod, PodList, PodResource> nsOp = mock(NonNamespaceOperation.class);
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

    @Test
    @Transactional
    @SuppressWarnings("unchecked")
    void testQueryForNodeReadOnlyDoesNotPersist() throws Exception {
        Pod pod = mock(Pod.class);
        ObjectMeta podMeta = mock(ObjectMeta.class);
        when(podMeta.getOwnerReferences()).thenReturn(List.of());
        when(podMeta.getNamespace()).thenReturn("test-namespace");
        when(podMeta.getLabels()).thenReturn(Map.of("app", "test-app"));
        when(pod.getMetadata()).thenReturn(podMeta);

        MixedOperation<Pod, PodList, PodResource> podOp = mock(MixedOperation.class);
        NonNamespaceOperation<Pod, PodList, PodResource> nsOp = mock(NonNamespaceOperation.class);
        PodResource podResource = mock(PodResource.class);
        when(client.pods()).thenReturn(podOp);
        when(podOp.inNamespace("test-namespace")).thenReturn(nsOp);
        when(nsOp.withName("test-pod")).thenReturn(podResource);
        when(podResource.get()).thenReturn(pod);

        long nodeCountBefore = DiscoveryNode.count();

        var result = discovery.queryForNodeReadOnly("test-namespace", "test-pod", "Pod");

        long nodeCountAfter = DiscoveryNode.count();

        assertNotNull(result, "Result should not be null");
        assertEquals(
                nodeCountBefore,
                nodeCountAfter,
                "queryForNodeReadOnly should not create any database records");

        @SuppressWarnings("unchecked")
        Pair<?, ?> resultPair = (Pair<?, ?>) result;
        assertNotNull(resultPair.getLeft(), "Kubernetes object should be returned");
        assertNull(resultPair.getRight(), "DiscoveryNode should be null since nothing in DB");
    }

    @Test
    @Transactional
    void testFindExistingNodeIdWithJsonbQuery() throws Exception {
        // Create a node with labels including the namespace label
        DiscoveryNode node = new DiscoveryNode();
        node.name = "test-pod";
        node.nodeType = "Pod";
        node.labels = new HashMap<>();
        node.labels.put("app", "test-app");
        node.labels.put(
                KubeEndpointSlicesDiscovery.DISCOVERY_NAMESPACE_LABEL_KEY, "test-namespace");
        node.children = new ArrayList<>();
        node.persist();

        // Flush to ensure the node is in the database
        entityManager.flush();
        entityManager.clear();

        // Test the SQL query directly using the same query string as the implementation
        String query =
                "SELECT n.id FROM DiscoveryNode n"
                        + " WHERE n.name = :name AND"
                        + " n.nodeType = :nodeType AND"
                        + " n.labels->>'discovery.cryostat.io/namespace' = :namespace";

        Long foundId =
                ((Number)
                                entityManager
                                        .createNativeQuery(query)
                                        .setParameter("name", "test-pod")
                                        .setParameter("nodeType", "Pod")
                                        .setParameter("namespace", "test-namespace")
                                        .getResultStream()
                                        .findFirst()
                                        .orElse(null))
                        .longValue();

        assertNotNull(foundId, "Should find the node using JSONB operator");
        assertEquals(node.id, foundId, "Found ID should match the created node's ID");
    }

    @Test
    @Transactional
    void testFindExistingNodeIdReturnsNullWhenNotFound() throws Exception {
        // Test that the query returns null when no matching node exists
        String query =
                "SELECT n.id FROM DiscoveryNode n"
                        + " WHERE n.name = :name AND"
                        + " n.nodeType = :nodeType AND"
                        + " n.labels->>'discovery.cryostat.io/namespace' = :namespace";

        Object result =
                entityManager
                        .createNativeQuery(query)
                        .setParameter("name", "nonexistent-pod")
                        .setParameter("nodeType", "Pod")
                        .setParameter("namespace", "nonexistent-namespace")
                        .getResultStream()
                        .findFirst()
                        .orElse(null);

        assertNull(result, "Should return null when node doesn't exist");
    }

    @Test
    @Transactional
    void testFindExistingNodeIdWithDuplicates() throws Exception {
        // Create multiple nodes with the same name/type/namespace to test duplicate handling
        DiscoveryNode node1 = new DiscoveryNode();
        node1.name = "duplicate-pod";
        node1.nodeType = "Pod";
        node1.labels = new HashMap<>();
        node1.labels.put(
                KubeEndpointSlicesDiscovery.DISCOVERY_NAMESPACE_LABEL_KEY, "test-namespace");
        node1.children = new ArrayList<>();
        node1.persist();

        DiscoveryNode node2 = new DiscoveryNode();
        node2.name = "duplicate-pod";
        node2.nodeType = "Pod";
        node2.labels = new HashMap<>();
        node2.labels.put(
                KubeEndpointSlicesDiscovery.DISCOVERY_NAMESPACE_LABEL_KEY, "test-namespace");
        node2.children = new ArrayList<>();
        node2.persist();

        entityManager.flush();
        entityManager.clear();

        // Test that getResultStream().findFirst() returns one of the duplicates
        String query =
                "SELECT n.id FROM DiscoveryNode n"
                        + " WHERE n.name = :name AND"
                        + " n.nodeType = :nodeType AND"
                        + " n.labels->>'discovery.cryostat.io/namespace' = :namespace";

        Long foundId =
                ((Number)
                                entityManager
                                        .createNativeQuery(query)
                                        .setParameter("name", "duplicate-pod")
                                        .setParameter("nodeType", "Pod")
                                        .setParameter("namespace", "test-namespace")
                                        .getResultStream()
                                        .findFirst()
                                        .orElse(null))
                        .longValue();

        assertNotNull(foundId, "Should find one of the duplicate nodes");
        assertTrue(
                foundId.equals(node1.id) || foundId.equals(node2.id),
                "Found ID should match one of the duplicate nodes");
    }

    @Test
    @Transactional
    void testFindOrphanedNodesDetectsOrphans() throws Exception {
        DiscoveryNode nsNode = new DiscoveryNode();
        nsNode.name = "test-namespace";
        nsNode.nodeType = "Namespace";
        nsNode.labels = new HashMap<>();
        nsNode.labels.put(
                KubeEndpointSlicesDiscovery.DISCOVERY_NAMESPACE_LABEL_KEY, "test-namespace");
        nsNode.labels.put("realm", "KubernetesApi");
        nsNode.children = new ArrayList<>();
        nsNode.persist();

        DiscoveryNode orphanPod1 = new DiscoveryNode();
        orphanPod1.name = "orphan-pod-1";
        orphanPod1.nodeType = "Pod";
        orphanPod1.labels = new HashMap<>();
        orphanPod1.labels.put(
                KubeEndpointSlicesDiscovery.DISCOVERY_NAMESPACE_LABEL_KEY, "test-namespace");
        orphanPod1.labels.put("realm", "KubernetesApi");
        orphanPod1.children = new ArrayList<>();
        orphanPod1.parent = nsNode;
        orphanPod1.persist();

        DiscoveryNode orphanPod2 = new DiscoveryNode();
        orphanPod2.name = "orphan-pod-2";
        orphanPod2.nodeType = "Pod";
        orphanPod2.labels = new HashMap<>();
        orphanPod2.labels.put(
                KubeEndpointSlicesDiscovery.DISCOVERY_NAMESPACE_LABEL_KEY, "test-namespace");
        orphanPod2.labels.put("realm", "KubernetesApi");
        orphanPod2.children = new ArrayList<>();
        orphanPod2.parent = nsNode;
        orphanPod2.persist();

        DiscoveryNode validPod = new DiscoveryNode();
        validPod.name = "valid-pod";
        validPod.nodeType = "Pod";
        validPod.labels = new HashMap<>();
        validPod.labels.put(
                KubeEndpointSlicesDiscovery.DISCOVERY_NAMESPACE_LABEL_KEY, "test-namespace");
        validPod.labels.put("realm", "KubernetesApi");
        validPod.children = new ArrayList<>();
        validPod.parent = nsNode;
        validPod.persist();

        DiscoveryNode endpointNode = new DiscoveryNode();
        endpointNode.name = "valid-endpoint";
        endpointNode.nodeType = "Endpoint";
        endpointNode.labels = new HashMap<>();
        endpointNode.labels.put(
                KubeEndpointSlicesDiscovery.DISCOVERY_NAMESPACE_LABEL_KEY, "test-namespace");
        endpointNode.labels.put("realm", "KubernetesApi");
        endpointNode.children = new ArrayList<>();
        endpointNode.parent = validPod;
        endpointNode.persist();
        validPod.children.add(endpointNode);

        Target target = new Target();
        target.connectUrl = URI.create("service:jmx:rmi:///jndi/rmi://192.168.1.100:9091/jmxrmi");
        target.alias = "valid-target";
        target.discoveryNode = endpointNode;
        target.persist();

        List<DiscoveryNode> orphans = discovery.findOrphanedNodesInNamespace("test-namespace");

        assertNotNull(orphans, "Result should not be null");
        assertEquals(2, orphans.size(), "Should find exactly 2 orphaned nodes");

        List<String> orphanNames = orphans.stream().map(n -> n.name).toList();
        assertTrue(
                orphanNames.contains("orphan-pod-1"),
                "Should include orphan-pod-1 in orphaned nodes");
        assertTrue(
                orphanNames.contains("orphan-pod-2"),
                "Should include orphan-pod-2 in orphaned nodes");
        assertFalse(
                orphanNames.contains("valid-pod"),
                "Should NOT include valid-pod (has children) in orphaned nodes");
        assertFalse(
                orphanNames.contains("valid-endpoint"),
                "Should NOT include valid-endpoint (has Target) in orphaned nodes");
    }

    @Test
    @Transactional
    void testNodesNotCreatedWithoutValidInformerSetup() {
        // This test verifies that nodes are NOT persisted when there's no valid
        // EndpointSlice informer setup. This is the correct behavior - we should only
        // persist nodes that have valid EndpointSlice descendants.

        var event = KubeEndpointSlicesDiscovery.NamespaceQueryEvent.from("test-namespace");
        long nodeCountBefore = DiscoveryNode.count();

        // This will fail internally due to missing informer, but should not crash
        try {
            discovery.handleQueryEvent(event);
        } catch (Exception e) {
            // Expected - informer not set up
        }
        entityManager.flush();
        entityManager.clear();

        long nodeCountAfter = DiscoveryNode.count();

        assertEquals(
                nodeCountBefore,
                nodeCountAfter,
                "Should not create any nodes without valid informer setup");
    }

    @Test
    @Transactional
    @SuppressWarnings("unchecked")
    void testNonJmxPodsDoNotCreateNodes() {
        EndpointSlice slice = mock(EndpointSlice.class);
        ObjectMeta sliceMeta = mock(ObjectMeta.class);
        when(sliceMeta.getNamespace()).thenReturn("test-namespace");
        when(sliceMeta.getName()).thenReturn("non-jmx-slice");
        when(slice.getMetadata()).thenReturn(sliceMeta);
        when(slice.getAddressType()).thenReturn("ipv4");

        EndpointPort port = mock(EndpointPort.class);
        when(port.getName()).thenReturn("http");
        when(port.getPort()).thenReturn(8080);
        when(slice.getPorts()).thenReturn(List.of(port));

        Endpoint endpoint = mock(Endpoint.class);
        when(endpoint.getAddresses()).thenReturn(List.of("192.168.1.200"));

        ObjectReference targetRef = mock(ObjectReference.class);
        when(targetRef.getNamespace()).thenReturn("test-namespace");
        when(targetRef.getName()).thenReturn("non-jmx-pod");
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
        when(podMeta.getOwnerReferences()).thenReturn(List.of());
        when(podMeta.getNamespace()).thenReturn("test-namespace");
        when(podMeta.getLabels()).thenReturn(Map.of("app", "non-jmx-app"));
        when(pod.getMetadata()).thenReturn(podMeta);

        MixedOperation<Pod, PodList, PodResource> podOp = mock(MixedOperation.class);
        NonNamespaceOperation<Pod, PodList, PodResource> nsOp = mock(NonNamespaceOperation.class);
        PodResource podResource = mock(PodResource.class);
        when(client.pods()).thenReturn(podOp);
        when(podOp.inNamespace("test-namespace")).thenReturn(nsOp);
        when(nsOp.withName("non-jmx-pod")).thenReturn(podResource);
        when(podResource.get()).thenReturn(pod);

        long nodeCountBefore = DiscoveryNode.count();
        long targetCountBefore = Target.count();

        discovery.onAdd(slice);
        entityManager.flush();
        entityManager.clear();

        long nodeCountAfter = DiscoveryNode.count();
        long targetCountAfter = Target.count();

        assertEquals(
                nodeCountBefore,
                nodeCountAfter,
                "No DiscoveryNodes should be created for non-JMX Pods");
        assertEquals(
                targetCountBefore,
                targetCountAfter,
                "No Targets should be created for non-JMX Pods");

        long podNodeCount =
                DiscoveryNode.<DiscoveryNode>find(
                                "#DiscoveryNode.byTypeWithName",
                                Parameters.with("nodeType", "Pod").and("name", "non-jmx-pod"))
                        .count();
        assertEquals(0, podNodeCount, "No Pod node should exist for non-JMX pod");
    }

    @Test
    void testEndpointSliceInformerEventsIgnoredDuringShutdown() {
        clearInvocations(bus);
        discovery.setShuttingDown(true);

        try {
            EndpointSlice slice = mock(EndpointSlice.class);

            discovery.onAdd(slice);
            discovery.onUpdate(slice, slice);
            discovery.onDelete(slice, false);

            verifyNoInteractions(bus);
            verifyNoInteractions(slice);
        } finally {
            discovery.setShuttingDown(false);
        }
    }

    @Test
    @Transactional
    void testHandleQueryEventIgnoredDuringShutdown() {
        clearInvocations(bus);
        discovery.setShuttingDown(true);

        try {
            long nodeCountBefore = DiscoveryNode.count();
            long targetCountBefore = Target.count();

            discovery.handleQueryEvent(
                    KubeEndpointSlicesDiscovery.NamespaceQueryEvent.from("test-namespace"));

            entityManager.flush();
            entityManager.clear();

            assertEquals(
                    nodeCountBefore,
                    DiscoveryNode.count(),
                    "Should not create DiscoveryNodes while shutting down");
            assertEquals(
                    targetCountBefore,
                    Target.count(),
                    "Should not create Targets while shutting down");
            verifyNoInteractions(bus);
        } finally {
            discovery.setShuttingDown(false);
        }
    }

    @Test
    @Transactional
    void testHandleEndpointEventIgnoredDuringShutdown() {
        discovery.setShuttingDown(true);

        try {
            long nodeCountBefore = DiscoveryNode.count();
            long targetCountBefore = Target.count();

            Target target = new Target();
            target.connectUrl =
                    URI.create("service:jmx:rmi:///jndi/rmi://192.168.1.100:9091/jmxrmi");
            target.alias = "ignored-target";
            target.labels = new HashMap<>();
            target.annotations = new Target.Annotations();

            KubeEndpointSlicesDiscovery.EndpointDiscoveryEvent event =
                    KubeEndpointSlicesDiscovery.EndpointDiscoveryEvent.from(
                            "test-namespace", target, null, Target.EventKind.LOST);

            discovery.handleEndpointEvent(event);

            entityManager.flush();
            entityManager.clear();

            assertEquals(
                    nodeCountBefore,
                    DiscoveryNode.count(),
                    "Should not create DiscoveryNodes while shutting down");
            assertEquals(
                    targetCountBefore,
                    Target.count(),
                    "Should not create Targets while shutting down");
        } finally {
            discovery.setShuttingDown(false);
        }
    }

    @Test
    void testShutdownWaitsForActiveDiscoveryEventHandler() throws Exception {
        assertTrue(discovery.enterDiscoveryEventHandler());

        CountDownLatch shutdownStarted = new CountDownLatch(1);
        CountDownLatch shutdownFinished = new CountDownLatch(1);
        AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();
        Thread shutdownThread =
                new Thread(
                        () -> {
                            try {
                                shutdownStarted.countDown();
                                discovery.onStop(null);
                            } catch (Throwable t) {
                                shutdownFailure.set(t);
                            } finally {
                                shutdownFinished.countDown();
                            }
                        });

        try {
            shutdownThread.start();

            assertTrue(shutdownStarted.await(5, TimeUnit.SECONDS));
            assertFalse(
                    shutdownFinished.await(250, TimeUnit.MILLISECONDS),
                    "Shutdown should wait for an active discovery event handler");
        } finally {
            discovery.exitDiscoveryEventHandler();
        }

        assertTrue(shutdownFinished.await(5, TimeUnit.SECONDS));
        if (shutdownFailure.get() != null) {
            fail(shutdownFailure.get());
        }
        discovery.setShuttingDown(false);
    }
}
