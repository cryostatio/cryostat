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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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
import io.quarkus.panache.common.Parameters;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
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

    public static final String DISCOVERY_NAMESPACE_LABEL_KEY =
            Discovery.DISCOVERY_PLUGIN_LABEL_PREFIX + "namespace";

    // SQL query to find orphaned nodes - nodes with no children and no associated Target
    // Uses native SQL to access JSONB map keys/values which HQL doesn't support well
    private static final String FIND_ORPHANED_NODES_SQL =
            "SELECT n.* FROM DiscoveryNode n "
                    + "WHERE n.labels->:namespaceKey = to_jsonb(CAST(:namespace AS text)) "
                    + "AND (SELECT COUNT(*) FROM DiscoveryNode c WHERE c.parentNode = n.id) = 0 "
                    + "AND n.nodeType != :namespaceType "
                    + "AND NOT EXISTS ("
                    + "  SELECT 1 FROM Target t WHERE t.discoveryNode = n.id"
                    + ")";

    // SQL query to find existing DiscoveryNode entities by name/nodeType within a particular
    // namespace
    private static final String FIND_NAMESPACED_NODE_SQL =
            """
            SELECT n.id FROM DiscoveryNode n %n\
            WHERE n.name = :name AND %n\
            n.nodeType = :nodeType AND %n\
            n.labels->>'%s' = :namespace\
            """
                    .formatted(DISCOVERY_NAMESPACE_LABEL_KEY);

    private static final List<String> EMPTY_PORT_NAMES = new ArrayList<>();

    private static final List<Integer> EMPTY_PORT_NUMBERS = new ArrayList<>();

    @Inject Logger logger;

    @Inject KubeConfig kubeConfig;

    @Inject KubernetesClient client;

    @Inject Scheduler scheduler;

    @Inject EventBus bus;

    @Inject EntityManager entityManager;

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

    private final ReentrantReadWriteLock shutdownLock = new ReentrantReadWriteLock();

    private volatile boolean shuttingDown;

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
        shuttingDown = false;

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
        shuttingDown = true;

        var writeLock = shutdownLock.writeLock();
        writeLock.lock();
        try {
            if (!(enabled() && available())) {
                return;
            }
            logger.debugv("Shutting down {0} client", REALM);

            deleteResyncJobIfSchedulerRunning();
            safeGetInformers()
                    .forEach(
                            (ns, informer) -> {
                                informer.close();
                                logger.debugv(
                                        "Closed EndpointSlice SharedInformer for namespace \"{0}\"",
                                        ns);
                            });
        } finally {
            writeLock.unlock();
        }
    }

    void deleteResyncJobIfSchedulerRunning() {
        try {
            if (!scheduler.isShutdown()) {
                scheduler.deleteJob(RESYNC_JOB_KEY);
            }
        } catch (SchedulerException se) {
            logger.warn(se);
        }
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

    void setShuttingDown(boolean shuttingDown) {
        this.shuttingDown = shuttingDown;
    }

    boolean enterDiscoveryEventHandler() {
        if (shuttingDown) {
            return false;
        }

        var readLock = shutdownLock.readLock();
        readLock.lock();
        if (shuttingDown) {
            readLock.unlock();
            return false;
        }
        return true;
    }

    void exitDiscoveryEventHandler() {
        shutdownLock.readLock().unlock();
    }

    @Override
    public void onAdd(EndpointSlice slice) {
        if (shuttingDown) {
            logger.trace("Ignoring EndpointSlice add event during shutdown");
            return;
        }
        logger.debugv(
                "EndpointSlice {0} created in namespace {1}",
                slice.getMetadata().getName(), slice.getMetadata().getNamespace());
        notify(NamespaceQueryEvent.from(slice.getMetadata().getNamespace()));
    }

    @Override
    public void onUpdate(EndpointSlice oldSlice, EndpointSlice newSlice) {
        if (shuttingDown) {
            logger.trace("Ignoring EndpointSlice update event during shutdown");
            return;
        }
        logger.debugv(
                "EndpointSlice {0} modified in namespace {1}",
                newSlice.getMetadata().getName(), newSlice.getMetadata().getNamespace());
        notify(NamespaceQueryEvent.from(newSlice.getMetadata().getNamespace()));
    }

    @Override
    public void onDelete(EndpointSlice endpoints, boolean deletedFinalStateUnknown) {
        if (shuttingDown) {
            logger.trace("Ignoring EndpointSlice delete event during shutdown");
            return;
        }
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
        if (!enterDiscoveryEventHandler()) {
            logger.tracev("Ignoring EndpointSlice namespace query event during shutdown: {0}", evt);
            return;
        }
        try {
            for (var namespace : evt.namespaces) {
                try {
                    Set<Target> persistedTargets = queryPersistedTargets(namespace);

                    Map<TargetDTO, DiscoveryNodeDTO> observedTargetsWithHierarchy =
                            buildInMemoryTreeForNamespaceDTO(namespace);

                    Set<TargetDTO> observedTargetDtos = observedTargetsWithHierarchy.keySet();

                    Set<String> observedConnectUrls =
                            observedTargetDtos.stream()
                                    .map(TargetDTO::connectUrl)
                                    .collect(Collectors.toSet());

                    Set<String> persistedConnectUrls =
                            persistedTargets.stream()
                                    .map(t -> t.connectUrl.toString())
                                    .collect(Collectors.toSet());

                    // Find removed targets (in persisted but not in observed)
                    Set<Target> removedTargets =
                            persistedTargets.stream()
                                    .filter(
                                            t ->
                                                    !observedConnectUrls.contains(
                                                            t.connectUrl.toString()))
                                    .collect(Collectors.toSet());

                    // Find added targets (in observed but not in persisted)
                    Set<TargetDTO> addedTargetDtos =
                            observedTargetDtos.stream()
                                    .filter(dto -> !persistedConnectUrls.contains(dto.connectUrl()))
                                    .collect(Collectors.toSet());

                    logger.debugv(
                            "Namespace {0}: Found {1} persisted targets, {2} observed targets, {3}"
                                    + " removed targets, {4} added targets",
                            namespace,
                            persistedTargets.size(),
                            observedTargetDtos.size(),
                            removedTargets.size(),
                            addedTargetDtos.size());

                    removedTargets.forEach(
                            (t) -> {
                                logger.debugv(
                                        "Publishing LOST event for target: {0}", t.connectUrl);
                                notify(
                                        EndpointDiscoveryEvent.from(
                                                namespace, t, null, EventKind.LOST));
                            });

                    addedTargetDtos.forEach(
                            (targetDto) -> {
                                DiscoveryNodeDTO hierarchy =
                                        observedTargetsWithHierarchy.get(targetDto);
                                logger.debugv(
                                        "Publishing FOUND event for target: {0}",
                                        targetDto.connectUrl());
                                notify(
                                        EndpointDiscoveryEvent.from(
                                                namespace,
                                                null,
                                                null,
                                                EventKind.FOUND,
                                                targetDto,
                                                hierarchy));
                            });
                } catch (Exception e) {
                    logger.errorv(
                            e, "Failed to synchronize EndpointSlices in namespace {0}", namespace);
                }
            }
        } finally {
            exitDiscoveryEventHandler();
        }
    }

    @ConsumeEvent(value = ENDPOINT_SLICE_DISCOVERY_ADDR, blocking = true, ordered = true)
    @Transactional(TxType.REQUIRED)
    public void handleEndpointEvent(EndpointDiscoveryEvent evt) {
        if (!enterDiscoveryEventHandler()) {
            logger.tracev("Ignoring EndpointSlice discovery event during shutdown: {0}", evt);
            return;
        }
        try {
            String namespace = evt.namespace;
            DiscoveryNode realm = DiscoveryNode.getRealm(REALM).orElseThrow();
            realm =
                    entityManager.find(
                            DiscoveryNode.class, realm.id, LockModeType.PESSIMISTIC_WRITE);
            DiscoveryNode lockedRealm = realm;
            DiscoveryNode nsNode =
                    DiscoveryNode.getChild(lockedRealm, n -> n.name.equals(namespace))
                            .orElseGet(
                                    () -> {
                                        DiscoveryNode created =
                                                DiscoveryNode.environment(
                                                        namespace, KubeDiscoveryNodeType.NAMESPACE);
                                        created.parent = lockedRealm;
                                        created.persist();
                                        return created;
                                    });

            if (evt.eventKind == EventKind.FOUND) {
                if (evt.targetDto != null && evt.hierarchyRoot != null) {
                    logger.debugv("Persisting target from DTO: {0}", evt.targetDto.connectUrl());
                    persistOwnerChainFromDTO(nsNode, evt.targetDto, evt.hierarchyRoot);
                } else {
                    logger.warnv(
                            "FOUND event missing DTOs for target: {0}",
                            evt.target != null ? evt.target.connectUrl : "null");
                }
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
        } finally {
            exitDiscoveryEventHandler();
        }
    }

    private void notify(NamespaceQueryEvent evt) {
        if (shuttingDown) {
            logger.tracev("Ignoring EndpointSlice namespace query event during shutdown: {0}", evt);
            return;
        }
        bus.publish(NAMESPACE_QUERY_ADDR, evt);
    }

    private void notify(EndpointDiscoveryEvent evt) {
        if (shuttingDown) {
            logger.tracev("Ignoring EndpointSlice discovery event during shutdown: {0}", evt);
            return;
        }
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
                        k -> {
                            KubeDiscoveryNodeType nodeType =
                                    KubeDiscoveryNodeType.fromKubernetesKind(ref.getKind());
                            if (nodeType == null) {
                                return null;
                            }
                            return nodeType.getQueryFunction()
                                    .apply(client)
                                    .apply(ref.getNamespace())
                                    .apply(ref.getName());
                        });

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
     * Build in-memory discovery tree for all targets in a namespace using DTOs. This method does
     * NOT persist anything to the database - it only queries Kubernetes API and builds the tree
     * structure in memory using DTOs.
     *
     * @param namespace The Kubernetes namespace
     * @return Map of TargetDTO to DiscoveryNodeDTO containing the full ownership chain
     */
    private Map<TargetDTO, DiscoveryNodeDTO> buildInMemoryTreeForNamespaceDTO(String namespace) {
        Map<TargetDTO, DiscoveryNodeDTO> result = new HashMap<>();

        Stream<EndpointSlice> endpoints;
        if (kubeConfig.watchAllNamespaces()) {
            endpoints =
                    safeGetInformers().get(KubeConfig.ALL_NAMESPACES).getStore().list().stream()
                            .filter(
                                    ep ->
                                            Objects.equals(
                                                    ep.getMetadata().getNamespace(), namespace));
        } else {
            var informer = safeGetInformers().get(namespace);
            if (informer == null) {
                logger.warnv("No informer found for namespace: {0}", namespace);
                return result;
            }
            endpoints = informer.getStore().list().stream();
        }

        endpoints
                .map(this::getTargetTuplesFrom)
                .flatMap(List::stream)
                .filter((tuple) -> Objects.nonNull(tuple.objRef))
                .forEach(
                        (tuple) -> {
                            Target t = tuple.toTarget();
                            if (t != null) {
                                DiscoveryNodeDTO hierarchyRoot =
                                        buildOwnershipLineageForTargetDTO(tuple);
                                TargetDTO targetDTO =
                                        new TargetDTO(
                                                t.connectUrl.toString(),
                                                t.alias,
                                                new HashMap<>(t.labels),
                                                t.annotations,
                                                hierarchyRoot);
                                result.put(targetDTO, hierarchyRoot);
                            }
                        });

        return result;
    }

    /**
     * Builds the complete ownership lineage for a target using DTOs, from the leaf node
     * (EndpointSlice) through Pod, ReplicaSet, Deployment, up to the Namespace. This method
     * constructs the entire tree in memory without creating Hibernate entities.
     *
     * @param tuple The TargetTuple containing target information and Kubernetes references
     * @return The root DiscoveryNodeDTO of the ownership hierarchy (typically a Namespace or the
     *     highest owner)
     */
    private DiscoveryNodeDTO buildOwnershipLineageForTargetDTO(TargetTuple tuple) {
        String targetKind = tuple.objRef.getKind();
        KubeDiscoveryNodeType targetType = KubeDiscoveryNodeType.fromKubernetesKind(targetKind);

        DiscoveryNodeDTO targetNode = createInMemoryTargetNodeDTO(tuple);

        if (targetType == KubeDiscoveryNodeType.POD) {
            Pair<HasMetadata, DiscoveryNodeDTO> pod =
                    queryForNodeDTO(
                            tuple.objRef.getNamespace(),
                            tuple.objRef.getName(),
                            tuple.objRef.getKind());

            if (pod != null) {
                DiscoveryNodeDTO podNode = pod.getRight();
                List<DiscoveryNodeDTO> podChildren = new ArrayList<>(podNode.children());
                podChildren.add(targetNode);
                DiscoveryNodeDTO updatedPodNode =
                        new DiscoveryNodeDTO(
                                podNode.name(),
                                podNode.nodeType(),
                                podNode.labels(),
                                podChildren,
                                podNode.parent(),
                                podNode.existingId());

                DiscoveryNodeDTO rootNode =
                        buildOwnershipHierarchyDTO(pod.getLeft(), updatedPodNode);
                return rootNode;
            }
        }

        return targetNode;
    }

    /**
     * Creates an in-memory DiscoveryNodeDTO for a target without database access. The node name
     * includes the address and port to ensure uniqueness per endpoint.
     *
     * @param tuple The TargetTuple containing target information
     * @return DiscoveryNodeDTO representing the target
     */
    private DiscoveryNodeDTO createInMemoryTargetNodeDTO(TargetTuple tuple) {
        Map<String, String> labels = new HashMap<>();
        labels.put(DISCOVERY_NAMESPACE_LABEL_KEY, tuple.objRef.getNamespace());

        Target target = tuple.toTarget();
        String nodeName = target.connectUrl.toString();

        return new DiscoveryNodeDTO(
                nodeName,
                KubeDiscoveryNodeType.ENDPOINT_SLICE.getKind(),
                labels,
                new ArrayList<>(),
                null,
                null);
    }

    /**
     * Queries Kubernetes API for a node and creates a DiscoveryNodeDTO. Checks if the node exists
     * in the database and marks the DTO accordingly.
     *
     * @param namespace Kubernetes namespace
     * @param name Resource name
     * @param kind Resource kind
     * @return Pair of Kubernetes object and DiscoveryNodeDTO, or null if not found
     */
    private Pair<HasMetadata, DiscoveryNodeDTO> queryForNodeDTO(
            String namespace, String name, String kind) {

        KubeDiscoveryNodeType nodeType = KubeDiscoveryNodeType.fromKubernetesKind(kind);
        if (nodeType == null) {
            return null;
        }

        HasMetadata kubeObj =
                nodeType.getQueryFunction().apply(client).apply(namespace).apply(name);

        Map<String, String> labels = new HashMap<>();
        if (kubeObj != null && kubeObj.getMetadata().getLabels() != null) {
            labels.putAll(kubeObj.getMetadata().getLabels());
        }
        labels.put(DISCOVERY_NAMESPACE_LABEL_KEY, namespace);

        Long existingId = findExistingNodeId(namespace, name, nodeType.getKind());

        DiscoveryNodeDTO nodeDTO =
                new DiscoveryNodeDTO(
                        name, nodeType.getKind(), labels, new ArrayList<>(), null, existingId);

        return Pair.of(kubeObj, nodeDTO);
    }

    /**
     * Builds the ownership hierarchy for a given Kubernetes object by chasing owner references up
     * the chain using DTOs. This method does NOT persist any nodes or create Hibernate entities -
     * it only constructs the hierarchical relationships in memory using DTOs. Stops walking up the
     * hierarchy when reaching an existing node (common ancestor).
     *
     * @param kubeObj The Kubernetes object to start from
     * @param node The DiscoveryNodeDTO representing the Kubernetes object
     * @return The root DiscoveryNodeDTO of the ownership chain (the topmost owner)
     */
    private DiscoveryNodeDTO buildOwnershipHierarchyDTO(
            HasMetadata kubeObj, DiscoveryNodeDTO node) {
        Pair<HasMetadata, DiscoveryNodeDTO> current = Pair.of(kubeObj, node);

        while (true) {
            DiscoveryNodeDTO currentNode = current.getRight();

            if (currentNode.existsInDb()) {
                logger.debugv(
                        "Reached existing node in DB: {0}/{1} (id: {2}), stopping hierarchy walk",
                        currentNode.name(), currentNode.nodeType(), currentNode.existingId());
                break;
            }

            Pair<HasMetadata, DiscoveryNodeDTO> owner = getOwnerNodeDTO(current);
            if (owner == null) {
                break;
            }

            DiscoveryNodeDTO ownerNode = owner.getRight();
            DiscoveryNodeDTO childNode = currentNode;

            List<DiscoveryNodeDTO> ownerChildren = new ArrayList<>(ownerNode.children());
            if (!ownerChildren.contains(childNode)) {
                ownerChildren.add(childNode);
            }

            DiscoveryNodeDTO updatedOwnerNode =
                    new DiscoveryNodeDTO(
                            ownerNode.name(),
                            ownerNode.nodeType(),
                            ownerNode.labels(),
                            ownerChildren,
                            ownerNode.parent(),
                            ownerNode.existingId());

            current = Pair.of(owner.getLeft(), updatedOwnerNode);
        }

        return current.getRight();
    }

    /**
     * Gets the owner node for a given child node by querying Kubernetes API. Creates a
     * DiscoveryNodeDTO and checks if it exists in the database.
     *
     * @param child Pair of Kubernetes object and its DiscoveryNodeDTO
     * @return Pair of owner Kubernetes object and its DiscoveryNodeDTO, or null if no owner
     */
    private Pair<HasMetadata, DiscoveryNodeDTO> getOwnerNodeDTO(
            Pair<HasMetadata, DiscoveryNodeDTO> child) {
        HasMetadata childRef = child.getLeft();
        if (childRef == null) {
            return null;
        }
        List<OwnerReference> owners = childRef.getMetadata().getOwnerReferences();
        if (owners.isEmpty()) {
            return null;
        }
        String namespace = childRef.getMetadata().getNamespace();
        OwnerReference owner =
                owners.stream()
                        .filter(o -> KubeDiscoveryNodeType.fromKubernetesKind(o.getKind()) != null)
                        .findFirst()
                        .orElse(owners.get(0));
        return queryForNodeDTO(namespace, owner.getName(), owner.getKind());
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

    void pruneOwnerChain(DiscoveryNode nsNode, Target target) {
        logger.debugv("Pruning owner chain for target: {0}", target.connectUrl);
        try {
            Optional<Target> managedTargetOpt =
                    Target.<Target>find("connectUrl", target.connectUrl).singleResultOptional();

            if (managedTargetOpt.isEmpty()) {
                logger.debugv("Target already deleted: {0}", target.connectUrl);
                return;
            }

            Target managedTarget = managedTargetOpt.get();
            if (managedTarget.id == null) {
                logger.debugv("Target has no id: {0}", target.connectUrl);
                return;
            }

            logger.debugv(
                    "Found managed target with id {0}, starting pruning from discoveryNode {1}",
                    managedTarget.id,
                    managedTarget.discoveryNode != null ? managedTarget.discoveryNode.id : "null");

            DiscoveryNode endpointNode = managedTarget.discoveryNode;
            if (endpointNode == null || endpointNode.id == null) {
                logger.debugv("No discoveryNode for target: {0}", target.connectUrl);
                managedTarget.delete();
                return;
            }

            endpointNode = DiscoveryNode.findById(endpointNode.id);
            logger.debugv(
                    "Loaded discoveryNode {0} with parent {1}",
                    endpointNode.id, endpointNode.parent != null ? endpointNode.parent.id : "null");

            // Get the parent BEFORE deleting the target, because deletion will cascade
            DiscoveryNode child = endpointNode.parent;

            // Delete the target first - this will cascade delete its discoveryNode (the
            // Endpoint/EndpointSlice)
            managedTarget.delete();

            // If there's no parent, we're done
            if (child == null) {
                logger.debugv("No parent node to prune");
                return;
            }

            // Walk up the hierarchy, removing childless nodes from their parents
            // Rely on orphan removal to delete them, don't call delete() explicitly
            while (child != null) {
                DiscoveryNode parent = child.parent;

                if (parent == null) {
                    logger.debugv(
                            "Reached orphaned node {0} (id={1}), relying on orphan removal",
                            child.name, child.id);
                    break;
                }

                entityManager.refresh(parent); // Reload from DB with current children

                // Remove child from parent's collection - orphan removal will delete it
                parent.children.remove(child);
                child.parent = null;

                boolean hasChildren = parent.hasChildren();
                boolean isNamespace =
                        parent.nodeType.equals(KubeDiscoveryNodeType.NAMESPACE.getKind());
                logger.debugv(
                        "Parent node {0} (id={1}, type={2}): hasChildren={3}, isNamespace={4}",
                        parent.name, parent.id, parent.nodeType, hasChildren, isNamespace);

                if (hasChildren || isNamespace) {
                    logger.debugv("Stopping pruning at parent node {0}", parent.name);
                    break;
                }

                logger.debugv("Continuing to prune parent node {0}", parent.name);
                // Remove child from parent's collection - orphan removal will delete it
                parent.children.remove(child);
                child.parent = null;

                // Move up to check the parent too
                child = parent;
            }

            String namespace = nsNode.labels.get(DISCOVERY_NAMESPACE_LABEL_KEY);
            if (namespace != null) {
                logger.debugv("Cleaning up orphaned nodes in namespace: {0}", namespace);
                List<DiscoveryNode> orphans = findOrphanedNodesInNamespace(namespace);

                for (DiscoveryNode orphan : orphans) {
                    logger.debugv(
                            "Removing orphaned node: {0} (id: {1}, type: {2})",
                            orphan.name, orphan.id, orphan.nodeType);

                    DiscoveryNode orphanParent = orphan.parent;
                    if (orphanParent != null) {
                        entityManager.refresh(orphanParent);
                        orphanParent.children.remove(orphan);
                        orphan.parent = null;
                    }

                    orphan.delete();
                }

                if (!orphans.isEmpty()) {
                    logger.debugv(
                            "Removed {0} orphaned nodes from namespace {1}",
                            orphans.size(), namespace);
                }
            }

            nsNode.persist();

        } catch (EntityNotFoundException e) {
            logger.debugv("Target was deleted during pruning operation: {0}", target.connectUrl, e);
            nsNode.persist();
        }
    }

    /**
     * Persists the node chain to the database. This method works with pre-converted entities that
     * are already linked in parent-child relationships by convertDtoTreeToEntities().
     *
     * <p>The method:
     *
     * <ol>
     *   <li>Walks up from leaf to root, collecting nodes
     *   <li>Persists from root to leaf (to satisfy foreign key constraints)
     *   <li>Persists the Target last
     *   <li>Ensures namespace and realm are persisted
     * </ol>
     *
     * @param nsNode The namespace node
     * @param target The target to persist
     * @param targetNode The leaf node (EndpointSlice wrapper)
     */
    void persistNodeChain(DiscoveryNode nsNode, Target target, DiscoveryNode targetNode) {
        logger.debugv("Persisting node chain for target: {0}", target.connectUrl);

        // Collect the node chain from leaf to root
        List<DiscoveryNode> nodeChain = collectNodeChain(targetNode, nsNode);

        // Persist nodes from parent to child (root to leaf)
        // This ensures foreign key constraints are satisfied
        for (int i = nodeChain.size() - 1; i >= 0; i--) {
            DiscoveryNode node = nodeChain.get(i);

            if (node.id != null) {
                // Node already exists in DB - persist any relationship changes
                logger.debugv(
                        "Node already persisted, persisting updates: {0} (id: {1})",
                        node.name, node.id);
                node.persist();
            } else {
                // Persist new nodes
                logger.debugv("Persisting new node: {0} (type: {1})", node.name, node.nodeType);
                node.persist();
            }
        }

        // Persist namespace node to save any relationship changes
        logger.debugv("Persisting namespace node: {0} (id: {1})", nsNode.name, nsNode.id);
        nsNode.persist();

        // Flush to ensure all DiscoveryNode changes are written to DB
        // This prevents other transactions from seeing stale/incomplete entities
        entityManager.flush();
        logger.debugv("Flushed DiscoveryNode changes to database");

        // Persist the Target last (after all nodes are persisted and flushed)
        logger.debugv("Persisting target: {0}", target.connectUrl);
        target.persist();

        logger.debugv("Node chain persistence complete for target: {0}", target.connectUrl);
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
     * Finds orphaned nodes in a namespace that have no children and no associated Target. These are
     * intermediate nodes (like Pods, Deployments, etc.) that were created during hierarchy building
     * but are no longer needed.
     *
     * @param namespace The namespace to search for orphaned nodes
     * @return List of orphaned DiscoveryNodes
     */
    List<DiscoveryNode> findOrphanedNodesInNamespace(String namespace) {
        List<DiscoveryNode> orphans =
                entityManager
                        .createNativeQuery(FIND_ORPHANED_NODES_SQL, DiscoveryNode.class)
                        .setParameter("namespaceKey", DISCOVERY_NAMESPACE_LABEL_KEY)
                        .setParameter("namespace", namespace)
                        .setParameter("namespaceType", KubeDiscoveryNodeType.NAMESPACE.getKind())
                        .getResultList();

        logger.debugv("Found {0} orphaned nodes in namespace {1}", orphans.size(), namespace);
        return orphans;
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
     * Queries Kubernetes API for a node and retrieves existing DiscoveryNode from the database.
     * This is a pure query method that NEVER persists nodes.
     *
     * @param namespace Kubernetes namespace
     * @param name Resource name
     * @param kind Resource kind
     * @return Pair of Kubernetes object and existing DiscoveryNode from database, or null if K8s
     *     object not found
     */
    Pair<HasMetadata, DiscoveryNode> queryForNodeReadOnly(
            String namespace, String name, String kind) {

        KubeDiscoveryNodeType nodeType = KubeDiscoveryNodeType.fromKubernetesKind(kind);
        if (nodeType == null) {
            logger.debugv("Unknown Kubernetes kind: {0}", kind);
            return null;
        }

        HasMetadata kubeObj =
                nodeType.getQueryFunction().apply(client).apply(namespace).apply(name);

        if (kubeObj == null) {
            logger.debugv(
                    "Kubernetes object not found: {0}/{1} (kind: {2})", namespace, name, kind);
            return null;
        }

        DiscoveryNode existingNode =
                DiscoveryNode.<DiscoveryNode>find(
                                "#DiscoveryNode.byTypeWithName",
                                Parameters.with("nodeType", nodeType.getKind()).and("name", name))
                        .stream()
                        .filter(
                                n ->
                                        namespace.equals(
                                                        n.labels.get(DISCOVERY_NAMESPACE_LABEL_KEY))
                                                && isInKubernetesApiRealm(n))
                        .findFirst()
                        .orElse(null);

        logger.debugv(
                "Query read-only for {0}/{1} (kind: {2}): found in DB = {3}",
                namespace, name, kind, existingNode != null);

        return Pair.of(kubeObj, existingNode);
    }

    /**
     * Check if a node belongs to the KubernetesApi Realm by walking up its parent chain.
     *
     * @param node The node to check
     * @return true if the node is in the KubernetesApi Realm, false otherwise
     */
    private boolean isInKubernetesApiRealm(DiscoveryNode node) {
        DiscoveryNode current = node;
        while (current != null) {
            if (current.nodeType != null
                    && current.nodeType.equals(NodeType.BaseNodeType.REALM.getKind())
                    && REALM.equals(current.name)) {
                return true;
            }
            current = current.parent;
        }
        return false;
    }

    /**
     * Retrieves Kubernetes labels for a given resource.
     *
     * @param namespace Kubernetes namespace
     * @param name Resource name
     * @param nodeType Resource type (e.g., "Pod", "Deployment")
     * @return Map of Kubernetes labels, or empty map if resource not found or has no labels
     */
    public Map<String, String> getKubernetesLabels(String namespace, String name, String nodeType) {
        return getKubernetesMetadata(namespace, name, nodeType).labels();
    }

    /**
     * Retrieves Kubernetes metadata (labels and annotations) for a given resource.
     *
     * @param namespace Kubernetes namespace
     * @param name Resource name
     * @param nodeType Resource type (e.g., "Pod", "Deployment")
     * @return KubernetesMetadata containing labels and annotations, or empty maps if resource not
     *     found
     */
    public KubernetesMetadata getKubernetesMetadata(
            String namespace, String name, String nodeType) {
        Map<String, String> labels = new HashMap<>();
        Map<String, String> annotations = new HashMap<>();
        try {
            Pair<HasMetadata, DiscoveryNode> kubeResource =
                    queryForNodeReadOnly(namespace, name, nodeType);
            if (kubeResource != null && kubeResource.getLeft() != null) {
                HasMetadata metadata = kubeResource.getLeft();
                if (metadata.getMetadata() != null) {
                    if (metadata.getMetadata().getLabels() != null) {
                        labels.putAll(metadata.getMetadata().getLabels());
                    }
                    if (metadata.getMetadata().getAnnotations() != null) {
                        annotations.putAll(metadata.getMetadata().getAnnotations());
                    }
                    logger.debugv(
                            "Retrieved {0} labels and {1} annotations for {2}/{3}",
                            labels.size(), annotations.size(), namespace, name);
                }
            }
        } catch (Exception e) {
            logger.warnv(e, "Failed to retrieve Kubernetes metadata for {0}/{1}", namespace, name);
        }
        return new KubernetesMetadata(labels, annotations);
    }

    /**
     * Queries the database to find an existing DiscoveryNode by composite ID. A DiscoveryNode is
     * uniquely identified by: name + nodeType + namespace label.
     *
     * @param namespace The namespace from the discovery.cryostat.io/namespace label
     * @param name The node name
     * @param nodeType The node type (e.g., "Pod", "Deployment")
     * @return The node ID if found, null otherwise
     */
    private Long findExistingNodeId(String namespace, String name, String nodeType) {
        try {
            Object result =
                    entityManager
                            .createNativeQuery(FIND_NAMESPACED_NODE_SQL)
                            .setParameter("name", name)
                            .setParameter("nodeType", nodeType)
                            .setParameter("namespace", namespace)
                            .getResultStream()
                            .findFirst()
                            .orElse(null);
            return result != null ? ((Number) result).longValue() : null;
        } catch (Exception e) {
            logger.errorv(
                    e,
                    "Error finding existing node: name={0}, type={1}, namespace={2}",
                    name,
                    nodeType,
                    namespace);
            return null;
        }
    }

    /**
     * Converts a DiscoveryNodeDTO tree to DiscoveryNode entities. This is the bridge between the
     * DTO world (in-memory tree building) and the entity world (persistence).
     *
     * <p>This method walks the DTO tree from root to leaf (depth-first) and:
     *
     * <ul>
     *   <li>If existsInDb=true: Loads existing entity using DiscoveryNode.findById()
     *   <li>If existsInDb=false: Creates new DiscoveryNode() entity
     *   <li>Builds parent-child relationships bidirectionally
     *   <li>Ensures the topmost new node links to the namespace node
     * </ul>
     *
     * @param rootDto The root of the DTO tree to convert
     * @param nsNode The namespace node to link the topmost new node to
     * @return The leaf node (EndpointSlice wrapper) entity
     */
    private DiscoveryNode convertDtoTreeToEntities(DiscoveryNodeDTO rootDto, DiscoveryNode nsNode) {
        logger.debugv("Converting DTO tree to entities, root: {0}", rootDto.name());

        // Walk the tree depth-first, converting DTOs to entities
        // This returns the root of the converted tree
        DiscoveryNode rootEntity = convertDtoNodeToEntity(rootDto, nsNode, false);

        // Find the topmost node in the chain (the one without a parent)
        DiscoveryNode topmost = rootEntity;
        while (topmost.parent != null && !topmost.parent.equals(nsNode)) {
            topmost = topmost.parent;
        }

        // Ensure the topmost node is linked to the namespace
        if (topmost.parent == null || !topmost.parent.equals(nsNode)) {
            topmost.parent = nsNode;
            logger.debugv(
                    "Set parent of topmost node {0} to namespace {1}", topmost.name, nsNode.name);
        }

        // Add to namespace children if not already present
        final Long topmostId = topmost.id;
        final DiscoveryNode topmostNode = topmost;
        boolean alreadyChild =
                nsNode.children.stream()
                        .anyMatch(
                                child ->
                                        child == topmostNode
                                                || (child.id != null
                                                        && topmostId != null
                                                        && child.id.equals(topmostId)));
        if (!alreadyChild) {
            nsNode.children.add(topmost);
            logger.debugv(
                    "Added topmost node {0} to namespace {1} children", topmost.name, nsNode.name);
        }

        // Find and return the leaf node (EndpointSlice wrapper) by walking down from rootEntity
        // We need to walk down the DTO tree structure, not the entity children collection,
        // because the entity children may include nodes from previous discoveries
        DiscoveryNode leafNode = findLeafNodeFromDto(rootDto, rootEntity);

        logger.debugv("DTO tree conversion complete, leaf node: {0}", leafNode.name);
        return leafNode;
    }

    /**
     * Finds the leaf node entity that corresponds to the leaf of the DTO tree. This walks down the
     * DTO tree structure to find the correct leaf, rather than using the entity's children
     * collection which may contain nodes from previous discoveries.
     *
     * @param dto The DTO node to search from
     * @param entity The corresponding entity node
     * @return The leaf node entity
     */
    private DiscoveryNode findLeafNodeFromDto(DiscoveryNodeDTO dto, DiscoveryNode entity) {
        // If this DTO has no children, this entity is the leaf
        if (dto.children().isEmpty()) {
            return entity;
        }

        // Otherwise, find the child entity that matches the first DTO child
        DiscoveryNodeDTO childDto = dto.children().get(0);

        // Find the matching child entity by name and type
        DiscoveryNode childEntity =
                entity.children.stream()
                        .filter(
                                child ->
                                        child.name.equals(childDto.name())
                                                && child.nodeType.equals(childDto.nodeType()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Could not find child entity for DTO: "
                                                        + childDto.name()));

        // Recursively search down the tree
        return findLeafNodeFromDto(childDto, childEntity);
    }

    /**
     * Recursively converts a single DiscoveryNodeDTO to a DiscoveryNode entity, including all its
     * children.
     *
     * @param dto The DTO to convert
     * @param nsNode The namespace node (used for linking the topmost new node)
     * @param isRoot Whether this is the root node of the tree
     * @return The converted entity
     */
    private DiscoveryNode convertDtoNodeToEntity(
            DiscoveryNodeDTO dto, DiscoveryNode nsNode, boolean isRoot) {
        final DiscoveryNode entity;

        if (dto.existsInDb()) {
            // Load existing entity from database using Panache
            logger.debugv(
                    "Loading existing node from DB: {0} (id: {1})", dto.name(), dto.existingId());
            DiscoveryNode loaded = DiscoveryNode.findById(dto.existingId());

            if (loaded == null) {
                logger.warnv(
                        "Node marked as existing but not found in DB: {0} (id: {1}), creating new"
                                + " node",
                        dto.name(), dto.existingId());
                entity = createNewNodeEntity(dto);
            } else {
                entity = loaded;
            }
        } else {
            // Create new entity
            logger.debugv("Creating new node entity: {0} (type: {1})", dto.name(), dto.nodeType());
            entity = createNewNodeEntity(dto);
        }

        // Convert children recursively
        for (DiscoveryNodeDTO childDto : dto.children()) {
            DiscoveryNode childEntity = convertDtoNodeToEntity(childDto, nsNode, false);

            // Build bidirectional relationship
            childEntity.parent = entity;

            boolean childExists =
                    entity.children.stream()
                            .anyMatch(
                                    existing ->
                                            existing == childEntity
                                                    || (existing.id != null
                                                            && existing.id.equals(childEntity.id)));
            if (!childExists) {
                entity.children.add(childEntity);
            }
        }

        return entity;
    }

    /**
     * Creates a new DiscoveryNode entity from a DTO.
     *
     * @param dto The DTO to convert
     * @return A new DiscoveryNode entity
     */
    private DiscoveryNode createNewNodeEntity(DiscoveryNodeDTO dto) {
        DiscoveryNode node = new DiscoveryNode();
        node.name = dto.name();
        node.nodeType = dto.nodeType();
        node.labels = new HashMap<>(dto.labels());
        node.children = new ArrayList<>();
        node.target = null;
        return node;
    }

    /**
     * Persists the ownership chain from DTOs.
     *
     * <p>This method:
     *
     * <ol>
     *   <li>Converts the DTO tree to entities using convertDtoTreeToEntities()
     *   <li>Gets the leaf node (EndpointSlice wrapper) from conversion
     *   <li>Creates the Target entity from TargetDTO
     *   <li>Links Target to its DiscoveryNode
     *   <li>Calls persistNodeChain() with the converted entities
     * </ol>
     *
     * @param nsNode The namespace node
     * @param targetDto The target DTO to persist
     * @param hierarchyRoot The root of the DTO hierarchy
     */
    private void persistOwnerChainFromDTO(
            DiscoveryNode nsNode, TargetDTO targetDto, DiscoveryNodeDTO hierarchyRoot) {
        logger.debugv("Persisting owner chain from DTO for target: {0}", targetDto.connectUrl());

        URI connectUrl = URI.create(targetDto.connectUrl());

        Optional<Target> existingTarget =
                Target.<Target>find("connectUrl", connectUrl).singleResultOptional();

        if (existingTarget.isPresent()) {
            logger.debugv(
                    "Target already exists for connectUrl: {0} (id: {1}), skipping persistence",
                    connectUrl, existingTarget.get().id);
            return;
        }

        logger.debugv("No existing target found for connectUrl: {0}, creating new", connectUrl);

        // Convert the DTO tree to entities
        DiscoveryNode leafNode = convertDtoTreeToEntities(hierarchyRoot, nsNode);

        // Create the Target entity from DTO
        Target target = new Target();
        target.connectUrl = connectUrl;
        target.alias = targetDto.alias();
        target.labels = new HashMap<>(targetDto.labels());
        target.annotations = targetDto.annotations();

        // Link Target to its DiscoveryNode
        target.discoveryNode = leafNode;
        leafNode.target = target;

        logger.debugv("Calling persistNodeChain for target: {0}", target.connectUrl);

        // Persist the node chain
        persistNodeChain(nsNode, target, leafNode);
    }

    @DisallowConcurrentExecution
    static class EndpointsResyncJob implements Job {
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

    /**
     * Container for Kubernetes metadata (labels and annotations).
     *
     * @param labels Kubernetes labels
     * @param annotations Kubernetes annotations
     */
    public static record KubernetesMetadata(
            Map<String, String> labels, Map<String, String> annotations) {
        public KubernetesMetadata(Map<String, String> labels, Map<String, String> annotations) {
            this.labels = Collections.unmodifiableMap(new HashMap<>(labels));
            this.annotations = Collections.unmodifiableMap(new HashMap<>(annotations));
        }
    }

    static record NamespaceQueryEvent(Collection<String> namespaces) {
        static NamespaceQueryEvent from(Collection<String> namespaces) {
            return new NamespaceQueryEvent(namespaces);
        }

        static NamespaceQueryEvent from(String namespace) {
            return new NamespaceQueryEvent(List.of(namespace));
        }
    }

    static record EndpointDiscoveryEvent(
            String namespace,
            Target target,
            ObjectReference objRef,
            EventKind eventKind,
            TargetDTO targetDto,
            DiscoveryNodeDTO hierarchyRoot) {
        static EndpointDiscoveryEvent from(
                String namespace, Target target, ObjectReference objRef, EventKind eventKind) {
            return new EndpointDiscoveryEvent(namespace, target, objRef, eventKind, null, null);
        }

        static EndpointDiscoveryEvent from(
                String namespace,
                Target target,
                ObjectReference objRef,
                EventKind eventKind,
                TargetDTO targetDto,
                DiscoveryNodeDTO hierarchyRoot) {
            return new EndpointDiscoveryEvent(
                    namespace, target, objRef, eventKind, targetDto, hierarchyRoot);
        }
    }

    /**
     * Represents a Target before it becomes a Hibernate entity. Used to build the discovery tree in
     * memory without creating entities.
     */
    record TargetDTO(
            String connectUrl,
            String alias,
            Map<String, String> labels,
            Target.Annotations annotations,
            DiscoveryNodeDTO discoveryNode) {}

    /**
     * Represents a DiscoveryNode before it becomes a Hibernate entity. Used to build the discovery
     * tree in memory without creating entities. The existingId holds the database ID if this node
     * already exists in the database (null if it's a new node).
     */
    record DiscoveryNodeDTO(
            String name,
            String nodeType,
            Map<String, String> labels,
            List<DiscoveryNodeDTO> children,
            DiscoveryNodeDTO parent,
            Long existingId) {
        /**
         * Returns true if this node already exists in the database.
         *
         * @return true if existingId is not null, false otherwise
         */
        boolean existsInDb() {
            return existingId != null;
        }
    }

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
