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
import java.util.Map;
import java.util.function.Consumer;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.transaction.Transactional;

import io.cryostat.core.net.discovery.JvmDiscoveryClient;
import io.cryostat.core.net.discovery.JvmDiscoveryClient.JvmDiscoveryEvent;
import io.cryostat.targets.Target;
import io.cryostat.targets.Target.Annotations;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class JDPDiscovery implements Consumer<JvmDiscoveryEvent> {

    @Produces
    static JvmDiscoveryClient produceJvmDiscoveryClient() {
        return new JvmDiscoveryClient(io.cryostat.core.log.Logger.INSTANCE);
    }

    private final Logger logger = LoggerFactory.getLogger(getClass());
    @Inject JvmDiscoveryClient jdp;
    @Inject Vertx vertx;

    @ConfigProperty(name = "cryostat.jdp.enabled")
    boolean jdpEnabled;

    void onStart(@Observes StartupEvent evt) {
        if (!jdpEnabled) {
            return;
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

    @Transactional
    @Override
    public void accept(JvmDiscoveryEvent evt) {
        logger.info("JDP Discovery Event {}", evt);
        URI connectUrl = null;
        try {
            connectUrl = URI.create(evt.getJvmDescriptor().getJmxServiceUrl().toString());
        } catch (MalformedURLException e) {
            logger.warn("Invalid JDP target observed", e);
            return;
        }
        switch (evt.getEventKind()) {
            case FOUND:
                Target target = new Target();
                target.connectUrl = connectUrl;
                target.alias = evt.getJvmDescriptor().getMainClass();
                target.labels = Map.of();
                target.annotations = new Annotations();
                target.persist();
                break;
            case LOST:
                Target.getTargetByConnectUrl(connectUrl).delete();
                break;
            default:
                logger.warn("Unknown JVM discovery event {}", evt.getEventKind());
                break;
        }
    }

    //     private static ServiceRef convert(DiscoveredJvmDescriptor desc)
    //             throws MalformedURLException, URISyntaxException {
    //         JMXServiceURL serviceUrl = desc.getJmxServiceUrl();
    //         ServiceRef serviceRef =
    //                 new ServiceRef(null, URIUtil.convert(serviceUrl), desc.getMainClass());
    //         URI rmiTarget = URIUtil.getRmiTarget(serviceUrl);
    //         serviceRef.setCryostatAnnotations(
    //                 Map.of(
    //                         AnnotationKey.REALM,
    //                         REALM,
    //                         AnnotationKey.JAVA_MAIN,
    //                         desc.getMainClass(),
    //                         AnnotationKey.HOST,
    //                         rmiTarget.getHost(),
    //                         AnnotationKey.PORT,
    //                         Integer.toString(rmiTarget.getPort())));
    //         return serviceRef;
    //     }
}
