/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package eu.maveniverse.maven.pilot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.aether.graph.DependencyNode;

/**
 * Tree data model with expand/collapse state and conflict detection.
 */
class DependencyTreeModel {

    static class TreeNode {
        final String groupId;
        final String artifactId;
        final String version;
        final String scope;
        final boolean optional;
        final int depth;
        final List<TreeNode> children;
        boolean expanded;
        String requestedVersion; // non-null if conflict
        String managedFrom; // who pulled this in as transitive

        TreeNode(String groupId, String artifactId, String version, String scope, boolean optional, int depth) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.scope = scope != null ? scope : "compile";
            this.optional = optional;
            this.depth = depth;
            this.children = new ArrayList<>();
            this.expanded = depth < 2; // expand top 2 levels by default
        }

        String ga() {
            return groupId + ":" + artifactId;
        }

        String gav() {
            return groupId + ":" + artifactId + ":" + version;
        }

        boolean hasChildren() {
            return !children.isEmpty();
        }

        boolean isConflict() {
            return requestedVersion != null && !requestedVersion.equals(version);
        }
    }

    final TreeNode root;
    final List<TreeNode> conflicts;
    final int totalNodes;

    DependencyTreeModel(TreeNode root, List<TreeNode> conflicts, int totalNodes) {
        this.root = root;
        this.conflicts = conflicts;
        this.totalNodes = totalNodes;
    }

    /**
     * Scopes included for each resolution scope, following Maven Resolver conventions.
     */
    private static final Map<String, Set<String>> SCOPE_INCLUSIONS = Map.of(
            "compile", Set.of("compile", "provided", "system", ""),
            "runtime", Set.of("compile", "runtime", ""),
            "test", Set.of("compile", "provided", "system", "runtime", "test", ""));

    static DependencyTreeModel fromDependencyNode(DependencyNode rootNode) {
        return fromDependencyNode(rootNode, null);
    }

    static DependencyTreeModel fromDependencyNode(DependencyNode rootNode, String scope) {
        Set<String> includedScopes = scope != null ? SCOPE_INCLUSIONS.get(scope) : null;
        int[] counter = {0};
        List<TreeNode> conflicts = new ArrayList<>();
        TreeNode root = convertNode(rootNode, 0, counter, new HashSet<>(), conflicts, includedScopes);

        return new DependencyTreeModel(root, conflicts, counter[0]);
    }

    private static TreeNode convertNode(
            DependencyNode node,
            int depth,
            int[] counter,
            Set<String> visited,
            List<TreeNode> conflicts,
            Set<String> includedScopes) {
        counter[0]++;

        String groupId, artifactId, version, scope;
        boolean optional = false;

        if (node.getDependency() != null) {
            var artifact = node.getDependency().getArtifact();
            groupId = artifact.getGroupId();
            artifactId = artifact.getArtifactId();
            version = artifact.getVersion();
            scope = node.getDependency().getScope();
            optional = node.getDependency().isOptional();
        } else if (node.getArtifact() != null) {
            groupId = node.getArtifact().getGroupId();
            artifactId = node.getArtifact().getArtifactId();
            version = node.getArtifact().getVersion();
            scope = "";
        } else {
            groupId = "?";
            artifactId = "?";
            version = "?";
            scope = "";
        }

        TreeNode treeNode = new TreeNode(groupId, artifactId, version, scope, optional, depth);

        // Read conflict data from the Resolver's ConflictResolver transformer.
        // After conflict resolution, losing nodes have their version replaced with
        // the winner's version, and the original requested version is stored in
        // "conflict.originalVersion" node data.
        if (node.getData().get("conflict.originalVersion") instanceof String originalVersion) {
            treeNode.requestedVersion = originalVersion;
            conflicts.add(treeNode);
        }

        // Guard against cycles
        String nodeKey = treeNode.ga() + ":" + version;
        if (!visited.add(nodeKey)) {
            return treeNode;
        }

        for (DependencyNode child : node.getChildren()) {
            if (includedScopes != null && child.getDependency() != null) {
                String childScope = child.getDependency().getScope();
                if (childScope != null && !includedScopes.contains(childScope)) {
                    continue;
                }
            }
            treeNode.children.add(convertNode(child, depth + 1, counter, visited, conflicts, includedScopes));
        }

        visited.remove(nodeKey);
        return treeNode;
    }

    /**
     * Returns the flattened list of visible nodes (respecting expand/collapse state).
     */
    List<TreeNode> visibleNodes() {
        List<TreeNode> visible = new ArrayList<>();
        collectVisible(root, visible);
        return visible;
    }

    private void collectVisible(TreeNode node, List<TreeNode> visible) {
        visible.add(node);
        if (node.expanded) {
            for (TreeNode child : node.children) {
                collectVisible(child, visible);
            }
        }
    }

    /**
     * Returns the path from root to the given node.
     */
    List<TreeNode> pathToRoot(TreeNode target) {
        List<TreeNode> path = new ArrayList<>();
        findPath(root, target, path);
        return path;
    }

    private boolean findPath(TreeNode current, TreeNode target, List<TreeNode> path) {
        path.add(current);
        if (current == target) {
            return true;
        }
        for (TreeNode child : current.children) {
            if (findPath(child, target, path)) {
                return true;
            }
        }
        path.remove(path.size() - 1);
        return false;
    }

    /**
     * Filter visible nodes by artifact coordinate substring.
     */
    List<TreeNode> filter(String query) {
        List<TreeNode> result = new ArrayList<>();
        filterNode(root, query.toLowerCase(), result);
        return result;
    }

    private void filterNode(TreeNode node, String query, List<TreeNode> result) {
        if (node.gav().toLowerCase().contains(query)) {
            result.add(node);
        }
        for (TreeNode child : node.children) {
            filterNode(child, query, result);
        }
    }
}
