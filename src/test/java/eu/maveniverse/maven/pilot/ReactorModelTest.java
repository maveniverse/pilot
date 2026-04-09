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

import static eu.maveniverse.maven.pilot.TestProjects.createProject;
import static eu.maveniverse.maven.pilot.TestProjects.subdir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReactorModelTest {

    @TempDir
    Path tempDir;

    @Test
    void buildSingleProject() {
        MavenProject root = createProject("root", tempDir);
        ReactorModel model = ReactorModel.build(List.of(root));

        assertThat(model.root).isNotNull();
        assertThat(model.root.name).isEqualTo("root");
        assertThat(model.root.depth).isEqualTo(0);
        assertThat(model.root.hasChildren()).isFalse();
        assertThat(model.root.isLeaf()).isTrue();
        assertThat(model.allModules).hasSize(1);
    }

    @Test
    void buildParentChild() throws IOException {
        Path childDir = subdir(tempDir, "child");

        MavenProject root = createProject("parent", tempDir);
        MavenProject child = createProject("child", childDir);

        ReactorModel model = ReactorModel.build(List.of(root, child));

        assertThat(model.root.name).isEqualTo("parent");
        assertThat(model.root.hasChildren()).isTrue();
        assertThat(model.root.children).hasSize(1);
        assertThat(model.root.children.get(0).name).isEqualTo("child");
        assertThat(model.root.children.get(0).depth).isEqualTo(1);
        assertThat(model.allModules).hasSize(2);
    }

    @Test
    void buildNestedHierarchy() throws IOException {
        Path coreDir = subdir(tempDir, "core");
        Path modelDir = Files.createDirectories(coreDir.resolve("core-model"));

        MavenProject root = createProject("parent", tempDir);
        MavenProject core = createProject("core", coreDir);
        MavenProject coreModel = createProject("core-model", modelDir);

        ReactorModel model = ReactorModel.build(List.of(root, core, coreModel));

        assertThat(model.root.children).hasSize(1);
        assertThat(model.root.children.get(0).name).isEqualTo("core");
        assertThat(model.root.children.get(0).children).hasSize(1);
        assertThat(model.root.children.get(0).children.get(0).name).isEqualTo("core-model");
        assertThat(model.root.children.get(0).children.get(0).depth).isEqualTo(2);
    }

    @Test
    void visibleNodesCollapsed() throws IOException {
        Path childDir = subdir(tempDir, "child");
        Path grandchildDir = Files.createDirectories(childDir.resolve("grandchild"));

        MavenProject root = createProject("root", tempDir);
        MavenProject child = createProject("child", childDir);
        MavenProject grandchild = createProject("grandchild", grandchildDir);

        ReactorModel model = ReactorModel.build(List.of(root, child, grandchild));

        // Root is expanded by default (depth 0 < 2), child is expanded (depth 1 < 2)
        List<ReactorModel.ModuleNode> visible = model.visibleNodes();
        assertThat(visible).hasSize(3);

        // Collapse child
        model.root.children.get(0).expanded = false;
        visible = model.visibleNodes();
        assertThat(visible).hasSize(2);
        assertThat(visible.get(0).name).isEqualTo("root");
        assertThat(visible.get(1).name).isEqualTo("child");
    }

    @Test
    void visibleNodesExpanded() throws IOException {
        Path childDir = subdir(tempDir, "child2");

        MavenProject root = createProject("root", tempDir);
        MavenProject child = createProject("child", childDir);

        ReactorModel model = ReactorModel.build(List.of(root, child));

        // All expanded by default (both depth < 2)
        List<ReactorModel.ModuleNode> visible = model.visibleNodes();
        assertThat(visible).hasSize(2);
    }

    @Test
    void filterByName() throws IOException {
        Path coreDir = subdir(tempDir, "core");
        Path apiDir = subdir(tempDir, "api");

        MavenProject root = createProject("parent", tempDir);
        MavenProject core = createProject("core-module", coreDir);
        MavenProject api = createProject("api-module", apiDir);

        ReactorModel model = ReactorModel.build(List.of(root, core, api));

        List<ReactorModel.ModuleNode> filtered = model.filter("core");
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).name).isEqualTo("core-module");
    }

    @Test
    void filterCaseInsensitive() throws IOException {
        Path childDir = subdir(tempDir, "child3");

        MavenProject root = createProject("ROOT-project", tempDir);
        MavenProject child = createProject("Child-Module", childDir);

        ReactorModel model = ReactorModel.build(List.of(root, child));

        assertThat(model.filter("root")).hasSize(1);
        assertThat(model.filter("ROOT")).hasSize(1);
        assertThat(model.filter("child")).hasSize(1);
    }

    @Test
    void filterByGa() throws IOException {
        Path childDir = subdir(tempDir, "child4");

        MavenProject root = createProject("parent", tempDir);
        MavenProject child = createProject("child", childDir);

        ReactorModel model = ReactorModel.build(List.of(root, child));

        List<ReactorModel.ModuleNode> filtered = model.filter("com.example:child");
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).name).isEqualTo("child");
    }

    @Test
    void filterNoMatch() {
        MavenProject root = createProject("app", tempDir);
        ReactorModel model = ReactorModel.build(List.of(root));

        assertThat(model.filter("nonexistent")).isEmpty();
    }

    @Test
    void recomputeCounts() throws IOException {
        Path coreDir = subdir(tempDir, "core2");
        Path apiDir = subdir(tempDir, "api2");

        MavenProject root = createProject("parent", tempDir);
        MavenProject core = createProject("core", coreDir);
        MavenProject api = createProject("api", apiDir);

        ReactorModel model = ReactorModel.build(List.of(root, core, api));

        model.root.ownUpdateCount = 2;
        model.root.children.get(0).ownUpdateCount = 3;
        model.root.children.get(1).ownUpdateCount = 1;

        model.recomputeCounts();

        assertThat(model.root.totalUpdateCount).isEqualTo(6); // 2 + 3 + 1
        assertThat(model.root.children.get(0).totalUpdateCount).isEqualTo(3);
        assertThat(model.root.children.get(1).totalUpdateCount).isEqualTo(1);
    }

    @Test
    void recomputeCountsNested() throws IOException {
        Path coreDir = subdir(tempDir, "core3");
        Path modelDir = Files.createDirectories(coreDir.resolve("model"));

        MavenProject root = createProject("parent", tempDir);
        MavenProject core = createProject("core", coreDir);
        MavenProject coreModel = createProject("model", modelDir);

        ReactorModel model = ReactorModel.build(List.of(root, core, coreModel));

        model.root.ownUpdateCount = 1;
        model.root.children.get(0).ownUpdateCount = 2;
        model.root.children.get(0).children.get(0).ownUpdateCount = 5;

        model.recomputeCounts();

        assertThat(model.root.totalUpdateCount).isEqualTo(8); // 1 + 2 + 5
        assertThat(model.root.children.get(0).totalUpdateCount).isEqualTo(7); // 2 + 5
        assertThat(model.root.children.get(0).children.get(0).totalUpdateCount).isEqualTo(5);
    }

    @Test
    void buildEmptyProjectListThrows() {
        assertThatThrownBy(() -> ReactorModel.build(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No projects in reactor");
    }

    @Test
    void moduleNodeGaAndGav() {
        MavenProject project = createProject("myapp", tempDir);
        ReactorModel.ModuleNode node = new ReactorModel.ModuleNode(project, 0);

        assertThat(node.ga()).isEqualTo("com.example:myapp");
        assertThat(node.gav()).isEqualTo("com.example:myapp:1.0");
    }

    @Test
    void collectSubtreeFromRoot() throws IOException {
        Path coreDir = subdir(tempDir, "core-sub");
        Path apiDir = subdir(tempDir, "api-sub");

        MavenProject root = createProject("parent", tempDir);
        MavenProject core = createProject("core", coreDir);
        MavenProject api = createProject("api", apiDir);

        ReactorModel model = ReactorModel.build(List.of(root, core, api));

        List<MavenProject> subtree = model.collectSubtree(model.root);
        assertThat(subtree).hasSize(3);
        assertThat(subtree).extracting(MavenProject::getArtifactId).containsExactly("parent", "core", "api");
    }

    @Test
    void collectSubtreeFromChild() throws IOException {
        Path coreDir = subdir(tempDir, "core-sub2");
        Path modelDir = Files.createDirectories(coreDir.resolve("model-sub"));
        Path apiDir = subdir(tempDir, "api-sub2");

        MavenProject root = createProject("parent", tempDir);
        MavenProject core = createProject("core", coreDir);
        MavenProject coreModel = createProject("model", modelDir);
        MavenProject api = createProject("api", apiDir);

        ReactorModel model = ReactorModel.build(List.of(root, core, coreModel, api));

        // Subtree from "core" should include core + model, but not api
        ReactorModel.ModuleNode coreNode = model.root.children.get(0);
        List<MavenProject> subtree = model.collectSubtree(coreNode);
        assertThat(subtree).hasSize(2);
        assertThat(subtree).extracting(MavenProject::getArtifactId).containsExactly("core", "model");
    }

    @Test
    void collectSubtreeLeafNode() throws IOException {
        Path childDir = subdir(tempDir, "leaf-child");

        MavenProject root = createProject("parent", tempDir);
        MavenProject child = createProject("child", childDir);

        ReactorModel model = ReactorModel.build(List.of(root, child));

        ReactorModel.ModuleNode leafNode = model.root.children.get(0);
        List<MavenProject> subtree = model.collectSubtree(leafNode);
        assertThat(subtree).hasSize(1);
        assertThat(subtree.get(0).getArtifactId()).isEqualTo("child");
    }

    @Test
    void moduleNodeDefaultExpansion() {
        MavenProject project = createProject("myapp", tempDir);

        ReactorModel.ModuleNode depth0 = new ReactorModel.ModuleNode(project, 0);
        assertThat(depth0.expanded).isTrue();

        ReactorModel.ModuleNode depth1 = new ReactorModel.ModuleNode(project, 1);
        assertThat(depth1.expanded).isTrue();

        ReactorModel.ModuleNode depth2 = new ReactorModel.ModuleNode(project, 2);
        assertThat(depth2.expanded).isFalse();
    }
}
