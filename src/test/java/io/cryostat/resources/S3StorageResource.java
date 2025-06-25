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
import org.jboss.logging.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

public class S3StorageResource
        implements QuarkusTestResourceLifecycleManager, DevServicesContext.ContextAware {

    protected static int S3_PORT = 8333;
    protected static final String IMAGE_NAME = "quay.io/cryostat/cryostat-storage:latest";
    protected static final Map<String, String> envMap =
            Map.of(
                    "DATA_DIR", "/data",
                    "IP_BIND", "0.0.0.0",
                    "WEED_V", "4",
                    "REST_ENCRYPTION_ENABLE", "1",
                    "CRYOSTAT_ACCESS_KEY", "access_key",
                    "CRYOSTAT_SECRET_KEY", "secret_key",
                    "CRYOSTAT_BUCKETS", "archivedrecordings,archivedreports,eventtemplates,probes");
    protected final Logger logger = Logger.getLogger(getClass());
    protected Optional<String> containerNetworkId;
    protected GenericContainer<?> container;

    @Override
    public Map<String, String> start() {
        container =
                new GenericContainer<>(DockerImageName.parse(IMAGE_NAME))
                        .withExposedPorts(S3_PORT)
                        .withEnv(envMap)
                        .withTmpFs(Map.of("/data", "rw"))
                        .waitingFor(Wait.forListeningPort());
        containerNetworkId.ifPresent(container::withNetworkMode);

        container.start();

        String networkHostPort =
                adjustS3Url(container, container.getHost(), container.getMappedPort(S3_PORT));

        Map<String, String> properties = new HashMap<String, String>();
        properties.put("quarkus.s3.aws.region", "us-east-1");
        properties.put("s3.url.override", networkHostPort);
        properties.put("quarkus.s3.endpoint-override", properties.get("s3.url.override"));
        properties.put("quarkus.s3.path-style-access", "true");
        properties.put("quarkus.s3.aws.credentials.type", "static");
        properties.put("quarkus.s3.aws.credentials.static-provider.access-key-id", "access_key");
        properties.put(
                "quarkus.s3.aws.credentials.static-provider.secret-access-key", "secret_key");
        properties.put(
                "aws.access-key-id",
                properties.get("quarkus.s3.aws.credentials.static-provider.access-key-id"));
        properties.put("aws.accessKeyId", properties.get("aws.access-key-id"));
        properties.put(
                "aws.secret-access-key",
                properties.get("quarkus.s3.aws.credentials.static-provider.secret-access-key"));
        properties.put("aws.secretAccessKey", properties.get("aws.secret-access-key"));

        return properties;
    }

    @Override
    public void stop() {
        logger.info("stopping");
        if (container != null) {
            container.stop();
            container.close();
        }
        logger.info("stopped");
    }

    @Override
    public void setIntegrationTestContext(DevServicesContext context) {
        containerNetworkId = context.containerNetworkId();
    }

    protected String adjustS3Url(GenericContainer<?> container, String host, int port) {
        return "http://" + container.getHost() + ":" + container.getMappedPort(S3_PORT);
    }
}
