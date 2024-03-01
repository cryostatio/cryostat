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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import org.openjdk.jmc.common.unit.QuantityConversionException;

import io.cryostat.core.templates.Template;
import io.cryostat.core.templates.TemplateType;
import io.cryostat.graphql.TargetNodes.AggregateInfo;
import io.cryostat.graphql.TargetNodes.Recordings;
import io.cryostat.graphql.matchers.LabelSelectorMatcher;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.recordings.RecordingHelper.RecordingOptions;
import io.cryostat.recordings.RecordingHelper.RecordingReplace;
import io.cryostat.recordings.Recordings.Metadata;
import io.cryostat.targets.Target;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.graphql.api.Nullable;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jdk.jfr.RecordingState;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Source;

@GraphQLApi
public class ActiveRecordings {

    @Inject RecordingHelper recordingHelper;

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

    public TargetNodes.ActiveRecordings active(
            @Source Recordings recordings, ActiveRecordingsFilter filter) {
        var out = new TargetNodes.ActiveRecordings();
        out.data = new ArrayList<>();
        out.aggregate = AggregateInfo.empty();

        var in = recordings.active;
        if (in != null && in.data != null) {
            out.data =
                    in.data.stream().filter(r -> filter == null ? true : filter.test(r)).toList();
            out.aggregate = AggregateInfo.fromActive(out.data);
        }

        return out;
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
                    n ->
                            labels == null
                                    || labels.stream()
                                            .allMatch(
                                                    label ->
                                                            LabelSelectorMatcher.parse(label)
                                                                    .test(n.metadata.labels()));
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
}
