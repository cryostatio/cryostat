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

import java.util.ArrayList;
import java.util.List;

import io.cryostat.V2Response;
import io.cryostat.targets.Target;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response.Status;
import org.jboss.logging.Logger;
import org.projectnessie.cel.tools.ScriptCreateException;
import org.projectnessie.cel.tools.ScriptException;

@Path("/api/beta/matchExpressions")
public class MatchExpressions {

    @Inject MatchExpressionEvaluator evaluator;
    @Inject Logger logger;

    @POST
    @RolesAllowed("read")
    public V2Response test(RequestData requestData) throws ScriptCreateException {
        MatchExpression expr = new MatchExpression(requestData.matchExpression);
        var targets = requestData.targets;
        if (requestData.targets == null) {
            targets = Target.listAll();
        }
        List<Target> matches = new ArrayList<>(targets.size());
        for (Target t : targets) {
            try {
                if (evaluator.applies(expr, t)) {
                    matches.add(t);
                }
            } catch (IllegalArgumentException | ScriptException e) {
                throw new BadRequestException(e);
            }
        }
        return V2Response.json(
                new MatchedMatchExpression(requestData.matchExpression, matches),
                Status.OK.toString());
    }

    static record RequestData(String matchExpression, List<Target> targets) {}

    static record MatchedMatchExpression(String expression, List<Target> targets) {}
}
