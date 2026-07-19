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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Compares two {@link DependencyTreeModel} trees and produces a list of diff entries.
 * Adapted from Toolbox's DependencyGraphComparator to work with Pilot's neutral tree model.
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

    public record DiffEntry(String ga, String version, int depth, Side side) {
        public String gav() {
            return ga + ":" + version;
        }
    }

    private TreeDiff() {}

    public static List<DiffEntry> diff(DependencyTreeModel left, DependencyTreeModel right) {
        List<DiffEntry> result = new ArrayList<>();
        Deque<DependencyTreeModel.TreeNode[]> stack1 = new ArrayDeque<>();
        Deque<DependencyTreeModel.TreeNode[]> stack2 = new ArrayDeque<>();
        stack1.push(new DependencyTreeModel.TreeNode[] {left.root});
        stack2.push(new DependencyTreeModel.TreeNode[] {right.root});

        while (!stack1.isEmpty() && !stack2.isEmpty()) {
            DependencyTreeModel.TreeNode n1 = stack1.pop()[0];
            DependencyTreeModel.TreeNode n2 = stack2.pop()[0];

            if (n1.ga().equals(n2.ga()) && n1.version.equals(n2.version)) {
                result.add(new DiffEntry(n1.ga(), n1.version, n1.depth, Side.SAME));
            } else {
                result.add(new DiffEntry(n1.ga(), n1.version, n1.depth, Side.LEFT));
                result.add(new DiffEntry(n2.ga(), n2.version, n2.depth, Side.RIGHT));
            }

            pushChildren(stack1, n1);
            pushChildren(stack2, n2);
        }
        drainRemaining(stack1, Side.LEFT, result);
        drainRemaining(stack2, Side.RIGHT, result);
        return result;
    }

    private static void pushChildren(Deque<DependencyTreeModel.TreeNode[]> stack, DependencyTreeModel.TreeNode node) {
        List<DependencyTreeModel.TreeNode> children = new ArrayList<>(node.children);
        Collections.reverse(children);
        for (DependencyTreeModel.TreeNode child : children) {
            stack.push(new DependencyTreeModel.TreeNode[] {child});
        }
    }

    private static void drainRemaining(Deque<DependencyTreeModel.TreeNode[]> stack, Side side, List<DiffEntry> result) {
        while (!stack.isEmpty()) {
            DependencyTreeModel.TreeNode[] pair = stack.pop();
            DependencyTreeModel.TreeNode node = pair[0];
            result.add(new DiffEntry(node.ga(), node.version, node.depth, side));
            pushChildren(stack, node);
        }
    }
}
