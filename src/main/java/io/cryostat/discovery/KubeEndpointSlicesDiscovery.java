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
import java.util.Stack;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.management.remote.JMXServiceURL;

import io.cryostat.ConfigProperties;
import io.cryostat.libcryostat.sys.FileSystem;
import io.cryostat.targets.Target;
import io.cryostat.targets.Target.Annotations;
import io.cryostat.targets.Target.EventKind;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectReference;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.discovery.v1.Endpoint;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointConditions;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointPort;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.concurrent.ConcurrentException;
import org.apache.commons.lang3.concurrent.LazyInitializer;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.TriggerBuilder;

/**
 * Discovery mechanism for Kubernetes and derivatives. Uses a Kubernetes client to communicate with
 * the k8s API server. This works by querying for Endpoint objects, which represent <ip, port>
 * tuples on k8s Services and therefore map to Pods (and therefore containers running JVMs), then
 * constructing a subtree by chasing owner references from the Endpoint object until the ownership
 * chain either ends or hits a Namespace object. Intermediate nodes across these chains are reused
 * so that common ancestors are shared and a tree is formed, rather than a list of lists.
 */
@ApplicationScoped
public class KubeEndpointSlicesDiscovery implements ResourceEventHandler<EndpointSlice> {

    private static final String NAMESPACE_QUERY_ADDR = "NS_QUERY_ENDPOINT_SLICE";
    private static final String ENDPOINT_SLICE_DISCOVERY_ADDR = "ENDPOINT_SLICE_DISC";
    private static final JobKey RESYNC_JOB_KEY =
            new JobKey("force-resync", "kube-endpoints-discovery");

    public static final String REALM = "KubernetesApi";

    public static final String DISCOVERY_NAMESPACE_LABEL_KEY = "discovery.cryostat.io/namespace";

    private static final List<String> EMPTY_PORT_NAMES = new ArrayList<>();

    private static final List<Integer> EMPTY_PORT_NUMBERS = new ArrayList<>();

    @Inject Logger logger;

    @Inject KubeConfig kubeConfig;

    @Inject KubernetesClient client;

    @Inject Scheduler scheduler;

    @Inject EventBus bus;

    @ConfigProperty(name = "cryostat.discovery.kubernetes.enabled")
    boolean enabled;

    @ConfigProperty(name = ConfigProperties.DISCOVERY_IPV6_ENABLED)
    boolean ipv6Enabled;

    @ConfigProperty(name = ConfigProperties.DISCOVERY_IPV4_DNS_TRANSFORM_ENABLED)
    boolean ipv4TransformEnabled;

    @ConfigProperty(name = "cryostat.discovery.kubernetes.port-names")
    Optional<List<String>> jmxPortNames;

    @ConfigProperty(name = "cryostat.discovery.kubernetes.port-numbers")
    Optional<List<Integer>> jmxPortNumbers;

    @ConfigProperty(name = "cryostat.discovery.kubernetes.resync-period")
    Duration informerResyncPeriod;

    @ConfigProperty(name = "cryostat.discovery.kubernetes.force-resync.enabled")
    boolean forceResyncEnabled;

    private final LazyInitializer<HashMap<String, SharedIndexInformer<EndpointSlice>>> nsInformers =
            new LazyInitializer<HashMap<String, SharedIndexInformer<EndpointSlice>>>() {
                @Override
                protected HashMap<String, SharedIndexInformer<EndpointSlice>> initialize()
                        throws ConcurrentException {
                    var result = new HashMap<String, SharedIndexInformer<EndpointSlice>>();
                    if (kubeConfig.watchAllNamespaces()) {
                        result.put(
                                KubeConfig.ALL_NAMESPACES,
                                client.discovery()
                                        .v1()
                                        .endpointSlices()
                                        .inAnyNamespace()
                                        .runnableInformer(informerResyncPeriod.toMillis())
                                        .addEventHandler(KubeEndpointSlicesDiscovery.this)
                                        .exceptionHandler(
                                                (b, t) -> {
                                                    logger.warn(t);
                                                    return true;
                                                })
                                        .run());
                        logger.debugv(
                                "Started EndpointSlice SharedInformer for all namespaces with"
                                        + " resync period {0}",
                                informerResyncPeriod);
                    } else {
                        kubeConfig
                                .getWatchNamespaces()
                                .forEach(
                                        ns -> {
                                            result.put(
                                                    ns,
                                                    client.discovery()
                                                            .v1()
                                                            .endpointSlices()
                                                            .inNamespace(ns)
                                                            .runnableInformer(
                                                                    informerResyncPeriod.toMillis())
                                                            .addEventHandler(
                                                                    KubeEndpointSlicesDiscovery
                                                                            .this)
                                                            .exceptionHandler(
                                                                    (b, t) -> {
                                                                        logger.warn(t);
                                                                        return true;
                                                                    })
                                                            .run());
                                            logger.debugv(
                                                    "Started EndpointSlice SharedInformer for"
                                                        + " namespace \"{0}\" with resync period"
                                                        + " {1}",
                                                    ns, informerResyncPeriod);
                                        });
                    }
                    return result;
                }
            };

