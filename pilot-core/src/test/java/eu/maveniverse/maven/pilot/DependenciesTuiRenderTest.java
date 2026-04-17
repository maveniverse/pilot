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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rendering and interaction tests for {@link DependenciesTui}.
 *
 * <p>Complements {@link DependenciesTuiTest} (which tests data model logic)
 * with assertions on the actual rendered terminal output.</p>
 */
class DependenciesTuiRenderTest {

    @TempDir
    Path tempDir;

    private String pomPath;

    @BeforeEach
    void setUp() throws Exception {
        pomPath = Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();
    }

    private DependenciesTui createTuiWithDeps() {
        List<DependenciesTui.DepEntry> declared = new ArrayList<>();

        var d1 = new DependenciesTui.DepEntry("com.google.guava", "guava", "", "33.0.0-jre", "compile", true);
        d1.usageStatus = DependencyUsageAnalyzer.UsageStatus.USED;
        declared.add(d1);

        var d2 = new DependenciesTui.DepEntry("org.slf4j", "slf4j-api", "", "2.0.9", "compile", true);
        d2.usageStatus = DependencyUsageAnalyzer.UsageStatus.USED;
        declared.add(d2);

        var d3 = new DependenciesTui.DepEntry("commons-io", "commons-io", "", "2.15.1", "compile", true);
        d3.usageStatus = DependencyUsageAnalyzer.UsageStatus.UNUSED;
        declared.add(d3);

        var d4 = new DependenciesTui.DepEntry("org.junit.jupiter", "junit-jupiter", "", "5.11.4", "test", true);
        d4.usageStatus = DependencyUsageAnalyzer.UsageStatus.USED;
        declared.add(d4);

        List<DependenciesTui.DepEntry> transitive = new ArrayList<>();

        var t1 = new DependenciesTui.DepEntry("com.google.guava", "failureaccess", "", "1.0.2", "compile", false);
        t1.pulledBy = "guava";
        t1.usageStatus = DependencyUsageAnalyzer.UsageStatus.USED;
        transitive.add(t1);

        var t2 = new DependenciesTui.DepEntry("org.checkerframework", "checker-qual", "", "3.42.0", "compile", false);
        t2.pulledBy = "guava";
        t2.usageStatus = DependencyUsageAnalyzer.UsageStatus.UNUSED;
        transitive.add(t2);

        return new DependenciesTui(declared, transitive, pomPath, "com.example:demo:1.0.0", true);
    }

    // ── Declared dependencies view ─────────────────────────────────────────

    @Test
    void renderShowsDeclaredDependencies() {
        var tui = createTuiWithDeps();
        String output = render(tui::renderStandalone);

        assertThat(output)
                .contains("com.google.guava:guava")
                .contains("org.slf4j:slf4j-api")
                .contains("commons-io:commons-io")
                .contains("org.junit.jupiter:junit-jupiter");
    }

    @Test
    void renderShowsVersions() {
        var tui = createTuiWithDeps();
        String output = render(tui::renderStandalone);

        assertThat(output)
                .contains("33.0.0-jre")
                .contains("2.0.9")
                .contains("2.15.1")
                .contains("5.11.4");
    }

    @Test
    void renderShowsProjectGav() {
        var tui = createTuiWithDeps();
        String output = render(tui::renderStandalone);

        assertThat(output).contains("com.example:demo:1.0.0");
    }

    @Test
    void renderShowsScopes() {
        var tui = createTuiWithDeps();
        String output = render(tui::renderStandalone);

        assertThat(output).contains("compile").contains("test");
    }

    // ── Tab switching ──────────────────────────────────────────────────────

    @Test
    void tabSwitchShowsTransitiveDependencies() {
        var tui = createTuiWithDeps();
        tui.handleEvent(KeyEvent.ofKey(KeyCode.TAB), null); // switch to Transitive tab

        String output = render(tui::renderStandalone);
        assertThat(output).contains("failureaccess").contains("checker-qual");
    }

    @Test
    void tabSwitchBackShowsDeclaredAgain() {
        var tui = createTuiWithDeps();
        int tabCount = tui.subViewCount();

        // Cycle through all tabs to get back to first
        for (int i = 0; i < tabCount; i++) {
            tui.handleEvent(KeyEvent.ofKey(KeyCode.TAB), null);
        }

        String output = render(tui::renderStandalone);
        assertThat(output).contains("com.google.guava:guava").contains("org.slf4j:slf4j-api");
    }

    // ── Navigation ─────────────────────────────────────────────────────────

    @Test
    void navigationChangesRenderedOutput() {
        var tui = createTuiWithDeps();
        String initial = render(tui::renderStandalone);

        tui.handleEvent(KeyEvent.ofKey(KeyCode.DOWN), null);
        String afterNav = render(tui::renderStandalone);

        assertThat(afterNav).isNotEqualTo(initial);
    }

    // ── Divider ────────────────────────────────────────────────────────────

    @Test
    void panelModeDividerBetweenTableAndDetails() {
        var tui = createTuiWithDeps();
        String output = render(f -> tui.render(f, f.area()));

        assertThat(output).contains("─".repeat(10));
    }

    // ── Empty state ────────────────────────────────────────────────────────

    @Test
    void renderWithNoDeclaredDependencies() {
        var tui = new DependenciesTui(List.of(), List.of(), pomPath, "com.example:app:1.0", true);

        String output = render(tui::renderStandalone);
        assertThat(output).contains("com.example:app:1.0");
    }

    @Test
    void renderWithNoTransitiveDependencies() {
        List<DependenciesTui.DepEntry> declared = new ArrayList<>();
        declared.add(new DependenciesTui.DepEntry("com.example", "lib", "", "1.0", "compile", true));

        var tui = new DependenciesTui(declared, List.of(), pomPath, "com.example:app:1.0", true);
        tui.handleEvent(KeyEvent.ofKey(KeyCode.TAB), null); // switch to Transitive tab

        String output = render(tui::renderStandalone);
        // Should render without errors even with no transitive deps
        assertThat(output).isNotEmpty();
    }
}
