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

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import io.cryostat.discovery.KubeEndpointSlicesDiscovery.KubeConfig;
import io.cryostat.libcryostat.sys.FileSystem;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectReference;
import io.fabric8.kubernetes.api.model.discovery.v1.Endpoint;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointPort;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.vertx.mutiny.core.eventbus.EventBus;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.Scheduler;

@ExtendWith(MockitoExtension.class)
class KubeEndpointSlicesDiscoveryTest {

    @Mock Logger logger;
    @Mock KubeConfig kubeConfig;
    @Mock KubernetesClient client;
    @Mock Scheduler scheduler;
    @Mock EventBus bus;
    @Mock FileSystem fs;

    KubeEndpointSlicesDiscovery discovery;

    @BeforeEach
    void setup() {
        discovery = new KubeEndpointSlicesDiscovery();
        discovery.logger = logger;
        discovery.kubeConfig = kubeConfig;
        discovery.client = client;
        discovery.scheduler = scheduler;
        discovery.bus = bus;
        discovery.enabled = true;
        discovery.ipv6Enabled = false;
        discovery.ipv4TransformEnabled = false;
        discovery.jmxPortNames = Optional.of(List.of("jfr-jmx"));
        discovery.jmxPortNumbers = Optional.of(List.of(9091));
        discovery.informerResyncPeriod = Duration.ofMinutes(5);
        discovery.forceResyncEnabled = false;
    }

    @Test
    void testAvailableReturnsTrueWhenKubeApiAvailableAndHasNamespace() {
        when(kubeConfig.kubeApiAvailable()).thenReturn(true);
        when(kubeConfig.getOwnNamespace()).thenReturn("test-namespace");

        assertTrue(discovery.available());
    }

    @Test
    void testAvailableReturnsFalseWhenKubeApiNotAvailable() {
        when(kubeConfig.kubeApiAvailable()).thenReturn(false);
        when(kubeConfig.getOwnNamespace()).thenReturn("test-namespace");

        assertFalse(discovery.available());
    }

    @Test
    void testAvailableReturnsFalseWhenNamespaceIsBlank() {
        when(kubeConfig.kubeApiAvailable()).thenReturn(true);
        when(kubeConfig.getOwnNamespace()).thenReturn("");

        assertFalse(discovery.available());
    }

    @Test
    void testAvailableReturnsFalseWhenExceptionThrown() {
        when(kubeConfig.kubeApiAvailable()).thenThrow(new RuntimeException("Test exception"));

        assertFalse(discovery.available());
    }

    @Test
    void testOnAddNotifiesNamespaceQuery() {
        EndpointSlice slice = mock(EndpointSlice.class);
        ObjectMeta metadata = mock(ObjectMeta.class);
        when(metadata.getName()).thenReturn("test-slice");
        when(metadata.getNamespace()).thenReturn("test-namespace");
        when(slice.getMetadata()).thenReturn(metadata);

        discovery.onAdd(slice);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(bus).publish(eq("NS_QUERY_ENDPOINT_SLICE"), eventCaptor.capture());

        Object event = eventCaptor.getValue();
        assertNotNull(event);
        // Verify the event contains the namespace
        assertTrue(event.toString().contains("test-namespace"));
    }

    @Test
    void testOnUpdateNotifiesNamespaceQuery() {
        EndpointSlice oldSlice = mock(EndpointSlice.class);
        EndpointSlice newSlice = mock(EndpointSlice.class);
        ObjectMeta oldMetadata = mock(ObjectMeta.class);
        ObjectMeta newMetadata = mock(ObjectMeta.class);
        lenient().when(oldMetadata.getName()).thenReturn("test-slice");
        lenient().when(oldMetadata.getNamespace()).thenReturn("test-namespace");
        when(newMetadata.getName()).thenReturn("test-slice");
        lenient().when(newMetadata.getNamespace()).thenReturn("test-namespace");
        lenient().when(oldSlice.getMetadata()).thenReturn(oldMetadata);
        when(newSlice.getMetadata()).thenReturn(newMetadata);

        discovery.onUpdate(oldSlice, newSlice);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(bus).publish(eq("NS_QUERY_ENDPOINT_SLICE"), eventCaptor.capture());

        Object event = eventCaptor.getValue();
        assertNotNull(event);
        // Verify the event contains the namespace
        assertTrue(event.toString().contains("test-namespace"));
    }

    @Test
    void testOnDeleteNotifiesNamespaceQuery() {
        EndpointSlice slice = mock(EndpointSlice.class);
        ObjectMeta metadata = mock(ObjectMeta.class);
        when(metadata.getName()).thenReturn("test-slice");
        when(metadata.getNamespace()).thenReturn("test-namespace");
        when(slice.getMetadata()).thenReturn(metadata);

        discovery.onDelete(slice, false);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(bus).publish(eq("NS_QUERY_ENDPOINT_SLICE"), eventCaptor.capture());

        Object event = eventCaptor.getValue();
        assertNotNull(event);
        // Verify the event contains the namespace
        assertTrue(event.toString().contains("test-namespace"));
    }

