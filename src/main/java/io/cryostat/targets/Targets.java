/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat.targets;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.persistence.NoResultException;
import javax.transaction.Transactional;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

import io.cryostat.MessagingServer;
import io.cryostat.Notification;

import io.quarkus.vertx.ConsumeEvent;
import io.vertx.core.eventbus.EventBus;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("")
public class Targets {

    public static final String TARGET_JVM_DISCOVERY = "TargetJvmDiscovery";
    public static final Pattern HOST_PORT_PAIR_PATTERN =
            Pattern.compile("^([^:\\s]+)(?::(\\d{1,5}))$");

    private final Logger logger = LoggerFactory.getLogger(getClass());
    @Inject EventBus bus;
    @Inject TargetConnectionManager connectionManager;

    @GET
    @Path("/api/v1/targets")
    @RolesAllowed("target:read")
    public List<Target> listV1() {
        return Target.listAll();
    }

    @GET
    @Path("/api/v3/targets")
    @RolesAllowed("target:read")
    public List<Target> list() {
        return Target.listAll();
    }

    @Transactional
    @POST
    @Path("/api/v2/targets")
    @Consumes("application/json")
    @RolesAllowed("target:create")
    public Response create(Target target, @RestQuery boolean dryrun) {
        try {
            target.connectUrl = sanitizeConnectUrl(target.connectUrl.toString());
            if (target.annotations == null) {
                target.annotations = new Target.Annotations();
                target.annotations.cryostat.put("REALM", "Custom Targets");
            }
            if (target.labels == null) {
                target.labels = new HashMap<>();
            }

            try {
                target.jvmId =
                        connectionManager.executeConnectedTask(target, conn -> conn.getJvmId());
            } catch (Exception e) {
                logger.error("Target connection failed", e);
                return Response.status(400).build();
            }

            if (dryrun) {
                return Response.ok().build();
            }

            target.persistAndFlush();

            bus.publish(
                    TARGET_JVM_DISCOVERY,
                    new TargetDiscoveryEvent(new TargetDiscovery(EventKind.FOUND, target)));

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
    @Consumes("multipart/form-data")
    @RolesAllowed("target:create")
    public Response create(
            @RestForm URI connectUrl, @RestForm String alias, @RestQuery boolean dryrun) {
        var target = new Target();
        target.connectUrl = connectUrl;
        target.alias = alias;

        return create(target, dryrun);
    }

    @GET
    @Path("/api/v3/targets/{id}")
    @RolesAllowed("target:read")
    public Target getById(@RestPath Long id) {
        return Target.findById(id);
    }

    @Transactional
    @DELETE
    @Path("/api/v2/targets/{connectUrl}")
    @RolesAllowed("target:delete")
    public Response delete(@RestPath URI connectUrl) throws URISyntaxException {
        try {
            Target target = Target.getTargetByConnectUrl(connectUrl);
            target.delete();
            bus.publish(
                    TARGET_JVM_DISCOVERY,
                    new TargetDiscoveryEvent(new TargetDiscovery(EventKind.LOST, target)));
            return Response.ok().build();
        } catch (Exception e) {
            if (ExceptionUtils.indexOfType(e, NoResultException.class) >= 0) {
                return Response.status(404).build();
            }
            return Response.serverError().build();
        }
    }

    @Transactional
    @DELETE
    @Path("/api/v3/targets/{id}")
    @RolesAllowed("target:delete")
    public Response delete(@RestPath long id) throws URISyntaxException {
        Target target = Target.findById(id);
        if (target == null) {
            return Response.status(404).build();
        }
        return delete(target.connectUrl);
    }

    @ConsumeEvent(TARGET_JVM_DISCOVERY)
    void onDiscovery(TargetDiscoveryEvent event) {
        bus.publish(MessagingServer.class.getName(), new Notification(TARGET_JVM_DISCOVERY, event));
    }

    public record TargetDiscoveryEvent(TargetDiscovery event) {}

    public record TargetDiscovery(EventKind kind, Target serviceRef) {}

    public enum EventKind {
        FOUND,
        LOST,
        ;
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
