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

import dev.tamboui.layout.Size;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.pilot.TuiTestRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for PluginsTui that exercise the async version-fetch path
 * (setRunner → fetchAllUpdates → applyVersionResult → onVersionsComplete)
 * using TuiTestRunner so the render loop processes render-thread callbacks.
 */
class PluginsTuiAsyncTest {

    private static final int WIDTH = 120;
    private static final int HEIGHT = 30;

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

    /** Synchronous resolver that returns a newer version immediately. */
    private UpdatesTui.VersionResolver updatingResolver(String newerVersion) {
        return (g, a) -> List.of(newerVersion, "999.0.0-SNAPSHOT");
    }

    /** Resolver that always returns empty (no updates). */
    private UpdatesTui.VersionResolver noUpdateResolver() {
        return (g, a) -> List.of();
    }

    /** Poll until loading finishes or 5-second timeout. */
    @SuppressWarnings("java:S2925") // Thread.sleep is intentional in this test helper
    private void waitForLoading(PluginsTui tui) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (tui.loading && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    // ── Empty plugins fast-path ──────────────────────────────────────────────

    @Test
    void emptyPluginsCompletesImmediately() throws Exception {
        Path dir = subdir("empty");
        PilotProject project = createProject("com.example", "app", "1.0", dir, List.of(), List.of());
        PluginsTui tui = new PluginsTui(project, List.of(project), noUpdateResolver());
        assertThat(tui.loading).isTrue();

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            // setRunner triggers fetchAllUpdates; empty list → loading=false synchronously
            tui.setRunner(testRunner.runner());
            pilot.pause();
            assertThat(tui.loading).isFalse();
            assertThat(tui.status()).contains("0 plugin update");
            pilot.press('q');
        }
        tui.close();
    }

    // ── Version resolution path ──────────────────────────────────────────────

    @Test
    void asyncLoadingWithNoUpdatesAvailable() throws Exception {
        Path dir = subdir("no-update");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());

        PluginsTui tui = new PluginsTui(project, List.of(project), noUpdateResolver());

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            tui.setRunner(testRunner.runner());
            pilot.pause(Duration.ofMillis(500));
            pilot.pause();
            waitForLoading(tui);

