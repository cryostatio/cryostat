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
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import io.cryostat.diagnostic.Diagnostics.ThreadDump;
import io.cryostat.diagnostic.DiagnosticsHelper;
import io.cryostat.graphql.ActiveRecordings.MetadataLabels;
import io.cryostat.graphql.TargetNodes.ThreadDumpAggregateInfo;
import io.cryostat.graphql.TargetNodes.ThreadDumps;
import io.cryostat.graphql.matchers.LabelSelectorMatcher;
import io.cryostat.recordings.ActiveRecordings.Metadata;
import io.cryostat.targets.Target;

import io.smallrye.graphql.api.Nullable;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Source;

@GraphQLApi
public class ThreadDumpGraphQL {

    @Inject DiagnosticsHelper diagnosticsHelper;

    @Query("threadDumps")
    @Description("List archived thread dumps")
    public ThreadDumps listArchivedThreadDumps(ThreadDumpsFilter filter) {
        var r = new TargetNodes.ThreadDumps();
        r.data =
                diagnosticsHelper
                        .getThreadDumps(filter == null ? null : filter.sourceTarget)
                        .stream()
                        .filter(filter)
                        .toList();
        r.aggregate = ThreadDumpAggregateInfo.fromArchived(r.data);
        return r;
    }

    @NonNull
    @Description("Delete a thread dump")
    public ThreadDump doDelete(@Source ThreadDump dump) throws IOException {
        diagnosticsHelper.deleteThreadDump(
                Target.getTargetByJvmId(dump.jvmId()).get(), dump.threadDumpId());
        return dump;
    }

    @NonNull
    @Description("Update the metadata for a thread dump")
    public ThreadDump doPutMetadata(@Source ThreadDump threadDump, MetadataLabels metadataInput)
            throws IOException {
        diagnosticsHelper.updateThreadDumpMetadata(
                threadDump.jvmId(), threadDump.threadDumpId(), metadataInput.getLabels());

        String downloadUrl =
                diagnosticsHelper.downloadUrl(threadDump.jvmId(), threadDump.threadDumpId());

        return new ThreadDump(
                threadDump.jvmId(),
                downloadUrl,
                threadDump.threadDumpId(),
                threadDump.lastModified(),
                threadDump.size(),
                new Metadata(metadataInput.getLabels()));
    }

    public static class ThreadDumpsFilter implements Predicate<ThreadDump> {
        public @Nullable String name;
        public @Nullable List<String> names;
        public @Nullable String sourceTarget;
        public @Nullable List<String> labels;
        public @Nullable Long sizeBytesGreaterThanEqual;
        public @Nullable Long sizeBytesLessThanEqual;
        public @Nullable Long archivedTimeAfterEqual;
        public @Nullable Long archivedTimeBeforeEqual;

        @Override
        public boolean test(ThreadDump t) {
            Predicate<ThreadDump> matchesName =
                    n -> name == null || Objects.equals(name, n.threadDumpId());
            Predicate<ThreadDump> matchesNames =
                    n -> names == null || names.contains(n.threadDumpId());

            Predicate<ThreadDump> matchesSourceTarget =
                    n -> sourceTarget == null || Objects.equals(t.jvmId(), sourceTarget);
            Predicate<ThreadDump> matchesSizeGte =
                    n -> sizeBytesGreaterThanEqual == null || sizeBytesGreaterThanEqual >= n.size();
            Predicate<ThreadDump> matchesSizeLte =
                    n -> sizeBytesLessThanEqual == null || sizeBytesLessThanEqual <= n.size();
            Predicate<ThreadDump> matchesArchivedTimeGte =
                    n ->
                            archivedTimeAfterEqual == null
                                    || archivedTimeAfterEqual >= n.lastModified();
            Predicate<ThreadDump> matchesArchivedTimeLte =
                    n ->
                            archivedTimeBeforeEqual == null
                                    || archivedTimeBeforeEqual <= n.lastModified();
            Predicate<ThreadDump> matchesLabels =
                    n ->
                            labels == null
                                    || labels.stream()
                                            .allMatch(
                                                    label ->
                                                            LabelSelectorMatcher.parse(label)
                                                                    .test(n.metadata().labels()));

            return List.of(
                            matchesName,
                            matchesNames,
                            matchesSourceTarget,
                            matchesSizeGte,
                            matchesSizeLte,
                            matchesArchivedTimeGte,
                            matchesArchivedTimeLte,
                            matchesLabels)
                    .stream()
                    .reduce(x -> true, Predicate::and)
                    .test(t);
        }
    }
}
