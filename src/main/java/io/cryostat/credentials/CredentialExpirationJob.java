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
package io.cryostat.credentials;

import java.util.List;

import io.cryostat.ConfigProperties;
import io.cryostat.discovery.DiscoveryPlugin;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CredentialExpirationJob {

    @Inject Logger logger;

    @ConfigProperty(name = ConfigProperties.CREDENTIAL_EXPIRATION)
    String credentialExpiration;

    @Scheduled(every = "${" + ConfigProperties.CREDENTIAL_EXPIRATION + "}")
    @Transactional
    void cleanupExpiredCredentials() {
        List<Credential> expired = Credential.findExpired();
        for (Credential credential : expired) {
            long pluginCount = DiscoveryPlugin.count("credential", credential);
            if (pluginCount == 0) {
                logger.infov("Deleting expired credential {0}", credential.id);
                credential.delete();
            }
        }
    }
}
