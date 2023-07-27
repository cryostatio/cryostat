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
package io.cryostat.credentials;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.cryostat.V2Response;

import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;

@Path("/api/v2.2/credentials")
public class Credentials {

    @GET
    @RolesAllowed("read")
    public V2Response list() {
        List<Credential> credentials = Credential.listAll();
        return V2Response.json(credentials.stream().map(Credentials::safeResult).toList());
    }

    @GET
    @RolesAllowed("read")
    @Path("/{id}")
    public V2Response get(@RestPath long id) {
        Credential credential = Credential.find("id", id).singleResult();
        return V2Response.json(safeMatchedResult(credential));
    }

    @Transactional
    @POST
    @RolesAllowed("write")
    public RestResponse<Void> create(
            @RestForm String matchExpression,
            @RestForm String username,
            @RestForm String password) {
        Credential credential = new Credential();
        credential.matchExpression = matchExpression;
        credential.username = username;
        credential.password = password;
        credential.persist();
        return ResponseBuilder.<Void>created(URI.create("/api/v2.2/credentials/" + credential.id))
                .build();
    }

    @Transactional
    @DELETE
    @RolesAllowed("write")
    @Path("/{id}")
    public void delete(@RestPath long id) {
        Credential credential = Credential.find("id", id).singleResult();
        credential.delete();
    }

    static Map<String, Object> safeResult(Credential credential) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", credential.id);
        result.put("matchExpression", credential.matchExpression);
        // TODO
        result.put("numMatchingTargets", 0);
        return result;
    }

    static Map<String, Object> safeMatchedResult(Credential credential) {
        Map<String, Object> result = new HashMap<>();
        result.put("matchExpression", credential.matchExpression);
        result.put("targets", List.of());
        return result;
    }
}
