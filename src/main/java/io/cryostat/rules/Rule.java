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

import java.util.Objects;

import io.cryostat.expressions.MatchExpression;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import com.fasterxml.jackson.annotation.JsonIgnore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

@Entity
@EntityListeners(Rule.Listener.class)
@SuppressFBWarnings(
        value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD",
        justification =
                "rule.description is not used directly anywhere, but it is serialized and may be"
                        + " displayed by clients")
public class Rule extends PanacheEntity {
    public static final String RULE_ADDRESS = "io.cryostat.rules.Rule";

    @Column(unique = true, updatable = false)
    @NotBlank
    public String name;

    @NotNull public String description;

    @OneToOne(fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    @JoinColumn(name = "matchExpression")
    @NotNull
    public MatchExpression matchExpression;

    @Column(nullable = false)
    @NotBlank
    public String eventSpecifier;

    @PositiveOrZero(message = "archivalPeriodSeconds must be positive or zero")
    public int archivalPeriodSeconds;

    @PositiveOrZero(message = "initialDelaySeconds must be positive or zero")
    public int initialDelaySeconds;

    @PositiveOrZero(message = "archivalPeriodSeconds must be positive or zero")
    public int preservedArchives;

    @Min(message = "maxAgeSeconds must be greater than -1", value = -1)
    public int maxAgeSeconds;

    @Min(message = "maxAgeSeconds must be greater than -1", value = -1)
    public int maxSizeBytes;

    public boolean enabled;

    public String getName() {
        return this.name;
    }

    @JsonIgnore
    public String getRecordingName() {
        // FIXME do something other than simply prepending "auto_"
        return String.format("auto_%s", name);
    }

    @JsonIgnore
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
            notify(new RuleEvent(RuleEventCategory.CREATED, rule));
        }

        @PostUpdate
        public void postUpdate(Rule rule) {
            notify(new RuleEvent(RuleEventCategory.UPDATED, rule));
        }

        @PostRemove
        public void postRemove(Rule rule) {
            notify(new RuleEvent(RuleEventCategory.DELETED, rule));
        }

        private void notify(RuleEvent event) {
            bus.publish(RULE_ADDRESS, event);
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(event.category().category(), event.rule()));
        }
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public record RuleEvent(RuleEventCategory category, Rule rule) {
        public RuleEvent {
            Objects.requireNonNull(category);
            Objects.requireNonNull(rule);
        }
    }

    public enum RuleEventCategory {
        CREATED("RuleCreated"),
        UPDATED("RuleUpdated"),
        DELETED("RuleDeleted");

        private final String name;

        private RuleEventCategory(String name) {
            this.name = name;
        }

        public String category() {
            return name;
        }
    }
}
