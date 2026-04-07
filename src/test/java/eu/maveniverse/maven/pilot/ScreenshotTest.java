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

import dev.tamboui.export.ExportRequest;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Terminal;
import dev.tamboui.terminal.TestBackend;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.pilot.TuiTestRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Generates SVG screenshots of each TUI screen for documentation.
 * Run with: mvn test -Dtest=ScreenshotTest -Dpilot.screenshots=true
 */
@EnabledIfSystemProperty(named = "pilot.screenshots", matches = "true")
class ScreenshotTest {

    private static final Path OUTPUT_DIR = Path.of("docs/images");
    private static final int WIDTH = 120;
    private static final int HEIGHT = 30;

    private String renderToSvg(dev.tamboui.tui.Renderer renderer, String title) {
        var terminal = new Terminal<>(new TestBackend(WIDTH, HEIGHT));
        var frame = terminal.draw(renderer::render);
        return ExportRequest.export(frame.buffer())
                .svg()
                .options(o -> o.title(title).chrome(true))
                .toString();
    }

    @Test
    void pomViewer() throws Exception {
        String rawPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>demo-app</artifactId>
                  <version>1.0.0</version>
                  <packaging>jar</packaging>
                  <name>Demo Application</name>
                  <properties>
                    <guava.version>33.0.0-jre</guava.version>
                    <slf4j.version>2.0.9</slf4j.version>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava</artifactId>
                      <version>${guava.version}</version>
                    </dependency>
                    <dependency>
                      <groupId>org.slf4j</groupId>
                      <artifactId>slf4j-api</artifactId>
                      <version>${slf4j.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """;
        String effectivePom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>demo-app</artifactId>
                  <version>1.0.0</version>
                  <packaging>jar</packaging>
                  <name>Demo Application</name>
                  <properties>
                    <guava.version>33.0.0-jre</guava.version>
                    <slf4j.version>2.0.9</slf4j.version>
                  </properties>
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
                  </dependencies>
                </project>
                """;

        PomTui tui = new PomTui(rawPom, effectivePom, "pom.xml");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::render, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.pause();
        }

        saveSvg("pom", renderToSvg(tui::render, "pilot:pom"));
    }

    @Test
    void dependencyTree() throws Exception {
        var root = new DependencyTreeModel.TreeNode("com.example", "demo-app", "1.0.0", "compile", false, 0);
        root.expanded = true;

        var guava = new DependencyTreeModel.TreeNode("com.google.guava", "guava", "33.0.0-jre", "compile", false, 1);
        guava.expanded = true;
        root.children.add(guava);

        guava.children.add(
                new DependencyTreeModel.TreeNode("com.google.guava", "failureaccess", "1.0.2", "compile", false, 2));
        guava.children.add(new DependencyTreeModel.TreeNode(
                "org.checkerframework", "checker-qual", "3.42.0", "compile", false, 2));
        guava.children.add(new DependencyTreeModel.TreeNode(
                "com.google.errorprone", "error_prone_annotations", "2.27.0", "compile", false, 2));
        guava.children.add(
                new DependencyTreeModel.TreeNode("com.google.code.findbugs", "jsr305", "3.0.2", "compile", false, 2));

        root.children.add(new DependencyTreeModel.TreeNode("org.slf4j", "slf4j-api", "2.0.9", "compile", false, 1));
        root.children.add(new DependencyTreeModel.TreeNode("commons-io", "commons-io", "2.15.1", "compile", false, 1));

        var junit = new DependencyTreeModel.TreeNode("org.junit.jupiter", "junit-jupiter", "5.11.4", "test", false, 1);
        junit.expanded = true;
        root.children.add(junit);

        junit.children.add(
                new DependencyTreeModel.TreeNode("org.junit.jupiter", "junit-jupiter-api", "5.11.4", "test", false, 2));
        junit.children.add(new DependencyTreeModel.TreeNode(
                "org.junit.jupiter", "junit-jupiter-engine", "5.11.4", "test", false, 2));

        // Add a conflict for visual interest
        var conflictNode = new DependencyTreeModel.TreeNode("org.slf4j", "slf4j-api", "1.7.36", "compile", false, 2);
        conflictNode.requestedVersion = "2.0.9";
        guava.children.add(conflictNode);

        var model = new DependencyTreeModel(root, List.of(conflictNode), 12);
        TreeTui tui = new TreeTui(model, "com.example:demo-app:1.0.0");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::render, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.pause();
        }

