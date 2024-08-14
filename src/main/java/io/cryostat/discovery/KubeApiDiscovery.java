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
import java.util.function.Function;
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
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.concurrent.ConcurrentException;
import org.apache.commons.lang3.concurrent.LazyInitializer;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class KubeApiDiscovery {
    public static final String REALM = "KubernetesApi";

    public static final String DISCOVERY_NAMESPACE_LABEL_KEY = "discovery.cryostat.io/namespace";

    private static final List<String> EMPTY_PORT_NAMES = new ArrayList<>();

    private static final List<Integer> EMPTY_PORT_NUMBERS = new ArrayList<>();

    @Inject Logger logger;

    @Inject KubeConfig kubeConfig;

    @Inject KubernetesClient client;

    @Inject EventBus bus;

    @ConfigProperty(name = "cryostat.discovery.kubernetes.enabled")
    boolean enabled;

    @ConfigProperty(name = "cryostat.discovery.kubernetes.port-names")
    Optional<List<String>> jmxPortNames;

    @ConfigProperty(name = "cryostat.discovery.kubernetes.port-numbers")
    Optional<List<Integer>> jmxPortNumbers;

    @ConfigProperty(name = "cryostat.discovery.kubernetes.resync-period")
    Duration informerResyncPeriod;

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
                                                client.endpoints()
                                                        .inNamespace(ns)
                                                        .inform(
                                                                new EndpointsHandler(),
                                                                informerResyncPeriod.toMillis()));
                                        logger.debugv(
                                                "Started Endpoints SharedInformer for"
                                                        + " namespace \"{0}\"",
                                                ns);
                                    });
                    return result;
                }
            };

    // Priority is set higher than default 0 such that onStart is called first before onAfterStart
    // This ensures realm node is persisted before initializing informers
    void onStart(@Observes @Priority(1) StartupEvent evt) {
        if (!enabled()) {
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

        logger.debugv("Starting {0} client", REALM);
    }

    void onAfterStart(@Observes StartupEvent evt) {
        if (!enabled() || !available()) {
            return;
        }
        safeGetInformers();
    }

    void onStop(@Observes ShutdownEvent evt) {
        if (!(enabled() && available())) {
            return;
        }

        logger.debugv("Shutting down {0} client", REALM);
        safeGetInformers()
                .forEach(
                        (ns, informer) -> {
                            informer.close();
                            logger.debugv(
                                    "Closed Endpoints SharedInformer for namespace \"{0}\"", ns);
                        });
    }

    boolean enabled() {
        return enabled;
    }

    boolean available() {
        try {
            boolean hasNamespace = StringUtils.isNotBlank(kubeConfig.getOwnNamespace());
            return kubeConfig.kubeApiAvailable() && hasNamespace;
        } catch (Exception e) {
            logger.trace(e);
        }
        return false;
    }

    private boolean isCompatiblePort(EndpointPort port) {
        return jmxPortNames.orElse(EMPTY_PORT_NAMES).contains(port.getName())
                || jmxPortNumbers.orElse(EMPTY_PORT_NUMBERS).contains(port.getPort());
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

    private boolean isTargetUnderRealm(URI connectUrl) throws IllegalStateException {
        // Check for any targets with the same connectUrl in other realms
        try {
            Target persistedTarget = Target.getTargetByConnectUrl(connectUrl);
            String realmOfTarget = persistedTarget.annotations.cryostat().get("REALM");
            if (!REALM.equals(realmOfTarget)) {
                logger.warnv(
                        "Expected persisted target with serviceURL {0} to be under realm"
                                + " {1} but found under {2} ",
                        persistedTarget.connectUrl, REALM, realmOfTarget);
                throw new IllegalStateException();
            }
            return true;
        } catch (NoResultException e) {
        }
        return false;
    }

    @ConsumeEvent(blocking = true, ordered = true)
    @Transactional(TxType.REQUIRES_NEW)
    public void handleEndpointEvent(EndpointDiscoveryEvent evt) {
        String namespace = evt.namespace;
        DiscoveryNode realm = DiscoveryNode.getRealm(REALM).orElseThrow();
        DiscoveryNode nsNode =
                DiscoveryNode.getChild(realm, n -> n.name.equals(namespace))
                        .orElse(
                                DiscoveryNode.environment(
                                        namespace, KubeDiscoveryNodeType.NAMESPACE));

        try {
            if (evt.eventKind == EventKind.FOUND) {
                buildOwnerChain(nsNode, evt.target, evt.objRef);
            } else {
                pruneOwnerChain(nsNode, evt.target);
            }

            if (!nsNode.hasChildren()) {
                realm.children.remove(nsNode);
                nsNode.parent = null;
            } else if (!realm.children.contains(nsNode)) {
                realm.children.add(nsNode);
                nsNode.parent = realm;
            }
            realm.persist();
        } catch (Exception e) {
            logger.warn("Endpoint handler exception", e);
        }
    }

    private void handleObservedEndpoints(String namespace) {
        List<DiscoveryNode> targetNodes =
                DiscoveryNode.findAllByNodeType(KubeDiscoveryNodeType.ENDPOINT).stream()
                        .filter(
                                (n) ->
                                        namespace.equals(
                                                n.labels.get(DISCOVERY_NAMESPACE_LABEL_KEY)))
                        .collect(Collectors.toList());

        Map<URI, ObjectReference> targetRefMap = new HashMap<>();

        Set<Target> persistedTargets = new HashSet<>();
        for (DiscoveryNode node : targetNodes) {
            persistedTargets.add(node.target);
        }

        Set<Target> observedTargets =
                safeGetInformers().get(namespace).getStore().list().stream()
                        .map((endpoint) -> getTargetTuplesFrom(endpoint))
                        .flatMap(List::stream)
                        .filter((tuple) -> Objects.nonNull(tuple.objRef))
                        .map(
                                (tuple) -> {
                                    Target t = tuple.toTarget();
                                    if (t != null) {
                                        targetRefMap.put(t.connectUrl, tuple.objRef);
                                    }
                                    return t;
                                })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

        // Add new targets
        Target.compare(persistedTargets)
                .to(observedTargets)
                .added()
                .forEach(
                        (t) ->
                                notify(
                                        EndpointDiscoveryEvent.from(
                                                namespace,
                                                t,
                                                targetRefMap.get(t.connectUrl),
                                                EventKind.FOUND)));

        // Prune deleted targets
        Target.compare(persistedTargets)
                .to(observedTargets)
                .removed()
                .forEach(
                        (t) ->
                                notify(
                                        EndpointDiscoveryEvent.from(
                                                namespace, t, null, EventKind.LOST)));
    }

    private void notify(EndpointDiscoveryEvent evt) {
        bus.publish(KubeApiDiscovery.class.getName(), evt);
    }

    private void pruneOwnerChain(DiscoveryNode nsNode, Target target) {
        if (!isTargetUnderRealm(target.connectUrl)) {
            logger.infov(
                    "Target with serviceURL {0} does not exist in discovery tree. Skipped deleting",
                    target.connectUrl);
            return;
        }

        // Retrieve the latest snapshot of the target
        // The target received from event message is outdated as it belongs to the previous
        // transaction
        target = Target.getTargetByConnectUrl(target.connectUrl);

        DiscoveryNode child = target.discoveryNode;
        while (true) {
            DiscoveryNode parent = child.parent;

            if (parent == null) {
                break;
            }

            parent.children.remove(child);
            child.parent = null;
            parent.persist();

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
        if (isTargetUnderRealm(target.connectUrl)) {
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
                    queryForNode(
                            targetRef.getNamespace(), targetRef.getName(), targetRef.getKind());

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

                if (!ownerNode.children.contains(childNode)) {
                    ownerNode.children.add(childNode);
                }
                childNode.parent = ownerNode;

                ownerNode.persist();
                childNode.persist();

                child = owner;
            }

            nsNode.children.add(child.getRight());
            child.getRight().parent = nsNode;
        } else {
            // if the Endpoint points to something else(?) than a Pod, just add the target straight
            // to the Namespace
            nsNode.children.add(targetNode);
            targetNode.parent = nsNode;
            targetNode.persist();
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
                nodeType.getQueryFunction().apply(client).apply(namespace).apply(name);

        DiscoveryNode node =
                DiscoveryNode.getNode(
                                n -> {
                                    return nodeType.getKind().equals(n.nodeType)
                                            && name.equals(n.name)
                                            && namespace.equals(
                                                    n.labels.get(DISCOVERY_NAMESPACE_LABEL_KEY));
                                })
                        .orElseGet(
                                () -> {
                                    DiscoveryNode newNode = new DiscoveryNode();
                                    newNode.name = name;
                                    newNode.nodeType = nodeType.getKind();
                                    newNode.children = new ArrayList<>();
                                    newNode.target = null;
                                    Map<String, String> labels =
                                            kubeObj != null
                                                    ? kubeObj.getMetadata().getLabels()
                                                    : new HashMap<>();
                                    // Add namespace to label to retrieve node later
                                    labels.put(DISCOVERY_NAMESPACE_LABEL_KEY, namespace);
                                    newNode.labels = labels;
                                    return newNode;
                                });
        return Pair.of(kubeObj, node);
    }

    @ApplicationScoped
    static final class KubeConfig {
        private static final String OWN_NAMESPACE = ".";

        @Inject Logger logger;
        @Inject FileSystem fs;

        @ConfigProperty(name = "cryostat.discovery.kubernetes.namespaces")
        Optional<List<String>> watchNamespaces;

        @ConfigProperty(name = "kubernetes.service.host")
        Optional<String> serviceHost;

        @ConfigProperty(name = "cryostat.discovery.kubernetes.namespace-path")
        String namespacePath;

        private final LazyInitializer<KubernetesClient> kubeClient =
                new LazyInitializer<KubernetesClient>() {
                    @Override
                    protected KubernetesClient initialize() throws ConcurrentException {
                        return new KubernetesClientBuilder()
                                .withTaskExecutor(Infrastructure.getDefaultWorkerPool())
                                .build();
                    }
                };

        Collection<String> getWatchNamespaces() {
            return watchNamespaces.orElse(List.of()).stream()
                    .map(
                            n -> {
                                if (OWN_NAMESPACE.equals(n)) {
                                    return getOwnNamespace();
                                }
                                return n;
                            })
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toSet());
        }

        String getOwnNamespace() {
            try {
                return fs.readString(Path.of(namespacePath));
            } catch (Exception e) {
                logger.trace(e);
                return null;
            }
        }

        boolean kubeApiAvailable() {
            return StringUtils.isNotBlank(serviceHost.orElse(""));
        }
    }

    private final class EndpointsHandler implements ResourceEventHandler<Endpoints> {
        @Override
        public void onAdd(Endpoints endpoints) {
            logger.debugv(
                    "Endpoint {0} created in namespace {1}",
                    endpoints.getMetadata().getName(), endpoints.getMetadata().getNamespace());
            QuarkusTransaction.joiningExisting()
                    .run(() -> handleObservedEndpoints(endpoints.getMetadata().getNamespace()));
        }

        @Override
        public void onUpdate(Endpoints oldEndpoints, Endpoints newEndpoints) {
            logger.debugv(
                    "Endpoint {0} modified in namespace {1}",
                    newEndpoints.getMetadata().getName(),
                    newEndpoints.getMetadata().getNamespace());
            QuarkusTransaction.joiningExisting()
                    .run(() -> handleObservedEndpoints(newEndpoints.getMetadata().getNamespace()));
        }

        @Override
        public void onDelete(Endpoints endpoints, boolean deletedFinalStateUnknown) {
            logger.debugv(
                    "Endpoint {0} deleted in namespace {1}",
                    endpoints.getMetadata().getName(), endpoints.getMetadata().getNamespace());
            if (deletedFinalStateUnknown) {
                logger.warnv("Deleted final state unknown: {0}", endpoints);
                return;
            }
            QuarkusTransaction.joiningExisting()
                    .run(() -> handleObservedEndpoints(endpoints.getMetadata().getNamespace()));
        }
    }

    private static record EndpointDiscoveryEvent(
            String namespace, Target target, ObjectReference objRef, EventKind eventKind) {
        static EndpointDiscoveryEvent from(
                String namespace, Target target, ObjectReference objRef, EventKind eventKind) {
            return new EndpointDiscoveryEvent(namespace, target, objRef, eventKind);
        }
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
                URI connectUrl = URI.create(jmxUrl.toString());

                Pair<HasMetadata, DiscoveryNode> pair =
                        queryForNode(namespace, objRef.getName(), objRef.getKind());

                HasMetadata obj = pair.getLeft();

                Target target = new Target();
                target.activeRecordings = new ArrayList<>();
                target.connectUrl = connectUrl;
                target.alias = objRef.getName();
                target.labels = (obj != null ? obj.getMetadata().getLabels() : new HashMap<>());
                target.annotations =
                        new Annotations(
                                obj != null ? obj.getMetadata().getAnnotations() : Map.of(),
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
    }
}

enum KubeDiscoveryNodeType implements NodeType {
    NAMESPACE("Namespace"),
    STATEFULSET(
            "StatefulSet",
            c -> ns -> n -> c.apps().statefulSets().inNamespace(ns).withName(n).get()),
    DAEMONSET("DaemonSet", c -> ns -> n -> c.apps().daemonSets().inNamespace(ns).withName(n).get()),
    DEPLOYMENT(
            "Deployment", c -> ns -> n -> c.apps().deployments().inNamespace(ns).withName(n).get()),
    REPLICASET(
            "ReplicaSet", c -> ns -> n -> c.apps().replicaSets().inNamespace(ns).withName(n).get()),
    REPLICATIONCONTROLLER(
            "ReplicationController",
            c -> ns -> n -> c.replicationControllers().inNamespace(ns).withName(n).get()),
    POD("Pod", c -> ns -> n -> c.pods().inNamespace(ns).withName(n).get()),
    ENDPOINT("Endpoint", c -> ns -> n -> c.endpoints().inNamespace(ns).withName(n).get()),
    // OpenShift resources
    DEPLOYMENTCONFIG("DeploymentConfig"),
    ;

    private final String kubernetesKind;
    private final transient Function<
                    KubernetesClient, Function<String, Function<String, ? extends HasMetadata>>>
            getFn;

    KubeDiscoveryNodeType(String kubernetesKind) {
        this(kubernetesKind, client -> namespace -> name -> null);
    }

    KubeDiscoveryNodeType(
            String kubernetesKind,
            Function<KubernetesClient, Function<String, Function<String, ? extends HasMetadata>>>
                    getFn) {
        this.kubernetesKind = kubernetesKind;
        this.getFn = getFn;
    }

    @Override
    public String getKind() {
        return kubernetesKind;
    }

    public Function<KubernetesClient, Function<String, Function<String, ? extends HasMetadata>>>
            getQueryFunction() {
        return getFn;
    }

    public static KubeDiscoveryNodeType fromKubernetesKind(String kubernetesKind) {
        if (kubernetesKind == null) {
            return null;
        }
        for (KubeDiscoveryNodeType nt : values()) {
            if (kubernetesKind.equalsIgnoreCase(nt.kubernetesKind)) {
                return nt;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return getKind();
    }
}
