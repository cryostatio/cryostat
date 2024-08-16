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
import java.util.List;
import java.util.Map;

import io.cryostat.core.jmcagent.Event;
import io.cryostat.core.jmcagent.JMCAgentJMXHelper;
import io.cryostat.core.jmcagent.JMCAgentJMXHelper.ProbeDefinitionException;
import io.cryostat.core.jmcagent.ProbeTemplate;
import io.cryostat.libcryostat.sys.FileSystem;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.smallrye.common.annotation.Blocking;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
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
    @Path("/api/v4/targets/{id}/probes/{probeTemplateName}")
    public Response postProbe(@RestPath long id, @RestPath String probeTemplateName) {
        try {
            Target target = Target.getTargetById(id);
            return connectionManager.executeConnectedTask(
                    target,
                    connection -> {
                        JMCAgentJMXHelper helper = new JMCAgentJMXHelper(connection.getHandle());
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
                                            Map.of(
                                                    "jvmId",
                                                    target.jvmId,
                                                    "events",
                                                    template.getEvents(),
                                                    "probeTemplate",
                                                    template.getFileName())));
                            return Response.status(RestResponse.Status.OK).build();
                        } catch (ProbeDefinitionException e) {
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
    @DELETE
    @Path("/api/v4/targets/{id}/probes")
    public Response deleteProbe(@RestPath long id) {
        try {
            Target target = Target.getTargetById(id);
            return connectionManager.executeConnectedTask(
                    target,
                    connection -> {
                        try {
                            JMCAgentJMXHelper helper =
                                    new JMCAgentJMXHelper(connection.getHandle());
                            // The convention for removing probes in the agent controller mbean is
                            // to call defineEventProbes with a null argument.
                            helper.defineEventProbes(null);
                            bus.publish(
                                    MessagingServer.class.getName(),
                                    new Notification(
                                            PROBES_REMOVED_CATEGORY,
                                            Map.of("jvmId", target.jvmId)));
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
    @GET
    @Path("/api/v4/targets/{id}/probes")
    public Response getProbes(@RestPath long id) {
        try {
            Target target = Target.getTargetById(id);

            return connectionManager.<Response>executeConnectedTask(
                    target,
                    connection -> {
                        JMCAgentJMXHelper helper = new JMCAgentJMXHelper(connection.getHandle());
                        String probes;
                        try {
                            probes = helper.retrieveEventProbes();
                        } catch (Exception e) {
                            logger.error("Failed to retrieve event probes", e);
                            return Response.status(Response.Status.BAD_REQUEST)
                                    .entity("Failed to retrieve event probes: " + e.getMessage())
                                    .build();
                        }

                        if (probes == null || probes.isBlank()) {
                            return Response.ok(new JsonArray().encode())
                                    .type(MediaType.APPLICATION_JSON)
                                    .build();
                        }

                        try {
                            ProbeTemplate template = new ProbeTemplate();
                            template.deserialize(
                                    new ByteArrayInputStream(
                                            probes.getBytes(StandardCharsets.UTF_8)));

                            JsonArray eventsJson = new JsonArray();
                            for (Event e : template.getEvents()) {
                                JsonObject eventJson = new JsonObject();
                                eventJson.put("name", e.name);
                                eventJson.put("description", e.description);
                                eventsJson.add(eventJson);
                            }
                            return Response.ok(eventsJson.encode())
                                    .type(MediaType.APPLICATION_JSON)
                                    .build();
                        } catch (Exception e) {
                            // Cleanup the probes if something went wrong
                            helper.defineEventProbes(null);
                            logger.error("Error processing probes, cleaning up active probes", e);
                            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                                    .entity("Error processing probes: " + e.getMessage())
                                    .build();
                        }
                    });
        } catch (Exception e) {
            logger.warn("Caught exception while handling getProbes request", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Unable to handle the getProbes request: " + e.getMessage())
                    .build();
        }
    }

    @Blocking
    @GET
    @Path("/api/v4/probes")
    public Response getProbeTemplates() {
        try {
            List<SerializableProbeTemplateInfo> templates =
                    service.getTemplates().stream()
                            .map(SerializableProbeTemplateInfo::fromProbeTemplate)
                            .toList();

            JsonArray templatesJson = new JsonArray();
            for (SerializableProbeTemplateInfo template : templates) {
                JsonObject templateJson = new JsonObject();
                templateJson.put("name", template.name());
                templateJson.put("description", template.xml());
                templatesJson.add(templateJson);
            }

            return Response.ok(templatesJson.encode()).type(MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            logger.warn("Caught exception: " + e.toString(), e);
            throw new BadRequestException(e);
        }
    }

    @Blocking
    @DELETE
    @Path("/api/v4/probes/{probeTemplateName}")
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
    @POST
    @Path("/api/v4/probes/{probeTemplateName}")
    public Response uploadProbeTemplate(
            @RestForm("probeTemplate") FileUpload body, @RestPath String probeTemplateName) {
        if (body == null || body.filePath() == null || !"probeTemplate".equals(body.name())) {
            throw new BadRequestException();
        }
        try (var stream = fs.newInputStream(body.filePath())) {
            service.addTemplate(stream, probeTemplateName);
            return Response.status(RestResponse.Status.OK).build();
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
            throw new BadRequestException(e);
        }
    }
}
