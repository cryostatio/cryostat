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
                .location(
                        URI.create(String.format("/api/v3/targets/%d/event_templates", target.id)))
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
                                        "/api/v3/targets/%d/event_templates/%s/%s",
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