    void onStart(@Observes StartupEvent evt) {
        if (!enabled()) {
            return;
        }

        if (!available()) {
            logger.errorv("{0} enabled but environment is not Kubernetes!", getClass().getName());
            return;
        }

        logger.debugv("Starting {0} client", REALM);
        safeGetInformers();
        if (forceResyncEnabled) {
            // TODO we should not need to force manual re-syncs this way - the Informer is already
            // supposed to resync itself. However, this has been observed to fail before and
            // Watchers/Informers lose connection to the k8s API server and never regain it, so
            // discovery gets stuck at that point in time until the Cryostat container is restarted.
            // This resync keeps things running and limping along even if the Informer fails -
            // updates will be delayed, but they will still happen.
            Callable<Collection<String>> resyncNamespaces;
            if (kubeConfig.watchAllNamespaces()) {
                resyncNamespaces =
                        () ->
                                client.namespaces().list().getItems().stream()
                                        .map(Namespace::getMetadata)
                                        .map(ObjectMeta::getName)
                                        .toList();
            } else {
                resyncNamespaces =
                        () ->
                                kubeConfig.getWatchNamespaces().stream()
                                        .filter(ns -> !KubeConfig.ALL_NAMESPACES.equals(ns))
                                        .toList();
            }
            try {
                var dataMap = new JobDataMap();
                dataMap.put("namespaces", resyncNamespaces.call());
                JobDetail jobDetail =
                        JobBuilder.newJob(EndpointsResyncJob.class)
                                .withIdentity(RESYNC_JOB_KEY)
                                .usingJobData(dataMap)
                                .build();
                var trigger =
                        TriggerBuilder.newTrigger()
                                .withIdentity(
                                        jobDetail.getKey().getName(), jobDetail.getKey().getGroup())
                                .startNow()
                                .withSchedule(
                                        SimpleScheduleBuilder.simpleSchedule()
                                                .repeatForever()
                                                .withIntervalInSeconds(
                                                        (int)
                                                                informerResyncPeriod
                                                                        .multipliedBy(4)
                                                                        .toSeconds()))
                                .build();
                if (!scheduler.checkExists(RESYNC_JOB_KEY)) {
                    scheduler.scheduleJob(jobDetail, trigger);
                }
            } catch (SchedulerException e) {
                logger.warn("Failed to schedule plugin prune job", e);
            } catch (Exception e) {
                logger.error(e);
            }
        }
    }

