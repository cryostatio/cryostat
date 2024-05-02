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

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.management.remote.JMXServiceURL;

import io.cryostat.ConfigProperties;
import io.cryostat.URIUtil;
import io.cryostat.core.net.JFRConnectionToolkit;
import io.cryostat.core.sys.FileSystem;
import io.cryostat.targets.Target;
import io.cryostat.targets.Target.Annotations;
import io.cryostat.targets.Target.EventKind;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.security.auth.module.UnixSystem;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.http.HttpMethod;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.core.net.SocketAddress;
import io.vertx.mutiny.ext.web.client.WebClient;
import io.vertx.mutiny.ext.web.codec.BodyCodec;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.NoResultException;
import jakarta.resource.spi.IllegalStateException;
import jakarta.transaction.Transactional;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
class PodmanDiscovery extends ContainerDiscovery {
    @ConfigProperty(name = "cryostat.discovery.podman.enabled")
    boolean enabled;

    @Override
    public String getRealm() {
        return "Podman";
    }

    @Override
    protected SocketAddress getSocket() {
        long uid = new UnixSystem().getUid();
        return SocketAddress.domainSocketAddress(
                String.format("/run/user/%d/podman/podman.sock", uid));
    }

    @Override
    protected String getContainersQueryURL() {
        return "http://d/v3.0.0/libpod/containers/json";
    }

    @Override
    protected String getContainerQueryURL(ContainerSpec spec) {
        return String.format("http://d/v3.0.0/libpod/containers/%s/json", spec.Id());
    }

    @Override
    protected boolean enabled() {
        return enabled;
    }

    @ConsumeEvent(blocking = true, ordered = true)
    @Transactional
    protected void handleContainerEvent(ContainerDiscoveryEvent evt) {
        try {
            updateDiscoveryTree(evt);
        } catch (IllegalStateException e) {
            logger.warn(e);
        }
    }

    @Override
    protected String notificationAddress() {
        return PodmanDiscovery.class.getName();
    }
}

@ApplicationScoped
class DockerDiscovery extends ContainerDiscovery {
    @ConfigProperty(name = "cryostat.discovery.docker.enabled")
    boolean enabled;

    @Override
    public String getRealm() {
        return "Docker";
    }

    @Override
    protected SocketAddress getSocket() {
        return SocketAddress.domainSocketAddress(String.format("/var/run/docker.sock"));
    }

    @Override
    protected String getContainersQueryURL() {
        return "http://d/v1.42/containers/json";
    }

    @Override
    protected String getContainerQueryURL(ContainerSpec spec) {
        return String.format("http://d/v1.42/containers/%s/json", spec.Id());
    }

    @Override
    protected boolean enabled() {
        return enabled;
    }

    @ConsumeEvent(blocking = true, ordered = true)
    @Transactional
    protected void handleContainerEvent(ContainerDiscoveryEvent evt) {
        try {
            updateDiscoveryTree(evt);
        } catch (IllegalStateException e) {
            logger.warn(e);
        }
    }

    @Override
    protected String notificationAddress() {
        return DockerDiscovery.class.getName();
    }
}

public abstract class ContainerDiscovery {
    public static final String DISCOVERY_LABEL = "io.cryostat.discovery";
    public static final String JMX_URL_LABEL = "io.cryostat.jmxUrl";
    public static final String JMX_HOST_LABEL = "io.cryostat.jmxHost";
    public static final String JMX_PORT_LABEL = "io.cryostat.jmxPort";

    @Inject Logger logger;
    @Inject FileSystem fs;
    @Inject Vertx vertx;
    @Inject WebClient webClient;
    @Inject JFRConnectionToolkit connectionToolkit;
    @Inject ObjectMapper mapper;
    @Inject EventBus bus;

    @ConfigProperty(name = ConfigProperties.CONTAINERS_POLL_PERIOD)
    Duration pollPeriod;

    @ConfigProperty(name = ConfigProperties.CONTAINERS_REQUEST_TIMEOUT)
    Duration requestTimeout;

