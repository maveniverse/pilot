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
import java.util.List;
import java.util.Map;

/**
 * Tree data model with expand/collapse state and conflict detection.
 */
public class DependencyTreeModel {

    public static class TreeNode {
        public final String groupId;
        public final String artifactId;
        public final String version;
        public final String scope;
        public final boolean optional;
        public final int depth;
        public final List<TreeNode> children;
        public boolean expanded;
        public String requestedVersion; // non-null if conflict
        public String managedFrom; // who pulled this in as transitive

        public TreeNode(String groupId, String artifactId, String version, String scope, boolean optional, int depth) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.scope = scope != null ? scope : "compile";
            this.optional = optional;
            this.depth = depth;
            this.children = new ArrayList<>();
            this.expanded = depth < 2; // expand top 2 levels by default
        }

        public String ga() {
            return groupId + ":" + artifactId;
        }

        public String gav() {
            return groupId + ":" + artifactId + ":" + version;
        }

        public boolean hasChildren() {
            return !children.isEmpty();
        }

        public boolean isConflict() {
            return requestedVersion != null && !requestedVersion.equals(version);
        }
    }

    public final TreeNode root;
    public final List<TreeNode> conflicts;
    public final int totalNodes;

    public DependencyTreeModel(TreeNode root, List<TreeNode> conflicts, int totalNodes) {
        this.root = root;
        this.conflicts = conflicts;
        this.totalNodes = totalNodes;
    }

    public static DependencyTreeModel fromTree(TreeNode root) {
        Map<String, String> resolvedVersions = new HashMap<>();
        collectResolvedVersions(root, resolvedVersions);

        List<TreeNode> conflicts = new ArrayList<>();
        collectConflicts(root, resolvedVersions, conflicts);

        return new DependencyTreeModel(root, conflicts, countNodes(root));
    }

    private static void collectResolvedVersions(TreeNode node, Map<String, String> resolvedVersions) {
        resolvedVersions.putIfAbsent(node.ga(), node.version);
        for (TreeNode child : node.children) {
            collectResolvedVersions(child, resolvedVersions);
        }
    }

    private static int countNodes(TreeNode node) {
        int count = 1;
        for (TreeNode child : node.children) {
            count += countNodes(child);
        }
        return count;
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
    public List<TreeNode> visibleNodes() {
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
    public List<TreeNode> pathToRoot(TreeNode target) {
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
    public List<TreeNode> filter(String query) {
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
