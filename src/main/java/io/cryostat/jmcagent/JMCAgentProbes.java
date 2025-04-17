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
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;

@Path("")
public class JMCAgentProbes {

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
    public void postProbe(@RestPath long id, @RestPath String probeTemplateName) {
        try {
            Target target = Target.getTargetById(id);
            connectionManager.executeConnectedTask(
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
                            return null;
                        } catch (ProbeDefinitionException e) {
                            // Cleanup the probes if something went wrong, calling defineEventProbes
                            // with a null argument will remove any active probes.
                            helper.defineEventProbes(null);
                            throw new InternalServerErrorException(e);
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
    public void deleteProbe(@RestPath long id) {
        try {
            Target target = Target.getTargetById(id);
            connectionManager.executeConnectedTask(
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
                            return null;
                        } catch (Exception e) {
                            throw new InternalServerErrorException(e);
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
    public List<ProbeResponse> getProbes(@RestPath long id) {
        try {
            Target target = Target.getTargetById(id);

            return connectionManager.<List<ProbeResponse>>executeConnectedTask(
                    target,
                    connection -> {
                        JMCAgentJMXHelper helper = new JMCAgentJMXHelper(connection.getHandle());
                        String probes;
                        try {
                            probes = helper.retrieveEventProbes();
                        } catch (Exception e) {
                            logger.error("Failed to retrieve event probes", e);
                            throw new BadRequestException(e);
                        }

                        if (probes == null || probes.isBlank()) {
                            return List.of();
                        }

                        try {
                            ProbeTemplate template = new ProbeTemplate();
                            template.deserialize(
                                    new ByteArrayInputStream(
                                            probes.getBytes(StandardCharsets.UTF_8)));

                            return Arrays.asList(template.getEvents()).stream()
                                    .map(ProbeResponse::new)
                                    .toList();
                        } catch (Exception e) {
                            // Cleanup the probes if something went wrong
                            helper.defineEventProbes(null);
                            logger.error("Error processing probes, cleaning up active probes", e);
                            throw new InternalServerErrorException(e);
                        }
                    });
        } catch (Exception e) {
            logger.warn("Caught exception while handling getProbes request", e);
            throw new InternalServerErrorException(e);
        }
    }

    static record ProbeResponse(String name, String description) {
        ProbeResponse(Event e) {
            this(e.name, e.description);
        }
    }
}
