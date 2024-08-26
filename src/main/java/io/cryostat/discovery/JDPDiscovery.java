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

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Consumer;

import javax.management.remote.JMXServiceURL;

import io.cryostat.core.net.discovery.JvmDiscoveryClient;
import io.cryostat.core.net.discovery.JvmDiscoveryClient.JvmDiscoveryEvent;
import io.cryostat.targets.Target;
import io.cryostat.targets.Target.Annotations;
import io.cryostat.util.URIUtil;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class JDPDiscovery implements Consumer<JvmDiscoveryEvent> {

    private static final String REALM = "JDP";

    @Produces
    @ApplicationScoped
    static JvmDiscoveryClient produceJvmDiscoveryClient() {
        return new JvmDiscoveryClient();
    }

    @Inject Logger logger;
    @Inject JvmDiscoveryClient jdp;
    @Inject Vertx vertx;
    @Inject EventBus eventBus;
    @Inject URIUtil uriUtil;

    @ConfigProperty(name = "cryostat.discovery.jdp.enabled")
    boolean enabled;

    @Transactional
    void onStart(@Observes StartupEvent evt) {
        if (!enabled) {
            return;
        }

        logger.debug("Starting JDP client");
        jdp.addListener(this);
        try {
            jdp.start();
        } catch (IOException ioe) {
            logger.error("Failure starting JDP client", ioe);
        }
    }

    void onStop(@Observes ShutdownEvent evt) {
        if (!enabled) {
            return;
        }
        logger.debug("Shutting down JDP client");
        jdp.stop();
        jdp.removeListener(this);
    }

    @Override
    public void accept(JvmDiscoveryEvent evt) {
        eventBus.publish(JDPDiscovery.class.getName(), evt);
    }

    @ConsumeEvent(blocking = true, ordered = true)
    @Transactional(TxType.REQUIRES_NEW)
    void handleJdpEvent(JvmDiscoveryEvent evt) {
        logger.tracev(
                "JDP Discovery Event {0} {1}",
                evt.getEventKind(), evt.getJvmDescriptor().getMainClass());
        URI connectUrl;
        URI rmiTarget;
        try {
            JMXServiceURL serviceUrl = evt.getJvmDescriptor().getJmxServiceUrl();
            connectUrl = URI.create(serviceUrl.toString());
            rmiTarget = uriUtil.getRmiTarget(serviceUrl);
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
                target.annotations
                        .cryostat()
                        .putAll(
                                Map.of(
                                        "REALM", // AnnotationKey.REALM,
                                        REALM,
                                        "JAVA_MAIN", // AnnotationKey.JAVA_MAIN,
                                        evt.getJvmDescriptor().getMainClass(),
                                        "HOST", // AnnotationKey.HOST,
                                        rmiTarget.getHost(),
                                        "PORT", // "AnnotationKey.PORT,
                                        Integer.toString(rmiTarget.getPort())));

                DiscoveryNode node = DiscoveryNode.target(target, BaseNodeType.JVM);

                target.discoveryNode = node;
                realm.children.add(node);
                node.parent = realm;
                target.persist();
                node.persist();
                realm.persist();
                break;
            case LOST:
                Target t = Target.getTargetByConnectUrl(connectUrl);
                realm.children.remove(t.discoveryNode);
                t.discoveryNode.parent = null;
                realm.persist();
                t.delete();
                break;
            default:
                logger.warnv("Unknown JVM discovery event {0}", evt.getEventKind());
                break;
        }
    }
}
