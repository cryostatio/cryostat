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

public class GrafanaResource
        implements QuarkusTestResourceLifecycleManager, DevServicesContext.ContextAware {

    private static int GRAFANA_PORT = 3000;
    private static String IMAGE_NAME = "quay.io/cryostat/cryostat-grafana-dashboard:latest";
    private static Map<String, String> envMap =
            Map.of(
                    "GF_INSTALL_PLUGINS", "grafana-simple-json-datasource",
                    "GF_AUTH_ANONYMOUS_ENABLED", "true",
                    "JFR_DATASOURCE_URL", "http://jfr-datasource:8080");

    private Optional<String> containerNetworkId;
    private GenericContainer<?> container;

    @Override
    public Map<String, String> start() {
        container =
                new GenericContainer<>(DockerImageName.parse(IMAGE_NAME))
                        .withExposedPorts(GRAFANA_PORT)
                        .withEnv(envMap)
                        .waitingFor(
                                Wait.forLogMessage(
                                        ".*inserting datasource from configuration.*", 1));
        containerNetworkId.ifPresent(container::withNetworkMode);

        container.start();

        String networkHostPort =
                "http://" + container.getHost() + ":" + container.getMappedPort(GRAFANA_PORT);

        return Map.of("grafana-dashboard.url", networkHostPort);
    }

    @Override
    public void stop() {
        container.stop();
        container.close();
    }

    @Override
    public void setIntegrationTestContext(DevServicesContext context) {
        containerNetworkId = context.containerNetworkId();
    }
}
