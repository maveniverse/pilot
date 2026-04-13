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
import static org.assertj.core.api.Assertions.assertThatNoException;

import dev.tamboui.terminal.Terminal;
import dev.tamboui.terminal.TestBackend;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReactorUpdatesTuiTest {

    @TempDir
    Path tempDir;

    private Path subdir(String name) throws IOException {
        return Files.createDirectories(tempDir.resolve(name));
    }

    private MavenProject createProject(String groupId, String artifactId, String version, Path basedir) {
        Model model = new Model();
        model.setGroupId(groupId);
        model.setArtifactId(artifactId);
        model.setVersion(version);

        Model originalModel = new Model();
        originalModel.setGroupId(groupId);
        originalModel.setArtifactId(artifactId);
        originalModel.setVersion(version);

        MavenProject project = new MavenProject(model);
        project.setOriginalModel(originalModel);
        project.setFile(basedir.resolve("pom.xml").toFile());
        return project;
    }

    private Dependency dep(String groupId, String artifactId, String version) {
        Dependency d = new Dependency();
        d.setGroupId(groupId);
        d.setArtifactId(artifactId);
        d.setVersion(version);
        return d;
    }

    private ReactorUpdatesTui createTui(ReactorCollector.CollectionResult result, List<MavenProject> projects) {
        ReactorModel model = ReactorModel.build(projects);
        return new ReactorUpdatesTui(result, model, "com.example:app:1.0", (g, a) -> List.of());
    }

    private void renderFrame(ReactorUpdatesTui tui) {
        assertThatNoException().isThrownBy(() -> {
            var terminal = new Terminal<>(new TestBackend(120, 30));
            terminal.draw(tui::render);
        });
    }

    @Test
    void duplicatePropertyNamesDetected() throws IOException {
        Path dir1 = subdir("parent");
        Path dir2 = subdir("child");

        MavenProject parent = createProject("com.example", "parent-bom", "1.0", dir1);
        Properties parentProps = new Properties();
        parentProps.setProperty("lib.version", "1.0");
        parent.getOriginalModel().setProperties(parentProps);
        parent.getModel().addDependency(dep("com.example", "lib-a", "1.0"));
        parent.getOriginalModel().addDependency(dep("com.example", "lib-a", "${lib.version}"));

        MavenProject child = createProject("com.example", "child-bom", "1.0", dir2);
        Properties childProps = new Properties();
        childProps.setProperty("lib.version", "2.0");
        child.getOriginalModel().setProperties(childProps);
        child.getModel().addDependency(dep("com.example", "lib-b", "2.0"));
        child.getOriginalModel().addDependency(dep("com.example", "lib-b", "${lib.version}"));

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(parent, child));
        assertThat(result.propertyGroups).hasSize(2);

        var tui = createTui(result, List.of(parent, child));

        // Set up updates so groups appear in display rows
        for (var dep : result.allDependencies) {
            dep.newestVersion = "3.0";
            dep.updateType = VersionComparator.UpdateType.MAJOR;
        }
        for (var group : result.propertyGroups) {
            group.newestVersion = "3.0";
            group.updateType = VersionComparator.UpdateType.MAJOR;
        }

        tui.buildDisplayRows();

        assertThat(tui.duplicatePropertyNames).containsExactly("lib.version");
    }

    @Test
    void noDuplicatesWhenPropertyNamesAreUnique() throws IOException {
        Path dir1 = subdir("mod1");
        Path dir2 = subdir("mod2");

        MavenProject p1 = createProject("com.example", "mod1", "1.0", dir1);
        Properties props1 = new Properties();
        props1.setProperty("jackson.version", "2.17.0");
        p1.getOriginalModel().setProperties(props1);
        p1.getModel().addDependency(dep("com.fasterxml", "jackson-core", "2.17.0"));
        p1.getOriginalModel().addDependency(dep("com.fasterxml", "jackson-core", "${jackson.version}"));

        MavenProject p2 = createProject("com.example", "mod2", "1.0", dir2);
        Properties props2 = new Properties();
        props2.setProperty("slf4j.version", "2.0.9");
        p2.getOriginalModel().setProperties(props2);
        p2.getModel().addDependency(dep("org.slf4j", "slf4j-api", "2.0.9"));
        p2.getOriginalModel().addDependency(dep("org.slf4j", "slf4j-api", "${slf4j.version}"));

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(p1, p2));

        var tui = createTui(result, List.of(p1, p2));

        for (var dep : result.allDependencies) {
            dep.newestVersion = "3.0";
            dep.updateType = VersionComparator.UpdateType.MAJOR;
        }
        for (var group : result.propertyGroups) {
            group.newestVersion = "3.0";
            group.updateType = VersionComparator.UpdateType.MAJOR;
        }

        tui.buildDisplayRows();

        assertThat(tui.duplicatePropertyNames).isEmpty();
    }

    @Test
    void buildDisplayRowsCreatesGroupHeadersAndDeps() throws IOException {
        Path dir = subdir("project");

        MavenProject project = createProject("com.example", "app", "1.0", dir);
        Properties props = new Properties();
        props.setProperty("jackson.version", "2.17.0");
        project.getOriginalModel().setProperties(props);
        project.getModel().addDependency(dep("com.fasterxml", "jackson-core", "2.17.0"));
        project.getModel().addDependency(dep("com.fasterxml", "jackson-databind", "2.17.0"));
        project.getOriginalModel().addDependency(dep("com.fasterxml", "jackson-core", "${jackson.version}"));
        project.getOriginalModel().addDependency(dep("com.fasterxml", "jackson-databind", "${jackson.version}"));

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        assertThat(result.propertyGroups).hasSize(1);
        assertThat(result.propertyGroups.get(0).dependencies).hasSize(2);

        var tui = createTui(result, List.of(project));

        for (var dep : result.allDependencies) {
            dep.newestVersion = "2.18.0";
            dep.updateType = VersionComparator.UpdateType.MINOR;
        }
        result.propertyGroups.get(0).newestVersion = "2.18.0";
        result.propertyGroups.get(0).updateType = VersionComparator.UpdateType.MINOR;

        tui.buildDisplayRows();

        // 1 group header + 2 dependencies
        assertThat(tui.displayRows).hasSize(3);
        assertThat(tui.displayRows.get(0).isGroupHeader()).isTrue();
        assertThat(tui.displayRows.get(0).propertyGroup.propertyName).isEqualTo("jackson.version");
        assertThat(tui.displayRows.get(1).isGroupHeader()).isFalse();
        assertThat(tui.displayRows.get(2).isGroupHeader()).isFalse();
    }

    @Test
    void buildDisplayRowsEmptyWhenNoUpdates() {
        MavenProject project = createProject("com.example", "app", "1.0", tempDir);
        project.getModel().addDependency(dep("com.example", "lib", "1.0"));
        project.getOriginalModel().addDependency(dep("com.example", "lib", "1.0"));

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));

        var tui = createTui(result, List.of(project));
        tui.buildDisplayRows();

        assertThat(tui.displayRows).isEmpty();
    }

    @Test
    void showDetailsDefaultsToTrue() {
        MavenProject project = createProject("com.example", "app", "1.0", tempDir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));

        var tui = createTui(result, List.of(project));

        assertThat(tui.showDetails).isTrue();
    }

    @Test
    void showDetailsCanBeToggledViaKeyHandler() {
        MavenProject project = createProject("com.example", "app", "1.0", tempDir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));

        var tui = createTui(result, List.of(project));

        assertThat(tui.showDetails).isTrue();
        tui.handleEvent(KeyEvent.ofChar('i'), null);
        assertThat(tui.showDetails).isFalse();
        tui.handleEvent(KeyEvent.ofChar('i'), null);
        assertThat(tui.showDetails).isTrue();
    }

    @Test
    void updateStatusIfDoneComputesPropertyGroupUpdates() throws IOException {
        Path dir = subdir("project2");

        MavenProject project = createProject("com.example", "app", "1.0", dir);
        Properties props = new Properties();
        props.setProperty("jackson.version", "2.17.0");
        project.getOriginalModel().setProperties(props);
        project.getModel().addDependency(dep("com.fasterxml", "jackson-core", "2.17.0"));
        project.getOriginalModel().addDependency(dep("com.fasterxml", "jackson-core", "${jackson.version}"));

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));

        var tui = createTui(result, List.of(project));

        // Simulate version resolution for the dependency
        var dep = result.allDependencies.get(0);
        dep.newestVersion = "2.18.0";
        dep.updateType = VersionComparator.UpdateType.MINOR;

        // Simulate all dependencies loaded
        tui.loadedCount = result.allDependencies.size() - 1;
        tui.loading = true;

        // Trigger updateStatusIfDone by incrementing loadedCount to match total
        tui.loadedCount = result.allDependencies.size();
        tui.updateStatusIfDone();

        // After completion, loading should be false and group should have computed update
        assertThat(tui.loading).isFalse();
        assertThat(result.propertyGroups.get(0).newestVersion).isEqualTo("2.18.0");
        assertThat(result.propertyGroups.get(0).updateType).isEqualTo(VersionComparator.UpdateType.MINOR);
        assertThat(tui.status).contains("update");
    }

    @Test
    void buildDisplayRowsWithFilterShowsOnlyMatchingUpdateType() throws IOException {
        Path dir = subdir("project3");

        MavenProject project = createProject("com.example", "app", "1.0", dir);

        // Direct dependency (ungrouped)
        project.getModel().addDependency(dep("com.example", "lib-a", "1.0"));
        project.getOriginalModel().addDependency(dep("com.example", "lib-a", "1.0"));
        project.getModel().addDependency(dep("com.example", "lib-b", "1.0"));
        project.getOriginalModel().addDependency(dep("com.example", "lib-b", "1.0"));

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));

        var tui = createTui(result, List.of(project));

        // Give lib-a a PATCH update
        result.ungroupedDependencies.get(0).newestVersion = "1.0.1";
        result.ungroupedDependencies.get(0).updateType = VersionComparator.UpdateType.PATCH;

        // Give lib-b a MAJOR update
        result.ungroupedDependencies.get(1).newestVersion = "2.0.0";
        result.ungroupedDependencies.get(1).updateType = VersionComparator.UpdateType.MAJOR;

        // Default filter: ALL - should show both
        tui.buildDisplayRows();
        assertThat(tui.displayRows).hasSize(2);

        // Filter PATCH (key '2') - only lib-a
        tui.handleEvent(KeyEvent.ofChar('2'), null);
        assertThat(tui.displayRows).hasSize(1);
        assertThat(tui.displayRows.get(0).dependency.artifactId).isEqualTo("lib-a");

        // Filter MAJOR (key '4') - only lib-b
        tui.handleEvent(KeyEvent.ofChar('4'), null);
        assertThat(tui.displayRows).hasSize(1);
        assertThat(tui.displayRows.get(0).dependency.artifactId).isEqualTo("lib-b");

        // Filter ALL (key '1') - both again
        tui.handleEvent(KeyEvent.ofChar('1'), null);
        assertThat(tui.displayRows).hasSize(2);
    }

    @Test
    void duplicatePropertyNamesFromSameOriginNotDetectedAsDuplicate() throws IOException {
        Path dir = subdir("single");

        MavenProject project = createProject("com.example", "app", "1.0", dir);
        Properties props = new Properties();
        props.setProperty("jackson.version", "2.17.0");
        project.getOriginalModel().setProperties(props);
        project.getModel().addDependency(dep("com.fasterxml", "jackson-core", "2.17.0"));
        project.getModel().addDependency(dep("com.fasterxml", "jackson-databind", "2.17.0"));
        project.getOriginalModel().addDependency(dep("com.fasterxml", "jackson-core", "${jackson.version}"));
        project.getOriginalModel().addDependency(dep("com.fasterxml", "jackson-databind", "${jackson.version}"));

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        // Same property from same origin → only 1 group
        assertThat(result.propertyGroups).hasSize(1);

        var tui = createTui(result, List.of(project));

        result.propertyGroups.get(0).newestVersion = "2.18.0";
        result.propertyGroups.get(0).updateType = VersionComparator.UpdateType.MINOR;
        for (var dep : result.allDependencies) {
            dep.newestVersion = "2.18.0";
            dep.updateType = VersionComparator.UpdateType.MINOR;
        }

        tui.buildDisplayRows();

        // Same property name from same origin → not a duplicate
        assertThat(tui.duplicatePropertyNames).isEmpty();
    }

    @Test
    void reactorRowIsGroupHeader() {
        var group = new ReactorCollector.PropertyGroup("lib.version", "${lib.version}", "1.0", null);
        var row = ReactorUpdatesTui.ReactorRow.group(group);
        assertThat(row.isGroupHeader()).isTrue();
        assertThat(row.propertyGroup).isSameAs(group);
        assertThat(row.dependency).isNull();
    }

    @Test
    void reactorRowIsDependency() {
        var dep = new ReactorCollector.AggregatedDependency("g", "a");
        var row = ReactorUpdatesTui.ReactorRow.dep(dep);
        assertThat(row.isGroupHeader()).isFalse();
        assertThat(row.dependency).isSameAs(dep);
        assertThat(row.propertyGroup).isNull();
    }

    // -- Rendering tests (exercise code paths for coverage) --

    @Test
    void renderWithDetailPaneForPropertyGroup() throws IOException {
        Path dir = subdir("render1");

        MavenProject project = createProject("com.example", "app", "1.0", dir);
        Properties props = new Properties();
        props.setProperty("jackson.version", "2.17.0");
        project.getOriginalModel().setProperties(props);
        project.getModel().addDependency(dep("com.fasterxml", "jackson-core", "2.17.0"));
        project.getModel().addDependency(dep("com.fasterxml", "jackson-databind", "2.17.0"));
        project.getOriginalModel().addDependency(dep("com.fasterxml", "jackson-core", "${jackson.version}"));
        project.getOriginalModel().addDependency(dep("com.fasterxml", "jackson-databind", "${jackson.version}"));

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        var tui = createTui(result, List.of(project));

        for (var dep : result.allDependencies) {
            dep.newestVersion = "2.18.0";
            dep.updateType = VersionComparator.UpdateType.MINOR;
        }
        result.propertyGroups.get(0).newestVersion = "2.18.0";
        result.propertyGroups.get(0).updateType = VersionComparator.UpdateType.MINOR;

        tui.loading = false;
        tui.buildDisplayRows();

        // Selected row 0 = property group header → detail pane shows group info
        renderFrame(tui);
    }

    @Test
    void renderWithDetailPaneForDependency() throws IOException {
        Path dir = subdir("render2");

        MavenProject project = createProject("com.example", "app", "1.0", dir);
        Properties props = new Properties();
        props.setProperty("jackson.version", "2.17.0");
        project.getOriginalModel().setProperties(props);
        project.getModel().addDependency(dep("com.fasterxml", "jackson-core", "2.17.0"));
        project.getOriginalModel().addDependency(dep("com.fasterxml", "jackson-core", "${jackson.version}"));

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        var tui = createTui(result, List.of(project));

        result.allDependencies.get(0).newestVersion = "2.18.0";
        result.allDependencies.get(0).updateType = VersionComparator.UpdateType.MINOR;
        result.propertyGroups.get(0).newestVersion = "2.18.0";
        result.propertyGroups.get(0).updateType = VersionComparator.UpdateType.MINOR;

        tui.loading = false;
        tui.buildDisplayRows();

        // Select row 1 = dependency under the group → detail pane shows dep info
        assertThat(tui.displayRows).hasSizeGreaterThanOrEqualTo(2);
        // Move selection down to the dependency row via key handler
        tui.handleEvent(KeyEvent.ofKey(KeyCode.DOWN), null);
        renderFrame(tui);
    }

    @Test
    void renderWithDetailPaneHidden() throws IOException {
        Path dir = subdir("render3");

        MavenProject project = createProject("com.example", "app", "1.0", dir);
        project.getModel().addDependency(dep("com.example", "lib", "1.0"));
        project.getOriginalModel().addDependency(dep("com.example", "lib", "1.0"));

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        var tui = createTui(result, List.of(project));

        result.ungroupedDependencies.get(0).newestVersion = "2.0";
        result.ungroupedDependencies.get(0).updateType = VersionComparator.UpdateType.MAJOR;

        tui.loading = false;
        tui.showDetails = false;
        tui.buildDisplayRows();

        renderFrame(tui);
    }

    @Test
    void renderWithDuplicatePropertyNames() throws IOException {
        Path dir1 = subdir("render4a");
        Path dir2 = subdir("render4b");

        MavenProject p1 = createProject("com.example", "bom-a", "1.0", dir1);
        Properties props1 = new Properties();
        props1.setProperty("lib.version", "1.0");
        p1.getOriginalModel().setProperties(props1);
        p1.getModel().addDependency(dep("com.example", "lib-a", "1.0"));
        p1.getOriginalModel().addDependency(dep("com.example", "lib-a", "${lib.version}"));

        MavenProject p2 = createProject("com.example", "bom-b", "1.0", dir2);
        Properties props2 = new Properties();
        props2.setProperty("lib.version", "2.0");
        p2.getOriginalModel().setProperties(props2);
        p2.getModel().addDependency(dep("com.example", "lib-b", "2.0"));
        p2.getOriginalModel().addDependency(dep("com.example", "lib-b", "${lib.version}"));

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(p1, p2));
        var tui = createTui(result, List.of(p1, p2));

        for (var dep : result.allDependencies) {
            dep.newestVersion = "3.0";
            dep.updateType = VersionComparator.UpdateType.MAJOR;
        }
        for (var group : result.propertyGroups) {
            group.newestVersion = "3.0";
            group.updateType = VersionComparator.UpdateType.MAJOR;
        }

        tui.loading = false;
        tui.buildDisplayRows();

        assertThat(tui.duplicatePropertyNames).containsExactly("lib.version");
        renderFrame(tui);
    }

    @Test
    void renderEmptyDepsView() {
        MavenProject project = createProject("com.example", "app", "1.0", tempDir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));

        var tui = createTui(result, List.of(project));
        tui.loading = false;
        tui.buildDisplayRows();

        renderFrame(tui);
    }

    @Test
    void renderLoadingState() {
        MavenProject project = createProject("com.example", "app", "1.0", tempDir);
        project.getModel().addDependency(dep("com.example", "lib", "1.0"));
        project.getOriginalModel().addDependency(dep("com.example", "lib", "1.0"));

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        var tui = createTui(result, List.of(project));
        tui.loading = true;
        tui.loadedCount = 0;

        renderFrame(tui);
    }

    @Test
    void renderWithUngroupedDependency() throws IOException {
        Path dir = subdir("render5");

        MavenProject project = createProject("com.example", "app", "1.0", dir);
        project.getModel().addDependency(dep("com.example", "lib", "1.0"));
        project.getOriginalModel().addDependency(dep("com.example", "lib", "1.0"));

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        var tui = createTui(result, List.of(project));

        result.ungroupedDependencies.get(0).newestVersion = "1.0.1";
        result.ungroupedDependencies.get(0).updateType = VersionComparator.UpdateType.PATCH;

        tui.loading = false;
        tui.buildDisplayRows();

        renderFrame(tui);
    }

    @Test
    void renderWithSelectedUngroupedDependency() throws IOException {
        Path dir = subdir("render6");

        MavenProject project = createProject("com.example", "app", "1.0", dir);
        project.getModel().addDependency(dep("com.example", "lib", "1.0"));
        project.getOriginalModel().addDependency(dep("com.example", "lib", "1.0"));

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        var tui = createTui(result, List.of(project));

        var dep = result.ungroupedDependencies.get(0);
        dep.newestVersion = "2.0.0";
        dep.updateType = VersionComparator.UpdateType.MAJOR;
        dep.selected = true;

        tui.loading = false;
        tui.buildDisplayRows();

        // detail pane for ungrouped dependency (no property info)
        renderFrame(tui);
    }

    @Test
    void renderPropertyGroupWithoutUpdate() throws IOException {
        Path dir = subdir("render7");

        MavenProject project = createProject("com.example", "app", "1.0", dir);
        Properties props = new Properties();
        props.setProperty("jackson.version", "2.17.0");
        project.getOriginalModel().setProperties(props);
        project.getModel().addDependency(dep("com.fasterxml", "jackson-core", "2.17.0"));
        project.getOriginalModel().addDependency(dep("com.fasterxml", "jackson-core", "${jackson.version}"));

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        var tui = createTui(result, List.of(project));

        // Dependency has update but group doesn't have a computed newest yet
        result.allDependencies.get(0).newestVersion = "2.18.0";
        result.allDependencies.get(0).updateType = VersionComparator.UpdateType.MINOR;
        // Group has no newestVersion → group.hasUpdate() is false but deps do

        tui.loading = false;
        tui.buildDisplayRows();

        if (!tui.displayRows.isEmpty()) {
            renderFrame(tui);
        }
    }

    @Test
    void renderWithManagedDependencyScopes() throws IOException {
        Path dir = subdir("render8");

        MavenProject project = createProject("com.example", "app", "1.0", dir);
        var d = dep("com.example", "lib", "1.0");
        d.setScope("compile");
        project.getModel().addDependency(d);
        var od = dep("com.example", "lib", "1.0");
        od.setScope("compile");
        project.getOriginalModel().addDependency(od);

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        var tui = createTui(result, List.of(project));

        result.ungroupedDependencies.get(0).newestVersion = "1.1.0";
        result.ungroupedDependencies.get(0).updateType = VersionComparator.UpdateType.MINOR;

        tui.loading = false;
        tui.buildDisplayRows();

        renderFrame(tui);
    }
}
