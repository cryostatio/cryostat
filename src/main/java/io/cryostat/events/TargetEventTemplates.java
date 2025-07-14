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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import io.cryostat.core.templates.TemplateService;
import io.cryostat.libcryostat.templates.Template;
import io.cryostat.libcryostat.templates.TemplateType;
import io.cryostat.targets.Target;

import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;

@Path("/api/v4/targets/{id}/event_templates")
public class TargetEventTemplates {

    public static final Template ALL_EVENTS_TEMPLATE =
            new Template(
                    "ALL",
                    "Enable all available events in the target JVM, with default option values."
                            + " This will be very expensive and is intended primarily for testing"
                            + " Cryostat's own capabilities.",
                    "Cryostat",
                    TemplateType.TARGET);

    @Inject TargetTemplateService.Factory targetTemplateServiceFactory;
    @Inject S3TemplateService customTemplateService;
    @Inject PresetTemplateService presetTemplateService;
    @Inject Logger logger;

    @GET
    @Blocking
    @RolesAllowed("read")
    @Operation(
            summary = "Retrieve a list of event templates available on the given target",
            description =
                    """
                    Retrieve a list of event templates available on the given target when starting recordings on the
                    same target. This includes all of the server's available templates, plus the templates available
                    specifically from the target (ex. within /usr/lib/jvm/java/lib/jfr).
                    """)
    public List<Template> listTargetTemplates(@RestPath long id) throws Exception {
        Target target = Target.find("id", id).singleResult();
        var list = new ArrayList<Template>();
        list.add(ALL_EVENTS_TEMPLATE);
        Comparator<Template> comparator =
                Comparator.comparing(Template::getType)
                        .thenComparing(Comparator.comparing(Template::getName))
                        .thenComparing(Comparator.comparing(Template::getProvider));
        list.addAll(targetTemplateServiceFactory.create(target).getTemplates());
        list.addAll(customTemplateService.getTemplates());
        list.addAll(presetTemplateService.getTemplates());
        Collections.sort(list, comparator);
        return list;
    }

    @GET
    @Blocking
    @Path("/{templateType}/{templateName}")
    @RolesAllowed("read")
    @Produces(MediaType.APPLICATION_XML)
    @Operation(
            summary = "Get a specific event template",
            description =
                    """
                    Get the .jfc (XML) file definition for the given target event template.
                    """)
    public String getTargetTemplate(
            @RestPath long id, @RestPath TemplateType templateType, @RestPath String templateName)
            throws Exception {
        if (ALL_EVENTS_TEMPLATE.getName().equals(templateName)
                && ALL_EVENTS_TEMPLATE.getType().equals(templateType)) {
            throw new BadRequestException();
        }
        Target target = Target.find("id", id).singleResult();
        TemplateService svc;
        switch (templateType) {
            case TARGET:
                svc = targetTemplateServiceFactory.create(target);
                break;
            case CUSTOM:
                svc = customTemplateService;
                break;
            case PRESET:
                svc = presetTemplateService;
                break;
            default:
                throw new BadRequestException();
        }
        return svc.getXml(templateName, templateType).orElseThrow(() -> new NotFoundException());
    }
}
