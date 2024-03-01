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
import java.util.function.Predicate;

import io.cryostat.graphql.TargetNodes.AggregateInfo;
import io.cryostat.graphql.TargetNodes.Recordings;
import io.cryostat.graphql.matchers.LabelSelectorMatcher;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.recordings.Recordings.ArchivedRecording;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.graphql.api.Nullable;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Source;

@GraphQLApi
public class ArchivedRecordings {

    @Inject RecordingHelper recordingHelper;

    @Blocking
    @Query("archivedRecordings")
    public TargetNodes.ArchivedRecordings listArchivedRecordings(ArchivedRecordingsFilter filter) {
        var r = new TargetNodes.ArchivedRecordings();
        r.data = recordingHelper.listArchivedRecordings();
        r.aggregate = new AggregateInfo();
        r.aggregate.size = r.data.stream().mapToLong(ArchivedRecording::size).sum();
        r.aggregate.count = r.data.size();
        return r;
    }

    public TargetNodes.ArchivedRecordings archived(
            @Source Recordings recordings, ArchivedRecordingsFilter filter) {
        var out = new TargetNodes.ArchivedRecordings();
        out.data = new ArrayList<>();
        out.aggregate = new AggregateInfo();

        var in = recordings.archived;
        if (in != null && in.data != null) {
            out.data =
                    in.data.stream().filter(r -> filter == null ? true : filter.test(r)).toList();
            out.aggregate.size = in.data.stream().mapToLong(ArchivedRecording::size).sum();
            out.aggregate.count = out.data.size();
        }

        return out;
    }

    @SuppressFBWarnings(value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD")
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

            return matchesName
                    .and(matchesNames)
                    .and(matchesSourceTarget)
                    .and(matchesLabels)
                    .and(matchesSizeGte)
                    .and(matchesSizeLte)
                    .and(matchesArchivedTimeGte)
                    .and(matchesArchivedTimeLte)
                    .test(r);
        }
    }
}
