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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

public class AgentApplicationResource
        implements QuarkusTestResourceLifecycleManager, DevServicesContext.ContextAware {

    private static final String IMAGE_NAME =
            "quay.io/redhat-java-monitoring/quarkus-cryostat-agent:latest";
    private static final int AGENT_PORT = 9977;
    private static final Map<String, String> envMap =
            new HashMap<>(
                    Map.of(
                            "JAVA_OPTS_APPEND",
                            "-javaagent:/deployments/app/cryostat-agent.jar",
                            "QUARKUS_HTTP_PORT",
                            "101010",
                            "CRYOSTAT_AGENT_APP_NAME",
                            "quarkus-cryostat-agent",
                            "CRYOSTAT_AGENT_WEBSERVER_HOST",
                            "0.0.0.0",
                            "CRYOSTAT_AGENT_WEBSERVER_PORT",
                            Integer.toString(AGENT_PORT),
                            "CRYOSTAT_AGENT_BASEURI",
                            "http://cryostat:8081/",
                            "CRYOSTAT_AGENT_BASEURI_RANGE",
                            "public",
                            "CRYOSTAT_AGENT_API_WRITES_ENABLED",
                            "true"));
    private static final Logger logger = Logger.getLogger(AgentApplicationResource.class);
    private Optional<String> containerNetworkId;
    private GenericContainer<?> container;
    private AtomicInteger cryostatPort = new AtomicInteger(8081);

    @Override
    public Map<String, String> start() {
        container =
                new GenericContainer<>(DockerImageName.parse(IMAGE_NAME))
                        .withExposedPorts(AGENT_PORT)
                        .withEnv(envMap)
                        .waitingFor(Wait.forListeningPort());
        containerNetworkId.ifPresent(container::withNetworkMode);
        container.addEnv(
                "CRYOSTAT_AGENT_BASEURI", String.format("http://cryostat:%d/", cryostatPort.get()));
        container.addEnv(
                "CRYOSTAT_AGENT_CALLBACK",
                String.format("http://%s:%d/", container.getContainerName(), AGENT_PORT));

        container.start();

        // return Map.of("quarkus.test.arg-line", "--name=cryostat");
        return Map.of();
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
        cryostatPort.set(
                Integer.parseInt(
                        context.devServicesProperties().getOrDefault("quarkus.http.port", "8081")));
    }
}
