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

    @Test
    void treeNodeGa() {
        var node = new DependencyTreeModel.TreeNode("com.google.guava", "guava", "33.0.0-jre", "compile", false, 0);
        assertThat(node.ga()).isEqualTo("com.google.guava:guava");
    }

    @Test
    void treeNodeGav() {
        var node = new DependencyTreeModel.TreeNode("com.google.guava", "guava", "33.0.0-jre", "compile", false, 0);
        assertThat(node.gav()).isEqualTo("com.google.guava:guava:33.0.0-jre");
    }

    @Test
    void treeNodeScopeDefaultsToCompile() {
        var node = new DependencyTreeModel.TreeNode("g", "a", "1.0", null, false, 0);
        assertThat(node.scope).isEqualTo("compile");
    }

    @Test
    void treeNodeExpandedByDefault() {
        var shallow = new DependencyTreeModel.TreeNode("g", "a", "1.0", "compile", false, 0);
        var deep = new DependencyTreeModel.TreeNode("g", "a", "1.0", "compile", false, 3);
        assertThat(shallow.expanded).isTrue();
        assertThat(deep.expanded).isFalse();
    }

    @Test
    void treeNodeHasChildren() {
        var parent = new DependencyTreeModel.TreeNode("g", "parent", "1.0", "compile", false, 0);
        assertThat(parent.hasChildren()).isFalse();
        parent.children.add(new DependencyTreeModel.TreeNode("g", "child", "1.0", "compile", false, 1));
        assertThat(parent.hasChildren()).isTrue();
    }

    @Test
    void treeNodeIsConflict() {
        var node = new DependencyTreeModel.TreeNode("g", "a", "2.0", "compile", false, 1);
        assertThat(node.isConflict()).isFalse();
        node.requestedVersion = "1.0";
        assertThat(node.isConflict()).isTrue();
        node.requestedVersion = "2.0";
        assertThat(node.isConflict()).isFalse();
    }

    @Test
    void fromTreeCountsNodes() {
        var root = buildSampleTree();
        DependencyTreeModel model = DependencyTreeModel.fromTree(root);
        assertThat(model.totalNodes).isEqualTo(4);
    }

    @Test
    void fromTreeDetectsConflicts() {
        var root = new DependencyTreeModel.TreeNode("g", "root", "1.0", "compile", false, 0);
        var child1 = new DependencyTreeModel.TreeNode("g", "lib", "2.0", "compile", false, 1);
        var child2 = new DependencyTreeModel.TreeNode("g", "other", "1.0", "compile", false, 1);
        var transitive = new DependencyTreeModel.TreeNode("g", "lib", "1.0", "compile", false, 2);
        child2.children.add(transitive);
        root.children.add(child1);
        root.children.add(child2);

        DependencyTreeModel model = DependencyTreeModel.fromTree(root);
        assertThat(model.conflicts).hasSize(1);
        assertThat(model.conflicts.get(0).requestedVersion).isEqualTo("1.0");
    }

    @Test
    void fromTreeDoesNotFlagRootAsConflict() {
        var root = new DependencyTreeModel.TreeNode("g", "root", "1.0", "compile", false, 0);
        DependencyTreeModel model = DependencyTreeModel.fromTree(root);
        assertThat(model.conflicts).isEmpty();
    }

    @Test
    void visibleNodesRespectsExpansion() {
        var root = buildSampleTree();
        DependencyTreeModel model = DependencyTreeModel.fromTree(root);
        root.expanded = true;
        root.children.get(0).expanded = false;

        List<DependencyTreeModel.TreeNode> visible = model.visibleNodes();
        // root + child-a (collapsed, so its child hidden) + child-b
        assertThat(visible).hasSize(3);
    }

    @Test
    void visibleNodesCollapsedRoot() {
        var root = buildSampleTree();
        root.expanded = false;
        DependencyTreeModel model = DependencyTreeModel.fromTree(root);

        List<DependencyTreeModel.TreeNode> visible = model.visibleNodes();
        assertThat(visible).hasSize(1);
        assertThat(visible.get(0)).isSameAs(root);
    }

    @Test
    void pathToRootFindsDirectChild() {
        var root = buildSampleTree();
        DependencyTreeModel model = DependencyTreeModel.fromTree(root);
        var childA = root.children.get(0);

        List<DependencyTreeModel.TreeNode> path = model.pathToRoot(childA);
        assertThat(path).containsExactly(root, childA);
    }

    @Test
    void pathToRootFindsDeepNode() {
        var root = buildSampleTree();
        DependencyTreeModel model = DependencyTreeModel.fromTree(root);
        var grandchild = root.children.get(0).children.get(0);

        List<DependencyTreeModel.TreeNode> path = model.pathToRoot(grandchild);
        assertThat(path).containsExactly(root, root.children.get(0), grandchild);
    }

    @Test
    void pathToRootReturnsEmptyForMissing() {
        var root = buildSampleTree();
        DependencyTreeModel model = DependencyTreeModel.fromTree(root);
        var unknown = new DependencyTreeModel.TreeNode("g", "unknown", "1.0", "compile", false, 0);

        List<DependencyTreeModel.TreeNode> path = model.pathToRoot(unknown);
        assertThat(path).isEmpty();
    }

    @Test
    void filterMatchesGav() {
        var root = buildSampleTree();
        DependencyTreeModel model = DependencyTreeModel.fromTree(root);

        assertThat(model.filter("child-a")).hasSize(1);
        assertThat(model.filter("child")).hasSize(3); // child-a, child-b, grandchild
        assertThat(model.filter("child-")).hasSize(2); // child-a, child-b
        assertThat(model.filter("CHILD-")).hasSize(2); // case-insensitive
        assertThat(model.filter("nonexistent")).isEmpty();
    }

    @Test
    void filterMatchesVersion() {
        var root = buildSampleTree();
        DependencyTreeModel model = DependencyTreeModel.fromTree(root);
        assertThat(model.filter("2.0")).hasSize(1);
    }

    private DependencyTreeModel.TreeNode buildSampleTree() {
        var root = new DependencyTreeModel.TreeNode("com.example", "root", "1.0", "compile", false, 0);
        var childA = new DependencyTreeModel.TreeNode("com.example", "child-a", "1.0", "compile", false, 1);
        var childB = new DependencyTreeModel.TreeNode("com.example", "child-b", "2.0", "test", false, 1);
        var grandchild = new DependencyTreeModel.TreeNode("com.example", "grandchild", "1.0", "compile", false, 2);
        childA.children.add(grandchild);
        root.children.add(childA);
        root.children.add(childB);
        return root;
    }
}
