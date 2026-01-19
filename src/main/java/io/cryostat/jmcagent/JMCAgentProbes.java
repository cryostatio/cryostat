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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.management.InstanceNotFoundException;

import io.cryostat.core.jmcagent.Event;
import io.cryostat.core.jmcagent.ProbeTemplate;
import io.cryostat.libcryostat.sys.FileSystem;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.smallrye.common.annotation.Blocking;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;

@Path("")
public class JMCAgentProbes {

    private static final String PROBES_REMOVED_CATEGORY = "ProbesRemoved";
    private static final String TEMPLATE_APPLIED_CATEGORY = "ProbeTemplateApplied";
    private static final String AGENT_OBJECT_NAME =
            "org.openjdk.jmc.jfr.agent:type=AgentController";
    private static final String DEFINE_EVENT_PROBES = "defineEventProbes";
    private static final String RETRIEVE_EVENT_PROBES = "retrieveEventProbes";
    private static final String[] DEFINE_EVENT_PROBES_SIGNATURE = {String.class.getName()};

    @Inject Logger logger;
    @Inject TargetConnectionManager connectionManager;
    @Inject S3ProbeTemplateService service;
    @Inject FileSystem fs;

    @Inject EventBus bus;

    @Blocking
    @POST
    @Path("/api/v4/targets/{id}/probes/{probeTemplateName}")
    @Operation(
            summary = "Activate a probe template on the specified target",
            description =
                    """
                    Activate a probe template (specified by template name) on the specified target (specified by ID).
                    """)
    public void postProbe(@RestPath long id, @RestPath String probeTemplateName) {
        Target target = Target.getTargetById(id);
        connectionManager.executeConnectedTask(
                target,
                connection -> {
                    try {
                        String templateContent = service.getTemplateContent(probeTemplateName);
                        ProbeTemplate template = new ProbeTemplate();
                        template.setFileName(probeTemplateName);
                        template.deserialize(
                                new ByteArrayInputStream(
                                        templateContent.getBytes(StandardCharsets.UTF_8)));
                        Object[] args = {templateContent};
                        connection.invokeMBeanOperation(
                                AGENT_OBJECT_NAME,
                                DEFINE_EVENT_PROBES,
                                args,
                                DEFINE_EVENT_PROBES_SIGNATURE,
                                Void.class);
                        bus.publish(
                                MessagingServer.class.getName(),
                                new Notification(
                                        TEMPLATE_APPLIED_CATEGORY,
                                        Map.of(
                                                "jvmId",
                                                target.jvmId,
                                                "probeTemplate",
                                                probeTemplateName)));
                        return null;
                    } catch (InstanceNotFoundException infe) {
                        throw new BadRequestException(infe);
                    } catch (Exception e) {
                        // Cleanup the probes if something went wrong, calling defineEventProbes
                        // with a literal null argument will remove any active probes.
                        Object[] args = {null};
                        connection.invokeMBeanOperation(
                                AGENT_OBJECT_NAME,
                                DEFINE_EVENT_PROBES,
                                args,
                                DEFINE_EVENT_PROBES_SIGNATURE,
                                Void.class);
                        throw new InternalServerErrorException(e);
                    }
                });
    }

    @Blocking
    @DELETE
    @Path("/api/v4/targets/{id}/probes")
    @Operation(summary = "Remove all loaded probes from the specified target")
    public void deleteProbe(@RestPath long id) {
        Target target = Target.getTargetById(id);
        connectionManager.executeConnectedTask(
                target,
                connection -> {
                    try {
                        // The convention for removing probes in the agent controller mbean is
                        // to call defineEventProbes with a null argument.
                        Object[] args = {null};
                        connection.invokeMBeanOperation(
                                AGENT_OBJECT_NAME,
                                DEFINE_EVENT_PROBES,
                                args,
                                DEFINE_EVENT_PROBES_SIGNATURE,
                                Void.class);
                        bus.publish(
                                MessagingServer.class.getName(),
                                new Notification(
                                        PROBES_REMOVED_CATEGORY,
                                        Map.of(
                                                "jvmId",
                                                target.jvmId,
                                                "target",
                                                target.connectUrl.toString())));
                        return null;
                    } catch (InstanceNotFoundException infe) {
                        throw new BadRequestException(infe);
                    } catch (Exception e) {
                        throw new InternalServerErrorException(e);
                    }
                });
    }

    @Blocking
    @GET
    @Path("/api/v4/targets/{id}/probes")
    @Operation(summary = "List loaded probes on the specified target")
    public List<ProbeResponse> getProbes(@RestPath long id) {
        Target target = Target.getTargetById(id);
        return connectionManager.<List<ProbeResponse>>executeConnectedTask(
                target,
                connection -> {
                    String probes;
                    try {
                        probes =
                                connection.invokeMBeanOperation(
                                        AGENT_OBJECT_NAME,
                                        RETRIEVE_EVENT_PROBES,
                                        new Object[0],
                                        new String[0],
                                        String.class);
                    } catch (Exception e) {
                        logger.error("Failed to retrieve event probes", e);
                        throw new BadRequestException(e);
                    }

                    if (StringUtils.isBlank(probes)) {
                        return List.of();
                    }

                    try {
                        ProbeTemplate template = new ProbeTemplate();
                        template.deserialize(
                                new ByteArrayInputStream(probes.getBytes(StandardCharsets.UTF_8)));

                        return Arrays.asList(template.getEvents()).stream()
                                .map(ProbeResponse::new)
                                .toList();
                    } catch (Exception e) {
                        // Cleanup the probes if something went wrong
                        Object[] args = {null};
                        connection.invokeMBeanOperation(
                                AGENT_OBJECT_NAME,
                                DEFINE_EVENT_PROBES,
                                args,
                                new String[0],
                                Void.class);
                        logger.error("Error processing probes, cleaning up active probes", e);
                        throw new InternalServerErrorException(e);
                    }
                });
    }

    static record ProbeResponse(String name, String description) {
        ProbeResponse(Event e) {
            this(e.name, e.description);
        }
    }
}
