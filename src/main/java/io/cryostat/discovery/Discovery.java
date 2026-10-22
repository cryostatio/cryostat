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
package io.cryostat.discovery;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

import io.cryostat.discovery.NodeType.BaseNodeType;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.security.PermissionsAllowed;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/api/v5/discovery/tree")
public class Discovery {

    private static final String SYNTHETIC_REALM_NAME = "Cryostat Discovery";
    private static final UUID SYNTHETIC_REALM_ID =
            UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    @GET
    @PermissionsAllowed(value = "discoverynodes:read", inclusive = true)
    @Operation(summary = "Retrieve the entire discovery tree.")
    public DiscoveryNode get(
            @QueryParam("mergeRealms") @DefaultValue("false") boolean mergeRealms) {
        if (!mergeRealms) {
            return DiscoveryNode.getUniverse();
        }
        return mergeRealms();
    }

    @SuppressFBWarnings("DLS_DEAD_LOCAL_STORE")
    private DiscoveryNode mergeRealms() {
        DiscoveryNode universe = DiscoveryNode.getUniverse();
        DiscoveryNode mergedRoot = new DiscoveryNode();
        mergedRoot.id = universe.id;
        mergedRoot.name = universe.name;
        mergedRoot.nodeType = universe.nodeType;
        mergedRoot.labels = new HashMap<>(universe.labels);
        mergedRoot.children = new ArrayList<>();

        DiscoveryNode syntheticRealm = new DiscoveryNode();
        syntheticRealm.id = SYNTHETIC_REALM_ID;
        syntheticRealm.name = SYNTHETIC_REALM_NAME;
        syntheticRealm.nodeType = BaseNodeType.REALM.getKind();
        syntheticRealm.labels = new HashMap<>();
        syntheticRealm.children = new ArrayList<>();
        mergedRoot.children.add(syntheticRealm);

        var mergedNodes = new HashMap<Pair<String, String>, DiscoveryNode>();

        var builtinRealmIds =
                DiscoveryPlugin.find("#DiscoveryPlugin.getBuiltinRealmIds")
                        .project(UUID.class)
                        .list();

        for (var realm : universe.children) {
            var stack = new ArrayDeque<NodeContext>();
            for (var child : realm.children) {
                stack.push(new NodeContext(child, null, builtinRealmIds.contains(realm.id)));
            }

            while (!stack.isEmpty()) {
                var ctx = stack.pop();
                var sourceNode = ctx.node;
                var mergedParent = ctx.parent;
                var fromBuiltin = ctx.fromBuiltin;

                Pair<String, String> key = Pair.of(sourceNode.nodeType, sourceNode.name);

                DiscoveryNode mergedNode;
                if (mergedParent == null) {
                    mergedNode = mergedNodes.computeIfAbsent(key, k -> copyNode(sourceNode));
                    syntheticRealm.children.add(mergedNode);
                    if (fromBuiltin) {
                        mergeNodeProperties(mergedNode, sourceNode);
                    }
                } else {
                    mergedNode =
                            mergedParent.children.stream()
                                    .filter(
                                            n ->
                                                    n.nodeType.equals(sourceNode.nodeType)
                                                            && n.name.equals(sourceNode.name))
                                    .findFirst()
                                    .orElseGet(
                                            () -> {
                                                var node = copyNode(sourceNode);
                                                mergedParent.children.add(node);
                                                return node;
                                            });

                    // if we have collisions, prefer the node which came from a builtin plugin
                    // and merge properties from discovery plugins in
                    if (fromBuiltin) {
                        mergeNodeProperties(mergedNode, sourceNode);
                    }
                }

                if (sourceNode.children != null && !sourceNode.children.isEmpty()) {
                    for (var child : sourceNode.children) {
                        stack.push(new NodeContext(child, mergedNode, fromBuiltin));
                    }
                }
            }
        }

        return mergedRoot;
    }

    private DiscoveryNode copyNode(DiscoveryNode source) {
        var copy = new DiscoveryNode();
        copy.id = source.id;
        copy.name = source.name;
        copy.nodeType = source.nodeType;
        copy.labels = new HashMap<>(source.labels);
        copy.children = new ArrayList<>();
        copy.target = source.target;
        return copy;
    }

    private void mergeNodeProperties(DiscoveryNode target, DiscoveryNode source) {
        if (source.id != null) {
            target.id = source.id;
        }
        if (source.labels != null) {
            target.labels.putAll(source.labels);
        }
        if (source.target != null) {
            target.target = source.target;
        }
    }

    private static record NodeContext(
            DiscoveryNode node, DiscoveryNode parent, boolean fromBuiltin) {}
}
