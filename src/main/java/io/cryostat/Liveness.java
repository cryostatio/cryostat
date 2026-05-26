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

import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("")
class Liveness {

    @GET
    // This does not actually block, but we force it to execute on the worker pool so that the
    // status check reports not only that the event loop dispatch thread is alive and responsive,
    // but that the worker pool is also actively servicing requests. If we don't force this then
    // this handler only checks if the event loop is alive, but the worker pool may be blocked or
    // otherwise unresponsive and the application as a whole will not be usable.
    @Blocking
    @Path("/health/liveness")
    @PermitAll
    @Operation(
            summary = "Check if the application is able to accept and respond to requests.",
            description =
                    """
                    Performs a no-op on a worker thread. This is a simply check to determine if
                    the application has available threads to service requests. HTTP 204 No Content
                    is the only expected response. If the application is not live and no worker
                    threads are available, then the client will never receive a response.
                    """)
    public void liveness() {}
}
