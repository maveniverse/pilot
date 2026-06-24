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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TreeDiffTest {

    private static DependencyTreeModel.TreeNode node(String g, String a, String v, int depth) {
        return new DependencyTreeModel.TreeNode(g, a, "", v, "", false, depth);
    }

    private static DependencyTreeModel tree(DependencyTreeModel.TreeNode root) {
        int[] count = {0};
        countNodes(root, count);
        return new DependencyTreeModel(root, List.of(), count[0]);
    }

    private static void countNodes(DependencyTreeModel.TreeNode node, int[] count) {
        count[0]++;
        for (var child : node.children) {
            countNodes(child, count);
        }
    }

    @Test
    void identicalTreesAllSame() {
        var root1 = node("g", "a", "1.0", 0);
        root1.children.add(node("g", "b", "2.0", 1));
        var root2 = node("g", "a", "1.0", 0);
        root2.children.add(node("g", "b", "2.0", 1));

        List<TreeDiff.DiffEntry> diff = TreeDiff.diff(tree(root1), tree(root2));

        assertThat(diff).hasSize(2).allMatch(e -> e.side() == TreeDiff.Side.SAME);
    }

    @Test
    void versionChangeProducesLeftAndRight() {
        var root1 = node("g", "a", "1.0", 0);
        root1.children.add(node("g", "b", "2.0", 1));
        var root2 = node("g", "a", "1.0", 0);
        root2.children.add(node("g", "b", "3.0", 1));

        List<TreeDiff.DiffEntry> diff = TreeDiff.diff(tree(root1), tree(root2));

        assertThat(diff).hasSize(3);
        assertThat(diff.get(0).side()).isEqualTo(TreeDiff.Side.SAME);
        assertThat(diff.get(1).side()).isEqualTo(TreeDiff.Side.LEFT);
        assertThat(diff.get(1).version()).isEqualTo("2.0");
        assertThat(diff.get(2).side()).isEqualTo(TreeDiff.Side.RIGHT);
        assertThat(diff.get(2).version()).isEqualTo("3.0");
    }

    @Test
    void addedNodeAppearsAsRight() {
        var root1 = node("g", "a", "1.0", 0);
        var root2 = node("g", "a", "1.0", 0);
        root2.children.add(node("g", "new", "1.0", 1));

        List<TreeDiff.DiffEntry> diff = TreeDiff.diff(tree(root1), tree(root2));

        assertThat(diff.stream().filter(e -> e.side() == TreeDiff.Side.RIGHT)).hasSize(1);
        assertThat(diff.stream()
                        .filter(e -> e.side() == TreeDiff.Side.RIGHT)
                        .findFirst()
                        .orElseThrow()
                        .ga())
                .isEqualTo("g:new");
    }

    @Test
    void removedNodeAppearsAsLeft() {
        var root1 = node("g", "a", "1.0", 0);
        root1.children.add(node("g", "old", "1.0", 1));
        var root2 = node("g", "a", "1.0", 0);

        List<TreeDiff.DiffEntry> diff = TreeDiff.diff(tree(root1), tree(root2));

        assertThat(diff.stream().filter(e -> e.side() == TreeDiff.Side.LEFT)).hasSize(1);
        assertThat(diff.stream()
                        .filter(e -> e.side() == TreeDiff.Side.LEFT)
                        .findFirst()
                        .orElseThrow()
                        .ga())
                .isEqualTo("g:old");
    }

    @Test
    void emptyTreesProduceOnlySameRoot() {
        var root1 = node("g", "a", "1.0", 0);
        var root2 = node("g", "a", "1.0", 0);

        List<TreeDiff.DiffEntry> diff = TreeDiff.diff(tree(root1), tree(root2));

        assertThat(diff).hasSize(1);
        assertThat(diff.get(0).side()).isEqualTo(TreeDiff.Side.SAME);
    }

    @Test
    void deepNestedChanges() {
        var root1 = node("g", "a", "1.0", 0);
        var child1 = node("g", "b", "1.0", 1);
        child1.children.add(node("g", "c", "1.0", 2));
        root1.children.add(child1);

        var root2 = node("g", "a", "1.0", 0);
        var child2 = node("g", "b", "1.0", 1);
        child2.children.add(node("g", "c", "2.0", 2));
        root2.children.add(child2);

        List<TreeDiff.DiffEntry> diff = TreeDiff.diff(tree(root1), tree(root2));

        assertThat(diff).hasSize(4);
        assertThat(diff.get(0).side()).isEqualTo(TreeDiff.Side.SAME);
        assertThat(diff.get(1).side()).isEqualTo(TreeDiff.Side.SAME);
        assertThat(diff.get(2).side()).isEqualTo(TreeDiff.Side.LEFT);
        assertThat(diff.get(3).side()).isEqualTo(TreeDiff.Side.RIGHT);
    }
}
