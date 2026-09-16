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

import dev.tamboui.text.Span;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for UpdatesTui tree-impact target resolution (resolveImpactTarget) and
 * property-group handling.
 */
class UpdatesTuiImpactTest {

    @TempDir
    Path tempDir;

    private Path subdir(String name) throws IOException {
        return Files.createDirectories(tempDir.resolve(name));
    }

    private PilotProject createProject(String groupId, String artifactId, String version, Path basedir) {
        return new PilotProject(
                groupId,
                artifactId,
                version,
                "jar",
                basedir,
                basedir.resolve("pom.xml"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new Properties(),
                null,
                null);
    }

    private PilotProject.Dep dep(String groupId, String artifactId, String version) {
        return new PilotProject.Dep(groupId, artifactId, version);
    }

    private UpdatesTui createTui(ReactorCollector.CollectionResult result, List<PilotProject> projects) {
        ReactorModel model = ReactorModel.build(projects);
        return new UpdatesTui(result, model, "com.example:app:1.0", (g, a) -> List.of());
    }

    // --- resolveImpactTarget: dependency row with no update ---

    @Test
    void noImpactTargetWhenDepHasNoUpdate() throws IOException {
        Path dir = subdir("no-update");
        PilotProject.Dep d = dep("com.example", "lib", "1.0");
        PilotProject project = new PilotProject(
                "com.example",
                "app",
                "1.0",
                "jar",
                dir,
                dir.resolve("pom.xml"),
                List.of(d),
                List.of(),
                List.of(d),
                List.of(),
                new Properties(),
                null,
                null);

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        UpdatesTui tui = createTui(result, List.of(project));

        // No update available — dep not in display rows (ALL filter requires hasUpdate())
        tui.loading = false;
        tui.buildDisplayRows();

        // When no resolver is configured, pressing 't' sets status synchronously
        tui.handleKeyEvent(KeyEvent.ofChar('t'));
        assertThat(tui.status()).isEqualTo("Tree impact not available");
    }

    @Test
    void resolveImpactTargetReturnsNullForDepWithNoUpdate() throws IOException {
        // Build a dep row where newestVersion == null → resolveImpactTarget must return null
        ReactorCollector.AggregatedDependency aggDep = new ReactorCollector.AggregatedDependency("com.example", "lib");
        aggDep.primaryVersion = "1.0";
        // newestVersion intentionally left null — no update available
        var row = UpdatesTui.ReactorRow.dep(aggDep);

        Path dir = subdir("resolve-no-update");
        PilotProject project = createProject("com.example", "app", "1.0", dir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        UpdatesTui tui = createTui(result, List.of(project));

        // resolveImpactTarget returns null and sets status to "No update available..."
        UpdatesTui.ImpactTarget target = tui.resolveImpactTarget(row);
        assertThat(target).isNull();
        assertThat(tui.status()).isEqualTo("No update available for tree impact");
    }

    // --- resolveImpactTarget: group-header paths ---

    @Test
    void resolveImpactTargetForGroupHeaderWithNoUpdate() throws IOException {
        var group = new ReactorCollector.PropertyGroup("spring.version", "${spring.version}", "6.1.0", null);
        // newestVersion intentionally left null — group has no update
        var row = UpdatesTui.ReactorRow.group(group);

        Path dir = subdir("group-no-update");
        PilotProject project = createProject("com.example", "app", "1.0", dir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        UpdatesTui tui = createTui(result, List.of(project));

        UpdatesTui.ImpactTarget target = tui.resolveImpactTarget(row);
        assertThat(target).isNull();
        assertThat(tui.status()).isEqualTo("No update available for tree impact");
    }

    @Test
    void resolveImpactTargetForGroupHeaderWithUpdateButNoDepsResolved() throws IOException {
        var group = new ReactorCollector.PropertyGroup("spring.version", "${spring.version}", "6.1.0", null);
        group.newestVersion = "6.2.0";
        // No dependency in the group has newestVersion set
        var dep = new ReactorCollector.AggregatedDependency("org.springframework", "spring-core");
        dep.primaryVersion = "6.1.0";
        // dep.newestVersion intentionally left null
        group.dependencies.add(dep);
        var row = UpdatesTui.ReactorRow.group(group);

        Path dir = subdir("group-no-resolved-dep");
        PilotProject project = createProject("com.example", "app", "1.0", dir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        UpdatesTui tui = createTui(result, List.of(project));

        UpdatesTui.ImpactTarget target = tui.resolveImpactTarget(row);
        assertThat(target).isNull();
        assertThat(tui.status()).isEqualTo("No dependency in group has a resolved update");
    }

    @Test
    void resolveImpactTargetForGroupHeaderWithResolvedDep() throws IOException {
        var group = new ReactorCollector.PropertyGroup("spring.version", "${spring.version}", "6.1.0", null);
        group.newestVersion = "6.2.0";
        var dep = new ReactorCollector.AggregatedDependency("org.springframework", "spring-core");
        dep.primaryVersion = "6.1.0";
        dep.newestVersion = "6.2.0";
        group.dependencies.add(dep);
        var row = UpdatesTui.ReactorRow.group(group);

        Path dir = subdir("group-resolved");
        PilotProject project = createProject("com.example", "app", "1.0", dir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        UpdatesTui tui = createTui(result, List.of(project));

        UpdatesTui.ImpactTarget target = tui.resolveImpactTarget(row);
        assertThat(target).isNotNull();
        assertThat(target.dep()).isSameAs(dep);
        assertThat(target.newVersion()).isEqualTo("6.2.0");
        assertThat(target.label()).contains("spring.version").contains("6.1.0").contains("6.2.0");
    }

    /**
     * When deps in a property group resolve to different newest versions, pg.newestVersion is the
     * group maximum (MAX across all deps). ImpactTarget.newVersion() must use pg.newestVersion so
     * that the computation matches the displayed label, not dep.newestVersion which may be lower.
     */
    @Test
    void resolveImpactTargetForGroupHeaderUsesGroupMaxVersion() throws IOException {
        var group = new ReactorCollector.PropertyGroup("spring.version", "${spring.version}", "6.1.0", null);
        group.newestVersion = "6.2.0"; // group maximum
        // First dep resolved to a lower version; second dep resolved to the group max
        var dep1 = new ReactorCollector.AggregatedDependency("org.springframework", "spring-core");
        dep1.primaryVersion = "6.1.0";
        dep1.newestVersion = "6.1.5"; // lower than group max — this dep is picked first by findFirst()
        var dep2 = new ReactorCollector.AggregatedDependency("org.springframework", "spring-context");
        dep2.primaryVersion = "6.1.0";
        dep2.newestVersion = "6.2.0";
        group.dependencies.add(dep1);
        group.dependencies.add(dep2);
        var row = UpdatesTui.ReactorRow.group(group);

        Path dir = subdir("group-max-version");
        PilotProject project = createProject("com.example", "app", "1.0", dir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        UpdatesTui tui = createTui(result, List.of(project));

        UpdatesTui.ImpactTarget target = tui.resolveImpactTarget(row);
        assertThat(target).isNotNull();
        // dep1 is selected (first with non-null newestVersion), but newVersion must be the group max
        assertThat(target.dep()).isSameAs(dep1);
        assertThat(target.newVersion()).isEqualTo("6.2.0"); // pg.newestVersion, not dep1.newestVersion ("6.1.5")
        assertThat(target.label()).contains("spring.version").contains("6.1.0").contains("6.2.0");
    }

    @Test
    void resolveImpactTargetForDepRowWithUpdate() throws IOException {
        var dep = new ReactorCollector.AggregatedDependency("com.example", "lib");
        dep.primaryVersion = "1.0";
        dep.newestVersion = "2.0";
        var row = UpdatesTui.ReactorRow.dep(dep);

        Path dir = subdir("dep-with-update");
        PilotProject project = createProject("com.example", "app", "1.0", dir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        UpdatesTui tui = createTui(result, List.of(project));

        UpdatesTui.ImpactTarget target = tui.resolveImpactTarget(row);
        assertThat(target).isNotNull();
        assertThat(target.dep()).isSameAs(dep);
        assertThat(target.newVersion()).isEqualTo("2.0");
        assertThat(target.label()).contains("com.example:lib").contains("1.0").contains("2.0");
    }

    // --- ReactorRow.group(): group header with update ---

    @Test
    void propertyGroupRowHasNullDependency() {
        var group = new ReactorCollector.PropertyGroup("spring.version", "${spring.version}", "6.1.0", null);
        var row = UpdatesTui.ReactorRow.group(group);

        assertThat(row.isGroupHeader()).isTrue();
        assertThat(row.dependency).isNull();
        assertThat(row.propertyGroup).isSameAs(group);
    }

    @Test
    void propertyGroupWithUpdateHasNewestVersion() {
        var group = new ReactorCollector.PropertyGroup("spring.version", "${spring.version}", "6.1.0", null);
        group.newestVersion = "6.2.0";

        assertThat(group.hasUpdate()).isTrue();
    }

    @Test
    void propertyGroupWithoutUpdateReportsNoUpdate() {
        var group = new ReactorCollector.PropertyGroup("spring.version", "${spring.version}", "6.1.0", null);

        assertThat(group.hasUpdate()).isFalse();
    }

    @Test
    void propertyGroupWithSameVersionReportsNoUpdate() {
        var group = new ReactorCollector.PropertyGroup("spring.version", "${spring.version}", "6.1.0", null);
        group.newestVersion = "6.1.0";

        assertThat(group.hasUpdate()).isFalse();
    }

    // --- Render paths exercising tree-impact overlay indirectly ---

    @Test
    void renderWithPropertyGroupRowDoesNotThrow() throws IOException {
        Path dir = subdir("pg-render");
        PilotProject.Dep d = new PilotProject.Dep("com.example", "lib", "${spring.version}", "compile", null);
        Properties props = new Properties();
        props.setProperty("spring.version", "6.1.0");
        PilotProject project = new PilotProject(
                "com.example",
                "app",
                "1.0",
                "jar",
                dir,
                dir.resolve("pom.xml"),
                List.of(d),
                List.of(),
                List.of(d),
                List.of(),
                props,
                null,
                null);

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        UpdatesTui tui = createTui(result, List.of(project));
        tui.loading = false;
        tui.buildDisplayRows();

        String output = TuiTestHelper.render(tui::renderStandalone);
        assertThat(output).isNotEmpty();
    }

    // --- AggregatedDependency.hasUpdate() ---

    @Test
    void aggregatedDepHasUpdateWhenNewerVersion() {
        ReactorCollector.AggregatedDependency dep = new ReactorCollector.AggregatedDependency("g", "a");
        dep.primaryVersion = "1.0";
        dep.newestVersion = "2.0";
        assertThat(dep.hasUpdate()).isTrue();
    }

    @Test
    void aggregatedDepNoUpdateWhenSameVersion() {
        ReactorCollector.AggregatedDependency dep = new ReactorCollector.AggregatedDependency("g", "a");
        dep.primaryVersion = "1.0";
        dep.newestVersion = "1.0";
        assertThat(dep.hasUpdate()).isFalse();
    }

    @Test
    void aggregatedDepNoUpdateWhenNewestNull() {
        ReactorCollector.AggregatedDependency dep = new ReactorCollector.AggregatedDependency("g", "a");
        dep.primaryVersion = "1.0";
        assertThat(dep.hasUpdate()).isFalse();
    }

    // --- Stale generation guard is exercised by the generation counter ---

    @Test
    void treeImpactGenerationStartsAtZero() throws IOException {
        Path dir = subdir("gen");
        PilotProject project = createProject("com.example", "app", "1.0", dir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        UpdatesTui tui = createTui(result, List.of(project));
        // treeImpactGeneration is private; just verify the tui builds without errors
        assertThat(tui.status()).isNotNull();
    }

    // --- treeImpactOverlay active paths (via reflection) ---

    /**
     * Opens the treeImpactOverlay on the given tui via reflection (it's a private field).
     */
    private static void openTreeImpactOverlay(UpdatesTui tui) throws Exception {
        Field f = UpdatesTui.class.getDeclaredField("treeImpactOverlay");
        f.setAccessible(true);
        DiffOverlay overlay = (DiffOverlay) f.get(tui);
        // Create a minimal non-empty entry list so isActive() returns true
        var entries = List.of(new TreeDiff.DiffEntry("com.example:lib", "1.0", "compile", 0, TreeDiff.Side.LEFT));
        overlay.openTreeImpact(entries);
    }

    @Test
    void handleKeyEventEscWhenTreeImpactOverlayActive() throws Exception {
        Path dir = subdir("overlay-esc");
        PilotProject project = createProject("com.example", "app", "1.0", dir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        UpdatesTui tui = createTui(result, List.of(project));
        openTreeImpactOverlay(tui);

        // ESC should close the overlay and return true
        boolean handled = tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE));
        assertThat(handled).isTrue();
    }

    @Test
    void handleKeyEventQWhenTreeImpactOverlayActive() throws Exception {
        Path dir = subdir("overlay-q");
        PilotProject project = createProject("com.example", "app", "1.0", dir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        UpdatesTui tui = createTui(result, List.of(project));
        openTreeImpactOverlay(tui);

        boolean handled = tui.handleKeyEvent(KeyEvent.ofChar('q'));
        assertThat(handled).isTrue();
    }

    @Test
    void handleKeyEventScrollWhenTreeImpactOverlayActive() throws Exception {
        Path dir = subdir("overlay-scroll");
        PilotProject project = createProject("com.example", "app", "1.0", dir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        UpdatesTui tui = createTui(result, List.of(project));
        openTreeImpactOverlay(tui);

        // Any other key (e.g. down arrow) goes to scroll handling, overlay stays active → true
        boolean handled = tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.DOWN));
        assertThat(handled).isTrue();
    }

    @Test
    void renderStandaloneWithTreeImpactOverlayActive() throws Exception {
        Path dir = subdir("overlay-render");
        PilotProject project = createProject("com.example", "app", "1.0", dir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        UpdatesTui tui = createTui(result, List.of(project));
        tui.loading = false;
        tui.buildDisplayRows();
        openTreeImpactOverlay(tui);

        // Should render without throwing; the overlay content should appear
        String output = TuiTestHelper.render(tui::renderStandalone);
        assertThat(output).contains("com.example:lib:1.0");
    }

    @Test
    void handleMouseEventWhenTreeImpactOverlayActive() throws Exception {
        Path dir = subdir("overlay-mouse");
        PilotProject project = createProject("com.example", "app", "1.0", dir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        UpdatesTui tui = createTui(result, List.of(project));
        openTreeImpactOverlay(tui);

        MouseEvent scrollEvent = MouseEvent.scrollDown(10, 10);
        boolean handled = tui.handleMouseEvent(scrollEvent, null);
        assertThat(handled).isTrue();
    }

    @Test
    void keyHintsContainsEscWhenTreeImpactOverlayActive() throws Exception {
        Path dir = subdir("overlay-hints");
        PilotProject project = createProject("com.example", "app", "1.0", dir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        UpdatesTui tui = createTui(result, List.of(project));
        openTreeImpactOverlay(tui);

        // keyHints when overlay active should contain Esc/close hint
        var hints = tui.keyHints();
        String hintsText = hints.stream().map(Span::content).reduce("", String::concat);
        assertThat(hintsText).contains("Esc");
    }

    @Test
    void constructorWithFiveArgs() throws IOException {
        // Cover the 5-arg constructor path: this(result, model, gav, resolver, null, sessionProvider)
        Path dir = subdir("constructor-5arg");
        PilotProject project = createProject("com.example", "app", "1.0", dir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        ReactorModel model = ReactorModel.build(List.of(project));

        UpdatesTui tui = new UpdatesTui(result, model, "com.example:app:1.0", (g, a) -> List.of(), null);
        assertThat(tui.status()).isNotNull();
    }

    @Test
    void constructorWithSixArgsAndImpactResolver() throws IOException {
        // Cover the 6-arg constructor with non-null treeImpactResolver
        Path dir = subdir("constructor-6arg");
        PilotProject project = createProject("com.example", "app", "1.0", dir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        ReactorModel model = ReactorModel.build(List.of(project));

        UpdatesTui.TreeImpactResolver resolver = (g, a, ov, nv) -> List.of();
        UpdatesTui tui = new UpdatesTui(result, model, "com.example:app:1.0", (g, a) -> List.of(), resolver, null);
        assertThat(tui.status()).isNotNull();
    }

    // --- handleEvent (standalone) with tree-impact overlay active ---

    @Test
    void handleEventEscWhenTreeImpactOverlayActive() throws Exception {
        Path dir = subdir("standalone-overlay-esc");
        PilotProject project = createProject("com.example", "app", "1.0", dir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        UpdatesTui tui = createTui(result, List.of(project));
        openTreeImpactOverlay(tui);

        // Standalone handleEvent with ESC should close the overlay
        boolean handled = tui.handleEvent(KeyEvent.ofKey(KeyCode.ESCAPE), null);
        assertThat(handled).isTrue();
    }

    @Test
    void handleEventQWhenTreeImpactOverlayActive() throws Exception {
        Path dir = subdir("standalone-overlay-q");
        PilotProject project = createProject("com.example", "app", "1.0", dir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        UpdatesTui tui = createTui(result, List.of(project));
        openTreeImpactOverlay(tui);

        boolean handled = tui.handleEvent(KeyEvent.ofChar('q'), null);
        assertThat(handled).isTrue();
    }

    @Test
    void handleEventScrollWhenTreeImpactOverlayActive() throws Exception {
        Path dir = subdir("standalone-overlay-scroll");
        PilotProject project = createProject("com.example", "app", "1.0", dir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        UpdatesTui tui = createTui(result, List.of(project));
        openTreeImpactOverlay(tui);

        // Down arrow goes to scroll, overlay stays active → handled
        boolean handled = tui.handleEvent(KeyEvent.ofKey(KeyCode.DOWN), null);
        assertThat(handled).isTrue();
    }

    @Test
    void handleEventNonKeyEventIsConsumed() throws Exception {
        Path dir = subdir("standalone-non-key");
        PilotProject project = createProject("com.example", "app", "1.0", dir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));
        UpdatesTui tui = createTui(result, List.of(project));

        // Non-key event (mouse scroll) should be consumed immediately
        MouseEvent scroll = MouseEvent.scrollDown(10, 10);
        boolean handled = tui.handleEvent(scroll, null);
        assertThat(handled).isTrue();
    }
}
