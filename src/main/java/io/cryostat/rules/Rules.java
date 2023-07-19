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
package io.cryostat.rules;

import io.cryostat.V2Response;

import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;

@Path("/api/v2/rules")
public class Rules {

    @Inject EventBus bus;

    // @ServerExceptionMapper
    // public RestResponse<String> mapE()

    @GET
    @RolesAllowed("read")
    public RestResponse<V2Response> list() {
        return RestResponse.ok(V2Response.json(Rule.listAll()));
    }

    @GET
    @RolesAllowed("read")
    @Path("/{name}")
    public RestResponse<V2Response> get(@RestPath String name) {
        return RestResponse.ok(V2Response.json(Rule.getByName(name)));
    }

    @Transactional
    @POST
    @RolesAllowed("write")
    @Consumes({MediaType.APPLICATION_JSON})
    public RestResponse<V2Response> create(Rule rule) {
        // TODO validate the incoming rule
        if (rule == null) {
            throw new BadRequestException("POST body was null");
        }
        boolean ruleExists = Rule.getByName(rule.name) != null;
        if (ruleExists) {
            throw new RuleExistsException(rule.name);
        }
        rule.persist();
        return ResponseBuilder.create(Response.Status.CREATED, V2Response.json(rule.name)).build();
    }

    @Transactional
    @PATCH
    @RolesAllowed("write")
    @Path("/{name}")
    @Consumes("application/json")
    public RestResponse<V2Response> update(
            @RestPath String name, @RestQuery boolean clean, JsonObject body) {
        Rule rule = Rule.getByName(name);
        boolean enabled = body.getBoolean("enabled");
        // order matters here, we want to clean before we disable
        if (clean && !enabled) {
            bus.send(Rule.RULE_ADDRESS + "?clean", rule);
        }
        rule.enabled = enabled;
        rule.persist();

        return ResponseBuilder.ok(V2Response.json(rule)).build();
    }

    @Transactional
    @POST
    @RolesAllowed("write")
    @Consumes({MediaType.MULTIPART_FORM_DATA, MediaType.APPLICATION_FORM_URLENCODED})
    public RestResponse<V2Response> create(
            @RestForm String name,
            @RestForm String description,
            @RestForm String matchExpression,
            @RestForm String eventSpecifier,
            @RestForm int archivalPeriodSeconds,
            @RestForm int initialDelaySeconds,
            @RestForm int preservedArchives,
            @RestForm int maxAgeSeconds,
            @RestForm int maxSizeBytes,
            @RestForm boolean enabled) {
        Rule rule = new Rule();
        rule.name = name;
        rule.description = description;
        rule.matchExpression = matchExpression;
        rule.eventSpecifier = eventSpecifier;
        rule.archivalPeriodSeconds = archivalPeriodSeconds;
        rule.initialDelaySeconds = initialDelaySeconds;
        rule.preservedArchives = preservedArchives;
        rule.maxAgeSeconds = maxAgeSeconds;
        rule.maxSizeBytes = maxSizeBytes;
        rule.enabled = enabled;
        return create(rule);
    }

    @Transactional
    @DELETE
    @RolesAllowed("write")
    @Path("/{name}")
    public RestResponse<V2Response> delete(@RestPath String name, @RestQuery boolean clean) {
        Rule rule = Rule.getByName(name);
        if (clean) {
            bus.send(Rule.RULE_ADDRESS + "?clean", rule);
        }
        rule.delete();
        return RestResponse.ok(V2Response.json(null));
    }

    static class RuleExistsException extends ClientErrorException {
        RuleExistsException(String ruleName) {
            super("Rule with name " + ruleName + " already exists", Response.Status.CONFLICT);
        }
    }
}
