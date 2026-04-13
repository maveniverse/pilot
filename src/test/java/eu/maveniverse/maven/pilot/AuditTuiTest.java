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
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.pilot.TuiTestRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.DefaultDependencyNode;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditTuiTest {

    private DefaultDependencyNode depNode(String g, String a, String v, String scope) {
        var artifact = new DefaultArtifact(g, a, "", "jar", v);
        var dependency = new Dependency(artifact, scope);
        return new DefaultDependencyNode(dependency);
    }

    private DefaultDependencyNode rootNode(String g, String a, String v) {
        var artifact = new DefaultArtifact(g, a, "", "jar", v);
        return new DefaultDependencyNode(artifact);
    }

    @Test
    void collectEntriesConvenienceOverload() {
        var root = rootNode("com.example", "app", "1.0.0");
        root.setChildren(List.of(
                depNode("org.slf4j", "slf4j-api", "2.0.9", "compile"),
                depNode("com.google.guava", "guava", "33.0.0-jre", "compile")));

        List<AuditTui.AuditEntry> entries = AuditTui.collectEntries(root);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).ga()).isEqualTo("org.slf4j:slf4j-api");
        assertThat(entries.get(1).ga()).isEqualTo("com.google.guava:guava");
        // Convenience overload should not populate modules
        assertThat(entries.get(0).modules).isEmpty();
    }

    @Test
    void collectEntriesDeduplicatesByGA() {
        var root = rootNode("com.example", "app", "1.0.0");
        var child1 = depNode("org.slf4j", "slf4j-api", "2.0.9", "compile");
        var child2 = depNode("com.google.guava", "guava", "33.0.0-jre", "compile");
        // duplicate of child1 under child2
        var grandchild = depNode("org.slf4j", "slf4j-api", "2.0.9", "compile");
        child2.setChildren(List.of(grandchild));
        root.setChildren(List.of(child1, child2));

        Map<String, AuditTui.AuditEntry> entryMap = new LinkedHashMap<>();
        AuditTui.collectEntries(root, entryMap, null, true);

        assertThat(entryMap).hasSize(2).containsKeys("org.slf4j:slf4j-api", "com.google.guava:guava");
    }

    @Test
    void collectEntriesSkipsRoot() {
        var root = rootNode("com.example", "app", "1.0.0");
        root.setChildren(List.of());

        Map<String, AuditTui.AuditEntry> entryMap = new LinkedHashMap<>();
        AuditTui.collectEntries(root, entryMap, null, true);

        assertThat(entryMap).isEmpty();
    }

    @Test
    void collectEntriesTracksModuleNames() {
        // Module A depends on slf4j and guava
        var moduleA = rootNode("com.example", "module-a", "1.0.0");
        moduleA.setChildren(List.of(
                depNode("org.slf4j", "slf4j-api", "2.0.9", "compile"),
                depNode("com.google.guava", "guava", "33.0.0-jre", "compile")));

        // Module B depends on slf4j only
        var moduleB = rootNode("com.example", "module-b", "1.0.0");
        moduleB.setChildren(List.of(depNode("org.slf4j", "slf4j-api", "2.0.9", "compile")));

        Map<String, AuditTui.AuditEntry> entryMap = new LinkedHashMap<>();
        AuditTui.collectEntries(moduleA, entryMap, "module-a", true);
        AuditTui.collectEntries(moduleB, entryMap, "module-b", true);

        assertThat(entryMap).hasSize(2);

        // slf4j used by both modules
        var slf4j = entryMap.get("org.slf4j:slf4j-api");
        assertThat(slf4j.modules).containsExactly("module-a", "module-b");

        // guava used by module-a only
        var guava = entryMap.get("com.google.guava:guava");
        assertThat(guava.modules).containsExactly("module-a");
    }

    @Test
    void collectEntriesNoModuleTrackingWhenNull() {
        var root = rootNode("com.example", "app", "1.0.0");
        root.setChildren(List.of(depNode("org.slf4j", "slf4j-api", "2.0.9", "compile")));

        Map<String, AuditTui.AuditEntry> entryMap = new LinkedHashMap<>();
        AuditTui.collectEntries(root, entryMap, null, true);

        var entry = entryMap.get("org.slf4j:slf4j-api");
        assertThat(entry.modules).isEmpty();
    }

    @Test
    void collectEntriesDoesNotDuplicateModuleName() {
        var root = rootNode("com.example", "app", "1.0.0");
        var child = depNode("org.slf4j", "slf4j-api", "2.0.9", "compile");
        // transitive dep also slf4j — same module should not be added twice
        var grandchild = depNode("org.slf4j", "slf4j-api", "2.0.9", "runtime");
        child.setChildren(List.of(grandchild));
        root.setChildren(List.of(child));

        Map<String, AuditTui.AuditEntry> entryMap = new LinkedHashMap<>();
        AuditTui.collectEntries(root, entryMap, "my-module", true);

        var entry = entryMap.get("org.slf4j:slf4j-api");
        assertThat(entry.modules).containsExactly("my-module");
    }

    @Test
    void collectEntriesPreservesInsertionOrder() {
        var root = rootNode("com.example", "app", "1.0.0");
        root.setChildren(List.of(
                depNode("org.a", "first", "1.0", "compile"),
                depNode("org.b", "second", "1.0", "compile"),
                depNode("org.c", "third", "1.0", "compile")));

        Map<String, AuditTui.AuditEntry> entryMap = new LinkedHashMap<>();
        AuditTui.collectEntries(root, entryMap, null, true);

        assertThat(entryMap.keySet()).containsExactly("org.a:first", "org.b:second", "org.c:third");
    }

    @Test
    void collectEntriesCapturesVersionAndScope() {
        var root = rootNode("com.example", "app", "1.0.0");
        root.setChildren(List.of(depNode("org.slf4j", "slf4j-api", "2.0.9", "test")));

        Map<String, AuditTui.AuditEntry> entryMap = new LinkedHashMap<>();
        AuditTui.collectEntries(root, entryMap, null, true);

        var entry = entryMap.get("org.slf4j:slf4j-api");
        assertThat(entry.version).isEqualTo("2.0.9");
        assertThat(entry.scope).isEqualTo("test");
        assertThat(entry.ga()).isEqualTo("org.slf4j:slf4j-api");
        assertThat(entry.gav()).isEqualTo("org.slf4j:slf4j-api:2.0.9");
    }

    @Test
    void collectEntriesSkipsNonRootNodeWithNullDependency() {
        // A non-root node that has no dependency should be skipped
        var root = rootNode("com.example", "app", "1.0.0");
        var noDep = new DefaultDependencyNode((org.eclipse.aether.graph.Dependency) null);
        noDep.setChildren(List.of(depNode("org.slf4j", "slf4j-api", "2.0.9", "compile")));
        root.setChildren(List.of(noDep));

        Map<String, AuditTui.AuditEntry> entryMap = new LinkedHashMap<>();
        AuditTui.collectEntries(root, entryMap, "mod", true);

        // slf4j should still be found (child of the null-dep node)
        assertThat(entryMap).hasSize(1).containsKey("org.slf4j:slf4j-api");
    }

    @Test
    void collectEntriesHandlesEmptyTree() {
        var root = rootNode("com.example", "app", "1.0.0");
        root.setChildren(List.of());

        Map<String, AuditTui.AuditEntry> entryMap = new LinkedHashMap<>();
        AuditTui.collectEntries(root, entryMap, "mod", true);

        assertThat(entryMap).isEmpty();
    }

    @Test
    void collectEntriesFirstVersionWins() {
        // When the same GA appears with different versions, the first version wins
        var root = rootNode("com.example", "app", "1.0.0");
        var child1 = depNode("org.slf4j", "slf4j-api", "2.0.9", "compile");
        var child2 = depNode("com.google.guava", "guava", "33.0.0-jre", "compile");
        var grandchild = depNode("org.slf4j", "slf4j-api", "1.7.36", "runtime");
        child2.setChildren(List.of(grandchild));
        root.setChildren(List.of(child1, child2));

        Map<String, AuditTui.AuditEntry> entryMap = new LinkedHashMap<>();
        AuditTui.collectEntries(root, entryMap, null, true);

        var entry = entryMap.get("org.slf4j:slf4j-api");
        assertThat(entry.version).isEqualTo("2.0.9");
        assertThat(entry.scope).isEqualTo("compile");
    }

    @Test
    void collectEntriesScopeFromDependencyNode() {
        var root = rootNode("com.example", "app", "1.0.0");
        root.setChildren(List.of(
                depNode("org.slf4j", "slf4j-api", "2.0.9", "runtime"),
                depNode("org.junit", "junit", "5.10.0", "test")));

        Map<String, AuditTui.AuditEntry> entryMap = new LinkedHashMap<>();
        AuditTui.collectEntries(root, entryMap, null, true);

        assertThat(entryMap.get("org.slf4j:slf4j-api").scope).isEqualTo("runtime");
        assertThat(entryMap.get("org.junit:junit").scope).isEqualTo("test");
    }

    @Test
    void auditEntryHasVulnerabilities() {
        var entry = new AuditTui.AuditEntry("org.slf4j", "slf4j-api", "2.0.9", "compile");
        assertThat(entry.hasVulnerabilities()).isFalse();

        entry.vulnerabilities = List.of();
        assertThat(entry.hasVulnerabilities()).isFalse();

        entry.vulnerabilities =
                List.of(new OsvClient.Vulnerability("CVE-2024-0001", "test", "HIGH", "2024-01-01", List.of()));
        assertThat(entry.hasVulnerabilities()).isTrue();
    }

    @Test
    void collectEntriesWalksTransitiveDeps() {
        var root = rootNode("com.example", "app", "1.0.0");
        var child = depNode("com.google.guava", "guava", "33.0.0-jre", "compile");
        var grandchild = depNode("com.google.guava", "failureaccess", "1.0.2", "compile");
        child.setChildren(List.of(grandchild));
        root.setChildren(List.of(child));

        Map<String, AuditTui.AuditEntry> entryMap = new LinkedHashMap<>();
        AuditTui.collectEntries(root, entryMap, "my-module", true);

        assertThat(entryMap).hasSize(2);
        assertThat(entryMap.get("com.google.guava:guava").modules).containsExactly("my-module");
        assertThat(entryMap.get("com.google.guava:failureaccess").modules).containsExactly("my-module");
    }

    @Test
    void collectReactorEntriesAggregatesModules() {
        DependencyNode moduleA = rootNode("com.example", "module-a", "1.0.0");
        moduleA.setChildren(List.of(
                depNode("org.slf4j", "slf4j-api", "2.0.9", "compile"),
                depNode("com.google.guava", "guava", "33.0.0-jre", "compile")));

        DependencyNode moduleB = rootNode("com.example", "module-b", "1.0.0");
        moduleB.setChildren(List.of(depNode("org.slf4j", "slf4j-api", "2.0.9", "compile")));

        LinkedHashMap<String, DependencyNode> moduleRoots = new LinkedHashMap<>();
        moduleRoots.put("module-a", moduleA);
        moduleRoots.put("module-b", moduleB);

        List<AuditTui.AuditEntry> entries = AuditTui.collectReactorEntries(moduleRoots);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).ga()).isEqualTo("org.slf4j:slf4j-api");
        assertThat(entries.get(0).modules).containsExactly("module-a", "module-b");
        assertThat(entries.get(1).ga()).isEqualTo("com.google.guava:guava");
        assertThat(entries.get(1).modules).containsExactly("module-a");
    }

    @Test
    void collectReactorEntriesEmptyMap() {
        LinkedHashMap<String, DependencyNode> moduleRoots = new LinkedHashMap<>();
        List<AuditTui.AuditEntry> entries = AuditTui.collectReactorEntries(moduleRoots);
        assertThat(entries).isEmpty();
    }

    @Test
    void getScopeStyleReturnsDefaultForNull() {
        Style style = AuditTui.getScopeStyle(null);
        assertThat(style).isEqualTo(Style.create());
    }

    @Test
    void getScopeStyleReturnsDefaultForCompile() {
        assertThat(AuditTui.getScopeStyle("compile")).isEqualTo(Style.create());
    }

    @Test
    void getScopeStyleReturnsDimForTest() {
        assertThat(AuditTui.getScopeStyle("test")).isEqualTo(Style.create().fg(Color.DARK_GRAY));
    }

    @Test
    void getScopeStyleReturnsDimForProvided() {
        assertThat(AuditTui.getScopeStyle("provided")).isEqualTo(Style.create().fg(Color.DARK_GRAY));
    }

    @Test
    void getScopeStyleReturnsYellowForRuntime() {
        assertThat(AuditTui.getScopeStyle("runtime")).isEqualTo(Style.create().fg(Color.YELLOW));
    }

    @Test
    void getScopeStyleNormalizesCase() {
        assertThat(AuditTui.getScopeStyle("TEST")).isEqualTo(Style.create().fg(Color.DARK_GRAY));
        assertThat(AuditTui.getScopeStyle("Runtime")).isEqualTo(Style.create().fg(Color.YELLOW));
        assertThat(AuditTui.getScopeStyle("Provided")).isEqualTo(Style.create().fg(Color.DARK_GRAY));
    }

    @Test
    void getScopeStyleTrimsWhitespace() {
        assertThat(AuditTui.getScopeStyle("  test  ")).isEqualTo(Style.create().fg(Color.DARK_GRAY));
        assertThat(AuditTui.getScopeStyle(" runtime ")).isEqualTo(Style.create().fg(Color.YELLOW));
    }

    @Test
    void getScopeStyleReturnsDefaultForUnknown() {
        assertThat(AuditTui.getScopeStyle("system")).isEqualTo(Style.create());
        assertThat(AuditTui.getScopeStyle("import")).isEqualTo(Style.create());
    }

    private static List<AuditTui.AuditEntry> buildTestEntries() {
        List<AuditTui.AuditEntry> entries = new ArrayList<>();
        var e1 = new AuditTui.AuditEntry("org.example", "vuln-lib", "1.0.0", "test");
        e1.licenseLoaded = true;
        e1.vulnsLoaded = true;
        e1.vulnerabilities =
                List.of(new OsvClient.Vulnerability("CVE-2024-0001", "Test vuln", "HIGH", "2024-01-01", List.of()));
        entries.add(e1);

        var e2 = new AuditTui.AuditEntry("org.example", "prod-lib", "2.0.0", "compile");
        e2.licenseLoaded = true;
        e2.vulnsLoaded = true;
        e2.vulnerabilities =
                List.of(new OsvClient.Vulnerability("CVE-2024-0002", "Prod vuln", "CRITICAL", "2024-02-01", List.of()));
        entries.add(e2);

        var e3 = new AuditTui.AuditEntry("org.example", "runtime-lib", "3.0.0", "runtime");
        e3.licenseLoaded = true;
        e3.vulnsLoaded = true;
        e3.vulnerabilities = List.of();
        entries.add(e3);

        return entries;
    }

    @Test
    void vulnerabilitiesViewRendersScopeColumn(@TempDir Path tempDir) throws Exception {
        String pomPath =
                Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();

        List<AuditTui.AuditEntry> entries = buildTestEntries();
        AuditTui tui = new AuditTui(entries, "com.example:test:1.0", null, pomPath);
        tui.rebuildVulnRows();

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::render, new Size(120, 30))) {
            var pilot = testRunner.pilot();

            // Navigate to Vulnerabilities tab (Tab -> By License, Tab -> Vulnerabilities)
            pilot.press(KeyCode.TAB);
            pilot.press(KeyCode.TAB);
            pilot.pause();

            // Navigate down to exercise detail pane rendering with different scopes
            pilot.press(KeyCode.DOWN);
            pilot.pause();
        }
    }

    @Test
    void scopeFilterCyclesAndFilters(@TempDir Path tempDir) throws Exception {
        String pomPath =
                Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();

        List<AuditTui.AuditEntry> entries = buildTestEntries();
        AuditTui tui = new AuditTui(entries, "com.example:test:1.0", null, pomPath);
        tui.rebuildVulnRows();

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::render, new Size(120, 30))) {
            var pilot = testRunner.pilot();

            // Initially shows all entries (3)
            pilot.pause();

            // Press 's' to filter to compile scope
            pilot.press('s');
            pilot.pause();

            // Press 's' again to cycle to runtime
            pilot.press('s');
            pilot.pause();

            // Press 's' to cycle to test
            pilot.press('s');
            pilot.pause();

            // Press 's' to cycle to provided
            pilot.press('s');
            pilot.pause();

            // Press 's' to cycle back to all
            pilot.press('s');
            pilot.pause();

            // Also test filtering on the vulnerabilities tab
            pilot.press(KeyCode.TAB);
            pilot.press(KeyCode.TAB);
            pilot.press('s'); // compile — only CVE-2024-0002 visible
            pilot.pause();

            pilot.press('s'); // runtime — no vulns
            pilot.pause();

            pilot.press('s'); // test — only CVE-2024-0001 visible
            pilot.pause();
        }
    }
}
