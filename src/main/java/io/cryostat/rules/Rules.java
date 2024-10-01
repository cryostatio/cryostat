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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;

@Path("/api/v4/rules")
public class Rules {

    @Inject EventBus bus;

    @GET
    @RolesAllowed("read")
    public List<Rule> list() {
        return Rule.listAll();
    }

    @GET
    @RolesAllowed("read")
    @Path("/{name}")
    public Rule get(@RestPath String name) {
        return Rule.getByName(name);
    }

    @Transactional
    @POST
    @RolesAllowed("write")
    @Consumes(MediaType.APPLICATION_JSON)
    public RestResponse<Rule> create(@Context UriInfo uriInfo, Rule rule) {
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

        return ResponseBuilder.<Rule>created(
                        uriInfo.getAbsolutePathBuilder().path(Long.toString(rule.id)).build())
                .entity(rule)
                .build();
    }

    @Transactional
    @PATCH
    @RolesAllowed("write")
    @Path("/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Rule update(@RestPath String name, @RestQuery boolean clean, JsonObject body) {
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

        return rule;
    }

    @Transactional
    @POST
    @RolesAllowed("write")
    @Consumes({MediaType.MULTIPART_FORM_DATA, MediaType.APPLICATION_FORM_URLENCODED})
    public RestResponse<Rule> create(
            @Context UriInfo uriInfo,
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
            return ResponseBuilder.<Rule>create(RestResponse.Status.CONFLICT).entity(rule).build();
        }

        rule.persist();

        return ResponseBuilder.<Rule>created(
                        uriInfo.getAbsolutePathBuilder().path(Long.toString(rule.id)).build())
                .entity(rule)
                .build();
    }

    @Transactional
    @DELETE
    @RolesAllowed("write")
    @Path("/{name}")
    public void delete(@RestPath String name, @RestQuery boolean clean) {
        Rule rule = Rule.getByName(name);
        if (rule == null) {
            throw new NotFoundException("Rule with name " + name + " not found");
        }
        if (clean) {
            bus.send(Rule.RULE_ADDRESS + "?clean", rule);
        }
        rule.delete();
    }
}