    protected long timerId;

    // Priority is set higher than default 0 such that onStart is called first before onAfterStart
    // This ensures realm node is persisted before querying for containers
    @Transactional
    void onStart(@Observes @Priority(1) StartupEvent evt) {
        if (!enabled()) {
            return;
        }

        if (!available()) {
            logger.errorv(
                    "{0} enabled but socket {1} is not accessible!",
                    getClass().getName(), Path.of(getSocket().path()));
            return;
        }

        DiscoveryNode universe = DiscoveryNode.getUniverse();
        if (DiscoveryNode.getRealm(getRealm()).isEmpty()) {
            DiscoveryPlugin plugin = new DiscoveryPlugin();
            DiscoveryNode node = DiscoveryNode.environment(getRealm(), BaseNodeType.REALM);
            plugin.realm = node;
            plugin.builtin = true;
            universe.children.add(node);
            node.parent = universe;
            plugin.persist();
            universe.persist();
        }

        logger.infov("Starting {0} client", getRealm());
    }

    void onAfterStart(@Observes StartupEvent evt) {
        if (!(enabled() && available())) {
            return;
        }

        queryContainers();
        this.timerId = vertx.setPeriodic(pollPeriod.toMillis(), unused -> queryContainers());
    }

    void onStop(@Observes ShutdownEvent evt) {
        if (!(enabled() && available())) {
            return;
        }
        logger.infov("Shutting down {0} client", getRealm());
        vertx.cancelTimer(timerId);
    }

    boolean available() {
        Path socketPath = Path.of(getSocket().path());
        return fs.exists(socketPath) && fs.isReadable(socketPath);
    }

    private Target toTarget(ContainerSpec desc) {
        URI connectUrl;
        String hostname;
        int jmxPort;
        try {
            JMXServiceURL serviceUrl;
            URI rmiTarget;
            if (desc.Labels.containsKey(JMX_URL_LABEL)) {
                serviceUrl = new JMXServiceURL(desc.Labels.get(JMX_URL_LABEL));
                connectUrl = URI.create(serviceUrl.toString());
                try {
                    rmiTarget = URIUtil.getRmiTarget(serviceUrl);
                    hostname = rmiTarget.getHost();
                    jmxPort = rmiTarget.getPort();
                } catch (IllegalArgumentException e) {
                    hostname = serviceUrl.getHost();
                    jmxPort = serviceUrl.getPort();
                }
            } else {
                jmxPort = Integer.parseInt(desc.Labels.get(JMX_PORT_LABEL));
                hostname = desc.Labels.get(JMX_HOST_LABEL);
                if (hostname == null) {
                    try {
                        hostname =
                                doContainerInspectRequest(desc)
                                        .get(2, TimeUnit.SECONDS)
                                        .Config
                                        .Hostname;
                    } catch (InterruptedException | TimeoutException | ExecutionException e) {
                        logger.warnv(e, "Invalid {0} target observed", getRealm());
                        return null;
                    }
                }
            }
            serviceUrl = connectionToolkit.createServiceURL(hostname, jmxPort);
            connectUrl = URI.create(serviceUrl.toString());
        } catch (MalformedURLException | URISyntaxException e) {
            logger.warnv(e, "Invalid {0} target observed", getRealm());
            return null;
        }

        Target target = new Target();
        target.activeRecordings = new ArrayList<>();
        target.connectUrl = connectUrl;
        target.alias = Optional.ofNullable(desc.Names.get(0)).orElse(desc.Id);
        target.labels = desc.Labels;
        target.annotations = new Annotations();
        target.annotations
                .cryostat()
                .putAll(
                        Map.of(
                                "REALM", // AnnotationKey.REALM,
                                getRealm(),
                                "HOST", // AnnotationKey.HOST,
                                hostname,
                                "PORT", // "AnnotationKey.PORT,
                                Integer.toString(jmxPort)));

        return target;
    }

