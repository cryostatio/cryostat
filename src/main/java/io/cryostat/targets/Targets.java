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

import java.net.URI;
import java.util.List;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;

@Path("")
public class Targets {

    @GET
    @Path("/api/v1/targets")
    @RolesAllowed("read")
    public Response listV1() {
        return Response.status(RestResponse.Status.PERMANENT_REDIRECT)
                .location(URI.create("/api/v3/targets"))
                .build();
    }

    @GET
    @Path("/api/v3/targets")
    @RolesAllowed("read")
    public List<Target> list() {
        return Target.listAll();
    }

    @GET
    @Path("/api/v3/targets/{id}")
    @RolesAllowed("read")
    public Target getById(@RestPath Long id) {
        return Target.find("id", id).singleResult();
    }
}
