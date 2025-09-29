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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.cryostat.ConfigProperties;
import io.cryostat.DeclarativeConfiguration;
import io.cryostat.expressions.MatchExpression;
import io.cryostat.expressions.MatchExpression.TargetMatcher;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;
import org.projectnessie.cel.tools.ScriptException;

@Path("/api/v4/credentials")
public class Credentials {

    @ConfigProperty(name = ConfigProperties.CREDENTIALS_DIR)
    java.nio.file.Path dir;

    @Inject DeclarativeConfiguration declarativeConfiguration;
    @Inject TargetConnectionManager connectionManager;
    @Inject TargetMatcher targetMatcher;
    @Inject ObjectMapper mapper;
    @Inject Logger logger;

    @Transactional
    void onStart(@Observes StartupEvent evt) {
        try {
            declarativeConfiguration
                    .walk(dir)
                    .forEach(
                            path -> {
                                logger.tracev("Creating credential from path: {0}", path);
                                try (var is = new BufferedInputStream(Files.newInputStream(path))) {
                                    var credential = mapper.readValue(is, Credential.class);
                                    // FIXME: Persisting the matchExpression here will allow
                                    // duplicates since the matchExpression gets a new ID. If the
                                    // data model gets reworked to deduplicate we'll need to add
                                    // application logic here to link it to the existing match
                                    // expression.
                                    credential.persist();
                                } catch (Exception e) {
                                    logger.error("Failed to create credentials from file", e);
                                }
                            });
        } catch (IOException e) {
            logger.error(e);
        }
    }

    @POST
    @Blocking
    @RolesAllowed("read")
    @Path("/test/{targetId}")
    @Operation(
            summary =
                    "Test if the supplied username/password are valid credentials for the specified"
                            + " target.")
    public Uni<CredentialTestResult> checkCredentialForTarget(
            @RestPath long targetId, @RestForm String username, @RestForm String password)
            throws URISyntaxException {
        Target target = Target.getTargetById(targetId);
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
                        });
    }

    @Blocking
    @Bulkhead
    @GET
    @RolesAllowed("read")
    @Operation(
            summary = "List information about all of the available Stored Credentials.",
            description =
                    """
                    Returns a list of match results. A match result includes the Stored Credential's ID,
                    its Match Expression, and a list of currently discovered Targets which match that expression
                    and are therefore candidates for Cryostat to select this Credential.
                    """)
    public List<CredentialMatchResult> list() {
        return Credential.<Credential>listAll().stream()
                .map(
                        c -> {
                            try {
                                return safeResult(c, targetMatcher);
                            } catch (ScriptException e) {
                                logger.warn(e);
                                return null;
                            }
                        })
                .filter(Objects::nonNull)
                .toList();
    }

    @Blocking
    @Bulkhead
    @GET
    @RolesAllowed("read")
    @Path("/{id}")
    @Operation(
            summary = "Get information about a Stored Credential",
            description =
                    """
                    Get match result information about a specific Stored Credential. A match result includes the Stored
                    Credential's ID, its Match Expression, and a list of currently discovered Targets which match that
                    expression and are therefore candidates for Cryostat to select this Credential.
                    """)
    public CredentialMatchResult get(@RestPath long id) {
        try {
            Credential credential = Credential.find("id", id).singleResult();
            return safeResult(credential, targetMatcher);
        } catch (ScriptException e) {
            logger.error("Error retrieving credential", e);
            throw new InternalServerErrorException(e);
        }
    }

    @Transactional
    @POST
    @RolesAllowed("write")
    @Operation(
            summary = "Define a new Stored Credential",
            description =
                    """
                    Define a new Stored Credential. Requires a match expression which defines which targets require
                    this credential, and the username and password to use to pass authentication checks on those
                    targets. Stored Credentials are stored in an encrypted keyring using symmetric encryption and an
                    encryption key configured on the Cryostat database.
                    """)
    public RestResponse<Credential> create(
            @Context UriInfo uriInfo,
            @RestForm String matchExpression,
            @RestForm String username,
            @RestForm String password) {
        MatchExpression expr = new MatchExpression(matchExpression);
        expr.persist();
        Credential credential = new Credential();
        credential.matchExpression = expr;
        credential.username = username;
        credential.password = password;
        credential.persist();
        return ResponseBuilder.<Credential>created(
                        uriInfo.getAbsolutePathBuilder().path(Long.toString(credential.id)).build())
                .entity(credential)
                .build();
    }

    @Transactional
    @DELETE
    @RolesAllowed("write")
    @Path("/{id}")
    @Operation(summary = "Delete a Stored Credential")
    public void delete(@RestPath long id) {
        Credential.find("id", id).singleResult().delete();
    }

    static CredentialMatchResult notificationResult(Credential credential) throws ScriptException {
        // TODO populating this on the credential post-persist hook leads to a database validation
        // error because the expression ends up getting defined twice with the same ID, somehow.
        // Populating this field with 0 means the UI is inaccurate when a new credential is first
        // defined, but after a refresh the data correctly updates.
        return new CredentialMatchResult(credential, List.of());
    }

    static CredentialMatchResult safeResult(Credential credential, TargetMatcher matcher)
            throws ScriptException {
        var matchedTargets = matcher.match(credential.matchExpression).targets();
        return new CredentialMatchResult(credential, matchedTargets);
    }

    static record CredentialMatchResult(
            long id, MatchExpression matchExpression, Collection<Target> targets) {
        CredentialMatchResult(Credential credential, Collection<Target> targets) {
            this(credential.id, credential.matchExpression, new ArrayList<>(targets));
        }
    }

    static enum CredentialTestResult {
        SUCCESS,
        FAILURE,
        NA;
    }
}
