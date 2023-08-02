/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
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
