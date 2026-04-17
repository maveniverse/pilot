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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdatesTuiTest {

    @TempDir
    Path tempDir;

    private Path subdir(String name) throws IOException {
        return Files.createDirectories(tempDir.resolve(name));
    }

    private PilotProject createProject(String groupId, String artifactId, String version, Path basedir) {
        return createProject(groupId, artifactId, version, basedir, List.of(), List.of(), List.of(), List.of());
    }

    private PilotProject createProject(
            String groupId,
            String artifactId,
            String version,
            Path basedir,
            List<PilotProject.Dep> deps,
            List<PilotProject.Dep> mgmtDeps,
            List<PilotProject.Dep> origDeps,
            List<PilotProject.Dep> origMgmtDeps) {
        return createProject(
                groupId, artifactId, version, basedir, deps, mgmtDeps, origDeps, origMgmtDeps, new Properties());
    }

    private PilotProject createProject(
            String groupId,
            String artifactId,
            String version,
            Path basedir,
            List<PilotProject.Dep> deps,
            List<PilotProject.Dep> mgmtDeps,
            List<PilotProject.Dep> origDeps,
            List<PilotProject.Dep> origMgmtDeps,
            Properties origProps) {
        return new PilotProject(
                groupId,
                artifactId,
                version,
                "jar",
                basedir,
                basedir.resolve("pom.xml"),
                deps,
                mgmtDeps,
                origDeps,
                origMgmtDeps,
                origProps,
                null,
                null);
    }

    private PilotProject.Dep dep(String groupId, String artifactId, String version) {
        return new PilotProject.Dep(groupId, artifactId, version);
    }

    private PilotProject.Dep dep(String groupId, String artifactId, String version, String scope) {
        return new PilotProject.Dep(groupId, artifactId, version, scope, null);
    }

    private UpdatesTui createTui(ReactorCollector.CollectionResult result, List<PilotProject> projects) {
        ReactorModel model = ReactorModel.build(projects);
        return new UpdatesTui(result, model, "com.example:app:1.0", (g, a) -> List.of());
    }

    private void renderFrame(UpdatesTui tui) {
        assertThatNoException().isThrownBy(() -> {
            var terminal = new Terminal<>(new TestBackend(120, 30));
            terminal.draw(tui::renderStandalone);
        });
    }

    @Test
    void duplicatePropertyNamesDetected() throws IOException {
        Path dir1 = subdir("parent");
        Path dir2 = subdir("child");

        Properties parentProps = new Properties();
        parentProps.setProperty("lib.version", "1.0");
        PilotProject parent = createProject(
                "com.example",
                "parent-bom",
                "1.0",
                dir1,
                List.of(dep("com.example", "lib-a", "1.0")),
                List.of(),
                List.of(dep("com.example", "lib-a", "${lib.version}")),
                List.of(),
                parentProps);

        Properties childProps = new Properties();
        childProps.setProperty("lib.version", "2.0");
        PilotProject child = createProject(
                "com.example",
                "child-bom",
                "1.0",
                dir2,
                List.of(dep("com.example", "lib-b", "2.0")),
                List.of(),
                List.of(dep("com.example", "lib-b", "${lib.version}")),
                List.of(),
                childProps);

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

        Properties props1 = new Properties();
        props1.setProperty("jackson.version", "2.17.0");
        PilotProject p1 = createProject(
                "com.example",
                "mod1",
                "1.0",
                dir1,
                List.of(dep("com.fasterxml", "jackson-core", "2.17.0")),
                List.of(),
                List.of(dep("com.fasterxml", "jackson-core", "${jackson.version}")),
                List.of(),
                props1);

        Properties props2 = new Properties();
        props2.setProperty("slf4j.version", "2.0.9");
        PilotProject p2 = createProject(
                "com.example",
                "mod2",
                "1.0",
                dir2,
                List.of(dep("org.slf4j", "slf4j-api", "2.0.9")),
                List.of(),
                List.of(dep("org.slf4j", "slf4j-api", "${slf4j.version}")),
                List.of(),
                props2);

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

        Properties props = new Properties();
        props.setProperty("jackson.version", "2.17.0");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(
                        dep("com.fasterxml", "jackson-core", "2.17.0"),
                        dep("com.fasterxml", "jackson-databind", "2.17.0")),
                List.of(),
                List.of(
                        dep("com.fasterxml", "jackson-core", "${jackson.version}"),
                        dep("com.fasterxml", "jackson-databind", "${jackson.version}")),
                List.of(),
                props);

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

        // Groups are collapsed by default — only header visible
        assertThat(tui.displayRows).hasSize(1);
        assertThat(tui.displayRows.get(0).isGroupHeader()).isTrue();
        assertThat(tui.displayRows.get(0).propertyGroup.propertyName).isEqualTo("jackson.version");

        // After expanding, children become visible
        result.propertyGroups.get(0).expanded = true;
        tui.buildDisplayRows();
        assertThat(tui.displayRows).hasSize(3);
        assertThat(tui.displayRows.get(1).isGroupHeader()).isFalse();
        assertThat(tui.displayRows.get(2).isGroupHeader()).isFalse();
    }

    @Test
    void buildDisplayRowsEmptyWhenNoUpdates() {
        PilotProject.Dep d = dep("com.example", "lib", "1.0");
        PilotProject project =
                createProject("com.example", "app", "1.0", tempDir, List.of(d), List.of(), List.of(d), List.of());

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));

        var tui = createTui(result, List.of(project));
        tui.buildDisplayRows();

        assertThat(tui.displayRows).isEmpty();
    }

    @Test
    void showDetailsDefaultsToTrue() {
        PilotProject project = createProject("com.example", "app", "1.0", tempDir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));

        var tui = createTui(result, List.of(project));

        assertThat(tui.showDetails).isTrue();
    }

    @Test
    void showDetailsCanBeToggledViaKeyHandler() {
        PilotProject project = createProject("com.example", "app", "1.0", tempDir);
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

        Properties props = new Properties();
        props.setProperty("jackson.version", "2.17.0");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(dep("com.fasterxml", "jackson-core", "2.17.0")),
                List.of(),
                List.of(dep("com.fasterxml", "jackson-core", "${jackson.version}")),
                List.of(),
                props);

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

        PilotProject.Dep da = dep("com.example", "lib-a", "1.0");
        PilotProject.Dep db = dep("com.example", "lib-b", "1.0");
        PilotProject project =
                createProject("com.example", "app", "1.0", dir, List.of(da, db), List.of(), List.of(da, db), List.of());

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

        // Filter PATCH (f cycles ALL→PATCH) - only lib-a
        tui.handleEvent(KeyEvent.ofChar('f'), null);
        assertThat(tui.displayRows).hasSize(1);
        assertThat(tui.displayRows.get(0).dependency.artifactId).isEqualTo("lib-a");

        // Filter MAJOR (f cycles PATCH→MINOR→MAJOR) - only lib-b
        tui.handleEvent(KeyEvent.ofChar('f'), null);
        tui.handleEvent(KeyEvent.ofChar('f'), null);
        assertThat(tui.displayRows).hasSize(1);
        assertThat(tui.displayRows.get(0).dependency.artifactId).isEqualTo("lib-b");

        // Filter ALL (f cycles MAJOR→ALL) - both again
        tui.handleEvent(KeyEvent.ofChar('f'), null);
        assertThat(tui.displayRows).hasSize(2);
    }

    @Test
    void duplicatePropertyNamesFromSameOriginNotDetectedAsDuplicate() throws IOException {
        Path dir = subdir("single");

        Properties props = new Properties();
        props.setProperty("jackson.version", "2.17.0");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(
                        dep("com.fasterxml", "jackson-core", "2.17.0"),
                        dep("com.fasterxml", "jackson-databind", "2.17.0")),
                List.of(),
                List.of(
                        dep("com.fasterxml", "jackson-core", "${jackson.version}"),
                        dep("com.fasterxml", "jackson-databind", "${jackson.version}")),
                List.of(),
                props);

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        // Same property from same origin -> only 1 group
        assertThat(result.propertyGroups).hasSize(1);

        var tui = createTui(result, List.of(project));

        result.propertyGroups.get(0).newestVersion = "2.18.0";
        result.propertyGroups.get(0).updateType = VersionComparator.UpdateType.MINOR;
        for (var dep : result.allDependencies) {
            dep.newestVersion = "2.18.0";
            dep.updateType = VersionComparator.UpdateType.MINOR;
        }

        tui.buildDisplayRows();

        // Same property name from same origin -> not a duplicate
        assertThat(tui.duplicatePropertyNames).isEmpty();
    }

    @Test
    void reactorRowIsGroupHeader() {
        var group = new ReactorCollector.PropertyGroup("lib.version", "${lib.version}", "1.0", null);
        var row = UpdatesTui.ReactorRow.group(group);
        assertThat(row.isGroupHeader()).isTrue();
        assertThat(row.propertyGroup).isSameAs(group);
        assertThat(row.dependency).isNull();
    }

    @Test
    void reactorRowIsDependency() {
        var dep = new ReactorCollector.AggregatedDependency("g", "a");
        var row = UpdatesTui.ReactorRow.dep(dep);
        assertThat(row.isGroupHeader()).isFalse();
        assertThat(row.dependency).isSameAs(dep);
        assertThat(row.propertyGroup).isNull();
    }

    // -- Rendering tests (exercise code paths for coverage) --

    @Test
    void renderWithDetailPaneForPropertyGroup() throws IOException {
        Path dir = subdir("render1");

        Properties props = new Properties();
        props.setProperty("jackson.version", "2.17.0");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(
                        dep("com.fasterxml", "jackson-core", "2.17.0"),
                        dep("com.fasterxml", "jackson-databind", "2.17.0")),
                List.of(),
                List.of(
                        dep("com.fasterxml", "jackson-core", "${jackson.version}"),
                        dep("com.fasterxml", "jackson-databind", "${jackson.version}")),
                List.of(),
                props);

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

        // Selected row 0 = property group header -> detail pane shows group info
        renderFrame(tui);
    }

    @Test
    void renderWithDetailPaneForDependency() throws IOException {
        Path dir = subdir("render2");

        Properties props = new Properties();
        props.setProperty("jackson.version", "2.17.0");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(dep("com.fasterxml", "jackson-core", "2.17.0")),
                List.of(),
                List.of(dep("com.fasterxml", "jackson-core", "${jackson.version}")),
                List.of(),
                props);

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        var tui = createTui(result, List.of(project));

        result.allDependencies.get(0).newestVersion = "2.18.0";
        result.allDependencies.get(0).updateType = VersionComparator.UpdateType.MINOR;
        result.propertyGroups.get(0).newestVersion = "2.18.0";
        result.propertyGroups.get(0).updateType = VersionComparator.UpdateType.MINOR;
        result.propertyGroups.get(0).expanded = true;

        tui.loading = false;
        tui.buildDisplayRows();

        // Select row 1 = dependency under the group -> detail pane shows dep info
        assertThat(tui.displayRows).hasSizeGreaterThanOrEqualTo(2);
        // Move selection down to the dependency row via key handler
        tui.handleEvent(KeyEvent.ofKey(KeyCode.DOWN), null);
        renderFrame(tui);
    }

    @Test
    void renderWithDetailPaneHidden() throws IOException {
        Path dir = subdir("render3");

        PilotProject.Dep d = dep("com.example", "lib", "1.0");
        PilotProject project =
                createProject("com.example", "app", "1.0", dir, List.of(d), List.of(), List.of(d), List.of());

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

        Properties props1 = new Properties();
        props1.setProperty("lib.version", "1.0");
        PilotProject p1 = createProject(
                "com.example",
                "bom-a",
                "1.0",
                dir1,
                List.of(dep("com.example", "lib-a", "1.0")),
                List.of(),
                List.of(dep("com.example", "lib-a", "${lib.version}")),
                List.of(),
                props1);

        Properties props2 = new Properties();
        props2.setProperty("lib.version", "2.0");
        PilotProject p2 = createProject(
                "com.example",
                "bom-b",
                "1.0",
                dir2,
                List.of(dep("com.example", "lib-b", "2.0")),
                List.of(),
                List.of(dep("com.example", "lib-b", "${lib.version}")),
                List.of(),
                props2);

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
        PilotProject project = createProject("com.example", "app", "1.0", tempDir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));

        var tui = createTui(result, List.of(project));
        tui.loading = false;
        tui.buildDisplayRows();

        renderFrame(tui);
    }

    @Test
    void renderLoadingState() {
        PilotProject.Dep d = dep("com.example", "lib", "1.0");
        PilotProject project =
                createProject("com.example", "app", "1.0", tempDir, List.of(d), List.of(), List.of(d), List.of());

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        var tui = createTui(result, List.of(project));
        tui.loading = true;
        tui.loadedCount = 0;

        renderFrame(tui);
    }

    @Test
    void renderWithUngroupedDependency() throws IOException {
        Path dir = subdir("render5");

        PilotProject.Dep d = dep("com.example", "lib", "1.0");
        PilotProject project =
                createProject("com.example", "app", "1.0", dir, List.of(d), List.of(), List.of(d), List.of());

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

        PilotProject.Dep d = dep("com.example", "lib", "1.0");
        PilotProject project =
                createProject("com.example", "app", "1.0", dir, List.of(d), List.of(), List.of(d), List.of());

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

        Properties props = new Properties();
        props.setProperty("jackson.version", "2.17.0");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(dep("com.fasterxml", "jackson-core", "2.17.0")),
                List.of(),
                List.of(dep("com.fasterxml", "jackson-core", "${jackson.version}")),
                List.of(),
                props);

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        var tui = createTui(result, List.of(project));

        // Dependency has update but group doesn't have a computed newest yet
        result.allDependencies.get(0).newestVersion = "2.18.0";
        result.allDependencies.get(0).updateType = VersionComparator.UpdateType.MINOR;
        // Group has no newestVersion -> group.hasUpdate() is false but deps do

        tui.loading = false;
        tui.buildDisplayRows();

        if (!tui.displayRows.isEmpty()) {
            renderFrame(tui);
        }
    }

    // -- Dependencies view: left/right navigation tests --

    private UpdatesTui createGroupTui(Path dir) throws IOException {
        Properties props = new Properties();
        props.setProperty("jackson.version", "2.17.0");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(
                        dep("com.fasterxml", "jackson-core", "2.17.0"),
                        dep("com.fasterxml", "jackson-databind", "2.17.0")),
                List.of(),
                List.of(
                        dep("com.fasterxml", "jackson-core", "${jackson.version}"),
                        dep("com.fasterxml", "jackson-databind", "${jackson.version}")),
                List.of(),
                props);

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        var tui = createTui(result, List.of(project));

        for (var d : result.allDependencies) {
            d.newestVersion = "2.18.0";
            d.updateType = VersionComparator.UpdateType.MINOR;
        }
        result.propertyGroups.get(0).newestVersion = "2.18.0";
        result.propertyGroups.get(0).updateType = VersionComparator.UpdateType.MINOR;

        tui.buildDisplayRows();
        return tui;
    }

    @Test
    void groupsCollapsedByDefault() throws IOException {
        var tui = createGroupTui(subdir("navDefault"));

        assertThat(tui.displayRows).hasSize(1);
        assertThat(tui.displayRows.get(0).isGroupHeader()).isTrue();
    }

    @Test
    void leftArrowCollapsesExpandedPropertyGroup() throws IOException {
        var tui = createGroupTui(subdir("navCollapse"));

        // Expand first
        tui.handleEvent(KeyEvent.ofKey(KeyCode.RIGHT), null);
        assertThat(tui.displayRows).hasSize(3);

        // LEFT → collapse
        tui.handleEvent(KeyEvent.ofKey(KeyCode.LEFT), null);
        assertThat(tui.displayRows).hasSize(1);
        assertThat(tui.displayRows.get(0).isGroupHeader()).isTrue();
    }

    @Test
    void rightArrowExpandsCollapsedPropertyGroup() throws IOException {
        var tui = createGroupTui(subdir("navExpand"));

        // Collapsed by default
        assertThat(tui.displayRows).hasSize(1);

        // RIGHT → expands
        tui.handleEvent(KeyEvent.ofKey(KeyCode.RIGHT), null);
        assertThat(tui.displayRows).hasSize(3);
    }

    @Test
    void rightArrowOnExpandedGroupMovesDown() throws IOException {
        var tui = createGroupTui(subdir("navDown"));

        // Expand first
        tui.handleEvent(KeyEvent.ofKey(KeyCode.RIGHT), null);
        // Group already expanded; RIGHT moves selection past header
        tui.handleEvent(KeyEvent.ofKey(KeyCode.RIGHT), null);
        // Now on child dep; LEFT jumps to parent header, LEFT again collapses
        tui.handleEvent(KeyEvent.ofKey(KeyCode.LEFT), null);
        tui.handleEvent(KeyEvent.ofKey(KeyCode.LEFT), null);
        assertThat(tui.displayRows).hasSize(1);
    }

    @Test
    void leftArrowOnChildDepJumpsToGroupHeader() throws IOException {
        var tui = createGroupTui(subdir("navChild"));

        // Expand first
        tui.handleEvent(KeyEvent.ofKey(KeyCode.RIGHT), null);
        // Move to child dep
        tui.handleEvent(KeyEvent.ofKey(KeyCode.DOWN), null);
        // LEFT → jump to parent group header
        tui.handleEvent(KeyEvent.ofKey(KeyCode.LEFT), null);
        // LEFT again → collapse (proves we're on the header)
        tui.handleEvent(KeyEvent.ofKey(KeyCode.LEFT), null);

        assertThat(tui.displayRows).hasSize(1);
    }

    @Test
    void leftArrowOnUngroupedDepDoesNotJumpToGroup() throws IOException {
        Path dir = subdir("navUngrouped");

        Properties props = new Properties();
        props.setProperty("jackson.version", "2.17.0");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(dep("com.fasterxml", "jackson-core", "2.17.0"), dep("com.example", "standalone-lib", "1.0")),
                List.of(),
                List.of(
                        dep("com.fasterxml", "jackson-core", "${jackson.version}"),
                        dep("com.example", "standalone-lib", "1.0")),
                List.of(),
                props);

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        var tui = createTui(result, List.of(project));

        for (var d : result.allDependencies) {
            d.newestVersion = "2.0.0";
            d.updateType = VersionComparator.UpdateType.MAJOR;
        }
        result.propertyGroups.get(0).newestVersion = "2.0.0";
        result.propertyGroups.get(0).updateType = VersionComparator.UpdateType.MAJOR;

        tui.buildDisplayRows();
        // 1 group header (collapsed) + 1 ungrouped dep
        assertThat(tui.displayRows).hasSize(2);

        // Move to ungrouped dep
        tui.handleEvent(KeyEvent.ofKey(KeyCode.DOWN), null);
        // LEFT should NOT jump to the group header (ungrouped dep has no parent)
        tui.handleEvent(KeyEvent.ofKey(KeyCode.LEFT), null);
        // Verify group is still collapsed (LEFT didn't jump to it and collapse it)
        assertThat(tui.displayRows).hasSize(2);
    }

    @Test
    void buildDisplayRowsRespectsCollapsedGroup() throws IOException {
        Path dir = subdir("navBuild");

        Properties props = new Properties();
        props.setProperty("jackson.version", "2.17.0");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(
                        dep("com.fasterxml", "jackson-core", "2.17.0"),
                        dep("com.fasterxml", "jackson-databind", "2.17.0")),
                List.of(),
                List.of(
                        dep("com.fasterxml", "jackson-core", "${jackson.version}"),
                        dep("com.fasterxml", "jackson-databind", "${jackson.version}")),
                List.of(),
                props);

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        var tui = createTui(result, List.of(project));

        for (var d : result.allDependencies) {
            d.newestVersion = "2.18.0";
            d.updateType = VersionComparator.UpdateType.MINOR;
        }
        result.propertyGroups.get(0).newestVersion = "2.18.0";
        result.propertyGroups.get(0).updateType = VersionComparator.UpdateType.MINOR;

        result.propertyGroups.get(0).expanded = false;
        tui.buildDisplayRows();

        assertThat(tui.displayRows).hasSize(1);
        assertThat(tui.displayRows.get(0).isGroupHeader()).isTrue();
    }

    // -- Modules view: left/right navigation tests --

    @Test
    void modulesLeftArrowCollapsesExpandedNode() throws IOException {
        Path parentDir = subdir("modCollapse");
        Path childDir = subdir("modCollapse/child");

        PilotProject parent = createProject("com.example", "parent", "1.0", parentDir);
        PilotProject child = createProject("com.example", "child", "1.0", childDir);

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(parent, child));
        ReactorModel model = ReactorModel.build(List.of(parent, child));
        var tui = new UpdatesTui(result, model, "com.example:parent:1.0", (g, a) -> List.of());

        // Switch to modules view and initialize selection
        tui.handleEvent(KeyEvent.ofKey(KeyCode.TAB), null);
        tui.handleEvent(KeyEvent.ofKey(KeyCode.DOWN), null);
        tui.handleEvent(KeyEvent.ofKey(KeyCode.UP), null);

        assertThat(model.root.expanded).isTrue();
        assertThat(model.visibleNodes()).hasSize(2);

        tui.handleEvent(KeyEvent.ofKey(KeyCode.LEFT), null);

        assertThat(model.root.expanded).isFalse();
        assertThat(model.visibleNodes()).hasSize(1);
    }

    @Test
    void modulesRightArrowExpandsCollapsedNode() throws IOException {
        Path parentDir = subdir("modExpand");
        Path childDir = subdir("modExpand/child");

        PilotProject parent = createProject("com.example", "parent", "1.0", parentDir);
        PilotProject child = createProject("com.example", "child", "1.0", childDir);

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(parent, child));
        ReactorModel model = ReactorModel.build(List.of(parent, child));
        var tui = new UpdatesTui(result, model, "com.example:parent:1.0", (g, a) -> List.of());

        tui.handleEvent(KeyEvent.ofKey(KeyCode.TAB), null);
        tui.handleEvent(KeyEvent.ofKey(KeyCode.DOWN), null);
        tui.handleEvent(KeyEvent.ofKey(KeyCode.UP), null);

        model.root.expanded = false;
        assertThat(model.visibleNodes()).hasSize(1);

        tui.handleEvent(KeyEvent.ofKey(KeyCode.RIGHT), null);

        assertThat(model.root.expanded).isTrue();
        assertThat(model.visibleNodes()).hasSize(2);
    }

    @Test
    void modulesRightArrowOnExpandedNodeMovesDown() throws IOException {
        Path parentDir = subdir("modDown");
        Path childDir = subdir("modDown/child");

        PilotProject parent = createProject("com.example", "parent", "1.0", parentDir);
        PilotProject child = createProject("com.example", "child", "1.0", childDir);

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(parent, child));
        ReactorModel model = ReactorModel.build(List.of(parent, child));
        var tui = new UpdatesTui(result, model, "com.example:parent:1.0", (g, a) -> List.of());

        tui.handleEvent(KeyEvent.ofKey(KeyCode.TAB), null);
        tui.handleEvent(KeyEvent.ofKey(KeyCode.DOWN), null);
        tui.handleEvent(KeyEvent.ofKey(KeyCode.UP), null);

        assertThat(model.root.expanded).isTrue();

        // RIGHT on expanded root → moves down to child
        tui.handleEvent(KeyEvent.ofKey(KeyCode.RIGHT), null);
        // LEFT on child (leaf) → jump to parent; LEFT again → collapse
        tui.handleEvent(KeyEvent.ofKey(KeyCode.LEFT), null);
        tui.handleEvent(KeyEvent.ofKey(KeyCode.LEFT), null);

        assertThat(model.root.expanded).isFalse();
    }

    @Test
    void modulesLeftArrowOnChildJumpsToParent() throws IOException {
        Path parentDir = subdir("modJump");
        Path childDir = subdir("modJump/child");
        Path grandchildDir = subdir("modJump/child/grandchild");

        PilotProject parent = createProject("com.example", "parent", "1.0", parentDir);
        PilotProject child = createProject("com.example", "child", "1.0", childDir);
        PilotProject grandchild = createProject("com.example", "grandchild", "1.0", grandchildDir);

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(parent, child, grandchild));
        ReactorModel model = ReactorModel.build(List.of(parent, child, grandchild));
        var tui = new UpdatesTui(result, model, "com.example:parent:1.0", (g, a) -> List.of());

        tui.handleEvent(KeyEvent.ofKey(KeyCode.TAB), null);

        // parent(d=0,exp) → child(d=1,exp) → grandchild(d=2)
        assertThat(model.visibleNodes()).hasSize(3);

        // Move to grandchild
        tui.handleEvent(KeyEvent.ofKey(KeyCode.DOWN), null);
        tui.handleEvent(KeyEvent.ofKey(KeyCode.DOWN), null);
        tui.handleEvent(KeyEvent.ofKey(KeyCode.DOWN), null);

        // LEFT on grandchild (leaf) → jump to child
        tui.handleEvent(KeyEvent.ofKey(KeyCode.LEFT), null);
        // LEFT on child (expanded, has children) → collapse
        tui.handleEvent(KeyEvent.ofKey(KeyCode.LEFT), null);

        assertThat(model.root.children.get(0).expanded).isFalse();
        assertThat(model.visibleNodes()).hasSize(2);
    }

    @Test
    void renderWithManagedDependencyScopes() throws IOException {
        Path dir = subdir("render8");

        PilotProject.Dep d = dep("com.example", "lib", "1.0", "compile");
        PilotProject project =
                createProject("com.example", "app", "1.0", dir, List.of(d), List.of(), List.of(d), List.of());

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        var tui = createTui(result, List.of(project));

        result.ungroupedDependencies.get(0).newestVersion = "1.1.0";
        result.ungroupedDependencies.get(0).updateType = VersionComparator.UpdateType.MINOR;

        tui.loading = false;
        tui.buildDisplayRows();

        renderFrame(tui);
    }
}
