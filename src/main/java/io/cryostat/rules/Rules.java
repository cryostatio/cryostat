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

import io.cryostat.V2Response;

import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;

@Path("/api/v2/rules")
public class Rules {

    @Inject EventBus bus;

    @GET
    @RolesAllowed("read")
    public V2Response list() {
        return V2Response.json(Rule.listAll());
    }

    @GET
    @RolesAllowed("read")
    @Path("/{name}")
    public V2Response get(@RestPath String name) {
        return V2Response.json(Rule.getByName(name));
    }

    @Transactional
    @POST
    @RolesAllowed("write")
    @Consumes("application/json")
    public V2Response create(Rule rule) {
        // TODO validate the incoming rule
        rule.persist();
        return V2Response.json(rule);
    }

    @Transactional
    @PATCH
    @RolesAllowed("write")
    @Path("/{name}")
    @Consumes("application/json")
    public V2Response update(@RestPath String name, @RestQuery boolean clean, JsonObject body) {
        Rule rule = Rule.getByName(name);
        rule.enabled = body.getBoolean("enabled");
        rule.persist();

        return V2Response.json(rule);
    }

    @Transactional
    @POST
    @RolesAllowed("write")
    public V2Response create(
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
    public V2Response delete(@RestPath String name, @RestQuery boolean clean) {
        Rule rule = Rule.getByName(name);
        rule.delete();
        return V2Response.json(rule);
    }
}
