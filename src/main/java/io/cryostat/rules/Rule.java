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

import io.cryostat.expressions.MatchExpression;
import io.cryostat.expressions.MatchExpressionEvaluator;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

@Entity
@EntityListeners(Rule.Listener.class)
public class Rule extends PanacheEntity {
    public static final String RULE_ADDRESS = "io.cryostat.rules.Rule";

    @Column(unique = true, nullable = false, updatable = false)
    public String name;

    public String description;

    @OneToOne(fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    @JoinColumn(name = "matchExpression")
    public MatchExpression matchExpression;

    @Column(nullable = false)
    @NotBlank(message = "eventSpecifier cannot be blank")
    public String eventSpecifier;

    @PositiveOrZero(message = "archivalPeriodSeconds must be positive or zero")
    public int archivalPeriodSeconds;

    @PositiveOrZero(message = "initialDelaySeconds must be positive or zero")
    public int initialDelaySeconds;

    @PositiveOrZero(message = "archivalPeriodSeconds must be positive or zero")
    public int preservedArchives;

    @Min(message = "maxAgeSeconds must be greater than 0 or -1", value = -1)
    public int maxAgeSeconds;

    @Min(message = "maxAgeSeconds must be greater than 0 or -1", value = -1)
    public int maxSizeBytes;

    public boolean enabled;

    public String getName() {
        return this.name;
    }

    public String getRecordingName() {
        // FIXME do something other than simply prepending "auto_"
        return String.format("auto_%s", name);
    }

    public boolean isArchiver() {
        return preservedArchives > 0 && archivalPeriodSeconds > 0;
    }

    public static Rule getByName(String name) {
        return find("name", name).firstResult();
    }

    @ApplicationScoped
    static class Listener {
        @Inject EventBus bus;

        @PostPersist
        public void postPersist(Rule rule) {
            // we cannot directly access EntityManager queries here
            bus.publish(RULE_ADDRESS, new RuleEvent(RuleEventCategory.CREATED, rule));
            notify(RuleEventCategory.CREATED, rule);
        }

        @PostUpdate
        public void postUpdate(Rule rule) {
            bus.publish(RULE_ADDRESS, new RuleEvent(RuleEventCategory.UPDATED, rule));
            notify(RuleEventCategory.UPDATED, rule);
        }

        @PostRemove
        public void postRemove(Rule rule) {
            bus.publish(RULE_ADDRESS, new RuleEvent(RuleEventCategory.DELETED, rule));
            notify(RuleEventCategory.DELETED, rule);
        }

        private void notify(RuleEventCategory category, Rule rule) {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(category.getCategory(), rule));
        }
    }

    public record RuleEvent(RuleEventCategory category, Rule rule) {}

    public enum RuleEventCategory {
        CREATED("RuleCreated"),
        UPDATED("RuleUpdated"),
        DELETED("RuleDeleted");

        private final String name;

        RuleEventCategory(String name) {
            this.name = name;
        }

        public String getCategory() {
            return name;
        }
    }
}
