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

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.management.remote.JMXServiceURL;

import io.cryostat.core.sys.FileSystem;
import io.cryostat.targets.Target;
import io.cryostat.targets.Target.Annotations;

import io.fabric8.kubernetes.api.model.EndpointAddress;
import io.fabric8.kubernetes.api.model.EndpointPort;
import io.fabric8.kubernetes.api.model.EndpointSubset;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectReference;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.concurrent.ConcurrentException;
import org.apache.commons.lang3.concurrent.LazyInitializer;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class KubeApiDiscovery {
    public static final String REALM = "KubernetesApi";

    public static final long ENDPOINTS_INFORMER_RESYNC_PERIOD = Duration.ofSeconds(30).toMillis();

    public static final String DISCOVERY_NAMESPACE_LABEL_KEY = "discovery.cryostat.io/namespace";

    @Inject Logger logger;

    @Inject KubeConfig kubeConfig;

    @ConfigProperty(name = "cryostat.discovery.kubernetes.enabled")
    boolean enabled;

    @ConfigProperty(name = "cryostat.discovery.k8s.port-names")
    Optional<List<String>> JmxPortNames;

    @ConfigProperty(name = "cryostat.discovery.k8s.port-numbers")
    Optional<List<Integer>> JmxPortNumbers;

    private final Map<Triple<String, String, String>, Pair<HasMetadata, DiscoveryNode>>
            discoveryNodeCache = new ConcurrentHashMap<>();
    private final Map<Triple<String, String, String>, Object> queryLocks =
            new ConcurrentHashMap<>();

    private final LazyInitializer<HashMap<String, SharedIndexInformer<Endpoints>>> nsInformers =
            new LazyInitializer<HashMap<String, SharedIndexInformer<Endpoints>>>() {
                @Override
                protected HashMap<String, SharedIndexInformer<Endpoints>> initialize()
                        throws ConcurrentException {
                    // TODO: add support for some wildcard indicating a single Informer for any
                    // namespace that Cryostat has permissions to. This will need some restructuring
                    // of how the namespaces within the discovery tree are mapped.
                    var result = new HashMap<String, SharedIndexInformer<Endpoints>>();
                    kubeConfig
                            .getWatchNamespaces()
                            .forEach(
                                    ns -> {
                                        result.put(
                                                ns,
                                                client().endpoints()
                                                        .inNamespace(ns)
                                                        .inform(
                                                                new EndpointsHandler(),
                                                                ENDPOINTS_INFORMER_RESYNC_PERIOD));
                                        logger.infov(
                                                "Started Endpoints SharedInformer for"
                                                        + " namespace \"{0}\"",
                                                ns);
                                    });
                    return result;
                }
            };

    @Transactional
    void onStart(@Observes @Priority(1) StartupEvent evt) {
        if (!(enabled())) {
            return;
        }

        if (!available()) {
            logger.errorv("{0} enabled but environment is not Kubernetes!", getClass().getName());
            return;
        }

        DiscoveryNode universe = DiscoveryNode.getUniverse();
        if (DiscoveryNode.getRealm(REALM).isEmpty()) {
            DiscoveryPlugin plugin = new DiscoveryPlugin();
            DiscoveryNode node = DiscoveryNode.environment(REALM, BaseNodeType.REALM);
            plugin.realm = node;
            plugin.builtin = true;
            universe.children.add(node);
            node.parent = universe;
            plugin.persist();
            universe.persist();
        }

        logger.infov("Starting {0} client", REALM);
    }

    @Transactional
    void onAfterStart(@Observes StartupEvent evt) {
        safeGetInformers();
    }

    void onStop(@Observes ShutdownEvent evt) {
        if (!(enabled() && available())) {
            return;
        }

        logger.infov("Shutting down {0} client", REALM);
        safeGetInformers()
                .forEach(
                        (ns, informer) -> {
                            informer.close();
                            logger.infov(
                                    "Closed Endpoints SharedInformer for namespace \"{0}\"", ns);
                        });
    }

    boolean enabled() {
        return enabled;
    }

    boolean available() {
        try {
            boolean hasNamespace = StringUtils.isNotBlank(kubeConfig.getOwnNamespace());
            return kubeConfig.kubeApiAvailable() || hasNamespace;
        } catch (Exception e) {
            logger.info(e);
        }
        return false;
    }

    KubernetesClient client() {
        return kubeConfig.kubeClient();
    }

    private boolean isCompatiblePort(EndpointPort port) {
        return JmxPortNames.orElse(List.of()).contains(port.getName())
                || JmxPortNumbers.orElse(List.of()).contains(port.getPort());
    }

    private List<TargetTuple> getTargetTuplesFrom(Endpoints endpoints) {
        return tuplesFromEndpoints(endpoints).stream()
                .filter(
                        (ref) -> {
                            return Objects.nonNull(ref) && isCompatiblePort(ref.port);
                        })
                .collect(Collectors.toList());
    }

    private Map<String, SharedIndexInformer<Endpoints>> safeGetInformers() {
        Map<String, SharedIndexInformer<Endpoints>> informers;
        try {
            informers = nsInformers.get();
        } catch (ConcurrentException e) {
            throw new IllegalStateException(e);
        }
        return informers;
    }

    @Transactional
    public void handleEndpointEvent(String namespace) {
        DiscoveryNode realm = DiscoveryNode.getRealm(REALM).orElseThrow();
        DiscoveryNode nsNode =
                DiscoveryNode.getChild(realm, n -> n.name.equals(namespace))
                        .orElse(
                                DiscoveryNode.environment(
                                        namespace, KubeDiscoveryNodeType.NAMESPACE));

        try {
            List<DiscoveryNode> targetNodes =
                    DiscoveryNode.findAllByNodeType(KubeDiscoveryNodeType.ENDPOINT);
            Map<Target, ObjectReference> targetRefMap = new HashMap<>();
            safeGetInformers().get(namespace).getStore().list().stream()
                    .map((endpoint) -> getTargetTuplesFrom(endpoint))
                    .flatMap(List::stream)
                    .filter((tuple) -> Objects.nonNull(tuple.objRef))
                    .collect(Collectors.toList())
                    .forEach((tuple) -> targetRefMap.put(tuple.toTarget(), tuple.objRef));

            Set<Target> persistedTargets =
                    targetNodes.stream().map(node -> node.target).collect(Collectors.toSet());
            Set<Target> observedTargets = targetRefMap.keySet();

            // Add newly added endpoints
            Target.compare(persistedTargets)
                    .to(observedTargets)
                    .added()
                    .forEach((t) -> buildOwnerChain(nsNode, t, targetRefMap.get(t)));

            // Prune deleted endpoints
            Target.compare(persistedTargets)
                    .to(observedTargets)
                    .removed()
                    .forEach((t) -> pruneOwnerChain(nsNode, t));

            if (!nsNode.hasChildren()) {
                realm.children.remove(nsNode);
            } else if (!realm.children.contains(nsNode)) {
                realm.children.add(nsNode);
                nsNode.parent = realm;
            }
            realm.persist();
        } catch (Exception e) {
            logger.warn("Endpoint handler exception", e);
        } finally {
            discoveryNodeCache.clear();
            queryLocks.clear();
        }
    }

    private void pruneOwnerChain(DiscoveryNode nsNode, Target target) {
        if (!target.isPersistent()) {
            logger.infov(
                    "Target with serviceURL {0} does not exist in discovery tree. Skipped deleting",
                    target.connectUrl);
            return;
        }

        DiscoveryNode targetNode = target.discoveryNode;

        DiscoveryNode child = targetNode;
        while (true) {
            DiscoveryNode parent = child.parent;
            if (parent == null) {
                break;
            }

            parent.children.remove(child);
            if (parent.hasChildren()
                    || parent.nodeType.equals(KubeDiscoveryNodeType.NAMESPACE.getKind())) {
                break;
            }

            child = parent;
        }

        nsNode.persist();
        target.delete();
    }

    private void buildOwnerChain(DiscoveryNode nsNode, Target target, ObjectReference targetRef) {
        if (target.isPersistent()) {
            logger.infov(
                    "Target with serviceURL {0} already exist in discovery tree. Skipped adding",
                    target.connectUrl);
            return;
        }
        String targetKind = targetRef.getKind();
        KubeDiscoveryNodeType targetType = KubeDiscoveryNodeType.fromKubernetesKind(targetKind);

        DiscoveryNode targetNode = DiscoveryNode.target(target, KubeDiscoveryNodeType.ENDPOINT);
        target.discoveryNode = targetNode;
        target.persist();

        if (targetType == KubeDiscoveryNodeType.POD) {
            // if the Endpoint points to a Pod, chase the owner chain up as far as possible, then
            // add that to the Namespace

            Pair<HasMetadata, DiscoveryNode> pod =
                    discoveryNodeCache.computeIfAbsent(
                            cacheKey(targetRef.getNamespace(), targetRef), this::queryForNode);

            pod.getRight().children.add(targetNode);
            targetNode.parent = pod.getRight();
            pod.getRight().persist();

            Pair<HasMetadata, DiscoveryNode> child = pod;
            while (true) {
                Pair<HasMetadata, DiscoveryNode> owner = getOwnerNode(child);
                if (owner == null) {
                    break;
                }

                DiscoveryNode ownerNode = owner.getRight();
                DiscoveryNode childNode = child.getRight();

                ownerNode.children.add(childNode);
                childNode.parent = ownerNode;

                ownerNode.persist();
                child = owner;
            }

            nsNode.children.add(child.getRight());
            child.getRight().parent = nsNode;
        } else {
            // if the Endpoint points to something else(?) than a Pod, just add the target straight
            // to the Namespace
            nsNode.children.add(targetNode);
            targetNode.parent = nsNode;
        }

        nsNode.persist();
    }

    private Pair<HasMetadata, DiscoveryNode> getOwnerNode(Pair<HasMetadata, DiscoveryNode> child) {
        HasMetadata childRef = child.getLeft();
        if (childRef == null) {
            return null;
        }
        List<OwnerReference> owners = childRef.getMetadata().getOwnerReferences();
        // Take first "expected" owner Kind from NodeTypes, or if none, simply use the first owner.
        // If there are no owners then return null to signify this and break the chain
        if (owners.isEmpty()) {
            return null;
        }
        String namespace = childRef.getMetadata().getNamespace();
        OwnerReference owner =
                owners.stream()
                        .filter(o -> KubeDiscoveryNodeType.fromKubernetesKind(o.getKind()) != null)
                        .findFirst()
                        .orElse(owners.get(0));
        return discoveryNodeCache.computeIfAbsent(cacheKey(namespace, owner), this::queryForNode);
    }

    private Triple<String, String, String> cacheKey(String ns, OwnerReference resource) {
        return Triple.of(ns, resource.getKind(), resource.getName());
    }

    // Unfortunately, ObjectReference and OwnerReference both independently implement getKind and
    // getName - they don't come from a common base class.
    private Triple<String, String, String> cacheKey(String ns, ObjectReference resource) {
        return Triple.of(ns, resource.getKind(), resource.getName());
    }

    private Pair<HasMetadata, DiscoveryNode> queryForNode(
            Triple<String, String, String> lookupKey) {

        String namespace = lookupKey.getLeft();
        String name = lookupKey.getRight();
        KubeDiscoveryNodeType nodeType =
                KubeDiscoveryNodeType.fromKubernetesKind(lookupKey.getMiddle());
        if (nodeType == null) {
            return null;
        }

        synchronized (queryLocks.computeIfAbsent(lookupKey, k -> new Object())) {
            HasMetadata kubeObj =
                    nodeType.getQueryFunction().apply(client()).apply(namespace).apply(name);

            DiscoveryNode node =
                    DiscoveryNode.getNode(
                                    n -> {
                                        return name.equals(n.name)
                                                && namespace.equals(
                                                        n.labels.get(
                                                                DISCOVERY_NAMESPACE_LABEL_KEY));
                                    })
                            .orElseGet(
                                    () -> {
                                        DiscoveryNode newNode = new DiscoveryNode();
                                        newNode.name = name;
                                        newNode.nodeType = nodeType.getKind();
                                        newNode.children = new ArrayList<>();
                                        newNode.target = null;
                                        newNode.labels =
                                                kubeObj != null
                                                        ? kubeObj.getMetadata().getLabels()
                                                        : new HashMap<>();
                                        // Add namespace to label to retrieve node later
                                        newNode.labels.put(
                                                DISCOVERY_NAMESPACE_LABEL_KEY, namespace);
                                        return newNode;
                                    });
            return Pair.of(kubeObj, node);
        }
    }

    @ApplicationScoped
    static final class KubeConfig {
        public static final String KUBERNETES_NAMESPACE_PATH =
                "/var/run/secrets/kubernetes.io/serviceaccount/namespace";

        private static final String OWN_NAMESPACE = ".";

        @Inject Logger logger;
        @Inject FileSystem fs;

        @ConfigProperty(name = "cryostat.discovery.k8s.namespaces")
        Optional<List<String>> watchNamespaces;

        @ConfigProperty(name = "kubernetes.service.host")
        Optional<String> serviceHost;

        private KubernetesClient kubeClient;

        Collection<String> getWatchNamespaces() {
            return watchNamespaces.orElse(List.of()).stream()
                    .map(
                            n -> {
                                if (OWN_NAMESPACE.equals(n)) {
                                    return getOwnNamespace();
                                }
                                return n;
                            })
                    .filter((n) -> !n.isBlank())
                    .collect(Collectors.toSet());
        }

        String getOwnNamespace() {
            try {
                return fs.readString(Path.of(KUBERNETES_NAMESPACE_PATH));
            } catch (Exception e) {
                logger.trace(e);
                return null;
            }
        }

        boolean kubeApiAvailable() {
            return serviceHost.isPresent();
        }

        KubernetesClient kubeClient() {
            if (kubeClient == null) {
                kubeClient =
                        new KubernetesClientBuilder()
                                .withTaskExecutor(Infrastructure.getDefaultWorkerPool())
                                .build();
            }
            return kubeClient;
        }
    }

    private final class EndpointsHandler implements ResourceEventHandler<Endpoints> {
        @Override
        public void onAdd(Endpoints endpoints) {
            logger.infov(
                    "Endpoint {0} created in namespace {1}",
                    endpoints.getMetadata().getName(), endpoints.getMetadata().getNamespace());
            handleEndpointEvent(endpoints.getMetadata().getNamespace());
        }

        @Override
        public void onUpdate(Endpoints oldEndpoints, Endpoints newEndpoints) {
            logger.infov(
                    "Endpoint {0} modified in namespace {1}",
                    newEndpoints.getMetadata().getName(),
                    newEndpoints.getMetadata().getNamespace());
            handleEndpointEvent(newEndpoints.getMetadata().getNamespace());
        }

        @Override
        public void onDelete(Endpoints endpoints, boolean deletedFinalStateUnknown) {
            logger.infov(
                    "Endpoint {0} deleted in namespace {1}",
                    endpoints.getMetadata().getName(), endpoints.getMetadata().getNamespace());
            if (deletedFinalStateUnknown) {
                logger.warnv("Deleted final state unknown: {0}", endpoints);
                return;
            }
            handleEndpointEvent(endpoints.getMetadata().getNamespace());
        }
    }

    List<TargetTuple> tuplesFromEndpoints(Endpoints endpoints) {
        List<TargetTuple> tts = new ArrayList<>();
        for (EndpointSubset subset : endpoints.getSubsets()) {
            for (EndpointPort port : subset.getPorts()) {
                for (EndpointAddress addr : subset.getAddresses()) {
                    tts.add(new TargetTuple(addr.getTargetRef(), addr, port));
                }
            }
        }
        return tts;
    }

    private class TargetTuple {
        ObjectReference objRef;
        EndpointAddress addr;
        EndpointPort port;

        TargetTuple(ObjectReference objRef, EndpointAddress addr, EndpointPort port) {
            this.objRef = objRef;
            this.addr = addr;
            this.port = port;
        }

        public Target toTarget() {
            try {
                String ip = addr.getIp().replaceAll("\\.", "-");
                String namespace = objRef.getNamespace();

                boolean isPod = objRef.getKind().equals(KubeDiscoveryNodeType.POD.getKind());

                String host = String.format("%s.%s", ip, namespace);
                if (isPod) {
                    host = String.format("%s.pod", host);
                }

                JMXServiceURL jmxUrl =
                        new JMXServiceURL(
                                "rmi",
                                "",
                                0,
                                "/jndi/rmi://" + host + ':' + port.getPort() + "/jmxrmi");

                Target target;
                try {
                    return Target.getTargetByConnectUrl(URI.create(jmxUrl.toString()));
                } catch (NoResultException e) {
                }

                Pair<HasMetadata, DiscoveryNode> pair =
                        discoveryNodeCache.computeIfAbsent(
                                cacheKey(namespace, objRef), KubeApiDiscovery.this::queryForNode);

                HasMetadata obj = pair.getLeft();

                target = new Target();
                target.activeRecordings = new ArrayList<>();
                target.connectUrl = URI.create(jmxUrl.toString());
                target.alias = objRef.getName();
                target.labels = obj != null ? obj.getMetadata().getLabels() : new HashMap<>();
                target.annotations = new Annotations();
                target.annotations
                        .platform()
                        .putAll(obj != null ? obj.getMetadata().getAnnotations() : Map.of());
                target.annotations
                        .cryostat()
                        .putAll(
                                Map.of(
                                        "REALM",
                                        REALM,
                                        "HOST",
                                        addr.getIp(),
                                        "PORT",
                                        Integer.toString(port.getPort()),
                                        "NAMESPACE",
                                        objRef.getNamespace(),
                                        isPod ? "POD_NAME" : "OBJECT_NAME",
                                        objRef.getName()));

                return target;
            } catch (Exception e) {
                logger.warn("Target conversion exception", e);
                return null;
            }
        }

        @Override
        public boolean equals(Object other) {
            if (other == null) {
                return false;
            }
            if (other == this) {
                return true;
            }
            if (!(other instanceof TargetTuple)) {
                return false;
            }
            TargetTuple sr = (TargetTuple) other;
            return new EqualsBuilder()
                    .append(objRef.getApiVersion(), sr.objRef.getApiVersion())
                    .append(objRef.getKind(), sr.objRef.getKind())
                    .append(objRef.getNamespace(), sr.objRef.getNamespace())
                    .append(objRef.getName(), sr.objRef.getName())
                    .append(addr.getIp(), sr.addr.getIp())
                    .append(port.getPort(), sr.port.getPort())
                    .build();
        }
    }
}
