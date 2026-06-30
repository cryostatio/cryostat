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

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.cryostat.ConfigProperties;
import io.cryostat.credentials.Credential;
import io.cryostat.discovery.DiscoveryPlugin.PluginCallback;
import io.cryostat.discovery.DiscoveryPlugin.PluginCleanupHelper;
import io.cryostat.discovery.KubeEndpointSlicesDiscovery.KubeDiscoveryNodeType;
import io.cryostat.discovery.NodeType.BaseNodeType;
import io.cryostat.expressions.MatchExpression;
import io.cryostat.targets.Target.Annotations;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.util.URIUtil;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jwt.proc.BadJWTException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Parameters;
import io.quarkus.runtime.ShutdownEvent;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.faulttolerance.api.RateLimit;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriBuilder;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestHeader;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.TriggerBuilder;
import org.quartz.impl.matchers.GroupMatcher;

@Path("")
public class Discovery {

    private static final String SYNTHETIC_REALM_NAME = "Cryostat Discovery";

    static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private static final String JOB_PERIODIC = "discovery.periodic";
    private static final String PLUGIN_ID_MAP_KEY = "pluginId";
    private static final String REFRESH_MAP_KEY = "refresh";

    public static final String DISCOVERY_PLUGIN_LABEL_PREFIX = "discovery.cryostat.io/";
    public static final String DISCOVERY_PLUGIN_ID_LABEL_KEY =
            DISCOVERY_PLUGIN_LABEL_PREFIX + "plugin-id";

    @ConfigProperty(name = ConfigProperties.DISCOVERY_PLUGINS_PING_PERIOD)
    Duration discoveryPingPeriod;

    @ConfigProperty(name = ConfigProperties.DISCOVERY_PLUGINS_MAX_FAILURES)
    int maxConsecutiveFailures;

    @ConfigProperty(name = ConfigProperties.AGENT_TLS_REQUIRED)
    boolean agentTlsRequired;

    @Inject Logger logger;
    @Inject ObjectMapper mapper;
    @Inject EventBus bus;
    @Inject TargetConnectionManager connectionManager;
    @Inject DiscoveryJwtFactory jwtFactory;
    @Inject DiscoveryJwtValidator jwtValidator;
    @Inject Scheduler scheduler;
    @Inject URIUtil uriUtil;
    @Inject KubeEndpointSlicesDiscovery k8sDiscovery;
    @Inject PluginCallbackFactory callbackFactory;
    @Inject PluginCleanupHelper cleanupHelper;
    @Inject EntityManager entityManager;

    void onStop(@Observes ShutdownEvent evt) throws SchedulerException {
        scheduler.shutdown();
    }

    @GET
    @Path("/api/v4/discovery")
    @RolesAllowed("read")
    @Operation(summary = "Retrieve the entire discovery tree.")
    public DiscoveryNode get(
            @QueryParam("mergeRealms") @DefaultValue("false") boolean mergeRealms) {
        if (!mergeRealms) {
            return DiscoveryNode.getUniverse();
        }
        return mergeRealms();
    }

    @GET
    @Path("/api/v4/discovery/{id}")
    @RolesAllowed("read")
    @Tag(ref = "Discovery")
    @Operation(
            summary = "Endpoint for discovery plugins to check their own registration status",
            description =
                    """
                    Endpoint for discovery plugins to check their own current registration status, ie. whether their
                    registration ID is still known and their current token is still valid.
                    """)
    public void checkRegistration(
            @Context RoutingContext ctx,
            @RestPath UUID id,
            @RestHeader("Cryostat-Discovery-Authentication") String token) {
        DiscoveryPlugin plugin = DiscoveryPlugin.find("id", id).singleResult();
        try {
            jwtValidator.validateJwt(ctx, plugin, token, true);
        } catch (MalformedURLException
                | URISyntaxException
                | UnknownHostException
                | SocketException
                | JOSEException
                | ParseException e) {
            throw new BadRequestException(e);
        }
    }

    @POST
    @Blocking
    @RolesAllowed("read")
    @Consumes({MediaType.MULTIPART_FORM_DATA, MediaType.APPLICATION_FORM_URLENCODED})
    @Path("/api/beta/discovery/credential_exists")
    @Operation(
            summary =
                    "Check if a Credential already exists with an identical MatchExpression"
                            + " script.")
    public RestResponse<Credential> checkCredentialExists(@RestForm String script) {
        var result = Credential.find("matchExpression.script", script);
        if (result.count() == 0) {
            return RestResponse.notFound();
        }
        return RestResponse.ok(result.firstResult());
    }

