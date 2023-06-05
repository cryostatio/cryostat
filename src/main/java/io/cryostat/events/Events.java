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
package io.cryostat.events;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openjdk.jmc.rjmx.services.jfr.IEventTypeInfo;

import io.cryostat.V2Response;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;

@Path("")
public class Events {

    @Inject TargetConnectionManager connectionManager;
    @Inject Logger logger;

    @GET
    @Path("/api/v1/targets/{connectUrl}/events")
    @RolesAllowed("read")
    public Response listEventsV1(@RestPath URI connectUrl, @RestQuery String q) throws Exception {
        logger.info(connectUrl.toString());
        Target target = Target.getTargetByConnectUrl(connectUrl);
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(URI.create(String.format("/api/v3/targets/%d/events?q=%s", target.id, q)))
                .build();
    }

    @GET
    @Path("/api/v2/targets/{connectUrl}/events")
    @RolesAllowed("read")
    public V2Response listEventsV2(@RestPath URI connectUrl, @RestQuery String q) throws Exception {
        Target target = Target.getTargetByConnectUrl(connectUrl);
        List<SerializableEventTypeInfo> events =
                connectionManager.executeConnectedTask(
                        target,
                        connection ->
                                connection.getService().getAvailableEventTypes().stream()
                                        .filter(
                                                evt ->
                                                        StringUtils.isBlank(q)
                                                                || eventMatchesSearchTerm(
                                                                        evt, q.toLowerCase()))
                                        .map(SerializableEventTypeInfo::fromEventTypeInfo)
                                        .sorted((a, b) -> a.typeId().compareTo(b.typeId()))
                                        .distinct()
                                        .toList());
        return V2Response.json(events);
    }

    @GET
    @Path("/api/v3/targets/{id}/events")
    @RolesAllowed("read")
    public List<SerializableEventTypeInfo> listEvents(@RestPath long id, @RestQuery String q)
            throws Exception {
        Target target = Target.find("id", id).singleResult();
        return connectionManager.executeConnectedTask(
                target,
                connection ->
                        connection.getService().getAvailableEventTypes().stream()
                                .filter(
                                        evt ->
                                                StringUtils.isBlank(q)
                                                        || eventMatchesSearchTerm(
                                                                evt, q.toLowerCase()))
                                .map(SerializableEventTypeInfo::fromEventTypeInfo)
                                .sorted((a, b) -> a.typeId().compareTo(b.typeId()))
                                .distinct()
                                .toList());
    }

    private boolean eventMatchesSearchTerm(IEventTypeInfo event, String term) {
        Set<String> terms = new HashSet<>();
        terms.add(event.getEventTypeID().getFullKey());
        terms.addAll(Arrays.asList(event.getHierarchicalCategory()));
        terms.add(event.getDescription());
        terms.add(event.getName());

        return terms.stream()
                .filter(s -> s != null)
                .map(String::toLowerCase)
                .anyMatch(s -> s.contains(term));
    }
}
