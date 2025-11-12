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
package io.cryostat.expressions;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.cryostat.expressions.MatchExpression.MatchedExpression;
import io.cryostat.targets.Target;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;
import org.projectnessie.cel.tools.ScriptException;

@Path("/api/v4/matchExpressions")
public class MatchExpressions {

    @Inject MatchExpression.TargetMatcher targetMatcher;
    @Inject Logger logger;

    @POST
    @RolesAllowed("read")
    @Blocking
    @Transactional
    @Operation(
            summary = "Test a MatchExpression against a list of Targets",
            description =
                    """
                    Given a list of Target IDs, retrieve each Target instance from the database, then return the list
                    filtered by the Targets which satisfy the Match Expression. If a given ID does not exist in the
                    database then the whole request will fail. The expression must evaluate to a boolean value for each
                    target. The match expression will not be stored.
                    """)
    public MatchedExpression test(RequestData requestData) throws ScriptException {
        var targets = new HashSet<Target>();
        if (requestData.targetIds == null) {
            targets.addAll(Target.<Target>listAll());
        } else {
            requestData.targetIds.forEach(id -> targets.add(Target.getTargetById(id)));
        }
        return targetMatcher.match(new MatchExpression(requestData.matchExpression), targets);
    }

    @GET
    @RolesAllowed("read")
    @Blocking
    @Operation(
            summary = "Retrieve a list of all currently defined Match Expressions",
            description =
                    """
                    Retrieve a list of all currently defined Match Expressions. These objects cannot be created
                    independently in the current API definition, so each of these expressions will be associated with
                    an Automated Rule or Stored Credential.
                    """)
    public Multi<Map<String, Object>> list() {
        List<MatchExpression> exprs = MatchExpression.listAll();
        // FIXME hack so that this endpoint renders the response as the entity object with id and
        // script fields, rather than allowing Jackson serialization to handle it normally where it
        // will be encoded as only the script as a raw string. This should be done using a JsonView
        // or similar technique instead
        return Multi.createFrom()
                .items(exprs.stream().map(expr -> Map.of("id", expr.id, "script", expr.script)));
    }

    @GET
    @Path("/{id}")
    @RolesAllowed("read")
    @Blocking
    @Operation(summary = "Retrieve a single Match Expression")
    public MatchedExpression get(@RestPath long id) throws ScriptException {
        return targetMatcher.match(MatchExpression.find("id", id).singleResult());
    }

    static record RequestData(String matchExpression, List<Long> targetIds) {
        RequestData {
            Objects.requireNonNull(matchExpression);
        }
    }
}
