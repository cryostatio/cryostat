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

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openjdk.jmc.flightrecorder.configuration.events.IEventTypeInfo;

import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;

@Path("")
public class Events {

    @Inject TargetConnectionManager connectionManager;
    @Inject Logger logger;

    @GET
    @Path("/api/v4/targets/{id}/events")
    @RolesAllowed("read")
    @Operation(
            summary = "List JFR event types registered within the given target",
            description =
                    """
                    Retrieve a list of JFR event types registered within the given target. This will include all
                    built-in JFR types emitted by the target JVM, as well as custom event types specific to that
                    target JVM if they are correctly registered. Custom event types, or event types emitted by plugins
                    and extensions, may not always appear in this list.
                    """)
    public List<SerializableEventTypeInfo> listEvents(@RestPath long id, @RestQuery String q)
            throws Exception {
        return searchEvents(Target.getTargetById(id), q);
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
