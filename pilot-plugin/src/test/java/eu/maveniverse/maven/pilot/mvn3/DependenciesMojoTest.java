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
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputSource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependenciesMojoTest {

    @Test
    void executeRejectsInvalidAction() throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "invalid");

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Invalid action 'invalid'");
    }

    @Test
    void defaultActionIsReport() throws Exception {
        var mojo = new DependenciesMojo(null);
        assertThat(MojoTestHelper.getField(mojo, "action")).isEqualTo("report");
    }

    @Test
    void rejectsTuiAction() throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "tui");
        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Invalid action 'tui'");
    }

    @Test
    void executeAcceptsReportAction() throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "report");
        assertThat(MojoTestHelper.getField(mojo, "action")).isEqualTo("report");
    }

    @Test
    void executeAcceptsCheckAction() throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");
        assertThat(MojoTestHelper.getField(mojo, "action")).isEqualTo("check");
    }

    @Test
    void executeAcceptsFixAction() throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "fix");
        assertThat(MojoTestHelper.getField(mojo, "action")).isEqualTo("fix");
    }

    // --- buildIgnoreSet ---

    @Test
    void buildIgnoreSetNull() {
        assertThat(DependenciesMojo.buildIgnoreSet(null)).isEmpty();
    }

    @Test
    void buildIgnoreSetEmpty() {
        assertThat(DependenciesMojo.buildIgnoreSet(List.of())).isEmpty();
    }

    @Test
    void buildIgnoreSetPopulated() {
        Set<String> result = DependenciesMojo.buildIgnoreSet(List.of("org.slf4j:slf4j-api", "com.example:*"));
        assertThat(result).containsExactlyInAnyOrder("org.slf4j:slf4j-api", "com.example:*");
    }

    // --- buildAnalyzer ---

    @Test
    void buildAnalyzerDefaults() {
        var mojo = new DependenciesMojo(null);
        var analyzer = mojo.buildAnalyzer();
        assertThat(analyzer).isNotNull();
    }

    @Test
    void buildAnalyzerWithAllowlists() throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "runtimeArtifacts", List.of("org.postgresql:postgresql"));
        MojoTestHelper.setField(mojo, "annotationOnlyArtifacts", List.of("org.projectlombok:lombok"));
        MojoTestHelper.setField(
                mojo, "reflectionLoadedClasses", Map.of("org.postgresql:postgresql", "org.postgresql.Driver"));

        var analyzer = mojo.buildAnalyzer();
        assertThat(analyzer).isNotNull();
    }

    // --- buildAncestorManagedGAs ---

    private static MavenProject projectWithDM(File pomFile, List<Dependency> deps) {
        MavenProject proj = new MavenProject();
        proj.setFile(pomFile);
        DependencyManagement dm = new DependencyManagement();
        dm.setDependencies(deps);
        proj.getModel().setDependencyManagement(dm);
        return proj;
    }

    private static Dependency dep(String groupId, String artifactId, String version, InputLocation loc) {
        Dependency d = new Dependency();
        d.setGroupId(groupId);
        d.setArtifactId(artifactId);
        d.setVersion(version);
        d.setLocation("", loc);
        return d;
    }

    private static InputLocation locFor(String path) {
        InputSource src = new InputSource();
        src.setLocation(path);
        return new InputLocation(1, 1, src);
    }

    @Test
    void buildAncestorManagedGAs_emptyWhenNoDependencyManagement() throws Exception {
        MavenProject proj = new MavenProject();
        proj.setFile(Files.createTempFile("pom", ".xml").toFile());
        assertThat(DependenciesMojo.buildAncestorManagedGAs(proj)).isEmpty();
    }

    @Test
    void buildAncestorManagedGAs_ownEntryExcluded() throws Exception {
        File pomFile = Files.createTempFile("pom", ".xml").toFile();
        String ownPath = pomFile.toPath().normalize().toString();

        Dependency own = dep("com.example", "own-lib", "1.0", locFor(ownPath));
        Dependency inherited = dep("com.other", "parent-lib", "2.0", locFor("/parent/pom.xml"));

        Set<String> result = DependenciesMojo.buildAncestorManagedGAs(projectWithDM(pomFile, List.of(own, inherited)));

        assertThat(result).containsExactly("com.other:parent-lib");
        assertThat(result).doesNotContain("com.example:own-lib");
    }

    @Test
    void buildAncestorManagedGAs_parentInheritedIncluded() throws Exception {
        File pomFile = Files.createTempFile("pom", ".xml").toFile();
        Dependency inherited = dep("org.parent", "parent-dep", "3.0", locFor("/some/parent/pom.xml"));

        Set<String> result = DependenciesMojo.buildAncestorManagedGAs(projectWithDM(pomFile, List.of(inherited)));

        assertThat(result).containsExactly("org.parent:parent-dep");
    }

    @Test
    void buildAncestorManagedGAs_nullInputLocationTreatedAsAncestor() throws Exception {
        File pomFile = Files.createTempFile("pom", ".xml").toFile();
        // Dependency with no InputLocation metadata — treated conservatively as ancestor-managed.
        Dependency noLoc = new Dependency();
        noLoc.setGroupId("com.unknown");
        noLoc.setArtifactId("mystery-lib");
        noLoc.setVersion("1.0");
        // no setLocation call → getLocation("") returns null

        Set<String> result = DependenciesMojo.buildAncestorManagedGAs(projectWithDM(pomFile, List.of(noLoc)));

        assertThat(result).containsExactly("com.unknown:mystery-lib");
    }

    @Test
    void buildAncestorManagedGAs_classifiedDependencyIncluded() throws Exception {
        File pomFile = Files.createTempFile("pom", ".xml").toFile();
        Dependency classified = dep("com.other", "lib", "1.0", locFor("/parent/pom.xml"));
        classified.setClassifier("tests");

        Set<String> result = DependenciesMojo.buildAncestorManagedGAs(projectWithDM(pomFile, List.of(classified)));

        assertThat(result).containsExactly("com.other:lib:tests");
    }

    @Test
    void buildAncestorManagedGAs_nonNormalizedOwnPathExcluded() throws Exception {
        // Regression: sourcePath from InputLocation may contain "." or ".." and must be normalized
        // before comparison with ownPomPath (which is always normalized) to avoid falsely classifying
        // the module's own DM entries as ancestor-managed.
        File pomFile = Files.createTempFile("pom", ".xml").toFile();
        // Construct a non-normalized equivalent: insert a redundant "." segment.
        String nonNormalizedPath = pomFile.getParent() + "/." + "/" + pomFile.getName();

        Dependency own = dep("com.example", "own-lib", "1.0", locFor(nonNormalizedPath));

        Set<String> result = DependenciesMojo.buildAncestorManagedGAs(projectWithDM(pomFile, List.of(own)));

        // own-lib must NOT appear in ancestorManagedGAs despite the non-normalized path
        assertThat(result).doesNotContain("com.example:own-lib");
    }

    // --- skipTestScope parameter ---

    @Test
    void defaultSkipTestScopeIsFalse() throws Exception {
        var mojo = new DependenciesMojo(null);
        assertThat(MojoTestHelper.getField(mojo, "skipTestScope")).isEqualTo(false);
    }

    @Test
    void executeNonInteractive_check_escalatesUnusedTestDep() throws Exception {
        // check action: a pre-classified UNUSED test-scoped dep must be escalated to MojoFailureException.
        // (The dep classification happens upstream in executeForProject; here we verify that
        // executeNonInteractive correctly collects it into unusedDeclared and fails.)
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");

        var dep = new DependenciesTui.DepEntry("org.awaitility", "awaitility", "", "4.2.0", "test", true);
        dep.usageStatus = DependencyUsageAnalyzer.UsageStatus.UNUSED;

        List<DependenciesTui.DepEntry> declared = List.of(dep);
        List<DependenciesTui.DepEntry> transitive = List.of();

        MavenProject proj = new MavenProject();
        proj.setFile(Files.createTempFile("pom", ".xml").toFile());

        // check action: UNUSED dep in unusedDeclared → MojoFailureException
        assertThatThrownBy(() -> mojo.executeNonInteractive(proj, declared, transitive, Map.of()))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("org.awaitility:awaitility");
    }

    // --- executeForProject guard ---

    @Test
    void executeForProject_failsWhenClassesAbsent(@TempDir Path tmp) throws Exception {
        // Create a non-empty main source directory so hasMainSources() returns true
        Path mainSrcDir = Files.createDirectories(tmp.resolve("src/main/java"));
        Files.createFile(mainSrcDir.resolve("Foo.java"));

        MavenProject proj = new MavenProject();
        proj.setPackaging("jar");
        proj.addCompileSourceRoot(mainSrcDir.toString()); // getCompileSourceRoots() is what hasMainSources() reads
        proj.getBuild().setOutputDirectory(tmp.resolve("classes").toString()); // non-existent
        proj.getBuild().setTestOutputDirectory(tmp.resolve("test-classes").toString());

        var mojo = new DependenciesMojo(null);
        assertThatThrownBy(() -> mojo.executeForProject(proj))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("target/classes not found");
    }

    @Test
    void executeForProject_failsWhenTestClassesAbsentAndTestScopedDepDeclared(@TempDir Path tmp) throws Exception {
        Path classesDir = Files.createDirectory(tmp.resolve("classes"));
        // Create a non-empty test source directory so hasTestSources() returns true
        Path testSrcDir = Files.createDirectories(tmp.resolve("src/test/java"));
        Files.createFile(testSrcDir.resolve("FooTest.java"));

        MavenProject proj = new MavenProject();
        proj.setPackaging("jar");
        proj.getBuild().setOutputDirectory(classesDir.toString());
        proj.getBuild().setTestOutputDirectory(tmp.resolve("test-classes").toString()); // non-existent
        proj.addTestCompileSourceRoot(
                testSrcDir.toString()); // getTestCompileSourceRoots() is what hasTestSources() reads
        Dependency dep = new Dependency();
        dep.setGroupId("org.junit.jupiter");
        dep.setArtifactId("junit-jupiter-api");
        dep.setVersion("5.10.0");
        dep.setScope("test");
        proj.getDependencies().add(dep);

        var mojo = new DependenciesMojo(null);
        assertThatThrownBy(() -> mojo.executeForProject(proj))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("target/test-classes not found");
    }

    @Test
    void executeForProject_doesNotFailWhenTestClassesAbsentButNoTestSources(@TempDir Path tmp) throws Exception {
        // Projects with test-scoped deps but no test sources (e.g. maven-plugin packaging
        // that inherits test deps from parent) must not be rejected — no test sources means
        // no test classes to compile, so the absence of target/test-classes is expected.
        Path classesDir = Files.createDirectory(tmp.resolve("classes"));

        MavenProject proj = new MavenProject();
        proj.setPackaging("maven-plugin");
        proj.getBuild().setOutputDirectory(classesDir.toString());
        proj.getBuild().setTestOutputDirectory(tmp.resolve("test-classes").toString()); // non-existent
        // No test source directory set
        Dependency dep = new Dependency();
        dep.setGroupId("org.junit.jupiter");
        dep.setArtifactId("junit-jupiter-api");
        dep.setVersion("5.10.0");
        dep.setScope("test");
        proj.getDependencies().add(dep);

        var mojo = new DependenciesMojo(null);
        // Guard must NOT throw MojoExecutionException — will NPE deeper on repoSystem (null in tests)
        assertThatThrownBy(() -> mojo.executeForProject(proj)).isNotInstanceOf(MojoExecutionException.class);
    }

    @Test
    void executeForProject_packageOnlyTestSrcDirTreatedAsNoTestSources(@TempDir Path tmp) throws Exception {
        // Regression: a test-source directory that contains only package subdirectories (no .java files)
        // must be treated as "no test sources". Previously, Files.list().findAny() returned true on
        // the package directory, causing a false positive that triggered the test-classes guard.
        Path classesDir = Files.createDirectory(tmp.resolve("classes"));
        // Create a package directory inside src/test/java but no actual source files
        Files.createDirectories(tmp.resolve("src/test/java/com/example"));

        MavenProject proj = new MavenProject();
        proj.setPackaging("jar");
        proj.getBuild().setOutputDirectory(classesDir.toString());
        proj.getBuild().setTestOutputDirectory(tmp.resolve("test-classes").toString()); // non-existent
        proj.addTestCompileSourceRoot(
                tmp.resolve("src/test/java").toString()); // getTestCompileSourceRoots() is what hasTestSources() reads
        Dependency dep = new Dependency();
        dep.setGroupId("org.junit.jupiter");
        dep.setArtifactId("junit-jupiter-api");
        dep.setVersion("5.10.0");
        dep.setScope("test");
        proj.getDependencies().add(dep);

        var mojo = new DependenciesMojo(null);
        // Guard must NOT throw MojoExecutionException (no actual test sources → absence of
        // test-classes is expected). It will NPE deeper on repoSystem, which is fine.
        assertThatThrownBy(() -> mojo.executeForProject(proj)).isNotInstanceOf(MojoExecutionException.class);
    }

    @Test
    void executeForProject_doesNotFailWhenTestClassesAbsentAndNoTestScopedDeps(@TempDir Path tmp) throws Exception {
        // Projects with no test-scoped deps and no test-classes must not be rejected —
        // the guard must be a no-op, and executeForProject should proceed past it
        // (it will NPE later on repoSystem, which is fine — the guard didn't fire).
        Path classesDir = Files.createDirectory(tmp.resolve("classes"));

        MavenProject proj = new MavenProject();
        proj.setPackaging("jar");
        proj.getBuild().setOutputDirectory(classesDir.toString());
        proj.getBuild().setTestOutputDirectory(tmp.resolve("test-classes").toString()); // non-existent
        // No dependencies at all

        var mojo = new DependenciesMojo(null);
        // Guard must NOT throw MojoExecutionException — it will NPE deeper in repoSystem
        assertThatThrownBy(() -> mojo.executeForProject(proj)).isNotInstanceOf(MojoExecutionException.class);
    }

    @Test
    void executeForProject_generatedTestSourcesDetectedViaCompileSourceRoots(@TempDir Path tmp) throws Exception {
        // Regression for gnodet-bot review: a project that has ONLY generated test sources
        // (empty primary dir, non-empty generated root registered via addTestCompileSourceRoot)
        // must be classified as having test sources. Previously, hasTestSources() only checked
        // getBuild().getTestSourceDirectory() — the primary dir — missing generated roots entirely.
        Path classesDir = Files.createDirectory(tmp.resolve("classes"));
        // Primary test source dir: exists but empty (no source files)
        Path primaryTestSrcDir = Files.createDirectories(tmp.resolve("src/test/java"));
        // Generated test source root: registered separately by an annotation processor
        Path generatedTestSrcDir = Files.createDirectories(tmp.resolve("target/generated-test-sources/annotations"));
        Files.createFile(generatedTestSrcDir.resolve("GeneratedTest.java"));

        MavenProject proj = new MavenProject();
        proj.setPackaging("jar");
        proj.getBuild().setOutputDirectory(classesDir.toString());
        proj.getBuild().setTestOutputDirectory(tmp.resolve("test-classes").toString()); // non-existent
        // Register both roots as test compile source roots (as Maven lifecycle and build-helper do)
        proj.addTestCompileSourceRoot(primaryTestSrcDir.toString());
        proj.addTestCompileSourceRoot(generatedTestSrcDir.toString());
        Dependency dep = new Dependency();
        dep.setGroupId("org.junit.jupiter");
        dep.setArtifactId("junit-jupiter-api");
        dep.setVersion("5.10.0");
        dep.setScope("test");
        proj.getDependencies().add(dep);

        var mojo = new DependenciesMojo(null);
        // Generated test source found → hasTestSources=true → test-classes absent → guard fires
        assertThatThrownBy(() -> mojo.executeForProject(proj))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("target/test-classes not found");
    }

    @Test
    void executeForProject_generatedMainSourcesDetectedViaCompileSourceRoots(@TempDir Path tmp) throws Exception {
        // Symmetrical test for hasMainSources: a project with ONLY generated main sources
        // (registered via addCompileSourceRoot, not getBuild().setSourceDirectory()) must be
        // correctly classified as having main sources.
        Path generatedSrcDir = Files.createDirectories(tmp.resolve("target/generated-sources/annotations"));
        Files.createFile(generatedSrcDir.resolve("Generated.java"));

        MavenProject proj = new MavenProject();
        proj.setPackaging("jar");
        proj.addCompileSourceRoot(generatedSrcDir.toString()); // generated root only
        proj.getBuild().setOutputDirectory(tmp.resolve("classes").toString()); // non-existent
        proj.getBuild().setTestOutputDirectory(tmp.resolve("test-classes").toString());

        var mojo = new DependenciesMojo(null);
        // Generated main source found → hasMainSources=true → classes absent → guard fires
        assertThatThrownBy(() -> mojo.executeForProject(proj))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("target/classes not found");
    }

    // --- knownUsed / knownUnused override ---

    private static MavenProject tempProject(Path tmp) throws Exception {
        MavenProject proj = new MavenProject();
        proj.setFile(Files.createTempFile(tmp, "pom", ".xml").toFile());
        return proj;
    }

    private static DependenciesTui.DepEntry depWithStatus(
            String groupId,
            String artifactId,
            String scope,
            boolean declared,
            DependencyUsageAnalyzer.UsageStatus status) {
        var dep = new DependenciesTui.DepEntry(groupId, artifactId, "", "1.0", scope, declared);
        dep.usageStatus = status;
        return dep;
    }

    @Test
    void knownUsed_promotesUndeterminedToUsed(@TempDir Path tmp) throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");
        MojoTestHelper.setField(mojo, "knownUsed", List.of("com.example:resource-only"));

        var dep = depWithStatus(
                "com.example", "resource-only", "compile", true, DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
        MavenProject proj = tempProject(tmp);

        // After override, dep becomes USED → no issues → no failure
        mojo.executeNonInteractive(proj, List.of(dep), List.of(), Map.of());
        assertThat(dep.usageStatus).isEqualTo(DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void knownUnused_promotesUndeterminedToUnused(@TempDir Path tmp) throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");
        MojoTestHelper.setField(mojo, "knownUnused", List.of("com.example:dead-dep"));

        var dep = depWithStatus(
                "com.example", "dead-dep", "compile", true, DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
        MavenProject proj = tempProject(tmp);

        // After override, dep becomes UNUSED → appears in unusedDeclared → check fails
        assertThatThrownBy(() -> mojo.executeNonInteractive(proj, List.of(dep), List.of(), Map.of()))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("com.example:dead-dep");
    }

    @Test
    void knownUsed_conflictsWithAnalyserUnused_failsWithContradictionError(@TempDir Path tmp) throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");
        MojoTestHelper.setField(mojo, "knownUsed", List.of("com.example:stale-annotation"));

        var dep = depWithStatus(
                "com.example", "stale-annotation", "compile", true, DependencyUsageAnalyzer.UsageStatus.UNUSED);
        MavenProject proj = tempProject(tmp);

        assertThatThrownBy(() -> mojo.executeNonInteractive(proj, List.of(dep), List.of(), Map.of()))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("knownUsed")
                .hasMessageContaining("UNUSED")
                .hasMessageContaining("Stale");
    }

    @Test
    void knownUnused_conflictsWithAnalyserUsed_failsWithContradictionError(@TempDir Path tmp) throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");
        MojoTestHelper.setField(mojo, "knownUnused", List.of("com.example:actually-used"));

        var dep = depWithStatus(
                "com.example", "actually-used", "compile", true, DependencyUsageAnalyzer.UsageStatus.USED);
        MavenProject proj = tempProject(tmp);

        assertThatThrownBy(() -> mojo.executeNonInteractive(proj, List.of(dep), List.of(), Map.of()))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("knownUnused")
                .hasMessageContaining("USED")
                .hasMessageContaining("Stale");
    }

    @Test
    void knownUsed_noEffectOnAlreadyUsedDep(@TempDir Path tmp) throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");
        MojoTestHelper.setField(mojo, "knownUsed", List.of("com.example:already-used"));

        var dep =
                depWithStatus("com.example", "already-used", "compile", true, DependencyUsageAnalyzer.UsageStatus.USED);
        MavenProject proj = tempProject(tmp);

        // No contradiction, no issues → clean
        mojo.executeNonInteractive(proj, List.of(dep), List.of(), Map.of());
        assertThat(dep.usageStatus).isEqualTo(DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void check_transitiveUndetermined_neverFailsEvenWhenFlagSet(@TempDir Path tmp) throws Exception {
        // Behavioral change: transitive UNDETERMINED no longer accumulates into the undetermined bucket,
        // so failOnUndetermined=true must NOT fail the build for transitive deps.
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");
        MojoTestHelper.setField(mojo, "failOnUndetermined", true);

        var transitiveDep = depWithStatus(
                "com.example",
                "transitive-resource",
                "compile",
                false,
                DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
        MavenProject proj = tempProject(tmp);

        // Transitive UNDETERMINED must NOT fail, regardless of failOnUndetermined
        mojo.executeNonInteractive(proj, List.of(), List.of(transitiveDep), Map.of());
    }

    // --- failOnUndetermined ---

    @Test
    void check_doesNotFailOnUndeterminedByDefault(@TempDir Path tmp) throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");
        // failOnUndetermined defaults to false

        var dep = depWithStatus(
                "com.example", "resource-jar", "compile", true, DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
        MavenProject proj = tempProject(tmp);

        // Only undetermined deps, failOnUndetermined=false → should NOT fail
        mojo.executeNonInteractive(proj, List.of(dep), List.of(), Map.of());
    }

    @Test
    void check_failsOnUndetermined_whenFlagSet(@TempDir Path tmp) throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");
        MojoTestHelper.setField(mojo, "failOnUndetermined", true);

        var dep = depWithStatus(
                "com.example", "resource-jar", "compile", true, DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
        MavenProject proj = tempProject(tmp);

        assertThatThrownBy(() -> mojo.executeNonInteractive(proj, List.of(dep), List.of(), Map.of()))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("com.example:resource-jar")
                .hasMessageContaining("Undetermined");
    }

    @Test
    void check_failsOnBothIssuesAndUndetermined(@TempDir Path tmp) throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");
        // failOnUndetermined=false, but there are real issues → still fails for the unused dep
        // undetermined dep is hidden by default (showUndetermined=false)

        var unused =
                depWithStatus("com.example", "unused-lib", "compile", true, DependencyUsageAnalyzer.UsageStatus.UNUSED);
        var undetermined = depWithStatus(
                "com.example", "resource-jar", "compile", true, DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
        MavenProject proj = tempProject(tmp);

        assertThatThrownBy(() -> mojo.executeNonInteractive(proj, List.of(unused, undetermined), List.of(), Map.of()))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("com.example:unused-lib")
                // undetermined is hidden (showUndetermined defaults to false)
                .hasMessageNotContaining("com.example:resource-jar");
    }

    @Test
    void check_showsUndeterminedWhenFlagSet(@TempDir Path tmp) throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");
        MojoTestHelper.setField(mojo, "showUndetermined", true);

        var dep = depWithStatus(
                "com.example", "resource-jar", "compile", true, DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
        MavenProject proj = tempProject(tmp);

        // showUndetermined=true, failOnUndetermined=false → warns but does not fail
        var log = new RecordingLog();
        mojo.setLog(log);
        mojo.executeNonInteractive(proj, List.of(dep), List.of(), Map.of());
        assertThat(log.warnings()).anyMatch(w -> w.contains("com.example:resource-jar"));
    }

    @Test
    void report_hidesUndeterminedByDefault(@TempDir Path tmp) throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "report");
        // showUndetermined defaults to false

        var dep = depWithStatus(
                "com.example", "resource-jar", "compile", true, DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
        MavenProject proj = tempProject(tmp);

        // Only undetermined dep, hidden by default → no issues → clean exit, no warnings
        var log = new RecordingLog();
        mojo.setLog(log);
        mojo.executeNonInteractive(proj, List.of(dep), List.of(), Map.of());
        assertThat(log.warnings()).isEmpty();
    }

    /** Minimal Maven Log implementation that captures warning messages for assertion. */
    private static class RecordingLog implements Log {
        private final List<String> warnings = new ArrayList<>();

        List<String> warnings() {
            return warnings;
        }

        @Override
        public boolean isDebugEnabled() {
            return false;
        }

        @Override
        public void debug(CharSequence content) {}

        @Override
        public void debug(CharSequence content, Throwable error) {}

        @Override
        public void debug(Throwable error) {}

        @Override
        public boolean isInfoEnabled() {
            return false;
        }

        @Override
        public void info(CharSequence content) {}

        @Override
        public void info(CharSequence content, Throwable error) {}

        @Override
        public void info(Throwable error) {}

        @Override
        public boolean isWarnEnabled() {
            return true;
        }

        @Override
        public void warn(CharSequence content) {
            warnings.add(content == null ? "" : content.toString());
        }

        @Override
        public void warn(CharSequence content, Throwable error) {
            warn(content);
        }

        @Override
        public void warn(Throwable error) {
            warnings.add(error == null ? "" : error.getMessage());
        }

        @Override
        public boolean isErrorEnabled() {
            return false;
        }

        @Override
        public void error(CharSequence content) {}

        @Override
        public void error(CharSequence content, Throwable error) {}

        @Override
        public void error(Throwable error) {}
    }
}
