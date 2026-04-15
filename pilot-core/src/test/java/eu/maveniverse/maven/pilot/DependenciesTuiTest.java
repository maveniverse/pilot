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
import org.junit.jupiter.api.Test;

class DependenciesTuiTest {

    @Test
    void depEntryGaWithoutClassifier() {
        var dep = new DependenciesTui.DepEntry("org.slf4j", "slf4j-api", "", "2.0.9", "compile", true);
        assertThat(dep.ga()).isEqualTo("org.slf4j:slf4j-api");
    }

    @Test
    void depEntryGaWithClassifier() {
        var dep = new DependenciesTui.DepEntry("dev.tamboui", "tamboui-core", "test-fixtures", "0.1.0", "test", true);
        assertThat(dep.ga()).isEqualTo("dev.tamboui:tamboui-core:test-fixtures");
    }

    @Test
    void depEntryGavWithoutClassifier() {
        var dep = new DependenciesTui.DepEntry("org.slf4j", "slf4j-api", "", "2.0.9", "compile", true);
        assertThat(dep.gav()).isEqualTo("org.slf4j:slf4j-api:2.0.9");
    }

    @Test
    void depEntryGavWithClassifier() {
        var dep = new DependenciesTui.DepEntry("dev.tamboui", "tamboui-core", "test-fixtures", "0.1.0", "test", true);
        assertThat(dep.gav()).isEqualTo("dev.tamboui:tamboui-core:test-fixtures:0.1.0");
    }

    @Test
    void depEntryNullClassifierDefaultsToEmpty() {
        var dep = new DependenciesTui.DepEntry("g", "a", null, "1.0", "compile", true);
        assertThat(dep.classifier).isEmpty();
        assertThat(dep.ga()).isEqualTo("g:a");
    }

    @Test
    void depEntryDefaults() {
        var dep = new DependenciesTui.DepEntry("g", "a", null, null, null, false);
        assertThat(dep.version).isEmpty();
        assertThat(dep.scope).isEqualTo("compile");
        assertThat(dep.classifier).isEmpty();
    }

    @Test
    void depEntryHasClassifier() {
        assertThat(new DependenciesTui.DepEntry("g", "a", "tests", "1.0", "test", true).hasClassifier())
                .isTrue();
        assertThat(new DependenciesTui.DepEntry("g", "a", "", "1.0", "compile", true).hasClassifier())
                .isFalse();
        assertThat(new DependenciesTui.DepEntry("g", "a", null, "1.0", "compile", true).hasClassifier())
                .isFalse();
    }

