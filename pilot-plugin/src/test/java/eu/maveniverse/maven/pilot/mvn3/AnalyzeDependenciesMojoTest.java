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
package eu.maveniverse.maven.pilot.mvn3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eu.maveniverse.maven.pilot.DependenciesTui;
import eu.maveniverse.maven.pilot.DependencyUsageAnalyzer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings({"deprecation", "removal"})
class AnalyzeDependenciesMojoTest {

    private final AnalyzeDependenciesMojo mojo = new AnalyzeDependenciesMojo(null);

    // --- Delegation to DependenciesReporter (backwards compat) ---

    @Test
    void formatFindingsDelegatesToReporter() {
        var dep = new DependenciesTui.DepEntry("com.example", "unused-lib", "", "1.0", "compile", true);
        String output = mojo.formatFindings(List.of(dep), List.of());
        assertThat(output).contains("com.example:unused-lib");
    }

    @Test
    void appendScopeDelegatesToReporter() {
        StringBuilder sb = new StringBuilder();
        var dep = new DependenciesTui.DepEntry("g", "a", "", "1", "test", true);
        AnalyzeDependenciesMojo.appendScope(sb, dep);
        assertThat(sb.toString()).isEqualTo(" (test)");
    }

    // --- check/report action tests ---

    @Test
    void checkThrowsMojoFailureException() {
        var dep = new DependenciesTui.DepEntry("com.example", "unused", "", "1.0", "compile", true);

        assertThatThrownBy(() -> mojo.check(List.of(dep), List.of()))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("com.example:unused")
                .hasMessageContaining("pilot.action=fix");
    }

    @Test
    void checkIncludesUsedTransitiveInMessage() {
        var transitive = new DependenciesTui.DepEntry("org.slf4j", "slf4j-api", "", "2.0", "compile", false);

        assertThatThrownBy(() -> mojo.check(List.of(), List.of(transitive)))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("org.slf4j:slf4j-api")
                .hasMessageContaining("should be declared");
    }

    @Test
    void reportDoesNotThrow() {
        var dep = new DependenciesTui.DepEntry("com.example", "unused", "", "1.0", "compile", true);
        var transitive = new DependenciesTui.DepEntry("org.slf4j", "slf4j-api", "", "2.0", "compile", false);
        mojo.report(List.of(dep), List.of(transitive));
    }

    // --- ignore list tests ---

    @Test
    void ignoreListFiltersUnusedDeclared() {
        var kept = new DependenciesTui.DepEntry("com.example", "kept", "", "1.0", "compile", true);
        var ignored = new DependenciesTui.DepEntry("com.example", "ignored", "", "1.0", "compile", true);
        List<DependenciesTui.DepEntry> unused = new ArrayList<>(List.of(kept, ignored));

        Set<String> ignoreSet = Set.of("com.example:ignored");
        unused.removeIf(dep -> DependencyUsageAnalyzer.matchesArtifactPattern(dep.ga(), ignoreSet));

        assertThat(unused).hasSize(1);
        assertThat(unused.get(0).ga()).isEqualTo("com.example:kept");
    }

    @Test
    void ignoreListFiltersUsedTransitive() {
        var reported = new DependenciesTui.DepEntry("com.needed", "lib", "", "1.0", "compile", false);
        var suppressed = new DependenciesTui.DepEntry("org.slf4j", "slf4j-api", "", "2.0", "compile", false);
        List<DependenciesTui.DepEntry> transitive = new ArrayList<>(List.of(reported, suppressed));

        Set<String> ignoreSet = Set.of("org.slf4j:slf4j-api");
        transitive.removeIf(dep -> DependencyUsageAnalyzer.matchesArtifactPattern(dep.ga(), ignoreSet));

        assertThat(transitive).hasSize(1);
        assertThat(transitive.get(0).ga()).isEqualTo("com.needed:lib");
    }

