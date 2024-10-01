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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.cryostat.core.FlightRecorderException;
import io.cryostat.core.templates.MutableTemplateService.InvalidXmlException;
import io.cryostat.libcryostat.sys.FileSystem;
import io.cryostat.libcryostat.templates.InvalidEventTemplateException;
import io.cryostat.libcryostat.templates.Template;
import io.cryostat.libcryostat.templates.TemplateType;

import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;
import org.jboss.resteasy.reactive.multipart.FileUpload;

@Path("/api/v4/event_templates")
public class EventTemplates {

    public static final Template ALL_EVENTS_TEMPLATE =
            new Template(
                    "ALL",
                    "Enable all available events in the target JVM, with default option values."
                            + " This will be very expensive and is intended primarily for testing"
                            + " Cryostat's own capabilities.",
                    "Cryostat",
                    TemplateType.TARGET);

    @Inject FileSystem fs;
    @Inject TargetTemplateService.Factory targetTemplateServiceFactory;
    @Inject S3TemplateService customTemplateService;
    @Inject Logger logger;

    @GET
    @Blocking
    @RolesAllowed("read")
    public List<Template> listTemplates() throws Exception {
        var list = new ArrayList<Template>();
        list.add(ALL_EVENTS_TEMPLATE);
        list.addAll(customTemplateService.getTemplates());
        return list;
    }

    @GET
    @Blocking
    @RolesAllowed("read")
    public Template getTemplate(@RestPath String templateName)
            throws IOException, FlightRecorderException {
        if (StringUtils.isBlank(templateName)) {
            throw new BadRequestException();
        }
        return customTemplateService.getTemplates().stream()
                .filter(t -> t.getName().equals(templateName))
                .findFirst()
                .orElseThrow();
    }

    @POST
    @RolesAllowed("write")
    public RestResponse<Template> postTemplates(
            @Context UriInfo uriInfo, @RestForm("template") FileUpload body) throws IOException {
        if (body == null || body.filePath() == null || !"template".equals(body.name())) {
            throw new BadRequestException();
        }
        try (var stream = fs.newInputStream(body.filePath())) {
            var template = customTemplateService.addTemplate(stream);
            return ResponseBuilder.<Template>created(
                            uriInfo.getAbsolutePathBuilder().path(template.getName()).build())
                    .entity(template)
                    .build();
        } catch (InvalidEventTemplateException | InvalidXmlException e) {
            throw new BadRequestException(e);
        }
    }

    @DELETE
    @Blocking
    @Path("/{templateName}")
    @RolesAllowed("write")
    public void deleteTemplate(@RestPath String templateName) throws FlightRecorderException {
        if (StringUtils.isBlank(templateName)) {
            throw new BadRequestException();
        }
        if (ALL_EVENTS_TEMPLATE.getName().equals(templateName)) {
            throw new BadRequestException();
        }
        if (!customTemplateService.getTemplates().stream()
                .anyMatch(t -> t.getName().equals(templateName))) {
            throw new NotFoundException();
        }
        customTemplateService.deleteTemplate(templateName);
    }
}
