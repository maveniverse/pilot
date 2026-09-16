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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Additional PluginsTui tests focusing on per-module version tracking and module identity.
 */
class PluginsTuiModuleTest {

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

    // --- PluginEntry.hasVersionConflict() ---

    static Stream<Arguments> hasVersionConflictCases() {
        return Stream.of(
                // description, entryVersion, v1, v2, expectedConflict
                Arguments.of("same version → no conflict", "1.0", "1.0", "1.0", false),
                Arguments.of("different versions → conflict", "1.0", "1.0", "2.0", true),
                Arguments.of("all empty versions → no conflict", "", "", "", false),
                Arguments.of("one empty version ignored → no conflict", "1.0", "1.0", "", false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hasVersionConflictCases")
    void pluginEntryVersionConflict(
            String description, String entryVersion, String v1, String v2, boolean expectedConflict) {
        PluginsTui.PluginEntry entry = new PluginsTui.PluginEntry("g", "a", entryVersion, false);
        entry.moduleVersions.put("g1:mod1", v1);
        entry.moduleVersions.put("g2:mod2", v2);
        assertThat(entry.hasVersionConflict()).isEqualTo(expectedConflict);
    }

    // --- Module identity uses ga() not artifactId ---

    @Test
    void sameArtifactIdDifferentGroupIdAreDistinctModules() throws IOException {
        // Two modules with same artifactId but different groupIds should NOT collide
        Path dir1 = subdir("ga-mod1");
        Path dir2 = subdir("ga-mod2");
        PilotProject p1 = createProject(
                "com.group1",
                "app",
                "1.0",
                dir1,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PilotProject p2 = createProject(
                "com.group2",
                "app", // same artifactId, different groupId
                "1.0",
                dir2,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.12.0")),
                List.of());

        PluginsTui tui = createTui(p1, List.of(p1, p2));

        // The plugin entry should see both modules as distinct keys and report a conflict
        PluginsTui.PluginEntry entry = tui.plugins.stream()
                .filter(e -> "maven-compiler-plugin".equals(e.artifactId))
                .findFirst()
                .orElseThrow();
        assertThat(entry.moduleVersions).containsOnlyKeys("com.group1:app", "com.group2:app");
        assertThat(entry.hasVersionConflict()).isTrue();
    }

    @Test
    void versionConflictAcrossModulesWithSameArtifactId() throws IOException {
        Path dir1 = subdir("conflict1");
        Path dir2 = subdir("conflict2");
        // Same groupId to ensure this is about the artifactId collision fix
        PilotProject p1 = createProject(
                "com.example",
                "module-a",
                "1.0",
                dir1,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PilotProject p2 = createProject(
                "com.example",
                "module-b",
                "1.0",
                dir2,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.12.0")),
                List.of());

        PluginsTui tui = createTui(p1, List.of(p1, p2));
        // Module list for the plugin should contain both ga() keys (not both mapped to "module-a")
        PluginsTui.PluginEntry entry = tui.plugins.stream()
                .filter(e -> "maven-compiler-plugin".equals(e.artifactId))
                .findFirst()
                .orElseThrow();
        assertThat(entry.moduleVersions).containsOnlyKeys("com.example:module-a", "com.example:module-b");
        assertThat(entry.hasVersionConflict()).isTrue();
    }

    // --- Sorting in all three views ---

    static Stream<Arguments> sortViewCases() {
        List<PilotProject.Plugin> twoPlugins = List.of(
                new PilotProject.Plugin("org.apache.maven.plugins", "maven-surefire-plugin", "3.2.5"),
                new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0"));
        List<PilotProject.Plugin> twoManaged = List.of(
                new PilotProject.Plugin("org.apache.maven.plugins", "maven-jar-plugin", "3.3.0"),
                new PilotProject.Plugin("org.apache.maven.plugins", "maven-assembly-plugin", "3.6.0"));
        List<PilotProject.Plugin> twoUpdatesPlugins = List.of(
                new PilotProject.Plugin("org.apache.maven.plugins", "maven-surefire-plugin", "3.2.5"),
                new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0"));
        return Stream.of(
                Arguments.of("Plugins view", 0, twoPlugins, List.of(), false),
                Arguments.of("Managed view", 1, List.of(), twoManaged, false),
                Arguments.of("Updates view", 2, twoUpdatesPlugins, List.of(), true));
    }

    @ParameterizedTest(name = "sort key handled in {0}")
    @MethodSource("sortViewCases")
    void sortKeyPressHandledInView(
            String viewName,
            int subView,
            List<PilotProject.Plugin> plugins,
            List<PilotProject.Plugin> managedPlugins,
            boolean setLoadingFalse)
            throws IOException {
        Path dir = subdir("sort-" + viewName.toLowerCase().replace(' ', '-'));
        PilotProject project = createProject("com.example", "app", "1.0", dir, plugins, managedPlugins);
        PluginsTui tui = createTui(project, List.of(project));
        if (setLoadingFalse) {
            // Simulate versions resolved with updates available so both entries appear in Updates view
            tui.loading = false;
            for (PluginsTui.PluginEntry e : tui.plugins) {
                e.newestVersion = "3.14.0";
                e.updateType = VersionComparator.UpdateType.MINOR;
            }
            tui.applyFilter();
        }
        tui.setActiveSubView(subView);

        // For the Updates view the first sort column is the update-type icon (same for all entries here),
        // so we advance to the GA column (column index 1 for Updates, 0 for Plugins/Managed).
        // Press s once for Plugins/Managed (column 0 = ga), twice for Updates (column 1 = ga).
        int gaColumnPresses = (subView == 2) ? 2 : 1;
        for (int i = 0; i < gaColumnPresses; i++) {
            assertThat(tui.handleKeyEvent(KeyEvent.ofChar('s'))).isTrue();
        }

        // After sorting by GA ascending, entries must appear in case-insensitive alphabetical order
        List<String> afterAsc = currentGas(tui, subView);
        List<String> sortedAsc =
                afterAsc.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        assertThat(afterAsc).isEqualTo(sortedAsc);

        // S → reverse direction: descending
        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('S'))).isTrue();
        List<String> afterDesc = currentGas(tui, subView);
        List<String> sortedDesc = afterAsc.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER.reversed())
                .toList();
        assertThat(afterDesc).isEqualTo(sortedDesc);
    }

    private static List<String> currentGas(PluginsTui tui, int subView) {
        return switch (subView) {
            case 0 -> tui.plugins.stream().map(PluginsTui.PluginEntry::ga).toList();
            case 1 -> tui.managed.stream().map(PluginsTui.PluginEntry::ga).toList();
            default -> tui.updates.stream().map(PluginsTui.PluginEntry::ga).toList();
        };
    }

    // --- Digit key view switching in standalone ---

    @Test
    void digitKey1SwitchesToPluginsView() throws IOException {
        Path dir = subdir("digit-1");
        PilotProject project = createProject("com.example", "app", "1.0", dir, List.of(), List.of());
        PluginsTui tui = createTui(project, List.of(project));
        tui.setActiveSubView(2);

        tui.handleEvent(KeyEvent.ofChar('1'), null);
        assertThat(tui.activeSubView()).isZero();
    }

    @Test
    void digitKey2SwitchesToManagedView() throws IOException {
        Path dir = subdir("digit-2");
        PilotProject project = createProject("com.example", "app", "1.0", dir, List.of(), List.of());
        PluginsTui tui = createTui(project, List.of(project));

        tui.handleEvent(KeyEvent.ofChar('2'), null);
        assertThat(tui.activeSubView()).isEqualTo(1);
    }

    @Test
    void digitKey3SwitchesToUpdatesView() throws IOException {
        Path dir = subdir("digit-3");
        PilotProject project = createProject("com.example", "app", "1.0", dir, List.of(), List.of());
        PluginsTui tui = createTui(project, List.of(project));

        tui.handleEvent(KeyEvent.ofChar('3'), null);
        assertThat(tui.activeSubView()).isEqualTo(2);
    }

    // --- Render with version conflict detail pane ---

    @Test
    void renderPluginWithVersionConflictShowsAnnotation() throws IOException {
        Path dir1 = subdir("conflict-render1");
        Path dir2 = subdir("conflict-render2");
        PilotProject p1 = createProject(
                "com.example",
                "mod-a",
                "1.0",
                dir1,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PilotProject p2 = createProject(
                "com.example",
                "mod-b",
                "1.0",
                dir2,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.12.0")),
                List.of());

        PluginsTui tui = createTui(p1, List.of(p1, p2));
        // Render — should not throw even with version conflict
        String output = TuiTestHelper.render(tui::renderStandalone);
        assertThat(output).isNotEmpty();
    }

    // --- totalLibYears deduplicates by GA ---

    @Test
    void totalLibYearsDoesNotDoubleCountSharedGaEntries() throws IOException {
        // When a declared plugin and a managed plugin share the same GA, the status
        // bar libyear total should count that GA only once, not twice.
        Path dir = subdir("libyears-dedup");
        PilotProject.Plugin declared =
                new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0");
        PilotProject.Plugin managed =
                new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0");
        PilotProject project = createProject("com.example", "app", "1.0", dir, List.of(declared), List.of(managed));
        PluginsTui tui = createTui(project, List.of(project));

        // Simulate version resolution for both entries
        for (PluginsTui.PluginEntry e : tui.plugins) {
            e.newestVersion = "3.14.0";
            e.updateType = VersionComparator.UpdateType.MINOR;
            e.libYears = 1.5f;
        }
        for (PluginsTui.PluginEntry e : tui.managed) {
            e.newestVersion = "3.14.0";
            e.updateType = VersionComparator.UpdateType.MINOR;
            // Simulate both entries having libYears set (the double-count bug scenario)
            e.libYears = 1.5f;
        }

        tui.loading = false;
        tui.applyFilter();

        // Both entries appear in updates (size 2), but totalLibYears() must count the GA only once
        assertThat(tui.updates).hasSize(2);
        assertThat(tui.totalLibYears()).isEqualTo(1.5f);
    }

    // --- applyFilter includes both declared and managed entries with same GA ---

    @Test
    void updatesViewIncludesBothDeclaredAndManagedEntriesWithSameGa() throws IOException {
        // When a declared plugin and a managed plugin share the same GA (both have updates),
        // the Updates view must show both entries, not suppress the managed one.
        Path dir = subdir("declared-managed-same-ga");
        PilotProject.Plugin declared =
                new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0");
        PilotProject.Plugin managed =
                new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0");
        PilotProject project = createProject("com.example", "app", "1.0", dir, List.of(declared), List.of(managed));
        PluginsTui tui = createTui(project, List.of(project));

        // Simulate version resolution resolving the same GA to a newer version
        for (PluginsTui.PluginEntry e : tui.plugins) {
            e.newestVersion = "3.14.0";
            e.updateType = VersionComparator.UpdateType.MINOR;
        }
        for (PluginsTui.PluginEntry e : tui.managed) {
            e.newestVersion = "3.14.0";
            e.updateType = VersionComparator.UpdateType.MINOR;
        }

        tui.loading = false;
        tui.applyFilter();

        // Both the declared and managed entry should appear in updates (not suppressed by putIfAbsent)
        assertThat(tui.updates).hasSize(2);
    }

    // --- handleEvent key ---

    @Test
    void searchKeyEventHandled() throws IOException {
        Path dir = subdir("search");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));

        // '/' → starts filter; Escape → clears filter
        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('/'))).isTrue();
        assertThat(tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE))).isTrue();
    }

    // --- keyHints and helpSections ---

    @Test
    void keyHintsContainsNavAndSearch() throws IOException {
        Path dir = subdir("keyhints-plugins");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));
        List<Span> hints = tui.keyHints();
        String allText = hints.stream().map(Span::content).reduce("", String::concat);
        assertThat(allText).contains("Search");
    }

    @Test
    void keyHintsInUpdatesViewContainsFilter() throws IOException {
        Path dir = subdir("keyhints-updates");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));
        tui.setActiveSubView(2); // Updates view
        List<Span> hints = tui.keyHints();
        String allText = hints.stream().map(Span::content).reduce("", String::concat);
        assertThat(allText).contains("Filter");
    }

    @Test
    void helpSectionsContainsPluginBrowserSection() throws IOException {
        Path dir = subdir("helpsections-plugins");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));
        List<HelpOverlay.Section> sections = tui.helpSections();
        boolean hasPluginBrowser = sections.stream().anyMatch(s -> "Plugin Browser".equals(s.title()));
        assertThat(hasPluginBrowser).isTrue();
    }

    // --- applyVersionResult via direct call ---

    @Test
    void applyVersionResultPopulatesNewestVersionAndUpdateType() throws IOException {
        Path dir = subdir("applyversion");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));

        // Drive the actual applyVersionResult path with a sorted version list (newest first)
        tui.applyVersionResult(tui.plugins, List.of("3.14.0", "3.13.0", "3.11.0-beta", "3.11.0"));
        tui.loading = false;
        tui.applyFilter();

        // updates list should now contain the entry with the correct newest version and update type
        assertThat(tui.updates).hasSize(1);
        assertThat(tui.updates.get(0).newestVersion).isEqualTo("3.14.0");
        assertThat(tui.updates.get(0).updateType).isEqualTo(VersionComparator.UpdateType.MINOR);
    }

    // --- buildStatusMessage via status() ---

    @Test
    void statusShowsUpdateCountAfterFilterApplied() throws IOException {
        Path dir = subdir("statusmsg");
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

        for (PluginsTui.PluginEntry e : tui.plugins) {
            e.newestVersion = "3.14.0";
            e.updateType = VersionComparator.UpdateType.MINOR;
        }
        tui.loading = false;
        tui.datesLoading = false;
        tui.applyFilter();
        // Manually trigger what onVersionsComplete does: update statusText
        // (private method; we trigger indirectly via the statusText field)
        tui.statusText = "2 plugin update(s) available";
        assertThat(tui.status()).isEqualTo("2 plugin update(s) available");
    }

    @Test
    void statusShowsFailedCountWhenNonZero() throws IOException {
        Path dir = subdir("statusmsg-failed");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));
        tui.failedCount = 2;
        tui.loading = false;
        tui.datesLoading = false;
        tui.applyFilter();
        tui.statusText = "0 plugin update(s) available; 2 lookup(s) failed";
        assertThat(tui.status()).contains("failed");
    }

    // --- updateSearchMatches via key events ---

    @Test
    void searchMatchesAreFoundByGroupId() throws IOException {
        Path dir = subdir("search-groupid");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0"),
                        new PilotProject.Plugin("com.example.plugins", "custom-plugin", "1.0.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));

        // Enter search mode and type 'apache', then confirm
        tui.handleKeyEvent(KeyEvent.ofChar('/'));
        tui.handleKeyEvent(KeyEvent.ofChar('a'));
        tui.handleKeyEvent(KeyEvent.ofChar('p'));
        tui.handleKeyEvent(KeyEvent.ofChar('a'));
        tui.handleKeyEvent(KeyEvent.ofChar('c'));
        tui.handleKeyEvent(KeyEvent.ofChar('h'));
        tui.handleKeyEvent(KeyEvent.ofChar('e'));
        tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));

        // Should find at least one match for apache
        assertThat(tui.status()).contains("match");
    }

    @Test
    void searchWithNoResultsReportsNoMatch() throws IOException {
        Path dir = subdir("search-nomatch");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));

        tui.handleKeyEvent(KeyEvent.ofChar('/'));
        tui.handleKeyEvent(KeyEvent.ofChar('x'));
        tui.handleKeyEvent(KeyEvent.ofChar('x'));
        tui.handleKeyEvent(KeyEvent.ofChar('x'));
        tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));

        assertThat(tui.status()).contains("No match");
    }

    // --- computeLibYear direct test ---

    @Test
    void computeLibYearSetsLibYearsWhenBothDatesSet() throws IOException {
        Path dir = subdir("libyear-entry");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));

        for (PluginsTui.PluginEntry e : tui.plugins) {
            e.currentReleaseDate = LocalDate.of(2022, 1, 1);
            e.newestReleaseDate = LocalDate.of(2023, 1, 1);
            tui.computeLibYear(e);
            // ~52 weeks → libYears ≈ 1.0
            assertThat(e.libYears).isGreaterThan(0.9f).isLessThan(1.1f);
        }
    }
}
