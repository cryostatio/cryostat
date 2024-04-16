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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.cryostat.core.agent.AgentJMXHelper;
import io.cryostat.core.agent.Event;
import io.cryostat.core.agent.ProbeTemplate;
import io.cryostat.core.sys.FileSystem;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.multipart.FileUpload;

@Path("")
public class JMCAgent {

    @Inject Logger logger;
    @Inject TargetConnectionManager connectionManager;
    @Inject S3ProbeTemplateService service;
    @Inject FileSystem fs;

    @POST
    @Path("/api/v3/targets/{id}/probes/{probeTemplateName}")
    public Response postProbe(@RestPath long id, @RestPath String probeTemplateName) {
        try {
            Target target = Target.find("id", id).singleResult();
            return connectionManager.executeConnectedTask(
                    target,
                    connection -> {
                        AgentJMXHelper helper = new AgentJMXHelper(connection.getHandle());
                        String templateContent = service.getTemplateContent(probeTemplateName);
                        helper.defineEventProbes(templateContent);
                        ProbeTemplate template = new ProbeTemplate();
                        template.deserialize(
                                new ByteArrayInputStream(
                                        templateContent.getBytes(StandardCharsets.UTF_8)));
                        return Response.status(RestResponse.Status.OK).build();
                    });
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    @DELETE
    @Path("/api/v3/targets/{id}/probes")
    public Response deleteProbe(@RestPath long id) {
        try {
            Target target = Target.find("id", id).singleResult();
            return connectionManager.executeConnectedTask(
                    target,
                    connection -> {
                        AgentJMXHelper helper = new AgentJMXHelper(connection.getHandle());
                        // The convention for removing probes in the agent controller mbean is to
                        // call defineEventProbes with a null argument.
                        helper.defineEventProbes(null);
                        return Response.status(RestResponse.Status.OK).build();
                    });
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    @GET
    @Path("/api/v3/targets/{id}/probes")
    public List<Event> getProbes(@RestPath long id) {
        try {
            Target target = Target.find("id", id).singleResult();
            return connectionManager.executeConnectedTask(
                    target,
                    connection -> {
                        List<Event> response = new ArrayList<Event>();
                        AgentJMXHelper helper = new AgentJMXHelper(connection.getHandle());
                        try {
                            String probes = helper.retrieveEventProbes();
                            if (probes != null && !probes.isBlank()) {
                                ProbeTemplate template = new ProbeTemplate();
                                template.deserialize(
                                        new ByteArrayInputStream(
                                                probes.getBytes(StandardCharsets.UTF_8)));
                                for (Event e : template.getEvents()) {
                                    response.add(e);
                                }
                            }
                        } catch (Exception e) {
                            throw new BadRequestException(e);
                        }
                        return response;
                    });
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    @DELETE
    @Path("/api/v3/probes/{probeTemplateName}")
    public Response deleteProbeTemplate(@RestPath String probeTemplateName) {
        try {
            service.deleteTemplate(probeTemplateName);
            return Response.status(RestResponse.Status.OK).build();
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    @POST
    @Path("/api/v3/probes/{probeTemplateName}")
    public Response uploadProbeTemplate(
            @RestForm("probeTemplate") FileUpload body, @RestPath String probeTemplateName) {
        if (body == null || body.filePath() == null || !"probeTemplate".equals(body.name())) {
            throw new BadRequestException();
        }
        try (var stream = fs.newInputStream(body.filePath())) {
            service.addTemplate(stream, probeTemplateName);
            return Response.status(RestResponse.Status.OK).build();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new BadRequestException(e);
        }
    }
}
