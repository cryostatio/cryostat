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
package io.cryostat.discovery;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.cryostat.ConfigProperties;
import io.cryostat.credentials.Credential;
import io.cryostat.expressions.MatchExpression;
import io.cryostat.targets.Target;
import io.cryostat.targets.Target.Annotations;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.util.URIUtil;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.common.annotation.Blocking;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;

/**
 * Discovery implementation that allows user-defined {@link io.cryostat.target.Target} definitions.
 * These can be created or deleted at will by any authenticated and authorized user. This is mainly
 * intended for ad-hoc connections to short-lived target instances, or cases where Cryostat is only
 * deployed for a short term. For long-lived Cryostat installations monitoring regular target
 * applications other discovery mechanisms should be preferred.
 */
@ApplicationScoped
@Path("")
public class CustomDiscovery {

    public static final Pattern HOST_PORT_PAIR_PATTERN =
            Pattern.compile("^([^:\\s]+)(?::(\\d{1,5}))$");
    private static final String REALM = "Custom Targets";

    @Inject Logger logger;
    @Inject EventBus bus;
    @Inject TargetConnectionManager connectionManager;
    @Inject URIUtil uriUtil;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration timeout;

    @Blocking
    @POST
    @Path("/api/v4/targets")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("write")
    @Operation(
            summary = "Create a target definition",
            description =
                    """
                    Create a target definition given a JSON request body target stub. The target stub must contain the
                    connectUrl and alias, and optionally contain a username and password to create a Stored Credential
                    associated with this target. The dryrun parameter can be used to perform this operation as a check,
                    to verify if such a target could be created (no connectUrl conflict and acceptable credentials).
                    """)
    public RestResponse<Target> create(
            @Context UriInfo uriInfo,
            TargetStub target,
            @RestQuery boolean dryrun,
            @RestQuery boolean storeCredentials) {
        return doCreate(uriInfo, target, dryrun, storeCredentials);
    }

    @Blocking
    @POST
    @Path("/api/v4/targets")
    @Consumes({MediaType.MULTIPART_FORM_DATA, MediaType.APPLICATION_FORM_URLENCODED})
    @RolesAllowed("write")
    @Operation(
            summary = "Create a target definition",
            description =
                    """
                    Create a target definition given a form (url-encoded or multipart) containing a target connectUrl
                    and alias, and optional username and password. The dryrun parameter can be used to perform this
                    operation as a check, to verify if such a target could be created (no connectUrl conflict and
                    acceptable credentials).
                    """)
    public RestResponse<Target> createForm(
            @Context UriInfo uriInfo,
            @RestForm URI connectUrl,
            @RestForm String alias,
            @RestForm String username,
            @RestForm String password,
            @RestQuery boolean dryrun,
            @RestQuery boolean storeCredentials) {
        return doCreate(
                uriInfo,
                new TargetStub(connectUrl, alias, username, password),
                dryrun,
                storeCredentials);
    }

