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
package io.cryostat.jmcagent;

import java.io.IOException;
import java.util.List;

import io.cryostat.core.jmcagent.ProbeTemplate;
import io.cryostat.libcryostat.sys.FileSystem;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.xml.sax.SAXException;

@Path("/api/v4/probes")
public class JMCAgentTemplates {

    @Inject Logger logger;
    @Inject S3ProbeTemplateService service;
    @Inject FileSystem fs;

    @Blocking
    @GET
    @Operation(summary = "List defined probe templates")
    public List<ProbeTemplateResponse> getProbeTemplates() {
        return service.getTemplates().stream()
                .map(SerializableProbeTemplateInfo::fromProbeTemplate)
                .map(ProbeTemplateResponse::new)
                .toList();
    }

    @Blocking
    @DELETE
    @Path("/{probeTemplateName}")
    @Operation(summary = "Delete the specified probe template")
    public void deleteProbeTemplate(@RestPath String probeTemplateName) {
        service.deleteTemplate(probeTemplateName);
    }

    @Blocking
    @POST
    @Operation(
            summary = "Create a probe template",
            description =
                    """
                        Create a probe template. This requires a probe template file upload in XML format. See
                        https://github.com/openjdk/jmc/blob/master/agent/README.md and
                        https://github.com/openjdk/jmc/blob/master/agent/src/main/resources/org/openjdk/jmc/agent/impl/jfrprobes_schema.xsd
                        for more information about this file format.
                    """)
    public RestResponse<ProbeTemplate> uploadProbeTemplate(
            @Context UriInfo uriInfo,
            @RestForm("probeTemplate") FileUpload body,
            @RestForm("name") String name)
            throws IOException, SAXException {
        if (StringUtils.isBlank(name)) {
            throw new BadRequestException("Request must contain a 'name' form parameter");
        }
        if (body == null || body.filePath() == null || !"probeTemplate".equals(body.name())) {
            throw new BadRequestException("Request must contain a 'probeTemplate' file upload");
        }
        var probeTemplate = service.addTemplate(body.filePath(), name);
        return ResponseBuilder.<ProbeTemplate>created(
                        uriInfo.getAbsolutePathBuilder().path(probeTemplate.getFileName()).build())
                .entity(probeTemplate)
                .build();
    }

    static record ProbeTemplateResponse(String name, String xml) {
        ProbeTemplateResponse(SerializableProbeTemplateInfo templateInfo) {
            this(templateInfo.name(), templateInfo.xml());
        }
    }
}