    @Bulkhead
    @Timeout
    @Retry(retryOn = {OptimisticLockException.class})
    @RateLimit
    @Blocking
    @POST
    @Path("/api/v4/discovery")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("write")
    @Tag(
            name = "Discovery",
            description =
                    """
                    Endpoints for Discovery Plugins to register with a Cryostat instance, refresh their registration
                    token, publish information about known targets, and unregister themselves.

                    The reference implementation of a Discovery Plugin is the Cryostat Agent.
                    """)
    @Operation(
            summary = "Register as a new discovery plugin or refresh existing registration",
            description =
                    """
                    Register a new discovery plugin, or refresh an existing plugin's registration and generate a new
                    token. New registrations require the realm and callback fields. Registration refreshers
                    additionally require the id and token fields, which are supplied in the response to the original
                    registration.
                    """)
    public PluginRegistration register(@Context RoutingContext ctx, JsonObject body)
            throws SchedulerException {
        String pluginId = body.getString("id");
        String priorToken = body.getString("token");
        String realmName = body.getString("realm");
        CallbackValidation callback =
                validateCallback(ctx, body.getString("callback"), "cryostat.target.callback");

        List<URI> locations;
        DiscoveryPlugin plugin;
        if (StringUtils.isNotBlank(pluginId) && StringUtils.isNotBlank(priorToken)) {
            // refresh the JWT for existing registration
            plugin =
                    DiscoveryPlugin.<DiscoveryPlugin>find("id", UUID.fromString(pluginId))
                            .singleResult();
            if (!Objects.equals(plugin.realm.name, realmName)) {
                throw new ForbiddenException();
            }
            if (!Objects.equals(plugin.callback, callback.unauthCallback())) {
                throw new BadRequestException("plugin callback mismatch");
            }
            try {
                locations = jwtFactory.getPluginLocations(plugin);
                jwtFactory.parseDiscoveryPluginJwt(
                        plugin, priorToken, locations, callback.remoteAddress(), false);
            } catch (URISyntaxException
                    | UnknownHostException
                    | SocketException
                    | BadJWTException
                    | JOSEException
                    | ParseException e) {
                throw new BadRequestException(e);
            } catch (NoSuchAlgorithmException e) {
                throw new InternalServerErrorException(e);
            }
        } else {
            // check if a plugin record with the same callback and realm-name already exists. If it
            // does, ping it:
            // - if it's still there reject this request as a duplicate
            // - otherwise delete the previous record and accept this new one as a replacement
            plugin =
                    QuarkusTransaction.joiningExisting()
                            .call(
                                    () ->
                                            findOrCreatePlugin(
                                                    callback.callbackUri(),
                                                    callback.unauthCallback(),
                                                    realmName,
                                                    null));

            try {
                locations = jwtFactory.getPluginLocations(plugin);
            } catch (URISyntaxException e) {
                throw new BadRequestException(e);
            }

            schedulePluginJob(plugin, true);
        }

        String token;
        try {
            token =
                    jwtFactory.createDiscoveryPluginJwt(
                            plugin, callback.remoteAddress(), locations);
        } catch (URISyntaxException | JOSEException | UnknownHostException | SocketException e) {
            throw new BadRequestException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new InternalServerErrorException(e);
        }

        return new PluginRegistration(plugin.id.toString(), token, getEnvMap());
    }

    @Bulkhead
    @Timeout
    @Retry(retryOn = {OptimisticLockException.class})
    @RateLimit
    @Blocking
    @POST
    @Path("/api/v4.3/discovery/agents")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("write")
    @Tag(ref = "Discovery")
    @Operation(
            summary = "Register and publish a Cryostat Agent",
            description =
                    """
                    Register a Cryostat Agent as a discovery plugin, create its associated Stored Credential, and
                    publish its target nodes in one request. This endpoint is intentionally Agent-specific; the
                    general Discovery Plugin registration and publication endpoints remain available for other
                    Discovery Plugin implementations.
                    """)
    public PluginRegistration registerAgent(@Context RoutingContext ctx, AgentRegistration body)
            throws SchedulerException {
        if (body == null) {
            throw new BadRequestException("body is required");
        }

        CallbackValidation callback =
                validateCallback(ctx, body.callback(), "cryostat.agent.callback");
        Credential credential = credentialFrom(body.credential());
        DiscoveryPublication publication =
                new DiscoveryPublication(
                        Optional.ofNullable(body.nodes()).orElseGet(List::of),
                        Optional.ofNullable(body.fillStrategy()),
                        Optional.ofNullable(body.context()));

        // Ping the Agent before opening any transaction, so we do not hold one open across the
        // network round-trip. See cryostatio/cryostat#1604.
        PrePingResult prePingResult =
                pingAgentCallback(callback.unauthCallback(), credential)
                        ? PrePingResult.REACHABLE
                        : PrePingResult.UNREACHABLE;

        DiscoveryPlugin plugin =
                QuarkusTransaction.joiningExisting()
                        .call(
                                () -> {
                                    DiscoveryPlugin p =
                                            findOrCreatePlugin(
                                                    callback.callbackUri(),
                                                    callback.unauthCallback(),
                                                    body.realm(),
                                                    credential,
                                                    prePingResult);
                                    replaceCredential(p, credential);
                                    return p;
                                });

        List<URI> locations;
        try {
            locations = jwtFactory.getPluginLocations(plugin);
        } catch (URISyntaxException e) {
            throw new BadRequestException(e);
        }

        schedulePluginJob(plugin, false);

        String token;
        try {
            token =
                    jwtFactory.createDiscoveryPluginJwt(
                            plugin, callback.remoteAddress(), locations);
        } catch (URISyntaxException | JOSEException | UnknownHostException | SocketException e) {
            throw new BadRequestException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new InternalServerErrorException(e);
        }

        UUID pluginId = plugin.id;
        QuarkusTransaction.joiningExisting()
                .run(
                        () ->
                                publishPluginTree(
                                        DiscoveryPlugin.<DiscoveryPlugin>find("id", pluginId)
                                                .singleResult(),
                                        publication));

        return new PluginRegistration(plugin.id.toString(), token, getEnvMap());
    }

