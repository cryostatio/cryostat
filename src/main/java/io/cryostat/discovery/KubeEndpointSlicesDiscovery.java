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
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.concurrent.ConcurrentException;
import org.apache.commons.lang3.concurrent.LazyInitializer;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
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
                                                .inform(
                                                        KubeEndpointSlicesDiscovery.this,
                                                        informerResyncPeriod.toMillis()))
                                .exceptionHandler(
                                        (b, t) -> {
                                            logger.warn(t);
                                            return true;
                                        });
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
                                                                    .inform(
                                                                            KubeEndpointSlicesDiscovery
                                                                                    .this,
                                                                            informerResyncPeriod
                                                                                    .toMillis()))
                                                    .exceptionHandler(
                                                            (b, t) -> {
                                                                logger.warn(t);
                                                                return true;
                                                            });
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
                                .withIdentity("force-resync", "kube-endpoints-discovery")
                                .usingJobData(dataMap)
                                .build();
                var trigger =
                        TriggerBuilder.newTrigger()
                                .usingJobData(jobDetail.getJobDataMap())
                                .withIdentity(
                                        jobDetail.getKey().getName(), jobDetail.getKey().getGroup())
                                .startNow()
                                .withSchedule(
                                        SimpleScheduleBuilder.simpleSchedule()
                                                .repeatForever()
                                                .withIntervalInSeconds(
                                                        (int) informerResyncPeriod.toSeconds()))
                                .build();
                scheduler.scheduleJob(jobDetail, trigger);
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

    private boolean isCompatiblePort(EndpointPort port) {
        return jmxPortNames.orElse(EMPTY_PORT_NAMES).contains(port.getName())
                || jmxPortNumbers.orElse(EMPTY_PORT_NUMBERS).contains(port.getPort());
    }

    List<TargetTuple> tuplesFromEndpoints(EndpointSlice slice) {
        List<TargetTuple> tts = new ArrayList<>();
        if (!ipv6Enabled && "ipv6".equalsIgnoreCase(slice.getAddressType())) {
            return tts;
        }
        List<EndpointPort> ports = slice.getPorts();
        if (ports == null) {
            return tts;
        }
        for (EndpointPort port : ports) {
            List<Endpoint> endpoints = slice.getEndpoints();
            if (endpoints == null) {
                continue;
            }
            for (Endpoint endpoint : endpoints) {
                List<String> addresses = endpoint.getAddresses();
                if (addresses == null || addresses.isEmpty()) {
                    continue;
                }
                // the EndpointSlice specification states that all of the
                // addresses are fungible, ie interchangeable - they will
                // resolve to the same Pod. So, we only need to worry about the
                // first one.
                String addr = addresses.get(0);
                var ref = endpoint.getTargetRef();
                if (ref == null) {
                    continue;
                }
                switch (slice.getAddressType().toLowerCase()) {
                    case "ipv6":
                        addr = String.format("[%s]", addr);
                        break;
                    case "ipv4":
                        if (ipv4TransformEnabled) {
                            addr =
                                    String.format(
                                            "%s.%s.pod",
                                            addr.replaceAll("\\.", "-"), ref.getNamespace());
                        }
                        break;
                    case "fqdn":
                    // fall-through
                    default:
                        // no-op
                        break;
                }
                tts.add(
                        new TargetTuple(
                                ref,
                                queryForNode(ref.getNamespace(), ref.getName(), ref.getKind())
                                        .getLeft(),
                                addr,
                                port,
                                endpoint.getConditions()));
            }
        }
        return tts;
    }

    private List<TargetTuple> getTargetTuplesFrom(EndpointSlice slice) {
        return tuplesFromEndpoints(slice).stream()
                .filter(
                        (ref) -> {
                            return Objects.nonNull(ref) && isCompatiblePort(ref.port);
                        })
                .collect(Collectors.toList());
    }

    private Map<String, SharedIndexInformer<EndpointSlice>> safeGetInformers() {
        try {
            return nsInformers.get();
        } catch (ConcurrentException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean isTargetUnderRealm(Target target) throws IllegalStateException {
        // Check for any targets with the same connectUrl in other realms
        try {
            Target persistedTarget = Target.getTargetByConnectUrl(target.connectUrl);
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

    @ConsumeEvent(value = NAMESPACE_QUERY_ADDR, blocking = true, ordered = true)
    @Transactional(TxType.REQUIRES_NEW)
    public void handleQueryEvent(NamespaceQueryEvent evt) {
        Map<URI, ObjectReference> targetRefMap = new HashMap<>();

        for (var namespace : evt.namespaces) {
            try {
                List<DiscoveryNode> targetNodes =
                        DiscoveryNode.findAllByNodeType(KubeDiscoveryNodeType.ENDPOINT_SLICE)
                                .stream()
                                .filter(
                                        (n) ->
                                                namespace.equals(
                                                        n.labels.get(
                                                                DISCOVERY_NAMESPACE_LABEL_KEY)))
                                .collect(Collectors.toList());

                Set<Target> persistedTargets = new HashSet<>();
                for (DiscoveryNode node : targetNodes) {
                    persistedTargets.add(node.target);
                }

                Stream<EndpointSlice> endpoints;
                if (kubeConfig.watchAllNamespaces()) {
                    endpoints =
                            safeGetInformers()
                                    .get(KubeConfig.ALL_NAMESPACES)
                                    .getStore()
                                    .list()
                                    .stream()
                                    .filter(
                                            ep ->
                                                    Objects.equals(
                                                            ep.getMetadata().getNamespace(),
                                                            namespace));
                } else {
                    endpoints = safeGetInformers().get(namespace).getStore().list().stream();
                }
                Set<Target> observedTargets =
                        endpoints
                                .map(this::getTargetTuplesFrom)
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

                // Prune deleted targets
                Target.compare(persistedTargets)
                        .to(observedTargets)
                        .removed()
                        .forEach(
                                (t) ->
                                        notify(
                                                EndpointDiscoveryEvent.from(
                                                        namespace, t, null, EventKind.LOST)));

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
            } catch (Exception e) {
                logger.errorv(e, "Failed to syncronize EndpointSlices in namespace {0}", namespace);
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
    }

    private void notify(NamespaceQueryEvent evt) {
        bus.publish(NAMESPACE_QUERY_ADDR, evt);
    }

    private void notify(EndpointDiscoveryEvent evt) {
        bus.publish(ENDPOINT_SLICE_DISCOVERY_ADDR, evt);
    }

    private void pruneOwnerChain(DiscoveryNode nsNode, Target target) {
        if (!isTargetUnderRealm(target)) {
            logger.debugv(
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
        if (isTargetUnderRealm(target)) {
            logger.debugv(
                    "Target with serviceURL {0} already exists in discovery tree. Skipped adding",
                    target.connectUrl);
            return;
        }
        String targetKind = targetRef.getKind();
        KubeDiscoveryNodeType targetType = KubeDiscoveryNodeType.fromKubernetesKind(targetKind);

        DiscoveryNode targetNode =
                DiscoveryNode.target(
                        target,
                        KubeDiscoveryNodeType.ENDPOINT_SLICE,
                        n -> {
                            n.labels.putAll(
                                    Map.of(
                                            DISCOVERY_NAMESPACE_LABEL_KEY,
                                            targetRef.getNamespace()));
                        });
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
                DiscoveryNode.byTypeWithName(
                        nodeType,
                        name,
                        n -> namespace.equals(n.labels.get(DISCOVERY_NAMESPACE_LABEL_KEY)),
                        n -> {
                            Map<String, String> labels =
                                    kubeObj != null
                                            ? kubeObj.getMetadata().getLabels()
                                            : new HashMap<>();
                            // Add namespace to label to retrieve node later
                            labels.put(DISCOVERY_NAMESPACE_LABEL_KEY, namespace);
                            n.labels.putAll(labels);
                        });
        return Pair.of(kubeObj, node);
    }

    private static class EndpointsResyncJob implements Job {
        @Inject Logger logger;
        @Inject EventBus bus;

        @SuppressWarnings("unchecked")
        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            try {
                Collection<String> namespaces =
                        (Collection<String>)
                                context.getJobDetail().getJobDataMap().get("namespaces");
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

    private class TargetTuple {
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
