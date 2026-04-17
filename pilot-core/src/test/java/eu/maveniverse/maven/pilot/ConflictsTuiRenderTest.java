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

import static eu.maveniverse.maven.pilot.TuiTestHelper.*;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.terminal.TestBackend;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConflictsTuiRenderTest {

    @TempDir
    Path tempDir;

    private String pomPath;

    @BeforeEach
    void setUp() throws Exception {
        pomPath = Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();
    }

    private ConflictsTui createTuiWithConflicts() {
        List<ConflictsTui.ConflictGroup> conflicts = new ArrayList<>();

        var e1 = new ConflictsTui.ConflictEntry(
                "org.apache.commons", "commons-lang3", "3.12.0", "3.14.0", "commons-text:1.11.0", "compile");
        var e2 = new ConflictsTui.ConflictEntry(
                "org.apache.commons", "commons-lang3", "3.14.0", "3.14.0", "direct", "compile");
        conflicts.add(new ConflictsTui.ConflictGroup("org.apache.commons:commons-lang3", List.of(e1, e2)));

        var e3 =
                new ConflictsTui.ConflictEntry("org.slf4j", "slf4j-api", "2.0.9", "2.0.9", "logback:1.4.14", "compile");
        var e4 = new ConflictsTui.ConflictEntry("org.slf4j", "slf4j-api", "2.0.9", "2.0.9", "direct", "compile");
        conflicts.add(new ConflictsTui.ConflictGroup("org.slf4j:slf4j-api", List.of(e3, e4)));

        return new ConflictsTui(conflicts, pomPath, "com.example:demo:1.0.0");
    }

    @Test
    void renderShowsConflictGroups() {
        var tui = createTuiWithConflicts();
        String output = render(tui::renderStandalone);

        assertThat(output).contains("commons-lang3");
    }

    @Test
    void showAllRevealsConvergedGroups() {
        var tui = createTuiWithConflicts();
        tui.handleEvent(KeyEvent.ofChar('t'), null);
        String output = render(tui::renderStandalone);

        assertThat(output).contains("commons-lang3").contains("slf4j-api");
    }

    @Test
    void renderShowsProjectGav() {
        var tui = createTuiWithConflicts();
        String output = render(tui::renderStandalone);

        assertThat(output).contains("com.example:demo:1.0.0");
    }

    @Test
    void standaloneDividerBetweenTableAndDetails() {
        var tui = createTuiWithConflicts();
        String output = render(tui::renderStandalone);

        assertThat(output).contains("─".repeat(10));
    }

    @Test
    void panelModeDividerBetweenTableAndDetails() {
        var tui = createTuiWithConflicts();
        String output = render(f -> tui.render(f, f.area()));

        assertThat(output).contains("─".repeat(10));
    }

    @Test
    void panelModeNoDividerWhenDetailsHidden() {
        var tui = createTuiWithConflicts();
        tui.handleEvent(KeyEvent.ofKey(KeyCode.ENTER), null);
        String output = render(f -> tui.render(f, f.area()));

        long dividerLines = output.lines().filter(l -> l.matches("^─+$")).count();
        assertThat(dividerLines).isZero();
    }

    @Test
    void loadingStateShowsProgressCounter() {
        List<ConflictsTui.ConflictGroup> empty = List.of();
        var tui = new ConflictsTui(empty, pomPath, "com.example:demo:1.0.0");
        String output = render(tui::renderStandalone);

        assertThat(output).contains("Conflict Resolution");
    }

    @Test
    void renderShowsVersionInfo() {
        var tui = createTuiWithConflicts();
        String output = render(tui::renderStandalone);

        assertThat(output).contains("3.12.0").contains("3.14.0");
    }

    @Test
    void emptyConflictsRenderWithoutError() {
        var tui = new ConflictsTui(List.of(), pomPath, "com.example:app:2.0");
        String output = render(tui::renderStandalone);

        assertThat(output).contains("com.example:app:2.0");
    }

    // ── collectConflicts tests ──────────────────────────────────────────────

    @Test
    void collectConflictsFindsChildNodes() {
        var root = new DependencyTreeModel.TreeNode("com.example", "root", "1.0", "compile", false, 0);
        var child1 = new DependencyTreeModel.TreeNode("org.a", "lib-a", "1.0", "compile", false, 1);
        var child2 = new DependencyTreeModel.TreeNode("org.a", "lib-a", "2.0", "compile", false, 1);
        root.children.add(child1);
        root.children.add(child2);

        Map<String, List<ConflictsTui.ConflictEntry>> conflicts = new HashMap<>();
        ConflictsTui.collectConflicts(root, conflicts, new ArrayList<>());

        assertThat(conflicts).containsKey("org.a:lib-a");
        assertThat(conflicts.get("org.a:lib-a")).hasSize(2);
    }

    @Test
    void collectConflictsUsesRequestedVersion() {
        var root = new DependencyTreeModel.TreeNode("com.example", "root", "1.0", "compile", false, 0);
        var child = new DependencyTreeModel.TreeNode("org.a", "lib-a", "2.0", "compile", false, 1);
        child.requestedVersion = "1.0";
        root.children.add(child);

        Map<String, List<ConflictsTui.ConflictEntry>> conflicts = new HashMap<>();
        ConflictsTui.collectConflicts(root, conflicts, new ArrayList<>());

        ConflictsTui.ConflictEntry entry = conflicts.get("org.a:lib-a").get(0);
        assertThat(entry.requestedVersion).isEqualTo("1.0");
        assertThat(entry.resolvedVersion).isEqualTo("2.0");
    }

    @Test
    void collectConflictsBuildsPath() {
        var root = new DependencyTreeModel.TreeNode("com.example", "root", "1.0", "compile", false, 0);
        var parent = new DependencyTreeModel.TreeNode("org.a", "parent", "1.0", "compile", false, 1);
        var child = new DependencyTreeModel.TreeNode("org.b", "child", "1.0", "compile", false, 2);
        root.children.add(parent);
        parent.children.add(child);

        Map<String, List<ConflictsTui.ConflictEntry>> conflicts = new HashMap<>();
        List<String> basePath = new ArrayList<>();
        basePath.add("[module-a]");
        ConflictsTui.collectConflicts(root, conflicts, basePath);

        ConflictsTui.ConflictEntry childEntry = conflicts.get("org.b:child").get(0);
        assertThat(childEntry.path)
                .contains("[module-a]")
                .contains("org.a:parent")
                .contains("org.b:child");
    }

    // ── filterConflictGroups tests ──────────────────────────────────────────

    @Test
    void filterConflictGroupsKeepsMultiEntry() {
        Map<String, List<ConflictsTui.ConflictEntry>> map = new HashMap<>();
        map.put(
                "org.a:lib",
                List.of(
                        new ConflictsTui.ConflictEntry("org.a", "lib", "1.0", "1.0", "direct", "compile"),
                        new ConflictsTui.ConflictEntry("org.a", "lib", "1.0", "1.0", "transitive", "compile")));
        map.put(
                "org.b:single",
                List.of(new ConflictsTui.ConflictEntry("org.b", "single", "1.0", "1.0", "direct", "compile")));

        List<ConflictsTui.ConflictGroup> groups = ConflictsTui.filterConflictGroups(map);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).ga).isEqualTo("org.a:lib");
    }

    @Test
    void filterConflictGroupsKeepsVersionMismatch() {
        Map<String, List<ConflictsTui.ConflictEntry>> map = new HashMap<>();
        map.put(
                "org.a:lib",
                List.of(new ConflictsTui.ConflictEntry("org.a", "lib", "1.0", "2.0", "direct", "compile")));

        List<ConflictsTui.ConflictGroup> groups = ConflictsTui.filterConflictGroups(map);

        assertThat(groups).hasSize(1);
    }

    // ── Async loading constructor tests ─────────────────────────────────────

    @Test
    void asyncConstructorShowsLoadingState() {
        PomEditSession session = new PomEditSession(Path.of(pomPath));
        PilotProject project = createDummyProject("mod-a");

        var tui = new ConflictsTui(List.of(project), session, "com.example:demo:1.0.0", p -> createEmptyTree());
        String output = render(tui::renderStandalone);

        assertThat(output).contains("Collecting Conflicts");
    }

    @Test
    void asyncConstructorEmptyProjectsNotLoading() {
        PomEditSession session = new PomEditSession(Path.of(pomPath));

        var tui = new ConflictsTui(List.of(), session, "com.example:demo:1.0.0", p -> createEmptyTree());
        String output = render(tui::renderStandalone);

        assertThat(output).contains("Conflict Resolution");
    }

    @Test
    void asyncLoadingShowsModuleName() {
        PomEditSession session = new PomEditSession(Path.of(pomPath));
        PilotProject project = createDummyProject("my-module");

        var tui = new ConflictsTui(List.of(project), session, "com.example:demo:1.0.0", p -> {
            return createEmptyTree();
        });
        String output = render(tui::renderStandalone);

        assertThat(output).contains("0/1");
    }

    @Test
    void asyncLoadingPanelModeShowsPlaceholder() {
        PomEditSession session = new PomEditSession(Path.of(pomPath));
        PilotProject project = createDummyProject("my-module");

        var tui = new ConflictsTui(List.of(project), session, "com.example:demo:1.0.0", p -> createEmptyTree());
        String output = render(f -> tui.render(f, f.area()));

        assertThat(output).contains("Collecting conflicts");
    }

    @Test
    void asyncLoadingHeaderShowsProgress() {
        PomEditSession session = new PomEditSession(Path.of(pomPath));
        PilotProject p1 = createDummyProject("mod-a");
        PilotProject p2 = createDummyProject("mod-b");

        var tui = new ConflictsTui(List.of(p1, p2), session, "com.example:demo:1.0.0", p -> createEmptyTree());
        String output = render(tui::renderStandalone);

        assertThat(output).contains("Collecting Conflicts").contains("0/2");
    }

    @Test
    void asyncLoadingStatusMessage() {
        PomEditSession session = new PomEditSession(Path.of(pomPath));
        PilotProject project = createDummyProject("mod-a");

        var tui = new ConflictsTui(List.of(project), session, "com.example:demo:1.0.0", p -> createEmptyTree());

        assertThat(tui.status()).contains("Collecting conflicts");
    }

    // ── resolveModule / mergeAndAdvance / onCollectionComplete tests ──────

    @Test
    void resolveModuleSingleModule() {
        PomEditSession session = new PomEditSession(Path.of(pomPath));
        PilotProject project = createDummyProject("mod-a");

        var tui = new ConflictsTui(List.of(project), session, "com.example:demo:1.0.0", p -> createTreeWithConflict());

        Map<String, List<ConflictsTui.ConflictEntry>> result = tui.resolveModule(project, false);

        assertThat(result).containsKey("org.a:lib-a");
        assertThat(result.get("org.a:lib-a").get(0).path).doesNotContain("[");
    }

    @Test
    void resolveModuleMultiModulePrefixesPath() {
        PomEditSession session = new PomEditSession(Path.of(pomPath));
        PilotProject project = createDummyProject("mod-a");

        var tui = new ConflictsTui(List.of(project), session, "com.example:demo:1.0.0", p -> createTreeWithConflict());

        Map<String, List<ConflictsTui.ConflictEntry>> result = tui.resolveModule(project, true);

        assertThat(result.get("org.a:lib-a").get(0).path).contains("[mod-a]");
    }

    @Test
    void mergeAndAdvanceTracksProgress() {
        PomEditSession session = new PomEditSession(Path.of(pomPath));
        PilotProject p1 = createDummyProject("mod-a");
        PilotProject p2 = createDummyProject("mod-b");

        var tui = new ConflictsTui(List.of(p1, p2), session, "com.example:demo:1.0.0", p -> createEmptyTree());

        Map<String, List<ConflictsTui.ConflictEntry>> mergedMap = new HashMap<>();
        Map<String, List<ConflictsTui.ConflictEntry>> local = new HashMap<>();
        local.put(
                "org.a:lib-a",
                new ArrayList<>(
                        List.of(new ConflictsTui.ConflictEntry("org.a", "lib-a", "1.0", "1.0", "direct", "compile"))));

        tui.mergeAndAdvance(local, mergedMap);

        assertThat(tui.status()).contains("1/2");
    }

    @Test
    void onCollectionCompleteTransitionsToLoaded() {
        PomEditSession session = new PomEditSession(Path.of(pomPath));
        PilotProject project = createDummyProject("mod-a");

        var tui = new ConflictsTui(List.of(project), session, "com.example:demo:1.0.0", p -> createEmptyTree());

        Map<String, List<ConflictsTui.ConflictEntry>> mergedMap = new HashMap<>();
        mergedMap.put(
                "org.a:lib-a",
                List.of(
                        new ConflictsTui.ConflictEntry("org.a", "lib-a", "1.0", "2.0", "path-a", "compile"),
                        new ConflictsTui.ConflictEntry("org.a", "lib-a", "2.0", "2.0", "path-b", "compile")));

        tui.onCollectionComplete(mergedMap);

        String output = render(tui::renderStandalone);
        assertThat(output).contains("Conflict Resolution").contains("lib-a");
    }

    // ── async setRunner / startConflictCollection tests ───────────────────

    @Test
    void asyncSetRunnerTriggersConflictCollection() throws Exception {
        PomEditSession session = new PomEditSession(Path.of(pomPath));
        PilotProject project = createDummyProject("mod-a");

        CountDownLatch resolved = new CountDownLatch(1);
        var tui = new ConflictsTui(List.of(project), session, "com.example:demo:1.0.0", p -> {
            resolved.countDown();
            return createTreeWithConflict();
        });

        try (var runner = TuiRunner.create(TuiConfig.builder()
                .backend(new TestBackend(80, 24))
                .shutdownHook(false)
                .build())) {
            tui.setRunner(runner);
            assertThat(resolved.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void asyncCollectionHandlesResolverFailure() throws Exception {
        PomEditSession session = new PomEditSession(Path.of(pomPath));
        PilotProject project = createDummyProject("mod-a");

        CountDownLatch failed = new CountDownLatch(1);
        var tui = new ConflictsTui(List.of(project), session, "com.example:demo:1.0.0", p -> {
            failed.countDown();
            throw new RuntimeException("resolver failed");
        });

        try (var runner = TuiRunner.create(TuiConfig.builder()
                .backend(new TestBackend(80, 24))
                .shutdownHook(false)
                .build())) {
            tui.setRunner(runner);
            assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private DependencyTreeModel createTreeWithConflict() {
        var root = new DependencyTreeModel.TreeNode("com.example", "root", "1.0", "compile", false, 0);
        var child = new DependencyTreeModel.TreeNode("org.a", "lib-a", "2.0", "compile", false, 1);
        child.requestedVersion = "1.0";
        root.children.add(child);
        return new DependencyTreeModel(root, List.of(), 1);
    }

    private PilotProject createDummyProject(String artifactId) {
        return new PilotProject(
                "com.example",
                artifactId,
                "1.0.0",
                "jar",
                tempDir,
                Path.of(pomPath),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new java.util.Properties(),
                null,
                null);
    }

    private DependencyTreeModel createEmptyTree() {
        var root = new DependencyTreeModel.TreeNode("com.example", "root", "1.0", "compile", false, 0);
        return new DependencyTreeModel(root, List.of(), 0);
    }
}
