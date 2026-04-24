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
import dev.tamboui.tui.Renderer;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.pilot.TuiTestRunner;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Generates demo recordings for each TUI screen: .cast files (asciinema),
 * SVG frames, and narration scripts for video production.
 *
 * <p>Run with: {@code mvn test -B -Dtest=RecordingDemoTest -Dpilot.recordings=true}
 *
 * <p>Output goes to {@code docs/recordings/}:
 * <ul>
 *   <li>{@code <demo>.cast} — Asciinema v2 cast file for terminal playback</li>
 *   <li>{@code <demo>/frame-NN.svg} — SVG frames for video assembly</li>
 *   <li>{@code <demo>.narration.json} — Scene narration text for TTS</li>
 * </ul>
 *
 * <p>After running, use {@code ./generate-videos.sh} to produce MP4 videos
 * with voice narration from the generated assets.
 */
@EnabledIfSystemProperty(named = "pilot.recordings", matches = "true")
class RecordingDemoTest {

    private static final Path OUTPUT_DIR = Path.of(System.getProperty("pilot.output.dir", "../docs/recordings"));
    private static final int WIDTH = 120;
    private static final int HEIGHT = 30;

    /**
     * Captures a sequence of TUI scenes with narration text, and writes them
     * as .cast files, SVG frames, and narration JSON.
     */
    static class DemoRecorder {

        private final Renderer renderer;
        private final String title;
        private final List<Scene> scenes = new ArrayList<>();

        DemoRecorder(Renderer renderer, String title) {
            this.renderer = renderer;
            this.title = title;
        }

        /**
         * Captures the current TUI state as a scene with narration.
         */
        void scene(String narration) {
            var terminal = new Terminal<>(new TestBackend(WIDTH, HEIGHT));
            var frame = terminal.draw(renderer::render);

            String svg = ExportRequest.export(frame.buffer())
                    .svg()
                    .options(o -> o.title(title).chrome(true))
                    .toString();
            String ansi = ExportRequest.export(frame.buffer())
                    .text()
                    .options(o -> o.styles(true))
                    .toString();

            scenes.add(new Scene(svg, ansi, narration));
        }

        /**
         * Writes an Asciinema v2 cast file. Timing uses estimated narration durations
         * (the generate-videos.sh script re-syncs to actual TTS audio durations).
         */
        void writeCast(Path path) throws IOException {
            Files.createDirectories(path.getParent());
            StringWriter writer = new StringWriter();

            long timestamp = System.currentTimeMillis() / 1000;
            writer.write(String.format(
                    "{\"version\": 2, \"width\": %d, \"height\": %d, \"timestamp\": %d, "
                            + "\"title\": \"%s\", \"env\": {\"TERM\": \"xterm-256color\"}}%n",
                    WIDTH, HEIGHT, timestamp, escapeJson(title)));

            double currentTime = 0.0;
            for (Scene scene : scenes) {
                String frameOutput = "\033[2J\033[H" + scene.ansi;
                writer.write(String.format(Locale.US, "[%.6f, \"o\", \"%s\"]%n", currentTime, escapeJson(frameOutput)));
                currentTime += estimateDuration(scene.narration);
            }

            // Final empty event to preserve total duration
            writer.write(String.format(Locale.US, "[%.6f, \"o\", \"\"]%n", currentTime));

            Files.writeString(path, writer.toString(), StandardCharsets.UTF_8);
        }

        /**
         * Writes individual SVG frames for video production.
         */
        void writeSvgFrames(Path dir) throws IOException {
            Files.createDirectories(dir);
            for (int i = 0; i < scenes.size(); i++) {
                Files.writeString(dir.resolve(String.format("frame-%02d.svg", i)), scenes.get(i).svg);
            }
        }

