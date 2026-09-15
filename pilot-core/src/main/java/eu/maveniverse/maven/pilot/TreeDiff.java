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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares two {@link DependencyTreeModel} trees and produces a list of diff entries.
 *
 * <p>Siblings are matched by GA+classifier+extension identity before descending, so an inserted or removed
 * child before an unchanged sibling does not cause spurious LEFT/RIGHT entries for the
 * unchanged siblings.
 */
public final class TreeDiff {

    public enum Side {
        LEFT(-1),
        SAME(0),
        RIGHT(1);

        public final int value;

        Side(int value) {
            this.value = value;
        }
    }

    public record DiffEntry(String ga, String version, String scope, int depth, Side side) {
        public String gav() {
            String base = ga + ":" + version;
            return (scope != null && !scope.isEmpty()) ? base + " [" + scope + "]" : base;
        }
    }

    private TreeDiff() {}

    public static List<DiffEntry> diff(DependencyTreeModel left, DependencyTreeModel right) {
        List<DiffEntry> result = new ArrayList<>();
        diffNodes(left.root, right.root, result);
        return result;
    }

    private static String nodeKey(DependencyTreeModel.TreeNode node) {
        String key = node.ga();
        if (node.classifier != null && !node.classifier.isEmpty()) {
            key += ":" + node.classifier;
        }
        if (node.extension != null && !node.extension.isEmpty() && !"jar".equals(node.extension)) {
            key += "@" + node.extension;
        }
        return key;
    }

    private static void diffNodes(
            DependencyTreeModel.TreeNode left, DependencyTreeModel.TreeNode right, List<DiffEntry> result) {
        // Emit the root / current pair — treat scope change as a difference
        if (left.ga().equals(right.ga()) && left.version.equals(right.version) && left.scope.equals(right.scope)) {
            result.add(new DiffEntry(left.ga(), left.version, left.scope, left.depth, Side.SAME));
        } else {
            result.add(new DiffEntry(left.ga(), left.version, left.scope, left.depth, Side.LEFT));
            result.add(new DiffEntry(right.ga(), right.version, right.scope, right.depth, Side.RIGHT));
        }

        // Match children by GA+classifier+extension identity
        List<DependencyTreeModel.TreeNode> leftChildren = left.children;
        List<DependencyTreeModel.TreeNode> rightChildren = right.children;

        // Build node-key → node maps (first occurrence wins, preserving order)
        Map<String, DependencyTreeModel.TreeNode> leftByGa = new LinkedHashMap<>();
        for (DependencyTreeModel.TreeNode c : leftChildren) {
            leftByGa.putIfAbsent(nodeKey(c), c);
        }
        Map<String, DependencyTreeModel.TreeNode> rightByGa = new LinkedHashMap<>();
        for (DependencyTreeModel.TreeNode c : rightChildren) {
            rightByGa.putIfAbsent(nodeKey(c), c);
        }

        // Unified key order: left order first, then right-only additions
        List<String> order = new ArrayList<>(leftByGa.keySet());
        for (String ga : rightByGa.keySet()) {
            if (!leftByGa.containsKey(ga)) {
                order.add(ga);
            }
        }

        for (String ga : order) {
            DependencyTreeModel.TreeNode lc = leftByGa.get(ga);
            DependencyTreeModel.TreeNode rc = rightByGa.get(ga);
            if (lc != null && rc != null) {
                // Present in both — recurse
                diffNodes(lc, rc, result);
            } else if (lc != null) {
                // Only in left (removed)
                drainSubtree(lc, Side.LEFT, result);
            } else {
                // Only in right (added)
                drainSubtree(rc, Side.RIGHT, result);
            }
        }
    }

    private static void drainSubtree(DependencyTreeModel.TreeNode node, Side side, List<DiffEntry> result) {
        result.add(new DiffEntry(node.ga(), node.version, node.scope, node.depth, side));
        for (DependencyTreeModel.TreeNode child : node.children) {
            drainSubtree(child, side, result);
        }
    }
}
