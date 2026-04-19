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

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Rendering and interaction tests for {@link UpdatesTui}.
 *
 * <p>Each test renders the TUI to a virtual terminal and asserts on the
 * plain-text content of the buffer, catching regressions in both rendering
 * logic and event handling.</p>
 */
class UpdatesTuiRenderTest {

    @TempDir
    Path tempDir;

    private static final String POM_WITH_DEPS = """
            <project>
              <dependencies>
                <dependency>
                  <groupId>com.google.guava</groupId>
                  <artifactId>guava</artifactId>
                  <version>33.0.0-jre</version>
                </dependency>
                <dependency>
                  <groupId>org.slf4j</groupId>
                  <artifactId>slf4j-api</artifactId>
                  <version>2.0.9</version>
                </dependency>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <version>5.10.1</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>
            </project>
            """;

    private PilotProject.Dep dep(String groupId, String artifactId, String version) {
        return new PilotProject.Dep(groupId, artifactId, version);
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
                new Properties(),
                null,
                null);
    }

    private PilotProject createProject(String groupId, String artifactId, String version, Path basedir) {
        return createProject(groupId, artifactId, version, basedir, List.of(), List.of(), List.of(), List.of());
    }

    private UpdatesTui createTui(ReactorCollector.CollectionResult result, List<PilotProject> projects) {
        ReactorModel model = ReactorModel.build(projects);
        return new UpdatesTui(result, model, "com.example:demo:1.0.0", (g, a) -> List.of());
    }

    private UpdatesTui createTuiWithUpdates() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(dir.resolve("pom.xml"), POM_WITH_DEPS);

        var deps = List.of(
                dep("com.google.guava", "guava", "33.0.0-jre"),
                dep("org.slf4j", "slf4j-api", "2.0.9"),
                dep("org.junit.jupiter", "junit-jupiter", "5.10.1"));

        var project = createProject("com.example", "demo", "1.0.0", dir, deps, List.of(), deps, List.of());

        var result = ReactorCollector.collect(List.of(project));
        var model = ReactorModel.build(List.of(project));

        var tui = new UpdatesTui(result, model, "com.example:demo:1.0.0", (g, a) -> List.of());

        for (var d : result.ungroupedDependencies) {
            switch (d.artifactId) {
                case "guava" -> {
                    d.newestVersion = "33.4.0-jre";
                    d.updateType = VersionComparator.UpdateType.MINOR;
                }
                case "slf4j-api" -> {
                    d.newestVersion = "2.0.16";
                    d.updateType = VersionComparator.UpdateType.PATCH;
                }
                case "junit-jupiter" -> {
                    d.newestVersion = "6.0.0";
                    d.updateType = VersionComparator.UpdateType.MAJOR;
                }
                default -> {} // other deps don't need updates for these tests
            }
        }

        tui.loading = false;
        tui.buildDisplayRows();
        tui.status = "3 update(s) available";
        return tui;
    }

    // ── Content rendering ──────────────────────────────────────────────────

    @Test
    void renderShowsDependencyNames() throws Exception {
        var tui = createTuiWithUpdates();
        String output = render(tui::renderStandalone);

        assertThat(output).contains("guava").contains("slf4j-api").contains("junit-jupiter");
    }

    @Test
    void renderShowsCurrentAndAvailableVersions() throws Exception {
        var tui = createTuiWithUpdates();
        String output = render(tui::renderStandalone);

        assertThat(output)
                .contains("33.0.0-jre")
                .contains("33.4.0-jre")
                .contains("2.0.9")
                .contains("2.0.16")
                .contains("5.10.1")
                .contains("6.0.0");
    }

    @Test
    void renderShowsHeaderStatusAndNoFilterByDefault() throws Exception {
        var tui = createTuiWithUpdates();
        String output = render(tui::renderStandalone);

        assertThat(output)
                .contains("com.example:demo:1.0.0")
                .contains("3 update(s) available")
                .doesNotContain("[PATCH]")
                .doesNotContain("[MINOR]")
                .doesNotContain("[MAJOR]");
    }

    @Test
    void renderShowsLoadingState() {
        var d = dep("com.example", "lib", "1.0");
        var project = createProject("com.example", "app", "1.0", tempDir, List.of(d), List.of(), List.of(d), List.of());
        var result = ReactorCollector.collect(List.of(project));

        var tui = createTui(result, List.of(project));
        tui.loading = true;
        tui.loadedCount = 0;

        String output = render(tui::renderStandalone);
        assertThat(output).contains("Checking");
    }

    @Test
    void renderShowsNoUpdatesMessage() {
        var project = createProject("com.example", "app", "1.0", tempDir);
        var result = ReactorCollector.collect(List.of(project));

        var tui = createTui(result, List.of(project));
        tui.loading = false;
        tui.buildDisplayRows();

        String output = render(tui::renderStandalone);
        assertThat(output).contains("No updates available");
    }

    @Test
    void renderShowsArrowBetweenVersions() throws Exception {
        var tui = createTuiWithUpdates();
        String output = render(tui::renderStandalone);

        assertThat(output).contains("\u2192"); // →
    }

    // ── Divider ────────────────────────────────────────────────────────────

    @Test
    void panelModeDividerBetweenTableAndDetails() throws Exception {
        var tui = createTuiWithUpdates();
        String output = render(f -> tui.render(f, f.area()));

        assertThat(output).contains("─".repeat(10));
    }

    // ── Apply ─────────────────────────────────────────────────────────────

    @Test
    void initiallyNoDependenciesApplied() throws Exception {
        var tui = createTuiWithUpdates();
        String output = render(tui::renderStandalone);

        assertThat(output).doesNotContain("[\u00b7]"); // [·]
    }

    @Test
    void spaceAppliesCurrentRow() throws Exception {
        var tui = createTuiWithUpdates();
        tui.handleEvent(KeyEvent.ofChar(' '), null);

        String output = render(tui::renderStandalone);
        assertThat(output).contains("[\u00b7]");
        assertThat(countOccurrences(output, "[\u00b7]")).isEqualTo(1);
    }

    @Test
    void spaceOnAppliedRowDoesNothing() throws Exception {
        var tui = createTuiWithUpdates();
        tui.handleEvent(KeyEvent.ofChar(' '), null); // apply
        tui.handleEvent(KeyEvent.ofChar(' '), null); // no-op

        String output = render(tui::renderStandalone);
        assertThat(countOccurrences(output, "[\u00b7]")).isEqualTo(1);
    }

    @Test
    void applyOnSecondRowAfterNavigation() throws Exception {
        var tui = createTuiWithUpdates();
        tui.handleEvent(KeyEvent.ofKey(KeyCode.DOWN), null); // move to row 1
        tui.handleEvent(KeyEvent.ofChar(' '), null); // apply row 1

        String output = render(tui::renderStandalone);
        assertThat(countOccurrences(output, "[\u00b7]")).isEqualTo(1);
    }

    // ── Filtering ──────────────────────────────────────────────────────────

    static Stream<Arguments> filterByTypeArgs() {
        return Stream.of(
                Arguments.of(1, "PATCH", "slf4j-api", new String[] {"guava", "junit-jupiter"}),
                Arguments.of(2, "MINOR", "guava", new String[] {"slf4j-api", "junit-jupiter"}),
                Arguments.of(3, "MAJOR", "junit-jupiter", new String[] {"guava", "slf4j-api"}));
    }

    @ParameterizedTest
    @MethodSource("filterByTypeArgs")
    void filterShowsOnlyMatchingUpdateType(int presses, String label, String visible, String[] hidden)
            throws Exception {
        var tui = createTuiWithUpdates();
        for (int i = 0; i < presses; i++) tui.handleEvent(KeyEvent.ofChar('f'), null);

        String output = render(tui::renderStandalone);
        assertThat(output).contains("[" + label + "]").contains(visible);
        for (String h : hidden) {
            assertThat(output).doesNotContain(h);
        }
    }

    @Test
    void filterCyclesBackToAll() throws Exception {
        var tui = createTuiWithUpdates();
        for (int i = 0; i < 4; i++) tui.handleEvent(KeyEvent.ofChar('f'), null);

        String output = render(tui::renderStandalone);
        assertThat(output)
                .doesNotContain("[PATCH]")
                .doesNotContain("[MINOR]")
                .doesNotContain("[MAJOR]")
                .contains("guava")
                .contains("slf4j-api")
                .contains("junit-jupiter");
    }

    @Test
    void reverseFilterCyclesToMajor() throws Exception {
        var tui = createTuiWithUpdates();
        tui.handleEvent(KeyEvent.ofChar('F'), null); // ALL -> MAJOR (reverse)

        String output = render(tui::renderStandalone);
        assertThat(output).contains("[MAJOR]").contains("junit-jupiter");
    }

    // ── Navigation ─────────────────────────────────────────────────────────

    @Test
    void navigationDownThenApplyHitsSecondRow() throws Exception {
        var tui = createTuiWithUpdates();
        // Apply first row
        tui.handleEvent(KeyEvent.ofChar(' '), null);
        // Move down and apply second row
        tui.handleEvent(KeyEvent.ofKey(KeyCode.DOWN), null);
        tui.handleEvent(KeyEvent.ofChar(' '), null);

        String output = render(tui::renderStandalone);
        assertThat(countOccurrences(output, "[\u00b7]")).isEqualTo(2);
    }

    @Test
    void navigationChangesRendering() throws Exception {
        var tui = createTuiWithUpdates();
        String initial = render(tui::renderStandalone);

        tui.handleEvent(KeyEvent.ofKey(KeyCode.DOWN), null);
        String afterDown = render(tui::renderStandalone);

        assertThat(afterDown).isNotEqualTo(initial);
    }

    // ── Apply updates ──────────────────────────────────────────────────────

    @Test
    void spaceAppliesUpdateShowsMessage() throws Exception {
        var tui = createTuiWithUpdates();
        tui.handleEvent(KeyEvent.ofChar(' '), null); // apply first dep

        String output = render(tui::renderStandalone);
        assertThat(output).contains("Updated");
    }

    @Test
    void spaceAppliesUpdateShowsAppliedMarker() throws Exception {
        var tui = createTuiWithUpdates();
        tui.handleEvent(KeyEvent.ofChar(' '), null); // apply first dep

        String output = render(tui::renderStandalone);
        assertThat(output).contains("[\u00b7]");
    }

    // ── Diff overlay ───────────────────────────────────────────────────────

    @Test
    void diffWithNoChangesShowsMessage() throws Exception {
        var tui = createTuiWithUpdates();
        tui.handleEvent(KeyEvent.ofChar('d'), null);

        String output = render(tui::renderStandalone);
        assertThat(output).contains("No changes to show");
    }

    @Test
    void diffAfterApplyShowsOverlayTitle() throws Exception {
        var tui = createTuiWithUpdates();
        tui.handleEvent(KeyEvent.ofChar(' '), null); // apply dep
        tui.handleEvent(KeyEvent.ofChar('d'), null); // open diff

        String output = render(tui::renderStandalone);
        assertThat(output).contains("POM Changes");
    }

    @Test
    void diffOverlayClosesOnEscape() throws Exception {
        var tui = createTuiWithUpdates();
        tui.handleEvent(KeyEvent.ofChar(' '), null); // apply
        tui.handleEvent(KeyEvent.ofChar('d'), null);
        tui.handleEvent(KeyEvent.ofKey(KeyCode.ESCAPE), null);

        String output = render(tui::renderStandalone);
        assertThat(output).doesNotContain("POM Changes").contains("guava");
    }

    @Test
    void diffAfterMultipleAppliesShowsChanges() throws Exception {
        var tui = createTuiWithUpdates();
        // Apply first dep
        tui.handleEvent(KeyEvent.ofChar(' '), null);
        // Move down and apply second dep
        tui.handleEvent(KeyEvent.ofKey(KeyCode.DOWN), null);
        tui.handleEvent(KeyEvent.ofChar(' '), null);
        tui.handleEvent(KeyEvent.ofChar('d'), null);

        String output = render(tui::renderStandalone);
        assertThat(output).contains("POM Changes");
    }

    // ── Key hints ──────────────────────────────────────────────────────────

    @Test
    void renderShowsKeyBindingHints() throws Exception {
        var tui = createTuiWithUpdates();
        String output = render(tui::renderStandalone);

        assertThat(output).contains("Nav").contains("Apply").contains("Filter").contains("Quit");
    }

    @Test
    void diffOverlayShowsDiffKeyHints() throws Exception {
        var tui = createTuiWithUpdates();
        tui.handleEvent(KeyEvent.ofChar(' '), null); // apply first
        tui.handleEvent(KeyEvent.ofChar('d'), null);

        String output = render(tui::renderStandalone);
        assertThat(output).contains("Scroll").contains("Close");
    }

    // ── Property groups ────────────────────────────────────────────────────

    private UpdatesTui createTuiWithPropertyGroup() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("prop-project"));
        Files.writeString(dir.resolve("pom.xml"), "<project/>");

        var deps = List.of(
                dep("org.springframework", "spring-core", "5.3.0"),
                dep("org.springframework", "spring-web", "5.3.0"),
                dep("com.google.guava", "guava", "33.0.0-jre"));
        var origDeps = List.of(
                dep("org.springframework", "spring-core", "${spring.version}"),
                dep("org.springframework", "spring-web", "${spring.version}"),
                dep("com.google.guava", "guava", "33.0.0-jre"));

        var props = new Properties();
        props.setProperty("spring.version", "5.3.0");
        var project = createProject("com.example", "demo", "1.0.0", dir, deps, List.of(), origDeps, List.of());
        // Replace properties on the project
        var projectWithProps = new PilotProject(
                "com.example",
                "demo",
                "1.0.0",
                "jar",
                dir,
                dir.resolve("pom.xml"),
                deps,
                List.of(),
                origDeps,
                List.of(),
                props,
                null,
                null);

        var result = ReactorCollector.collect(List.of(projectWithProps));
        var model = ReactorModel.build(List.of(projectWithProps));
        var tui = new UpdatesTui(result, model, "com.example:demo:1.0.0", (g, a) -> List.of());

        for (var group : result.propertyGroups) {
            group.newestVersion = "6.1.0";
            group.updateType = VersionComparator.UpdateType.MAJOR;
            for (var d : group.dependencies) {
                d.newestVersion = "6.1.0";
                d.updateType = VersionComparator.UpdateType.MAJOR;
            }
        }
        for (var d : result.ungroupedDependencies) {
            if ("guava".equals(d.artifactId)) {
                d.newestVersion = "33.4.0-jre";
                d.updateType = VersionComparator.UpdateType.MINOR;
            }
        }

        tui.loading = false;
        tui.buildDisplayRows();
        tui.status = "3 update(s) available";
        return tui;
    }

    @Test
    void propertyGroupRendersGroupHeader() throws Exception {
        var tui = createTuiWithPropertyGroup();
        String output = render(tui::renderStandalone);

        assertThat(output).contains("${spring.version}");
    }

    @Test
    void rightArrowExpandsPropertyGroup() throws Exception {
        var tui = createTuiWithPropertyGroup();
        tui.handleEvent(KeyEvent.ofKey(KeyCode.RIGHT), null);

        String output = render(tui::renderStandalone);
        assertThat(output).contains("spring-core").contains("spring-web");
    }

    @Test
    void leftArrowCollapsesExpandedPropertyGroup() throws Exception {
        var tui = createTuiWithPropertyGroup();
        tui.handleEvent(KeyEvent.ofKey(KeyCode.RIGHT), null);
        String expanded = render(tui::renderStandalone);

        tui.handleEvent(KeyEvent.ofKey(KeyCode.LEFT), null);
        String collapsed = render(tui::renderStandalone);

        assertThat(expanded).contains("spring-core").contains("spring-web");
        assertThat(collapsed).isNotEqualTo(expanded);
    }

    @Test
    void leftArrowOnChildDepJumpsToGroupHeader() throws Exception {
        var tui = createTuiWithPropertyGroup();
        tui.handleEvent(KeyEvent.ofKey(KeyCode.RIGHT), null);
        tui.handleEvent(KeyEvent.ofKey(KeyCode.DOWN), null);
        tui.handleEvent(KeyEvent.ofKey(KeyCode.LEFT), null);

        String output = render(tui::renderStandalone);
        assertThat(output).contains("spring-core");
    }

    @Test
    void sortCycleTriggersReorder() throws Exception {
        var tui = createTuiWithPropertyGroup();
        tui.handleEvent(KeyEvent.ofChar('s'), null);

        String output = render(tui::renderStandalone);
        assertThat(output).contains("${spring.version}").contains("guava");
    }
}
