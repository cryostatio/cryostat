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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import io.cryostat.ConfigProperties;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@Path("")
public class TrustStore {

    @ConfigProperty(name = ConfigProperties.SSL_TRUSTSTORE_DIR)
    java.nio.file.Path trustStoreDir;

    @Inject Logger logger;

    @Blocking
    @GET
    @Path("/api/v3/tls/certs")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> listCerts() throws IOException {
        var accessible =
                Files.exists(trustStoreDir)
                        && Files.isDirectory(trustStoreDir)
                        && Files.isReadable(trustStoreDir)
                        && Files.isExecutable(trustStoreDir);
        if (!accessible) {
            return List.of();
        }
        return Files.walk(trustStoreDir)
                .map(java.nio.file.Path::toFile)
                .filter(File::isFile)
                .map(File::getPath)
                .toList();
    }
}