    @Test
    void addDeclaredEntry() {
        Set<String> gas = new HashSet<>();
        List<DependenciesTui.DepEntry> entries = new ArrayList<>();
        DependenciesTui.addDeclaredEntry(gas, entries, "org.slf4j", "slf4j-api", "", "2.0.9", "compile");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).declared).isTrue();
        assertThat(entries.get(0).ga()).isEqualTo("org.slf4j:slf4j-api");
        assertThat(gas).contains("org.slf4j:slf4j-api");
    }

    @Test
    void addDeclaredEntryWithClassifier() {
        Set<String> gas = new HashSet<>();
        List<DependenciesTui.DepEntry> entries = new ArrayList<>();
        DependenciesTui.addDeclaredEntry(gas, entries, "dev.tamboui", "tamboui-core", "test-fixtures", "0.1.0", "test");
        assertThat(entries.get(0).ga()).isEqualTo("dev.tamboui:tamboui-core:test-fixtures");
        assertThat(gas).contains("dev.tamboui:tamboui-core:test-fixtures");
    }

    // -- collectTransitive tests --

    private DependencyTreeModel.TreeNode treeNode(String g, String a, String v, String scope) {
        return new DependencyTreeModel.TreeNode(g, a, v, scope, false, 0);
    }

    private DependencyTreeModel.TreeNode treeNode(String g, String a, String classifier, String v, String scope) {
        return new DependencyTreeModel.TreeNode(g, a, classifier, v, scope, false, 0);
    }

    @Test
    void collectTransitiveFindsUndeclared() {
        var root = treeNode("com.example", "app", "1.0", "compile");
        var child = treeNode("org.slf4j", "slf4j-api", "2.0.9", "compile");
        var grandchild = treeNode("org.slf4j", "slf4j-impl", "2.0.9", "runtime");
        child.children.add(grandchild);
        root.children.add(child);

        Set<String> declaredGAs = Set.of("org.slf4j:slf4j-api");
        Set<String> seen = new HashSet<>();
        List<DependenciesTui.DepEntry> result = new ArrayList<>();

        DependenciesTui.collectTransitive(root, declaredGAs, seen, result);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).ga()).isEqualTo("org.slf4j:slf4j-impl");
        assertThat(result.get(0).declared).isFalse();
    }

    @Test
    void collectTransitiveSetsPulledBy() {
        var root = treeNode("com.example", "app", "1.0", "compile");
        var child = treeNode("com.google.guava", "guava", "33.0", "compile");
        var grandchild = treeNode("com.google.guava", "failureaccess", "1.0.2", "compile");
        child.children.add(grandchild);
        root.children.add(child);

        Set<String> declaredGAs = Set.of("com.google.guava:guava");
        Set<String> seen = new HashSet<>();
        List<DependenciesTui.DepEntry> result = new ArrayList<>();

        DependenciesTui.collectTransitive(root, declaredGAs, seen, result);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).pulledBy).isEqualTo("com.google.guava:guava");
    }

    @Test
    void collectTransitiveSkipsDeclared() {
        var root = treeNode("com.example", "app", "1.0", "compile");
        var child = treeNode("org.slf4j", "slf4j-api", "2.0.9", "compile");
        root.children.add(child);

        Set<String> declaredGAs = Set.of("org.slf4j:slf4j-api");
        Set<String> seen = new HashSet<>();
        List<DependenciesTui.DepEntry> result = new ArrayList<>();

        DependenciesTui.collectTransitive(root, declaredGAs, seen, result);

        assertThat(result).isEmpty();
    }

    @Test
    void collectTransitiveDeduplicates() {
        var root = treeNode("com.example", "app", "1.0", "compile");
        var child1 = treeNode("com.google.guava", "guava", "33.0", "compile");
        var child2 = treeNode("com.example", "other", "1.0", "compile");
        var dup1 = treeNode("com.google.guava", "failureaccess", "1.0.2", "compile");
        var dup2 = treeNode("com.google.guava", "failureaccess", "1.0.2", "compile");
        child1.children.add(dup1);
        child2.children.add(dup2);
        root.children.add(child1);
        root.children.add(child2);

        Set<String> declaredGAs = new HashSet<>();
        Set<String> seen = new HashSet<>();
        List<DependenciesTui.DepEntry> result = new ArrayList<>();

        DependenciesTui.collectTransitive(root, declaredGAs, seen, result);

        long failureAccessCount = result.stream()
                .filter(e -> e.ga().equals("com.google.guava:failureaccess"))
                .count();
        assertThat(failureAccessCount).isEqualTo(1);
    }

    @Test
    void collectTransitiveWithClassifier() {
        var root = treeNode("com.example", "app", "1.0", "compile");
        var child = treeNode("com.example", "lib", "1.0", "compile");
        var classifiedChild = treeNode("dev.tamboui", "tamboui-core", "test-fixtures", "0.1.0", "test");
        child.children.add(classifiedChild);
        root.children.add(child);

        Set<String> declaredGAs = new HashSet<>();
        Set<String> seen = new HashSet<>();
        List<DependenciesTui.DepEntry> result = new ArrayList<>();

        DependenciesTui.collectTransitive(root, declaredGAs, seen, result);

        var classified = result.stream()
                .filter(e -> e.ga().equals("dev.tamboui:tamboui-core:test-fixtures"))
                .findFirst();
        assertThat(classified).isPresent();
        assertThat(classified.orElseThrow().classifier).isEqualTo("test-fixtures");
    }

    // -- addDependencyAligned tests --

    @Test
    void addAlignedFollowsInlineLiteralConvention() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.slf4j</groupId>
                      <artifactId>slf4j-api</artifactId>
                      <version>2.0.9</version>
                    </dependency>
                  </dependencies>
                </project>
                """;
        String result = DependenciesTui.addDependencyAligned(pom, "com.google.guava", "guava", "33.0", null, null);
        // Should add with inline literal version (matching existing convention)
        assertThat(result)
                .contains("<artifactId>guava</artifactId>")
                .contains("<version>33.0</version>")
                .doesNotContain("<dependencyManagement>");
    }

    @Test
    void addAlignedFollowsManagedConvention() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.slf4j</groupId>
                        <artifactId>slf4j-api</artifactId>
                        <version>2.0.9</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.slf4j</groupId>
                      <artifactId>slf4j-api</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """;
        String result = DependenciesTui.addDependencyAligned(pom, "com.google.guava", "guava", "33.0", null, null);
        // Managed entry should contain version
        String mgmt =
                result.substring(result.indexOf("<dependencyManagement>"), result.indexOf("</dependencyManagement>"));
        assertThat(mgmt).contains("<artifactId>guava</artifactId>").contains("<version>33.0</version>");
        // Dependency in <dependencies> should be version-less
        String deps = result.substring(result.lastIndexOf("<dependencies>"));
        assertThat(deps).contains("<artifactId>guava</artifactId>").doesNotContain("<version>33.0</version>");
    }

    @Test
    void addAlignedFollowsPropertyConvention() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <properties>
                    <slf4j.version>2.0.9</slf4j.version>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.slf4j</groupId>
                      <artifactId>slf4j-api</artifactId>
                      <version>${slf4j.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """;
        String result = DependenciesTui.addDependencyAligned(pom, "com.google.guava", "guava", "33.0", null, null);
        // Should create a version property and reference it via ${...} in the dependency version
        assertThat(result)
                .contains("<artifactId>guava</artifactId>")
                .contains("33.0</")
                .containsPattern("<version>\\$\\{[^}]+\\}</version>");
    }

    @Test
    void addAlignedWithScope() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.slf4j</groupId>
                      <artifactId>slf4j-api</artifactId>
                      <version>2.0.9</version>
                    </dependency>
                  </dependencies>
                </project>
                """;
        String result = DependenciesTui.addDependencyAligned(pom, "junit", "junit", "4.13.2", null, "test");
        assertThat(result).contains("<artifactId>junit</artifactId>").contains("<scope>test</scope>");
    }

    @Test
    void addAlignedWithClassifier() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.slf4j</groupId>
                      <artifactId>slf4j-api</artifactId>
                      <version>2.0.9</version>
                    </dependency>
                  </dependencies>
                </project>
                """;
        String result = DependenciesTui.addDependencyAligned(
                pom, "dev.tamboui", "tamboui-core", "0.1.0", "test-fixtures", "test");
        assertThat(result)
                .contains("<artifactId>tamboui-core</artifactId>")
                .contains("<classifier>test-fixtures</classifier>")
                .contains("<scope>test</scope>");
    }

    @Test
    void addAlignedCompileScopeOmitted() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.slf4j</groupId>
                      <artifactId>slf4j-api</artifactId>
                      <version>2.0.9</version>
                    </dependency>
                  </dependencies>
                </project>
                """;
        String result = DependenciesTui.addDependencyAligned(pom, "com.google.guava", "guava", "33.0", null, "compile");
        assertThat(result).contains("<artifactId>guava</artifactId>").doesNotContain("<scope>");
    }
}