    private boolean isTargetUnderRealm(String realm, URI connectUrl) throws IllegalStateException {
        // Check for any targets with the same connectUrl in other realms
        try {
            Target persistedTarget = Target.getTargetByConnectUrl(connectUrl);
            String realmOfTarget = persistedTarget.annotations.cryostat().get("REALM");
            if (!realm.equals(realmOfTarget)) {
                logger.warnv(
                        "Expected persisted target with serviceURL {0} to be under realm"
                                + " {1} but found under {2} ",
                        persistedTarget.connectUrl, getRealm(), realmOfTarget);
                throw new IllegalStateException();
            }
            return true;
        } catch (NoResultException e) {
        }
        return false;
    }

    private void queryContainers() {
        doContainerListRequest(
                current -> {
                    Infrastructure.getDefaultWorkerPool()
                            .execute(
                                    () ->
                                            QuarkusTransaction.joiningExisting()
                                                    .run(() -> handleObservedContainers(current)));
                });
    }

    private void doContainerListRequest(Consumer<List<ContainerSpec>> successHandler) {
        URI requestPath = URI.create(getContainersQueryURL());
        try {
            webClient
                    .request(HttpMethod.GET, getSocket(), 80, "localhost", requestPath.toString())
                    .addQueryParam(
                            "filters",
                            mapper.writeValueAsString(Map.of("label", List.of(DISCOVERY_LABEL))))
                    .timeout(requestTimeout.toMillis())
                    .as(BodyCodec.string())
                    .send()
                    .subscribe()
                    .with(
                            item -> {
                                try {
                                    successHandler.accept(
                                            mapper.readValue(
                                                    item.body(),
                                                    new TypeReference<List<ContainerSpec>>() {}));
                                } catch (JsonProcessingException e) {
                                    logger.error("Json processing error", e);
                                }
                            },
                            failure -> {
                                logger.errorv(failure, "{0} API request failed", getRealm());
                            });
        } catch (JsonProcessingException e) {
            logger.error("Json processing error", e);
        }
    }

    private CompletableFuture<ContainerDetails> doContainerInspectRequest(ContainerSpec container) {
        CompletableFuture<ContainerDetails> result = new CompletableFuture<>();
        URI requestPath = URI.create(getContainerQueryURL(container));
        webClient
                .request(HttpMethod.GET, getSocket(), 80, "localhost", requestPath.toString())
                .timeout(requestTimeout.toMillis())
                .as(BodyCodec.string())
                .send()
                .subscribe()
                .with(
                        item -> {
                            try {
                                result.complete(
                                        mapper.readValue(item.body(), ContainerDetails.class));
                            } catch (JsonProcessingException e) {
                                logger.error("Json processing error", e);
                                result.completeExceptionally(e);
                            }
                        },
                        failure -> {
                            logger.errorv(failure, "{0} API request failed", getRealm());
                            result.completeExceptionally(failure);
                        });
        return result;
    }

    private void handleObservedContainers(List<ContainerSpec> current) {
        Map<URI, ContainerSpec> containerRefMap = new HashMap<>();

        Set<Target> persistedTargets =
                Target.findByRealm(getRealm()).stream().collect(Collectors.toSet());
        Set<Target> observedTargets =
                current.stream()
                        .map(
                                (desc) -> {
                                    Target t = toTarget(desc);
                                    if (Objects.nonNull(t)) {
                                        containerRefMap.put(t.connectUrl, desc);
                                    }
                                    return t;
                                })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

        Target.compare(persistedTargets)
                .to(observedTargets)
                .added()
                .forEach(
                        (t) ->
                                notify(
                                        ContainerDiscoveryEvent.from(
                                                containerRefMap.get(t.connectUrl),
                                                t,
                                                EventKind.FOUND)));

        Target.compare(persistedTargets)
                .to(observedTargets)
                .removed()
                .forEach((t) -> notify(ContainerDiscoveryEvent.from(null, t, EventKind.LOST)));
    }

