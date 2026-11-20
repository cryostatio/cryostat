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
package io.cryostat.mbean;

import java.time.Duration;
import java.util.List;

import io.cryostat.ConfigProperties;
import io.cryostat.libcryostat.net.MbeanAttributeMap;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;

@Path("/")
public class MbeanQuery {

    @Inject Logger log;

    @Inject TargetConnectionManager targetConnectionManager;

    @ConfigProperty(name = ConfigProperties.CONNECTIONS_UPLOAD_TIMEOUT)
    Duration uploadFailedTimeout;

    @Inject ObjectMapper mapper;

    @Path("api/beta/targets/{targetId}/mbean-query")
    @RolesAllowed("read")
    @Transactional
    @Produces({MediaType.APPLICATION_JSON})
    @GET
    @Operation(summary = "Retrieve all currently available mbean attributes")
    public List<MbeanAttributeMap> queryMbeans(@RestPath long targetId) {
        log.tracev("Mbean query received for target {0}", targetId);
        Target target = Target.getTargetById(targetId);
        return targetConnectionManager.executeConnectedTask(
                target,
                conn -> {
                    return conn.queryMbeanAttributes();
                },
                uploadFailedTimeout);
    }
}
