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

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

import io.cryostat.discovery.DiscoveryNode;
import io.cryostat.graphql.RootNode.DiscoveryNodeFilterInput;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.recordings.Recordings.ArchivedRecording;
import io.cryostat.targets.Target;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLEnumValueDefinition;
import graphql.schema.GraphQLSchema;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Source;

@GraphQLApi
public class TargetNodes {

    @Inject RecordingHelper recordingHelper;

    public GraphQLSchema.Builder registerRecordingStateEnum(
            @Observes GraphQLSchema.Builder builder) {
        GraphQLEnumType recordingState =
                GraphQLEnumType.newEnum()
                        .name("RecordingState")
                        .description("Running state of an active Flight Recording")
                        .values(
                                Arrays.asList(jdk.jfr.RecordingState.values()).stream()
                                        .map(
                                                s ->
                                                        new GraphQLEnumValueDefinition.Builder()
                                                                .name(s.name())
                                                                .value(s)
                                                                .description(s.name())
                                                                .build())
                                        .toList())
                        .build();
        return builder.additionalType(recordingState);
    }

    @Query("targetNodes")
    @Description("Get the Target discovery nodes, i.e. the leaf nodes of the discovery tree")
    public List<DiscoveryNode> getTargetNodes(DiscoveryNodeFilterInput filter) {
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

    @Description("Get the active and archived recordings belonging to this target")
    public Recordings recordings(@Source Target target) {
        var recordings = new Recordings();
        recordings.active = new ActiveRecordings();
        recordings.active.data = target.activeRecordings;
        recordings.active.aggregate = new AggregateInfo();
        recordings.active.aggregate.count = recordings.active.data.size();
        recordings.active.aggregate.size = 0;

        recordings.archived = new ArchivedRecordings();
        recordings.archived.data = recordingHelper.listArchivedRecordings(target);
        recordings.archived.aggregate = new AggregateInfo();
        recordings.archived.aggregate.count = recordings.archived.data.size();
        recordings.archived.aggregate.size =
                recordings.archived.data.stream().mapToLong(ArchivedRecording::size).sum();

        return recordings;
    }

    public static class Recordings {
        public @NonNull ActiveRecordings active;
        public @NonNull ArchivedRecordings archived;
    }

    public static class ActiveRecordings {
        public @NonNull List<ActiveRecording> data;
        public @NonNull AggregateInfo aggregate;
    }

    public static class ArchivedRecordings {
        public @NonNull List<ArchivedRecording> data;
        public @NonNull AggregateInfo aggregate;
    }

    public static class AggregateInfo {
        public @NonNull long count;
        public @NonNull long size;
    }
}
