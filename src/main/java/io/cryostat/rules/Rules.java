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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.cryostat.ConfigProperties;
import io.cryostat.DeclarativeConfiguration;
import io.cryostat.expressions.MatchExpression;
import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.util.EntityExistsException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.event.Observes;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;

@Path("/api/v4/rules")
public class Rules {

    @ConfigProperty(name = ConfigProperties.RULES_DIR)
    java.nio.file.Path dir;

    @Inject DeclarativeConfiguration declarativeConfiguration;
    @Inject EventBus bus;
    @Inject ObjectMapper mapper;
    @Inject Logger logger;

    @Transactional
    void onStart(@Observes StartupEvent evt) {
        try {
            declarativeConfiguration
                    .walk(dir)
                    .forEach(
                            path -> {
                                try (var is = new BufferedInputStream(Files.newInputStream(path))) {
                                    var declarativeRule = mapper.readValue(is, Rule.class);
                                    logger.tracev(
                                            "Processing declarative Automated Rule with name"
                                                    + " \"{0}\" at {1}",
                                            declarativeRule.name, path);
                                    var exists =
                                            Rule.find("name", declarativeRule.name).count() != 0;
                                    if (exists) {
                                        logger.tracev(
                                                "Rule with name \"{0}\" already exists in database."
                                                        + " Skipping declarative rule at {1}",
                                                declarativeRule.name, path);
                                        return;
                                    }
                                    declarativeRule.persist();
                                } catch (IOException ioe) {
                                    logger.warn(ioe);
                                } catch (Exception e) {
                                    logger.error(e);
                                }
                            });
        } catch (IOException e) {
            logger.error(e);
        }
    }

    @GET
    @RolesAllowed("read")
    @Operation(summary = "List all Automated Rules")
    public List<Rule> list() {
        return Rule.listAll();
    }

    @GET
    @RolesAllowed("read")
    @Path("/{name}")
    @Operation(summary = "Get an Automated Rule by name")
    public Rule get(@RestPath String name) {
        return Rule.getByName(name);
    }

    @Transactional
    @POST
    @RolesAllowed("write")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Create a new Automated Rule")
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
    @Operation(
            summary = "Update an Automated Rule",
            description =
                    """
                    Update Automated Rule parameters, such as whether the rule is currently active or not.
                    """)
    public Rule update(@RestPath String name, @RestQuery boolean clean, JsonObject body) {
        Rule rule = Rule.getByName(name);
        if (rule == null) {
            throw new NotFoundException("Rule with name " + name + " not found");
        }

        if (!Objects.equals(body.getString("name"), name)) {
            throw new BadRequestException("Rule name cannot be updated");
        }

        // order matters here, we want to clean before we disable
        if (clean) {
            bus.send(Rule.RULE_ADDRESS + "?clean", rule);
        }
        if (body.containsKey("enabled")) {
            rule.enabled = body.getBoolean("enabled");
        }
        if (body.containsKey("matchExpression")) {
            MatchExpression expr = new MatchExpression(body.getString("matchExpression"));
            expr.persist();
            rule.matchExpression = expr;
        }
        if (body.containsKey("description")) {
            rule.description = body.getString("description");
        }
        if (body.containsKey("eventSpecifier")) {
            rule.eventSpecifier = body.getString("eventSpecifier");
        }
        if (body.containsKey("archivalPeriodSeconds")) {
            rule.archivalPeriodSeconds = body.getInteger("archivalPeriodSeconds");
        }
        if (body.containsKey("initialDelaySeconds")) {
            rule.initialDelaySeconds = body.getInteger("initialDelaySeconds");
        }
        if (body.containsKey("preservedArchives")) {
            rule.preservedArchives = body.getInteger("preservedArchives");
        }
        if (body.containsKey("maxAgeSeconds")) {
            rule.maxAgeSeconds = body.getInteger("maxAgeSeconds");
        }
        if (body.containsKey("maxSizeBytes")) {
            rule.maxSizeBytes = body.getInteger("maxSizeBytes");
        }
        if (body.containsKey("metadata")) {
            rule.metadata = body.getJsonObject("metadata").mapTo(Metadata.class);
        }

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
            @RestForm("metadata") Optional<String> rawMetadata,
            @RestForm boolean enabled)
            throws JsonMappingException, JsonProcessingException {
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
        if (rawMetadata.isPresent()) {
            rule.metadata = mapper.readValue(rawMetadata.get(), Metadata.class);
        }
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
    @Operation(summary = "Delete an Automated Rule by name")
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
