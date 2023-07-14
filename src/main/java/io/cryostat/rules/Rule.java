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

import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.vertx.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.jboss.logging.Logger;

// TODO add quarkus-quartz dependency to store Rules and make them into persistent recurring tasks
@Entity
@EntityListeners(Rule.Listener.class)
public class Rule extends PanacheEntity {

    @Column(unique = true, nullable = false, updatable = false)
    public String name;

    public String description;

    @Column(nullable = false)
    public String matchExpression;

    @Column(nullable = false)
    public String eventSpecifier;

    public int archivalPeriodSeconds;
    public int initialDelaySeconds;
    public int preservedArchives;
    public int maxAgeSeconds;
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
        return preservedArchives > 0;
    }

    public static Rule getByName(String name) {
        return find("name", name).singleResult();
    }

    @ApplicationScoped
    static class Listener {
        @Inject EventBus bus;
        @Inject Logger logger;
        @Inject MatchExpressionEvaluator evaluator;

        @PostPersist
        public void postPersist(Rule rule) {
            // we cannot directly access EntityManager queries here
            bus.publish(RuleService.RULE_ADDRESS, new RuleEvent(RuleEventCategory.CREATED, rule));
            notify(RuleEventCategory.CREATED, rule);
        }

        @PostUpdate
        public void postUpdate(Rule rule) {
            bus.publish(RuleService.RULE_ADDRESS, new RuleEvent(RuleEventCategory.UPDATED, rule));
            notify(RuleEventCategory.UPDATED, rule);
        }

        @PostRemove
        public void postRemove(Rule rule) {
            bus.publish(RuleService.RULE_ADDRESS, new RuleEvent(RuleEventCategory.DELETED, rule));
            notify(RuleEventCategory.DELETED, rule);
        }

        private void notify(RuleEventCategory category, Rule rule) {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(category.getCategory(), rule));
        }
    }

    static class RuleEvent {
        public final RuleEventCategory category;
        public final Rule rule;

        RuleEvent(RuleEventCategory category, Rule rule) {
            this.category = category;
            this.rule = rule;
        }

        public RuleEventCategory getCategory() {
            return category;
        }

        public Rule getRule() {
            return rule;
        }
    }

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
