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

import dev.tamboui.layout.Size;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.pilot.TuiTestRunner;
import jakarta.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for page navigation (PgUp/PgDn/Home/End) across TUI views.
 */
class PageNavigationTest {

    @TempDir
    Path tempDir;

    String pomPath;

    @BeforeEach
    void setUp() throws Exception {
        pomPath = Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();
    }

    @Test
    void pomPageNavigation() throws Exception {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <properties>
                    <java.version>17</java.version>
                    <encoding>UTF-8</encoding>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.slf4j</groupId>
                      <artifactId>slf4j-api</artifactId>
                      <version>2.0.9</version>
                    </dependency>
                  </dependencies>
                </project>
                """;
        PomTui tui = new PomTui(pom, pom, "pom.xml");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();
            pilot.press(KeyCode.PAGE_DOWN);
            pilot.pause();
            pilot.press(KeyCode.PAGE_UP);
            pilot.pause();
            pilot.press(KeyCode.HOME);
            pilot.pause();
            pilot.press(KeyCode.END);
            pilot.pause();
            pilot.press('q');
        }
    }

    @Test
    void treePageNavigation() throws Exception {
        var root = new DependencyTreeModel.TreeNode("com.example", "app", "1.0", "compile", false, 0);
        for (int i = 0; i < 5; i++) {
            root.children.add(new DependencyTreeModel.TreeNode("com.example", "lib" + i, "1.0", "compile", false, 1));
        }
        var treeModel = new DependencyTreeModel(root, List.of(), root.children.size() + 1);
        TreeTui tui = new TreeTui(treeModel, "compile", "com.example:app:1.0");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();
            pilot.press(KeyCode.PAGE_DOWN);
            pilot.pause();
            pilot.press(KeyCode.PAGE_UP);
            pilot.pause();
            pilot.press(KeyCode.HOME);
            pilot.pause();
            pilot.press(KeyCode.END);
            pilot.pause();
            pilot.press('q');
        }
    }

    @Test
    void conflictsPageNavigation() throws Exception {
        var groups = new ArrayList<ConflictsTui.ConflictGroup>();
        for (int i = 0; i < 5; i++) {
            var entry = new ConflictsTui.ConflictEntry(
                    "com.example", "lib" + i, "1.0", "1.1", "root > a > lib" + i, "compile");
            groups.add(new ConflictsTui.ConflictGroup("com.example:lib" + i, List.of(entry)));
        }
        ConflictsTui tui = new ConflictsTui(groups, pomPath, "com.example:app:1.0");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();
            pilot.press(KeyCode.PAGE_DOWN);
            pilot.pause();
            pilot.press(KeyCode.PAGE_UP);
            pilot.pause();
            pilot.press(KeyCode.HOME);
            pilot.pause();
            pilot.press(KeyCode.END);
            pilot.pause();
            pilot.press('q');
        }
    }

    @Test
    void dependenciesPageNavigation() throws Exception {
        var declared = new ArrayList<DependenciesTui.DepEntry>();
        for (int i = 0; i < 5; i++) {
            declared.add(new DependenciesTui.DepEntry("com.example", "lib" + i, "", "1.0", "compile", true));
        }
        DependenciesTui tui = new DependenciesTui(declared, List.of(), pomPath, "com.example:app:1.0");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();
            pilot.press(KeyCode.PAGE_DOWN);
            pilot.pause();
            pilot.press(KeyCode.PAGE_UP);
            pilot.pause();
            pilot.press(KeyCode.HOME);
            pilot.pause();
            pilot.press(KeyCode.END);
            pilot.pause();
            pilot.press('q');
        }
    }

    @Test
    void updatesPageNavigation() throws Exception {
        List<PilotProject.Dep> deps = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            deps.add(new PilotProject.Dep("com.example", "lib" + i, "1.0"));
        }
        Path basedir = tempDir.resolve("app");
        Files.createDirectories(basedir);
        Files.writeString(basedir.resolve("pom.xml"), "<project/>");
        PilotProject proj = new PilotProject(
                "com.example",
                "app",
                "1.0",
                "jar",
                basedir,
                basedir.resolve("pom.xml"),
                deps,
                List.of(),
                deps,
                List.of(),
                new java.util.Properties(),
                null,
                null);
        List<PilotProject> projects = List.of(proj);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(projects);
        ReactorModel model = ReactorModel.build(projects);
        UpdatesTui tui = new UpdatesTui(result, model, "com.example:app:1.0", (g, a) -> List.of());
        tui.loadedCount = 5;
        tui.updateStatusIfDone();

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();
            pilot.press(KeyCode.PAGE_DOWN);
            pilot.pause();
            pilot.press(KeyCode.PAGE_UP);
            pilot.pause();
            pilot.press(KeyCode.HOME);
            pilot.pause();
            pilot.press(KeyCode.END);
            pilot.pause();
            pilot.press('q');
        }
    }

    @Test
    void searchPageNavigation() throws Exception {
        List<String[]> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            results.add(new String[] {"com.example", "lib" + i, "1.0." + i, "jar", "10", ""});
        }
        SearchTui tui = new SearchTui(
                (term, rows, start) -> Json.createObjectBuilder()
                        .add("numFound", 0)
                        .add("docs", Json.createArrayBuilder())
                        .build(),
                "test",
                results,
                5);

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();
            pilot.press(KeyCode.TAB);
            pilot.pause();
            pilot.press(KeyCode.DOWN);
            pilot.pause();
            pilot.press(KeyCode.DOWN);
            pilot.pause();
            pilot.press(KeyCode.PAGE_DOWN);
            pilot.pause();
            pilot.press(KeyCode.PAGE_UP);
            pilot.pause();
            pilot.press(KeyCode.HOME);
            pilot.pause();
            pilot.press(KeyCode.END);
            pilot.pause();
            pilot.press(KeyCode.ESCAPE);
        }
    }

    @Test
    void auditPageNavigation() throws Exception {
        var entries = new ArrayList<AuditTui.AuditEntry>();
        for (int i = 0; i < 5; i++) {
            var entry = new AuditTui.AuditEntry("com.example", "lib" + i, "1.0", "compile");
            entry.license = "Apache-2.0";
            entry.licenseLoaded = true;
            entry.vulnsLoaded = true;
            entry.vulnerabilities = List.of();
            entries.add(entry);
        }
        var root = new DependencyTreeModel.TreeNode("com.example", "app", "1.0", "compile", false, 0);
        var treeModel = new DependencyTreeModel(root, List.of(), root.children.size() + 1);
        AuditTui tui = new AuditTui(entries, "com.example:app:1.0", treeModel, pomPath);

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();
            pilot.press(KeyCode.PAGE_DOWN);
            pilot.pause();
            pilot.press(KeyCode.PAGE_UP);
            pilot.pause();
            pilot.press(KeyCode.HOME);
            pilot.pause();
            pilot.press(KeyCode.END);
            pilot.pause();
            pilot.press('q');
        }
    }
}
