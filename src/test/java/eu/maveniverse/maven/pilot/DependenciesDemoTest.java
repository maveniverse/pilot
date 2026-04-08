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
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.pilot.TuiTestRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Demo test for Dependency Analysis TUI.
 */
class DependenciesDemoTest {

    @Test
    void browseDeclaredAndTransitive(@TempDir Path tempDir) throws Exception {
        String pomPath =
                Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();
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

        List<DependenciesTui.DepEntry> transitive = new ArrayList<>();
        var t1 = new DependenciesTui.DepEntry("com.google.guava", "failureaccess", "", "1.0.2", "compile", false);
        t1.pulledBy = "guava";
        t1.usageStatus = DependencyUsageAnalyzer.UsageStatus.USED;
        transitive.add(t1);
        var t2 = new DependenciesTui.DepEntry("org.checkerframework", "checker-qual", "", "3.42.0", "compile", false);
        t2.pulledBy = "guava";
        t2.usageStatus = DependencyUsageAnalyzer.UsageStatus.UNUSED;
        transitive.add(t2);

        // Use a non-existent POM path since we won't actually write
        DependenciesTui tui = new DependenciesTui(declared, transitive, pomPath, "com.example:demo:1.0.0", true);

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::render, new Size(100, 24))) {

            Pilot pilot = testRunner.pilot();

            // Render initial state
            pilot.pause();

            // Navigate declared dependencies
            pilot.press(KeyCode.DOWN);
            pilot.pause();
            pilot.press(KeyCode.DOWN);
            pilot.pause();

            // Switch to transitive view
            pilot.press(KeyCode.TAB);
            pilot.pause();

            // Navigate transitive deps
            pilot.press(KeyCode.DOWN);
            pilot.pause();

            // Switch back
            pilot.press(KeyCode.TAB);
            pilot.pause();

            // Quit
            pilot.press('q');
        }
    }

    @Test
    void browseWithoutBytecodeAnalysis(@TempDir Path tempDir) throws Exception {
        String pomPath =
                Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();
        List<DependenciesTui.DepEntry> declared = new ArrayList<>();
        declared.add(new DependenciesTui.DepEntry("com.google.guava", "guava", "", "33.0.0-jre", "compile", true));
        declared.add(new DependenciesTui.DepEntry("org.slf4j", "slf4j-api", "", "2.0.9", "compile", true));

        List<DependenciesTui.DepEntry> transitive = new ArrayList<>();
        var t1 = new DependenciesTui.DepEntry("com.google.guava", "failureaccess", "", "1.0.2", "compile", false);
        t1.pulledBy = "guava";
        transitive.add(t1);

        // bytecodeAnalyzed = false
        DependenciesTui tui = new DependenciesTui(declared, transitive, pomPath, "com.example:demo:1.0.0");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::render, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();

            pilot.press(KeyCode.DOWN);
            pilot.pause();

            // Switch to transitive view
            pilot.press(KeyCode.TAB);
            pilot.pause();

            pilot.press('q');
        }
    }

    @Test
    void emptyDependencyList(@TempDir Path tempDir) throws Exception {
        String pomPath =
                Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();
        DependenciesTui tui =
                new DependenciesTui(new ArrayList<>(), new ArrayList<>(), pomPath, "com.example:demo:1.0.0");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::render, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();

            // Switch to transitive view (also empty)
            pilot.press(KeyCode.TAB);
            pilot.pause();

            pilot.press('q');
        }
    }

    @Test
    void undeterminedStatusRendering(@TempDir Path tempDir) throws Exception {
        String pomPath =
                Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();
        List<DependenciesTui.DepEntry> declared = new ArrayList<>();
        var d1 = new DependenciesTui.DepEntry("com.example", "unknown-lib", "", "1.0", "compile", true);
        d1.usageStatus = DependencyUsageAnalyzer.UsageStatus.UNDETERMINED;
        declared.add(d1);

        DependenciesTui tui = new DependenciesTui(declared, new ArrayList<>(), pomPath, "com.example:demo:1.0.0", true);

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::render, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();
            pilot.press('q');
        }
    }

    @Test
    void changeScopeModifiesPom(@TempDir Path tempDir) throws Exception {
        Path pomFile = tempDir.resolve("pom.xml");
        Files.writeString(pomFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>test</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava</artifactId>
                      <version>33.0.0-jre</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        List<DependenciesTui.DepEntry> declared = new ArrayList<>();
        declared.add(new DependenciesTui.DepEntry("com.google.guava", "guava", "", "33.0.0-jre", "compile", true));

        DependenciesTui tui =
                new DependenciesTui(declared, new ArrayList<>(), pomFile.toString(), "com.example:test:1.0");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::render, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();

            // Press 's' to cycle scope from compile -> provided
            pilot.press('s');
            pilot.pause();

            String pomContent = tui.currentPomContent();
            assertThat(pomContent).contains("<scope>provided</scope>");

            // Press 's' again to cycle scope from provided -> runtime
            pilot.press('s');
            pilot.pause();

            pomContent = tui.currentPomContent();
            assertThat(pomContent).contains("<scope>runtime</scope>");

            // Press 's' again: runtime -> test
            pilot.press('s');
            pilot.pause();

            pomContent = tui.currentPomContent();
            assertThat(pomContent).contains("<scope>test</scope>");

            // Press 's' again: test -> compile (removes <scope> element)
            pilot.press('s');
            pilot.pause();

            pomContent = tui.currentPomContent();
            assertThat(pomContent).doesNotContain("<scope>");

            pilot.press('q');
        }
    }

    @Test
    void removeDeclaredModifiesPom(@TempDir Path tempDir) throws Exception {
        Path pomFile = tempDir.resolve("pom.xml");
        Files.writeString(pomFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>test</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava</artifactId>
                      <version>33.0.0-jre</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        List<DependenciesTui.DepEntry> declared = new ArrayList<>();
        declared.add(new DependenciesTui.DepEntry("com.google.guava", "guava", "", "33.0.0-jre", "compile", true));

        DependenciesTui tui =
                new DependenciesTui(declared, new ArrayList<>(), pomFile.toString(), "com.example:test:1.0");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::render, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();

            // Press 'x' to remove the selected dependency
            pilot.press('x');
            pilot.pause();

            String pomContent = tui.currentPomContent();
            assertThat(pomContent).doesNotContain("guava");

            pilot.press('q');
        }
    }

    @Test
    void addTransitiveModifiesPom(@TempDir Path tempDir) throws Exception {
        Path pomFile = tempDir.resolve("pom.xml");
        Files.writeString(pomFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>test</artifactId>
                  <version>1.0</version>
                  <dependencies>
                  </dependencies>
                </project>
                """);

        List<DependenciesTui.DepEntry> transitive = new ArrayList<>();
        var t1 = new DependenciesTui.DepEntry("com.google.guava", "guava", "", "33.0.0-jre", "compile", false);
        t1.pulledBy = "some-parent";
        t1.usageStatus = DependencyUsageAnalyzer.UsageStatus.USED;
        transitive.add(t1);

        DependenciesTui tui =
                new DependenciesTui(new ArrayList<>(), transitive, pomFile.toString(), "com.example:test:1.0", true);

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::render, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();

            // Switch to transitive view
            pilot.press(KeyCode.TAB);
            pilot.pause();

            // Press 'a' to add to POM
            pilot.press('a');
            pilot.pause();

            String pomContent = tui.currentPomContent();
            assertThat(pomContent).contains("guava");

            pilot.press('q');
        }
    }
}
