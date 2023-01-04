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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;

import io.cryostat.V2Response;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.vertx.core.eventbus.EventBus;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;

@Path("/api/v2.2/credentials")
public class Credentials {

    @Inject EventBus bus;

    @GET
    @RolesAllowed("credential:read")
    public V2Response list() {
        List<Credential> credentials = Credential.listAll();
        return V2Response.json(credentials.stream().map(this::safeResult).toList());
    }

    @GET
    @RolesAllowed("credential:read")
    @Path("/{id}")
    public V2Response get(@RestPath long id) {
        Credential credential = Credential.findById(id);
        if (credential == null) {
            throw new NotFoundException();
        }
        return V2Response.json(safeMatchedResult(credential));
    }

    @Transactional
    @POST
    @RolesAllowed("credential:create")
    public void create(
            @RestForm String matchExpression,
            @RestForm String username,
            @RestForm String password) {
        Credential credential = new Credential();
        credential.matchExpression = matchExpression;
        credential.username = username;
        credential.password = password;
        credential.persist();
        bus.publish(
                MessagingServer.class.getName(),
                new Notification("CredentialsStored", safeResult(credential)));
    }

    @Transactional
    @DELETE
    @RolesAllowed("credential:delete")
    @Path("/{id}")
    public void delete(@RestPath long id) {
        Credential credential = Credential.findById(id);
        if (credential == null) {
            throw new NotFoundException();
        }
        credential.delete();
        bus.publish(
                MessagingServer.class.getName(),
                new Notification("CredentialsDeleted", safeResult(credential)));
    }

    private Map<String, Object> safeResult(Credential credential) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", credential.id);
        result.put("matchExpression", credential.matchExpression);
        // TODO
        result.put("numMatchingTargets", 0);
        return result;
    }

    private Map<String, Object> safeMatchedResult(Credential credential) {
        Map<String, Object> result = new HashMap<>();
        result.put("matchExpression", credential.matchExpression);
        result.put("targets", List.of());
        return result;
    }
}
