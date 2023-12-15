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
import java.util.Objects;

import io.cryostat.V2Response;
import io.cryostat.expressions.MatchExpression;
import io.cryostat.expressions.MatchExpression.TargetMatcher;

import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;
import org.projectnessie.cel.tools.ScriptException;

@Path("/api/v2.2/credentials")
public class Credentials {

    @Inject TargetMatcher targetMatcher;
    @Inject Logger logger;

    @GET
    @RolesAllowed("read")
    public V2Response list() {
        List<Credential> credentials = Credential.listAll();
        return V2Response.json(
                Response.Status.OK,
                credentials.stream()
                        .map(
                                c -> {
                                    try {
                                        return Credentials.safeResult(c, targetMatcher);
                                    } catch (ScriptException e) {
                                        logger.warn(e);
                                        return null;
                                    }
                                })
                        .filter(Objects::nonNull)
                        .toList());
    }

    @GET
    @RolesAllowed("read")
    @Path("/{id}")
    public V2Response get(@RestPath long id) throws ScriptException {
        Credential credential = Credential.find("id", id).singleResult();
        return V2Response.json(Response.Status.OK, safeMatchedResult(credential, targetMatcher));
    }

    @Transactional
    @POST
    @RolesAllowed("write")
    public RestResponse<Void> create(
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

    @Blocking
    static Map<String, Object> safeResult(Credential credential, TargetMatcher matcher)
            throws ScriptException {
        Map<String, Object> result = new HashMap<>();
        result.put("id", credential.id);
        result.put("matchExpression", credential.matchExpression);
        result.put(
                "numMatchingTargets", matcher.match(credential.matchExpression).targets().size());
        return result;
    }

    @Blocking
    static Map<String, Object> safeMatchedResult(Credential credential, TargetMatcher matcher)
            throws ScriptException {
        Map<String, Object> result = new HashMap<>();
        result.put("matchExpression", credential.matchExpression);
        result.put("targets", matcher.match(credential.matchExpression).targets());
        return result;
    }
}
