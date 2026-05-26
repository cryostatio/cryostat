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
package io.cryostat;

import java.net.URI;
import java.util.Optional;

import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("")
class Liveness {

    @Inject TargetConnectionManager tcm;

    @GET
    @Blocking
    @Path("/health/liveness")
    @PermitAll
    @Operation(
            summary = "Check if the application is able to accept and respond to requests.",
            description =
                    """
                    Performs a simple target connection request on a worker thread.
                    This is a simply check to determine if the application has available threads
                    to service requests. HTTP 204 No Content is the only expected response.
                    If the application is not live and no worker threads are available,
                    then the client will never receive a response.
                    """)
    public Uni<Void> liveness() {
        Target self = new Target();
        self.connectUrl = URI.create("service:jmx:rmi:///jndi/rmi://localhost:0/jmxrmi");
        return tcm.executeDirect(
                self,
                Optional.empty(),
                conn -> {
                    conn.getJvmIdentifier();
                    return null;
                });
    }
}
