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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import io.cryostat.graphql.ActiveRecordings.MetadataLabels;
import io.cryostat.graphql.TargetNodes.RecordingAggregateInfo;
import io.cryostat.graphql.TargetNodes.Recordings;
import io.cryostat.graphql.matchers.LabelSelectorMatcher;
import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.recordings.ArchivedRecordings.ArchivedRecording;
import io.cryostat.recordings.RecordingHelper;

import io.smallrye.graphql.api.Nullable;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Source;

@GraphQLApi
public class ArchivedRecordings {

    @Inject RecordingHelper recordingHelper;

    @Query("archivedRecordings")
    @Description("List archived recordings")
    public TargetNodes.ArchivedRecordings listArchivedRecordings(ArchivedRecordingsFilter filter) {
        var r = new TargetNodes.ArchivedRecordings();
        r.data =
                recordingHelper
                        .listArchivedRecordings(filter == null ? null : filter.sourceTarget)
                        .stream()
                        .filter(filter)
                        .toList();
        r.aggregate = RecordingAggregateInfo.fromArchived(r.data);
        return r;
    }

    @Description("List and optionally filter archived recordings belonging to a Target")
    public TargetNodes.ArchivedRecordings archived(
            @Source Recordings recordings, ArchivedRecordingsFilter filter) {
        var out = new TargetNodes.ArchivedRecordings();
        out.data = new ArrayList<>();
        out.aggregate = RecordingAggregateInfo.empty();

        var in = recordings.archived;
        if (in != null && in.data != null) {
            out.data =
                    in.data.stream().filter(r -> filter == null ? true : filter.test(r)).toList();
            out.aggregate = RecordingAggregateInfo.fromArchived(out.data);
        }

        return out;
    }

    @NonNull
    @Description("Delete an archived recording")
    public ArchivedRecording doDelete(@Source ArchivedRecording recording) throws IOException {
        recordingHelper.deleteArchivedRecording(recording.jvmId(), recording.name());
        return recording;
    }

    @NonNull
    @Description("Update the metadata associated with an archived recording")
    public ArchivedRecording doPutMetadata(
            @Source ArchivedRecording recording, MetadataLabels metadataInput) throws IOException {
        recordingHelper.updateArchivedRecordingMetadata(
                recording.jvmId(), recording.name(), metadataInput.getLabels());

        String downloadUrl = recordingHelper.downloadUrl(recording.jvmId(), recording.name());
        String reportUrl = recordingHelper.reportUrl(recording.jvmId(), recording.name());

        return new ArchivedRecording(
                recording.jvmId(),
                recording.name(),
                downloadUrl,
                reportUrl,
                new Metadata(metadataInput.getLabels()),
                recording.size(),
                recording.archivedTime());
    }

    public static class ArchivedRecordingsFilter implements Predicate<ArchivedRecording> {
        public @Nullable String name;
        public @Nullable List<String> names;
        public @Nullable String sourceTarget;
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
            Predicate<ArchivedRecording> matchesSourceTarget =
                    n ->
                            sourceTarget == null
                                    || Objects.equals(
                                            r.metadata().labels().get("connectUrl"), sourceTarget);
            Predicate<ArchivedRecording> matchesLabels =
                    n ->
                            labels == null
                                    || labels.stream()
                                            .allMatch(
                                                    label ->
                                                            LabelSelectorMatcher.parse(label)
                                                                    .test(n.metadata().labels()));
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

            return List.of(
                            matchesName,
                            matchesNames,
                            matchesSourceTarget,
                            matchesLabels,
                            matchesSizeGte,
                            matchesSizeLte,
                            matchesArchivedTimeGte,
                            matchesArchivedTimeLte)
                    .stream()
                    .reduce(x -> true, Predicate::and)
                    .test(r);
        }
    }
}
