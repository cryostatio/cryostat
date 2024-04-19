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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.cryostat.V2Response;
import io.cryostat.core.agent.AgentJMXHelper;
import io.cryostat.core.agent.Event;
import io.cryostat.core.agent.ProbeTemplate;
import io.cryostat.core.sys.FileSystem;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.smallrye.common.annotation.Blocking;
import io.vertx.core.eventbus.EventBus;
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

    private static final String PROBES_REMOVED_CATEGORY = "ProbesRemoved";
    private static final String TEMPLATE_APPLIED_CATEGORY = "ProbeTemplateApplied";

    @Inject Logger logger;
    @Inject TargetConnectionManager connectionManager;
    @Inject S3ProbeTemplateService service;
    @Inject FileSystem fs;

    @Inject EventBus bus;

    @Blocking
    @POST
    @Path("/api/v3/targets/{id}/probes/{probeTemplateName}")
    public Response postProbe(@RestPath long id, @RestPath String probeTemplateName) {
        try {
            Target target = Target.getTargetById(id);
            return connectionManager.executeConnectedTask(
                    target,
                    connection -> {
                        AgentJMXHelper helper = new AgentJMXHelper(connection.getHandle());
                        try {
                            ProbeTemplate template = new ProbeTemplate();
                            // Retrieve template and deserialize to validate
                            String templateContent = service.getTemplateContent(probeTemplateName);
                            template.deserialize(
                                    new ByteArrayInputStream(
                                            templateContent.getBytes(StandardCharsets.UTF_8)));
                            helper.defineEventProbes(templateContent);
                            bus.publish(
                                    MessagingServer.class.getName(),
                                    new Notification(
                                            TEMPLATE_APPLIED_CATEGORY,
                                            Map.of("probeTemplate", template.getFileName())));
                            return Response.status(RestResponse.Status.OK).build();
                        } catch (Exception e) {
                            // Cleanup the probes if something went wrong, calling defineEventProbes
                            // with a null argument will remove any active probes.
                            helper.defineEventProbes(null);
                            return Response.status(RestResponse.Status.INTERNAL_SERVER_ERROR)
                                    .build();
                        }
                    });
        } catch (Exception e) {
            logger.warn("Caught exception" + e.toString(), e);
            throw new BadRequestException(e);
        }
    }

    @Blocking
    @POST
    @Path("/api/v2/targets/{connectUrl}/probes/{probeTemplateName}")
    public Response postProbev2(@RestPath URI connectUrl, @RestPath String probeTemplateName) {
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(
                        URI.create(
                                String.format(
                                        "/api/v3/targets/%d/probes/%s",
                                        Target.getTargetByConnectUrl(connectUrl).id,
                                        probeTemplateName)))
                .build();
    }

    @Blocking
    @DELETE
    @Path("/api/v3/targets/{id}/probes")
    public Response deleteProbe(@RestPath long id) {
        try {
            Target target = Target.getTargetById(id);
            return connectionManager.executeConnectedTask(
                    target,
                    connection -> {
                        try {
                            AgentJMXHelper helper = new AgentJMXHelper(connection.getHandle());
                            // The convention for removing probes in the agent controller mbean is
                            // to
                            // call defineEventProbes with a null argument.
                            helper.defineEventProbes(null);
                            bus.publish(
                                    MessagingServer.class.getName(),
                                    new Notification(
                                            PROBES_REMOVED_CATEGORY, Map.of("target", target.id)));
                            return Response.status(RestResponse.Status.OK).build();
                        } catch (Exception e) {
                            return Response.status(RestResponse.Status.INTERNAL_SERVER_ERROR)
                                    .build();
                        }
                    });
        } catch (Exception e) {
            logger.warn("Caught exception" + e.toString(), e);
            throw new BadRequestException(e);
        }
    }

    @Blocking
    @DELETE
    @Path("/api/v2/targets/{connectUrl}/probes")
    public Response deleteProbev2(@RestPath URI connectUrl) {
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(
                        URI.create(
                                String.format(
                                        "/api/v3/targets/%d/probes",
                                        Target.getTargetByConnectUrl(connectUrl).id)))
                .build();
    }

    @Blocking
    @GET
    @Path("/api/v3/targets/{id}/probes")
    public V2Response getProbes(@RestPath long id) {
        try {
            Target target = Target.getTargetById(id);
            return connectionManager.executeConnectedTask(
                    target,
                    connection -> {
                        AgentJMXHelper helper = new AgentJMXHelper(connection.getHandle());
                        List<Event> result = new ArrayList<>();
                        String probes = helper.retrieveEventProbes();
                        try {
                            if (probes != null && !probes.isBlank()) {
                                ProbeTemplate template = new ProbeTemplate();
                                template.deserialize(
                                        new ByteArrayInputStream(
                                                probes.getBytes(StandardCharsets.UTF_8)));
                                for (Event e : template.getEvents()) {
                                    result.add(e);
                                }
                            }
                            return V2Response.json(Response.Status.OK, result);
                        } catch (Exception e) {
                            throw new BadRequestException(e);
                        }
                    });
        } catch (Exception e) {
            logger.warn("Caught exception" + e.toString(), e);
            throw new BadRequestException(e);
        }
    }

    @Blocking
    @GET
    @Path("/api/v2/targets/{connectUrl}/probes")
    public Response getProbesv2(@RestPath URI connectUrl) {
        Target target = Target.getTargetByConnectUrl(connectUrl);
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(URI.create(String.format("/api/v3/targets/%d/probes", target.id)))
                .build();
    }

    @Blocking
    @GET
    @Path("/api/v3/probes")
    public V2Response getProbeTemplates() {
        try {
            return V2Response.json(
                    Response.Status.OK,
                    service.getTemplates().stream()
                            .map(SerializableProbeTemplateInfo::fromProbeTemplate)
                            .toList());
        } catch (Exception e) {
            logger.warn("Caught exception" + e.toString(), e);
            throw new BadRequestException(e);
        }
    }

    @Blocking
    @GET
    @Path("/api/v2/probes")
    public Response getProbeTemplatesv2() {
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(URI.create("/api/v3/probes"))
                .build();
    }

    @Blocking
    @DELETE
    @Path("/api/v3/probes/{probeTemplateName}")
    public Response deleteProbeTemplate(@RestPath String probeTemplateName) {
        try {
            service.deleteTemplate(probeTemplateName);
            return Response.status(RestResponse.Status.OK).build();
        } catch (Exception e) {
            logger.warn("Caught exception" + e.toString(), e);
            throw new BadRequestException(e);
        }
    }

    @Blocking
    @DELETE
    @Path("/api/v2/probes/{probeTemplateName}")
    public Response deleteProbeTemplatesv2(@RestPath String probeTemplateName) {
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(URI.create(String.format("/api/v3/probes/%s", probeTemplateName)))
                .build();
    }

    @Blocking
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

    @Blocking
    @POST
    @Path("/api/v2/probes/{probeTemplateName}")
    public Response postProbeTemplatev2(
            @RestForm("probeTemplate") FileUpload body, @RestPath String probeTemplateName) {
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(URI.create(String.format("/api/v3/probes/%s", probeTemplateName)))
                .build();
    }
}
