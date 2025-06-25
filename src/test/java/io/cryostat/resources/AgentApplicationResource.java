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
package io.cryostat.resources;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import io.quarkus.test.common.DevServicesContext;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.jboss.logging.Logger;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.HostPortWaitStrategy;
import org.testcontainers.utility.DockerImageName;

public class AgentApplicationResource
        implements QuarkusTestResourceLifecycleManager, DevServicesContext.ContextAware {

    private static final String IMAGE_NAME =
            "quay.io/redhat-java-monitoring/quarkus-cryostat-agent:latest";
    public static final int PORT = 9977;
    public static final String ALIAS = "quarkus-cryostat-agent";
    private static final Map<String, String> envMap =
            new HashMap<>(
                    Map.of(
                            "JAVA_OPTS_APPEND",
                            """
                            -javaagent:/deployments/app/cryostat-agent.jar
                            -Djava.util.logging.manager=org.jboss.logmanager.LogManager
                            -Dio.cryostat.agent.shaded.org.slf4j.simpleLogger.defaultLogLevel=trace
                            """,
                            "QUARKUS_HTTP_PORT",
                            "9898",
                            "CRYOSTAT_AGENT_APP_NAME",
                            "quarkus-cryostat-agent",
                            "CRYOSTAT_AGENT_WEBCLIENT_TLS_REQUIRED",
                            "false",
                            "CRYOSTAT_AGENT_WEBSERVER_HOST",
                            "0.0.0.0",
                            "CRYOSTAT_AGENT_WEBSERVER_PORT",
                            Integer.toString(PORT),
                            "CRYOSTAT_AGENT_BASEURI_RANGE",
                            "public",
                            "CRYOSTAT_AGENT_API_WRITES_ENABLED",
                            "true"));
    private static final Logger logger = Logger.getLogger(AgentApplicationResource.class);
    private Optional<String> containerNetworkId;
    private AuthProxyContainer authProxy;
    private GenericContainer<?> container;
    private AtomicInteger cryostatPort = new AtomicInteger(8081);

    @Override
    public Map<String, String> start() {
        Optional<Network> network =
                containerNetworkId.map(
                        id ->
                                new Network() {
                                    @Override
                                    public String getId() {
                                        return id;
                                    }

                                    @Override
                                    public void close() {}

                                    @Override
                                    public Statement apply(
                                            Statement base, Description description) {
                                        throw new UnsupportedOperationException(
                                                "Unimplemented method 'apply'");
                                    }
                                });
        authProxy = new AuthProxyContainer(network, cryostatPort.get());

        container =
                new GenericContainer<>(DockerImageName.parse(IMAGE_NAME))
                        .dependsOn(authProxy)
                        .withExposedPorts(PORT)
                        .withEnv(envMap)
                        .withNetworkAliases(ALIAS)
                        .waitingFor(new HostPortWaitStrategy().forPorts(PORT));
        network.ifPresent(container::withNetwork);
        container.addEnv(
                "CRYOSTAT_AGENT_BASEURI",
                String.format("http://%s:%d/", AuthProxyContainer.ALIAS, AuthProxyContainer.PORT));
        container.addEnv("CRYOSTAT_AGENT_CALLBACK", String.format("http://%s:%d/", ALIAS, PORT));

        container.start();

        return Map.of(
                "cryostat.agent.tls.required", "false",
                "cryostat.http.proxy.host", ALIAS,
                "cryostat.http.proxy.port", Integer.toString(cryostatPort.get()),
                "quarkus.http.proxy.proxy-address-forwarding", "true",
                "quarkus.http.proxy.allow-x-forwarded", "true",
                "quarkus.http.proxy.enable-forwarded-host", "true",
                "quarkus.http.proxy.enable-forwarded-prefix", "true",
                "quarkus.http.access-log.pattern", "long",
                "quarkus.http.access-log.enabled", "true");
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
            container.close();
        }
        if (authProxy != null) {
            authProxy.stop();
            authProxy.close();
        }
    }

    @Override
    public void setIntegrationTestContext(DevServicesContext context) {
        containerNetworkId = context.containerNetworkId();
        cryostatPort.set(
                Integer.parseInt(
                        context.devServicesProperties().getOrDefault("quarkus.http.port", "8081")));
    }
}