        /**
         * Writes narration metadata as JSON. The generate-videos.sh script reads this
         * to produce TTS audio and sync it with the video frames.
         */
        void writeNarration(Path path) throws IOException {
            Files.createDirectories(path.getParent());
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"title\": \"").append(escapeJson(title)).append("\",\n");
            json.append("  \"scenes\": [\n");
            for (int i = 0; i < scenes.size(); i++) {
                Scene scene = scenes.get(i);
                json.append("    {\"frame\": ")
                        .append(i)
                        .append(", \"text\": \"")
                        .append(escapeJson(scene.narration))
                        .append("\"}");
                if (i < scenes.size() - 1) json.append(",");
                json.append("\n");
            }
            json.append("  ]\n");
            json.append("}\n");
            Files.writeString(path, json.toString(), StandardCharsets.UTF_8);
        }

        private static double estimateDuration(String text) {
            // ~175 words per minute for TTS, plus padding
            int wordCount = text.split("\\s+").length;
            return Math.max(2.0, wordCount * 60.0 / 175.0 + 0.8);
        }

        private static String escapeJson(String s) {
            StringBuilder sb = new StringBuilder(s.length() * 2);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            return sb.toString();
        }

        record Scene(String svg, String ansi, String narration) {}
    }

    // ── POM Viewer ──────────────────────────────────────────────────────

    @Test
    void pomViewerDemo() throws Exception {
        String rawPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent-pom</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>demo-app</artifactId>
                  <name>Demo Application</name>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.slf4j</groupId>
                      <artifactId>slf4j-api</artifactId>
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
                    <maven.compiler.source>17</maven.compiler.source>
                    <maven.compiler.target>17</maven.compiler.target>
                    <guava.version>33.0.0-jre</guava.version>
                    <slf4j.version>2.0.9</slf4j.version>
                  </properties>
                  <dependencyManagement>
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
                  </dependencyManagement>
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
        String parentPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent-pom</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <properties>
                    <maven.compiler.source>17</maven.compiler.source>
                    <maven.compiler.target>17</maven.compiler.target>
                    <guava.version>33.0.0-jre</guava.version>
                    <slf4j.version>2.0.9</slf4j.version>
                  </properties>
                  <dependencyManagement>
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
                  </dependencyManagement>
                </project>
                """;

        PomTui tui = new PomTui(
                rawPom,
                XmlTreeModel.parse(effectivePom),
                new IdentityHashMap<>(),
                "pom.xml",
                Map.of("parent-pom/pom.xml", parentPom.split("\n")));
        DemoRecorder recorder = new DemoRecorder(tui::renderStandalone, "pilot:pom");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();

            pilot.pause();
            recorder.scene(
                    "Pilot's POM viewer renders your project's pom.xml as an interactive tree. This project inherits from a parent POM.");

            pilot.press('e');
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.pause();
            recorder.scene(
                    "Press 'e' to expand all nodes. The dependencies have no version tags — they inherit versions from the parent's dependency management.");

            pilot.press(KeyCode.TAB);
            pilot.pause();
            recorder.scene(
                    "Press Tab to switch to the effective POM, where all properties and parent inheritance are fully resolved.");

            pilot.press('e');
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.pause();
            recorder.scene(
                    "The detail pane at the bottom shows which POM defines each element. Here, the properties come from the parent POM.");

            pilot.press('/');
            pilot.press("guava");
            pilot.press(KeyCode.ENTER);
            pilot.pause();
            recorder.scene("Press slash to search. Matching nodes are highlighted so you can find what you need fast.");

            pilot.press('q');
        }

        writeRecording(recorder, "pom-viewer");
    }

    // ── Dependency Tree ─────────────────────────────────────────────────

    @Test
    void dependencyTreeDemo() throws Exception {
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

        var conflictNode = new DependencyTreeModel.TreeNode("org.slf4j", "slf4j-api", "1.7.36", "compile", false, 2);
        conflictNode.requestedVersion = "2.0.9";
        guava.children.add(conflictNode);

        var model = new DependencyTreeModel(root, List.of(conflictNode), 12);
        TreeTui tui = new TreeTui(model, "compile", "com.example:demo-app:1.0.0");
        DemoRecorder recorder = new DemoRecorder(tui::renderStandalone, "pilot:tree");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();

            pilot.pause();
            recorder.scene(
                    "The dependency tree shows your project's full dependency graph with transitive dependencies indented below their parents.");

            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.pause();
            recorder.scene(
                    "Navigate through the tree with arrow keys. Non-compile scopes like test are labeled in brackets next to the dependency.");

            pilot.press(KeyCode.LEFT);
            pilot.pause();
            recorder.scene("Press left to collapse a subtree, hiding all its transitive dependencies.");

            pilot.press(KeyCode.RIGHT);
            pilot.pause();
            recorder.scene("Press right to expand it again. The tree remembers which nodes were expanded.");

            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.pause();
            recorder.scene(
                    "Version conflicts are highlighted in the tree. Here, slf4j-api is requested at version 1.7.36 but resolved to 2.0.9.");

            pilot.press('q');
        }

        writeRecording(recorder, "dependency-tree");
    }

    // ── Dependency Updates ──────────────────────────────────────────────

    @Test
    void updatesDemo(@TempDir Path tempDir) throws Exception {
        Path basedir = tempDir.resolve("demo");
        Files.createDirectories(basedir);
        Files.writeString(basedir.resolve("pom.xml"), "<project/>");

        List<PilotProject.Dep> deps = List.of(
                new PilotProject.Dep("com.google.guava", "guava", "33.0.0-jre", "compile", "jar"),
                new PilotProject.Dep("org.slf4j", "slf4j-api", "2.0.9", "compile", "jar"),
                new PilotProject.Dep("commons-io", "commons-io", "2.15.1", "compile", "jar"),
                new PilotProject.Dep("org.junit.jupiter", "junit-jupiter", "5.10.1", "test", "jar"),
                new PilotProject.Dep("org.apache.commons", "commons-lang3", "3.12.0", "compile", "jar"));
        PilotProject proj = new PilotProject(
                "com.example",
                "demo",
                "1.0.0",
                "jar",
                basedir,
                basedir.resolve("pom.xml"),
                deps,
                List.of(),
                deps,
                List.of(),
                new Properties(),
                null,
                null);
        List<PilotProject> projects = List.of(proj);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(projects);
        ReactorModel model = ReactorModel.build(projects);

        for (var ad : result.allDependencies) {
            switch (ad.artifactId) {
                case "guava" -> {
                    ad.newestVersion = "33.4.0-jre";
                    ad.updateType = VersionComparator.UpdateType.MINOR;
                }
                case "slf4j-api" -> {
                    ad.newestVersion = "2.0.16";
                    ad.updateType = VersionComparator.UpdateType.PATCH;
                }
                case "commons-io" -> {
                    ad.newestVersion = "2.18.0";
                    ad.updateType = VersionComparator.UpdateType.MINOR;
                }
                case "junit-jupiter" -> {
                    ad.newestVersion = "5.11.4";
                    ad.updateType = VersionComparator.UpdateType.MINOR;
                }
                case "commons-lang3" -> {
                    ad.newestVersion = "3.17.0";
                    ad.updateType = VersionComparator.UpdateType.MINOR;
                }
                default -> {}
            }
        }

        UpdatesTui tui = new UpdatesTui(result, model, "com.example:demo:1.0.0", (g, a) -> List.of());
        tui.buildDisplayRows();
        DemoRecorder recorder = new DemoRecorder(tui::renderStandalone, "pilot:updates");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();

            pilot.pause();
            recorder.scene(
                    "The updates view shows all dependencies with newer versions available. Updates are color-coded: blue for patch, yellow for minor, red for major.");

            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.pause();
            recorder.scene(
                    "Navigate to a dependency and see its current version alongside the newest available version.");

            pilot.press(' ');
            pilot.pause();
            recorder.scene("Press space to select individual dependencies for batch update.");

            pilot.press(KeyCode.DOWN);
            pilot.press(' ');
            pilot.pause();
            recorder.scene("Select multiple dependencies. Selected ones are marked with a checkmark.");

            pilot.press('2');
            pilot.pause();
            recorder.scene(
                    "Press number keys to filter by update type. Here we filter to show only patch updates, the safest to apply.");

            pilot.press('1');
            pilot.pause();
            recorder.scene("Press 1 to return to the full list showing all available updates.");

            pilot.press('q');
        }

        writeRecording(recorder, "updates");
    }

    // ── Dependency Analysis ─────────────────────────────────────────────

    @Test
    void dependenciesDemo(@TempDir Path tempDir) throws Exception {
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
        var d4 = new DependenciesTui.DepEntry("org.apache.commons", "commons-text", "", "1.11.0", "compile", false);
        d4.usageStatus = DependencyUsageAnalyzer.UsageStatus.UNUSED;
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

        DependenciesTui tui = new DependenciesTui(declared, transitive, pomPath, "com.example:demo:1.0.0", true);
        DemoRecorder recorder = new DemoRecorder(tui::renderStandalone, "pilot:dependencies");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();

            pilot.pause();
            recorder.scene(
                    "The dependency analysis view uses bytecode analysis to detect which declared dependencies are actually used in your code.");

            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.pause();
            recorder.scene(
                    "Dependencies marked as unused are highlighted, helping you identify candidates for removal to keep your POM clean.");

            pilot.press('2');
            pilot.pause();
            recorder.scene(
                    "Press 2 to switch to the transitive dependencies view. These are dependencies pulled in by your declared dependencies.");

            pilot.press(KeyCode.DOWN);
            pilot.pause();
            recorder.scene(
                    "Transitive dependencies also show their usage status. If your code uses a transitive dependency directly, consider declaring it explicitly.");

            pilot.press('1');
            pilot.pause();
            recorder.scene("Press 1 to return to the declared dependencies view.");

            pilot.press('q');
        }

        writeRecording(recorder, "dependencies");
    }

    // ── Conflict Resolution ─────────────────────────────────────────────

    @Test
    void conflictsDemo(@TempDir Path tempDir) throws Exception {
        String pomPath =
                Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();
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

        var e5 = new ConflictsTui.ConflictEntry(
                "com.fasterxml.jackson.core", "jackson-databind", "2.15.3", "2.17.0", "spring-boot:3.2.0", "compile");
        var e6 = new ConflictsTui.ConflictEntry(
                "com.fasterxml.jackson.core",
                "jackson-databind",
                "2.17.0",
                "2.17.0",
                "jackson-modules:2.17.0",
                "compile");
        conflicts.add(new ConflictsTui.ConflictGroup("com.fasterxml.jackson.core:jackson-databind", List.of(e5, e6)));

        ConflictsTui tui = new ConflictsTui(conflicts, pomPath, "com.example:demo:1.0.0");
        DemoRecorder recorder = new DemoRecorder(tui::renderStandalone, "pilot:conflicts");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();

            pilot.pause();
            recorder.scene(
                    "The conflicts view lists all dependency version conflicts in your project. Each row shows a dependency that is requested at different versions.");

            pilot.press(KeyCode.ENTER);
            pilot.pause();
            recorder.scene(
                    "Press Enter to expand the details panel. It shows which paths requested which version, and which version Maven resolved to.");

            pilot.press(KeyCode.DOWN);
            pilot.pause();
            recorder.scene(
                    "Navigate between conflict groups. Conflicts where the resolved version differs from the requested version are highlighted as warnings.");

            pilot.press(KeyCode.ENTER);
            pilot.pause();
            recorder.scene(
                    "Toggle the details panel on and off as you browse. This helps you understand exactly how Maven resolves version conflicts in your project.");

            pilot.press('q');
        }

        writeRecording(recorder, "conflicts");
    }

    // ── Maven Central Search ────────────────────────────────────────────

    @Test
    void searchDemo() throws Exception {
        SearchTui.SearchClient mockClient = (q, rows, start) -> {
            throw new UnsupportedOperationException("demo");
        };

        List<String[]> results = new ArrayList<>();
        results.add(new String[] {"com.google.guava", "guava", "33.4.0-jre", "jar", "127", "2024-10-15"});
        results.add(new String[] {"com.google.code.gson", "gson", "2.11.0", "jar", "89", "2024-05-20"});
        results.add(new String[] {"com.google.inject", "guice", "7.0.0", "jar", "42", "2024-01-15"});
        results.add(new String[] {"com.google.protobuf", "protobuf-java", "4.28.3", "jar", "156", "2024-11-01"});
        results.add(new String[] {"com.google.auto.value", "auto-value", "1.11.0", "jar", "34", "2024-06-10"});

        SearchTui tui = new SearchTui(mockClient, "google", results, 5);
        tui.cachePomInfo(
                "com.google.guava",
                "guava",
                "33.4.0-jre",
                new SearchTui.PomInfo(
                        "Guava: Google Core Libraries for Java",
                        "Guava is a suite of core and expanded libraries for Java.",
                        "https://github.com/google/guava",
                        "Google LLC",
                        "Apache-2.0",
                        null,
                        "2024-10-15"));
        tui.cachePomInfo(
                "com.google.code.gson",
                "gson",
                "2.11.0",
                new SearchTui.PomInfo(
                        "Gson",
                        "A Java serialization/deserialization library to convert Java Objects into JSON and back.",
                        null,
                        "Google, Inc.",
                        "Apache-2.0",
                        null,
                        "2024-05-20"));
        tui.cachePomInfo(
                "com.google.inject",
                "guice",
                "7.0.0",
                new SearchTui.PomInfo(
                        "Google Guice - Core Library",
                        "Guice is a lightweight dependency injection framework for Java.",
                        null,
                        "Google, Inc.",
                        "Apache-2.0",
                        null,
                        "2024-01-15"));
        tui.cachePomInfo(
                "com.google.protobuf",
                "protobuf-java",
                "4.28.3",
                new SearchTui.PomInfo(
                        "Protocol Buffers [Core]",
                        "Core Protocol Buffers library.",
                        null,
                        "Google LLC",
                        "BSD-3-Clause",
                        null,
                        "2024-11-01"));
        tui.cachePomInfo(
                "com.google.auto.value",
                "auto-value",
                "1.11.0",
                new SearchTui.PomInfo(
                        "AutoValue Processor",
                        "Immutable value-type code generation for Java.",
                        null,
                        "Google LLC",
                        "Apache-2.0",
                        null,
                        "2024-06-10"));
        DemoRecorder recorder = new DemoRecorder(tui::renderStandalone, "pilot:search");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();

            pilot.pause();
            recorder.scene(
                    "The search tool queries Maven Central right from the terminal. Type your search term and results appear instantly.");

            pilot.press(KeyCode.DOWN);
            pilot.pause();
            recorder.scene(
                    "Press Down or Enter to move into the results table. Each row shows the groupId, artifactId, and latest version.");

            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.pause();
            recorder.scene(
                    "Navigate with arrow keys. The detail bar shows artifact metadata like name, license, and organization.");

            pilot.press(KeyCode.DOWN);
            pilot.pause();
            recorder.scene(
                    "The detail bar updates as you navigate. Press Enter to select an artifact for use in your project.");

            pilot.press('q');
        }

        writeRecording(recorder, "search");
    }

    // ── License & Security Audit ────────────────────────────────────────

    @Test
    void auditDemo(@TempDir Path tempDir) throws Exception {
        String pomPath =
                Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();

        List<AuditTui.AuditEntry> entries = new ArrayList<>();

        var e1 = new AuditTui.AuditEntry("com.google.guava", "guava", "33.0.0-jre", "compile");
        e1.license = "Apache License, Version 2.0";
        e1.licenseUrl = "http://www.apache.org/licenses/LICENSE-2.0";
        e1.licenseLoaded = true;
        e1.vulnsLoaded = true;
        e1.vulnerabilities = List.of();
        entries.add(e1);

        var e2 = new AuditTui.AuditEntry("org.slf4j", "slf4j-api", "2.0.9", "compile");
        e2.license = "MIT License";
        e2.licenseUrl = "https://opensource.org/licenses/MIT";
        e2.licenseLoaded = true;
        e2.vulnsLoaded = true;
        e2.vulnerabilities = List.of();
        entries.add(e2);

        var e3 = new AuditTui.AuditEntry("commons-io", "commons-io", "2.15.1", "compile");
        e3.license = "Apache License, Version 2.0";
        e3.licenseUrl = "http://www.apache.org/licenses/LICENSE-2.0";
        e3.licenseLoaded = true;
        e3.vulnsLoaded = true;
        e3.vulnerabilities = List.of();
        entries.add(e3);

        var e4 = new AuditTui.AuditEntry("ch.qos.logback", "logback-classic", "1.4.14", "compile");
        e4.license = "Eclipse Public License - v 1.0";
        e4.licenseUrl = "https://www.eclipse.org/legal/epl-v10.html";
        e4.licenseLoaded = true;
        e4.vulnsLoaded = true;
        e4.vulnerabilities = List.of();
        entries.add(e4);

        var e5 = new AuditTui.AuditEntry("org.eclipse.jgit", "org.eclipse.jgit", "6.8.0", "compile");
        e5.license = "Eclipse Distribution License v1.0";
        e5.licenseUrl = "https://www.eclipse.org/org/documents/edl-v10.html";
        e5.licenseLoaded = true;
        e5.vulnsLoaded = true;
        e5.vulnerabilities = List.of();
        entries.add(e5);

        var e6 = new AuditTui.AuditEntry("org.apache.commons", "commons-text", "1.10.0", "compile");
        e6.license = "Apache License, Version 2.0";
        e6.licenseUrl = "http://www.apache.org/licenses/LICENSE-2.0";
        e6.licenseLoaded = true;
        e6.vulnsLoaded = true;
        e6.vulnerabilities = List.of();
        entries.add(e6);

        var e7 = new AuditTui.AuditEntry("org.junit.jupiter", "junit-jupiter", "5.11.4", "test");
        e7.license = "Eclipse Public License v2.0";
        e7.licenseUrl = "https://www.eclipse.org/legal/epl-v20.html";
        e7.licenseLoaded = true;
        e7.vulnsLoaded = true;
        e7.vulnerabilities = List.of();
        entries.add(e7);

        AuditTui tui = new AuditTui(entries, "com.example:demo-app:1.0.0", null, pomPath);
        DemoRecorder recorder = new DemoRecorder(tui::renderStandalone, "pilot:audit");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();

            pilot.pause();
            recorder.scene(
                    "The audit tool scans all your dependencies for license compliance and known security vulnerabilities.");

            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.pause();
            recorder.scene(
                    "Each dependency's license is fetched from Maven Central. Permissive licenses like Apache and MIT appear in default text. Weak copyleft licenses are highlighted in yellow.");

            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.pause();
            recorder.scene(
                    "The detail pane shows the full artifact coordinates, scope, and a direct link to the license text.");

            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.pause();
            recorder.scene(
                    "Three views are available via Tab: per-dependency licenses, grouped by license type, and a vulnerability scanner powered by the OSV database.");

            pilot.press('q');
        }

        writeRecording(recorder, "audit");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void writeRecording(DemoRecorder recorder, String name) throws IOException {
        recorder.writeCast(OUTPUT_DIR.resolve(name + ".cast"));
        recorder.writeSvgFrames(OUTPUT_DIR.resolve(name));
        recorder.writeNarration(OUTPUT_DIR.resolve(name + ".narration.json"));
    }
}
