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

package io.cryostat.triggers;

import java.time.Duration;
import java.util.List;

import io.cryostat.ConfigProperties;
import io.cryostat.libcryostat.triggers.SmartTrigger;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;

@Path("")
public class SmartTriggers {

    @Inject Logger log;

    @Inject TargetConnectionManager targetConnectionManager;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_UPLOAD_TIMEOUT)
    Duration uploadFailedTimeout;

    @Path("targets/{targetId}/smart-triggers")
    @RolesAllowed("read")
    @Transactional
    @GET
    public List<SmartTrigger> getSmartTriggers(@RestPath long targetId) {
        log.trace("Smart triggers list request received");
        Target target = Target.getTargetById(targetId);
        return targetConnectionManager.executeConnectedTask(
                target, conn -> conn.listSmartTriggers(), uploadFailedTimeout);
    }

    @Path("targets/{targetId}/smart-triggers")
    @RolesAllowed("write")
    @Transactional
    @POST
    public void addSmartTriggers(@RestPath long targetId, @RestForm String definition) {
        log.tracev("Smart Triggers Add request received: {0}", definition);
        Target target = Target.getTargetById(targetId);
        targetConnectionManager.executeConnectedTask(
                target,
                conn -> {
                    conn.addSmartTriggers(definition);
                    return null;
                },
                uploadFailedTimeout);
    }

    @Path("targets/{targetId}/smart-triggers")
    @RolesAllowed("write")
    @Transactional
    @DELETE
    public void removeSmartTriggers(@RestPath long targetId, @RestForm String definition) {
        log.tracev("Smart Triggers Remove request received: {0}", definition);
        Target target = Target.getTargetById(targetId);
        targetConnectionManager.executeConnectedTask(
                target,
                conn -> {
                    conn.removeSmartTriggers(definition);
                    return null;
                },
                uploadFailedTimeout);
    }
}
