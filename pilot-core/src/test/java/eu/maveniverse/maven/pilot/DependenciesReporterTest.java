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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependenciesReporterTest {

    @Test
    void formatFindingsUnusedDeclaredOnly() {
        var dep = new DependenciesTui.DepEntry("com.example", "unused-lib", "", "1.0", "compile", true);

        String output = DependenciesReporter.formatFindings(List.of(dep), List.of());

        assertThat(output)
                .contains("Unused declared dependency (can be removed):")
                .contains("com.example:unused-lib")
                .doesNotContain("transitive");
    }

    @Test
    void formatFindingsUsedTransitiveOnly() {
        var dep = new DependenciesTui.DepEntry("com.transitive", "lib", "", "2.0", "compile", false);

        String output = DependenciesReporter.formatFindings(List.of(), List.of(dep));

        assertThat(output)
                .contains("Used transitive dependency (should be declared):")
                .contains("com.transitive:lib")
                .doesNotContain("Unused");
    }

    @Test
    void formatFindingsBothSections() {
        var unused = new DependenciesTui.DepEntry("com.example", "unused", "", "1.0", "compile", true);
        var transitive = new DependenciesTui.DepEntry("com.transitive", "needed", "", "2.0", "runtime", false);

        String output = DependenciesReporter.formatFindings(List.of(unused), List.of(transitive));

        assertThat(output)
                .contains("Unused declared dependency (can be removed):")
                .contains("com.example:unused")
                .contains("Used transitive dependency (should be declared):")
                .contains("com.transitive:needed (runtime)");
    }

    @Test
    void formatFindingsMultipleDeps() {
        var unused1 = new DependenciesTui.DepEntry("com.a", "one", "", "1.0", "compile", true);
        var unused2 = new DependenciesTui.DepEntry("com.b", "two", "", "1.0", "test", true);

        String output = DependenciesReporter.formatFindings(List.of(unused1, unused2), List.of());

        assertThat(output)
                .contains("Unused declared dependencies (can be removed):")
                .contains("com.a:one")
                .contains("com.b:two (test)");
    }

    @Test
    void formatFindingsCompileScopeOmitted() {
        var dep = new DependenciesTui.DepEntry("com.example", "lib", "", "1.0", "compile", true);

        String output = DependenciesReporter.formatFindings(List.of(dep), List.of());

        assertThat(output).contains("com.example:lib\n").doesNotContain("(compile)");
    }

    @Test
    void formatFindingsNonCompileScopeShown() {
        var dep = new DependenciesTui.DepEntry("com.example", "lib", "", "1.0", "provided", true);

        String output = DependenciesReporter.formatFindings(List.of(dep), List.of());

        assertThat(output).contains("com.example:lib (provided)");
    }

    @Test
    void appendScopeSkipsCompile() {
        StringBuilder sb = new StringBuilder();
        var dep = new DependenciesTui.DepEntry("g", "a", "", "1", "compile", true);
        DependenciesReporter.appendScope(sb, dep);
        assertThat(sb).isEmpty();
    }

    @Test
    void appendScopeIncludesNonCompile() {
        StringBuilder sb = new StringBuilder();
        var dep = new DependenciesTui.DepEntry("g", "a", "", "1", "test", true);
        DependenciesReporter.appendScope(sb, dep);
        assertThat(sb).hasToString(" (test)");
    }

    @Test
    void formatCheckFailureIncludesHint() {
        var dep = new DependenciesTui.DepEntry("com.example", "unused", "", "1.0", "compile", true);

        String msg = DependenciesReporter.formatCheckFailure(List.of(dep), List.of());

        assertThat(msg).contains("com.example:unused").contains("pilot.action=fix");
    }

    @Test
    void formatFindingsEmpty() {
        String output = DependenciesReporter.formatFindings(List.of(), List.of());
        assertThat(output).isEmpty();
    }

    @Test
    void formatFindingsUndeterminedOnly() {
        var dep = new DependenciesTui.DepEntry("com.example", "mystery-jar", "", "1.0", "compile", true);

        String output = DependenciesReporter.formatFindings(List.of(), List.of(), List.of(dep));

        assertThat(output)
                .contains("Undetermined dependency (usage could not be determined")
                .contains("com.example:mystery-jar")
                .doesNotContain("Unused declared")
                .doesNotContain("Used transitive");
    }

    @Test
    void formatFindingsAllThreeSections() {
        var unused = new DependenciesTui.DepEntry("com.example", "unused", "", "1.0", "compile", true);
        var transitive = new DependenciesTui.DepEntry("com.transitive", "needed", "", "2.0", "runtime", false);
        var undetermined = new DependenciesTui.DepEntry("com.example", "mystery", "", "3.0", "compile", true);

        String output =
                DependenciesReporter.formatFindings(List.of(unused), List.of(transitive), List.of(undetermined));

        assertThat(output)
                .contains("Unused declared dependency")
                .contains("com.example:unused")
                .contains("Used transitive dependency")
                .contains("com.transitive:needed (runtime)")
                .contains("Undetermined dependency")
                .contains("com.example:mystery");
    }

    @Test
    void formatFindingsMultipleUndetermined() {
        var dep1 = new DependenciesTui.DepEntry("com.a", "one", "", "1.0", "compile", true);
        var dep2 = new DependenciesTui.DepEntry("com.b", "two", "", "1.0", "test", false);

        String output = DependenciesReporter.formatFindings(List.of(), List.of(), List.of(dep1, dep2));

        assertThat(output)
                .contains("Undetermined dependencies (usage could not be determined")
                .contains("com.a:one")
                .contains("com.b:two (test)");
    }

    @Test
    void formatCheckFailureWithUndetermined() {
        var dep = new DependenciesTui.DepEntry("com.example", "mystery", "", "1.0", "compile", true);

        String msg = DependenciesReporter.formatCheckFailure(List.of(), List.of(), List.of(dep));

        assertThat(msg).contains("com.example:mystery").contains("Undetermined").contains("knownUsed");
    }

    // -- fix --

    @Test
    void fixRemovesUnusedDependency(@TempDir Path tempDir) throws Exception {
        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>unused-lib</artifactId>
                      <version>1.0</version>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>kept-lib</artifactId>
                      <version>2.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        var unused = new DependenciesTui.DepEntry("com.example", "unused-lib", "", "1.0", "compile", true);
        List<String> logs = new ArrayList<>();

        DependenciesReporter.fix(pomPath, List.of(unused), List.of(), Map.of(), logs::add);

        String result = Files.readString(pomPath);
        assertThat(result).doesNotContain("unused-lib").contains("kept-lib");
        assertThat(logs).anyMatch(l -> l.contains("Removed unused dependency: com.example:unused-lib"));
    }

    @Test
    void fixAddsUsedTransitiveDependency(@TempDir Path tempDir) throws Exception {
        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>existing-lib</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        var transitive = new DependenciesTui.DepEntry("org.needed", "transitive-lib", "", "2.0", "compile", false);
        List<String> logs = new ArrayList<>();

        DependenciesReporter.fix(
                pomPath, List.of(), List.of(transitive), Map.of("org.needed:transitive-lib", "2.0"), logs::add);

        String result = Files.readString(pomPath);
        assertThat(result).contains("transitive-lib").contains("existing-lib");
        assertThat(logs).anyMatch(l -> l.contains("Added used transitive dependency: org.needed:transitive-lib"));
    }

    @Test
    void fixHandlesNonCompileScope(@TempDir Path tempDir) throws Exception {
        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>existing</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        var transitive = new DependenciesTui.DepEntry("org.test", "test-lib", "", "1.0", "test", false);
        List<String> logs = new ArrayList<>();

        DependenciesReporter.fix(
                pomPath, List.of(), List.of(transitive), Map.of("org.test:test-lib", "1.0"), logs::add);

        String result = Files.readString(pomPath);
        assertThat(result).contains("test-lib").contains("<scope>test</scope>");
    }

    @Test
    void fixBothRemovesAndAdds(@TempDir Path tempDir) throws Exception {
        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>unused</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        var unused = new DependenciesTui.DepEntry("com.example", "unused", "", "1.0", "compile", true);
        var transitive = new DependenciesTui.DepEntry("org.needed", "needed", "", "2.0", "compile", false);
        List<String> logs = new ArrayList<>();

        DependenciesReporter.fix(
                pomPath, List.of(unused), List.of(transitive), Map.of("org.needed:needed", "2.0"), logs::add);

        String result = Files.readString(pomPath);
        assertThat(result).doesNotContain("com.example").contains("needed");
        assertThat(logs).hasSize(3); // remove + add + updated
    }

    // -- fix with ancestorManagedGAs --

    @Test
    void fixAncestorManagedOmitsVersion(@TempDir Path tempDir) throws Exception {
        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>existing</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        var transitive = new DependenciesTui.DepEntry("org.managed", "ancestor-lib", "", "3.0", "compile", false);
        List<String> logs = new ArrayList<>();
        Set<String> ancestorManaged = Set.of("org.managed:ancestor-lib");

        DependenciesReporter.fix(
                pomPath,
                List.of(),
                List.of(transitive),
                Map.of("org.managed:ancestor-lib", "3.0"),
                ancestorManaged,
                logs::add);

        String result = Files.readString(pomPath);
        assertThat(result)
                .contains("ancestor-lib")
                // The newly added dependency must NOT have its resolved version hardcoded
                .doesNotContain("<version>3.0</version>");
        assertThat(logs).anyMatch(l -> l.contains("version managed by ancestor"));
    }

    @Test
    void fixAncestorManagedNonCompileScopeWritten(@TempDir Path tempDir) throws Exception {
        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>existing</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        var transitive = new DependenciesTui.DepEntry("org.managed", "test-lib", "", "2.5", "test", false);
        List<String> logs = new ArrayList<>();
        Set<String> ancestorManaged = Set.of("org.managed:test-lib");

        DependenciesReporter.fix(
                pomPath,
                List.of(),
                List.of(transitive),
                Map.of("org.managed:test-lib", "2.5"),
                ancestorManaged,
                logs::add);

        String result = Files.readString(pomPath);
        assertThat(result)
                .contains("test-lib")
                .doesNotContain("<version>2.5</version>")
                .contains("<scope>test</scope>");
    }

    @Test
    void fixNonAncestorManagedWritesVersion(@TempDir Path tempDir) throws Exception {
        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>existing</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        var transitive = new DependenciesTui.DepEntry("org.other", "unmanaged-lib", "", "4.0", "compile", false);
        List<String> logs = new ArrayList<>();
        // ancestorManaged does NOT include org.other:unmanaged-lib
        Set<String> ancestorManaged = Set.of("org.managed:something-else");

        DependenciesReporter.fix(
                pomPath,
                List.of(),
                List.of(transitive),
                Map.of("org.other:unmanaged-lib", "4.0"),
                ancestorManaged,
                logs::add);

        String result = Files.readString(pomPath);
        assertThat(result).contains("unmanaged-lib").contains("4.0");
    }

    // -- testScopedDeclared (USED_IN_TEST) --

    @Test
    void formatFindingsTestScopedDeclaredSection() {
        var dep = new DependenciesTui.DepEntry("com.example", "compile-but-test-only", "", "1.0", "compile", true);

        String output = DependenciesReporter.formatFindings(List.of(), List.of(dep), List.of(), List.of());

        assertThat(output)
                .contains("used only in tests")
                .contains("narrowed to test")
                .contains("com.example:compile-but-test-only")
                .doesNotContain("Unused declared")
                .doesNotContain("Used transitive");
    }

    @Test
    void formatFindingsAllFourSections() {
        var unused = new DependenciesTui.DepEntry("com.example", "unused", "", "1.0", "compile", true);
        var testOnly = new DependenciesTui.DepEntry("com.example", "test-only", "", "1.0", "compile", true);
        var transitive = new DependenciesTui.DepEntry("com.transitive", "needed", "", "2.0", "runtime", false);
        var undetermined = new DependenciesTui.DepEntry("com.example", "mystery", "", "3.0", "compile", true);

        String output = DependenciesReporter.formatFindings(
                List.of(unused), List.of(testOnly), List.of(transitive), List.of(undetermined));

        assertThat(output)
                .contains("Unused declared dependency")
                .contains("com.example:unused")
                .contains("used only in tests")
                .contains("com.example:test-only")
                .contains("Used transitive dependency")
                .contains("com.transitive:needed (runtime)")
                .contains("Undetermined dependency")
                .contains("com.example:mystery");
    }

    @Test
    void formatCheckFailureWithTestScopedDeclared() {
        var dep = new DependenciesTui.DepEntry("com.example", "test-only", "", "1.0", "compile", true);

        String msg = DependenciesReporter.formatCheckFailure(List.of(), List.of(dep), List.of(), List.of());

        assertThat(msg)
                .contains("used only in tests")
                .contains("com.example:test-only")
                .contains("-Dpilot.action=fix");
    }

    @Test
    void fixNarrowsTestOnlyDepToTestScope(@TempDir Path tempDir) throws Exception {
        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>compile-but-test-only</artifactId>
                      <version>1.0</version>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>kept-as-is</artifactId>
                      <version>2.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        var testOnly = new DependenciesTui.DepEntry("com.example", "compile-but-test-only", "", "1.0", "compile", true);
        List<String> logs = new ArrayList<>();

        DependenciesReporter.fix(pomPath, List.of(), List.of(testOnly), List.of(), Map.of(), Set.of(), logs::add);

        String result = Files.readString(pomPath);
        assertThat(result)
                .contains("compile-but-test-only")
                .contains("<scope>test</scope>")
                .contains("kept-as-is");
        assertThat(logs).anyMatch(l -> l.contains("Narrowed to test scope") && l.contains("compile-but-test-only"));
    }

    @Test
    void fixCombinesAllThreeActions(@TempDir Path tempDir) throws Exception {
        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>unused</artifactId>
                      <version>1.0</version>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>test-only</artifactId>
                      <version>2.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        var unused = new DependenciesTui.DepEntry("com.example", "unused", "", "1.0", "compile", true);
        var testOnly = new DependenciesTui.DepEntry("com.example", "test-only", "", "2.0", "compile", true);
        var transitive = new DependenciesTui.DepEntry("org.needed", "lib", "", "3.0", "compile", false);
        List<String> logs = new ArrayList<>();

        DependenciesReporter.fix(
                pomPath,
                List.of(unused),
                List.of(testOnly),
                List.of(transitive),
                Map.of("org.needed:lib", "3.0"),
                Set.of(),
                logs::add);

        String result = Files.readString(pomPath);
        assertThat(result)
                .doesNotContain("unused")
                .contains("test-only")
                .contains("<scope>test</scope>")
                .contains("org.needed")
                .contains("lib");
        assertThat(logs)
                .anyMatch(l -> l.contains("Removed unused dependency: com.example:unused"))
                .anyMatch(l -> l.contains("Narrowed to test scope") && l.contains("test-only"))
                .anyMatch(l -> l.contains("Added used transitive dependency: org.needed:lib"));
    }

    @Test
    void fixAncestorManagedNoDependenciesSection(@TempDir Path tempDir) throws Exception {
        // Tests the case where the POM has no <dependencies> section yet
        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>my-module</artifactId>
                  <version>1.0</version>
                </project>
                """);

        var transitive = new DependenciesTui.DepEntry("org.managed", "bom-lib", "", "5.0", "compile", false);
        List<String> logs = new ArrayList<>();
        Set<String> ancestorManaged = Set.of("org.managed:bom-lib");

        DependenciesReporter.fix(
                pomPath,
                List.of(),
                List.of(transitive),
                Map.of("org.managed:bom-lib", "5.0"),
                ancestorManaged,
                logs::add);

        String result = Files.readString(pomPath);
        assertThat(result)
                .contains("bom-lib")
                .doesNotContain("<version>5.0</version>")
                .contains("<dependencies>");
    }

    @Test
    void fixNoopWhenDepAlreadyAbsent(@TempDir Path tempDir) throws Exception {
        // When the dep to remove is not present in the POM (already absent),
        // deleteDependency returns false → no "Removed" log entry should be emitted.
        Path pomPath = tempDir.resolve("pom.xml");
        String original = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>kept-lib</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """;
        Files.writeString(pomPath, original);

        // This dep is NOT in the POM — deleteDependency returns false, no log
        var notPresent = new DependenciesTui.DepEntry("com.example", "not-present", "", "1.0", "compile", true);
        List<String> logs = new ArrayList<>();

        DependenciesReporter.fix(pomPath, List.of(notPresent), List.of(), Map.of(), logs::add);

        // POM must still contain kept-lib and NOT contain a "Removed" entry for not-present
        String result = Files.readString(pomPath);
        assertThat(result).contains("kept-lib");
        assertThat(logs).noneMatch(l -> l.contains("Removed unused dependency: com.example:not-present"));
        // Only the "Updated" message should be logged
        assertThat(logs).anyMatch(l -> l.startsWith("Updated "));
    }

    @Test
    void fixNoopWhenTransitiveAlreadyPresent(@TempDir Path tempDir) throws Exception {
        // When the transitive dep to add is already declared in the POM,
        // addAligned returns false → no "Added" log entry should be emitted.
        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.already</groupId>
                      <artifactId>present-lib</artifactId>
                      <version>2.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        // This dep IS already in the POM — addAligned returns false, no log
        var alreadyPresent = new DependenciesTui.DepEntry("org.already", "present-lib", "", "2.0", "compile", false);
        List<String> logs = new ArrayList<>();

        DependenciesReporter.fix(
                pomPath, List.of(), List.of(alreadyPresent), Map.of("org.already:present-lib", "2.0"), logs::add);

        // No "Added" message for the already-present dep
        assertThat(logs).noneMatch(l -> l.contains("Added used transitive dependency: org.already:present-lib"));
        // Only the "Updated" message should be logged
        assertThat(logs).anyMatch(l -> l.startsWith("Updated "));
    }

    @Test
    void fixNoopWhenNarrowingAlreadyTestScope(@TempDir Path tempDir) throws Exception {
        // When the dep's scope is already <test>, the alreadyTest guard must prevent
        // logging — CountingFixLogger must not count it as a change, so the pass
        // converges cleanly (passTotal == 0 on re-run).
        Path pomPath = tempDir.resolve("pom.xml");
        Files.writeString(pomPath, """
                        <project>
                          <dependencies>
                            <dependency>
                              <groupId>com.example</groupId>
                              <artifactId>already-test</artifactId>
                              <version>1.0</version>
                              <scope>test</scope>
                            </dependency>
                          </dependencies>
                        </project>
                        """);

        // This dep already has <scope>test</scope> — alreadyTest guard fires, no log
        var dep = new DependenciesTui.DepEntry("com.example", "already-test", "", "1.0", "test", true);
        List<String> logs = new ArrayList<>();

        DependenciesReporter.fix(pomPath, List.of(), List.of(dep), List.of(), Map.of(), Set.of(), logs::add);

        // No "Narrowed" message — scope was already test
        assertThat(logs)
                .noneMatch(l -> l.contains("Narrowed to test scope (used only in tests): com.example:already-test"));
        // POM scope must remain <test>
        assertThat(Files.readString(pomPath)).contains("<scope>test</scope>");
        // Only the "Updated" message may be logged (pre-existing write-always behaviour)
        assertThat(logs).noneMatch(l -> l.contains("Narrowed"));
    }
}
