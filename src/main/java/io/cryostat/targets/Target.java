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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import io.cryostat.discovery.DiscoveryNode;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PrePersist;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jboss.logging.Logger;

@Entity
@EntityListeners(Target.Listener.class)
@NamedQueries({@NamedQuery(name = "Target.unconnected", query = "from Target where jvmId is null")})
public class Target extends PanacheEntity {

    public static final String TARGET_JVM_DISCOVERY = "TargetJvmDiscovery";

    @Column(unique = true, updatable = false)
    @NotNull
    public URI connectUrl;

    @NotBlank public String alias;

    public String jvmId;

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

    public static Target getTargetById(long targetId) {
        return Target.find("id", targetId).singleResult();
    }

    public static Target getTargetByConnectUrl(URI connectUrl) {
        return find("connectUrl", connectUrl).singleResult();
    }

    public static Optional<Target> getTargetByJvmId(String jvmId) {
        return find("jvmId", jvmId).firstResultOptional();
    }

    public static Optional<Target> getTarget(Predicate<Target> predicate) {
        List<Target> targets = listAll();
        return targets.stream().filter(predicate).findFirst();
    }

    public static boolean deleteByConnectUrl(URI connectUrl) {
        return delete("connectUrl", connectUrl) > 0;
    }

    public static List<Target> findByRealm(String realm) {
        List<Target> targets = findAll().list();

        return targets.stream()
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

    @SuppressFBWarnings(value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"})
    public record TargetDiscovery(EventKind kind, Target serviceRef, String jvmId) {
        public TargetDiscovery {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(serviceRef);
        }
    }

    @ApplicationScoped
    static class Listener {

        @Inject Logger logger;
        @Inject EventBus bus;
        ScheduledExecutorService scheduler =
                Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors());

        @PrePersist
        void prePersist(Target target) {
            if (StringUtils.isBlank(target.alias)) {
                throw new IllegalArgumentException();
            }
            var encodedAlias = URLEncoder.encode(target.alias, StandardCharsets.UTF_8);
            if (!Objects.equals(encodedAlias, target.alias)) {
                target.alias = encodedAlias;
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
            scheduler.schedule(() -> notify(EventKind.FOUND, target), 1, TimeUnit.SECONDS);
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
