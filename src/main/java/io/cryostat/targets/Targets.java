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
package io.cryostat.targets;

import java.util.List;

import io.cryostat.expressions.MatchExpressionEvaluator;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;

@Path("")
public class Targets {

    @Inject MatchExpressionEvaluator matchExpressionEvaluator;
    @Inject TargetConnectionManager connectionManager;
    @Inject Logger logger;

    @GET
    @Path("/api/v4/targets")
    @RolesAllowed("read")
    @Operation(
            summary = "List currently discovered targets",
            description =
                    """
                    Get a list of the currently discovered targets. These are essentialy the same as the leaf nodes of
                    the discovery tree. See 'GET /api/v4/discovery'.
                    """)
    public List<Target> list() {
        return Target.listAll();
    }

    @GET
    @Path("/api/v4/targets/{id}")
    @RolesAllowed("read")
    @Operation(
            summary = "Get a target by ID",
            description =
                    """
                    Get details about a particular target given its ID.
                    """)
    public Target getById(@RestPath Long id) {
        return Target.find("id", id).singleResult();
    }
}
