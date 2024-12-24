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

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

public class AuthProxyContainer extends GenericContainer<AuthProxyContainer> {

    private static final String IMAGE_NAME = "quay.io/oauth2-proxy/oauth2-proxy:latest";
    private static final String CFG_FILE_PATH = "/tmp/auth_proxy_alpha_config.yml";
    static final int PORT = 8080;
    static final String ALIAS = "authproxy";
    private static final Map<String, String> envMap =
            new HashMap<>(
                    Map.of(
                            "OAUTH2_PROXY_REDIRECT_URL",
                            "http://localhost:8080/oauth2/callback",
                            "OAUTH2_PROXY_COOKIE_SECRET",
                            "__24_BYTE_COOKIE_SECRET_",
                            "OAUTH2_PROXY_SKIP_AUTH_ROUTES",
                            ".*",
                            "OAUTH2_PROXY_EMAIL_DOMAINS",
                            "*"));
    private static final String ALPHA_CONFIG =
            """
server:
  BindAddress: http://AUTHPROXY_HOST:AUTHPROXY_PORT
upstreamConfig:
  proxyRawPath: true
  upstreams:
    - id: cryostat
      path: /
      uri: http://cryostat:CRYOSTAT_PORT
providers:
  - id: dummy
    name: Unused - Sign In Below
    clientId: CLIENT_ID
    clientSecret: CLIENT_SECRET
    provider: google
""";

    public AuthProxyContainer(Optional<Network> network, int cryostatPort) {
        super(IMAGE_NAME);
        network.ifPresent(this::withNetwork);
        withCommand(String.format("--alpha-config=%s", CFG_FILE_PATH));
        withExposedPorts(PORT);
        withNetworkAliases(ALIAS);
        withEnv(envMap);
        withCopyToContainer(
                Transferable.of(
                        ALPHA_CONFIG
                                .replaceAll("AUTHPROXY_HOST", "0.0.0.0")
                                .replaceAll("AUTHPROXY_PORT", Integer.toString(PORT))
                                .replaceAll("CRYOSTAT_PORT", Integer.toString(cryostatPort))),
                CFG_FILE_PATH);
        waitingFor(Wait.forLogMessage(".*OAuthProxy configured.*", 1));
    }
}
