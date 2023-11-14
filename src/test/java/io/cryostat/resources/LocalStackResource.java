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
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.utility.DockerImageName;

public class LocalStackResource
        implements QuarkusTestResourceLifecycleManager, DevServicesContext.ContextAware {

    private static String IMAGE_NAME = "localstack/localstack:2.1.0";
    private static final LocalStackContainer LOCAL_STACK_CONTAINER;
    private Optional<String> containerNetworkId;

    static {
        DockerImageName localstackImage = DockerImageName.parse(IMAGE_NAME);
        LOCAL_STACK_CONTAINER = new LocalStackContainer(localstackImage).withServices(Service.S3);
    }

    @Override
    public Map<String, String> start() {
        LOCAL_STACK_CONTAINER.start();
        System.setProperty("aws.accessKeyId", LOCAL_STACK_CONTAINER.getAccessKey());
        System.setProperty("aws.secretAccessKey", LOCAL_STACK_CONTAINER.getSecretKey());
        Map<String, String> properties = new HashMap<String, String>();
        properties.put("quarkus.ses.aws.region", "us-east-1");
        properties.put(
                "s3.url.override",
                LOCAL_STACK_CONTAINER.getEndpointOverride(Service.S3).toString());
        properties.put("quarkus.ses.aws.credentials.type", "static");
        properties.put("aws.accessKeyId", LOCAL_STACK_CONTAINER.getAccessKey());
        properties.put("aws.secretAccessKey", LOCAL_STACK_CONTAINER.getSecretKey());

        containerNetworkId.ifPresent(LOCAL_STACK_CONTAINER::withNetworkMode);

        return properties;
    }

    @Override
    public void stop() {
        LOCAL_STACK_CONTAINER.stop();
        LOCAL_STACK_CONTAINER.close();
    }

    @Override
    public void setIntegrationTestContext(DevServicesContext context) {
        containerNetworkId = context.containerNetworkId();
    }
}
