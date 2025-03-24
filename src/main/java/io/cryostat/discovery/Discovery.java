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
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import io.cryostat.ConfigProperties;
import io.cryostat.discovery.DiscoveryPlugin.PluginCallback;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.util.URIUtil;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jwt.proc.BadJWTException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriBuilder;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
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

    static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private static final String JOB_PERIODIC = "periodic";
    private static final String JOB_STARTUP = "startup";
    private static final String PLUGIN_ID_MAP_KEY = "pluginId";
    private static final String REFRESH_MAP_KEY = "refresh";

    @ConfigProperty(name = "cryostat.discovery.plugins.ping-period")
    Duration discoveryPingPeriod;

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

    void onStart(@Observes StartupEvent evt) {
        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            DiscoveryPlugin.<DiscoveryPlugin>findAll().list().stream()
                                    .filter(p -> !p.builtin)
                                    .forEach(
                                            plugin -> {
                                                var dataMap = new JobDataMap();
                                                dataMap.put(PLUGIN_ID_MAP_KEY, plugin.id);
                                                dataMap.put(REFRESH_MAP_KEY, true);
                                                JobDetail jobDetail =
                                                        JobBuilder.newJob(RefreshPluginJob.class)
                                                                .withIdentity(
                                                                        plugin.id.toString(),
                                                                        JOB_STARTUP)
                                                                .usingJobData(dataMap)
                                                                .build();
                                                var trigger =
                                                        TriggerBuilder.newTrigger()
                                                                .usingJobData(
                                                                        jobDetail.getJobDataMap())
                                                                .startNow()
                                                                .withSchedule(
                                                                        SimpleScheduleBuilder
                                                                                .simpleSchedule()
                                                                                .withRepeatCount(0))
                                                                .build();
                                                try {
                                                    scheduler.scheduleJob(jobDetail, trigger);
                                                } catch (SchedulerException e) {
                                                    logger.warn(
                                                            "Failed to schedule plugin prune job",
                                                            e);
                                                }
                                            });
                        });
    }

    void onStop(@Observes ShutdownEvent evt) throws SchedulerException {
        scheduler.shutdown();
    }

    @GET
    @Path("/api/v4/discovery")
    @RolesAllowed("read")
    public DiscoveryNode get() {
        return DiscoveryNode.getUniverse();
    }

    @GET
    @Path("/api/v4/discovery/{id}")
    @RolesAllowed("read")
    public void checkRegistration(
            @Context RoutingContext ctx, @RestPath UUID id, @RestQuery String token) {
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

    @Transactional
    @POST
    @Path("/api/v4/discovery")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("write")
    @SuppressFBWarnings("DLS_DEAD_LOCAL_STORE")
    public PluginRegistration register(@Context RoutingContext ctx, JsonObject body)
            throws SchedulerException {
        String pluginId = body.getString("id");
        String priorToken = body.getString("token");
        String realmName = body.getString("realm");
        URI callbackUri;
        try {
            String callback = body.getString("callback");
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
                                "cryostat.target.callback of \"%s\" is unacceptable with the"
                                        + " current URI range settings",
                                callbackUri));
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

        URI location;
        DiscoveryPlugin plugin;
        if (StringUtils.isNotBlank(pluginId) && StringUtils.isNotBlank(priorToken)) {
            // refresh the JWT for existing registration
            plugin =
                    DiscoveryPlugin.<DiscoveryPlugin>find("id", UUID.fromString(pluginId))
                            .singleResult();
            if (!Objects.equals(plugin.realm.name, realmName)) {
                throw new ForbiddenException();
            }
            if (!Objects.equals(plugin.callback, unauthCallback)) {
                throw new BadRequestException("plugin callback mismatch");
            }
            try {
                location = jwtFactory.getPluginLocation(plugin);
                jwtFactory.parseDiscoveryPluginJwt(
                        plugin, priorToken, location, remoteAddress, false);
            } catch (URISyntaxException
                    | UnknownHostException
                    | SocketException
                    | BadJWTException
                    | JOSEException
                    | ParseException e) {
                throw new BadRequestException(e);
            }
        } else {
            // check if a plugin record with the same callback already exists. If it does,
            // ping it:
            // if it's still there reject this request as a duplicate, otherwise delete the
            // previous record and accept this new one as a replacement
            DiscoveryPlugin.<DiscoveryPlugin>find("callback", unauthCallback)
                    .singleResultOptional()
                    .ifPresent(
                            p -> {
                                try {
                                    var cb = PluginCallback.create(p);
                                    cb.ping();
                                    throw new IllegalArgumentException(
                                            String.format(
                                                    "Plugin with callback %s already exists and is"
                                                            + " still reachable",
                                                    unauthCallback));
                                } catch (Exception e) {
                                    logger.error(e);
                                    p.delete();
                                }
                            });

            // new plugin registration
            plugin = new DiscoveryPlugin();
            plugin.callback = callbackUri;
            plugin.realm =
                    DiscoveryNode.environment(
                            requireNonBlank(realmName, "realm"), NodeType.BaseNodeType.REALM);
            plugin.builtin = false;

            var universe = DiscoveryNode.getUniverse();
            plugin.realm.parent = universe;
            plugin.persist();
            universe.children.add(plugin.realm);
            universe.persist();

            try {
                location = jwtFactory.getPluginLocation(plugin);
            } catch (URISyntaxException e) {
                throw new BadRequestException(e);
            }

            var dataMap = new JobDataMap();
            dataMap.put(PLUGIN_ID_MAP_KEY, plugin.id);
            dataMap.put(REFRESH_MAP_KEY, true);
            JobDetail jobDetail =
                    JobBuilder.newJob(RefreshPluginJob.class)
                            .withIdentity(plugin.id.toString(), JOB_PERIODIC)
                            .usingJobData(dataMap)
                            .build();
            var trigger =
                    TriggerBuilder.newTrigger()
                            .usingJobData(jobDetail.getJobDataMap())
                            .startAt(Date.from(Instant.now().plus(discoveryPingPeriod)))
                            .withSchedule(
                                    SimpleScheduleBuilder.simpleSchedule()
                                            .repeatForever()
                                            .withIntervalInSeconds(
                                                    (int) discoveryPingPeriod.toSeconds()))
                            .build();
            scheduler.scheduleJob(jobDetail, trigger);
        }

        String token;
        try {
            token = jwtFactory.createDiscoveryPluginJwt(plugin, remoteAddress, location);
        } catch (URISyntaxException | JOSEException | UnknownHostException | SocketException e) {
            throw new BadRequestException(e);
        }

        // TODO implement more generic env map passing by some platform detection
        // strategy or generalized config properties
        var envMap = new HashMap<String, String>();
        String insightsProxy = System.getenv("INSIGHTS_PROXY");
        if (StringUtils.isNotBlank(insightsProxy)) {
            envMap.put("INSIGHTS_SVC", insightsProxy);
        }
        return new PluginRegistration(plugin.id.toString(), token, envMap);
    }

    @Transactional
    @POST
    @Path("/api/v4/discovery/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @PermitAll
    public void publish(
            @Context RoutingContext ctx,
            @RestPath UUID id,
            @RestQuery String token,
            List<DiscoveryNode> body) {
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
        plugin.realm.children.clear();
        plugin.realm.children.addAll(body);
        for (var b : body) {
            if (b.target != null) {
                try {
                    if (!uriUtil.validateUri(b.target.connectUrl)) {
                        throw new BadRequestException(
                                String.format(
                                        "Connect URL of \"%s\" is unacceptable with the"
                                                + " current URI range settings",
                                        b.target.connectUrl));
                    }
                } catch (MalformedURLException e) {
                    throw new BadRequestException(e);
                }
                if (!uriUtil.isJmxUrl(b.target.connectUrl)) {
                    if (agentTlsRequired && !b.target.connectUrl.getScheme().equals("https")) {
                        throw new BadRequestException(
                                String.format(
                                        "TLS for agent connections is required by (%s)",
                                        ConfigProperties.AGENT_TLS_REQUIRED));
                    }
                    if (!b.target.connectUrl.getScheme().equals("https")
                            && !b.target.connectUrl.getScheme().equals("http")) {
                        throw new BadRequestException(
                                String.format(
                                        "Target connect URL is neither JMX nor HTTP(S): (%s)",
                                        b.target.connectUrl.toString()));
                    }
                }
                // Continue since we've verified the connect URL is either JMX or HTTPS with
                // TLS verification enabled, or HTTP with TLS verification disabled.
                b.target.discoveryNode = b;
                b.target.discoveryNode.parent = plugin.realm;
                b.parent = plugin.realm;
            }
            b.persist();
        }
        plugin.persist();
    }

    @Transactional
    @DELETE
    @Path("/api/v4/discovery/{id}")
    @PermitAll
    public void deregister(@Context RoutingContext ctx, @RestPath UUID id, @RestQuery String token)
            throws SchedulerException {
        DiscoveryPlugin plugin = DiscoveryPlugin.find("id", id).singleResult();
        try {
            jwtValidator.validateJwt(ctx, plugin, token, false);
        } catch (MalformedURLException
                | URISyntaxException
                | UnknownHostException
                | SocketException
                | JOSEException
                | ParseException e) {
            throw new BadRequestException(e);
        }
        if (plugin.builtin) {
            throw new ForbiddenException();
        }

        Set<JobKey> jobKeys = new HashSet<>();
        jobKeys.addAll(scheduler.getJobKeys(GroupMatcher.jobGroupEquals(JOB_PERIODIC)));
        jobKeys.addAll(scheduler.getJobKeys(GroupMatcher.jobGroupEquals(JOB_STARTUP)));
        for (var key : jobKeys) {
            scheduler.deleteJob(key);
        }
        plugin.delete();
    }

    @GET
    @JsonView(DiscoveryNode.Views.Flat.class)
    @Path("/api/v4/discovery_plugins")
    @RolesAllowed("read")
    public List<DiscoveryPlugin> getPlugins(@RestQuery String realm) {
        // TODO filter for the matching realm name within the DB query
        return DiscoveryPlugin.findAll().<DiscoveryPlugin>list().stream()
                .filter(p -> StringUtils.isBlank(realm) || p.realm.name.equals(realm))
                .toList();
    }

    @GET
    @Path("/api/v4/discovery_plugins/{id}")
    @RolesAllowed("read")
    public DiscoveryPlugin getPlugin(@RestPath UUID id) {
        return DiscoveryPlugin.find("id", id).singleResult();
    }

    @SuppressFBWarnings("RCN_REDUNDANT_NULLCHECK_OF_NULL_VALUE")
    static class RefreshPluginJob implements Job {
        @Inject Logger logger;

        @Override
        @Transactional
        public void execute(JobExecutionContext context) throws JobExecutionException {
            DiscoveryPlugin plugin = null;
            try {
                boolean refresh = context.getMergedJobDataMap().getBoolean(REFRESH_MAP_KEY);
                plugin =
                        DiscoveryPlugin.find(
                                        "id", context.getMergedJobDataMap().get(PLUGIN_ID_MAP_KEY))
                                .singleResult();
                var cb = PluginCallback.create(plugin);
                if (refresh) {
                    cb.refresh();
                    logger.debugv(
                            "Refreshed discovery plugin: {0} @ {1}", plugin.realm, plugin.callback);
                } else {
                    cb.ping();
                    logger.debugv(
                            "Retained discovery plugin: {0} @ {1}", plugin.realm, plugin.callback);
                }
            } catch (Exception e) {
                if (plugin != null) {
                    logger.debugv(
                            e, "Pruned discovery plugin: {0} @ {1}", plugin.realm, plugin.callback);
                    plugin.delete();
                } else {
                    var ex = new JobExecutionException(e);
                    ex.setUnscheduleFiringTrigger(true);
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
}