    @Test
    void testTuplesFromEndpointsReturnsEmptyListForIPv6WhenDisabled() {
        discovery.ipv6Enabled = false;
        EndpointSlice slice = mock(EndpointSlice.class);
        when(slice.getAddressType()).thenReturn("ipv6");

        List<?> result = discovery.tuplesFromEndpoints(slice);

        assertTrue(result.isEmpty());
    }

    @Test
    void testTuplesFromEndpointsReturnsEmptyListWhenPortsAreNull() {
        EndpointSlice slice = mock(EndpointSlice.class);
        when(slice.getAddressType()).thenReturn("ipv4");
        when(slice.getPorts()).thenReturn(null);

        List<?> result = discovery.tuplesFromEndpoints(slice);

        assertTrue(result.isEmpty());
    }

    @Test
    void testTuplesFromEndpointsReturnsEmptyListWhenEndpointsAreNull() {
        EndpointSlice slice = mock(EndpointSlice.class);
        when(slice.getAddressType()).thenReturn("ipv4");
        EndpointPort port = mock(EndpointPort.class);
        lenient().when(port.getName()).thenReturn("jfr-jmx");
        lenient().when(port.getPort()).thenReturn(9091);
        when(slice.getPorts()).thenReturn(List.of(port));
        when(slice.getEndpoints()).thenReturn(null);

        List<?> result = discovery.tuplesFromEndpoints(slice);

        assertTrue(result.isEmpty());
    }

    @Test
    void testTuplesFromEndpointsSkipsEndpointsWithoutTargetRef() {
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

        List<?> result = discovery.tuplesFromEndpoints(slice);

        assertTrue(result.isEmpty());
    }

    @Test
    void testTuplesFromEndpointsSkipsEndpointsWithEmptyAddresses() {
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

        List<?> result = discovery.tuplesFromEndpoints(slice);

        assertTrue(result.isEmpty());
    }

    @Test
    void testKubeConfigWatchAllNamespacesReturnsTrueWhenWildcard() {
        KubeConfig config = new KubeConfig();
        config.logger = logger;
        config.fs = fs;
        config.watchNamespaces = Optional.of(List.of("*"));
        config.serviceHost = Optional.of("kubernetes.default.svc");
        config.namespacePath = "/var/run/secrets/kubernetes.io/serviceaccount/namespace";

        assertTrue(config.watchAllNamespaces());
    }

    @Test
    void testKubeConfigWatchAllNamespacesReturnsFalseWhenSpecificNamespaces() {
        KubeConfig config = new KubeConfig();
        config.logger = logger;
        config.fs = fs;
        config.watchNamespaces = Optional.of(List.of("ns1", "ns2"));
        config.serviceHost = Optional.of("kubernetes.default.svc");
        config.namespacePath = "/var/run/secrets/kubernetes.io/serviceaccount/namespace";

        assertFalse(config.watchAllNamespaces());
    }

    @Test
    void testKubeConfigGetWatchNamespacesReplacesOwnNamespace() throws Exception {
        KubeConfig config = new KubeConfig();
        config.logger = logger;
        config.fs = fs;
        config.watchNamespaces = Optional.of(List.of("."));
        config.serviceHost = Optional.of("kubernetes.default.svc");
        config.namespacePath = "/var/run/secrets/kubernetes.io/serviceaccount/namespace";

        when(fs.readString(any(Path.class))).thenReturn("actual-namespace");

        var namespaces = config.getWatchNamespaces();

        assertTrue(namespaces.contains("actual-namespace"));
        assertFalse(namespaces.contains("."));
    }

    @Test
    void testKubeConfigGetOwnNamespaceReturnsNamespaceFromFile() throws Exception {
        KubeConfig config = new KubeConfig();
        config.logger = logger;
        config.fs = fs;
        config.namespacePath = "/var/run/secrets/kubernetes.io/serviceaccount/namespace";

        when(fs.readString(any(Path.class))).thenReturn("test-namespace");

        String namespace = config.getOwnNamespace();

        assertEquals("test-namespace", namespace);
    }

    @Test
    void testKubeConfigGetOwnNamespaceReturnsNullOnException() throws Exception {
        KubeConfig config = new KubeConfig();
        config.logger = logger;
        config.fs = fs;
        config.namespacePath = "/var/run/secrets/kubernetes.io/serviceaccount/namespace";

        when(fs.readString(any(Path.class))).thenThrow(new RuntimeException("File not found"));

        String namespace = config.getOwnNamespace();

        assertNull(namespace);
    }

    @Test
    void testKubeConfigKubeApiAvailableReturnsTrueWhenServiceHostPresent() {
        KubeConfig config = new KubeConfig();
        config.logger = logger;
        config.fs = fs;
        config.serviceHost = Optional.of("kubernetes.default.svc");

        assertTrue(config.kubeApiAvailable());
    }

    @Test
    void testKubeConfigKubeApiAvailableReturnsFalseWhenServiceHostBlank() {
        KubeConfig config = new KubeConfig();
        config.logger = logger;
        config.fs = fs;
        config.serviceHost = Optional.of("");

        assertFalse(config.kubeApiAvailable());
    }

    @Test
    void testKubeConfigKubeApiAvailableReturnsFalseWhenServiceHostAbsent() {
        KubeConfig config = new KubeConfig();
        config.logger = logger;
        config.fs = fs;
        config.serviceHost = Optional.empty();

        assertFalse(config.kubeApiAvailable());
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
}
