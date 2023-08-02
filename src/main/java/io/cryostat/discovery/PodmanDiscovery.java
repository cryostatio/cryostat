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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import javax.management.remote.JMXServiceURL;

import io.cryostat.URIUtil;
import io.cryostat.core.net.JFRConnectionToolkit;
import io.cryostat.targets.Target;
import io.cryostat.targets.Target.Annotations;
import io.cryostat.targets.Target.EventKind;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.security.auth.module.UnixSystem;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PodmanDiscovery {

    private static final String REALM = "Podman";
    public static final String DISCOVERY_LABEL = "io.cryostat.discovery";
    public static final String JMX_URL_LABEL = "io.cryostat.jmxUrl";
    public static final String JMX_HOST_LABEL = "io.cryostat.jmxHost";
    public static final String JMX_PORT_LABEL = "io.cryostat.jmxPort";

    private long timerId;
    private final CopyOnWriteArrayList<ContainerSpec> containers = new CopyOnWriteArrayList<>();

    @Inject Logger logger;
    @Inject Vertx vertx;
    @Inject WebClient webClient;
    @Inject JFRConnectionToolkit connectionToolkit;
    @Inject ObjectMapper mapper;

    @ConfigProperty(name = "cryostat.discovery.podman.enabled")
    boolean enabled;

    @Transactional
    void onStart(@Observes StartupEvent evt) {
        if (!enabled) {
            return;
        }

        DiscoveryNode universe = DiscoveryNode.getUniverse();
        if (DiscoveryNode.getRealm(REALM).isEmpty()) {
            DiscoveryPlugin plugin = new DiscoveryPlugin();
            DiscoveryNode node = DiscoveryNode.environment(REALM, DiscoveryNode.REALM);
            plugin.realm = node;
            plugin.builtin = true;
            universe.children.add(node);
            plugin.persist();
            universe.persist();
        }

        queryContainers();
        this.timerId = vertx.setPeriodic(10_000, unused -> queryContainers());
    }

    void onStop(@Observes ShutdownEvent evt) {
        if (!enabled) {
            return;
        }
        logger.info("Shutting down Podman client");
        vertx.cancelTimer(timerId);
    }

    private void queryContainers() {
        doPodmanListRequest(
                current -> {
                    Set<ContainerSpec> previous = new HashSet<>(containers);
                    Set<ContainerSpec> updated = new HashSet<>(current);

                    Set<ContainerSpec> intersection = new HashSet<>(containers);
                    intersection.retainAll(updated);

                    Set<ContainerSpec> removed = new HashSet<>(previous);
                    removed.removeAll(intersection);

                    Set<ContainerSpec> added = new HashSet<>(updated);
                    added.removeAll(intersection);

                    containers.removeAll(removed);
                    Infrastructure.getDefaultWorkerPool()
                            .execute(
                                    () ->
                                            removed.stream()
                                                    .filter(Objects::nonNull)
                                                    .forEach(
                                                            container ->
                                                                    handlePodmanEvent(
                                                                            container,
                                                                            EventKind.LOST)));

                    containers.addAll(added);
                    Infrastructure.getDefaultWorkerPool()
                            .execute(
                                    () ->
                                            added.stream()
                                                    .filter(Objects::nonNull)
                                                    .forEach(
                                                            container ->
                                                                    handlePodmanEvent(
                                                                            container,
                                                                            EventKind.FOUND)));
                });
    }

    private void doPodmanListRequest(Consumer<List<ContainerSpec>> successHandler) {
        logger.info("Starting Podman client");
        URI requestPath = URI.create("http://d/v3.0.0/libpod/containers/json");
        try {
            webClient
                    .request(HttpMethod.GET, getSocket(), 80, "localhost", requestPath.toString())
                    .addQueryParam(
                            "filters",
                            mapper.writeValueAsString(Map.of("label", List.of(DISCOVERY_LABEL))))
                    .timeout(2_000L)
                    .as(BodyCodec.string())
                    .send(
                            ar -> {
                                if (ar.failed()) {
                                    Throwable t = ar.cause();
                                    logger.error("Podman API request failed", t);
                                    return;
                                }
                                try {
                                    successHandler.accept(
                                            mapper.readValue(
                                                    ar.result().body(),
                                                    new TypeReference<List<ContainerSpec>>() {}));
                                } catch (JsonProcessingException e) {
                                    logger.error("Json processing error");
                                    return;
                                }
                            });
        } catch (JsonProcessingException e) {
            logger.error("Json processing error");
            return;
        }
    }

    private CompletableFuture<ContainerDetails> doPodmanInspectRequest(ContainerSpec container) {
        CompletableFuture<ContainerDetails> result = new CompletableFuture<>();
        URI requestPath =
                URI.create(
                        String.format("http://d/v3.0.0/libpod/containers/%s/json", container.Id));
        webClient
                .request(HttpMethod.GET, getSocket(), 80, "localhost", requestPath.toString())
                .timeout(2_000L)
                .as(BodyCodec.string())
                .send(
                        ar -> {
                            if (ar.failed()) {
                                Throwable t = ar.cause();
                                logger.error("Podman API request failed", t);
                                result.completeExceptionally(t);
                                return;
                            }
                            try {
                                result.complete(
                                        mapper.readValue(
                                                ar.result().body(), ContainerDetails.class));
                            } catch (JsonProcessingException e) {
                                logger.error("Json processing error");
                                return;
                            }
                        });
        return result;
    }

    private static SocketAddress getSocket() {
        long uid = new UnixSystem().getUid();
        String socketPath = String.format("/run/user/%d/podman/podman.sock", uid);
        return SocketAddress.domainSocketAddress(socketPath);
    }

    @Transactional
    public void handlePodmanEvent(ContainerSpec desc, EventKind evtKind) {
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
                                doPodmanInspectRequest(desc)
                                        .get(2, TimeUnit.SECONDS)
                                        .Config
                                        .Hostname;
                    } catch (InterruptedException | TimeoutException | ExecutionException e) {
                        containers.remove(desc);
                        logger.warn("Invalid Podman target observed", e);
                        return;
                    }
                }
            }
            serviceUrl = connectionToolkit.createServiceURL(hostname, jmxPort);
            connectUrl = URI.create(serviceUrl.toString());
        } catch (MalformedURLException | URISyntaxException e) {
            containers.remove(desc);
            logger.warn("Invalid Podman target observed", e);
            return;
        }

        DiscoveryNode realm = DiscoveryNode.getRealm(REALM).orElseThrow();

        if (evtKind == EventKind.FOUND) {
            Target target = new Target();
            target.activeRecordings = new ArrayList<>();
            target.connectUrl = connectUrl;
            target.alias = Optional.ofNullable(desc.Names.get(0)).orElse(desc.Id);
            target.labels = desc.Labels;
            target.annotations = new Annotations();
            target.annotations.cryostat.putAll(
                    Map.of(
                            "REALM", // AnnotationKey.REALM,
                            REALM,
                            "HOST", // AnnotationKey.HOST,
                            hostname,
                            "PORT", // "AnnotationKey.PORT,
                            Integer.toString(jmxPort)));

            DiscoveryNode node = DiscoveryNode.target(target);
            target.discoveryNode = node;
            String podName = desc.PodName;
            DiscoveryNode pod = new DiscoveryNode();
            if (StringUtils.isNotBlank(podName)) {
                pod = DiscoveryNode.environment(podName, DiscoveryNode.POD);
                if (!realm.children.contains(pod)) {
                    pod.children.add(node);
                    realm.children.add(pod);
                } else {
                    pod =
                            DiscoveryNode.getChild(
                                            realm,
                                            n ->
                                                    podName.equals(n.name)
                                                            && DiscoveryNode.POD.equals(n.nodeType))
                                    .orElseThrow();
                    pod.children.add(node);
                }
                pod.persist();
            } else {
                realm.children.add(node);
            }
            target.persist();
            node.persist();
            realm.persist();
        } else {
            Target t = Target.getTargetByConnectUrl(connectUrl);
            String podName = desc.PodName;
            if (StringUtils.isNotBlank(podName)) {
                DiscoveryNode pod = DiscoveryNode.environment(podName, DiscoveryNode.POD);
                pod.children.remove(t.discoveryNode);
            } else {
                realm.children.remove(t.discoveryNode);
            }
            t.delete();
            realm.persist();
        }
    }

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
}
/* */
