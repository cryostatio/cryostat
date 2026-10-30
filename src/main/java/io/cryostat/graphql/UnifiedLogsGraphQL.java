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

import io.cryostat.diagnostic.Diagnostics.UnifiedLog;
import io.cryostat.diagnostic.DiagnosticsHelper;
import io.cryostat.graphql.TargetNodes.UnifiedLogAggregateInfo;
import io.cryostat.graphql.TargetNodes.UnifiedLogs;
import io.cryostat.graphql.matchers.LabelSelectorMatcher;
import io.cryostat.security.rbac.graphql.RequiresPermission;

import io.smallrye.graphql.api.Nullable;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Source;
import org.jboss.logging.Logger;

@GraphQLApi
public class UnifiedLogsGraphQL {

    @Inject DiagnosticsHelper diagnosticsHelper;
    @Inject Logger log;

    @Query("unifiedLogs")
    @RequiresPermission("unifiedlogs:read")
    @Description("List archived unified logs")
    public UnifiedLogs listArchivedUnifiedLogs(UnifiedLogsFilter filter) {
        var r = new UnifiedLogs();
        r.data =
                diagnosticsHelper
                        .getUnifiedLogs(filter == null ? null : filter.sourceTarget)
                        .stream()
                        .filter(v -> filter == null ? true : filter.test(v))
                        .toList();
        r.aggregate = UnifiedLogAggregateInfo.fromArchived(r.data);
        return r;
    }

    @NonNull
    @RequiresPermission("unifiedlogs:delete")
    @Description("Delete a unified log")
    public UnifiedLog doDelete(@Source UnifiedLog log) throws IOException {
        diagnosticsHelper.deleteUnifiedLog(log.jvmId(), log.unifiedLogId());
        return log;
    }

    public static class UnifiedLogsFilter implements Predicate<UnifiedLog> {
        public @Nullable String name;
        public @Nullable List<String> names;
        public @Nullable String sourceTarget;
        public @Nullable List<String> labels;
        public @Nullable Long sizeBytesGreaterThanEqual;
        public @Nullable Long sizeBytesLessThanEqual;
        public @Nullable Long archivedTimeAfterEqual;
        public @Nullable Long archivedTimeBeforeEqual;

        @Override
        public boolean test(UnifiedLog ul) {
            Predicate<UnifiedLog> matchesName =
                    n -> name == null || Objects.equals(name, ul.unifiedLogId());
            Predicate<UnifiedLog> matchesNames =
                    n -> names == null || names.contains(ul.unifiedLogId());

            Predicate<UnifiedLog> matchesSourceTarget =
                    n -> sourceTarget == null || Objects.equals(ul.jvmId(), sourceTarget);
            Predicate<UnifiedLog> matchesSizeGte =
                    n ->
                            sizeBytesGreaterThanEqual == null
                                    || ul.size() >= sizeBytesGreaterThanEqual;
            Predicate<UnifiedLog> matchesSizeLte =
                    n -> sizeBytesLessThanEqual == null || ul.size() <= sizeBytesLessThanEqual;
            Predicate<UnifiedLog> matchesArchivedTimeGte =
                    n ->
                            archivedTimeAfterEqual == null
                                    || ul.lastModified() >= archivedTimeAfterEqual;
            Predicate<UnifiedLog> matchesArchivedTimeLte =
                    n ->
                            archivedTimeBeforeEqual == null
                                    || ul.lastModified() <= archivedTimeBeforeEqual;
            Predicate<UnifiedLog> matchesLabels =
                    n ->
                            labels == null
                                    || labels.stream()
                                            .allMatch(
                                                    label ->
                                                            LabelSelectorMatcher.parse(label)
                                                                    .test(ul.metadata().labels()));

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
                    .test(ul);
        }
    }
}
