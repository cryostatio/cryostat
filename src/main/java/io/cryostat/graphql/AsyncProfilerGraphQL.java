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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import io.cryostat.ConfigProperties;
import io.cryostat.asyncprofiler.AsyncProfilerHelper;
import io.cryostat.discovery.DiscoveryNode;
import io.cryostat.graphql.RootNode.DiscoveryNodeFilter;
import io.cryostat.graphql.TargetNodes.AsyncProfileAggregateInfo;
import io.cryostat.graphql.TargetNodes.AsyncProfiles;
import io.cryostat.targets.Target;
import io.cryostat.targets.AgentClient.AsyncProfile;
import io.cryostat.targets.AgentClient.StartProfileRequest;
import io.smallrye.graphql.api.Nullable;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Source;

@GraphQLApi
public class AsyncProfilerGraphQL {

    @Inject AsyncProfilerHelper helper;
    @ConfigProperty(name = ConfigProperties.CONNECTIONS_FAILED_TIMEOUT)
    Duration connectionFailedTimeout;

    @Query("asyncProfiler")
    @Description("List archived async profiles")
    public AsyncProfiles listArchivedAsyncProfiles(AsyncProfilerFilter filter) {
        var r = new TargetNodes.AsyncProfiles();
        r.data =
                helper.getProfiles(Target.getTargetByJvmId(filter == null ? null : filter.sourceTarget).get()).await()
                .atMost(connectionFailedTimeout).stream()
                        .filter(v -> filter == null ? true : filter.test(v))
                        .toList();
        r.aggregate = AsyncProfileAggregateInfo.fromArchived(r.data);
        return r;
    }

    @Transactional
    @Mutation
    @Description("Trigger an async profiler request on all Targets under the subtrees of the discovery nodes"
                    + " matching the given filter")
    public List<String> createAsyncProfile(@NonNull DiscoveryNodeFilter nodes, @NonNull StartProfileRequest req) {
        var list =
                DiscoveryNode.<DiscoveryNode>listAll().stream()
                        .filter(n -> nodes == null ? true : nodes.test(n))
                        .flatMap(
                                node ->
                                        RootNode.recurseChildren(node, n -> n.target != null)
                                                .stream()
                                                .map(n -> n.target))
                        .toList();
        Duration duration = Duration.ofSeconds(req.duration());
        var profileIds = new ArrayList<String>();
        for (var t : list) {
                var p = helper.createAsyncProfile(t, req.events(), duration);
                profileIds.add(p.await().atMost(connectionFailedTimeout));
        }
        return profileIds;
    }

    @Transactional
    @Mutation
    @Description(
            "Delete an existing Async Profile matching the given filter, on all Targets under"
                    + " the subtrees of the discovery nodes matching the given filter")
    public List<AsyncProfile> deleteAsyncProfiles(@NonNull DiscoveryNodeFilter nodes, @Nullable AsyncProfilerFilter filter) {
        var list =
                DiscoveryNode.<DiscoveryNode>listAll().stream()
                        .filter(n -> nodes == null ? true : nodes.test(n))
                        .flatMap(
                                node ->
                                        RootNode.recurseChildren(node, n -> n.target != null)
                                                .stream()
                                                .map(n -> n.target))
                        .flatMap(
                                t ->
                                        helper.getProfiles(t).await()
                                        .atMost(connectionFailedTimeout).stream()
                                                .filter(
                                                        h ->
                                                                filter == null
                                                                        || filter.test(h)))
                        .toList();
        for (var p : list) {
            helper.deleteProfile(Target.getTargetByJvmId(p.jvmId()).get(), p.id());
        }
        return list;
    }

    @NonNull
    @Description("Delete an Async Profile")
    public AsyncProfile doDelete(@Source AsyncProfile profile) throws IOException {
        helper.deleteProfile(Target.getTargetByJvmId(profile.jvmId()).get(), profile.id());
        return profile;
    }

    public static class AsyncProfilerFilter implements Predicate<AsyncProfile> {
        public @Nullable String name;
        public @Nullable List<String> names;
        public @Nullable String sourceTarget;
        public @Nullable Long sizeBytesGreaterThanEqual;
        public @Nullable Long sizeBytesLessThanEqual;

        @Override
        public boolean test(AsyncProfile t) {
            Predicate<AsyncProfile> matchesName =
                    n -> name == null || Objects.equals(name, n.id());
            Predicate<AsyncProfile> matchesNames = n -> names == null || names.contains(n.id());

            Predicate<AsyncProfile> matchesSourceTarget =
                    n -> sourceTarget == null || Objects.equals(t.jvmId(), sourceTarget);
            Predicate<AsyncProfile> matchesSizeGte =
                    n -> sizeBytesGreaterThanEqual == null || sizeBytesGreaterThanEqual >= n.size();
            Predicate<AsyncProfile> matchesSizeLte =
                    n -> sizeBytesLessThanEqual == null || sizeBytesLessThanEqual <= n.size();

            return List.of(
                            matchesName,
                            matchesNames,
                            matchesSourceTarget,
                            matchesSizeGte,
                            matchesSizeLte)
                    .stream()
                    .reduce(x -> true, Predicate::and)
                    .test(t);
        }
    }
}

