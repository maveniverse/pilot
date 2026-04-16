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
import eu.maveniverse.domtrip.maven.AlignOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Generates SVG screenshots of each TUI screen for documentation.
 * Run with: mvn test -Dtest=ScreenshotTest -Dpilot.screenshots=true
 */
@EnabledIfSystemProperty(named = "pilot.screenshots", matches = "true")
class ScreenshotTest {

    private static final Path OUTPUT_DIR = Path.of(System.getProperty("pilot.output.dir", "../docs/images"));
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

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.pause();
        }

        saveSvg("pom", renderToSvg(tui::renderStandalone, "pilot:pom"));
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
        TreeTui tui = new TreeTui(model, "compile", "com.example:demo-app:1.0.0");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.pause();
        }

        saveSvg("tree", renderToSvg(tui::renderStandalone, "pilot:tree"));
    }

    @Test
    void dependencyUpdates(@TempDir Path tempDir) throws Exception {
        String pomPath =
                Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();
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

        UpdatesTui tui = new UpdatesTui(deps, pomPath, "com.example:demo:1.0.0", (g, a) -> List.of());

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            pilot.press(KeyCode.DOWN);
            pilot.press(' ');
            pilot.press(KeyCode.DOWN);
            pilot.press(' ');
            pilot.pause();
        }

        saveSvg("updates", renderToSvg(tui::renderStandalone, "pilot:updates"));
    }

    @Test
    void dependencyAnalysis(@TempDir Path tempDir) throws Exception {
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

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            pilot.press(KeyCode.DOWN);
            pilot.pause();
        }

        saveSvg("dependencies", renderToSvg(tui::renderStandalone, "pilot:dependencies"));
    }

    @Test
    void conflictResolution(@TempDir Path tempDir) throws Exception {
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

        ConflictsTui tui = new ConflictsTui(conflicts, pomPath, "com.example:demo:1.0.0");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            pilot.press(KeyCode.ENTER);
            pilot.pause();
        }

        saveSvg("conflicts", renderToSvg(tui::renderStandalone, "pilot:conflicts"));
    }

    @Test
    void search() throws Exception {
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
                        "A Java serialization/deserialization library.",
                        null,
                        "Google, Inc.",
                        "Apache-2.0",
                        null,
                        "2024-05-20"));

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.pause();
        }

        saveSvg("search", renderToSvg(tui::renderStandalone, "pilot:search"));
    }

    @Test
    void licenseAudit(@TempDir Path tempDir) throws Exception {
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
        tui.initFromEntries();

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.pause();
        }

        saveSvg("audit", renderToSvg(tui::renderStandalone, "pilot:audit"));
    }

    @Test
    void vulnerabilityAudit(@TempDir Path tempDir) throws Exception {
        String pomPath =
                Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();

        List<AuditTui.AuditEntry> entries = new ArrayList<>();

        var e1 = new AuditTui.AuditEntry("org.apache.logging.log4j", "log4j-core", "2.14.1", "compile");
        e1.license = "Apache License, Version 2.0";
        e1.licenseLoaded = true;
        e1.vulnsLoaded = true;
        e1.vulnerabilities = List.of(new OsvClient.Vulnerability(
                "CVE-2021-44228",
                "Log4Shell RCE via JNDI lookup in log messages",
                "CRITICAL",
                "2021-12-10",
                List.of("GHSA-jfh8-c2jp-5v3q")));
        entries.add(e1);

        var e2 = new AuditTui.AuditEntry("com.fasterxml.jackson.core", "jackson-databind", "2.13.0", "compile");
        e2.license = "Apache License, Version 2.0";
        e2.licenseLoaded = true;
        e2.vulnsLoaded = true;
        e2.vulnerabilities = List.of(new OsvClient.Vulnerability(
                "CVE-2022-42003", "Deep nesting DoS in jackson-databind", "HIGH", "2022-10-02", List.of()));
        entries.add(e2);

        var e3 = new AuditTui.AuditEntry("commons-io", "commons-io", "2.11.0", "compile");
        e3.license = "Apache License, Version 2.0";
        e3.licenseLoaded = true;
        e3.vulnsLoaded = true;
        e3.vulnerabilities = List.of(new OsvClient.Vulnerability(
                "CVE-2024-47554", "Possible denial of service in Commons IO", "MEDIUM", "2024-10-03", List.of()));
        entries.add(e3);

        var e4 = new AuditTui.AuditEntry("org.yaml", "snakeyaml", "1.33", "compile");
        e4.license = "Apache License, Version 2.0";
        e4.licenseLoaded = true;
        e4.vulnsLoaded = true;
        e4.vulnerabilities = List.of(new OsvClient.Vulnerability(
                "CVE-2022-1471", "Arbitrary code execution via SnakeYaml Constructor", "LOW", "2022-12-01", List.of()));
        entries.add(e4);

        AuditTui tui = new AuditTui(entries, "com.example:demo-app:1.0.0", null, pomPath);
        tui.initFromEntries();

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            pilot.press(KeyCode.TAB);
            pilot.press(KeyCode.TAB);
            pilot.pause();
        }

        saveSvg("audit-vulns", renderToSvg(tui::renderStandalone, "pilot:audit"));
    }

    @Test
    void conventionAlignment(@TempDir Path tempDir) throws Exception {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>demo-app</artifactId>
                  <version>1.0.0</version>
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
        String pomPath = Files.writeString(tempDir.resolve("pom.xml"), pom).toString();

        AlignOptions detected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.INLINE)
                .versionSource(AlignOptions.VersionSource.LITERAL)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();

        AlignTui tui = new AlignTui(pomPath, "com.example:demo-app:1.0.0", detected, null);

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            // Cycle Version Source to PROPERTY to show a changed value
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.RIGHT);
            pilot.pause();
        }

        saveSvg("align", renderToSvg(tui::renderStandalone, "pilot:align"));
    }

    private void saveSvg(String name, String svg) throws Exception {
        Files.createDirectories(OUTPUT_DIR);
        Files.writeString(OUTPUT_DIR.resolve(name + ".svg"), svg);
    }
}