    public void updateDiscoveryTree(ContainerDiscoveryEvent evt) throws IllegalStateException {
        EventKind evtKind = evt.eventKind;
        ContainerSpec desc = evt.desc;
        Target target = evt.target;

        DiscoveryNode realm = DiscoveryNode.getRealm(getRealm()).orElseThrow();

        if (evtKind == EventKind.FOUND) {
            if (isTargetUnderRealm(getRealm(), target.connectUrl)) {
                logger.infov(
                        "Target with serviceURL {0} already exist in discovery tree. Skip adding",
                        target.connectUrl);
                return;
            }
            DiscoveryNode node = DiscoveryNode.target(target, BaseNodeType.JVM);
            target.discoveryNode = node;

            String podName = desc.PodName;
            if (StringUtils.isNotBlank(podName)) {
                DiscoveryNode pod =
                        DiscoveryNode.getChild(realm, (n) -> n.name.equals(podName))
                                .orElse(
                                        DiscoveryNode.environment(
                                                podName, ContainerDiscoveryNodeType.POD));
                if (!realm.children.contains(pod)) {
                    pod.children.add(node);
                    node.parent = pod;
                    realm.children.add(pod);
                    pod.parent = realm;
                } else {
                    pod =
                            DiscoveryNode.getChild(
                                            realm,
                                            n ->
                                                    podName.equals(n.name)
                                                            && ContainerDiscoveryNodeType.POD
                                                                    .getKind()
                                                                    .equals(n.nodeType))
                                    .orElseThrow();
                    pod.children.add(node);
                    node.parent = pod;
                }
                pod.persist();
            } else {
                realm.children.add(node);
                node.parent = realm;
            }
            target.persist();
            node.persist();
            realm.persist();
        } else {
            if (!isTargetUnderRealm(getRealm(), target.connectUrl)) {
                logger.infov(
                        "Target with serviceURL {0} does not exist in discovery tree. Skip"
                                + " deleting",
                        target.connectUrl);
                return;
            }
            DiscoveryNode node = target.discoveryNode;

            while (true) {
                DiscoveryNode parent = node.parent;
                if (parent == null) {
                    break;
                }

                parent.children.remove(node);
                parent.persist();
                node.parent = null;

                if (parent.hasChildren() || node.nodeType.equals(BaseNodeType.REALM.getKind())) {
                    break;
                }

                node = parent;
            }

            realm.persist();
            target.delete();
        }
    }

    protected void notify(ContainerDiscoveryEvent evt) {
        bus.publish(notificationAddress(), evt);
    }

    protected abstract SocketAddress getSocket();

    protected abstract String getRealm();

    protected abstract String getContainersQueryURL();

    protected abstract String getContainerQueryURL(ContainerSpec spec);

    protected abstract boolean enabled();

    protected abstract String notificationAddress();

    protected abstract void handleContainerEvent(ContainerDiscoveryEvent evt);

    static record PortSpec(
            long container_port, String host_ip, long host_port, String protocol, long range) {}

    static record ContainerSpec(
            String Id,
            String Image,
            Map<String, String> Labels,
            List<String> Names,
            long Pid,
            String Pod,
            String PodName,
            List<PortSpec> Ports,
            long StartedAt,
            String State) {}

    static record ContainerDetails(Config Config) {}

    static record Config(String Hostname) {}

    static record ContainerDiscoveryEvent(ContainerSpec desc, Target target, EventKind eventKind) {
        static ContainerDiscoveryEvent from(
                ContainerSpec spec, Target target, EventKind eventKind) {
            return new ContainerDiscoveryEvent(spec, target, eventKind);
        }
    }
}

enum ContainerDiscoveryNodeType implements NodeType {
    // represents a container pod managed by Podman
    POD("Pod"),
    ;

    private final String kind;

    ContainerDiscoveryNodeType(String kind) {
        this.kind = kind;
    }

    @Override
    public String getKind() {
        return kind;
    }

    @Override
    public String toString() {
        return getKind();
    }
}
