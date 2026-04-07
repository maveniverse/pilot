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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.DefaultDependencyNode;
import org.eclipse.aether.graph.Dependency;
import org.junit.jupiter.api.Test;

class AnalyzeTuiTest {

    @Test
    void depEntryGaWithoutClassifier() {
        var dep = new AnalyzeTui.DepEntry("org.slf4j", "slf4j-api", "", "2.0.9", "compile", true);
        assertThat(dep.ga()).isEqualTo("org.slf4j:slf4j-api");
    }

    @Test
    void depEntryGaWithClassifier() {
        var dep = new AnalyzeTui.DepEntry("dev.tamboui", "tamboui-core", "test-fixtures", "0.1.0", "test", true);
        assertThat(dep.ga()).isEqualTo("dev.tamboui:tamboui-core:test-fixtures");
    }

    @Test
    void depEntryGavWithoutClassifier() {
        var dep = new AnalyzeTui.DepEntry("org.slf4j", "slf4j-api", "", "2.0.9", "compile", true);
        assertThat(dep.gav()).isEqualTo("org.slf4j:slf4j-api:2.0.9");
    }

    @Test
    void depEntryGavWithClassifier() {
        var dep = new AnalyzeTui.DepEntry("dev.tamboui", "tamboui-core", "test-fixtures", "0.1.0", "test", true);
        assertThat(dep.gav()).isEqualTo("dev.tamboui:tamboui-core:test-fixtures:0.1.0");
    }

    @Test
    void depEntryNullClassifierDefaultsToEmpty() {
        var dep = new AnalyzeTui.DepEntry("g", "a", null, "1.0", "compile", true);
        assertThat(dep.classifier).isEmpty();
        assertThat(dep.ga()).isEqualTo("g:a");
    }

    @Test
    void depEntryDefaults() {
        var dep = new AnalyzeTui.DepEntry("g", "a", null, null, null, false);
        assertThat(dep.version).isEmpty();
        assertThat(dep.scope).isEqualTo("compile");
        assertThat(dep.classifier).isEmpty();
    }

    @Test
    void depEntryHasClassifier() {
        assertThat(new AnalyzeTui.DepEntry("g", "a", "tests", "1.0", "test", true).hasClassifier())
                .isTrue();
        assertThat(new AnalyzeTui.DepEntry("g", "a", "", "1.0", "compile", true).hasClassifier())
                .isFalse();
        assertThat(new AnalyzeTui.DepEntry("g", "a", null, "1.0", "compile", true).hasClassifier())
                .isFalse();
    }

