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
package io.cryostat.graphql;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

import org.openjdk.jmc.common.unit.QuantityConversionException;

import io.cryostat.core.net.JFRConnection;
import io.cryostat.core.net.MBeanMetrics;
import io.cryostat.core.templates.Template;
import io.cryostat.core.templates.TemplateType;
import io.cryostat.discovery.DiscoveryNode;
import io.cryostat.graphql.RootNode.DiscoveryNodeFilter;
import io.cryostat.graphql.matchers.LabelSelectorMatcher;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.recordings.RecordingHelper.RecordingOptions;
import io.cryostat.recordings.RecordingHelper.RecordingReplace;
import io.cryostat.recordings.Recordings.ArchivedRecording;
import io.cryostat.recordings.Recordings.Metadata;
import io.cryostat.targets.Target;
import io.cryostat.targets.TargetConnectionManager;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLEnumValueDefinition;
import graphql.schema.GraphQLSchema;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.graphql.api.Context;
import io.smallrye.graphql.api.Nullable;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jdk.jfr.RecordingState;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Source;

@GraphQLApi
public class TargetNodes {

    @Inject RecordingHelper recordingHelper;
    @Inject TargetConnectionManager connectionManager;

    public GraphQLSchema.Builder registerRecordingStateEnum(
            @Observes GraphQLSchema.Builder builder) {
        return createEnumType(
                builder, RecordingState.class, "Running state of an active Flight Recording");
    }

    private static GraphQLSchema.Builder createEnumType(
            GraphQLSchema.Builder builder, Class<? extends Enum<?>> klazz, String description) {
        return builder.additionalType(
                GraphQLEnumType.newEnum()
                        .name(klazz.getSimpleName())
                        .description(description)
                        .values(
                                Arrays.asList(klazz.getEnumConstants()).stream()
                                        .map(
                                                s ->
                                                        new GraphQLEnumValueDefinition.Builder()
                                                                .name(s.name())
                                                                .value(s)
                                                                .description(s.name())
                                                                .build())
                                        .toList())
                        .build());
    }

    @Blocking
    @Query("targetNodes")
    @Description("Get the Target discovery nodes, i.e. the leaf nodes of the discovery tree")
    public List<DiscoveryNode> getTargetNodes(DiscoveryNodeFilter filter) {
        // TODO do this filtering at the database query level as much as possible. As is, this will
        // load the entire discovery tree out of the database, then perform the filtering at the
        // application level.
        return Target.<Target>findAll().stream()
                .filter(distinctWith(t -> t.jvmId))
                .map(t -> t.discoveryNode)
                .filter(n -> filter == null ? true : filter.test(n))
                .toList();
    }

    private static <T> Predicate<T> distinctWith(Function<? super T, ?> fn) {
        Set<Object> observed = ConcurrentHashMap.newKeySet();
        return t -> observed.add(fn.apply(t));
    }

    @Blocking
    @Description("Get the active and archived recordings belonging to this target")
    public Recordings recordings(@Source Target target, Context context) {
        var dfe = context.unwrap(DataFetchingEnvironment.class);
        var requestedFields =
                dfe.getSelectionSet().getFields().stream().map(field -> field.getName()).toList();

        var recordings = new Recordings();

        if (requestedFields.contains("active")) {
            recordings.active = new ActiveRecordings();
            recordings.active.data = target.activeRecordings;
            recordings.active.aggregate = new AggregateInfo();
            recordings.active.aggregate.count = recordings.active.data.size();
            recordings.active.aggregate.size = 0;
        }

        if (requestedFields.contains("archived")) {
            recordings.archived = new ArchivedRecordings();
            recordings.archived.data = recordingHelper.listArchivedRecordings(target);
            recordings.archived.aggregate = new AggregateInfo();
            recordings.archived.aggregate.count = recordings.archived.data.size();
            recordings.archived.aggregate.size =
                    recordings.archived.data.stream().mapToLong(ArchivedRecording::size).sum();
        }

        return recordings;
    }

