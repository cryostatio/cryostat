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
import java.util.ArrayList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.cryostat.targets.JvmIdException;
import io.cryostat.targets.Target;
import io.cryostat.targets.Target.Annotations;
import io.cryostat.targets.TargetConnectionManager;

import io.quarkus.runtime.StartupEvent;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;

@ApplicationScoped
@Path("")
public class CustomDiscovery {

    public static final Pattern HOST_PORT_PAIR_PATTERN =
            Pattern.compile("^([^:\\s]+)(?::(\\d{1,5}))$");
    private static final String REALM = "Custom Targets";

    @Inject Logger logger;
    @Inject EventBus bus;
    @Inject TargetConnectionManager connectionManager;

    @Transactional
    void onStart(@Observes StartupEvent evt) {
        DiscoveryNode universe = DiscoveryNode.getUniverse();
        if (DiscoveryNode.getRealm(REALM).isEmpty()) {
            DiscoveryPlugin plugin = new DiscoveryPlugin();
            DiscoveryNode node = DiscoveryNode.environment(REALM, DiscoveryNode.REALM);
            plugin.realm = node;
            plugin.builtin = true;
            universe.children.add(node);
            plugin.persist();
            universe.persist();
        }
    }

    @Transactional(rollbackOn = {JvmIdException.class})
    @POST
    @Path("/api/v2/targets")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("write")
    public Response create(Target target, @RestQuery boolean dryrun) {
        try {
            target.connectUrl = sanitizeConnectUrl(target.connectUrl.toString());

            try {
                if (target.isAgent()) {
                    // TODO test connection
                    target.jvmId = target.connectUrl.toString();
                } else {
                    target.jvmId =
                            connectionManager.executeConnectedTask(target, conn -> conn.getJvmId());
                }
            } catch (Exception e) {
                logger.error("Target connection failed", e);
                return Response.status(400).build();
            }

            if (dryrun) {
                return Response.ok().build();
            }

            target.activeRecordings = new ArrayList<>();
            target.labels = Map.of();
            target.annotations = new Annotations();
            target.annotations.cryostat().putAll(Map.of("REALM", REALM));

            DiscoveryNode node = DiscoveryNode.target(target);
            target.discoveryNode = node;
            DiscoveryNode realm = DiscoveryNode.getRealm(REALM).orElseThrow();

            realm.children.add(node);
            target.persist();
            node.persist();
            realm.persist();

            return Response.created(URI.create("/api/v3/targets/" + target.id)).build();
        } catch (Exception e) {
            if (ExceptionUtils.indexOfType(e, ConstraintViolationException.class) >= 0) {
                logger.warn("Invalid target definition", e);
                return Response.status(400).build();
            }
            logger.error("Unknown error", e);
            return Response.serverError().build();
        }
    }

    @Transactional
    @POST
    @Path("/api/v2/targets")
    @Consumes({MediaType.MULTIPART_FORM_DATA, MediaType.APPLICATION_FORM_URLENCODED})
    @RolesAllowed("write")
    public Response create(
            @RestForm URI connectUrl, @RestForm String alias, @RestQuery boolean dryrun) {
        var target = new Target();
        target.connectUrl = connectUrl;
        target.alias = alias;

        return create(target, dryrun);
    }

    @Transactional
    @DELETE
    @Path("/api/v2/targets/{connectUrl}")
    @RolesAllowed("write")
    public Response delete(@RestPath URI connectUrl) throws URISyntaxException {
        Target target = Target.getTargetByConnectUrl(connectUrl);
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(URI.create(String.format("/api/v3/targets/%d", target.id)))
                .build();
    }

    @Transactional
    @DELETE
    @Path("/api/v3/targets/{id}")
    @RolesAllowed("write")
    public Response delete(@RestPath long id) throws URISyntaxException {
        Target target = Target.find("id", id).singleResult();
        DiscoveryNode realm = DiscoveryNode.getRealm(REALM).orElseThrow();
        realm.children.remove(target.discoveryNode);
        target.delete();
        realm.persist();
        return Response.ok().build();
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
}
