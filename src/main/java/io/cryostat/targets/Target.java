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
package io.cryostat.targets;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import io.cryostat.discovery.DiscoveryNode;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.util.URIUtil;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SoftDelete;
import org.hibernate.type.SqlTypes;
import org.jboss.logging.Logger;

/**
 * Represents a remote target JVM which Cryostat has discovered. These may be discovered by built-in
 * discovery plugins, external discovery plugins (ex. Cryostat Agent instances), or directly by API
 * clients via the Custom Targets API.
 *
 * @see io.cryostat.discovery.Discovery
 */
@Entity
@EntityListeners(Target.Listener.class)
@Table(
        indexes = {
            @Index(columnList = "jvmId"),
            @Index(columnList = "connectUrl"),
        })
@NamedQueries({
    @NamedQuery(name = "Target.unconnected", query = "from Target where jvmId is null"),
})
@SoftDelete
public class Target extends PanacheEntity {

    public static final String TARGET_JVM_DISCOVERY = "TargetJvmDiscovery";

    @Column(unique = true, updatable = false)
    @NotNull
    public URI connectUrl;

    @NotBlank public String alias;

    /**
     * Hash ID identifying this JVM instance. This is a (mostly) unique hash computed from various
     * factors in the remote target's {@link java.lang.management.RuntimeMXBean}. Two different
     * remote JVM processes running as replicas of the same container will have all of the same hash
     * inputs, except for hopefully different start timestamps. This hash ID is used to identify
     * JVMs because multiple connectUrls may resolve to the same host:port and therefore the same
     * actual JVM process.
     *
     * <p>Cryostat attempts to connect to a target immediately to retrieve the RuntimeMXBean data
     * and compute the hash ID. If the connection attempt fails then Cryostat will retry for some
     * time before eventually giving up. Therefore, a null JVM ID indicates that Cryostat has not
     * yet been successful in connecting to the target JVM. The connection URL may be incorrect or
     * there may be external network factors preventing Cryostat from establishing a connection.
     */
    @Nullable public String jvmId;

    @JdbcTypeCode(SqlTypes.JSON)
    @NotNull
    public Map<String, String> labels = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @NotNull
    public Annotations annotations = new Annotations();

    @OneToMany(
            mappedBy = "target",
            cascade = {CascadeType.ALL},
            orphanRemoval = true)
    @NotNull
    @JsonIgnore
    public List<ActiveRecording> activeRecordings = new ArrayList<>();

    @OneToOne(
            cascade = {CascadeType.ALL},
            orphanRemoval = true)
    @JoinColumn(name = "discoveryNode")
    @NotNull
    @JsonIgnore
    public DiscoveryNode discoveryNode;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public boolean isAgent() {
        return AgentConnection.isAgentConnection(connectUrl);
    }

    @JsonIgnore
    public String targetId() {
        return this.connectUrl.toString();
    }

    @JsonIgnore
    public boolean isConnectable() {
        return id != null && id > 0 && StringUtils.isNotBlank(jvmId);
    }

    public static List<Target> getTargetsIncludingDeleted() {
        return Panache.getSession()
                // ignore soft deletion field
                .createNativeQuery("select * from Target", Target.class)
                .getResultList();
    }

    public static Target getTargetById(long targetId) {
        return Panache.getSession()
                // ignore soft deletion field
                .createNativeQuery("select * from Target where id = :id", Target.class)
                .setParameter("id", targetId)
                .getSingleResult();
    }

    public static Target getTargetByConnectUrl(URI connectUrl) {
        return Panache.getSession()
                // ignore soft deletion field
                .createNativeQuery(
                        "select * from Target where connectUrl = :connectUrl", Target.class)
                .setParameter("connectUrl", connectUrl.toString())
                .getSingleResult();
    }

    public static Optional<Target> getTargetByJvmId(String jvmId) {
        return Panache.getSession()
                // ignore soft deletion field
                .createNativeQuery("select * from Target where jvmId = :jvmId", Target.class)
                .setParameter("jvmId", jvmId)
                .uniqueResultOptional();
    }

    public static Optional<Target> getTarget(Predicate<Target> predicate) {
        return Target.<Target>findAll().stream().filter(predicate).findFirst();
    }

    public static List<Target> findByRealm(String realm) {
        // TODO reimplement this to work by grabbing the relevant Realm discovery node, then
        // traversing the tree to find Target leaves
        return Target.<Target>listAll().stream()
                .filter((t) -> realm.equals(t.annotations.cryostat().get("REALM")))
                .collect(Collectors.toList());
    }