    @Test
    void ignoreListWildcardFiltersEntireGroup() {
        var dep1 = new DependenciesTui.DepEntry("org.slf4j", "slf4j-api", "", "2.0", "compile", false);
        var dep2 = new DependenciesTui.DepEntry("org.slf4j", "slf4j-simple", "", "2.0", "runtime", false);
        var dep3 = new DependenciesTui.DepEntry("com.other", "lib", "", "1.0", "compile", false);
        List<DependenciesTui.DepEntry> deps = new ArrayList<>(List.of(dep1, dep2, dep3));

        Set<String> ignoreSet = Set.of("org.slf4j:*");
        deps.removeIf(dep -> DependencyUsageAnalyzer.matchesArtifactPattern(dep.ga(), ignoreSet));

        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).ga()).isEqualTo("com.other:lib");
    }

    // --- buildIgnoreSet tests ---

    @Test
    void buildIgnoreSetNull() {
        assertThat(AnalyzeDependenciesMojo.buildIgnoreSet(null)).isEmpty();
    }

    @Test
    void buildIgnoreSetEmpty() {
        assertThat(AnalyzeDependenciesMojo.buildIgnoreSet(List.of())).isEmpty();
    }

    @Test
    void buildIgnoreSetPopulated() {
        Set<String> result = AnalyzeDependenciesMojo.buildIgnoreSet(List.of("org.slf4j:slf4j-api", "com.example:*"));
        assertThat(result).containsExactlyInAnyOrder("org.slf4j:slf4j-api", "com.example:*");
    }

    // --- buildAnalyzer tests ---

    @Test
    void buildAnalyzerDefaults() {
        var analyzer = mojo.buildAnalyzer();
        assertThat(analyzer).isNotNull();
    }

    @Test
    void buildAnalyzerWithConfig() throws Exception {
        var configuredMojo = new AnalyzeDependenciesMojo(null);
        MojoTestHelper.setField(configuredMojo, "runtimeArtifacts", List.of("org.postgresql:postgresql"));
        MojoTestHelper.setField(configuredMojo, "annotationOnlyArtifacts", List.of("org.projectlombok:lombok"));
        MojoTestHelper.setField(
                configuredMojo,
                "reflectionLoadedClasses",
                Map.of("org.postgresql:postgresql", "org.postgresql.Driver"));

        var analyzer = configuredMojo.buildAnalyzer();
        assertThat(analyzer).isNotNull();
    }

    // --- execute validation tests ---

    @Test
    void executeRejectsInvalidAction() throws Exception {
        var invalidMojo = new AnalyzeDependenciesMojo(null);
        MojoTestHelper.setField(invalidMojo, "action", "invalid");

        assertThatThrownBy(invalidMojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Invalid action 'invalid'");
    }

    // --- applyResults: USED_IN_TEST bucketing ---

    private static MavenProject tempProject(Path tmp) throws Exception {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, "<project/>");
        var proj = new MavenProject();
        proj.setFile(pom.toFile());
        var build = new Build();
        build.setOutputDirectory(tmp.resolve("classes").toString());
        build.setTestOutputDirectory(tmp.resolve("test-classes").toString());
        proj.setBuild(build);
        return proj;
    }

    private static MavenProject tempProjectWithDep(Path tmp, String groupId, String artifactId) throws Exception {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>%s</groupId>
                      <artifactId>%s</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(groupId, artifactId));
        var proj = new MavenProject();
        proj.setFile(pom.toFile());
        var build = new Build();
        build.setOutputDirectory(tmp.resolve("classes").toString());
        build.setTestOutputDirectory(tmp.resolve("test-classes").toString());
        proj.setBuild(build);
        return proj;
    }

    @Test
    void applyResults_usedInTest_declared_escalatesToCheckFailure(@TempDir Path tmp) throws Exception {
        var analyzeMojo = new AnalyzeDependenciesMojo(null);
        MojoTestHelper.setField(analyzeMojo, "action", "check");

        var dep = new DependenciesTui.DepEntry("com.example", "compile-test-only", "", "1.0", "compile", true);
        var result = new DependencyUsageAnalyzer.AnalysisResult(
                Map.of("com.example:compile-test-only", DependencyUsageAnalyzer.UsageStatus.USED_IN_TEST), Map.of());
        MavenProject proj = tempProject(tmp);

        assertThatThrownBy(() -> analyzeMojo.applyResults(proj, List.of(dep), List.of(), result, Map.of()))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("com.example:compile-test-only")
                .hasMessageContaining("narrowed to test");
    }

    @Test
    void applyResults_usedInTest_transitive_escalatesToCheckFailure(@TempDir Path tmp) throws Exception {
        var analyzeMojo = new AnalyzeDependenciesMojo(null);
        MojoTestHelper.setField(analyzeMojo, "action", "check");

        var dep = new DependenciesTui.DepEntry("com.example", "test-only-transitive", "", "1.0", "compile", false);
        dep.usageStatus = DependencyUsageAnalyzer.UsageStatus.USED_IN_TEST;
        var result = new DependencyUsageAnalyzer.AnalysisResult(
                Map.of(), Map.of("com.example:test-only-transitive", DependencyUsageAnalyzer.UsageStatus.USED_IN_TEST));
        MavenProject proj = tempProject(tmp);

        assertThatThrownBy(() -> analyzeMojo.applyResults(proj, List.of(), List.of(dep), result, Map.of()))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("com.example:test-only-transitive");
    }

    @Test
    void applyResults_noIssues_returnsCleanly(@TempDir Path tmp) throws Exception {
        var analyzeMojo = new AnalyzeDependenciesMojo(null);
        MojoTestHelper.setField(analyzeMojo, "action", "check");

        var dep = new DependenciesTui.DepEntry("com.example", "used-lib", "", "1.0", "compile", true);
        var result = new DependencyUsageAnalyzer.AnalysisResult(
                Map.of("com.example:used-lib", DependencyUsageAnalyzer.UsageStatus.USED), Map.of());
        MavenProject proj = tempProject(tmp);

        // No issues — should not throw
        analyzeMojo.applyResults(proj, List.of(dep), List.of(), result, Map.of());
    }

    @Test
    void applyResults_usedInTest_transitive_createsTestScopedCopy(@TempDir Path tmp) throws Exception {
        // Transitive USED_IN_TEST must be promoted as test-scoped WITHOUT mutating the original entry
        var analyzeMojo = new AnalyzeDependenciesMojo(null);
        MojoTestHelper.setField(analyzeMojo, "action", "report");

        var dep = new DependenciesTui.DepEntry("com.example", "test-only-transitive", "", "1.0", "compile", false);
        var result = new DependencyUsageAnalyzer.AnalysisResult(
                Map.of(), Map.of("com.example:test-only-transitive", DependencyUsageAnalyzer.UsageStatus.USED_IN_TEST));
        MavenProject proj = tempProject(tmp);

        analyzeMojo.applyResults(proj, List.of(), List.of(dep), result, Map.of());

        // Original dep must not be mutated
        assertThat(dep.scope).isEqualTo("compile");
    }

    @Test
    void applyResults_usedInTest_declared_report_producesWarning(@TempDir Path tmp) throws Exception {
        // report action: USED_IN_TEST declared dep must produce a warning, not throw.
        var analyzeMojo = new AnalyzeDependenciesMojo(null);
        MojoTestHelper.setField(analyzeMojo, "action", "report");

        var dep = new DependenciesTui.DepEntry("com.example", "compile-test-only", "", "1.0", "compile", true);
        var result = new DependencyUsageAnalyzer.AnalysisResult(
                Map.of("com.example:compile-test-only", DependencyUsageAnalyzer.UsageStatus.USED_IN_TEST), Map.of());
        MavenProject proj = tempProject(tmp);

        // Should not throw
        analyzeMojo.applyResults(proj, List.of(dep), List.of(), result, Map.of());
    }

    @Test
    void applyResults_usedInTest_declared_fix_narrowsScopeInPom(@TempDir Path tmp) throws Exception {
        // fix action: USED_IN_TEST declared dep must have its scope narrowed to test in the POM.
        var analyzeMojo = new AnalyzeDependenciesMojo(null);
        MojoTestHelper.setField(analyzeMojo, "action", "fix");

        var dep = new DependenciesTui.DepEntry("com.example", "compile-test-only", "", "1.0", "compile", true);
        var result = new DependencyUsageAnalyzer.AnalysisResult(
                Map.of("com.example:compile-test-only", DependencyUsageAnalyzer.UsageStatus.USED_IN_TEST), Map.of());
        MavenProject proj = tempProjectWithDep(tmp, "com.example", "compile-test-only");

        analyzeMojo.applyResults(proj, List.of(dep), List.of(), result, Map.of());

        String pomContent = Files.readString(proj.getFile().toPath());
        assertThat(pomContent).contains("<scope>test</scope>");
    }
}
