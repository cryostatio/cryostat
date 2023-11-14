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

import io.quarkus.test.common.DevServicesContext;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

public class LocalStackResource
        implements QuarkusTestResourceLifecycleManager, DevServicesContext.ContextAware {

    private static int S3_PORT = 4566;
    private static final String IMAGE_NAME = "docker.io/localstack/localstack:latest";
    private static final Map<String, String> envMap =
            Map.of(
                    "START_WEB", "0",
                    "SERVICES", "s3",
                    "EAGER_SERVICE_LOADING", "1",
                    "SKIP_SSL_CERT_DOWNLOAD", "1",
                    "SKIP_INFRA_DOWNLOADS", "1",
                    "DISABLE_EVENTS", "1");
    private Optional<String> containerNetworkId;
    private GenericContainer<?> container;

    @Override
    public Map<String, String> start() {
        container =
                new GenericContainer<>(DockerImageName.parse(IMAGE_NAME))
                        .withExposedPorts(S3_PORT)
                        .withEnv(envMap)
                        .waitingFor(Wait.forHealthcheck());
        containerNetworkId.ifPresent(container::withNetworkMode);

        container.start();

        String networkHostPort =
                "http://" + container.getHost() + ":" + container.getMappedPort(S3_PORT);

        Map<String, String> properties = new HashMap<String, String>();
        properties.put("quarkus.s3.aws.region", "us-east-1");
        properties.put("s3.url.override", networkHostPort);
        properties.put("quarkus.s3.endpoint-override", properties.get("s3.url.override"));
        properties.put("quarkus.s3.aws.region", "us-east-1");
        properties.put("quarkus.s3.aws.credentials.type", "static");
        properties.put("quarkus.s3.aws.credentials.static-provider.access-key-id", "unused");
        properties.put("quarkus.s3.aws.credentials.static-provider.secret-access-key", "unused");
        properties.put("aws.access-key-id", "unused");
        properties.put("aws.secret-access-key", "unused");

        return properties;
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
