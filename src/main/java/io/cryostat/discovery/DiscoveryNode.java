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
package io.cryostat.discovery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import io.cryostat.targets.Target;
import io.cryostat.targets.Target.TargetDiscovery;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonView;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PrePersist;
import jakarta.transaction.Transactional;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jboss.logging.Logger;

@Entity
@EntityListeners(DiscoveryNode.Listener.class)
public class DiscoveryNode extends PanacheEntity {

    public static String NODE_TYPE = "nodeType";
    public static String UNIVERSE = "Universe";
    public static String REALM = "Realm";
    public static String POD = "Pod";

    @Column(unique = false, nullable = false, updatable = false)
    @JsonView(Views.Flat.class)
    public String name;

    @Column(unique = false, nullable = false, updatable = false)
    @JsonView(Views.Flat.class)
    public String nodeType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    @JsonView(Views.Flat.class)
    public Map<String, String> labels = new HashMap<>();

    @OneToMany(fetch = FetchType.LAZY, orphanRemoval = true)
    @JsonView(Views.Nested.class)
    public List<DiscoveryNode> children = new ArrayList<>();

    @OneToOne(
            mappedBy = "discoveryNode",
            cascade = {CascadeType.ALL},
            fetch = FetchType.LAZY,
            orphanRemoval = true)
    @JsonInclude(value = Include.NON_NULL)
    @JsonView(Views.Flat.class)
    public Target target;

    @Override
    public int hashCode() {
        return Objects.hash(id, name, nodeType, labels, children, target);
    }

    public static DiscoveryNode getUniverse() {
        return DiscoveryNode.find(NODE_TYPE, UNIVERSE)
                .<DiscoveryNode>singleResultOptional()
                .orElseGet(() -> environment(UNIVERSE, UNIVERSE));
    }

    public static Optional<DiscoveryNode> getRealm(String name) {
        return getUniverse().children.stream().filter(n -> name.equals(n.name)).findFirst();
    }

    public static Optional<DiscoveryNode> getChild(
            DiscoveryNode node, Predicate<DiscoveryNode> predicate) {
        return node.children.stream().filter(predicate).findFirst();
    }

    public static DiscoveryNode environment(String name, String nodeType) {
        DiscoveryNode node = new DiscoveryNode();
        node.name = name;
        node.nodeType = nodeType;
        node.labels = new HashMap<>();
        node.children = new ArrayList<>();
        node.target = null;
        node.persist();
        return node;
    }

    public static DiscoveryNode target(Target target) {
        DiscoveryNode node = new DiscoveryNode();
        node.name = target.connectUrl.toString();
        node.nodeType = "JVM";
        node.labels = new HashMap<>(target.labels);
        node.children = null;
        node.target = target;
        node.persist();
        return node;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        DiscoveryNode other = (DiscoveryNode) obj;
        return Objects.equals(target, other.target)
                && Objects.equals(labels, other.labels)
                && Objects.equals(children, other.children);
    }

    @ApplicationScoped
    static class Listener {

        @Inject Logger logger;
        @Inject EventBus bus;

        @Transactional
        @Blocking
        @ConsumeEvent(Target.TARGET_JVM_DISCOVERY)
        void onMessage(TargetDiscovery event) {
            switch (event.kind()) {
                case LOST:
                    break;
                case FOUND:
                    break;
                default:
                    // no-op
                    break;
            }
        }

        @PrePersist
        void prePersist(DiscoveryNode node) {}

        @PostPersist
        void postPersist(DiscoveryNode node) {}

        @PostUpdate
        void postUpdate(DiscoveryNode node) {}

        @PostRemove
        void postRemove(DiscoveryNode node) {}
    }

    public static class Views {
        public static class Flat {}

        public static class Nested extends Flat {}
    }
}
