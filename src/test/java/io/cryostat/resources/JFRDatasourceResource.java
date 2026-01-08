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

import java.util.Map;
import java.util.Optional;

import io.quarkus.test.common.DevServicesContext;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

public class JFRDatasourceResource
        implements QuarkusTestResourceLifecycleManager, DevServicesContext.ContextAware {

    private static final int JFR_DATASOURCE_PORT = 8080;
    private static final String IMAGE_NAME = "quay.io/cryostat/jfr-datasource:latest";
    private static final Map<String, String> envMap = Map.of();

    private Optional<String> containerNetworkId;
    private GenericContainer<?> container;

    @Override
    public Map<String, String> start() {
        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(IMAGE_NAME))
                .withExposedPorts(JFR_DATASOURCE_PORT)
                .withEnv(envMap)
                .waitingFor(Wait.forLogMessage(".*Listening on:.*", 1));
        containerNetworkId.ifPresent(c::withNetworkMode);
        container = c;

        container.start();

        String networkHostPort =
                "http://"
                        + container.getHost()
                        + ":"
                        + container.getMappedPort(JFR_DATASOURCE_PORT);

        return Map.of("grafana-datasource.url", networkHostPort);
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
}
