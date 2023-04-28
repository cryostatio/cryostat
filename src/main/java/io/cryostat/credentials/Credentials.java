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
package io.cryostat.credentials;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.security.RolesAllowed;
import javax.transaction.Transactional;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;

import io.cryostat.V2Response;

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
