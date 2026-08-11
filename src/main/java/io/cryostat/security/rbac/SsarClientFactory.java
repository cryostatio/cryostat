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
package io.cryostat.security.rbac;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Creates Fabric8 {@link KubernetesClient} instances authenticated with caller-supplied bearer
 * tokens for use in SelfSubjectAccessReview calls. The cluster host and CA configuration is
 * auto-detected from the in-cluster service-account environment.
 */
@ApplicationScoped
public class SsarClientFactory {

    @Inject KubernetesClient baseClient;

    /**
     * Build a new {@link KubernetesClient} that carries {@code rawToken} as the OAuth bearer token.
     * All other connection parameters (host, port, CA cert) are inherited from the auto-detected
     * cluster configuration of the injected base client.
     *
     * @param rawToken the raw bearer token string to authenticate with
     * @return a freshly constructed client instance; caller is responsible for closing it
     */
    public KubernetesClient createClientForToken(String rawToken) {
        Config baseConfig = baseClient.getConfiguration();
        Config tokenConfig = new ConfigBuilder(baseConfig).withOauthToken(rawToken).build();
        return new KubernetesClientBuilder().withConfig(tokenConfig).build();
    }
}
