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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import javax.management.remote.JMXServiceURL;

import io.cryostat.core.sys.FileSystem;
import io.cryostat.targets.Target;
import io.cryostat.targets.Target.Annotations;
import io.cryostat.targets.Target.EventKind;

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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class KubeApiDiscovery {
    public static final String REALM = "KubernetesApi";

    public static final long ENDPOINTS_INFORMER_RESYNC_PERIOD = Duration.ofSeconds(30).toMillis();

    public static final String NAMESPACE_LABEL_KEY = "io.cryostat/namespace";

    @Inject Logger logger;

    @Inject KubeConfig kubeConfig;

    @ConfigProperty(name = "cryostat.discovery.kubernetes.enabled")
    boolean enabled;

    @ConfigProperty(name = "cryostat.discovery.k8s.port-names")
    Optional<List<String>> JmxPortNames;

    @ConfigProperty(name = "cryostat.discovery.k8s.port-numbers")
    Optional<List<Integer>> JmxPortNumbers;

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
            plugin.persist();
            universe.persist();
        }

        logger.infov("Starting {0} client", REALM);
    }

    void onAfterStart(@Observes StartupEvent evt) {
        safeGetInformers(); // trigger lazy init
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
    public void handleEndpointEvent(TargetTuple tuple, EventKind eventKind) {
        DiscoveryNode realm = DiscoveryNode.getRealm(REALM).orElseThrow();
        DiscoveryNode nsNode =
                DiscoveryNode.getChild(realm, n -> tuple.objRef.getNamespace().equals(n.name))
                        .orElse(
                                DiscoveryNode.environment(
                                        tuple.objRef.getNamespace(),
                                        KubeDiscoveryNodeType.NAMESPACE));

        try {
            if (eventKind == EventKind.FOUND) {
                buildOwnerChain(nsNode, tuple);
                if (!realm.children.contains(nsNode)) {
                    realm.children.add(nsNode);
                }
            } else if (eventKind == EventKind.LOST) {
                pruneOwnerChain(nsNode, tuple);
                if (!nsNode.hasChildren()) {
                    realm.children.remove(nsNode);
                }
            }
            realm.persist();
        } catch (Exception e) {
            logger.warn("Endpoint handler exception", e);
        }
    }

    private void pruneOwnerChain(DiscoveryNode nsNode, TargetTuple targetTuple) {
        ObjectReference targetRef = targetTuple.objRef;
        if (targetRef == null) {
            logger.errorv(
                    "Address {0} for Endpoint {1} had null target reference",
                    targetTuple.addr.getIp() != null
                            ? targetTuple.addr.getIp()
                            : targetTuple.addr.getHostname(),
                    targetTuple.objRef.getName());
            return;
        }

        try {
            Target t = Target.getTargetByConnectUrl(targetTuple.toTarget().connectUrl);
            DiscoveryNode targetNode = t.discoveryNode;

            if (nsNode.children.contains(targetNode)) {
                nsNode.children.remove(targetNode);
            } else {
                Pair<HasMetadata, DiscoveryNode> pod =
                        queryForNode(
                                targetRef.getNamespace(), targetRef.getName(), targetRef.getKind());
                pod.getRight().children.remove(targetNode);
                pod.getRight().persist();

                Pair<HasMetadata, DiscoveryNode> node = pod;
                while (true) {
                    Pair<HasMetadata, DiscoveryNode> owner = getOwnerNode(node);
                    if (owner == null) {
                        break;
                    }
                    DiscoveryNode ownerNode = owner.getRight();
                    if (!node.getRight().hasChildren()) {
                        ownerNode.children.remove(pod.getRight());
                        ownerNode.persist();
                    }
                    node = owner;
                }
            }
            nsNode.persist();
            t.delete();
        } catch (NoResultException e) {
        }
    }

    private void buildOwnerChain(DiscoveryNode nsNode, TargetTuple targetTuple) {
        ObjectReference targetRef = targetTuple.objRef;
        if (targetRef == null) {
            logger.errorv(
                    "Address {0} for Endpoint {1} had null target reference",
                    targetTuple.addr.getIp() != null
                            ? targetTuple.addr.getIp()
                            : targetTuple.addr.getHostname(),
                    targetTuple.objRef.getName());
            return;
        }

        String targetKind = targetRef.getKind();
        KubeDiscoveryNodeType targetType = KubeDiscoveryNodeType.fromKubernetesKind(targetKind);

        Target target = targetTuple.toTarget();
        DiscoveryNode targetNode = DiscoveryNode.target(target, KubeDiscoveryNodeType.ENDPOINT);
        target.discoveryNode = targetNode;
        target.persist();

        if (targetType == KubeDiscoveryNodeType.POD) {
            // if the Endpoint points to a Pod, chase the owner chain up as far as possible, then
            // add that to the Namespace

            Pair<HasMetadata, DiscoveryNode> pod =
                    queryForNode(
                            targetRef.getNamespace(), targetRef.getName(), targetRef.getKind());

            pod.getRight().children.add(targetNode);
            pod.getRight().persist();

            Pair<HasMetadata, DiscoveryNode> node = pod;
            while (true) {
                Pair<HasMetadata, DiscoveryNode> owner = getOwnerNode(node);
                if (owner == null) {
                    break;
                }
                DiscoveryNode ownerNode = owner.getRight();
                ownerNode.children.add(node.getRight());
                ownerNode.persist();

                node = owner;
            }

            nsNode.children.add(node.getRight());
        } else {
            // if the Endpoint points to something else(?) than a Pod, just add the target straight
            // to the Namespace
            nsNode.children.add(targetNode);
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
        return queryForNode(namespace, owner.getName(), owner.getKind());
    }

    private Pair<HasMetadata, DiscoveryNode> queryForNode(
            String namespace, String name, String kind) {
        KubeDiscoveryNodeType nodeType = KubeDiscoveryNodeType.fromKubernetesKind(kind);
        if (nodeType == null) {
            return null;
        }

        HasMetadata kubeObj =
                nodeType.getQueryFunction().apply(client()).apply(namespace).apply(name);

        DiscoveryNode node =
                DiscoveryNode.getNode(
                                n -> {
                                    return name.equals(n.name)
                                            && namespace.equals(n.labels.get(NAMESPACE_LABEL_KEY));
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
                                    newNode.labels.put(NAMESPACE_LABEL_KEY, namespace);
                                    return newNode;
                                });

        return Pair.of(kubeObj, node);
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
                    .filter(Objects::nonNull)
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
                                .withTaskExecutor(ForkJoinPool.commonPool())
                                .build();
            }
            return kubeClient;
        }
    }

    private final class EndpointsHandler implements ResourceEventHandler<Endpoints> {
        @Override
        public void onAdd(Endpoints endpoints) {
            getTargetTuplesFrom(endpoints)
                    .forEach(
                            (tuples) -> {
                                handleEndpointEvent(tuples, EventKind.FOUND);
                            });
        }

        @Override
        public void onUpdate(Endpoints oldEndpoints, Endpoints newEndpoints) {
            Set<TargetTuple> previousTuples = new HashSet<>(getTargetTuplesFrom(oldEndpoints));
            Set<TargetTuple> currentTuples = new HashSet<>(getTargetTuplesFrom(newEndpoints));

            if (previousTuples.equals(currentTuples)) {
                return;
            }

            TargetTuple.compare(previousTuples).to(currentTuples).removed().stream()
                    .forEach(tuple -> handleEndpointEvent(tuple, EventKind.LOST));

            TargetTuple.compare(previousTuples).to(currentTuples).added().stream()
                    .forEach(tuple -> handleEndpointEvent(tuple, EventKind.FOUND));
        }

        @Override
        public void onDelete(Endpoints endpoints, boolean deletedFinalStateUnknown) {
            if (deletedFinalStateUnknown) {
                logger.warnv("Deleted final state unknown: {0}", endpoints);
                return;
            }
            getTargetTuplesFrom(endpoints)
                    .forEach(
                            (tuple) -> {
                                handleEndpointEvent(tuple, EventKind.LOST);
                            });
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
            Pair<HasMetadata, DiscoveryNode> pair =
                    queryForNode(objRef.getNamespace(), objRef.getName(), objRef.getKind());
            HasMetadata obj = pair.getLeft();
            try {
                String targetName = objRef.getName();

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

                Target target = new Target();
                target.activeRecordings = new ArrayList<>();
                target.connectUrl = URI.create(jmxUrl.toString());
                target.alias = targetName;
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

        public static Compare compare(Collection<TargetTuple> src) {
            return new Compare(src);
        }

        public static class Compare {
            private Collection<TargetTuple> previous, current;

            public Compare(Collection<TargetTuple> previous) {
                this.previous = new HashSet<>(previous);
            }

            public Compare to(Collection<TargetTuple> current) {
                this.current = new HashSet<>(current);
                return this;
            }

            public Collection<TargetTuple> added() {
                Collection<TargetTuple> added = new HashSet<>(current);
                added.removeAll(previous);
                return added;
            }

            public Collection<TargetTuple> removed() {
                Collection<TargetTuple> removed = new HashSet<>(previous);
                removed.removeAll(current);
                return removed;
            }
        }
    }
}
