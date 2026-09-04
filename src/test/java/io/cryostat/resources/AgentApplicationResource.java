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

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.quarkus.test.common.DevServicesContext;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.apache.commons.lang3.StringUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.HostPortWaitStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

public class AgentApplicationResource
        implements QuarkusTestResourceLifecycleManager, DevServicesContext.ContextAware {

    private static final String DEFAULT_IMAGE =
            "quay.io/redhat-java-monitoring/quarkus-cryostat-agent:latest";
    public static final int PORT = 9977;
    public static final String ALIAS = "quarkus-cryostat-agent";

    protected Map<String, String> getEnvMap() {
        // Network- and port-specific settings (QUARKUS_HTTP_PORT,
        // CRYOSTAT_AGENT_WEBSERVER_PORT, CRYOSTAT_AGENT_BASEURI, CRYOSTAT_AGENT_CALLBACK) are
        // applied in start() since they differ between the networked and non-networked setups.
        return new HashMap<>(
                Map.of(
                        "JAVA_OPTS_APPEND",
                        """
                        -javaagent:/deployments/app/cryostat-agent.jar
                        -javaagent:/deployments/app/jmc-agent.jar
                        -javaagent:/deployments/app/async-profiler.jar
                        -Djava.util.logging.manager=org.jboss.logmanager.LogManager
                        -Dio.cryostat.agent.shaded.org.slf4j.simpleLogger.defaultLogLevel=warn
                        """
                                .replace("\n", " ")
                                .strip(),
                        "CRYOSTAT_AGENT_APP_NAME",
                        "quarkus-cryostat-agent",
                        "CRYOSTAT_AGENT_WEBCLIENT_TLS_REQUIRED",
                        "false",
                        "CRYOSTAT_AGENT_WEBSERVER_HOST",
                        "0.0.0.0",
                        "CRYOSTAT_AGENT_BASEURI_RANGE",
                        "public",
                        "CRYOSTAT_AGENT_API_WRITES_ENABLED",
                        "true"));
    }

    private Optional<String> containerNetworkId;
    private GenericContainer<?> container;

    @SuppressWarnings("resource")
    @Override
    public Map<String, String> start() {
        int cryostatPort = findFreePort();

        Optional<Network> network =
                containerNetworkId
                        .filter(StringUtils::isNotBlank)
                        .map(
                                id ->
                                        new Network() {
                                            @Override
                                            public String getId() {
                                                return id;
                                            }

                                            @Override
                                            public void close() {}
                                        });

        String img =
                Optional.ofNullable(System.getenv("QUARKUS_TEST_IMAGE"))
                        .filter(StringUtils::isNotBlank)
                        .orElse(DEFAULT_IMAGE);
        this.container =
                new GenericContainer<>(DockerImageName.parse(img))
                        .withEnv(getEnvMap())
                        .withStartupAttempts(3)
                        .withCreateContainerCmdModifier(
                                cmd ->
                                        cmd.getHostConfig()
                                                .withCpuShares(512)
                                                .withMemory(256L * 1024L * 1024L));

        if (network.isPresent()) {
            // Networked setup (integration tests): Cryostat runs in a container on the same
            // network, so the agent's callback host (its network alias) resolves to the same
            // address the agent's registration request originates from, satisfying Cryostat's
            // callback validation.
            int hostAgentPort = findFreePort();
            container
                    .withExposedPorts(PORT)
                    .withNetworkAliases(ALIAS)
                    .withExtraHost("host.docker.internal", "host-gateway")
                    .waitingFor(new HostPortWaitStrategy().forPorts(PORT))
                    .withNetwork(network.get());
            container.setPortBindings(List.of(String.format("%d:%d", hostAgentPort, PORT)));
            container.addEnv("QUARKUS_HTTP_PORT", "9898");
            container.addEnv("CRYOSTAT_AGENT_WEBSERVER_PORT", Integer.toString(PORT));
            container.addEnv(
                    "CRYOSTAT_AGENT_BASEURI",
                    String.format("http://host.docker.internal:%d/", cryostatPort));
            container.addEnv(
                    "CRYOSTAT_AGENT_CALLBACK", String.format("http://%s:%d/", ALIAS, PORT));
        } else {
            // Non-networked setup (unit tests): Cryostat runs on the host, outside any container
            // network. Use host networking so the agent shares the host's network namespace; its
            // registration request then originates from a loopback address that matches its
            // "localhost" callback host, satisfying Cryostat's callback validation, and the
            // callback remains directly reachable by the host-side Cryostat.
            int agentWebserverPort = findFreePort();
            int agentAppPort = findFreePort();
            container
                    .withNetworkMode("host")
                    .waitingFor(Wait.forLogMessage(".*Installed features.*", 1));
            container.addEnv("QUARKUS_HTTP_PORT", Integer.toString(agentAppPort));
            container.addEnv("CRYOSTAT_AGENT_WEBSERVER_PORT", Integer.toString(agentWebserverPort));
            container.addEnv(
                    "CRYOSTAT_AGENT_BASEURI", String.format("http://localhost:%d/", cryostatPort));
            container.addEnv(
                    "CRYOSTAT_AGENT_CALLBACK",
                    String.format("http://localhost:%d/", agentWebserverPort));
        }

        container.start();

        return Map.of(
                "cryostat.agent.tls.required",
                "false",
                "quarkus.http.port",
                Integer.toString(cryostatPort),
                "quarkus.http.test-port",
                Integer.toString(cryostatPort));
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
            container.close();
        }
    }

    @Override
    public void setIntegrationTestContext(DevServicesContext context) {
        containerNetworkId = context.containerNetworkId();
    }

    private static int findFreePort() {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Failed to find free port", e);
        }
    }
}
