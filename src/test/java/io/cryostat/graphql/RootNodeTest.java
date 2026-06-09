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

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import io.cryostat.discovery.DiscoveryNode;
import io.cryostat.targets.Target;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RootNodeTest {

    private DiscoveryNode rootNode;
    private DiscoveryNode childNode1;
    private DiscoveryNode childNode2;
    private DiscoveryNode grandchildNode1;
    private DiscoveryNode grandchildNode2;
    private DiscoveryNode grandchildNode3;
    private Target target1;
    private Target target2;
    private Target target3;

    @BeforeEach
    void setup() {
        // Create test targets
        target1 = createTarget("target1", "service:jmx:rmi:///jndi/rmi://target1:9091/jmxrmi");
        target2 = createTarget("target2", "service:jmx:rmi:///jndi/rmi://target2:9091/jmxrmi");
        target3 = createTarget("target3", "service:jmx:rmi:///jndi/rmi://target3:9091/jmxrmi");

        // Create a tree structure:
        //        root
        //       /    \
        //   child1   child2
        //    /  \       \
        // gc1  gc2      gc3
        rootNode = createNode("root", "Universe", null);
        childNode1 = createNode("child1", "Realm", null);
        childNode2 = createNode("child2", "Realm", null);
        grandchildNode1 = createNode("grandchild1", "JVM", target1);
        grandchildNode2 = createNode("grandchild2", "JVM", target2);
        grandchildNode3 = createNode("grandchild3", "JVM", target3);

        // Build the tree
        rootNode.children = new ArrayList<>(List.of(childNode1, childNode2));
        childNode1.children = new ArrayList<>(List.of(grandchildNode1, grandchildNode2));
        childNode2.children = new ArrayList<>(List.of(grandchildNode3));
        grandchildNode1.children = new ArrayList<>();
        grandchildNode2.children = new ArrayList<>();
        grandchildNode3.children = new ArrayList<>();
    }

    @Test
    void testRecurseChildren_withNullPredicate_returnsAllNodes() {
        Set<DiscoveryNode> result = RootNode.recurseChildren(rootNode, null);

        assertEquals(6, result.size());
        assertTrue(result.contains(rootNode));
        assertTrue(result.contains(childNode1));
        assertTrue(result.contains(childNode2));
        assertTrue(result.contains(grandchildNode1));
        assertTrue(result.contains(grandchildNode2));
        assertTrue(result.contains(grandchildNode3));
    }

    @Test
    void testRecurseChildren_withAlwaysTruePredicate_returnsAllNodes() {
        Set<DiscoveryNode> result = RootNode.recurseChildren(rootNode, n -> true);

        assertEquals(6, result.size());
        assertTrue(result.contains(rootNode));
        assertTrue(result.contains(childNode1));
        assertTrue(result.contains(childNode2));
        assertTrue(result.contains(grandchildNode1));
        assertTrue(result.contains(grandchildNode2));
        assertTrue(result.contains(grandchildNode3));
    }

    @Test
    void testRecurseChildren_withAlwaysFalsePredicate_returnsEmptySet() {
        Set<DiscoveryNode> result = RootNode.recurseChildren(rootNode, n -> false);

        assertTrue(result.isEmpty());
    }

    @Test
    void testRecurseChildren_filterByTargetNotNull_returnsOnlyTargetNodes() {
        Set<DiscoveryNode> result = RootNode.recurseChildren(rootNode, n -> n.target != null);

        assertEquals(3, result.size());
        assertTrue(result.contains(grandchildNode1));
        assertTrue(result.contains(grandchildNode2));
        assertTrue(result.contains(grandchildNode3));
        assertFalse(result.contains(rootNode));
        assertFalse(result.contains(childNode1));
        assertFalse(result.contains(childNode2));
    }

    @Test
    void testRecurseChildren_filterByNodeType_returnsMatchingNodes() {
        Set<DiscoveryNode> result =
                RootNode.recurseChildren(rootNode, n -> "Realm".equals(n.nodeType));

        assertEquals(2, result.size());
        assertTrue(result.contains(childNode1));
        assertTrue(result.contains(childNode2));
        assertFalse(result.contains(rootNode));
        assertFalse(result.contains(grandchildNode1));
    }

    @Test
    void testRecurseChildren_filterByName_returnsMatchingNode() {
        Set<DiscoveryNode> result =
                RootNode.recurseChildren(rootNode, n -> "child1".equals(n.name));

        assertEquals(1, result.size());
        assertTrue(result.contains(childNode1));
    }

    @Test
    void testRecurseChildren_fromChildNode_returnsSubtree() {
        Set<DiscoveryNode> result = RootNode.recurseChildren(childNode1, null);

        assertEquals(3, result.size());
        assertTrue(result.contains(childNode1));
        assertTrue(result.contains(grandchildNode1));
        assertTrue(result.contains(grandchildNode2));
        assertFalse(result.contains(rootNode));
        assertFalse(result.contains(childNode2));
        assertFalse(result.contains(grandchildNode3));
    }

    @Test
    void testRecurseChildren_fromLeafNode_returnsSingleNode() {
        Set<DiscoveryNode> result = RootNode.recurseChildren(grandchildNode1, null);

        assertEquals(1, result.size());
        assertTrue(result.contains(grandchildNode1));
    }

    @Test
    void testRecurseChildren_fromLeafNodeWithPredicate_returnsNodeIfMatches() {
        Set<DiscoveryNode> result =
                RootNode.recurseChildren(grandchildNode1, n -> n.target != null);

        assertEquals(1, result.size());
        assertTrue(result.contains(grandchildNode1));
    }

    @Test
    void testRecurseChildren_fromLeafNodeWithFailingPredicate_returnsEmpty() {
        Set<DiscoveryNode> result =
                RootNode.recurseChildren(grandchildNode1, n -> n.target == null);

        assertTrue(result.isEmpty());
    }

    @Test
    void testRecurseChildren_withNodeHavingNoChildren_returnsOnlyThatNode() {
        DiscoveryNode singleNode = createNode("single", "JVM", target1);
        singleNode.children = new ArrayList<>();

        Set<DiscoveryNode> result = RootNode.recurseChildren(singleNode, null);

        assertEquals(1, result.size());
        assertTrue(result.contains(singleNode));
    }

    @Test
    void testRecurseChildren_withNodeHavingNullChildren_returnsOnlyThatNode() {
        DiscoveryNode singleNode = createNode("single", "JVM", target1);
        singleNode.children = null;

        Set<DiscoveryNode> result = RootNode.recurseChildren(singleNode, null);

        assertEquals(1, result.size());
        assertTrue(result.contains(singleNode));
    }

    @Test
    void testRecurseChildren_complexPredicate_returnsMatchingNodes() {
        // Filter for nodes that are either the root or have targets
        Set<DiscoveryNode> result =
                RootNode.recurseChildren(rootNode, n -> "root".equals(n.name) || n.target != null);

        assertEquals(4, result.size());
        assertTrue(result.contains(rootNode));
        assertTrue(result.contains(grandchildNode1));
        assertTrue(result.contains(grandchildNode2));
        assertTrue(result.contains(grandchildNode3));
        assertFalse(result.contains(childNode1));
        assertFalse(result.contains(childNode2));
    }

    @Test
    void testRecurseChildren_deepTree_returnsAllNodes() {
        // Create a deeper tree: root -> level1 -> level2 -> level3 -> level4
        DiscoveryNode level1 = createNode("level1", "Type1", null);
        DiscoveryNode level2 = createNode("level2", "Type2", null);
        DiscoveryNode level3 = createNode("level3", "Type3", null);
        DiscoveryNode level4 = createNode("level4", "Type4", target1);

        level1.children = new ArrayList<>(List.of(level2));
        level2.children = new ArrayList<>(List.of(level3));
        level3.children = new ArrayList<>(List.of(level4));
        level4.children = new ArrayList<>();

        Set<DiscoveryNode> result = RootNode.recurseChildren(level1, null);

        assertEquals(4, result.size());
        assertTrue(result.contains(level1));
        assertTrue(result.contains(level2));
        assertTrue(result.contains(level3));
        assertTrue(result.contains(level4));
    }

    @Test
    void testRecurseChildren_wideTree_returnsAllNodes() {
        // Create a wide tree with many children at one level
        DiscoveryNode parent = createNode("parent", "Parent", null);
        List<DiscoveryNode> children = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            DiscoveryNode child = createNode("child" + i, "Child", null);
            child.children = new ArrayList<>();
            children.add(child);
        }
        parent.children = children;

        Set<DiscoveryNode> result = RootNode.recurseChildren(parent, null);

        assertEquals(11, result.size()); // parent + 10 children
        assertTrue(result.contains(parent));
        children.forEach(child -> assertTrue(result.contains(child)));
    }

    @Test
    void testRecurseChildren_filterByTargetJvmId_returnsMatchingTargetNodes() {
        target1.jvmId = "jvm-123";
        target2.jvmId = "jvm-456";
        target3.jvmId = "jvm-123";

        Set<DiscoveryNode> result =
                RootNode.recurseChildren(
                        rootNode, n -> n.target != null && "jvm-123".equals(n.target.jvmId));

        assertEquals(2, result.size());
        assertTrue(result.contains(grandchildNode1));
        assertTrue(result.contains(grandchildNode3));
        assertFalse(result.contains(grandchildNode2));
    }

    private DiscoveryNode createNode(String name, String nodeType, Target target) {
        DiscoveryNode node = new DiscoveryNode();
        node.name = name;
        node.nodeType = nodeType;
        node.labels = new HashMap<>();
        node.target = target;
        node.children = new ArrayList<>();
        return node;
    }

    private Target createTarget(String alias, String connectUrl) {
        Target target = new Target();
        target.alias = alias;
        target.connectUrl = URI.create(connectUrl);
        target.labels = new HashMap<>();
        target.annotations = new Target.Annotations();
        return target;
    }
}
