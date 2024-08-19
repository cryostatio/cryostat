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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.cryostat.expressions.MatchExpression;
import io.cryostat.expressions.MatchExpression.TargetMatcher;

import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;
import org.projectnessie.cel.tools.ScriptException;

@Path("/api/v4/credentials")
public class Credentials {

    @Inject TargetMatcher targetMatcher;
    @Inject Logger logger;

    @Blocking
    @GET
    @RolesAllowed("read")
    public Response list() {
        try {
            List<Credential> credentials = Credential.listAll();
            List<Map<String, Object>> results =
                    credentials.stream()
                            .map(
                                    c -> {
                                        try {
                                            return safeResult(c, targetMatcher);
                                        } catch (ScriptException e) {
                                            logger.warn(e);
                                            return null;
                                        }
                                    })
                            .filter(Objects::nonNull)
                            .toList();

            return Response.ok(results).type(MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            logger.error("Error listing credentials", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @Blocking
    @GET
    @RolesAllowed("read")
    @Path("/{id}")
    public Response get(@RestPath long id) throws ScriptException {
        try {
            Credential credential = Credential.find("id", id).singleResult();
            Map<String, Object> result = safeResult(credential, targetMatcher);
            return Response.ok(result).type(MediaType.APPLICATION_JSON).build();
        } catch (ScriptException e) {
            logger.error("Error retrieving credential", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        } catch (NoResultException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Unexpected error occurred", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @Transactional
    @POST
    @RolesAllowed("write")
    public RestResponse<Credential> create(
            @Context UriInfo uriInfo,
            @RestForm String matchExpression,
            @RestForm String username,
            @RestForm String password) {
        MatchExpression expr = new MatchExpression(matchExpression);
        expr.persist();
        Credential credential = new Credential();
        credential.matchExpression = expr;
        credential.username = username;
        credential.password = password;
        credential.persist();
        return ResponseBuilder.<Credential>created(
                        uriInfo.getAbsolutePathBuilder().path(Long.toString(credential.id)).build())
                .entity(credential)
                .build();
    }

    @Transactional
    @DELETE
    @RolesAllowed("write")
    @Path("/{id}")
    public void delete(@RestPath long id) {
        Credential.find("id", id).singleResult().delete();
    }

    static Map<String, Object> notificationResult(Credential credential) throws ScriptException {
        Map<String, Object> result = new HashMap<>();
        result.put("id", credential.id);
        result.put("matchExpression", credential.matchExpression);
        // TODO populating this on the credential post-persist hook leads to a database validation
        // error because the expression ends up getting defined twice with the same ID, somehow.
        // Populating this field with 0 means the UI is inaccurate when a new credential is first
        // defined, but after a refresh the data correctly updates.
        result.put("numMatchingTargets", 0);
        return result;
    }

    static Map<String, Object> safeResult(Credential credential, TargetMatcher matcher)
            throws ScriptException {
        Map<String, Object> result = new HashMap<>();
        result.put("id", credential.id);
        result.put("matchExpression", credential.matchExpression);
        // TODO remove numMatchingTargets, clients can just use targets.length
        result.put(
                "numMatchingTargets", matcher.match(credential.matchExpression).targets().size());
        result.put("targets", matcher.match(credential.matchExpression).targets());
        return result;
    }
}
