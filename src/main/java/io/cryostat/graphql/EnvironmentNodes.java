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

import io.cryostat.discovery.DiscoveryNode;
import io.cryostat.graphql.matchers.LabelSelectorMatcher;

import io.smallrye.graphql.api.Nullable;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;

@GraphQLApi
public class EnvironmentNodes {

    public static class EnvironmentNodeFilterInput {
        @Nullable public Long id;
        @Nullable public String name;
        @Nullable public List<String> names;
        @Nullable public String nodeType;
        @Nullable public List<String> labels;
    }

    @Query("environmentNodes")
    @Description("Get all environment nodes in the discovery tree with optional filtering")
    public List<DiscoveryNode> environmentNodes(EnvironmentNodeFilterInput filter) {
        DiscoveryNode rootNode = DiscoveryNode.getUniverse();
        return filterAndTraverse(rootNode, filter);
    }

    private List<DiscoveryNode> filterAndTraverse(
            DiscoveryNode node, EnvironmentNodeFilterInput filter) {
        List<DiscoveryNode> filteredNodes = new ArrayList<>();
        if (matchesFilter(node, filter)) {
            filteredNodes.add(node);
        }
        if (node.children != null) {
            for (DiscoveryNode child : node.children) {
                filteredNodes.addAll(filterAndTraverse(child, filter));
            }
        }
        return filteredNodes;
    }

    private static boolean matchesFilter(DiscoveryNode node, EnvironmentNodeFilterInput filter) {
        if (node.target != null) return false;
        if (filter == null) return true;

        boolean matchesId = filter.id == null || filter.id.equals(node.id);
        boolean matchesName = filter.name == null || Objects.equals(filter.name, node.name);
        boolean matchesNames = filter.names == null || filter.names.contains(node.name);
        boolean matchesLabels =
                filter.labels == null
                        || filter.labels.stream()
                                .allMatch(
                                        label ->
                                                LabelSelectorMatcher.parse(label)
                                                        .test(node.labels));
        boolean matchesNodeType = filter.nodeType == null || filter.nodeType.equals(node.nodeType);

        return matchesId && matchesName && matchesNames && matchesLabels && matchesNodeType;
    }
}
