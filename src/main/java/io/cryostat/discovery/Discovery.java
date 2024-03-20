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
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import io.cryostat.discovery.DiscoveryPlugin.PluginCallback;
import io.cryostat.targets.TargetConnectionManager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jwt.proc.BadJWTException;
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
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;

@Path("")
public class Discovery {

    public static final Pattern HOST_PORT_PAIR_PATTERN =
            Pattern.compile("^([^:\\s]+)(?::(\\d{1,5}))$");
    static final String X_FORWARDED_FOR = "X-Forwarded-For";

    @Inject Logger logger;
    @Inject ObjectMapper mapper;
    @Inject EventBus bus;
    @Inject TargetConnectionManager connectionManager;
    @Inject DiscoveryJwtFactory jwtFactory;

    @Transactional
    void onStart(@Observes StartupEvent evt) {
        DiscoveryNode.getUniverse();

        DiscoveryPlugin.<DiscoveryPlugin>findAll().list().stream()
                .filter(p -> !p.builtin)
                .forEach(
                        plugin -> {
                            try {
                                PluginCallback.create(plugin).ping();
                                logger.infov(
                                        "Retained discovery plugin: {0} @ {1}",
                                        plugin.realm, plugin.callback);
                            } catch (Exception e) {
                                logger.infov(
                                        "Pruned discovery plugin: {0} @ {1}",
                                        plugin.realm, plugin.callback);
                                plugin.delete();
                            }
                        });
    }

    @GET
    @Path("/api/v2.1/discovery")
    @RolesAllowed("read")
    public Response getv21() {
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(URI.create("/api/v3/discovery"))
                .build();
    }

    @GET
    @Path("/api/v3/discovery")
    @RolesAllowed("read")
    public DiscoveryNode get() {
        return DiscoveryNode.getUniverse();
    }

    @GET
    @Path("/api/v2.2/discovery/{id}")
    @RolesAllowed("read")
    public RestResponse<Void> checkRegistration(@RestPath UUID id, @RestQuery String token) {
        // TODO validate the provided token
        DiscoveryPlugin.find("id", id).singleResult();
        return ResponseBuilder.<Void>ok().build();
    }

    @Transactional
    @POST
    @Path("/api/v2.2/discovery")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("write")
    public Response register(@Context RoutingContext ctx, JsonObject body)
            throws URISyntaxException,
                    JOSEException,
                    UnknownHostException,
                    SocketException,
                    ParseException,
                    BadJWTException {
        String pluginId = body.getString("id");
        String priorToken = body.getString("token");
        String realmName = body.getString("realm");
        URI callbackUri = new URI(body.getString("callback"));

        // TODO apply URI range validation to the remote address
        InetAddress remoteAddress = getRemoteAddress(ctx);
        String authzHeader =
                Optional.ofNullable(ctx.request().headers().get(HttpHeaders.AUTHORIZATION))
                        .orElse("None");

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
            if (!Objects.equals(plugin.callback, callbackUri)) {
                throw new BadRequestException();
            }
            location = jwtFactory.getPluginLocation("/api/v2.2/discovery/", plugin.id.toString());
            jwtFactory.parseDiscoveryPluginJwt(
                    priorToken, realmName, location, remoteAddress, false);
        } else {
            // new plugin registration

            plugin = new DiscoveryPlugin();
            plugin.callback = callbackUri;
            plugin.realm =
                    DiscoveryNode.environment(requireNonBlank(realmName), DiscoveryNode.REALM);
            plugin.builtin = false;
            plugin.persist();

            DiscoveryNode.getUniverse().children.add(plugin.realm);

            location = jwtFactory.getPluginLocation("/api/v2.2/discovery/", plugin.id.toString());
        }

        String token =
                jwtFactory.createDiscoveryPluginJwt(
                        authzHeader, realmName, remoteAddress, location);

