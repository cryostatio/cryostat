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
import java.util.ArrayList;
import java.util.List;

import io.cryostat.core.templates.Template;
import io.cryostat.core.templates.TemplateType;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;

@Path("")
public class EventTemplates {

    public static final Template ALL_EVENTS_TEMPLATE =
            new Template(
                    "ALL",
                    "Enable all available events in the target JVM, with default option values."
                            + " This will be very expensive and is intended primarily for testing"
                            + " Cryostat's own capabilities.",
                    "Cryostat",
                    TemplateType.TARGET);

    @Inject TargetConnectionManager connectionManager;

    @GET
    @Path("/api/v1/targets/{connectUrl}/templates")
    @RolesAllowed("read")
    public Response listTemplatesV1(@RestPath URI connectUrl) throws Exception {
        Target target = Target.getTargetByConnectUrl(connectUrl);
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(URI.create(String.format("api/v3/targets/%d/event_templates", target.id)))
                .build();
    }

    @GET
    @Path("/api/v1/targets/{connectUrl}/templates/{templateName}/type/{templateType}")
    @RolesAllowed("read")
    public Response getTargetTemplateV1(
            @RestPath URI connectUrl,
            @RestPath String templateName,
            @RestPath TemplateType templateType)
            throws Exception {
        Target target = Target.getTargetByConnectUrl(connectUrl);
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(
                        URI.create(
                                String.format(
                                        "api/v3/targets/%d/event_templates/%s/%s",
                                        target.id, templateName, templateType)))
                .build();
    }

    @GET
    @Path("/api/v3/targets/{id}/event_templates")
    @RolesAllowed("read")
    public List<Template> listTemplates(@RestPath long id) throws Exception {
        Target target = Target.find("id", id).singleResult();
        return connectionManager.executeConnectedTask(
                target,
                connection -> {
                    List<Template> list =
                            new ArrayList<>(connection.getTemplateService().getTemplates());
                    list.add(ALL_EVENTS_TEMPLATE);
                    return list;
                });
    }

    @GET
    @Path("/api/v3/targets/{id}/event_templates/{templateName}/{templateType}")
    @RolesAllowed("read")
    public String getTargetTemplate(
            @RestPath long id, @RestPath String templateName, @RestPath TemplateType templateType)
            throws Exception {
        Target target = Target.find("id", id).singleResult();
        return connectionManager.executeConnectedTask(
                target,
                conn ->
                        conn.getTemplateService()
                                .getXml(templateName, templateType)
                                .orElseThrow(NotFoundException::new)
                                .toString());
    }
}
