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
        assertThat(sb.toString()).isEqualTo(" (test)");
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
        assertThat(result).doesNotContain("unused-lib");
        assertThat(result).contains("kept-lib");
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
        assertThat(result).contains("transitive-lib");
        assertThat(result).contains("existing-lib");
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
        assertThat(result).contains("test-lib");
        assertThat(result).contains("<scope>test</scope>");
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
        assertThat(result).doesNotContain("com.example");
        assertThat(result).contains("needed");
        assertThat(logs).hasSize(3); // remove + add + updated
    }
}