    public ActiveRecordings active(@Source Recordings recordings, ActiveRecordingsFilter filter) {
        var out = new ActiveRecordings();
        out.data = new ArrayList<>();
        out.aggregate = new AggregateInfo();

        var in = recordings.active;
        if (in != null && in.data != null) {
            out.data =
                    in.data.stream().filter(r -> filter == null ? true : filter.test(r)).toList();
            out.aggregate.size = 0;
            out.aggregate.count = out.data.size();
        }

        return out;
    }

    public ArchivedRecordings archived(
            @Source Recordings recordings, ArchivedRecordingsFilter filter) {
        var out = new ArchivedRecordings();
        out.data = new ArrayList<>();
        out.aggregate = new AggregateInfo();

        var in = recordings.archived;
        if (in != null && in.data != null) {
            out.data =
                    in.data.stream().filter(r -> filter == null ? true : filter.test(r)).toList();
            out.aggregate.size = 0;
            out.aggregate.count = out.data.size();
        }

        return out;
    }

    @Blocking
    @Description("Get live MBean metrics snapshot from the specified Target")
    public Uni<MBeanMetrics> mbeanMetrics(@Source Target target) {
        return connectionManager.executeConnectedTaskUni(target, JFRConnection::getMBeanMetrics);
    }

    @Blocking
    @Transactional
    @Description("Start a new Flight Recording on the specified Target")
    public Uni<ActiveRecording> doStartRecording(
            @Source Target target, @NonNull RecordingSettings settings)
            throws QuantityConversionException {
        var fTarget = Target.<Target>findById(target.id);
        Template template =
                recordingHelper.getPreferredTemplate(
                        fTarget, settings.template, settings.templateType);
        return recordingHelper.startRecording(
                fTarget,
                RecordingReplace.STOPPED,
                template,
                settings.asOptions(),
                settings.metadata.labels());
    }

    @Blocking
    @Transactional
    @Description("Create a new Flight Recorder Snapshot on the specified Target")
    public Uni<ActiveRecording> doSnapshot(@Source Target target) {
        var fTarget = Target.<Target>findById(target.id);
        return recordingHelper.createSnapshot(fTarget);
    }