    @Transactional
    @Bulkhead
    @Timeout
    @Retry(retryOn = {OptimisticLockException.class})
    @RateLimit
    @POST
    @Path("/api/v4/discovery/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @PermitAll
    @Tag(ref = "Discovery")
    @Operation(
            summary = "Publish updated target discovery information",
            description =
                    """
                    Using its plugin ID and current token, a discovery plugin uses this endpoint to publish a JSON
                    request body containing a list of discovery nodes. The discovery plugin itself is a Realm node in
                    the overall discovery tree, so the published list of nodes here will replace the plugin Realm
                    node's list of children.
                    """)
    public void publish(
            @Context RoutingContext ctx,
            @RestPath UUID id,
            @RestHeader("Cryostat-Discovery-Authentication") String token,
            List<DiscoveryNode> body) {
        publishWithContext(
                ctx,
                id,
                token,
                new DiscoveryPublication(
                        body, Optional.of(DiscoveryFillStrategy.NONE), Optional.of(Map.of())));
    }

    @Transactional
    @Bulkhead
    @Timeout
    @Retry(retryOn = {OptimisticLockException.class})
    @RateLimit
    @POST
    @Path("/api/v4.2/discovery/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @PermitAll
    @Tag(ref = "Discovery")
    @Operation(
            summary = "Publish updated target discovery information",
            description =
                    """
                    Using its plugin ID and current token, a discovery plugin uses this endpoint to publish a JSON
                    request body containing a list of discovery nodes. The discovery plugin itself is a Realm node in
                    the overall discovery tree, so the published list of nodes here will replace the plugin Realm
                    node's list of children.
                    """)
    public void publishWithContext(
            @Context RoutingContext ctx,
            @RestPath UUID id,
            @RestHeader("Cryostat-Discovery-Authentication") String token,
            DiscoveryPublication body) {
        DiscoveryPlugin plugin = DiscoveryPlugin.find("id", id).singleResult();
        try {
            jwtValidator.validateJwt(ctx, plugin, token, true);
        } catch (MalformedURLException
                | URISyntaxException
                | UnknownHostException
                | SocketException
                | JOSEException
                | ParseException e) {
            throw new BadRequestException(e);
        }
        publishPluginTree(plugin, body);
    }

    private void publishPluginTree(DiscoveryPlugin plugin, DiscoveryPublication body) {
        for (var n : body.nodes) {
            validatePublishedNode(n);
        }

        for (var n : body.nodes) {
            n.target.discoveryNode = n;
        }

        // Reconcile published nodes against existing ones by connectUrl so surviving targets keep
        // their identity rather than being deleted and recreated (spurious LOST/FOUND). See #1604.
        boolean kubernetes =
                body.fillStrategy
                        .map(algo -> algo == DiscoveryFillStrategy.KUBERNETES)
                        .orElse(false);
        if (kubernetes) {
            reconcileKubernetesPublication(plugin, body);
        } else {
            DiscoveryNode realm =
                    entityManager.find(
                            DiscoveryNode.class, plugin.realm.id, LockModeType.PESSIMISTIC_WRITE);
            reconcileFlatChildren(realm, body.nodes);
        }
        plugin.persist();
    }

    @SuppressFBWarnings(
            value = "UC_USELESS_OBJECT",
            justification =
                    "existingByUrl is read via remove() to match survivors and iterated via"
                        + " values() to delete stale nodes; SpotBugs cannot see the side effects of"
                        + " the Panache delete()/persist() consumers.")
    private void reconcileKubernetesPublication(DiscoveryPlugin plugin, DiscoveryPublication body) {
        Map<String, String> pubCtx = body.context.orElse(Map.of());
        String namespace = pubCtx.get("namespace");
        String nodeType = pubCtx.get("nodetype");
        String name = pubCtx.get("name");
        if (StringUtils.isBlank(namespace)
                || StringUtils.isBlank(nodeType)
                || StringUtils.isBlank(name)) {
            String key;
            if (StringUtils.isBlank(namespace)) {
                key = "namespace";
            } else if (StringUtils.isBlank(nodeType)) {
                key = "nodeType";
            } else {
                key = "name";
            }
            throw new BadRequestException(
                    new IllegalArgumentException(String.format("%s cannot be blank", key)));
        }

        DiscoveryNode k8sRealm =
                DiscoveryNode.getRealm(KubeEndpointSlicesDiscovery.REALM)
                        .orElseThrow(
                                () -> new IllegalStateException("KubernetesApi realm not found"));

        Map<URI, DiscoveryNode> existingByUrl = new HashMap<>();
        for (DiscoveryNode node : DiscoveryNode.getByPluginId(plugin.id)) {
            if (node.target != null && node.target.connectUrl != null) {
                existingByUrl.put(node.target.connectUrl, node);
            }
        }

        DiscoveryNode nsNode =
                DiscoveryNode.<DiscoveryNode>find(
                                "#DiscoveryNode.byTypeWithName",
                                Parameters.with(
                                                "nodeType",
                                                KubeDiscoveryNodeType.NAMESPACE.getKind())
                                        .and("name", namespace))
                        .firstResultOptional()
                        .orElseGet(
                                () -> {
                                    DiscoveryNode newNs = new DiscoveryNode();
                                    newNs.name = namespace;
                                    newNs.nodeType = KubeDiscoveryNodeType.NAMESPACE.getKind();
                                    newNs.labels = new HashMap<>();
                                    newNs.children = new ArrayList<>();
                                    newNs.target = null;
                                    newNs.parent = k8sRealm;
                                    newNs.persist();
                                    return newNs;
                                });
        if (nsNode.parent == null || !nsNode.parent.equals(k8sRealm)) {
            nsNode.parent = k8sRealm;
        }

        DiscoveryNode lineage = k8sDiscovery.getOwnershipLineage(namespace, name, nodeType);
        KubeEndpointSlicesDiscovery.KubernetesMetadata k8sMetadata =
                k8sDiscovery.getKubernetesMetadata(namespace, name, nodeType);
        DiscoveryNode innermost = mergeLineageIntoTree(nsNode, lineage);

        // Persist new lineage nodes top-down so target nodes below reference a persisted parent.
        List<DiscoveryNode> lineageChain = new ArrayList<>();
        for (DiscoveryNode c = innermost; c != null && c != nsNode; c = c.parent) {
            lineageChain.add(c);
        }
        nsNode.persist();
        for (int i = lineageChain.size() - 1; i >= 0; i--) {
            lineageChain.get(i).persist();
        }

        for (var n : body.nodes) {
            enrichWithKubernetesMetadata(n, k8sMetadata);
            if (n.labels == null) {
                n.labels = new HashMap<>();
            }
            n.labels.put(DISCOVERY_PLUGIN_ID_LABEL_KEY, plugin.id.toString());

            DiscoveryNode existing = existingByUrl.remove(n.target.connectUrl);
            if (existing == null) {
                n.parent = innermost;
                innermost.children.add(n);
                n.persist();
                continue;
            }

            DiscoveryNode oldParent = existing.parent;
            existing.name = n.name;
            existing.nodeType = n.nodeType;
            existing.labels = n.labels;
            existing.target.alias = n.target.alias;
            if (n.target.labels != null) {
                existing.target.labels = n.target.labels;
            }
            if (n.target.annotations != null) {
                existing.target.annotations = n.target.annotations;
            }
            if (oldParent == null || !oldParent.equals(innermost)) {
                if (oldParent != null) {
                    oldParent.children.remove(existing);
                }
                existing.parent = innermost;
                innermost.children.add(existing);
            }
            existing.persist();
            if (oldParent != null && !oldParent.equals(innermost)) {
                cleanupHelper.pruneEmptyAncestors(oldParent);
            }
        }

        for (DiscoveryNode gone : existingByUrl.values()) {
            DiscoveryNode parent = gone.parent;
            if (parent != null) {
                parent.children.remove(gone);
            }
            gone.parent = null;
            gone.delete();
            cleanupHelper.pruneEmptyAncestors(parent);
        }
        entityManager.flush();
    }

    private void enrichWithKubernetesMetadata(
            DiscoveryNode n, KubeEndpointSlicesDiscovery.KubernetesMetadata k8sMetadata) {
        if (n.target == null) {
            return;
        }
        if (!k8sMetadata.labels().isEmpty()) {
            if (n.target.labels == null) {
                n.target.labels = new HashMap<>();
            }
            k8sMetadata.labels().forEach((k, v) -> n.target.labels.putIfAbsent(k, v));
        }
        if (!k8sMetadata.annotations().isEmpty()) {
            if (n.target.annotations == null) {
                n.target.annotations = new Annotations();
            }
            Map<String, String> platformAnnotations =
                    new HashMap<>(n.target.annotations.platform());
            k8sMetadata.annotations().forEach((k, v) -> platformAnnotations.putIfAbsent(k, v));
            n.target.annotations =
                    new Annotations(platformAnnotations, n.target.annotations.cryostat());
        }
    }

    @Transactional
    @DELETE
    @Path("/api/v4/discovery/{id}")
    @PermitAll
    @Tag(ref = "Discovery")
    @Operation(
            summary = "Delete the given plugin's registration",
            description =
                    """
                    Delete the plugin's registration along with its discovery Realm node and all of its children. This
                    is used when a discovery plugin is shutting down.
                    """)
    public void deregister(
            @Context RoutingContext ctx,
            @RestPath UUID id,
            @RestHeader("Cryostat-Discovery-Authentication") String token)
            throws SchedulerException {
        DiscoveryPlugin plugin = DiscoveryPlugin.findById(id);
        if (plugin == null) {
            logger.debugv("Could not find registered plugin with ID {0}", id);
            throw new NotFoundException();
        }

        if (plugin.builtin) {
            logger.debugv(
                    "Refusing to delete built-in plugin with ID {0} ({1})", id, plugin.realm.name);
            throw new ForbiddenException();
        }
        try {
            jwtValidator.validateJwt(ctx, plugin, token, false);
        } catch (MalformedURLException
                | URISyntaxException
                | UnknownHostException
                | SocketException
                | JOSEException
                | ParseException e) {
            logger.debugv("Refusing to delete plugin ID {0} due to invalid JWT", id);
            throw new BadRequestException(e);
        }

        Set<JobKey> jobKeys = new HashSet<>();
        jobKeys.addAll(scheduler.getJobKeys(GroupMatcher.jobGroupEquals(JOB_PERIODIC)));
        for (var key : jobKeys) {
            if (!Objects.equals(plugin.id.toString(), key.getName())) {
                continue;
            }
            scheduler.deleteJob(key);
        }

        plugin.delete();
    }

    @GET
    @JsonView(DiscoveryNode.Views.Flat.class)
    @Path("/api/v4/discovery_plugins")
    @RolesAllowed("read")
    @Tag(ref = "Discovery")
    @Operation(
            summary = "List currently registered discovery plugins",
            description =
                    """
                    Retrieve a list of currently registered discovery plugins only, not including their subtrees.
                    """)
    public List<DiscoveryPlugin> getPlugins(@RestQuery String realm) {
        // TODO filter for the matching realm name within the DB query
        return DiscoveryPlugin.findAll().<DiscoveryPlugin>list().stream()
                .filter(p -> StringUtils.isBlank(realm) || p.realm.name.equals(realm))
                .toList();
    }

    @GET
    @Path("/api/v4/discovery_plugins/{id}")
    @RolesAllowed("read")
    @Tag(ref = "Discovery")
    @Operation(
            summary = "Retrieve a specific discovery plugin",
            description =
                    """
                    Retrieve information about a specific discovery plugin, including its discovery Realm node and
                    subtree.
                    """)
    public DiscoveryPlugin getPlugin(@RestPath UUID id) {
        return DiscoveryPlugin.find("id", id).singleResult();
    }

    private void validatePublishedNode(DiscoveryNode currentNode) {
        if (currentNode.target == null) {
            throw new BadRequestException("Published nodes must have embedded targets");
        }
        try {
            if (!uriUtil.validateUri(currentNode.target.connectUrl)) {
                throw new BadRequestException(
                        String.format(
                                "Connect URL of \"%s\" is unacceptable with the"
                                        + " current URI range settings",
                                currentNode.target.connectUrl));
            }
        } catch (MalformedURLException e) {
            throw new BadRequestException(e);
        }
        if (!uriUtil.isJmxUrl(currentNode.target.connectUrl)) {
            if (agentTlsRequired && !currentNode.target.connectUrl.getScheme().equals("https")) {
                throw new BadRequestException(
                        String.format(
                                "TLS for agent connections is required by (%s)",
                                ConfigProperties.AGENT_TLS_REQUIRED));
            }
            if (!currentNode.target.connectUrl.getScheme().equals("https")
                    && !currentNode.target.connectUrl.getScheme().equals("http")) {
                throw new BadRequestException(
                        String.format(
                                "Target connect URL is neither JMX nor HTTP(S): (%s)",
                                currentNode.target.connectUrl.toString()));
            }
        }
    }

    private void deleteSubtree(DiscoveryNode node) {
        for (DiscoveryNode child : new ArrayList<>(node.children)) {
            deleteSubtree(child);
        }
        node.children.clear();
        node.parent = null;
        node.delete();
    }

    private void reconcileFlatChildren(DiscoveryNode realm, List<DiscoveryNode> incoming) {
        Map<URI, DiscoveryNode> existingByUrl = new HashMap<>();
        for (DiscoveryNode child : new ArrayList<>(realm.children)) {
            if (child.target != null && child.target.connectUrl != null) {
                existingByUrl.put(child.target.connectUrl, child);
            }
        }

        Set<URI> incomingUrls = new HashSet<>();
        for (DiscoveryNode n : incoming) {
            incomingUrls.add(n.target.connectUrl);
        }

        for (DiscoveryNode child : new ArrayList<>(realm.children)) {
            URI url = child.target != null ? child.target.connectUrl : null;
            if (url == null || !incomingUrls.contains(url)) {
                realm.children.remove(child);
                deleteSubtree(child);
            }
        }
        // Flush removals before inserts so Hibernate does not insert a re-published connectUrl
        // before deleting the node that held it (connectUrl is unique).
        entityManager.flush();

        for (DiscoveryNode n : incoming) {
            DiscoveryNode existing = existingByUrl.get(n.target.connectUrl);
            if (existing == null) {
                n.parent = realm;
                realm.children.add(n);
                n.persist();
                continue;
            }
            existing.name = n.name;
            existing.nodeType = n.nodeType;
            if (n.labels != null) {
                existing.labels = n.labels;
            }
            existing.target.alias = n.target.alias;
            if (n.target.labels != null) {
                existing.target.labels = n.target.labels;
            }
            if (n.target.annotations != null) {
                existing.target.annotations = n.target.annotations;
            }
            existing.persist();
        }
        realm.persist();
    }

    private CallbackValidation validateCallback(
            RoutingContext ctx, String callback, String parameterName) {
        URI callbackUri;
        try {
            if (StringUtils.isBlank(callback)) {
                throw new BadRequestException("callback cannot be blank");
            }
            callbackUri = new URI(callback);
        } catch (URISyntaxException e) {
            throw new BadRequestException(e);
        }
        URI unauthCallback = UriBuilder.fromUri(callbackUri).userInfo(null).build();

        InetAddress remoteAddress = getRemoteAddress(ctx);
        URI remoteURI;
        try {
            remoteURI = new URI(remoteAddress.getHostAddress());
        } catch (URISyntaxException e) {
            throw new BadRequestException(e);
        }

        try {
            if (!uriUtil.validateUri(callbackUri)) {
                throw new BadRequestException(
                        String.format(
                                "%s of \"%s\" is unacceptable with the current URI range settings",
                                parameterName, callbackUri));
            }
            if (!uriUtil.validateUri(remoteURI)) {
                throw new BadRequestException(
                        String.format(
                                "Remote Address of \"%s\" is unacceptable with the"
                                        + " current URI range settings",
                                remoteURI));
            }
        } catch (MalformedURLException e) {
            throw new BadRequestException(e);
        }

        for (var e : new String[] {callbackUri.getScheme(), callbackUri.getHost()}) {
            if (StringUtils.isBlank(e)) {
                throw new BadRequestException("callback must contain scheme and host");
            }
        }
        if (agentTlsRequired && !callbackUri.getScheme().equals("https")) {
            throw new BadRequestException(
                    String.format(
                            "TLS for agent connections is required by (%s)",
                            ConfigProperties.AGENT_TLS_REQUIRED));
        }

        return new CallbackValidation(callbackUri, unauthCallback, remoteAddress);
    }

    private DiscoveryPlugin findOrCreatePlugin(
            URI callbackUri, URI unauthCallback, String realmName, Credential credential) {
        return findOrCreatePlugin(
                callbackUri, unauthCallback, realmName, credential, PrePingResult.NOT_ATTEMPTED);
    }

    private DiscoveryPlugin findOrCreatePlugin(
            URI callbackUri,
            URI unauthCallback,
            String realmName,
            Credential credential,
            PrePingResult prePingResult) {
        Optional<DiscoveryPlugin> existing =
                DiscoveryPlugin.findByCallbackAndRealmName(unauthCallback, realmName);

        if (existing.isPresent()) {
            DiscoveryPlugin plugin = existing.get();
            logger.debugv("Reusing existing plugin: {0}", plugin.id);
            if (credential != null) {
                replaceCredential(plugin, credential);
            }
            return plugin;
        }

        // Check for plugin with same callback, different realm
        Optional<DiscoveryPlugin> byCallback =
                DiscoveryPlugin.<DiscoveryPlugin>find("callback", unauthCallback)
                        .singleResultOptional();

        if (byCallback.isPresent()) {
            DiscoveryPlugin p = byCallback.get();
            try {
                var cb = callbackFactory.create(p);
                cb.ping();
                // Plugin is reachable but has different realm
                throw new DuplicatePluginException(
                        String.format(
                                "Plugin with callback %s already exists and is still reachable",
                                unauthCallback));
            } catch (DuplicatePluginException e) {
                throw e;
            } catch (Exception e) {
                // Plugin unreachable, delete and create new
                logger.debug(e);
                UUID oldPluginId = p.id;
                try {
                    var toDelete = DiscoveryPlugin.<DiscoveryPlugin>findById(oldPluginId);
                    if (toDelete != null) {
                        toDelete.delete();
                        logger.debugv("Deleted unreachable plugin: {0}", oldPluginId);
                    } else {
                        logger.debugv(
                                "Plugin already deleted (concurrent cleanup): {0}", oldPluginId);
                    }
                } catch (Exception deleteEx) {
                    logger.debugv(
                            deleteEx,
                            "Failed to delete unreachable plugin (may already be deleted): {0}",
                            oldPluginId);
                }
            }
        }

        if (prePingResult == PrePingResult.UNREACHABLE) {
            throw new BadRequestException(
                    String.format("Agent callback is not reachable: %s", unauthCallback));
        }

        DiscoveryPlugin plugin = new DiscoveryPlugin();
        plugin.callback = callbackUri;
        plugin.realm =
                DiscoveryNode.environment(
                        requireNonBlank(realmName, "realm"), NodeType.BaseNodeType.REALM);
        plugin.builtin = false;
        if (credential != null) {
            plugin.credential = credential;
            credential.discoveryPlugin = plugin;
        }
        if (prePingResult == PrePingResult.REACHABLE) {
            // Record the ping we already did so prePersist skips its own (in-transaction) ping.
            plugin.lastSuccessfulPing = Instant.now();
        }

        var universe = DiscoveryNode.getUniverse();
        plugin.realm.parent = universe;
        plugin.persist();
        universe.children.add(plugin.realm);
        universe.persist();

        logger.debugv("Created new plugin: {0}", plugin.id);
        return plugin;
    }

    private boolean pingAgentCallback(URI unauthCallback, Credential credential) {
        try {
            callbackFactory.create(unauthCallback, credential).ping();
            logger.debugv("Agent callback reachable: {0}", unauthCallback);
            return true;
        } catch (Exception e) {
            logger.debugv(e, "Agent callback ping failed during registration: {0}", unauthCallback);
            return false;
        }
    }

    private void replaceCredential(DiscoveryPlugin plugin, Credential credential) {
        if (credential == null) {
            return;
        }
        if (credentialsEquivalent(plugin.credential, credential)) {
            return;
        }
        if (hasSameMatchExpression(plugin.credential, credential)) {
            plugin.credential.username = credential.username;
            plugin.credential.password = credential.password;
            plugin.credential.persist();
            plugin.persist();
            return;
        }
        if (plugin.credential != null) {
            plugin.credential.discoveryPlugin = null;
        }
        credential.discoveryPlugin = plugin;
        plugin.credential = credential;
        if (!credential.isPersistent()) {
            credential.persist();
        }
        plugin.persist();
    }

    private boolean credentialsEquivalent(Credential existing, Credential incoming) {
        if (existing == incoming) {
            return true;
        }
        if (existing == null || incoming == null) {
            return false;
        }
        return Objects.equals(existing.username, incoming.username)
                && Objects.equals(existing.password, incoming.password)
                && Objects.equals(matchExpressionScript(existing), matchExpressionScript(incoming));
    }

    private boolean hasSameMatchExpression(Credential existing, Credential incoming) {
        if (existing == null || incoming == null) {
            return false;
        }
        return Objects.equals(matchExpressionScript(existing), matchExpressionScript(incoming));
    }

    private String matchExpressionScript(Credential credential) {
        return credential.matchExpression == null ? null : credential.matchExpression.script;
    }

    private Credential credentialFrom(AgentCredentialRequest body) {
        if (body == null) {
            throw new BadRequestException("credential is required");
        }
        Credential credential = new Credential();
        credential.matchExpression =
                new MatchExpression(requireNonBlank(body.matchExpression(), "matchExpression"));
        credential.username = requireNonBlank(body.username(), "username");
        credential.password = requireNonBlank(body.password(), "password");
        return credential;
    }

    private void schedulePluginJob(DiscoveryPlugin plugin, boolean refresh)
            throws SchedulerException {
        JobKey jobKey = getPeriodicJobKey(plugin);
        if (scheduler.checkExists(jobKey)) {
            JobDetail jobDetail = scheduler.getJobDetail(jobKey);
            jobDetail.getJobDataMap().put(REFRESH_MAP_KEY, refresh);
            scheduler.addJob(jobDetail, true, true);
            return;
        }

        var dataMap = new JobDataMap();
        dataMap.put(PLUGIN_ID_MAP_KEY, plugin.id);
        dataMap.put(REFRESH_MAP_KEY, refresh);
        JobDetail jobDetail =
                JobBuilder.newJob(RefreshPluginJob.class)
                        .withIdentity(plugin.id.toString(), JOB_PERIODIC)
                        .usingJobData(dataMap)
                        .build();
        var trigger =
                TriggerBuilder.newTrigger()
                        .withIdentity(jobDetail.getKey().getName(), jobDetail.getKey().getGroup())
                        .startAt(Date.from(Instant.now().plus(discoveryPingPeriod)))
                        .withSchedule(
                                SimpleScheduleBuilder.simpleSchedule()
                                        .repeatForever()
                                        .withIntervalInSeconds(
                                                (int) discoveryPingPeriod.toSeconds()))
                        .build();
        scheduler.scheduleJob(jobDetail, trigger);
    }

    private Map<String, String> getEnvMap() {
        // TODO implement more generic env map passing by some platform detection
        // strategy or generalized config properties
        var envMap = new HashMap<String, String>();
        String insightsProxy = System.getenv("INSIGHTS_PROXY");
        if (StringUtils.isNotBlank(insightsProxy)) {
            envMap.put("INSIGHTS_SVC", insightsProxy);
        }
        return envMap;
    }

    @SuppressFBWarnings("DLS_DEAD_LOCAL_STORE")
    private DiscoveryNode mergeRealms() {
        DiscoveryNode universe = DiscoveryNode.getUniverse();
        DiscoveryNode mergedRoot = new DiscoveryNode();
        mergedRoot.id = universe.id;
        mergedRoot.name = universe.name;
        mergedRoot.nodeType = universe.nodeType;
        mergedRoot.labels = new HashMap<>(universe.labels);
        mergedRoot.children = new ArrayList<>();

        DiscoveryNode syntheticRealm = new DiscoveryNode();
        syntheticRealm.id = Long.MAX_VALUE;
        syntheticRealm.name = SYNTHETIC_REALM_NAME;
        syntheticRealm.nodeType = BaseNodeType.REALM.getKind();
        syntheticRealm.labels = new HashMap<>();
        syntheticRealm.children = new ArrayList<>();
        mergedRoot.children.add(syntheticRealm);

        var mergedNodes = new HashMap<Pair<String, String>, DiscoveryNode>();

        var builtinRealmIds =
                DiscoveryPlugin.find("#DiscoveryPlugin.getBuiltinRealmIds")
                        .project(Long.class)
                        .list();

        for (var realm : universe.children) {
            var stack = new ArrayDeque<NodeContext>();
            for (var child : realm.children) {
                stack.push(new NodeContext(child, null, builtinRealmIds.contains(realm.id)));
            }

            while (!stack.isEmpty()) {
                var ctx = stack.pop();
                var sourceNode = ctx.node;
                var mergedParent = ctx.parent;
                var fromBuiltin = ctx.fromBuiltin;

                Pair<String, String> key = Pair.of(sourceNode.nodeType, sourceNode.name);

                DiscoveryNode mergedNode;
                if (mergedParent == null) {
                    mergedNode = mergedNodes.computeIfAbsent(key, k -> copyNode(sourceNode));
                    syntheticRealm.children.add(mergedNode);
                    if (fromBuiltin) {
                        mergeNodeProperties(mergedNode, sourceNode);
                    }
                } else {
                    mergedNode =
                            mergedParent.children.stream()
                                    .filter(
                                            n ->
                                                    n.nodeType.equals(sourceNode.nodeType)
                                                            && n.name.equals(sourceNode.name))
                                    .findFirst()
                                    .orElseGet(
                                            () -> {
                                                var node = copyNode(sourceNode);
                                                mergedParent.children.add(node);
                                                return node;
                                            });

                    // if we have collisions, prefer the node which came from a builtin plugin
                    // and merge properties from discovery plugins in
                    if (fromBuiltin) {
                        mergeNodeProperties(mergedNode, sourceNode);
                    }
                }

                if (sourceNode.children != null && !sourceNode.children.isEmpty()) {
                    for (var child : sourceNode.children) {
                        stack.push(new NodeContext(child, mergedNode, fromBuiltin));
                    }
                }
            }
        }

        return mergedRoot;
    }

    private DiscoveryNode copyNode(DiscoveryNode source) {
        var copy = new DiscoveryNode();
        copy.id = source.id;
        copy.name = source.name;
        copy.nodeType = source.nodeType;
        copy.labels = new HashMap<>(source.labels);
        copy.children = new ArrayList<>();
        copy.target = source.target;
        return copy;
    }

    private void mergeNodeProperties(DiscoveryNode target, DiscoveryNode source) {
        if (source.id != null) {
            target.id = source.id;
        }
        if (source.labels != null) {
            target.labels.putAll(source.labels);
        }
        if (source.target != null) {
            target.target = source.target;
        }
    }

    /**
     * Merges a lineage chain into an existing tree, reusing nodes where possible. This walks down
     * the lineage chain and at each level checks if a node with the same name and type already
     * exists as a child of the parent. If it does, reuses that node; if not, adds the new node.
     *
     * @param parent The parent node to merge the lineage into (typically a Namespace node)
     * @param lineage The root of the lineage chain to merge (typically a Deployment node)
     * @return The innermost (leaf) node of the merged lineage
     */
    private DiscoveryNode mergeLineageIntoTree(DiscoveryNode parent, DiscoveryNode lineage) {
        DiscoveryNode currentParent = parent;
        DiscoveryNode currentLineage = lineage;

        while (currentLineage != null) {
            // Check if a node with the same name and type already exists as a child
            final DiscoveryNode lineageNode = currentLineage;
            DiscoveryNode existingNode =
                    currentParent.children.stream()
                            .filter(
                                    child ->
                                            child.name.equals(lineageNode.name)
                                                    && child.nodeType.equals(lineageNode.nodeType))
                            .findFirst()
                            .orElse(null);

            if (existingNode != null) {
                // Reuse the existing node
                if (lineageNode.labels != null) {
                    existingNode.labels.putAll(lineageNode.labels);
                }
                currentParent = existingNode;
            } else {
                // Add the new node to the parent
                lineageNode.parent = currentParent;
                currentParent.children.add(lineageNode);
                currentParent = lineageNode;
            }

            if (lineageNode.children == null || lineageNode.children.isEmpty()) {
                // Reached the leaf node
                return currentParent;
            }
            currentLineage = lineageNode.children.get(0);
        }

        return currentParent;
    }

    private static record NodeContext(
            DiscoveryNode node, DiscoveryNode parent, boolean fromBuiltin) {}

    private enum PrePingResult {
        NOT_ATTEMPTED,
        REACHABLE,
        UNREACHABLE
    }

    /**
     * Check that discovery plugins are still alive/reachable and prompt them to regenerate expiring
     * tokens. Plugins are issued short-lived tokens at registration time. Cryostat periodically
     * pings plugins to ensure they are still alive/reachable and to prompt them to request a fresh
     * token if their token will be expiring soon.
     */
    @DisallowConcurrentExecution
    static class RefreshPluginJob implements Job {
        @Inject Logger logger;

        @ConfigProperty(name = ConfigProperties.DISCOVERY_PLUGINS_MAX_FAILURES)
        int maxConsecutiveFailures;

        @ConfigProperty(name = ConfigProperties.DISCOVERY_PLUGINS_PING_PERIOD)
        Duration basePingPeriod;

        @ConfigProperty(name = ConfigProperties.DISCOVERY_PLUGINS_MAX_BACKOFF_MULTIPLIER)
        int maxBackoffMultiplier;

        @Inject PluginCallbackFactory callbackFactory;

        @Inject PluginCleanupHelper cleanupHelper;

        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            boolean refresh = context.getMergedJobDataMap().getBoolean(REFRESH_MAP_KEY);
            UUID pluginId = (UUID) context.getMergedJobDataMap().get(PLUGIN_ID_MAP_KEY);
            if (pluginId == null) {
                var ex =
                        new JobExecutionException(
                                new NullPointerException("pluginId cannot be null"));
                ex.setUnscheduleFiringTrigger(true);
                throw ex;
            }

            try {
                QuarkusTransaction.requiringNew()
                        .run(
                                () -> {
                                    var p = DiscoveryPlugin.<DiscoveryPlugin>findById(pluginId);

                                    if (p == null) {
                                        throw new NoResultException(
                                                "Plugin not found: " + pluginId);
                                    }

                                    if (p.nextPingAt != null
                                            && Instant.now().isBefore(p.nextPingAt)) {
                                        logger.debugv(
                                                "Skipping ping due to backoff: {0} @ {1}",
                                                p.realm.name, p.callback);
                                        return;
                                    }

                                    PluginCallback cb;
                                    try {
                                        cb = callbackFactory.create(p);
                                    } catch (URISyntaxException use) {
                                        throw new IllegalStateException(use);
                                    }
                                    if (refresh) {
                                        cb.refresh();
                                        logger.debugv(
                                                "Refreshed discovery plugin: {0} @ {1}",
                                                p.realm.name, p.callback);
                                    } else {
                                        cb.ping();
                                        logger.debugv(
                                                "Retained discovery plugin: {0} @ {1}",
                                                p.realm.name, p.callback);
                                    }

                                    p.consecutiveFailures = 0;
                                    p.lastSuccessfulPing = Instant.now();
                                    p.backoffMultiplier = 1;
                                    p.nextPingAt = null;
                                    p.persist();

                                    logger.debugv(
                                            "Plugin ping successful - lastSuccessfulPing: {0},"
                                                    + " consecutiveFailures reset to 0,"
                                                    + " backoffMultiplier reset to 1: {1} @"
                                                    + " {2}",
                                            p.lastSuccessfulPing, p.realm.name, p.callback);
                                });
            } catch (Exception e) {
                logger.warnv(e, "Plugin ping failed");
                var ex = new JobExecutionException(e);

                boolean noSuchPlugin = ExceptionUtils.indexOfType(e, NoResultException.class) >= 0;
                boolean unknownHost =
                        ExceptionUtils.indexOfType(e, UnknownHostException.class) >= 0;
                boolean illegalState =
                        ExceptionUtils.indexOfType(e, IllegalStateException.class) >= 0;
                if (noSuchPlugin || unknownHost || illegalState) {
                    logger.warnv(
                            "Unscheduled job for unknown discovery plugin: {0}",
                            context.getMergedJobDataMap().get(PLUGIN_ID_MAP_KEY));
                    if (noSuchPlugin) {
                        QuarkusTransaction.joiningExisting()
                                .run(() -> cleanupHelper.cleanupPluginNodes(pluginId));
                    }
                    ex.setUnscheduleFiringTrigger(true);
                }

                var f =
                        QuarkusTransaction.joiningExisting()
                                .call(
                                        () -> {
                                            var p =
                                                    DiscoveryPlugin.<DiscoveryPlugin>findById(
                                                            pluginId);
                                            if (p == null) {
                                                cleanupHelper.cleanupPluginNodes(pluginId);
                                                ex.setUnscheduleFiringTrigger(true);
                                                return true;
                                            }
                                            p.consecutiveFailures++;
                                            p.lastFailedPing = Instant.now();
                                            p.backoffMultiplier =
                                                    Math.min(
                                                            p.backoffMultiplier * 2,
                                                            maxBackoffMultiplier);
                                            Duration backoffPeriod =
                                                    basePingPeriod.multipliedBy(
                                                            p.backoffMultiplier);
                                            p.nextPingAt = Instant.now().plus(backoffPeriod);
                                            p.persist();

                                            logger.debugv(
                                                    "Plugin ping failed - lastFailedPing: {0},"
                                                            + " consecutiveFailures: {1}/{2},"
                                                            + " backoffMultiplier: {3}, nextPingAt:"
                                                            + " {4}: {5} @ {6}",
                                                    p.lastFailedPing,
                                                    p.consecutiveFailures,
                                                    maxConsecutiveFailures,
                                                    p.backoffMultiplier,
                                                    p.nextPingAt,
                                                    p.realm.name,
                                                    p.callback);

                                            boolean failed =
                                                    p.consecutiveFailures >= maxConsecutiveFailures;
                                            if (failed) {
                                                logger.warnv(
                                                        "Pruning discovery plugin after {0}"
                                                                + " consecutive failures: {1} @"
                                                                + " {2}",
                                                        p.consecutiveFailures,
                                                        p.realm.name,
                                                        p.callback);
                                                p.delete();
                                            } else {
                                                logger.warnv(
                                                        "Plugin ping failed ({0}/{1}), backing"
                                                                + " off for {2}: {3} @ {4}",
                                                        p.consecutiveFailures,
                                                        maxConsecutiveFailures,
                                                        backoffPeriod,
                                                        p.realm.name,
                                                        p.callback);
                                            }
                                            return failed;
                                        });
                if (f) {
                    throw ex;
                }
            }
        }
    }

    static String requireNonBlank(String in, String name) {
        if (StringUtils.isBlank(in)) {
            throw new IllegalArgumentException(
                    String.format("Parameter \"%s\" may not be blank", name));
        }
        return in;
    }

    /**
     * Create a JobKey for a plugin's periodic refresh job.
     *
     * @param plugin The plugin to create a JobKey for
     * @return JobKey for the plugin's periodic refresh job
     */
    static JobKey getPeriodicJobKey(DiscoveryPlugin plugin) {
        return getPeriodicJobKey(plugin.id);
    }

    /**
     * Create a JobKey for a plugin's periodic refresh job using the plugin ID.
     *
     * @param pluginId The plugin ID to create a JobKey for
     * @return JobKey for the plugin's periodic refresh job
     */
    static JobKey getPeriodicJobKey(UUID pluginId) {
        return JobKey.jobKey(pluginId.toString(), JOB_PERIODIC);
    }

    private InetAddress getRemoteAddress(RoutingContext ctx) {
        InetAddress addr = null;
        if (ctx.request() != null && ctx.request().remoteAddress() != null) {
            addr = jwtValidator.tryResolveAddress(addr, ctx.request().remoteAddress().host());
        }
        if (ctx.request() != null && ctx.request().headers() != null) {
            addr =
                    jwtValidator.tryResolveAddress(
                            addr, ctx.request().headers().get(X_FORWARDED_FOR));
        }
        return addr;
    }

    static record PluginRegistration(String id, String token, Map<String, String> env) {}

    static record AgentRegistration(
            String realm,
            String callback,
            AgentCredentialRequest credential,
            List<DiscoveryNode> nodes,
            DiscoveryFillStrategy fillStrategy,
            Map<String, String> context) {}

    static record AgentCredentialRequest(
            String matchExpression, String username, String password) {}

    static record DiscoveryPublication(
            List<DiscoveryNode> nodes,
            Optional<DiscoveryFillStrategy> fillStrategy,
            Optional<Map<String, String>> context) {}

    private static record CallbackValidation(
            URI callbackUri, URI unauthCallback, InetAddress remoteAddress) {}

    enum DiscoveryFillStrategy {
        NONE,
        KUBERNETES,
        ;
    }

    static class DuplicatePluginException extends IllegalArgumentException {

        public DuplicatePluginException(String format) {
            super(format);
        }
    }
}
