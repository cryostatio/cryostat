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

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import io.cryostat.discovery.DiscoveryNode;

import io.smallrye.graphql.api.Nullable;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Source;

@GraphQLApi
public class RootNode {

    @Query("rootNode")
    @Description("Get the root target discovery node")
    public DiscoveryNode getRootNode() {
        return DiscoveryNode.getUniverse();
    }

    public List<DiscoveryNode> descendantTargets(
            @Source DiscoveryNode discoveryNode, DescendantTargetsFilterInput filter) {
        // TODO do this filtering at the database query level as much as possible. As is, this will
        // load the entire discovery tree out of the database, then perform the filtering at the
        // application level.
        return recurseChildren(discoveryNode, n -> n.target != null).stream()
                .filter(n -> filter == null ? true : filter.test(n))
                .toList();
    }

    private Set<DiscoveryNode> recurseChildren(
            DiscoveryNode node, Predicate<DiscoveryNode> predicate) {
        Set<DiscoveryNode> result = new HashSet<>();
        if (predicate.test(node)) {
            result.add(node);
        }
        if (node.children != null) {
            node.children.forEach(c -> result.addAll(recurseChildren(c, predicate)));
        }
        return result;
    }

    public static class DescendantTargetsFilterInput implements Predicate<DiscoveryNode> {
        public @Nullable Long id;
        public @Nullable String name;
        public @Nullable List<String> names;

        // TODO reimplement label and annotations matching syntax
        // public List<String> labels;
        // public List<String> annotations;

        @Override
        public boolean test(DiscoveryNode t) {
            Predicate<DiscoveryNode> matchesId = n -> id == null || id == n.id;
            Predicate<DiscoveryNode> matchesName =
                    n -> name == null || Objects.equals(name, n.name);
            Predicate<DiscoveryNode> matchesNames = n -> names == null || names.contains(n.name);

            return matchesId.and(matchesName).and(matchesNames).test(t);
        }
    }
}
