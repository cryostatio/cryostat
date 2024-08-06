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
package io.cryostat.events;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openjdk.jmc.flightrecorder.configuration.events.IEventTypeInfo;

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

@Path("")
public class Events {

    @Inject TargetConnectionManager connectionManager;
    @Inject Logger logger;

    @GET
    @Path("/api/v2/targets/{connectUrl}/events")
    @RolesAllowed("read")
    public V2Response listEventsV2(@RestPath URI connectUrl, @RestQuery String q) throws Exception {
        return V2Response.json(
                Response.Status.OK, searchEvents(Target.getTargetByConnectUrl(connectUrl), q));
    }

    @GET
    @Path("/api/v3/targets/{id}/events")
    @RolesAllowed("read")
    public List<SerializableEventTypeInfo> listEvents(@RestPath long id, @RestQuery String q)
            throws Exception {
        return searchEvents(Target.find("id", id).singleResult(), q);
    }

    private List<SerializableEventTypeInfo> searchEvents(Target target, String q) throws Exception {
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