    public ActiveRecording getRecordingById(long remoteId) {
        return activeRecordings.stream()
                .filter(rec -> rec.remoteId == remoteId)
                .findFirst()
                .orElse(null);
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public static record Annotations(Map<String, String> platform, Map<String, String> cryostat) {
        public Annotations {
            if (platform == null) {
                platform = new HashMap<>();
            }
            if (cryostat == null) {
                cryostat = new HashMap<>();
            }
        }

        public Annotations() {
            this(null, null);
        }

        public Map<String, String> merged() {
            Map<String, String> merged = new HashMap<>(cryostat);
            merged.putAll(platform);
            return Collections.unmodifiableMap(merged);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(alias, annotations, connectUrl, jvmId, labels);
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
        Target other = (Target) obj;
        return Objects.equals(alias, other.alias)
                && Objects.equals(annotations, other.annotations)
                && Objects.equals(connectUrl, other.connectUrl)
                && Objects.equals(jvmId, other.jvmId)
                && Objects.equals(labels, other.labels);
    }

    public static Compare compare(Collection<Target> src) {
        return new Compare(src);
    }

    public static class Compare {
        private Collection<Target> previous, current;

        public Compare(Collection<Target> previous) {
            this.previous = new HashSet<>(previous);
        }

        public Compare to(Collection<Target> current) {
            this.current = new HashSet<>(current);
            return this;
        }

        public Collection<Target> added() {
            return removeAllUpdatedRefs(addedOrUpdatedRefs(), updated(false));
        }

        public Collection<Target> removed() {
            return removeAllUpdatedRefs(removedOrUpdatedRefs(), updated(true));
        }

        public Collection<Target> updated(boolean keepOld) {
            Collection<Target> updated = new HashSet<>();
            intersection(removedOrUpdatedRefs(), addedOrUpdatedRefs(), keepOld)
                    .forEach((ref) -> updated.add(ref));
            return updated;
        }

        private Collection<Target> addedOrUpdatedRefs() {
            Collection<Target> added = new HashSet<>(current);
            added.removeAll(previous);
            return added;
        }

        private Collection<Target> removedOrUpdatedRefs() {
            Collection<Target> removed = new HashSet<>(previous);
            removed.removeAll(current);
            return removed;
        }

        private Collection<Target> removeAllUpdatedRefs(
                Collection<Target> src, Collection<Target> updated) {
            Collection<Target> tnSet = new HashSet<>(src);
            intersection(src, updated, true).stream().forEach((ref) -> tnSet.remove(ref));
            return tnSet;
        }

        private Collection<Target> intersection(
                Collection<Target> src, Collection<Target> other, boolean keepOld) {
            final Collection<Target> intersection = new HashSet<>();

            // Manual removal since Target also compares jvmId
            for (Target srcTarget : src) {
                for (Target otherTarget : other) {
                    if (Objects.equals(srcTarget.connectUrl, otherTarget.connectUrl)) {
                        intersection.add(keepOld ? srcTarget : otherTarget);
                    }
                }
            }

            return intersection;
        }
    }

    public enum EventKind {
        FOUND,
        MODIFIED,
        LOST,
        ;
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public record TargetDiscovery(EventKind kind, Target serviceRef, String jvmId) {
        public TargetDiscovery {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(serviceRef);
        }
    }

    @ApplicationScoped
    static class Listener {

        @Inject URIUtil uriUtil;
        @Inject Logger logger;
        @Inject EventBus bus;

        @PrePersist
        void prePersist(Target target) {
            if (StringUtils.isBlank(target.alias)) {
                throw new IllegalArgumentException();
            }
            try {
                if (!uriUtil.validateUri(target.connectUrl)) {
                    throw new IllegalArgumentException(
                            String.format(
                                    "Connect URL of \"%s\" is unacceptable with the"
                                            + " current URI range settings",
                                    target.connectUrl));
                }
            } catch (MalformedURLException me) {
                throw new IllegalArgumentException(me);
            }
            if (target.labels == null) {
                target.labels = new HashMap<>();
            }
            if (target.annotations == null) {
                target.annotations = new Annotations();
            }
            if (target.activeRecordings == null) {
                target.activeRecordings = new ArrayList<>();
            }
        }

        @PostPersist
        void postPersist(Target target) {
            notify(EventKind.FOUND, target);
        }

        @PostUpdate
        void postUpdate(Target target) {
            notify(EventKind.MODIFIED, target);
        }

        @PostRemove
        void postRemove(Target target) {
            notify(EventKind.LOST, target);
        }

        private void notify(EventKind eventKind, Target target) {
            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(
                            TARGET_JVM_DISCOVERY,
                            new TargetDiscoveryEvent(
                                    new TargetDiscovery(eventKind, target, target.jvmId))));
            bus.publish(TARGET_JVM_DISCOVERY, new TargetDiscovery(eventKind, target, target.jvmId));
        }

        public record TargetDiscoveryEvent(TargetDiscovery event) {
            public TargetDiscoveryEvent {
                Objects.requireNonNull(event);
            }

            public String jvmId() {
                return event.serviceRef().jvmId;
            }
        }
    }
}