        // TODO implement more generic env map passing by some platform detection strategy or
        // generalized config properties
        var envMap = new HashMap<String, String>();
        String insightsProxy = System.getenv("INSIGHTS_PROXY");
        if (StringUtils.isNotBlank(insightsProxy)) {
            envMap.put("INSIGHTS_SVC", "INSIGHTS_PROXY");
        }
        return Response.created(location)
                .entity(
                        Map.of(
                                "meta",
                                        Map.of(
                                                "mimeType", "JSON",
                                                "status", "OK"),
                                "data",
                                        Map.of(
                                                "result",
                                                Map.of(
                                                        "id",
                                                        plugin.id.toString(),
                                                        "token",
                                                        token,
                                                        "env",
                                                        envMap))))
                .build();
    }

    @Transactional
    @POST
    @Path("/api/v2.2/discovery/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @PermitAll
    public Map<String, Map<String, String>> publish(
            @RestPath UUID id, @RestQuery String token, List<DiscoveryNode> body) {
        // TODO validate the provided token
        DiscoveryPlugin plugin = DiscoveryPlugin.find("id", id).singleResult();
        plugin.realm.children.clear();
        plugin.persist();
        plugin.realm.children.addAll(body);
        body.forEach(
                b -> {
                    if (b.target != null) {
                        b.target.discoveryNode = b;
                    }
                    b.persist();
                });
        plugin.persist();

        return Map.of(
                "meta",
                        Map.of(
                                "mimeType", "JSON",
                                "status", "OK"),
                "data", Map.of("result", plugin.id.toString()));
    }

    @Transactional
    @DELETE
    @Path("/api/v2.2/discovery/{id}")
    @PermitAll
    public Map<String, Map<String, String>> deregister(@RestPath UUID id, @RestQuery String token) {
        // TODO validate the provided token
        DiscoveryPlugin plugin = DiscoveryPlugin.find("id", id).singleResult();
        if (plugin.builtin) {
            throw new ForbiddenException();
        }
        plugin.delete();
        DiscoveryNode.getUniverse().children.remove(plugin.realm);
        return Map.of(
                "meta",
                        Map.of(
                                "mimeType", "JSON",
                                "status", "OK"),
                "data", Map.of("result", plugin.id.toString()));
    }

    @GET
    @Path("/api/v3/discovery_plugins")
    @RolesAllowed("read")
    public Response getPlugins(@RestQuery String realm) throws JsonProcessingException {
        // TODO filter for the matching realm name within the DB query
        List<DiscoveryPlugin> plugins =
                DiscoveryPlugin.findAll().<DiscoveryPlugin>list().stream()
                        .filter(p -> StringUtils.isBlank(realm) || p.realm.name.equals(realm))
                        .toList();
        return Response.ok()
                .entity(
                        mapper.writerWithView(DiscoveryNode.Views.Flat.class)
                                .writeValueAsString(plugins))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    @GET
    @Path("/api/v3/discovery_plugins/{id}")
    @RolesAllowed("read")
    public DiscoveryPlugin getPlugin(@RestPath UUID id) throws JsonProcessingException {
        return DiscoveryPlugin.find("id", id).singleResult();
    }

    private static String requireNonBlank(String in) {
        if (StringUtils.isBlank(in)) {
            throw new IllegalArgumentException();
        }
        return in;
    }

    private InetAddress getRemoteAddress(RoutingContext ctx) {
        InetAddress addr = null;
        if (ctx.request() != null && ctx.request().remoteAddress() != null) {
            addr = tryResolveAddress(addr, ctx.request().remoteAddress().host());
        }
        if (ctx.request() != null && ctx.request().headers() != null) {
            addr = tryResolveAddress(addr, ctx.request().headers().get(X_FORWARDED_FOR));
        }
        return addr;
    }

    private InetAddress tryResolveAddress(InetAddress addr, String host) {
        if (StringUtils.isBlank(host)) {
            return addr;
        }
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            logger.error("Address resolution exception", e);
        }
        return addr;
    }
}
