package io.cryostat.graphql;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;

import io.cryostat.discovery.DiscoveryNode;
import io.smallrye.graphql.api.Nullable;

@GraphQLApi
public class EnvironmentNodes {

    public static class EnvironmentNodeFilterInput {
        @Nullable
        public Long id;
        @Nullable
        public String name;
        @Nullable
        public List<String> names;
        @Nullable
        public String nodeType;
        @Nullable
        public List<String> labels;

    }

    public static class EnvironmentNode {
        private Long id;
        private String name;
        private List<String> labels;
        private String nodeType;
        private List<EnvironmentNode> children;

        public Long getId(){
            return this.id;
        }

        public String getName(){
            return this.name;
        }

        public List<String> getLabels(){
            return this.labels;
        }

        public String getNodeType(){
            return this.nodeType;
        }

        public List<EnvironmentNode> getChildren(){
            return this.children;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public void setName(String name) {
            this.name = name;
        }

        public void setLabels(List<String> labels) {
            this.labels = labels;
        }

        public void setNodeType(String nodeType) {
            this.nodeType = nodeType;
        }

        public void addChild(EnvironmentNode child) {
            if (this.children == null) {
                this.children = new ArrayList<>();
            }
            this.children.add(child);
        }
    }

    @Query("environmentNodes")
    @Description("Get all environment nodes in the discovery tree with optional filtering")
    public List<EnvironmentNode> environmentNodes(EnvironmentNodeFilterInput filter) {
        DiscoveryNode rootNode = DiscoveryNode.getUniverse();
        return filterAndTraverse(rootNode, filter).stream()
            .map(EnvironmentNodes::convertDiscoveryNodeToEnvironmentNode)
            .collect(Collectors.toList());
    }

    private List<DiscoveryNode> filterAndTraverse(DiscoveryNode node, EnvironmentNodeFilterInput filter) {
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
        if (filter == null) return true;

        boolean matchesId = filter.id == null || filter.id.equals(node.id);
        boolean matchesName = filter.name == null || Objects.equals(filter.name, node.name);
        boolean matchesNames = filter.names == null || filter.names.contains(node.name);
        boolean matchesLabels = filter.labels == null || filter.labels.stream().allMatch(label -> node.labels.containsKey(label));
        boolean matchesNodeType = filter.nodeType == null || filter.nodeType.equals(node.nodeType);

        return matchesId && matchesName && matchesNames && matchesLabels && matchesNodeType;
    }

    private static EnvironmentNode convertDiscoveryNodeToEnvironmentNode(DiscoveryNode discoveryNode) {
        EnvironmentNode envNode = new EnvironmentNode();
        envNode.setId(discoveryNode.id);
        envNode.setName(discoveryNode.name);
        List<String> labelsList = discoveryNode.labels.entrySet().stream()
        .map(entry -> entry.getKey() + "=" + entry.getValue())
        .collect(Collectors.toList());
        envNode.setLabels(labelsList);
        envNode.setNodeType(discoveryNode.nodeType);
        if (discoveryNode.children != null) {
            for (DiscoveryNode child : discoveryNode.children) {
                envNode.addChild(convertDiscoveryNodeToEnvironmentNode(child));
            }
        }
        return envNode;
    }
}
