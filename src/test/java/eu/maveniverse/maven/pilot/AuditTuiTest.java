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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.DefaultDependencyNode;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.junit.jupiter.api.Test;

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
}
