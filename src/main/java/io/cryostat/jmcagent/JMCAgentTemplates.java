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

import java.util.List;

import io.cryostat.libcryostat.sys.FileSystem;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.multipart.FileUpload;

@Path("")
public class JMCAgentTemplates {

    @Inject Logger logger;
    @Inject S3ProbeTemplateService service;
    @Inject FileSystem fs;

    @Blocking
    @GET
    @Path("/api/v4/probes")
    public List<ProbeTemplateResponse> getProbeTemplates() {
        try {
            return service.getTemplates().stream()
                    .map(SerializableProbeTemplateInfo::fromProbeTemplate)
                    .map(ProbeTemplateResponse::new)
                    .toList();
        } catch (Exception e) {
            logger.warn("Caught exception: " + e.toString(), e);
            throw new BadRequestException(e);
        }
    }

    @Blocking
    @DELETE
    @Path("/api/v4/probes/{probeTemplateName}")
    public void deleteProbeTemplate(@RestPath String probeTemplateName) {
        try {
            service.deleteTemplate(probeTemplateName);
        } catch (Exception e) {
            logger.warn("Caught exception" + e.toString(), e);
            throw new BadRequestException(e);
        }
    }

    @Blocking
    @POST
    @Path("/api/v4/probes/{probeTemplateName}")
    public void uploadProbeTemplate(
            @RestForm("probeTemplate") FileUpload body, @RestPath String probeTemplateName) {
        if (body == null || body.filePath() == null || !"probeTemplate".equals(body.name())) {
            throw new BadRequestException();
        }
        try (var stream = fs.newInputStream(body.filePath())) {
            service.addTemplate(stream, probeTemplateName);
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
            throw new BadRequestException(e);
        }
    }

    static record ProbeTemplateResponse(String name, String description) {
        ProbeTemplateResponse(SerializableProbeTemplateInfo templateInfo) {
            this(templateInfo.name(), templateInfo.xml());
        }
    }
}