    void onStop(@Observes ShutdownEvent evt) {
        if (!(enabled() && available())) {
            return;
        }
        logger.debugv("Shutting down {0} client", REALM);

        try {
            scheduler.deleteJob(RESYNC_JOB_KEY);
        } catch (SchedulerException se) {
            logger.warn(se);
        }
        safeGetInformers()
                .forEach(
                        (ns, informer) -> {
                            informer.close();
                            logger.debugv(
                                    "Closed EndpointSlice SharedInformer for namespace \"{0}\"",
                                    ns);
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

    @Override
    public void onAdd(EndpointSlice slice) {
        logger.debugv(
                "EndpointSlice {0} created in namespace {1}",
                slice.getMetadata().getName(), slice.getMetadata().getNamespace());
        notify(NamespaceQueryEvent.from(slice.getMetadata().getNamespace()));
    }

    @Override
    public void onUpdate(EndpointSlice oldSlice, EndpointSlice newSlice) {
        logger.debugv(
                "EndpointSlice {0} modified in namespace {1}",
                newSlice.getMetadata().getName(), newSlice.getMetadata().getNamespace());
        notify(NamespaceQueryEvent.from(newSlice.getMetadata().getNamespace()));
    }

    @Override
    public void onDelete(EndpointSlice endpoints, boolean deletedFinalStateUnknown) {
        logger.debugv(
                "EndpointSlice {0} deleted in namespace {1}",
                endpoints.getMetadata().getName(), endpoints.getMetadata().getNamespace());
        if (deletedFinalStateUnknown) {
            logger.warnv("Deleted final state unknown: {0}", endpoints);
        }
        notify(NamespaceQueryEvent.from(endpoints.getMetadata().getNamespace()));
    }

    private Map<String, SharedIndexInformer<EndpointSlice>> safeGetInformers() {
        try {
            return nsInformers.get();
        } catch (ConcurrentException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean isCompatiblePort(EndpointPort port) {
        return jmxPortNames.orElse(EMPTY_PORT_NAMES).contains(port.getName())
                || jmxPortNumbers.orElse(EMPTY_PORT_NUMBERS).contains(port.getPort());
    }

    @ConsumeEvent(value = NAMESPACE_QUERY_ADDR, blocking = true, ordered = true)
    @Transactional(TxType.REQUIRES_NEW)
    public void handleQueryEvent(NamespaceQueryEvent evt) {
        for (var namespace : evt.namespaces) {
            try {
                Set<Target> persistedTargets = queryPersistedTargets(namespace);

                Map<Target, TargetWithHierarchy> observedTargetsWithHierarchy =
                        buildInMemoryTreeForNamespace(namespace);

                Set<Target> observedTargets = observedTargetsWithHierarchy.keySet();

                Target.compare(persistedTargets)
                        .to(observedTargets)
                        .removed()
                        .forEach(
                                (t) ->
                                        notify(
                                                EndpointDiscoveryEvent.from(
                                                        namespace, t, null, EventKind.LOST)));

                Target.compare(persistedTargets)
                        .to(observedTargets)
                        .added()
                        .forEach(
                                (t) -> {
                                    TargetWithHierarchy hierarchy =
                                            observedTargetsWithHierarchy.get(t);
                                    notify(
                                            EndpointDiscoveryEvent.from(
                                                    namespace,
                                                    t,
                                                    hierarchy.objRef,
                                                    EventKind.FOUND));
                                });
            } catch (Exception e) {
                logger.errorv(
                        e, "Failed to synchronize EndpointSlices in namespace {0}", namespace);
            }
        }
    }

    @ConsumeEvent(value = ENDPOINT_SLICE_DISCOVERY_ADDR, blocking = true, ordered = true)
    @Transactional(TxType.REQUIRED)
    public void handleEndpointEvent(EndpointDiscoveryEvent evt) {
        String namespace = evt.namespace;
        DiscoveryNode realm = DiscoveryNode.getRealm(REALM).orElseThrow();
        DiscoveryNode nsNode =
                DiscoveryNode.getChild(realm, n -> n.name.equals(namespace))
                        .orElse(
                                DiscoveryNode.environment(
                                        namespace, KubeDiscoveryNodeType.NAMESPACE));

        if (evt.eventKind == EventKind.FOUND) {
            persistOwnerChain(nsNode, evt.target, evt.objRef);
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
    }

    /**
     * Builds the ownership hierarchy for a given DiscoveryNode by chasing owner references up the
     * chain. This method does NOT persist any nodes - it only constructs the hierarchical
     * relationships in memory.
     *
     * @param node The starting node to build the ownership hierarchy from
     * @return The root node of the ownership chain (the topmost owner)
     */
    public DiscoveryNode buildOwnershipHierarchy(DiscoveryNode node) {
        Pair<HasMetadata, DiscoveryNode> current = Pair.of(null, node);

        // Chase the owner chain upward
        while (true) {
            Pair<HasMetadata, DiscoveryNode> owner = getOwnerNode(current);
            if (owner == null) {
                break;
            }

            DiscoveryNode ownerNode = owner.getRight();
            DiscoveryNode childNode = current.getRight();

            // Build parent-child relationships without persisting
            if (!ownerNode.children.contains(childNode)) {
                ownerNode.children.add(childNode);
            }
            childNode.parent = ownerNode;

            current = owner;
        }

        // Return the root of the ownership chain
        return current.getRight();
    }

    private void notify(NamespaceQueryEvent evt) {
        bus.publish(NAMESPACE_QUERY_ADDR, evt);
    }

    private void notify(EndpointDiscoveryEvent evt) {
        bus.publish(ENDPOINT_SLICE_DISCOVERY_ADDR, evt);
    }

    List<TargetTuple> getTargetTuplesFrom(EndpointSlice slice) {
        List<TargetTuple> tts = new ArrayList<>();

        if (!ipv6Enabled && "ipv6".equalsIgnoreCase(slice.getAddressType())) {
            return tts;
        }

        List<EndpointPort> ports = slice.getPorts();
        if (ports == null) {
            return tts;
        }

        String addressType = slice.getAddressType();
        String addressTypeLower = addressType.toLowerCase();
        List<Endpoint> endpoints = slice.getEndpoints();

        if (endpoints == null) {
            return tts;
        }

        Map<String, HasMetadata> nodeCache = new HashMap<>();

        for (EndpointPort port : ports) {
            for (Endpoint endpoint : endpoints) {
                TargetTuple tuple = createTargetTuple(endpoint, port, addressTypeLower, nodeCache);
                if (tuple != null && isCompatiblePort(tuple.port)) {
                    tts.add(tuple);
                }
            }
        }

        return tts;
    }

    private TargetTuple createTargetTuple(
            Endpoint endpoint,
            EndpointPort port,
            String addressTypeLower,
            Map<String, HasMetadata> nodeCache) {

        List<String> addresses = endpoint.getAddresses();
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }

        ObjectReference ref = endpoint.getTargetRef();
        if (ref == null) {
            return null;
        }

        // the EndpointSlice specification states that all of the
        // addresses are fungible, ie interchangeable - they will
        // resolve to the same Pod. So, we only need to worry about the
        // first one.
        String addr = addresses.get(0);
        String transformedAddr = transformAddress(addr, addressTypeLower, ref);

        String cacheKey = ref.getNamespace() + "/" + ref.getName() + "/" + ref.getKind();
        HasMetadata obj =
                nodeCache.computeIfAbsent(
                        cacheKey,
                        k ->
                                queryForNode(ref.getNamespace(), ref.getName(), ref.getKind())
                                        .getLeft());

        return new TargetTuple(ref, obj, transformedAddr, port, endpoint.getConditions());
    }

    private String transformAddress(String addr, String addressType, ObjectReference ref) {
        switch (addressType) {
            case "ipv6":
                return String.format("[%s]", addr);
            case "ipv4":
                if (ipv4TransformEnabled) {
                    return String.format(
                            "%s.%s.pod", addr.replaceAll("\\.", "-"), ref.getNamespace());
                }
                return addr;
            default:
                return addr;
        }
    }

    /**
     * Query persisted targets from the database for a given namespace.
     *
     * @param namespace The Kubernetes namespace
     * @return Set of persisted Target entities
     */
    private Set<Target> queryPersistedTargets(String namespace) {
        List<DiscoveryNode> targetNodes =
                DiscoveryNode.findAllByNodeType(KubeDiscoveryNodeType.ENDPOINT_SLICE).stream()
                        .filter(
                                (n) ->
                                        namespace.equals(
                                                n.labels.get(DISCOVERY_NAMESPACE_LABEL_KEY)))
                        .collect(Collectors.toList());

        Set<Target> persistedTargets = new HashSet<>();
        for (DiscoveryNode node : targetNodes) {
            persistedTargets.add(node.target);
        }
        return persistedTargets;
    }

    /**
     * Build in-memory discovery tree for all targets in a namespace. This method does NOT persist
     * anything to the database - it only queries Kubernetes API and builds the tree structure in
     * memory.
     *
     * @param namespace The Kubernetes namespace
     * @return Map of Target to TargetWithHierarchy containing the full ownership chain
     */
    private Map<Target, TargetWithHierarchy> buildInMemoryTreeForNamespace(String namespace) {
        Map<Target, TargetWithHierarchy> result = new HashMap<>();

        Stream<EndpointSlice> endpoints;
        if (kubeConfig.watchAllNamespaces()) {
            endpoints =
                    safeGetInformers().get(KubeConfig.ALL_NAMESPACES).getStore().list().stream()
                            .filter(
                                    ep ->
                                            Objects.equals(
                                                    ep.getMetadata().getNamespace(), namespace));
        } else {
            endpoints = safeGetInformers().get(namespace).getStore().list().stream();
        }

        endpoints
                .map(this::getTargetTuplesFrom)
                .flatMap(List::stream)
                .filter((tuple) -> Objects.nonNull(tuple.objRef))
                .forEach(
                        (tuple) -> {
                            Target t = tuple.toTarget();
                            if (t != null) {
                                DiscoveryNode hierarchyRoot = buildOwnershipLineageForTarget(tuple);
                                result.put(t, new TargetWithHierarchy(tuple.objRef, hierarchyRoot));
                            }
                        });

        return result;
    }

    /**
     * Builds the complete ownership lineage for a target, from the leaf node (EndpointSlice)
     * through Pod, ReplicaSet, Deployment, up to the Namespace. This method constructs the entire
     * tree in memory without any database access.
     *
     * @param tuple The TargetTuple containing target information and Kubernetes references
     * @return The root DiscoveryNode of the ownership hierarchy (typically a Namespace or the
     *     highest owner)
     */
    private DiscoveryNode buildOwnershipLineageForTarget(TargetTuple tuple) {
        String targetKind = tuple.objRef.getKind();
        KubeDiscoveryNodeType targetType = KubeDiscoveryNodeType.fromKubernetesKind(targetKind);

        DiscoveryNode targetNode = createInMemoryTargetNode(tuple);

        if (targetType == KubeDiscoveryNodeType.POD) {
            Pair<HasMetadata, DiscoveryNode> pod =
                    queryForNodeInMemory(
                            tuple.objRef.getNamespace(),
                            tuple.objRef.getName(),
                            tuple.objRef.getKind());

            if (pod != null) {
                DiscoveryNode podNode = pod.getRight();
                podNode.children.add(targetNode);
                targetNode.parent = podNode;

                DiscoveryNode rootNode = buildOwnershipHierarchy(pod.getLeft(), podNode);
                return rootNode;
            }
        }

        return targetNode;
    }

    /**
     * Creates an in-memory DiscoveryNode for a target without database access.
     *
     * @param tuple The TargetTuple containing target information
     * @return In-memory DiscoveryNode representing the target
     */
    private DiscoveryNode createInMemoryTargetNode(TargetTuple tuple) {
        DiscoveryNode targetNode = new DiscoveryNode();
        targetNode.name = tuple.objRef.getName();
        targetNode.nodeType = KubeDiscoveryNodeType.ENDPOINT_SLICE.getKind();
        targetNode.labels = new HashMap<>();
        targetNode.labels.put(DISCOVERY_NAMESPACE_LABEL_KEY, tuple.objRef.getNamespace());
        targetNode.children = new ArrayList<>();
        return targetNode;
    }

    /**
     * Builds the complete ownership hierarchy for a given leaf node by querying the Kubernetes API.
     * This method accepts an orphaned leaf node (with no parent references) and augments it by
     * building the full ownership chain in memory without any database access. The leaf node is
     * modified in place to have its parent references set, and the root node of the hierarchy is
     * returned.
     *
     * <p>For example, given a Pod node, this will query Kubernetes to find its ReplicaSet owner,
     * then the Deployment owner of that ReplicaSet, etc., building the complete chain: Pod ->
     * ReplicaSet -> Deployment -> (root).
     *
     * @param leafNode The orphaned leaf DiscoveryNode (e.g., representing a Pod) to augment with
     *     ownership hierarchy
     * @return The root DiscoveryNode of the ownership hierarchy (the ultimate parent)
     */
    public DiscoveryNode getOwnershipLineage(String namespace, DiscoveryNode leafNode) {
        KubeDiscoveryNodeType nodeType =
                KubeDiscoveryNodeType.fromKubernetesKind(leafNode.nodeType);
        if (nodeType == null) {
            return leafNode;
        }

        leafNode.labels.putIfAbsent(DISCOVERY_NAMESPACE_LABEL_KEY, namespace);
        HasMetadata kubeObj =
                nodeType.getQueryFunction().apply(client).apply(namespace).apply(leafNode.name);

        if (kubeObj == null) {
            return leafNode;
        }

        return buildOwnershipHierarchy(kubeObj, leafNode);
    }

    /**
     * Builds the complete ownership hierarchy for a given node identified by namespace, name, and
     * type. This is an overloaded convenience method that constructs a DiscoveryNode from the
     * provided parameters and delegates to {@link #getOwnershipLineage(String, DiscoveryNode)}.
     *
     * @param namespace The Kubernetes namespace containing the resource
     * @param nodeName The name of the Kubernetes resource (e.g., Pod name, EndpointSlice name)
     * @param nodeType The Kubernetes resource type (e.g., "Pod", "EndpointSlice", "Deployment")
     * @return The root DiscoveryNode of the ownership hierarchy, or a standalone node if no
     *     ownership chain exists
     */
    public DiscoveryNode getOwnershipLineage(String namespace, String nodeName, String nodeType) {
        DiscoveryNode leafNode = new DiscoveryNode();
        leafNode.name = nodeName;
        leafNode.nodeType = nodeType;
        leafNode.labels = new HashMap<>();
        leafNode.labels.put(DISCOVERY_NAMESPACE_LABEL_KEY, namespace);
        leafNode.children = new ArrayList<>();

        return getOwnershipLineage(namespace, leafNode);
    }

    /**
     * Builds the ownership hierarchy for a given Kubernetes object by chasing owner references up
     * the chain. This method does NOT persist any nodes or access the database - it only constructs
     * the hierarchical relationships in memory using the Kubernetes API.
     *
     * @param kubeObj The Kubernetes object to start from
     * @param node The DiscoveryNode representing the Kubernetes object
     * @return The root node of the ownership chain (the topmost owner)
     */
    DiscoveryNode buildOwnershipHierarchy(HasMetadata kubeObj, DiscoveryNode node) {
        Pair<HasMetadata, DiscoveryNode> current = Pair.of(kubeObj, node);

        while (true) {
            Pair<HasMetadata, DiscoveryNode> owner = getOwnerNodeInMemory(current);
            if (owner == null) {
                break;
            }

            DiscoveryNode ownerNode = owner.getRight();
            DiscoveryNode childNode = current.getRight();

            if (!ownerNode.children.contains(childNode)) {
                ownerNode.children.add(childNode);
            }
            childNode.parent = ownerNode;

            current = owner;
        }

        return current.getRight();
    }

    private void pruneOwnerChain(DiscoveryNode nsNode, Target target) {
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

    /**
     * Persists the ownership chain to the database. This is Stage 2 of the two-stage process. The
     * in-memory tree should already be built before calling this method.
     *
     * @param nsNode The namespace node
     * @param target The target to persist
     * @param targetRef The Kubernetes object reference for the target
     */
    private void persistOwnerChain(DiscoveryNode nsNode, Target target, ObjectReference targetRef) {
        DiscoveryNode targetNode = createTargetNode(target, targetRef);
        target.discoveryNode = targetNode;

        buildOwnershipHierarchy(targetRef, targetNode, nsNode);
        persistNodeChain(nsNode, target, targetNode);
    }

    private DiscoveryNode createTargetNode(Target target, ObjectReference targetRef) {
        return DiscoveryNode.target(
                target,
                KubeDiscoveryNodeType.ENDPOINT_SLICE,
                n ->
                        n.labels.putAll(
                                Map.of(DISCOVERY_NAMESPACE_LABEL_KEY, targetRef.getNamespace())));
    }

    private DiscoveryNode buildOwnershipHierarchy(
            ObjectReference targetRef, DiscoveryNode targetNode, DiscoveryNode nsNode) {
        KubeDiscoveryNodeType targetType =
                KubeDiscoveryNodeType.fromKubernetesKind(targetRef.getKind());

        if (targetType != KubeDiscoveryNodeType.POD) {
            nsNode.children.add(targetNode);
            targetNode.parent = nsNode;
            return targetNode;
        }

        Pair<HasMetadata, DiscoveryNode> pod =
                queryForNode(targetRef.getNamespace(), targetRef.getName(), targetRef.getKind());

        DiscoveryNode podNode = pod.getRight();
        podNode.children.add(targetNode);
        targetNode.parent = podNode;

        DiscoveryNode rootNode = buildOwnershipHierarchy(pod.getLeft(), podNode);

        nsNode.children.add(rootNode);
        rootNode.parent = nsNode;

        return rootNode;
    }

    private void persistNodeChain(DiscoveryNode nsNode, Target target, DiscoveryNode targetNode) {
        List<DiscoveryNode> nodeChain = collectNodeChain(targetNode, nsNode);

        target.persist();

        for (int i = nodeChain.size() - 1; i >= 0; i--) {
            nodeChain.get(i).persist();
        }

        // Use Stack-based iterative approach to persist all nodes at once
        // This ensures we persist from leaf to root
        Stack<DiscoveryNode> stack = new Stack<>();
        Set<DiscoveryNode> visited = new HashSet<>();

        // Start from the target node and traverse up to collect all nodes
        DiscoveryNode current = targetNode;
        while (current != null && current != nsNode) {
            if (!visited.contains(current)) {
                stack.push(current);
                visited.add(current);
            }
            current = current.parent;
        }

        // Persist target first (it has the associated Target entity)
        target.persist();

        // Persist all nodes in the chain from top to bottom
        while (!stack.isEmpty()) {
            DiscoveryNode node = stack.pop();
            node.persist();
        }

        // Finally persist the namespace node
        nsNode.persist();
    }

    private List<DiscoveryNode> collectNodeChain(DiscoveryNode startNode, DiscoveryNode endNode) {
        List<DiscoveryNode> chain = new ArrayList<>();
        DiscoveryNode current = startNode;

        while (current != null && current != endNode) {
            chain.add(current);
            current = current.parent;
        }

        return chain;
    }

    /**
     * Gets the owner node for a given child node by querying Kubernetes API. This method creates an
     * in-memory DiscoveryNode without database access.
     *
     * @param child Pair of Kubernetes object and its DiscoveryNode
     * @return Pair of owner Kubernetes object and its DiscoveryNode, or null if no owner
     */
    private Pair<HasMetadata, DiscoveryNode> getOwnerNodeInMemory(
            Pair<HasMetadata, DiscoveryNode> child) {
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
        return queryForNodeInMemory(namespace, owner.getName(), owner.getKind());
    }

    /**
     * Queries Kubernetes API for a node and creates an in-memory DiscoveryNode. This method does
     * NOT access the database.
     *
     * @param namespace Kubernetes namespace
     * @param name Resource name
     * @param kind Resource kind
     * @return Pair of Kubernetes object and in-memory DiscoveryNode, or null if not found
     */
    private Pair<HasMetadata, DiscoveryNode> queryForNodeInMemory(
            String namespace, String name, String kind) {

        KubeDiscoveryNodeType nodeType = KubeDiscoveryNodeType.fromKubernetesKind(kind);
        if (nodeType == null) {
            return null;
        }

        HasMetadata kubeObj =
                nodeType.getQueryFunction().apply(client).apply(namespace).apply(name);

        DiscoveryNode node = new DiscoveryNode();
        node.name = name;
        node.nodeType = nodeType.getKind();
        node.labels = new HashMap<>();

        Map<String, String> labels = new HashMap<>();
        if (kubeObj != null && kubeObj.getMetadata().getLabels() != null) {
            labels.putAll(kubeObj.getMetadata().getLabels());
        }
        labels.put(DISCOVERY_NAMESPACE_LABEL_KEY, namespace);
        node.labels.putAll(labels);

        return Pair.of(kubeObj, node);
    }

    /**
     * Queries Kubernetes API for a node and retrieves or creates a DiscoveryNode from the database.
     * This method DOES access the database.
     *
     * @param namespace Kubernetes namespace
     * @param name Resource name
     * @param kind Resource kind
     * @return Pair of Kubernetes object and DiscoveryNode from database, or null if not found
     */
    private Pair<HasMetadata, DiscoveryNode> queryForNode(
            String namespace, String name, String kind) {

        KubeDiscoveryNodeType nodeType = KubeDiscoveryNodeType.fromKubernetesKind(kind);
        if (nodeType == null) {
            return null;
        }

        HasMetadata kubeObj =
                nodeType.getQueryFunction().apply(client).apply(namespace).apply(name);

        DiscoveryNode node =
                DiscoveryNode.byTypeWithName(
                        nodeType,
                        name,
                        n -> namespace.equals(n.labels.get(DISCOVERY_NAMESPACE_LABEL_KEY)),
                        n -> {
                            Map<String, String> labels = new HashMap<>();
                            if (kubeObj != null && kubeObj.getMetadata().getLabels() != null) {
                                labels.putAll(kubeObj.getMetadata().getLabels());
                            }
                            // Add namespace to label to retrieve node later
                            labels.put(DISCOVERY_NAMESPACE_LABEL_KEY, namespace);
                            n.labels.putAll(labels);
                        });
        return Pair.of(kubeObj, node);
    }

    @DisallowConcurrentExecution
    private static class EndpointsResyncJob implements Job {
        @Inject Logger logger;
        @Inject EventBus bus;

        @SuppressWarnings("unchecked")
        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            try {
                Collection<String> namespaces =
                        (Collection<String>) context.getMergedJobDataMap().get("namespaces");
                logger.debugv("Resyncing namespaces: {0}", namespaces);
                bus.publish(NAMESPACE_QUERY_ADDR, NamespaceQueryEvent.from(namespaces));
            } catch (Exception e) {
                logger.warn(e);
            }
        }
    }

    @ApplicationScoped
    static final class KubeConfig {
        static final String ALL_NAMESPACES = "*";
        private static final String OWN_NAMESPACE = ".";

        @Inject Logger logger;
        @Inject FileSystem fs;

        @ConfigProperty(name = "cryostat.discovery.kubernetes.namespaces")
        Optional<List<String>> watchNamespaces;

        @ConfigProperty(name = "kubernetes.service.host")
        Optional<String> serviceHost;

        @ConfigProperty(name = "cryostat.discovery.kubernetes.namespace-path")
        String namespacePath;

        boolean watchAllNamespaces() {
            return getWatchNamespaces().stream().anyMatch(ns -> ALL_NAMESPACES.equals(ns));
        }

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

    private static record NamespaceQueryEvent(Collection<String> namespaces) {
        static NamespaceQueryEvent from(Collection<String> namespaces) {
            return new NamespaceQueryEvent(namespaces);
        }

        static NamespaceQueryEvent from(String namespace) {
            return new NamespaceQueryEvent(List.of(namespace));
        }
    }

    private static record EndpointDiscoveryEvent(
            String namespace, Target target, ObjectReference objRef, EventKind eventKind) {
        static EndpointDiscoveryEvent from(
                String namespace, Target target, ObjectReference objRef, EventKind eventKind) {
            return new EndpointDiscoveryEvent(namespace, target, objRef, eventKind);
        }
    }

    private static record TargetWithHierarchy(
            ObjectReference objRef, DiscoveryNode hierarchyRoot) {}

    class TargetTuple {
        ObjectReference objRef;
        HasMetadata obj;
        String addr;
        EndpointPort port;
        EndpointConditions conditions;

        TargetTuple(
                ObjectReference objRef,
                HasMetadata obj,
                String addr,
                EndpointPort port,
                EndpointConditions conditions) {
            this.objRef = objRef;
            this.obj = obj;
            this.addr = addr;
            this.port = port;
            this.conditions = conditions;
        }

        public Target toTarget() {
            try {
                boolean isPod = objRef.getKind().equals(KubeDiscoveryNodeType.POD.getKind());

                JMXServiceURL jmxUrl =
                        new JMXServiceURL(
                                "rmi",
                                "",
                                0,
                                "/jndi/rmi://" + addr + ':' + port.getPort() + "/jmxrmi");
                URI connectUrl = URI.create(jmxUrl.toString());

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
                                        addr,
                                        "PORT",
                                        Integer.toString(port.getPort()),
                                        "NAMESPACE",
                                        objRef.getNamespace(),
                                        isPod ? "POD_NAME" : "OBJECT_NAME",
                                        objRef.getName(),
                                        "CONDITION_READY",
                                        String.valueOf(Boolean.TRUE.equals(conditions.getReady())),
                                        "CONDITION_SERVING",
                                        String.valueOf(
                                                Boolean.TRUE.equals(conditions.getServing())),
                                        "CONDITION_TERMINATING",
                                        String.valueOf(
                                                Boolean.TRUE.equals(conditions.getTerminating()))));

                return target;
            } catch (Exception e) {
                logger.warn("Target conversion exception", e);
                return null;
            }
        }
    }

    static enum KubeDiscoveryNodeType implements NodeType {
        NAMESPACE("Namespace"),
        STATEFULSET(
                "StatefulSet",
                c -> ns -> n -> c.apps().statefulSets().inNamespace(ns).withName(n).get()),
        DAEMONSET(
                "DaemonSet",
                c -> ns -> n -> c.apps().daemonSets().inNamespace(ns).withName(n).get()),
        DEPLOYMENT(
                "Deployment",
                c -> ns -> n -> c.apps().deployments().inNamespace(ns).withName(n).get()),
        REPLICASET(
                "ReplicaSet",
                c -> ns -> n -> c.apps().replicaSets().inNamespace(ns).withName(n).get()),
        REPLICATIONCONTROLLER(
                "ReplicationController",
                c -> ns -> n -> c.replicationControllers().inNamespace(ns).withName(n).get()),
        POD("Pod", c -> ns -> n -> c.pods().inNamespace(ns).withName(n).get()),
        ENDPOINT("Endpoint", c -> ns -> n -> c.endpoints().inNamespace(ns).withName(n).get()),
        ENDPOINT_SLICE(
                "EndpointSlice",
                c ->
                        ns ->
                                n ->
                                        c.discovery()
                                                .v1()
                                                .endpointSlices()
                                                .inNamespace(ns)
                                                .withName(n)
                                                .get()),
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
                Function<
                                KubernetesClient,
                                Function<String, Function<String, ? extends HasMetadata>>>
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
}
