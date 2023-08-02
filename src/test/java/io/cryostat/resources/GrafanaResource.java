package io.cryostat.resources;
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
import java.util.Map;
import java.util.Optional;

import io.quarkus.test.common.DevServicesContext;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public class GrafanaResource
        implements QuarkusTestResourceLifecycleManager, DevServicesContext.ContextAware {

    private static int GRAFANA_PORT = 3000;
    private static String IMAGE_NAME = "quay.io/cryostat/cryostat-grafana-dashboard:latest";
    private static Map<String, String> envMap =
            Map.of(
                    "GF_INSTALL_PLUGINS", "grafana-simple-json-datasource",
                    "GF_AUTH_ANONYMOUS_ENABLED", "tru",
                    "JFR_DATASOURCE_URL", "http://jfr-datasource:8080");

    private Optional<String> containerNetworkId;
    private GenericContainer<?> container;

    @Override
    public Map<String, String> start() {
        container =
                new GenericContainer<>(DockerImageName.parse(IMAGE_NAME))
                        .withExposedPorts(GRAFANA_PORT)
                        .withEnv(envMap)
                        .withLogConsumer(outputFrame -> {});
        containerNetworkId.ifPresent(container::withNetworkMode);

        container.start();

        String networkHostPort =
                "http://" + container.getHost() + ":" + container.getMappedPort(GRAFANA_PORT);

        return Map.of("grafana-dashboard.url", networkHostPort);
    }

    @Override
    public void stop() {
        container.stop();
    }

    @Override
    public void setIntegrationTestContext(DevServicesContext context) {
        containerNetworkId = context.containerNetworkId();
    }
}
