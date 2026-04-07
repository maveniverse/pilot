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

class DependencyTreeModelTest {

    private DependencyTreeModel.TreeNode node(String g, String a, String v, int depth) {
        return new DependencyTreeModel.TreeNode(g, a, v, "compile", false, depth);
    }

    private DependencyTreeModel buildSimpleTree() {
        var root = node("com.example", "app", "1.0.0", 0);
        var child1 = node("org.slf4j", "slf4j-api", "2.0.9", 1);
        var child2 = node("com.google.guava", "guava", "33.0.0-jre", 1);
        var grandchild = node("com.google.guava", "failureaccess", "1.0.2", 2);
        child2.children.add(grandchild);
        root.children.add(child1);
        root.children.add(child2);
        return new DependencyTreeModel(root, List.of(), 4);
    }

    @Test
    void treeNodeGaAndGav() {
        var n = node("org.slf4j", "slf4j-api", "2.0.9", 0);
        assertThat(n.ga()).isEqualTo("org.slf4j:slf4j-api");
        assertThat(n.gav()).isEqualTo("org.slf4j:slf4j-api:2.0.9");
    }

    @Test
    void treeNodeDefaultScope() {
        var n = new DependencyTreeModel.TreeNode("g", "a", "1.0", null, false, 0);
        assertThat(n.scope).isEqualTo("compile");
    }

    @Test
    void treeNodeHasChildren() {
        var parent = node("g", "a", "1.0", 0);
        assertThat(parent.hasChildren()).isFalse();
        parent.children.add(node("g2", "a2", "1.0", 1));
        assertThat(parent.hasChildren()).isTrue();
    }

    @Test
    void treeNodeExpandedByDefault() {
        assertThat(node("g", "a", "1.0", 0).expanded).isTrue();
        assertThat(node("g", "a", "1.0", 1).expanded).isTrue();
        assertThat(node("g", "a", "1.0", 2).expanded).isFalse();
        assertThat(node("g", "a", "1.0", 3).expanded).isFalse();
    }

    @Test
    void treeNodeIsConflict() {
        var n = node("g", "a", "2.0", 1);
        assertThat(n.isConflict()).isFalse();

        n.requestedVersion = "1.0";
        assertThat(n.isConflict()).isTrue();

        n.requestedVersion = "2.0";
        assertThat(n.isConflict()).isFalse();
    }

    @Test
    void visibleNodesRespectsExpansion() {
        var model = buildSimpleTree();

        // root (depth 0, expanded) + child1 (depth 1, expanded) + child2 (depth 1, expanded)
        // + grandchild (depth 2, NOT expanded by default but visible because parent is expanded)
        var visible = model.visibleNodes();
        assertThat(visible).hasSize(4);
        assertThat(visible.get(0).ga()).isEqualTo("com.example:app");
        assertThat(visible.get(1).ga()).isEqualTo("org.slf4j:slf4j-api");
        assertThat(visible.get(2).ga()).isEqualTo("com.google.guava:guava");
        assertThat(visible.get(3).ga()).isEqualTo("com.google.guava:failureaccess");
    }

    @Test
    void collapsingNodeHidesChildren() {
        var model = buildSimpleTree();
        model.root.expanded = false;

        var visible = model.visibleNodes();
        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).ga()).isEqualTo("com.example:app");
    }

    @Test
    void collapsingChildHidesGrandchildren() {
        var model = buildSimpleTree();
        // Collapse guava (child2) which has grandchild
        model.root.children.get(1).expanded = false;

        var visible = model.visibleNodes();
        assertThat(visible).hasSize(3); // root + child1 + child2 (grandchild hidden)
    }

    @Test
    void pathToRootFindsDirectChild() {
        var model = buildSimpleTree();
        var child1 = model.root.children.get(0);

        var path = model.pathToRoot(child1);
        assertThat(path).hasSize(2);
        assertThat(path.get(0)).isSameAs(model.root);
        assertThat(path.get(1)).isSameAs(child1);
    }

    @Test
    void pathToRootFindsGrandchild() {
        var model = buildSimpleTree();
        var grandchild = model.root.children.get(1).children.get(0);

        var path = model.pathToRoot(grandchild);
        assertThat(path).hasSize(3);
        assertThat(path.get(0)).isSameAs(model.root);
        assertThat(path.get(2)).isSameAs(grandchild);
    }

    @Test
    void pathToRootReturnsEmptyForUnknownNode() {
        var model = buildSimpleTree();
        var unknown = node("unknown", "unknown", "1.0", 0);

        var path = model.pathToRoot(unknown);
        assertThat(path).isEmpty();
    }

    @Test
    void filterMatchesGav() {
        var model = buildSimpleTree();

        var results = model.filter("slf4j");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).ga()).isEqualTo("org.slf4j:slf4j-api");
    }

    @Test
    void filterIsCaseInsensitive() {
        var model = buildSimpleTree();

        var results = model.filter("SLF4J");
        assertThat(results).hasSize(1);
    }

    @Test
    void filterMatchesMultipleNodes() {
        var model = buildSimpleTree();

        var results = model.filter("guava");
        assertThat(results).hasSize(2); // guava + failureaccess (both have guava in groupId)
    }

    @Test
    void filterReturnsEmptyForNoMatch() {
        var model = buildSimpleTree();

        var results = model.filter("nonexistent");
        assertThat(results).isEmpty();
    }
}
