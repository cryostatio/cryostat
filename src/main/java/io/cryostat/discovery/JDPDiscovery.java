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

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Consumer;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.management.remote.JMXServiceURL;
import javax.transaction.Transactional;

import io.cryostat.URIUtil;
import io.cryostat.core.net.discovery.JvmDiscoveryClient;
import io.cryostat.core.net.discovery.JvmDiscoveryClient.JvmDiscoveryEvent;
import io.cryostat.targets.Target;
import io.cryostat.targets.Target.Annotations;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.Vertx;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class JDPDiscovery implements Consumer<JvmDiscoveryEvent> {

    private static final String REALM = "JDP";

    @Produces
    @ApplicationScoped
    static JvmDiscoveryClient produceJvmDiscoveryClient() {
        return new JvmDiscoveryClient(io.cryostat.core.log.Logger.INSTANCE);
    }

    @Inject Logger logger;
    @Inject JvmDiscoveryClient jdp;
    @Inject Vertx vertx;

    @ConfigProperty(name = "cryostat.jdp.enabled")
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

        logger.info("Starting JDP client");
        jdp.addListener(this);
        try {
            jdp.start();
        } catch (IOException ioe) {
            logger.error("Failure starting JDP client", ioe);
        }
    }

    void onStop(@Observes ShutdownEvent evt) {
        logger.info("Shutting down JDP client");
        jdp.stop();
        jdp.removeListener(this);
    }

    @Override
    public void accept(JvmDiscoveryEvent evt) {
        Infrastructure.getDefaultWorkerPool().execute(() -> this.handleJdpEvent(evt));
    }

    @Transactional
    public synchronized void handleJdpEvent(JvmDiscoveryEvent evt) {
        logger.infov(
                "JDP Discovery Event {0} {1}",
                evt.getEventKind(), evt.getJvmDescriptor().getMainClass());
        URI connectUrl;
        URI rmiTarget;
        try {
            JMXServiceURL serviceUrl = evt.getJvmDescriptor().getJmxServiceUrl();
            connectUrl = URI.create(serviceUrl.toString());
            rmiTarget = URIUtil.getRmiTarget(serviceUrl);
        } catch (MalformedURLException | URISyntaxException e) {
            logger.warn("Invalid JDP target observed", e);
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
                logger.warnv("Unknown JVM discovery event {0}", evt.getEventKind());
                break;
        }
    }
}
