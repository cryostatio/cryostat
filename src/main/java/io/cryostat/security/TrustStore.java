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
package io.cryostat.security;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import io.cryostat.ConfigProperties;
import io.cryostat.DeclarativeConfiguration;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;

@Path("/api/v4/tls/certs")
public class TrustStore {

    @ConfigProperty(name = ConfigProperties.SSL_TRUSTSTORE_DIR)
    java.nio.file.Path trustStoreDir;

    @Inject DeclarativeConfiguration declarativeConfiguration;
    @Inject Logger logger;

    @Blocking
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "List additional trusted SSL/TLS certificates",
            description =
                    """
                    In addition to the standard system/OpenJDK certificate trust store, Cryostat can be configured to
                    trust additional certificates which may be presented by target JVM JMX servers or by Cryostat Agent
                    HTTPS servers. This endpoint returns a list of local file paths to additional certificate files,
                    which Cryostat will have loaded into an additional trust store at startup.
                    """)
    public List<String> listCerts() throws IOException {
        var accessible =
                Files.exists(trustStoreDir)
                        && Files.isDirectory(trustStoreDir)
                        && Files.isReadable(trustStoreDir)
                        && Files.isExecutable(trustStoreDir);
        if (!accessible) {
            return List.of();
        }
        return declarativeConfiguration.walk(trustStoreDir).stream()
                .map(java.nio.file.Path::toString)
                .toList();
    }
}