        saveSvg("tree", renderToSvg(tui::render, "pilot:tree"));
    }

    @Test
    void dependencyUpdates() throws Exception {
        List<UpdatesTui.DependencyInfo> deps = new ArrayList<>();

        var d1 = new UpdatesTui.DependencyInfo("com.google.guava", "guava", "33.0.0-jre", "compile", "jar");
        d1.newestVersion = "33.4.0-jre";
        d1.updateType = VersionComparator.UpdateType.MINOR;
        deps.add(d1);

        var d2 = new UpdatesTui.DependencyInfo("org.slf4j", "slf4j-api", "2.0.9", "compile", "jar");
        d2.newestVersion = "2.0.16";
        d2.updateType = VersionComparator.UpdateType.PATCH;
        deps.add(d2);

        var d3 = new UpdatesTui.DependencyInfo("commons-io", "commons-io", "2.15.1", "compile", "jar");
        d3.newestVersion = "2.18.0";
        d3.updateType = VersionComparator.UpdateType.MINOR;
        deps.add(d3);

        var d4 = new UpdatesTui.DependencyInfo("org.junit.jupiter", "junit-jupiter", "5.10.1", "test", "jar");
        d4.newestVersion = "5.11.4";
        d4.updateType = VersionComparator.UpdateType.MINOR;
        deps.add(d4);

        var d5 = new UpdatesTui.DependencyInfo("org.apache.commons", "commons-lang3", "3.12.0", "compile", "jar");
        d5.newestVersion = "3.17.0";
        d5.updateType = VersionComparator.UpdateType.MINOR;
        deps.add(d5);

        UpdatesTui tui = new UpdatesTui(deps, "/tmp/test-pom.xml", "com.example:demo:1.0.0", (g, a) -> List.of());

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::render, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            pilot.press(KeyCode.DOWN);
            pilot.press(' ');
            pilot.press(KeyCode.DOWN);
            pilot.press(' ');
            pilot.pause();
        }

        saveSvg("updates", renderToSvg(tui::render, "pilot:updates"));
    }

    @Test
    void dependencyAnalysis() throws Exception {
        List<AnalyzeTui.DepEntry> declared = new ArrayList<>();
        declared.add(new AnalyzeTui.DepEntry("com.google.guava", "guava", "", "33.0.0-jre", "compile", true));
        declared.add(new AnalyzeTui.DepEntry("org.slf4j", "slf4j-api", "", "2.0.9", "compile", true));
        declared.add(new AnalyzeTui.DepEntry("commons-io", "commons-io", "", "2.15.1", "compile", true));
        declared.add(new AnalyzeTui.DepEntry("org.apache.commons", "commons-text", "", "1.11.0", "compile", false));

        List<AnalyzeTui.DepEntry> transitive = new ArrayList<>();
        var t1 = new AnalyzeTui.DepEntry("com.google.guava", "failureaccess", "", "1.0.2", "compile", false);
        t1.pulledBy = "guava";
        transitive.add(t1);
        var t2 = new AnalyzeTui.DepEntry("org.checkerframework", "checker-qual", "", "3.42.0", "compile", false);
        t2.pulledBy = "guava";
        transitive.add(t2);

        AnalyzeTui tui = new AnalyzeTui(declared, transitive, "/tmp/test-pom.xml", "com.example:demo:1.0.0");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::render, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            pilot.press(KeyCode.DOWN);
            pilot.pause();
        }

        saveSvg("analyze", renderToSvg(tui::render, "pilot:analyze"));
    }

    @Test
    void conflictResolution() throws Exception {
        List<ConflictsTui.ConflictGroup> conflicts = new ArrayList<>();

        var e1 = new ConflictsTui.ConflictEntry(
                "org.apache.commons", "commons-lang3", "3.12.0", "3.14.0", "commons-text:1.11.0", "compile");
        var e2 = new ConflictsTui.ConflictEntry(
                "org.apache.commons", "commons-lang3", "3.14.0", "3.14.0", "direct", "compile");
        conflicts.add(new ConflictsTui.ConflictGroup("org.apache.commons:commons-lang3", List.of(e1, e2)));

        var e3 =
                new ConflictsTui.ConflictEntry("org.slf4j", "slf4j-api", "2.0.7", "2.0.9", "logback:1.4.14", "compile");
        var e4 = new ConflictsTui.ConflictEntry("org.slf4j", "slf4j-api", "2.0.9", "2.0.9", "direct", "compile");
        conflicts.add(new ConflictsTui.ConflictGroup("org.slf4j:slf4j-api", List.of(e3, e4)));

        ConflictsTui tui = new ConflictsTui(conflicts, "/tmp/test-pom.xml", "com.example:demo:1.0.0");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::render, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            pilot.press(KeyCode.ENTER);
            pilot.pause();
        }

        saveSvg("conflicts", renderToSvg(tui::render, "pilot:conflicts"));
    }

    private void saveSvg(String name, String svg) throws Exception {
        Files.createDirectories(OUTPUT_DIR);
        Files.writeString(OUTPUT_DIR.resolve(name + ".svg"), svg);
    }
}
