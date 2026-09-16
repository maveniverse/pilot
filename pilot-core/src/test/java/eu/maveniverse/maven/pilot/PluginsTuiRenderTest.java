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

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseButton;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.TickEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Additional rendering and event-handling tests for PluginsTui to improve coverage
 * of filter cycling, search, sort, standalone event dispatch, and detail pane rendering.
 */
class PluginsTuiRenderTest {

    @TempDir
    Path tempDir;

    private Path subdir(String name) throws IOException {
        return Files.createDirectories(tempDir.resolve(name));
    }

    private PilotProject createProject(
            String groupId,
            String artifactId,
            String version,
            Path basedir,
            List<PilotProject.Plugin> plugins,
            List<PilotProject.Plugin> managedPlugins) {
        PilotProject pp = new PilotProject(
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
        pp.setPlugins(plugins);
        pp.setManagedPlugins(managedPlugins);
        return pp;
    }

    private PluginsTui createTui(PilotProject project, List<PilotProject> allProjects) {
        return new PluginsTui(project, allProjects, (g, a) -> List.of());
    }

    private void renderFrame(PluginsTui tui) {
        String output = TuiTestHelper.render(tui::renderStandalone);
        assertThat(output).isNotEmpty();
    }

    // ── Filter cycling ────────────────────────────────────────────────────────

    @Test
    void filterCycleForwardWithFKey() throws IOException {
        Path dir = subdir("filter-f");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));
        tui.loading = false;

        // Switch to Updates view first
        tui.setActiveSubView(2);

