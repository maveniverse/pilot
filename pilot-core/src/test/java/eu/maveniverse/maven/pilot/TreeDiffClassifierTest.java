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

class TreeDiffClassifierTest {

    private static DependencyTreeModel.TreeNode node(String g, String a, String v, int depth) {
        return new DependencyTreeModel.TreeNode(g, a, "", v, "", false, depth);
    }

    private static DependencyTreeModel.TreeNode nodeWithClassifier(
            String g, String a, String classifier, String extension, String v, int depth) {
        return new DependencyTreeModel.TreeNode(g, a, classifier, extension, v, "", false, depth);
    }

    private static DependencyTreeModel tree(DependencyTreeModel.TreeNode root) {
        int[] count = {0};
        count(root, count);
        return new DependencyTreeModel(root, List.of(), count[0]);
    }

    private static void count(DependencyTreeModel.TreeNode node, int[] acc) {
        acc[0]++;
        for (var c : node.children) count(c, acc);
    }

    @Test
    void classifierQualifiedSiblingKeptSeparateFromPlainJar() {
        // Both trees have foo:bar and foo:bar:test-jar as siblings
        var root1 = node("g", "app", "1.0", 0);
        root1.children.add(node("foo", "bar", "1.0", 1));
        root1.children.add(nodeWithClassifier("foo", "bar", "tests", "jar", "1.0", 1));

        var root2 = node("g", "app", "1.0", 0);
        root2.children.add(node("foo", "bar", "2.0", 1)); // upgraded
        root2.children.add(nodeWithClassifier("foo", "bar", "tests", "jar", "2.0", 1)); // upgraded

        List<TreeDiff.DiffEntry> diff = TreeDiff.diff(tree(root1), tree(root2));

        // root: SAME; bar: LEFT 1.0, RIGHT 2.0; bar:tests: LEFT 1.0, RIGHT 2.0
        assertThat(diff).hasSize(5);
        assertThat(diff.get(0).side()).isEqualTo(TreeDiff.Side.SAME); // root
        long lefts = diff.stream().filter(e -> e.side() == TreeDiff.Side.LEFT).count();
        long rights = diff.stream().filter(e -> e.side() == TreeDiff.Side.RIGHT).count();
        assertThat(lefts).isEqualTo(2);
        assertThat(rights).isEqualTo(2);
    }

    @Test
    void classifierRemovedAppearsAsLeft() {
        var root1 = node("g", "app", "1.0", 0);
        root1.children.add(node("foo", "bar", "1.0", 1));
        root1.children.add(nodeWithClassifier("foo", "bar", "tests", "jar", "1.0", 1));

        var root2 = node("g", "app", "1.0", 0);
        root2.children.add(node("foo", "bar", "1.0", 1)); // plain jar remains

        List<TreeDiff.DiffEntry> diff = TreeDiff.diff(tree(root1), tree(root2));

        // root: SAME; bar: SAME; bar:tests: LEFT
        assertThat(diff.stream().filter(e -> e.side() == TreeDiff.Side.LEFT)).hasSize(1);
        assertThat(diff.stream().filter(e -> e.side() == TreeDiff.Side.RIGHT)).isEmpty();
        assertThat(diff.stream()
                        .filter(e -> e.side() == TreeDiff.Side.LEFT)
                        .findFirst()
                        .orElseThrow()
                        .ga())
                .isEqualTo("foo:bar");
    }

    @Test
    void classifierAddedAppearsAsRight() {
        var root1 = node("g", "app", "1.0", 0);
        root1.children.add(node("foo", "bar", "1.0", 1));

        var root2 = node("g", "app", "1.0", 0);
        root2.children.add(node("foo", "bar", "1.0", 1));
        root2.children.add(nodeWithClassifier("foo", "bar", "tests", "jar", "1.0", 1));

        List<TreeDiff.DiffEntry> diff = TreeDiff.diff(tree(root1), tree(root2));

        assertThat(diff.stream().filter(e -> e.side() == TreeDiff.Side.LEFT)).isEmpty();
        assertThat(diff.stream().filter(e -> e.side() == TreeDiff.Side.RIGHT)).hasSize(1);
    }

    @Test
    void insertedNodeDoesNotCorruptUnchangedSibling() {
        // Regression for positional-match bug:
        //   left:  [X, Y]
        //   right: [NEW, X, Y]
        // Correct result: NEW=RIGHT, X=SAME, Y=SAME (not X=changed, Y=changed)
        var root1 = node("g", "app", "1.0", 0);
        root1.children.add(node("g", "x", "1.0", 1));
        root1.children.add(node("g", "y", "1.0", 1));

        var root2 = node("g", "app", "1.0", 0);
        root2.children.add(node("g", "new", "1.0", 1));
        root2.children.add(node("g", "x", "1.0", 1));
        root2.children.add(node("g", "y", "1.0", 1));

        List<TreeDiff.DiffEntry> diff = TreeDiff.diff(tree(root1), tree(root2));

        // root:SAME, x:SAME, y:SAME, new:RIGHT
        assertThat(diff.stream().filter(e -> e.side() == TreeDiff.Side.SAME)).hasSize(3);
        assertThat(diff.stream().filter(e -> e.side() == TreeDiff.Side.RIGHT)).hasSize(1);
        assertThat(diff.stream().filter(e -> e.side() == TreeDiff.Side.LEFT)).isEmpty();
    }

    @Test
    void nonJarExtensionIncludedInKey() {
        // foo:bar (jar) and foo:bar (zip) should be distinct siblings — different extensions, same GA
        var root1 = node("g", "app", "1.0", 0);
        root1.children.add(node("foo", "bar", "1.0", 1)); // plain jar
        root1.children.add(nodeWithClassifier("foo", "bar", "", "zip", "1.0", 1)); // zip variant

        var root2 = node("g", "app", "1.0", 0);
        root2.children.add(node("foo", "bar", "2.0", 1)); // upgraded jar
        root2.children.add(nodeWithClassifier("foo", "bar", "", "zip", "2.0", 1)); // upgraded zip

        List<TreeDiff.DiffEntry> diff = TreeDiff.diff(tree(root1), tree(root2));

        // root:SAME; bar-jar: LEFT+RIGHT; bar-zip: LEFT+RIGHT → 5 total
        assertThat(diff).hasSize(5);
        assertThat(diff.stream().filter(e -> e.side() == TreeDiff.Side.LEFT)).hasSize(2);
        assertThat(diff.stream().filter(e -> e.side() == TreeDiff.Side.RIGHT)).hasSize(2);
    }
}
