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
package io.cryostat.rules;

import java.util.List;

import io.cryostat.expressions.MatchExpression;
import io.cryostat.util.EntityExistsException;

import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;

@Path("/api/v4/rules")
public class Rules {

    @Inject EventBus bus;

    @GET
    @RolesAllowed("read")
    public Response list() {
        List<Rule> rules = Rule.listAll();
        JsonObject meta =
                new JsonObject().put("type", MediaType.APPLICATION_JSON).put("status", "OK");
        JsonObject data = new JsonObject().put("result", rules);
        JsonObject response = new JsonObject().put("meta", meta).put("data", data);
        return Response.ok(response.encode(), MediaType.APPLICATION_JSON).build();
    }

    @GET
    @RolesAllowed("read")
    @Path("/{name}")
    public Response get(@RestPath String name) {
        Rule rule = Rule.getByName(name);
        return Response.ok(rule).build();
    }

    @Transactional
    @POST
    @RolesAllowed("write")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(Rule rule) {
        // TODO validate the incoming rule
        if (rule == null) {
            throw new BadRequestException("POST body was null");
        }
        boolean ruleExists = Rule.getByName(rule.name) != null;
        if (ruleExists) {
            throw new EntityExistsException("Rule", rule.name);
        }
        if (rule.description == null) {
            rule.description = "";
        }
        rule.persist();

        JsonObject meta =
                new JsonObject().put("type", MediaType.APPLICATION_JSON).put("status", "Created");
        JsonObject data = new JsonObject().put("result", rule);
        JsonObject response = new JsonObject().put("meta", meta).put("data", data);
        return Response.status(Response.Status.CREATED).entity(response.encode()).build();
    }

    @Transactional
    @PATCH
    @RolesAllowed("write")
    @Path("/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response update(@RestPath String name, @RestQuery boolean clean, JsonObject body) {
        Rule rule = Rule.getByName(name);
        if (rule == null) {
            throw new NotFoundException("Rule with name " + name + " not found");
        }

        boolean enabled = body.getBoolean("enabled");
        // order matters here, we want to clean before we disable
        if (clean && !enabled) {
            bus.send(Rule.RULE_ADDRESS + "?clean", rule);
        }

        rule.enabled = enabled;
        rule.persist();

        JsonObject meta =
                new JsonObject().put("type", MediaType.APPLICATION_JSON).put("status", "OK");
        JsonObject data = new JsonObject().put("result", JsonObject.mapFrom(rule));
        JsonObject response = new JsonObject().put("meta", meta).put("data", data);

        return Response.ok(response.encode()).type(MediaType.APPLICATION_JSON).build();
    }

    @Transactional
    @POST
    @RolesAllowed("write")
    @Consumes({MediaType.MULTIPART_FORM_DATA, MediaType.APPLICATION_FORM_URLENCODED})
    public Response create(
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
        MatchExpression expr = new MatchExpression(matchExpression);
        expr.persist();
        Rule rule = new Rule();
        rule.name = name;
        rule.description = description;
        rule.matchExpression = expr;
        rule.eventSpecifier = eventSpecifier;
        rule.archivalPeriodSeconds = archivalPeriodSeconds;
        rule.initialDelaySeconds = initialDelaySeconds;
        rule.preservedArchives = preservedArchives;
        rule.maxAgeSeconds = maxAgeSeconds;
        rule.maxSizeBytes = maxSizeBytes;
        rule.enabled = enabled;

        if (Rule.getByName(rule.name) != null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(
                            new JsonObject()
                                    .put("error", "Rule already exists with name: " + rule.name)
                                    .encode())
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        rule.persist();

        JsonObject meta =
                new JsonObject().put("type", MediaType.APPLICATION_JSON).put("status", "Created");
        JsonObject data = new JsonObject().put("result", rule.name);
        JsonObject response = new JsonObject().put("meta", meta).put("data", data);

        return Response.status(Response.Status.CREATED)
                .entity(response.encode())
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    @Transactional
    @DELETE
    @RolesAllowed("write")
    @Path("/{name}")
    public Response delete(@RestPath String name, @RestQuery boolean clean) {
        Rule rule = Rule.getByName(name);
        if (rule == null) {
            throw new NotFoundException("Rule with name " + name + " not found");
        }
        if (clean) {
            bus.send(Rule.RULE_ADDRESS + "?clean", rule);
        }
        rule.delete();

        JsonObject meta =
                new JsonObject().put("type", MediaType.APPLICATION_JSON).put("status", "OK");
        JsonObject data = new JsonObject().put("result", (JsonObject) null);
        JsonObject response = new JsonObject().put("meta", meta).put("data", data);

        return Response.ok(response.encode(), MediaType.APPLICATION_JSON).build();
    }
}
