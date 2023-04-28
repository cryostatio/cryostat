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
package io.cryostat.discovery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
