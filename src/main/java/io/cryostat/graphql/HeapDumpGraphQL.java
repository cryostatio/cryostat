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

import io.cryostat.diagnostic.Diagnostics.HeapDump;
import io.cryostat.diagnostic.DiagnosticsHelper;
import io.cryostat.graphql.ActiveRecordings.MetadataLabels;
import io.cryostat.graphql.TargetNodes.HeapDumpAggregateInfo;
import io.cryostat.graphql.TargetNodes.HeapDumps;
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
public class HeapDumpGraphQL {

    @Inject DiagnosticsHelper diagnosticsHelper;

    @Query("heapDumps")
    @Description("List archived heap dumps")
    public HeapDumps listArchivedHeapDumps(HeapDumpsFilter filter) {
        var r = new TargetNodes.HeapDumps();
        r.data =
                diagnosticsHelper.getHeapDumps(filter == null ? null : filter.sourceTarget).stream()
                        .filter(filter)
                        .toList();
        r.aggregate = HeapDumpAggregateInfo.fromArchived(r.data);
        return r;
    }

    @NonNull
    @Description("Delete a heap dump")
    public HeapDump doDelete(@Source HeapDump dump) throws IOException {
        diagnosticsHelper.deleteHeapDump(
                dump.heapDumpId(), Target.getTargetByJvmId(dump.jvmId()).get());
        return dump;
    }

    @NonNull
    @Description("Update the metadata for a heap dump")
    public HeapDump doPutMetadata(@Source HeapDump heapDump, MetadataLabels metadataInput)
            throws IOException {
        diagnosticsHelper.updateHeapDumpMetadata(
                heapDump.jvmId(), heapDump.heapDumpId(), metadataInput.getLabels());

        String downloadUrl = diagnosticsHelper.downloadUrl(heapDump.jvmId(), heapDump.heapDumpId());

        return new HeapDump(
                heapDump.jvmId(),
                downloadUrl,
                heapDump.heapDumpId(),
                heapDump.lastModified(),
                heapDump.size(),
                new Metadata(metadataInput.getLabels()));
    }

    public static class HeapDumpsFilter implements Predicate<HeapDump> {
        public @Nullable String name;
        public @Nullable List<String> names;
        public @Nullable String sourceTarget;
        public @Nullable List<String> labels;
        public @Nullable Long sizeBytesGreaterThanEqual;
        public @Nullable Long sizeBytesLessThanEqual;
        public @Nullable Long archivedTimeAfterEqual;
        public @Nullable Long archivedTimeBeforeEqual;

        @Override
        public boolean test(HeapDump t) {
            Predicate<HeapDump> matchesName =
                    n -> name == null || Objects.equals(name, n.heapDumpId());
            Predicate<HeapDump> matchesNames = n -> names == null || names.contains(n.heapDumpId());

            Predicate<HeapDump> matchesSourceTarget =
                    n -> sourceTarget == null || Objects.equals(t.jvmId(), sourceTarget);
            Predicate<HeapDump> matchesSizeGte =
                    n -> sizeBytesGreaterThanEqual == null || sizeBytesGreaterThanEqual >= n.size();
            Predicate<HeapDump> matchesSizeLte =
                    n -> sizeBytesLessThanEqual == null || sizeBytesLessThanEqual <= n.size();
            Predicate<HeapDump> matchesArchivedTimeGte =
                    n ->
                            archivedTimeAfterEqual == null
                                    || archivedTimeAfterEqual >= n.lastModified();
            Predicate<HeapDump> matchesArchivedTimeLte =
                    n ->
                            archivedTimeBeforeEqual == null
                                    || archivedTimeBeforeEqual <= n.lastModified();
            Predicate<HeapDump> matchesLabels =
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