        // Cycling f key changes filter (no-op on content but code path hit)
        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('f'))).isTrue();
        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('f'))).isTrue();
        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('f'))).isTrue();
        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('f'))).isTrue(); // wraps back to ALL
    }

    @Test
    void filterCycleBackwardWithShiftFKey() throws IOException {
        Path dir = subdir("filter-F");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));
        tui.loading = false;
        tui.setActiveSubView(2);

        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('F'))).isTrue();
        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('F'))).isTrue();
    }

    @Test
    void filterKeyIgnoredInPluginsView() throws IOException {
        Path dir = subdir("filter-plugins-view");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));

        // 'f' / 'F' are not consumed in Plugins view
        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('f'))).isFalse();
        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('F'))).isFalse();
    }

    // ── Search ────────────────────────────────────────────────────────────────

    @Test
    void searchModeActivatedBySlash() throws IOException {
        Path dir = subdir("search-slash");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0"),
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-surefire-plugin", "3.2.5")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));

        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('/'))).isTrue();
        // In search mode, status should show "Search:"
        assertThat(tui.status()).contains("Search");
    }

    @Test
    void searchTypingAndEnter() throws IOException {
        Path dir = subdir("search-type");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0"),
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-surefire-plugin", "3.2.5")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));

        // Enter search mode
        tui.handleKeyEvent(KeyEvent.ofChar('/'));
        // Type "compiler"
        for (char c : "compiler".toCharArray()) {
            tui.handleKeyEvent(KeyEvent.ofChar(c));
        }
        assertThat(tui.status()).contains("Search").contains("compiler");

        // Press Enter to confirm
        tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
        // Now active search is set, status reflects match count
        assertThat(tui.status()).contains("1 match");
    }

    @Test
    void searchEscapeClearsSearch() throws IOException {
        Path dir = subdir("search-esc");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));

        tui.handleKeyEvent(KeyEvent.ofChar('/'));
        tui.handleKeyEvent(KeyEvent.ofChar('c'));
        // Escape in search mode clears it
        tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE));
        // No longer in search mode
        assertThat(tui.status()).doesNotContain("Search");
    }

    @Test
    void searchBackspaceRemovesChar() throws IOException {
        Path dir = subdir("search-backspace");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));

        tui.handleKeyEvent(KeyEvent.ofChar('/'));
        tui.handleKeyEvent(KeyEvent.ofChar('c'));
        tui.handleKeyEvent(KeyEvent.ofChar('o'));
        tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.BACKSPACE));
        // Should still be in search mode with one char
        assertThat(tui.status()).contains("Search").contains("c");
    }

    @Test
    void searchNAndNNavigateMatches() throws IOException {
        Path dir = subdir("search-nav");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0"),
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-surefire-plugin", "3.2.5"),
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-jar-plugin", "3.3.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));

        // Search for "maven"
        tui.handleKeyEvent(KeyEvent.ofChar('/'));
        for (char c : "maven".toCharArray()) {
            tui.handleKeyEvent(KeyEvent.ofChar(c));
        }
        tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));

        // Navigate with n
        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('n'))).isTrue();
        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('n'))).isTrue();
        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('N'))).isTrue();
    }

    // ── Sort ──────────────────────────────────────────────────────────────────

    @Test
    void sortKeyOnPluginsView() throws IOException {
        Path dir = subdir("sort-plugins");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0"),
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-surefire-plugin", "3.2.5")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));

        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('s'))).isTrue();
        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('S'))).isTrue();
        // Render to exercise sorted render path
        renderFrame(tui);
    }

    @Test
    void sortKeyOnManagedView() throws IOException {
        Path dir = subdir("sort-managed");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(),
                List.of(
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0"),
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-jar-plugin", "3.3.0")));
        PluginsTui tui = createTui(project, List.of(project));
        tui.setActiveSubView(1);

        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('s'))).isTrue();
        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('S'))).isTrue();
        renderFrame(tui);
    }

    @Test
    void sortKeyOnUpdatesViewWithEntries() throws IOException {
        Path dir = subdir("sort-updates");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));
        // Manually set an update on the first plugin entry
        PluginsTui.PluginEntry entry = tui.plugins.get(0);
        entry.newestVersion = "3.12.0";
        entry.updateType = VersionComparator.UpdateType.PATCH;
        tui.loading = false;
        tui.applyFilter();
        tui.setActiveSubView(2);

        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('s'))).isTrue();
        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('S'))).isTrue();
        renderFrame(tui);
    }

    // ── Render paths with updates ─────────────────────────────────────────────

    @Test
    void renderUpdatesViewWithAvailableUpdate() throws IOException {
        Path dir = subdir("render-update");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));

        PluginsTui.PluginEntry entry = tui.plugins.get(0);
        entry.newestVersion = "3.12.0";
        entry.updateType = VersionComparator.UpdateType.PATCH;
        tui.loading = false;
        tui.applyFilter();
        tui.setActiveSubView(2);

        String output = TuiTestHelper.render(tui::renderStandalone);
        assertThat(output).contains("maven-compiler-plugin").contains("3.11.0");
    }

    @Test
    void renderDetailPaneForManagedPlugin() throws IOException {
        Path dir = subdir("detail-managed");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(),
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-jar-plugin", "3.3.0")));
        PluginsTui tui = createTui(project, List.of(project));
        tui.loading = false;
        tui.setActiveSubView(1); // Managed view

        String output = TuiTestHelper.render(tui::renderStandalone);
        assertThat(output).contains("maven-jar-plugin");
    }

    @Test
    void renderDetailPaneWithUpdateInfo() throws IOException {
        Path dir = subdir("detail-update");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));

        // Set up a major update
        PluginsTui.PluginEntry entry = tui.plugins.get(0);
        entry.newestVersion = "4.0.0";
        entry.updateType = VersionComparator.UpdateType.MAJOR;
        tui.loading = false;
        tui.applyFilter();
        tui.setActiveSubView(2); // Updates view

        String output = TuiTestHelper.render(tui::renderStandalone);
        assertThat(output).contains("3.11.0");
    }

    @Test
    void renderStatusWithActiveSearch() throws IOException {
        Path dir = subdir("status-search");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0"),
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-jar-plugin", "3.3.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));
        tui.loading = false;
        tui.applyFilter();

        // Set up active search with match
        tui.handleKeyEvent(KeyEvent.ofChar('/'));
        for (char c : "compiler".toCharArray()) {
            tui.handleKeyEvent(KeyEvent.ofChar(c));
        }
        tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));

        // Status should include match count
        String status = tui.status();
        assertThat(status).contains("match");
    }

    @Test
    void renderKeyHintsInSearchMode() throws IOException {
        Path dir = subdir("hints-search");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));

        tui.handleKeyEvent(KeyEvent.ofChar('/'));
        // Key hints in search mode should contain search-specific hints
        assertThat(tui.keyHints()).isNotEmpty();
    }

    @Test
    void renderKeyHintsInUpdatesView() throws IOException {
        Path dir = subdir("hints-updates");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));
        tui.loading = false;
        tui.setActiveSubView(2);

        // Key hints in Updates view should include 'f' for filter
        List<?> hints = tui.keyHints();
        assertThat(hints).isNotEmpty();
        String hintsText =
                hints.stream().map(Object::toString).reduce("", (a, b) -> a + b).toLowerCase();
        assertThat(hintsText).contains("f");
    }

    // ── Standalone event handling (handleEvent) ───────────────────────────────

    @Test
    void handleEventDigitKeySwitchesView() throws IOException {
        Path dir = subdir("digit-switch");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));

        // Digit '2' should switch to Managed view (index 1)
        tui.handleEvent(KeyEvent.ofChar('2'), null);
        assertThat(tui.activeSubView()).isEqualTo(1);

        // Digit '3' should switch to Updates view (index 2)
        tui.handleEvent(KeyEvent.ofChar('3'), null);
        assertThat(tui.activeSubView()).isEqualTo(2);

        // Digit '1' should switch back to Plugins view (index 0)
        tui.handleEvent(KeyEvent.ofChar('1'), null);
        assertThat(tui.activeSubView()).isZero();
    }

    @Test
    void handleEventMouseEventReturnsTrue() throws IOException {
        Path dir = subdir("mouse");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));

        MouseEvent click = MouseEvent.press(MouseButton.LEFT, 5, 5);
        boolean result = tui.handleEvent(click, null);
        assertThat(result).isTrue();
    }

    @Test
    void handleEventTickEventReturnsFalseWhenNotLoading() throws IOException {
        Path dir = subdir("tick");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));
        tui.loading = false;

        TickEvent tick = TickEvent.of(1, Duration.ofMillis(20));
        // needsTickRedraw() returns false when not loading and not datesLoading
        boolean result = tui.handleEvent(tick, null);
        assertThat(result).isFalse();
    }

    @Test
    void handleEventTickEventReturnsTrueWhenLoading() throws IOException {
        Path dir = subdir("tick-loading");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));
        // loading is true by default

        TickEvent tick = TickEvent.of(1, Duration.ofMillis(20));
        boolean result = tui.handleEvent(tick, null);
        assertThat(result).isTrue();
    }

    @Test
    void handleEventUnknownKeyReturnsFalse() throws IOException {
        Path dir = subdir("unknown-key");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));

        // A digit key >= number of views should be ignored
        boolean result = tui.handleEvent(KeyEvent.ofChar('9'), null);
        // No view index 8 in PluginsTui (only 3 views), so digit '9' is not consumed
        // handleSimpleStandaloneEvent will be called but 'h'/'q' etc. don't match '9'
        assertThat(result).isFalse();
    }

    // ── Status line edge cases ────────────────────────────────────────────────

    @Test
    void statusAfterLoadingComplete() throws IOException {
        Path dir = subdir("status-after-load");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));
        tui.loading = false;
        tui.applyFilter();
        // Simulate statusText update that onVersionsComplete() would perform
        tui.statusText = "0 plugin update(s) available";
        String status = tui.status();
        assertThat(status).doesNotContain("Loading");
    }

    @Test
    void subViewNamesAfterLoadingComplete() throws IOException {
        Path dir = subdir("subview-names");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));
        tui.loading = false;
        tui.applyFilter();

        List<String> names = tui.subViewNames();
        assertThat(names).hasSize(3);
        assertThat(names.get(0)).contains("Plugins");
        assertThat(names.get(1)).contains("Managed");
        // Updates view shows count when loading is complete
        assertThat(names.get(2)).contains("Updates");
        assertThat(names.get(2)).contains("(");
    }

    // ── Multi-module rendering ─────────────────────────────────────────────────

    @Test
    void renderPluginsTableMultiModule() throws IOException {
        Path dir1 = subdir("multi-1");
        Path dir2 = subdir("multi-2");
        PilotProject p1 = createProject(
                "com.example",
                "mod1",
                "1.0",
                dir1,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PilotProject p2 = createProject(
                "com.example",
                "mod2",
                "1.0",
                dir2,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-surefire-plugin", "3.2.5")),
                List.of());
        PluginsTui tui = new PluginsTui(p1, List.of(p1, p2), (g, a) -> List.of());
        tui.loading = false;

        String output = TuiTestHelper.render(tui::renderStandalone);
        assertThat(output).contains("maven-compiler-plugin").contains("maven-surefire-plugin");
    }

    @Test
    void renderUpdatesTableMultiModuleWithUpdate() throws IOException {
        Path dir1 = subdir("multi-update-1");
        Path dir2 = subdir("multi-update-2");
        PilotProject p1 = createProject(
                "com.example",
                "mod1",
                "1.0",
                dir1,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PilotProject p2 = createProject(
                "com.example",
                "mod2",
                "1.0",
                dir2,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = new PluginsTui(p1, List.of(p1, p2), (g, a) -> List.of());

        // Set up minor update
        PluginsTui.PluginEntry entry = tui.plugins.get(0);
        entry.newestVersion = "3.12.0";
        entry.updateType = VersionComparator.UpdateType.MINOR;
        tui.loading = false;
        tui.applyFilter();
        tui.setActiveSubView(2);

        String output = TuiTestHelper.render(tui::renderStandalone);
        assertThat(output).contains("maven-compiler-plugin");
    }

    // ── needsTickRedraw ───────────────────────────────────────────────────────

    @Test
    void needsTickRedrawTrueWhenDatesLoading() throws IOException {
        Path dir = subdir("dates-loading");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));
        tui.loading = false;
        tui.datesLoading = true;

        assertThat(tui.needsTickRedraw()).isTrue();
    }
}
