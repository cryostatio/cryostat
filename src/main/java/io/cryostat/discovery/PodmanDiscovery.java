/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.cryostat.discovery;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import javax.management.remote.JMXServiceURL;

import io.cryostat.URIUtil;
import io.cryostat.core.net.JFRConnectionToolkit;
import io.cryostat.core.net.discovery.JvmDiscoveryClient.JvmDiscoveryEvent;
import io.cryostat.targets.Target;
import io.cryostat.targets.Target.Annotations;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.sun.security.auth.module.UnixSystem;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
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
    @Inject Gson gson;

    @ConfigProperty(name = "cryostat.podman.enabled")
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

        logger.info("***STARTING PODMAN TEST");
        boolean serviceReachable = testPodmanApi();
        logger.info("***REACHABLE????" + serviceReachable);
        queryContainers();
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

                    // does anything ever get modified in this scheme?
                    // notifyAsyncTargetDiscovery(EventKind.MODIFIED, sr);

                    containers.removeAll(removed);
                    containers.addAll(added);
                });
    }

    private void doPodmanListRequest(Consumer<List<ContainerSpec>> successHandler) {
        logger.info("Starting Podman client");
        URI requestPath = URI.create("http://d/v3.0.0/libpod/containers/json");
        webClient
                .request(HttpMethod.GET, getSocket(), 80, "localhost", requestPath.toString())
                .addQueryParam("filters", gson.toJson(Map.of("label", List.of(DISCOVERY_LABEL))))
                .timeout(2_000L)
                .as(BodyCodec.string())
                .send(
                        ar -> {
                            if (ar.failed()) {
                                Throwable t = ar.cause();
                                logger.error("Podman API request failed", t);
                                return;
                            }
                            successHandler.accept(
                                    gson.fromJson(
                                            ar.result().body(),
                                            new TypeToken<List<ContainerSpec>>() {}));
                            logger.info("****result" + ar.result().body());
                        });
    }

    private boolean testPodmanApi() {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        URI requestPath = URI.create("http://d/info");
        ForkJoinPool.commonPool()
                .submit(
                        () -> {
                            webClient
                                    .request(
                                            HttpMethod.GET,
                                            getSocket(),
                                            80,
                                            "localhost",
                                            requestPath.toString())
                                    .timeout(2_000L)
                                    .as(BodyCodec.none())
                                    .send(
                                            ar -> {
                                                if (ar.failed()) {
                                                    Throwable t = ar.cause();
                                                    logger.info("Podman API request failed", t);
                                                    result.complete(false);
                                                    return;
                                                }
                                                result.complete(true);
                                            });
                        });
        try {
            return result.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            logger.error(e);
            return false;
        }
    }

    private static String getSocketPath() {
        long uid = new UnixSystem().getUid();
        String socketPath = String.format("/run/user/%d/podman/podman.sock", uid);
        return socketPath;
    }

    private static SocketAddress getSocket() {
        return SocketAddress.domainSocketAddress(getSocketPath());
    }

    void onStop(@Observes ShutdownEvent evt) {
        if (!enabled) {
            return;
        }
        logger.info("Shutting down Podman client");
        vertx.cancelTimer(timerId);
    }

    @Transactional
    public synchronized void handlePodmanEvent(JvmDiscoveryEvent evt) {
        URI connectUrl;
        URI rmiTarget;
        try {
            JMXServiceURL serviceUrl = evt.getJvmDescriptor().getJmxServiceUrl();
            connectUrl = URI.create(serviceUrl.toString());
            rmiTarget = URIUtil.getRmiTarget(serviceUrl);
        } catch (MalformedURLException | URISyntaxException e) {
            logger.warn("Invalid Podman target observed", e);
            return;
        }
        DiscoveryNode realm = DiscoveryNode.getRealm(REALM).orElseThrow();
        switch (evt.getEventKind()) {
            case FOUND:
                Target target = new Target();
                target.activeRecordings = new ArrayList<>();
                target.connectUrl = connectUrl;
                target.alias = evt.getJvmDescriptor().getMainClass();
                target.labels = Map.of();
                target.annotations = new Annotations();
                target.annotations.cryostat.putAll(
                        Map.of(
                                "REALM", // AnnotationKey.REALM,
                                REALM,
                                "JAVA_MAIN", // AnnotationKey.JAVA_MAIN,
                                evt.getJvmDescriptor().getMainClass(),
                                "HOST", // AnnotationKey.HOST,
                                rmiTarget.getHost(),
                                "PORT", // "AnnotationKey.PORT,
                                Integer.toString(rmiTarget.getPort())));

                DiscoveryNode node = DiscoveryNode.target(target);

                target.discoveryNode = node;
                realm.children.add(node);
                target.persist();
                node.persist();
                realm.persist();
                break;
            case LOST:
                Target t = Target.getTargetByConnectUrl(connectUrl);
                realm.children.remove(t.discoveryNode);
                t.delete();
                realm.persist();
                break;
            default:
                logger.warnv("Unknown Podman discovery event {0}", evt.getEventKind());
                break;
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
}
/* */
