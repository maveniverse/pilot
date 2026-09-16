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

    @Test
    void classifierQualifiedSiblingsAreDistinct() {
        // Both trees have two children with the same GA but different classifiers;
        // they must not collapse into a single entry.
        var root1 = new DependencyTreeModel.TreeNode("g", "a", "", "1.0", "compile", false, 0);
        root1.children.add(new DependencyTreeModel.TreeNode("g", "b", "", "1.0", "compile", false, 1));
        root1.children.add(new DependencyTreeModel.TreeNode("g", "b", "tests", "1.0", "test", false, 1));
        var root2 = new DependencyTreeModel.TreeNode("g", "a", "", "1.0", "compile", false, 0);
        root2.children.add(new DependencyTreeModel.TreeNode("g", "b", "", "2.0", "compile", false, 1));
        root2.children.add(new DependencyTreeModel.TreeNode("g", "b", "tests", "2.0", "test", false, 1));

        List<TreeDiff.DiffEntry> diff = TreeDiff.diff(tree(root1), tree(root2));

        // root SAME + 2 × (LEFT + RIGHT) = 5 entries
        assertThat(diff).hasSize(5);
        assertThat(diff.get(0).side()).isEqualTo(TreeDiff.Side.SAME); // root
        assertThat(diff.get(1).side()).isEqualTo(TreeDiff.Side.LEFT); // g:b 1.0
        assertThat(diff.get(2).side()).isEqualTo(TreeDiff.Side.RIGHT); // g:b 2.0
        assertThat(diff.get(3).side()).isEqualTo(TreeDiff.Side.LEFT); // g:b:tests 1.0
        assertThat(diff.get(4).side()).isEqualTo(TreeDiff.Side.RIGHT); // g:b:tests 2.0
    }

    @Test
    void scopeChangeProducesLeftAndRight() {
        // A dependency whose GAV is unchanged but whose scope changes from compile to runtime
        // must appear as LEFT+RIGHT, not SAME.
        var root1 = new DependencyTreeModel.TreeNode("g", "a", "", "1.0", "compile", false, 0);
        root1.children.add(new DependencyTreeModel.TreeNode("g", "b", "", "1.0", "compile", false, 1));
        var root2 = new DependencyTreeModel.TreeNode("g", "a", "", "1.0", "compile", false, 0);
        root2.children.add(new DependencyTreeModel.TreeNode("g", "b", "", "1.0", "runtime", false, 1));

        List<TreeDiff.DiffEntry> diff = TreeDiff.diff(tree(root1), tree(root2));

        // root SAME + LEFT + RIGHT = 3 entries
        assertThat(diff).hasSize(3);
        assertThat(diff.get(0).side()).isEqualTo(TreeDiff.Side.SAME); // root
        assertThat(diff.get(1).side()).isEqualTo(TreeDiff.Side.LEFT); // g:b compile
        assertThat(diff.get(1).gav()).contains("[compile]");
        assertThat(diff.get(2).side()).isEqualTo(TreeDiff.Side.RIGHT); // g:b runtime
        assertThat(diff.get(2).gav()).contains("[runtime]");
    }

    @Test
    void addedNodeWithChildrenDrainsSubtree() {
        // A newly added node (right-only) that itself has children must drain the
        // entire subtree — both the parent and its descendants appear as RIGHT.
        var root1 = node("g", "a", "1.0", 0);
        var root2 = node("g", "a", "1.0", 0);
        var newParent = node("g", "new-parent", "1.0", 1);
        newParent.children.add(node("g", "new-child", "1.0", 2));
        root2.children.add(newParent);

        List<TreeDiff.DiffEntry> diff = TreeDiff.diff(tree(root1), tree(root2));

        // root SAME + new-parent RIGHT + new-child RIGHT = 3 entries
        assertThat(diff).hasSize(3);
        assertThat(diff.get(0).side()).isEqualTo(TreeDiff.Side.SAME);
        assertThat(diff.get(1).side()).isEqualTo(TreeDiff.Side.RIGHT);
        assertThat(diff.get(1).ga()).isEqualTo("g:new-parent");
        assertThat(diff.get(2).side()).isEqualTo(TreeDiff.Side.RIGHT);
        assertThat(diff.get(2).ga()).isEqualTo("g:new-child");
    }

    @Test
    void removedNodeWithChildrenDrainsSubtree() {
        // A removed node (left-only) that itself has children must drain the
        // entire subtree — both the parent and its descendants appear as LEFT.
        var root1 = node("g", "a", "1.0", 0);
        var oldParent = node("g", "old-parent", "1.0", 1);
        oldParent.children.add(node("g", "old-child", "1.0", 2));
        root1.children.add(oldParent);
        var root2 = node("g", "a", "1.0", 0);

        List<TreeDiff.DiffEntry> diff = TreeDiff.diff(tree(root1), tree(root2));

        // root SAME + old-parent LEFT + old-child LEFT = 3 entries
        assertThat(diff).hasSize(3);
        assertThat(diff.get(0).side()).isEqualTo(TreeDiff.Side.SAME);
        assertThat(diff.get(1).side()).isEqualTo(TreeDiff.Side.LEFT);
        assertThat(diff.get(1).ga()).isEqualTo("g:old-parent");
        assertThat(diff.get(2).side()).isEqualTo(TreeDiff.Side.LEFT);
        assertThat(diff.get(2).ga()).isEqualTo("g:old-child");
    }

    @Test
    void diffNodesTruncatesAtMaxDepth() {
        // Build a tree that is MAX_DEPTH + 1 deep (depth field = MAX_DEPTH + 1 on the leaf).
        // diffNodes hits the guard when left.depth > MAX_DEPTH.
        DependencyTreeModel.TreeNode root = node("g", "root", "1.0", 0);
        DependencyTreeModel.TreeNode cur = root;
        for (int i = 1; i <= TreeDiff.MAX_DEPTH + 1; i++) {
            DependencyTreeModel.TreeNode child = node("g", "dep", "1.0", i);
            cur.children.add(child);
            cur = child;
        }

        List<TreeDiff.DiffEntry> diff = TreeDiff.diff(tree(root), tree(root));

        assertThat(diff.stream().anyMatch(e -> e.ga().startsWith("[tree truncated")))
                .as("depth guard must emit a truncation sentinel")
                .isTrue();
    }

    @Test
    void drainSubtreeTruncatesAtMaxDepth() {
        // Build a right-only deep subtree so drainSubtree is called on a node at depth
        // MAX_DEPTH + 1, exercising the guard in drainSubtree.
        DependencyTreeModel.TreeNode root1 = node("g", "a", "1.0", 0);
        DependencyTreeModel.TreeNode root2 = node("g", "a", "1.0", 0);
        DependencyTreeModel.TreeNode cur = root2;
        for (int i = 1; i <= TreeDiff.MAX_DEPTH + 1; i++) {
            DependencyTreeModel.TreeNode child = node("g", "new", "1.0", i);
            cur.children.add(child);
            cur = child;
        }

        List<TreeDiff.DiffEntry> diff = TreeDiff.diff(tree(root1), tree(root2));

        assertThat(diff.stream().anyMatch(e -> e.ga().startsWith("[tree truncated")))
                .as("drainSubtree depth guard must emit a truncation sentinel")
                .isTrue();
    }

    @Test
    void gavWithEmptyScopeOmitsBracket() {
        // When scope is empty, gav() returns just "ga:version" without a bracket suffix.
        var entry = new TreeDiff.DiffEntry("g:a", "1.0", "", 0, TreeDiff.Side.SAME);
        assertThat(entry.gav()).isEqualTo("g:a:1.0");
    }

    @Test
    void gavWithNullScopeOmitsBracket() {
        // When scope is null, gav() returns just "ga:version" without a bracket suffix.
        var entry = new TreeDiff.DiffEntry("g:a", "1.0", null, 0, TreeDiff.Side.SAME);
        assertThat(entry.gav()).isEqualTo("g:a:1.0");
    }

    @Test
    void nonJarExtensionIncludedInNodeKey() {
        // A node with a non-jar extension must produce a distinct key so it is not
        // collapsed with the default jar variant.
        var root1 = new DependencyTreeModel.TreeNode("g", "a", "", "1.0", "compile", false, 0);
        var zip1 = new DependencyTreeModel.TreeNode("g", "b", "", "zip", "1.0", "compile", false, 1);
        root1.children.add(zip1);

        var root2 = new DependencyTreeModel.TreeNode("g", "a", "", "1.0", "compile", false, 0);
        var zip2 = new DependencyTreeModel.TreeNode("g", "b", "", "zip", "2.0", "compile", false, 1);
        root2.children.add(zip2);

        List<TreeDiff.DiffEntry> diff = TreeDiff.diff(tree(root1), tree(root2));

        // root SAME + zip 1.0 LEFT + zip 2.0 RIGHT = 3 entries
        assertThat(diff).hasSize(3);
        assertThat(diff.get(0).side()).isEqualTo(TreeDiff.Side.SAME);
        assertThat(diff.get(1).side()).isEqualTo(TreeDiff.Side.LEFT);
        assertThat(diff.get(2).side()).isEqualTo(TreeDiff.Side.RIGHT);
    }
}
