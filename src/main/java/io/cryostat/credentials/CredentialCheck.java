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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

import io.cryostat.V2Response;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response.Status;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;

@Path("")
public class CredentialCheck {

    @Inject TargetConnectionManager connectionManager;

    @POST
    @Blocking
    @RolesAllowed("read")
    @Path("/api/beta/credentials/{connectUrl}")
    public Uni<V2Response> checkCredentialForTarget(
            @RestPath String connectUrl, @RestForm String username, @RestForm String password)
            throws URISyntaxException {
        Target target = Target.getTargetByConnectUrl(new URI(connectUrl));
        return connectionManager
                .executeDirect(
                        target,
                        Optional.empty(),
                        (conn) -> {
                            conn.connect();
                            return CredentialTestResult.NA;
                        })
                .onFailure()
                .recoverWithUni(
                        () -> {
                            Credential cred = new Credential();
                            cred.username = username;
                            cred.password = password;
                            return connectionManager
                                    .executeDirect(
                                            target,
                                            Optional.of(cred),
                                            (conn) -> {
                                                conn.connect();
                                                return CredentialTestResult.SUCCESS;
                                            })
                                    .onFailure(
                                            t ->
                                                    connectionManager.isJmxAuthFailure(t)
                                                            || connectionManager.isAgentAuthFailure(
                                                                    t))
                                    .recoverWithItem(t -> CredentialTestResult.FAILURE);
                        })
                .map(r -> V2Response.json(Status.OK, r));
    }

    static enum CredentialTestResult {
        SUCCESS,
        FAILURE,
        NA;
    }
}