            assertThat(tui.loading).isFalse();
            assertThat(tui.status()).contains("0 plugin update");
            pilot.press('q');
        }
        tui.close();
    }

    @Test
    void asyncLoadingWithUpdateSetsNewestVersionAndStatus() throws Exception {
        Path dir = subdir("with-update");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());

        PluginsTui tui = new PluginsTui(project, List.of(project), updatingResolver("3.14.0"));

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            tui.setRunner(testRunner.runner());
            pilot.pause(Duration.ofMillis(500));
            pilot.pause();
            waitForLoading(tui);

            assertThat(tui.loading).isFalse();
            assertThat(tui.plugins.get(0).newestVersion).isEqualTo("3.14.0");
            assertThat(tui.status()).contains("1 plugin update");
            pilot.press('q');
        }
        tui.close();
    }

    @Test
    void asyncLoadingWithSharedGaPropagatesUpdateToBothDeclaredAndManaged() throws Exception {
        Path dir = subdir("shared-ga");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")));

        PluginsTui tui = new PluginsTui(project, List.of(project), updatingResolver("3.14.0"));

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            tui.setRunner(testRunner.runner());
            pilot.pause(Duration.ofMillis(500));
            pilot.pause();
            waitForLoading(tui);

            assertThat(tui.loading).isFalse();
            // Both declared and managed entries share GA — update propagated to both
            assertThat(tui.plugins.get(0).newestVersion).isEqualTo("3.14.0");
            assertThat(tui.managed.get(0).newestVersion).isEqualTo("3.14.0");
            // Updates view: both entries appear (no deduplication)
            assertThat(tui.updates).hasSize(2);
            pilot.press('q');
        }
        tui.close();
    }

    // ── Failure path ─────────────────────────────────────────────────────────

    @Test
    void resolverFailureIncrementsFailed() throws Exception {
        Path dir = subdir("resolver-fail");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());

        PluginsTui tui = new PluginsTui(project, List.of(project), (g, a) -> {
            throw new RuntimeException("network failure");
        });

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            tui.setRunner(testRunner.runner());
            pilot.pause(Duration.ofMillis(500));
            pilot.pause();
            waitForLoading(tui);

            assertThat(tui.loading).isFalse();
            assertThat(tui.failedCount).isEqualTo(1);
            assertThat(tui.status()).contains("lookup(s) failed");
            pilot.press('q');
        }
        tui.close();
    }

    @Test
    void partialFailureReportsCorrectCounts() throws Exception {
        Path dir = subdir("partial-fail");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0"),
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-surefire-plugin", "3.2.5")),
                List.of());

        PluginsTui tui = new PluginsTui(project, List.of(project), (g, a) -> {
            if (a.contains("surefire")) throw new RuntimeException("timeout");
            return List.of("3.14.0");
        });

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            tui.setRunner(testRunner.runner());
            pilot.pause(Duration.ofMillis(500));
            pilot.pause();
            waitForLoading(tui);

            assertThat(tui.loading).isFalse();
            assertThat(tui.failedCount).isEqualTo(1);
            String status = tui.status();
            assertThat(status).contains("plugin update").contains("failed");
            pilot.press('q');
        }
        tui.close();
    }

    // ── Multi-module status message ──────────────────────────────────────────

    @Test
    void multiModuleStatusMessageContainsAcrossModules() throws Exception {
        Path dir1 = subdir("mod1");
        Path dir2 = subdir("mod2");
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

        PluginsTui tui = new PluginsTui(p1, List.of(p1, p2), noUpdateResolver());

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            tui.setRunner(testRunner.runner());
            pilot.pause(Duration.ofMillis(500));
            pilot.pause();
            waitForLoading(tui);

            assertThat(tui.loading).isFalse();
            assertThat(tui.status()).contains("across modules");
            pilot.press('q');
        }
        tui.close();
    }

    // ── setRunner guard: no re-fetch when already loaded ────────────────────

    @Test
    void setRunnerWhenAlreadyLoadedDoesNotRefetch() throws Exception {
        Path dir = subdir("no-refetch");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());

        int[] callCount = {0};
        PluginsTui tui = new PluginsTui(project, List.of(project), (g, a) -> {
            callCount[0]++;
            return List.of();
        });
        tui.loading = false; // simulate already loaded

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            tui.setRunner(testRunner.runner()); // should NOT trigger refetch since loading=false
            pilot.pause(Duration.ofMillis(200));
            pilot.pause();
            assertThat(callCount[0]).isZero();
            pilot.press('q');
        }
        tui.close();
    }

    // ── Rendering paths ──────────────────────────────────────────────────────

    @Test
    void renderPluginsViewAfterLoading() throws Exception {
        Path dir = subdir("render-plugins");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0"),
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-surefire-plugin", "3.2.5")),
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-jar-plugin", "3.3.0")));

        PluginsTui tui = new PluginsTui(project, List.of(project), noUpdateResolver());

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            tui.setRunner(testRunner.runner());
            pilot.pause(Duration.ofMillis(300));
            pilot.pause();
            waitForLoading(tui);

            // Plugins view
            String rendered = TuiTestHelper.render(tui::renderStandalone);
            assertThat(rendered).contains("maven-compiler-plugin").contains("maven-surefire-plugin");

            // Managed view — dispatch digit key directly (synchronous, avoids race with TuiTestHelper.render)
            tui.handleEvent(KeyEvent.ofChar('2'), null);
            rendered = TuiTestHelper.render(tui::renderStandalone);
            assertThat(rendered).contains("maven-jar-plugin");

            // Updates view (empty — no updates)
            tui.handleEvent(KeyEvent.ofChar('3'), null);
            rendered = TuiTestHelper.render(tui::renderStandalone);
            assertThat(rendered).isNotEmpty();

            pilot.press('q');
        }
        tui.close();
    }

    @Test
    void renderUpdatesViewWithEntries() throws Exception {
        Path dir = subdir("render-updates");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());

        PluginsTui tui = new PluginsTui(project, List.of(project), updatingResolver("3.14.0"));

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            tui.setRunner(testRunner.runner());
            pilot.pause(Duration.ofMillis(500));
            pilot.pause();
            waitForLoading(tui);

            // Switch to Updates view
            pilot.press('3');
            pilot.pause();
            String rendered = TuiTestHelper.render(tui::renderStandalone);
            assertThat(rendered).contains("maven-compiler-plugin");
            // Updates view header should show
            assertThat(rendered).contains("Updates");

            pilot.press('q');
        }
        tui.close();
    }

    // ── Sort keys ────────────────────────────────────────────────────────────

    @Test
    void sortKeysCycleInPluginsView() throws Exception {
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

        PluginsTui tui = new PluginsTui(project, List.of(project), noUpdateResolver());

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            tui.setRunner(testRunner.runner());
            pilot.pause(Duration.ofMillis(300));
            pilot.pause();
            waitForLoading(tui);

            // Cycle sort column
            tui.handleKeyEvent(KeyEvent.ofChar('s'));
            tui.handleKeyEvent(KeyEvent.ofChar('s'));
            // Reverse sort direction
            tui.handleKeyEvent(KeyEvent.ofChar('S'));

            // Key hints should be non-empty
            List<dev.tamboui.text.Span> hints = tui.keyHints();
            assertThat(hints).isNotEmpty();

            pilot.press('q');
        }
        tui.close();
    }

    @Test
    void sortKeysCycleInUpdatesView() throws Exception {
        Path dir = subdir("sort-updates");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());

        PluginsTui tui = new PluginsTui(project, List.of(project), updatingResolver("4.0.0"));

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            tui.setRunner(testRunner.runner());
            pilot.pause(Duration.ofMillis(500));
            pilot.pause();
            waitForLoading(tui);

            // Switch to Updates view
            pilot.press('3');
            pilot.pause();
            // Sort in Updates view
            tui.handleKeyEvent(KeyEvent.ofChar('s'));
            tui.handleKeyEvent(KeyEvent.ofChar('S'));

            String status = tui.status();
            assertThat(status).isNotNull();
            pilot.press('q');
        }
        tui.close();
    }

    // ── Filter cycling ───────────────────────────────────────────────────────

    @Test
    void filterCyclingInUpdatesView() throws Exception {
        Path dir = subdir("filter-updates");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());

        PluginsTui tui = new PluginsTui(project, List.of(project), updatingResolver("4.0.0"));

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            tui.setRunner(testRunner.runner());
            pilot.pause(Duration.ofMillis(500));
            pilot.pause();
            waitForLoading(tui);

            // Switch to Updates view
            pilot.press('3');
            pilot.pause();

            // Cycle filter 4 times (ALL → PATCH → MINOR → MAJOR → ALL)
            for (int i = 0; i < 4; i++) {
                tui.handleKeyEvent(KeyEvent.ofChar('f'));
                String rendered = TuiTestHelper.render(tui::renderStandalone);
                assertThat(rendered).isNotEmpty();
            }

            pilot.press('q');
        }
        tui.close();
    }

    // ── Search ───────────────────────────────────────────────────────────────

    @Test
    void searchInPluginsView() throws Exception {
        Path dir = subdir("search");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0"),
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-surefire-plugin", "3.2.5")),
                List.of());

        PluginsTui tui = new PluginsTui(project, List.of(project), noUpdateResolver());

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            tui.setRunner(testRunner.runner());
            pilot.pause(Duration.ofMillis(300));
            pilot.pause();
            waitForLoading(tui);

            // Enter search mode
            tui.handleKeyEvent(KeyEvent.ofChar('/'));
            assertThat(tui.status()).contains("Search");

            // Type a query
            for (char c : "compiler".toCharArray()) {
                tui.handleKeyEvent(KeyEvent.ofChar(c));
            }
            // Confirm
            tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
            assertThat(tui.status()).contains("match");

            // Navigate
            tui.handleKeyEvent(KeyEvent.ofChar('n'));
            tui.handleKeyEvent(KeyEvent.ofChar('N'));

            // Clear search
            tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE));

            pilot.press('q');
        }
        tui.close();
    }

    // ── needsTickRedraw ───────────────────────────────────────────────────────

    @Test
    void needsTickRedrawReturnsTrueWhileLoading() throws Exception {
        Path dir = subdir("tick-loading");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());

        PluginsTui tui = new PluginsTui(project, List.of(project), noUpdateResolver());
        // loading=true by construction
        assertThat(tui.needsTickRedraw()).isTrue();
    }

    @Test
    void needsTickRedrawReturnsFalseAfterLoading() throws Exception {
        Path dir = subdir("tick-done");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());

        PluginsTui tui = new PluginsTui(project, List.of(project), noUpdateResolver());
        tui.loading = false;
        tui.datesLoading = false;
        assertThat(tui.needsTickRedraw()).isFalse();
    }

    // ── Help sections ─────────────────────────────────────────────────────────

    @Test
    void helpSectionsAreNonEmpty() throws Exception {
        Path dir = subdir("help");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());

        PluginsTui tui = new PluginsTui(project, List.of(project), noUpdateResolver());
        List<HelpOverlay.Section> sections = tui.helpSections();
        assertThat(sections).isNotEmpty();
    }

    // ── close() ───────────────────────────────────────────────────────────────

    @Test
    void closeShutdownsHttpPool() throws Exception {
        Path dir = subdir("close");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());

        PluginsTui tui = new PluginsTui(project, List.of(project), noUpdateResolver());
        tui.close(); // should not throw even before any runner is set
        // httpPool is shut down — further submits will be rejected
        assertThat(tui.httpPool.isShutdown()).isTrue();
    }
}