    @Test
    void addDeclaredEntry() {
        Set<String> gas = new HashSet<>();
        List<AnalyzeTui.DepEntry> entries = new ArrayList<>();
        AnalyzeTui.addDeclaredEntry(gas, entries, "org.slf4j", "slf4j-api", "", "2.0.9", "compile");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).declared).isTrue();
        assertThat(entries.get(0).ga()).isEqualTo("org.slf4j:slf4j-api");
        assertThat(gas).contains("org.slf4j:slf4j-api");
    }

    @Test
    void addDeclaredEntryWithClassifier() {
        Set<String> gas = new HashSet<>();
        List<AnalyzeTui.DepEntry> entries = new ArrayList<>();
        AnalyzeTui.addDeclaredEntry(gas, entries, "dev.tamboui", "tamboui-core", "test-fixtures", "0.1.0", "test");
        assertThat(entries.get(0).ga()).isEqualTo("dev.tamboui:tamboui-core:test-fixtures");
        assertThat(gas).contains("dev.tamboui:tamboui-core:test-fixtures");
    }

    // -- collectTransitive tests --

    private DefaultDependencyNode depNode(String g, String a, String v, String scope) {
        var artifact = new DefaultArtifact(g, a, "", "jar", v);
        return new DefaultDependencyNode(new Dependency(artifact, scope));
    }

    private DefaultDependencyNode rootNode(String g, String a, String v) {
        return new DefaultDependencyNode(new DefaultArtifact(g, a, "", "jar", v));
    }

    @Test
    void collectTransitiveFindsUndeclared() {
        var root = rootNode("com.example", "app", "1.0");
        var child = depNode("org.slf4j", "slf4j-api", "2.0.9", "compile");
        var grandchild = depNode("org.slf4j", "slf4j-impl", "2.0.9", "runtime");
        child.setChildren(List.of(grandchild));
        root.setChildren(List.of(child));

        Set<String> declaredGAs = Set.of("org.slf4j:slf4j-api");
        Set<String> seen = new HashSet<>();
        List<AnalyzeTui.DepEntry> result = new ArrayList<>();

        AnalyzeTui.collectTransitive(root, declaredGAs, seen, result);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).ga()).isEqualTo("org.slf4j:slf4j-impl");
        assertThat(result.get(0).declared).isFalse();
    }

    @Test
    void collectTransitiveSetsPulledBy() {
        var root = rootNode("com.example", "app", "1.0");
        var child = depNode("com.google.guava", "guava", "33.0", "compile");
        var grandchild = depNode("com.google.guava", "failureaccess", "1.0.2", "compile");
        child.setChildren(List.of(grandchild));
        root.setChildren(List.of(child));

        Set<String> declaredGAs = Set.of("com.google.guava:guava");
        Set<String> seen = new HashSet<>();
        List<AnalyzeTui.DepEntry> result = new ArrayList<>();

        AnalyzeTui.collectTransitive(root, declaredGAs, seen, result);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).pulledBy).isEqualTo("com.google.guava:guava");
    }

    @Test
    void collectTransitiveSkipsDeclared() {
        var root = rootNode("com.example", "app", "1.0");
        var child = depNode("org.slf4j", "slf4j-api", "2.0.9", "compile");
        root.setChildren(List.of(child));

        Set<String> declaredGAs = Set.of("org.slf4j:slf4j-api");
        Set<String> seen = new HashSet<>();
        List<AnalyzeTui.DepEntry> result = new ArrayList<>();

        AnalyzeTui.collectTransitive(root, declaredGAs, seen, result);

        assertThat(result).isEmpty();
    }

    @Test
    void collectTransitiveDeduplicates() {
        var root = rootNode("com.example", "app", "1.0");
        var child1 = depNode("com.google.guava", "guava", "33.0", "compile");
        var child2 = depNode("com.example", "other", "1.0", "compile");
        // Both pull the same transitive dep
        var dup1 = depNode("com.google.guava", "failureaccess", "1.0.2", "compile");
        var dup2 = depNode("com.google.guava", "failureaccess", "1.0.2", "compile");
        child1.setChildren(List.of(dup1));
        child2.setChildren(List.of(dup2));
        root.setChildren(List.of(child1, child2));

        Set<String> declaredGAs = new HashSet<>();
        Set<String> seen = new HashSet<>();
        List<AnalyzeTui.DepEntry> result = new ArrayList<>();

        AnalyzeTui.collectTransitive(root, declaredGAs, seen, result);

        long failureAccessCount = result.stream()
                .filter(e -> e.ga().equals("com.google.guava:failureaccess"))
                .count();
        assertThat(failureAccessCount).isEqualTo(1);
    }

    @Test
    void collectTransitiveWithClassifier() {
        var root = rootNode("com.example", "app", "1.0");
        var child = depNode("com.example", "lib", "1.0", "compile");
        var artifact = new DefaultArtifact("dev.tamboui", "tamboui-core", "test-fixtures", "jar", "0.1.0");
        var classifiedChild = new DefaultDependencyNode(new Dependency(artifact, "test"));
        child.setChildren(List.of(classifiedChild));
        root.setChildren(List.of(child));

        Set<String> declaredGAs = new HashSet<>();
        Set<String> seen = new HashSet<>();
        List<AnalyzeTui.DepEntry> result = new ArrayList<>();

        AnalyzeTui.collectTransitive(root, declaredGAs, seen, result);

        var classified = result.stream()
                .filter(e -> e.ga().equals("dev.tamboui:tamboui-core:test-fixtures"))
                .findFirst();
        assertThat(classified).isPresent();
        assertThat(classified.orElseThrow().classifier).isEqualTo("test-fixtures");
    }
}
