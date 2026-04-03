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
import java.util.HashMap;
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

    static DependencyTreeModel fromDependencyNode(DependencyNode rootNode) {
        Map<String, String> resolvedVersions = new HashMap<>();
        int[] counter = {0};
        TreeNode root = convertNode(rootNode, 0, resolvedVersions, counter, new HashSet<>());

        // Detect conflicts: where requested != resolved
        List<TreeNode> conflicts = new ArrayList<>();
        collectConflicts(root, resolvedVersions, conflicts);

        return new DependencyTreeModel(root, conflicts, counter[0]);
    }

    private static TreeNode convertNode(
            DependencyNode node, int depth, Map<String, String> resolvedVersions, int[] counter, Set<String> visited) {
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

        String ga = treeNode.ga();
        resolvedVersions.putIfAbsent(ga, version);

        // Guard against cycles
        String nodeKey = ga + ":" + version;
        if (!visited.add(nodeKey)) {
            return treeNode;
        }

        for (DependencyNode child : node.getChildren()) {
            treeNode.children.add(convertNode(child, depth + 1, resolvedVersions, counter, visited));
        }

        visited.remove(nodeKey);
        return treeNode;
    }

    private static void collectConflicts(
            TreeNode node, Map<String, String> resolvedVersions, List<TreeNode> conflicts) {
        String resolvedVersion = resolvedVersions.get(node.ga());
        if (resolvedVersion != null && !resolvedVersion.equals(node.version) && node.depth > 0) {
            node.requestedVersion = node.version;
            conflicts.add(node);
        }
        for (TreeNode child : node.children) {
            collectConflicts(child, resolvedVersions, conflicts);
        }
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
