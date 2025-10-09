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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import io.cryostat.core.FlightRecorderException;
import io.cryostat.core.templates.MutableTemplateService.InvalidXmlException;
import io.cryostat.core.templates.TemplateService;
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
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.openapi.annotations.Operation;
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
    @Inject PresetTemplateService presetTemplateService;
    @Inject Logger logger;

    @GET
    @Blocking
    @RolesAllowed("read")
    @Operation(
            summary = "List server event templates",
            description =
                    """
                    Retrieve a list of templates available on this Cryostat server. These templates can be applied to
                    recordings started on any discovered target, but any event configurations within the template which
                    reference events that do not exist on the target will be ignored.
                    """)
    public List<Template> listTemplates() throws Exception {
        var list = new ArrayList<Template>();
        list.add(ALL_EVENTS_TEMPLATE);
        list.addAll(customTemplateService.getTemplates());
        list.addAll(presetTemplateService.getTemplates());
        return list;
    }

    @GET
    @Path("/{templateType}")
    @Blocking
    @RolesAllowed("read")
    @Operation(summary = "List server event templates of the given type")
    public List<Template> getTemplates(@RestPath String templateType)
            throws IOException, FlightRecorderException {
        TemplateType tt = TemplateType.valueOf(templateType);
        switch (tt) {
            case CUSTOM:
                return customTemplateService.getTemplates();
            case PRESET:
                return presetTemplateService.getTemplates();
            default:
                throw new BadRequestException();
        }
    }

    @GET
    @Path("/{templateType}/{templateName}")
    @Blocking
    @RolesAllowed("read")
    @Produces(MediaType.APPLICATION_XML)
    @Operation(
            summary = "Get a specific event template",
            description =
                    """
                    Get the .jfc (XML) file definition for the given server event template. This is the same type of
                    event configuration file that ships with OpenJDK distributions.
                    """)
    public String getTemplate(@RestPath String templateType, @RestPath String templateName)
            throws IOException, FlightRecorderException {
        TemplateType tt = TemplateType.valueOf(templateType);
        TemplateService svc;
        switch (tt) {
            case CUSTOM:
                svc = customTemplateService;
                break;
            case PRESET:
                svc = presetTemplateService;
                break;
            default:
                throw new BadRequestException();
        }
        return svc.getXml(templateName, tt).orElseThrow(() -> new NotFoundException());
    }

    @POST
    @RolesAllowed("write")
    @Operation(
            summary = "Upload a custom event template",
            description =
                    """
                    Upload a new custom event template to the server. This must be in OpenJDK .jfc (XML) format.
                    """)
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
        } finally {
            // Clean up temporary files
            Files.delete(body.filePath());
        }
    }

    @DELETE
    @Blocking
    @Path("/{templateName}")
    @RolesAllowed("write")
    @Operation(
            summary = "Delete a custom event template",
            description =
                    """
                    Delete a custom event template from the server. Only previously uploaded custom event templates can
                    be deleted.
                    """)
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