    RestResponse<Target> doCreate(
            UriInfo uriInfo, TargetStub targetStub, boolean dryrun, boolean storeCredentials) {
        try {
            var target = targetStub.asTarget();
            var credential = targetStub.getCredential();
            target.connectUrl = sanitizeConnectUrl(target.connectUrl.toString());
            if (!uriUtil.validateUri(target.connectUrl)) {
                throw new BadRequestException(
                        String.format(
                                "The provided URI \"%s\" is unacceptable with the"
                                        + " current URI range settings.",
                                target.connectUrl));
            }

            if (QuarkusTransaction.joiningExisting()
                            .call(() -> Target.find("connectUrl", target.connectUrl).count())
                    > 0) {
                throw new BadRequestException("Duplicate connection URL");
            }

            try {
                String jvmId =
                        target.jvmId =
                                connectionManager
                                        .executeDirect(
                                                target,
                                                credential,
                                                conn -> conn.getJvmIdentifier().getHash())
                                        .await()
                                        .atMost(timeout);

                if (QuarkusTransaction.joiningExisting()
                                .call(() -> Target.find("jvmId", jvmId).count())
                        > 0) {
                    throw new BadRequestException(
                            String.format("Target with JVM ID \"%s\" already discovered", jvmId));
                }
            } catch (Exception e) {
                logger.error("Target connection failed", e);
                String msg =
                        connectionManager.isJmxSslFailure(e)
                                ? "Untrusted JMX SSL/TLS certificate"
                                : connectionManager.isJmxAuthFailure(e)
                                        ? "JMX authentication failure"
                                        : connectionManager.isServiceTypeFailure(e)
                                                ? "Unexpected service type on port"
                                                : "Target connection failed";
                throw new BadRequestException(msg, e);
            }

            if (dryrun) {
                return ResponseBuilder.accepted(target).build();
            }

            return QuarkusTransaction.requiringNew()
                    .call(
                            () -> {
                                target.persist();
                                credential.ifPresent(c -> c.persist());

                                target.activeRecordings = new ArrayList<>();
                                target.annotations = new Annotations(null, Map.of("REALM", REALM));

                                DiscoveryNode node =
                                        DiscoveryNode.target(target, NodeType.BaseNodeType.JVM);
                                target.discoveryNode = node;
                                DiscoveryNode realm = DiscoveryNode.getRealm(REALM).orElseThrow();

                                realm.children.add(node);
                                node.parent = realm;
                                target.persist();
                                node.persist();
                                realm.persist();

                                return ResponseBuilder.<Target>created(
                                                uriInfo.getAbsolutePathBuilder()
                                                        .path(Long.toString(target.id))
                                                        .build())
                                        .entity(target)
                                        .build();
                            });
        } catch (Exception e) {
            if (ExceptionUtils.indexOfType(e, ConstraintViolationException.class) >= 0) {
                logger.warn("Invalid target definition", e);
                throw new BadRequestException("Duplicate connection URL", e);
            }
            logger.error("Unknown error", e);
            throw new InternalServerErrorException(e);
        }
    }

    @Transactional
    @DELETE
    @Path("/api/v4/targets/{id}")
    @RolesAllowed("write")
    @Operation(
            summary = "Delete the specified target",
            description =
                    """
                    Delete the specified target by ID. Only allows deletion of targets that were defined by the same
                    Custom Target discovery API. Other targets must be removed by the discovery mechanisms which
                    discovered them.
                    """)
    public void delete(@RestPath long id) throws URISyntaxException {
        Target target = Target.find("id", id).singleResult();
        DiscoveryNode realm = DiscoveryNode.getRealm(REALM).orElseThrow();
        boolean withinRealm = realm.children.remove(target.discoveryNode);
        if (!withinRealm) {
            throw new BadRequestException();
        }
        target.discoveryNode.parent = null;
        realm.persist();
        target.delete();
    }

    private URI sanitizeConnectUrl(String in) throws URISyntaxException, MalformedURLException {
        URI out;

        Matcher m = HOST_PORT_PAIR_PATTERN.matcher(in);
        if (m.find()) {
            String host = m.group(1);
            String port = m.group(2);
            out =
                    URI.create(
                            String.format(
                                    "service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi",
                                    host, Integer.valueOf(port)));
        } else {
            out = new URI(in);
        }

        return out;
    }

    record TargetStub(URI connectUrl, String alias, String username, String password) {
        Target asTarget() {
            var t = new Target();
            t.alias = alias;
            t.connectUrl = connectUrl;
            return t;
        }

        Optional<Credential> getCredential() {
            Credential credential = null;
            if (StringUtils.isNotBlank(username) && StringUtils.isNotBlank(password)) {
                credential = new Credential();
                credential.matchExpression =
                        new MatchExpression(
                                String.format(
                                        "target.connectUrl == \"%s\"", connectUrl.toString()));
                credential.username = username;
                credential.password = password;
            }
            return Optional.ofNullable(credential);
        }
    }
}
