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

    public static Rule getByName(String name) {
        return find("name", name).singleResult();
    }

    @ApplicationScoped
    static class Listener {

        @Inject EventBus bus;

        @PostPersist
        public void postPersist(Rule rule) {
            notify("RuleCreated", rule);
        }

        @PostUpdate
        public void postUpdate(Rule rule) {
            notify("RuleUpdated", rule);
        }

        @PostRemove
        public void postRemove(Rule rule) {
            notify("RuleDeleted", rule);
        }

        private void notify(String category, Rule rule) {
            bus.publish(MessagingServer.class.getName(), new Notification(category, rule));
        }
    }
}