    @SuppressFBWarnings(value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD")
    public static class Recordings {
        public @NonNull ActiveRecordings active;
        public @NonNull ArchivedRecordings archived;
    }

    @SuppressFBWarnings(value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD")
    public static class ActiveRecordings {
        public @NonNull List<ActiveRecording> data;
        public @NonNull AggregateInfo aggregate;
    }

    @SuppressFBWarnings(value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD")
    public static class ArchivedRecordings {
        public @NonNull List<ArchivedRecording> data;
        public @NonNull AggregateInfo aggregate;
    }

    @SuppressFBWarnings(value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD")
    public static class AggregateInfo {
        public @NonNull @Description("The number of elements in this collection") long count;
        public @NonNull @Description(
                "The sum of sizes of elements in this collection, or 0 if not applicable") long
                size;
    }

    @SuppressFBWarnings(value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD")
    public static class ActiveRecordingsFilter implements Predicate<ActiveRecording> {
        public @Nullable String name;
        public @Nullable List<String> names;
        public @Nullable List<String> labels;
        public @Nullable RecordingState state;
        public @Nullable Boolean continuous;
        public @Nullable Boolean toDisk;
        public @Nullable Long durationMsGreaterThanEqual;
        public @Nullable Long durationMsLessThanEqual;
        public @Nullable Long startTimeMsAfterEqual;
        public @Nullable Long startTimeMsBeforeEqual;

        @Override
        public boolean test(ActiveRecording r) {
            Predicate<ActiveRecording> matchesName =
                    n -> name == null || Objects.equals(name, n.name);
            Predicate<ActiveRecording> matchesNames = n -> names == null || names.contains(n.name);
            Predicate<ActiveRecording> matchesLabels =
                    n -> {
                        if (labels == null) {
                            return true;
                        }
                        var allMatch = true;
                        for (var l : labels) {
                            allMatch &= LabelSelectorMatcher.parse(l).test(n.metadata.labels());
                        }
                        return allMatch;
                    };
            Predicate<ActiveRecording> matchesState = n -> state == null || n.state.equals(state);
            Predicate<ActiveRecording> matchesContinuous =
                    n -> continuous == null || continuous.equals(n.continuous);
            Predicate<ActiveRecording> matchesToDisk =
                    n -> toDisk == null || toDisk.equals(n.toDisk);
            Predicate<ActiveRecording> matchesDurationGte =
                    n ->
                            durationMsGreaterThanEqual == null
                                    || durationMsGreaterThanEqual >= n.duration;
            Predicate<ActiveRecording> matchesDurationLte =
                    n -> durationMsLessThanEqual == null || durationMsLessThanEqual <= n.duration;
            Predicate<ActiveRecording> matchesStartTimeAfter =
                    n -> startTimeMsAfterEqual == null || startTimeMsAfterEqual >= n.startTime;
            Predicate<ActiveRecording> matchesStartTimeBefore =
                    n -> startTimeMsBeforeEqual == null || startTimeMsBeforeEqual <= n.startTime;

            return matchesName
                    .and(matchesNames)
                    .and(matchesLabels)
                    .and(matchesState)
                    .and(matchesContinuous)
                    .and(matchesToDisk)
                    .and(matchesDurationGte)
                    .and(matchesDurationLte)
                    .and(matchesStartTimeBefore)
                    .and(matchesStartTimeAfter)
                    .test(r);
        }
    }

    @SuppressFBWarnings(value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD")
    public static class ArchivedRecordingsFilter implements Predicate<ArchivedRecording> {
        public @Nullable String name;
        public @Nullable List<String> names;
        public @Nullable List<String> labels;
        public @Nullable Long sizeBytesGreaterThanEqual;
        public @Nullable Long sizeBytesLessThanEqual;
        public @Nullable Long archivedTimeAfterEqual;
        public @Nullable Long archivedTimeBeforeEqual;

        @Override
        public boolean test(ArchivedRecording r) {
            Predicate<ArchivedRecording> matchesName =
                    n -> name == null || Objects.equals(name, n.name());
            Predicate<ArchivedRecording> matchesNames =
                    n -> names == null || names.contains(n.name());
            Predicate<ArchivedRecording> matchesLabels =
                    n -> {
                        if (labels == null) {
                            return true;
                        }
                        var allMatch = true;
                        for (var l : labels) {
                            allMatch &= LabelSelectorMatcher.parse(l).test(n.metadata().labels());
                        }
                        return allMatch;
                    };
            Predicate<ArchivedRecording> matchesSizeGte =
                    n -> sizeBytesGreaterThanEqual == null || sizeBytesGreaterThanEqual >= n.size();
            Predicate<ArchivedRecording> matchesSizeLte =
                    n -> sizeBytesLessThanEqual == null || sizeBytesLessThanEqual <= n.size();
            Predicate<ArchivedRecording> matchesArchivedTimeGte =
                    n ->
                            archivedTimeAfterEqual == null
                                    || archivedTimeAfterEqual >= n.archivedTime();
            Predicate<ArchivedRecording> matchesArchivedTimeLte =
                    n ->
                            archivedTimeBeforeEqual == null
                                    || archivedTimeBeforeEqual <= n.archivedTime();

            return matchesName
                    .and(matchesNames)
                    .and(matchesLabels)
                    .and(matchesSizeGte)
                    .and(matchesSizeLte)
                    .and(matchesArchivedTimeGte)
                    .and(matchesArchivedTimeLte)
                    .test(r);
        }
    }

    @SuppressFBWarnings(value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD")
    public static class RecordingSettings {
        public @NonNull String name;
        public @NonNull String template;
        public @NonNull TemplateType templateType;
        public @Nullable RecordingReplace replace;
        public @Nullable Boolean continuous;
        public @Nullable Boolean archiveOnStop;
        public @Nullable Boolean toDisk;
        public @Nullable Long duration;
        public @Nullable Long maxSize;
        public @Nullable Long maxAge;
        public @Nullable Metadata metadata;

        public RecordingOptions asOptions() {
            return new RecordingOptions(
                    name,
                    Optional.ofNullable(toDisk),
                    Optional.ofNullable(archiveOnStop),
                    Optional.ofNullable(duration),
                    Optional.ofNullable(maxSize),
                    Optional.ofNullable(maxAge));
        }
    }
}
