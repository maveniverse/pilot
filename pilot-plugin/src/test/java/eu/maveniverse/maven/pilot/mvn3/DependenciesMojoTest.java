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

import eu.maveniverse.maven.pilot.DependenciesReporter;
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

    // --- isOwnDeclared ---

    @Test
    void isOwnDeclared_ownDepReturnsTrue() throws Exception {
        File pomFile = Files.createTempFile("pom", ".xml").toFile();
        String ownPath = pomFile.toPath().normalize().toString();

        Dependency own = dep("com.example", "own-lib", "1.0", locFor(ownPath));

        assertThat(DependenciesMojo.isOwnDeclared(own, ownPath)).isTrue();
    }

    @Test
    void isOwnDeclared_inheritedDepReturnsFalse() throws Exception {
        File pomFile = Files.createTempFile("pom", ".xml").toFile();
        String ownPath = pomFile.toPath().normalize().toString();

        Dependency inherited = dep("com.other", "parent-lib", "2.0", locFor("/parent/pom.xml"));

        assertThat(DependenciesMojo.isOwnDeclared(inherited, ownPath)).isFalse();
    }

    @Test
    void isOwnDeclared_nullLocationReturnsFalse() throws Exception {
        File pomFile = Files.createTempFile("pom", ".xml").toFile();
        String ownPath = pomFile.toPath().normalize().toString();

        // Dependency with no InputLocation metadata → loc == null → treated as not-own
        Dependency noLoc = new Dependency();
        noLoc.setGroupId("com.unknown");
        noLoc.setArtifactId("mystery-lib");
        noLoc.setVersion("1.0");

        assertThat(DependenciesMojo.isOwnDeclared(noLoc, ownPath)).isFalse();
    }

    @Test
    void isOwnDeclared_nullOwnPomPathReturnsTrueConservatively() throws Exception {
        Dependency dep = dep("com.example", "lib", "1.0", locFor("/some/pom.xml"));

        // When ownPomPath is null (proj.getFile() == null), we can't compare — treat as own
        assertThat(DependenciesMojo.isOwnDeclared(dep, null)).isTrue();
    }

    @Test
    void isOwnDeclared_nonNormalizedOwnPathRecognized() throws Exception {
        File pomFile = Files.createTempFile("pom", ".xml").toFile();
        String ownPath = pomFile.toPath().normalize().toString();
        // Non-normalized equivalent of the same path
        String nonNormalized = pomFile.getParent() + "/." + "/" + pomFile.getName();

        Dependency own = dep("com.example", "own-lib", "1.0", locFor(nonNormalized));

        // After normalization, paths are equal → must be recognized as own
        assertThat(DependenciesMojo.isOwnDeclared(own, ownPath)).isTrue();
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

    @Test
    void executeNonInteractive_inheritedDep_notReportedAsUnused(@TempDir Path tmp) throws Exception {
        // Regression for isOwn classification: a dep whose InputLocation source points to a parent POM
        // is classified as inherited (ownDeclared=false) in executeForProject. This test verifies
        // that executeNonInteractive — the downstream consumer of that classification — correctly
        // excludes inherited deps from the unusedDeclared and undetermined buckets even when the
        // analyser classifies them as UNUSED or UNDETERMINED.
        //
        // Testing path: executeForProject sets entry.ownDeclared via:
        //   isOwn = ownPomPath.equals(depSrc)   where depSrc comes from InputLocation.getSource()
        // A dep from /parent/pom.xml → isOwn=false → ownDeclared=false → excluded from reporting.
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");
        MojoTestHelper.setField(mojo, "failOnUndetermined", true);

        // Simulate an inherited dep: ownDeclared=false (as set by executeForProject when
        // InputLocation.getSource().getLocation() != ownPomPath)
        var inheritedUnused = new DependenciesTui.DepEntry("com.parent", "parent-lib", "", "1.0", "compile", true);
        inheritedUnused.ownDeclared = false;
        inheritedUnused.usageStatus = DependencyUsageAnalyzer.UsageStatus.UNUSED;

        var inheritedUndetermined =
                new DependenciesTui.DepEntry("com.parent", "resource-lib", "", "1.0", "compile", true);
        inheritedUndetermined.ownDeclared = false;
        inheritedUndetermined.usageStatus = DependencyUsageAnalyzer.UsageStatus.UNDETERMINED;

        MavenProject proj = new MavenProject();
        proj.setFile(Files.createTempFile(tmp, "pom", ".xml").toFile());

        // Neither inherited dep should appear in unusedDeclared or undetermined buckets.
        // With failOnUndetermined=true, if inheritedUndetermined were leaked into the undetermined
        // bucket, executeNonInteractive would throw MojoFailureException — proving the guard works
        // when the call completes without throwing.
        mojo.executeNonInteractive(proj, List.of(inheritedUnused, inheritedUndetermined), List.of(), Map.of());
    }

    @Test
    void executeNonInteractive_ownDep_reportedAsUnused(@TempDir Path tmp) throws Exception {
        // Complementary to the above: an own dep (ownDeclared=true, the default) that is UNUSED
        // MUST appear in unusedDeclared and cause a check failure. Verifies the guard doesn't
        // over-filter.
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");

        var ownUnused = new DependenciesTui.DepEntry("com.example", "own-lib", "", "1.0", "compile", true);
        ownUnused.ownDeclared = true;
        ownUnused.usageStatus = DependencyUsageAnalyzer.UsageStatus.UNUSED;

        MavenProject proj = new MavenProject();
        proj.setFile(Files.createTempFile(tmp, "pom", ".xml").toFile());

        assertThatThrownBy(() -> mojo.executeNonInteractive(proj, List.of(ownUnused), List.of(), Map.of()))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("com.example:own-lib");
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
    void hasTestSources_detectsGroovyTestDirByConvention(@TempDir Path tmp) throws Exception {
        // When pilot:dependencies is invoked directly (not via full lifecycle), GMavenPlus's
        // addTestSources (INITIALIZE phase) may not have run, so src/test/groovy is NOT in
        // getTestCompileSourceRoots(). hasTestSources() must fall back to probing well-known
        // JVM test source directories by convention (groovy, kotlin, scala).
        Path classesDir = Files.createDirectory(tmp.resolve("classes"));
        // Only src/test/java registered (Maven default), but it's empty (package dirs only)
        Path javaTestSrcDir = Files.createDirectories(tmp.resolve("src/test/java/com/example"));
        // Groovy test sources exist but are NOT registered in getTestCompileSourceRoots()
        Path groovyTestSrcDir = Files.createDirectories(tmp.resolve("src/test/groovy/com/example"));
        Files.createFile(groovyTestSrcDir.resolve("SomeSpec.groovy"));

        MavenProject proj = new MavenProject();
        proj.setPackaging("jar");
        proj.setFile(tmp.resolve("pom.xml").toFile()); // basedir = tmp; file need not exist for getBasedir()
        proj.getBuild().setOutputDirectory(classesDir.toString());
        proj.getBuild().setTestOutputDirectory(tmp.resolve("test-classes").toString()); // non-existent
        proj.addTestCompileSourceRoot(javaTestSrcDir.getParent().getParent().toString()); // src/test/java only
        Dependency dep = new Dependency();
        dep.setGroupId("org.spockframework");
        dep.setArtifactId("spock-core");
        dep.setVersion("2.3");
        dep.setScope("test");
        proj.getDependencies().add(dep);

        var mojo = new DependenciesMojo(null);
        // Groovy test source found via convention → hasTestSources=true → test-classes absent → guard fires
        assertThatThrownBy(() -> mojo.executeForProject(proj))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("target/test-classes not found");
    }

    @Test
    void hasMainSources_detectsGroovyMainDirByConvention(@TempDir Path tmp) throws Exception {
        // Symmetric test for hasMainSources: when pilot:dependencies is invoked directly,
        // GMavenPlus's addSources (GENERATE_SOURCES phase) may not have run, so src/main/groovy
        // is NOT in getCompileSourceRoots(). hasMainSources() must fall back to probing
        // well-known JVM main source directories by convention.
        // Only src/main/java registered (Maven default), but it's empty (package dirs only)
        Path javaMainSrcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        // Groovy main sources exist but are NOT registered in getCompileSourceRoots()
        Path groovyMainSrcDir = Files.createDirectories(tmp.resolve("src/main/groovy/com/example"));
        Files.createFile(groovyMainSrcDir.resolve("SomeClass.groovy"));

        MavenProject proj = new MavenProject();
        proj.setPackaging("jar");
        proj.setFile(tmp.resolve("pom.xml").toFile()); // basedir = tmp
        proj.getBuild().setOutputDirectory(tmp.resolve("classes").toString()); // non-existent
        proj.getBuild().setTestOutputDirectory(tmp.resolve("test-classes").toString());
        proj.addCompileSourceRoot(javaMainSrcDir.getParent().getParent().toString()); // src/main/java only

        var mojo = new DependenciesMojo(null);
        // Groovy main source found via convention → hasMainSources=true → classes absent → guard fires
        assertThatThrownBy(() -> mojo.executeForProject(proj))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("target/classes not found");
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

    // --- isOwn classification in executeForProject ---

    @Test
    void executeForProject_inheritedDepFromParentClassifiedAsNotOwn(@TempDir Path tmp) throws Exception {
        // Verify that the isOwn InputLocation path-comparison in executeForProject classifies
        // a dependency whose InputLocation.source.location points to a parent POM as
        // ownDeclared=false. This covers the proj.getDependencies() loop (different code path
        // from buildAncestorManagedGAs which handles DM entries).
        //
        // Strategy: a MavenProject whose pom file is tmp/pom.xml contains one dependency
        // whose InputLocation source is "/parent/pom.xml" (a different file). The classes
        // dir exists so the hasMainSources guard doesn't fire. executeForProject reaches the
        // dependency classification loop, marks the dep as ownDeclared=false, then NPEs on
        // repoSystem (null) — NOT a MojoExecutionException guard-failure. This confirms the
        // isOwn logic ran cleanly without treating the dep as own-declared.
        File pomFile = Files.createTempFile(tmp, "pom", ".xml").toFile();
        // Classes dir must exist so the hasMainSources guard doesn't fire first
        Path classesDir = Files.createDirectory(tmp.resolve("classes"));

        // An inherited dep: InputLocation points to /parent/pom.xml, not the module's own pom
        Dependency inheritedDep = new Dependency();
        inheritedDep.setGroupId("org.parent");
        inheritedDep.setArtifactId("parent-lib");
        inheritedDep.setVersion("1.0");
        inheritedDep.setScope("compile");
        inheritedDep.setLocation("", locFor("/parent/pom.xml"));

        MavenProject proj = new MavenProject();
        proj.setFile(pomFile); // ownPomPath = pomFile path — does NOT equal /parent/pom.xml → isOwn=false
        proj.setPackaging("jar");
        proj.getBuild().setOutputDirectory(classesDir.toString()); // exists — guard passes
        proj.getBuild().setTestOutputDirectory(tmp.resolve("test-classes").toString());
        proj.getDependencies().add(inheritedDep);

        var mojo = new DependenciesMojo(null);
        // The guard passes; the dep-classification loop runs (isOwn=false for the parent dep)
        // and then NPEs on repoSystem (null) — confirming the isOwn classification ran cleanly.
        // A MojoExecutionException would mean the guard fired, which would indicate a bug.
        assertThatThrownBy(() -> mojo.executeForProject(proj))
                .isNotInstanceOf(MojoExecutionException.class); // guard did NOT fire
    }

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

    @Test
    void check_usedInTest_declaredCompile_escalatesToFailure(@TempDir Path tmp) throws Exception {
        // A compile-scope dep used only in tests (USED_IN_TEST) should trigger a check failure
        // asking the user to narrow its scope — not silently pass.
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");

        var dep = depWithStatus(
                "com.example",
                "compile-but-test-only",
                "compile",
                true,
                DependencyUsageAnalyzer.UsageStatus.USED_IN_TEST);
        MavenProject proj = tempProject(tmp);

        assertThatThrownBy(() -> mojo.executeNonInteractive(proj, List.of(dep), List.of(), Map.of()))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("com.example:compile-but-test-only")
                .hasMessageContaining("narrowed to test");
    }

    @Test
    void report_usedInTest_declaredCompile_producesWarning(@TempDir Path tmp) throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "report");

        var dep = depWithStatus(
                "com.example",
                "compile-but-test-only",
                "compile",
                true,
                DependencyUsageAnalyzer.UsageStatus.USED_IN_TEST);
        MavenProject proj = tempProject(tmp);

        var log = new RecordingLog();
        mojo.setLog(log);
        mojo.executeNonInteractive(proj, List.of(dep), List.of(), Map.of());
        assertThat(log.warnings())
                .anyMatch(w -> w.contains("narrowed to test") && w.contains("com.example:compile-but-test-only"));
    }

    @Test
    void check_usedInTest_inheritedCompile_notReported(@TempDir Path tmp) throws Exception {
        // USED_IN_TEST on an inherited dep (ownDeclared=false) must not trigger a check failure.
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");

        var dep = depWithStatus(
                "com.parent",
                "compile-but-test-only",
                "compile",
                true,
                DependencyUsageAnalyzer.UsageStatus.USED_IN_TEST);
        dep.ownDeclared = false;
        MavenProject proj = tempProject(tmp);

        // Should not throw — inherited deps are excluded
        mojo.executeNonInteractive(proj, List.of(dep), List.of(), Map.of());
    }

    @Test
    void check_usedInTest_transitive_escalatesToFailure(@TempDir Path tmp) throws Exception {
        // A transitive dep classified as USED_IN_TEST should be promoted at test scope → appears
        // in usedTransitive list → triggers check failure (used transitive = undeclared direct dep).
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");

        var dep = depWithStatus(
                "com.example",
                "test-only-transitive",
                "compile",
                false,
                DependencyUsageAnalyzer.UsageStatus.USED_IN_TEST);
        MavenProject proj = tempProject(tmp);

        assertThatThrownBy(() -> mojo.executeNonInteractive(proj, List.of(), List.of(dep), Map.of()))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("com.example:test-only-transitive");
    }

    // --- executeFixWithIterations / CountingFixLogger ---

    /**
     * Subclass that lets tests control what each pass "does" without needing a real Maven reactor.
     * On the first {@code passesWithChanges} calls to {@code executeForProject(proj, fixLogger)}
     * it simulates one transitive-add; subsequent calls do nothing (convergence).
     */
    private static class FakeFixMojo extends DependenciesMojo {
        private final int passesWithChanges;
        private int callCount = 0;
        final List<String> loggedMessages = new ArrayList<>();

        FakeFixMojo(int passesWithChanges) {
            super(null);
            this.passesWithChanges = passesWithChanges;
        }

        @Override
        void executeForProject(MavenProject proj, DependenciesReporter.FixLogger fixLogger) throws Exception {
            callCount++;
            if (callCount <= passesWithChanges && fixLogger != null) {
                fixLogger.log("Added used transitive dependency: com.example:level" + callCount);
            }
        }

        @Override
        public Log getLog() {
            return new Log() {
                public boolean isDebugEnabled() {
                    return false;
                }

                public void debug(CharSequence c) { // no-op
                }

                public void debug(CharSequence c, Throwable t) { // no-op
                }

                public void debug(Throwable t) { // no-op
                }

                public boolean isInfoEnabled() {
                    return true;
                }

                public void info(CharSequence c) {
                    loggedMessages.add(c.toString());
                }

                public void info(CharSequence c, Throwable t) {
                    loggedMessages.add(c.toString());
                }

                public void info(Throwable t) { // no-op
                }

                public boolean isWarnEnabled() {
                    return true;
                }

                public void warn(CharSequence c) {
                    loggedMessages.add("[WARN] " + c);
                }

                public void warn(CharSequence c, Throwable t) {
                    loggedMessages.add("[WARN] " + c);
                }

                public void warn(Throwable t) { // no-op
                }

                public boolean isErrorEnabled() {
                    return false;
                }

                public void error(CharSequence c) { // no-op
                }

                public void error(CharSequence c, Throwable t) { // no-op
                }

                public void error(Throwable t) { // no-op
                }
            };
        }

        int callCount() {
            return callCount;
        }
    }

    @Test
    void executeFixWithIterations_convergesIn2Passes(@TempDir Path tmp) throws Exception {
        // Simulate: pass 1 adds a 2nd-level transitive dep; pass 2 is clean.
        // This is the real-world scenario: adding dep A exposes dep B (A's transitive),
        // which is added in pass 2; pass 3 finds nothing.
        FakeFixMojo mojo = new FakeFixMojo(1); // only pass 1 produces changes
        mojo.maxIterations = 5;

        MavenProject proj = new MavenProject();
        proj.setFile(Files.createTempFile(tmp, "pom", ".xml").toFile());

        mojo.executeFixWithIterations(proj);

        // 2 passes: one with changes, one to confirm convergence
        assertThat(mojo.callCount()).isEqualTo(2);
        assertThat(mojo.loggedMessages)
                // Pass 1 summary logged
                .anyMatch(m -> m.contains("Pass 1/5") && m.contains("1 added"))
                // Convergence logged on pass 2
                .anyMatch(m -> m.contains("Pass 2/5") && m.contains("converged"));
    }

    @Test
    void executeFixWithIterations_stopsAtMaxIterations(@TempDir Path tmp) throws Exception {
        // Pathological case: every pass still finds changes.
        // Loop must stop at maxIterations and warn.
        FakeFixMojo mojo = new FakeFixMojo(Integer.MAX_VALUE); // always produces changes
        mojo.maxIterations = 3;

        MavenProject proj = new MavenProject();
        proj.setFile(Files.createTempFile(tmp, "pom", ".xml").toFile());

        mojo.executeFixWithIterations(proj);

        assertThat(mojo.callCount()).isEqualTo(3);
        assertThat(mojo.loggedMessages)
                .anyMatch(m -> m.contains("[WARN]") && m.contains("max-iterations"))
                .anyMatch(m -> m.contains("Pass 1/3") && m.contains("1 added"))
                .anyMatch(m -> m.contains("Pass 2/3") && m.contains("1 added"))
                .anyMatch(m -> m.contains("Pass 3/3") && m.contains("1 added"));
    }

    @Test
    void executeFixWithIterations_singlePassClean(@TempDir Path tmp) throws Exception {
        // First pass finds nothing → "No dependency issues found." and stop.
        FakeFixMojo mojo = new FakeFixMojo(0);
        mojo.maxIterations = 5;

        MavenProject proj = new MavenProject();
        proj.setFile(Files.createTempFile(tmp, "pom", ".xml").toFile());

        mojo.executeFixWithIterations(proj);

        assertThat(mojo.callCount()).isEqualTo(1);
        assertThat(mojo.loggedMessages).anyMatch(m -> m.contains("No dependency issues found"));
    }

    @Test
    void countingFixLogger_countsCorrectly() {
        List<String> logged = new ArrayList<>();
        DependenciesMojo.CountingFixLogger logger = new DependenciesMojo.CountingFixLogger(logged::add);

        logger.log("Added used transitive dependency: com.example:foo");
        logger.log("Added used transitive dependency (version managed by ancestor): com.example:bar");
        logger.log("Removed unused dependency: com.old:artifact");
        logger.log("Narrowed to test scope (used only in tests): com.test:lib");
        logger.log("Updated /some/path/pom.xml");

        assertThat(logger.added).isEqualTo(2);
        assertThat(logger.removed).isEqualTo(1);
        assertThat(logger.narrowed).isEqualTo(1);
        assertThat(logged).hasSize(5);
    }

    @Test
    void execute_rejectsZeroMaxIterations() throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "fix");
        MojoTestHelper.setField(mojo, "maxIterations", 0);

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("pilot.maxIterations must be >= 1");
    }

    @Test
    void execute_fixAction_routesToExecuteFixWithIterations(@TempDir Path tmp) throws Exception {
        // Verify that execute() with action=fix delegates to executeFixWithIterations()
        // (not executeForProject directly). Uses FakeFixMojo to avoid needing a real reactor.
        FakeFixMojo mojo = new FakeFixMojo(0); // 0 changes → converges in 1 pass
        mojo.maxIterations = 5;
        MojoTestHelper.setField(mojo, "action", "fix");

        MavenProject proj = new MavenProject();
        proj.setPackaging("jar");
        proj.setFile(Files.createTempFile(tmp, "pom", ".xml").toFile());
        MojoTestHelper.setField(mojo, "project", proj);

        mojo.execute();

        // executeForProject was called once (converged on first clean pass)
        assertThat(mojo.callCount()).isEqualTo(1);
        assertThat(mojo.loggedMessages).anyMatch(m -> m.contains("No dependency issues found"));
    }

    @Test
    void execute_reportAction_callsExecuteForProjectDirectly(@TempDir Path tmp) throws Exception {
        // Verify that execute() with action=report delegates to executeForProject (not the fix loop).
        // Use a FakeFixMojo to capture calls; execute with pom packaging so it returns early.
        FakeFixMojo mojo = new FakeFixMojo(0);
        MojoTestHelper.setField(mojo, "action", "report");

        MavenProject proj = new MavenProject();
        proj.setPackaging("pom"); // early-exit in executeForProject
        MojoTestHelper.setField(mojo, "project", proj);

        mojo.execute();

        // pom-packaging triggers early-exit in executeForProject before any work
        assertThat(mojo.callCount()).isEqualTo(1);
    }

    @Test
    void executeForProject_withFixLogger_pomPackagingSkipped(@TempDir Path tmp) throws Exception {
        // executeForProject(proj, fixLogger) with pom packaging must exit early without calling
        // the fixLogger at all.
        var mojo = new DependenciesMojo(null);
        MavenProject proj = new MavenProject();
        proj.setPackaging("pom");
        proj.setFile(Files.createTempFile(tmp, "pom", ".xml").toFile());

        List<String> logged = new ArrayList<>();
        DependenciesReporter.FixLogger fixLogger = logged::add;

        mojo.executeForProject(proj, fixLogger);

        assertThat(logged).isEmpty(); // no fix log emitted for pom-packaging skip
    }

    @Test
    void execute_maxIterationsValidation_ignoredForReportAndCheck() throws Exception {
        // maxIterations < 1 must NOT trigger the maxIterations-specific error for report/check
        // — the param has no effect for those actions.
        for (String action : List.of("report", "check")) {
            var mojo = new DependenciesMojo(null);
            MojoTestHelper.setField(mojo, "action", action);
            MojoTestHelper.setField(mojo, "maxIterations", 0);
            // Will throw (null project → NPE wrapped in MojoExecutionException), but NOT for
            // the maxIterations guard — that guard only fires for action=fix.
            assertThatThrownBy(mojo::execute).hasMessageNotContaining("pilot.maxIterations");
        }
    }

    @Test
    void executeForProject_singleArg_delegatesToTwoArgOverload(@TempDir Path tmp) throws Exception {
        // The no-fixLogger overload must behave identically to the 2-arg variant with null logger.
        // We exercise it by reaching the resolver — it will throw because repoSystem is null,
        // which is acceptable here: we only care that the 1-arg → 2-arg delegation occurred.
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "report");

        MavenProject proj = new MavenProject();
        proj.setFile(Files.createTempFile(tmp, "pom", ".xml").toFile());
        // Give it a fake classes dir so the early-exit guard passes
        Path classesDir = tmp.resolve("classes");
        Files.createDirectories(classesDir);
        proj.getBuild().setOutputDirectory(classesDir.toString());
        proj.getBuild().setTestOutputDirectory(tmp.resolve("test-classes").toString());

        // Will fail at dep resolution (null repoSystem), but not at the 1-arg delegation
        assertThatThrownBy(() -> mojo.executeForProject(proj)).isNotInstanceOf(MojoExecutionException.class);
    }

    @Test
    void executeNonInteractive_twoArgOverload_delegatesToFiveArgVariant(@TempDir Path tmp) throws Exception {
        // The 2-arg overload must delegate without ancestor-managed or fixLogger.
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");

        var dep = depWithStatus("com.example", "lib", "compile", true, DependencyUsageAnalyzer.UsageStatus.UNUSED);
        MavenProject proj = tempProject(tmp);

        // 2-arg: (proj, declared, transitive, gaToVersion) — unused dep → MojoFailureException
        assertThatThrownBy(() -> mojo.executeNonInteractive(proj, List.of(dep), List.of(), Map.of()))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("com.example:lib");
    }

    @Test
    void executeNonInteractive_threeArgOverload_delegatesToFiveArgVariant(@TempDir Path tmp) throws Exception {
        // The 3-arg overload (with ancestorManagedGAs, without fixLogger) must delegate correctly.
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");

        var dep = depWithStatus("com.example", "lib", "compile", true, DependencyUsageAnalyzer.UsageStatus.UNUSED);
        MavenProject proj = tempProject(tmp);

        assertThatThrownBy(() -> mojo.executeNonInteractive(proj, List.of(dep), List.of(), Map.of(), Set.of()))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("com.example:lib");
    }

    @Test
    void executeNonInteractive_report_showsUndeterminedAsWarnWhenShowFlagSet(@TempDir Path tmp) throws Exception {
        // report action: UNDETERMINED deps are shown as a warning when showUndetermined=true,
        // but no exception is thrown.
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "report");
        MojoTestHelper.setField(mojo, "showUndetermined", true);

        var dep = depWithStatus(
                "com.example", "resource-jar", "compile", true, DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
        MavenProject proj = tempProject(tmp);

        // report action should not throw for undetermined — it warns
        mojo.executeNonInteractive(proj, List.of(dep), List.of(), Map.of());
    }

    @Test
    void executeNonInteractive_check_warnUndetermined_noFailWhenFlagFalse(@TempDir Path tmp) throws Exception {
        // showUndetermined=true but failOnUndetermined=false → warn, no exception
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");
        MojoTestHelper.setField(mojo, "showUndetermined", true);

        var dep = depWithStatus(
                "com.example", "resource-jar", "compile", true, DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
        MavenProject proj = tempProject(tmp);

        // Should produce a warning but not throw
        mojo.executeNonInteractive(proj, List.of(dep), List.of(), Map.of());
    }

    @Test
    void buildTestScan_skipTestScope_removesTestDepsAndReturnsEmpty(@TempDir Path tmp) throws Exception {
        // When skipTestScope=true, buildTestScan strips test-scoped entries from both lists
        // and returns an empty ScanResult.
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "skipTestScope", true);

        var testDep = new DependenciesTui.DepEntry("org.junit", "junit", "", "5.0", "test", true);
        var compileDep = new DependenciesTui.DepEntry("com.example", "lib", "", "1.0", "compile", true);
        List<DependenciesTui.DepEntry> declared = new ArrayList<>(List.of(testDep, compileDep));
        List<DependenciesTui.DepEntry> transitive = new ArrayList<>(
                List.of(new DependenciesTui.DepEntry("com.example", "trans", "", "1.0", "test", false)));

        // Invoke private method via reflection
        java.lang.reflect.Method m = DependenciesMojo.class.getDeclaredMethod(
                "buildTestScan", boolean.class, java.nio.file.Path.class, List.class, List.class);
        m.setAccessible(true);
        var result = (eu.maveniverse.maven.pilot.ClassFileScanner.ScanResult)
                m.invoke(mojo, true, tmp.resolve("test-classes"), declared, transitive);

        // Test-scoped entries removed from both lists
        assertThat(declared).hasSize(1).allMatch(d -> "compile".equals(d.scope));
        assertThat(transitive).isEmpty();
        // Empty scan result
        assertThat(result.referencedClasses()).isEmpty();
    }

    @Test
    void buildTestScan_noTestSources_returnsEmpty(@TempDir Path tmp) throws Exception {
        // hasTestSources=false → empty result, no directory scan
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "skipTestScope", false);

        java.lang.reflect.Method m = DependenciesMojo.class.getDeclaredMethod(
                "buildTestScan", boolean.class, java.nio.file.Path.class, List.class, List.class);
        m.setAccessible(true);
        var result = (eu.maveniverse.maven.pilot.ClassFileScanner.ScanResult)
                m.invoke(mojo, false, tmp.resolve("test-classes"), new ArrayList<>(), new ArrayList<>());

        assertThat(result.referencedClasses()).isEmpty();
    }

    @Test
    void buildTestScan_testSourcesButNoDirYet_returnsEmpty(@TempDir Path tmp) throws Exception {
        // hasTestSources=true but dir does not exist yet (not compiled) → empty result
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "skipTestScope", false);

        java.lang.reflect.Method m = DependenciesMojo.class.getDeclaredMethod(
                "buildTestScan", boolean.class, java.nio.file.Path.class, List.class, List.class);
        m.setAccessible(true);
        Path nonExistentDir = tmp.resolve("test-classes-nonexistent");
        var result = (eu.maveniverse.maven.pilot.ClassFileScanner.ScanResult)
                m.invoke(mojo, true, nonExistentDir, new ArrayList<>(), new ArrayList<>());

        assertThat(result.referencedClasses()).isEmpty();
    }

    @Test
    void suppressPomAggregatorCoveredTransitives_emptyPomAggregators_noOp(@TempDir Path tmp) throws Exception {
        // When pomAggregatorGAs is empty, the method returns immediately — no modification
        var mojo = new DependenciesMojo(null);
        var dep = new DependenciesTui.DepEntry("com.example", "lib", "", "1.0", "compile", false);
        List<DependenciesTui.DepEntry> transitive = new ArrayList<>(List.of(dep));
        // Create a minimal DependencyTreeModel with a root node
        var root = new eu.maveniverse.maven.pilot.DependencyTreeModel.TreeNode(
                "com.example", "parent", "1.0", "compile", false, 0);
        var depTree = new eu.maveniverse.maven.pilot.DependencyTreeModel(root, List.of(), 1);

        java.lang.reflect.Method m = DependenciesMojo.class.getDeclaredMethod(
                "suppressPomAggregatorCoveredTransitives",
                List.class,
                eu.maveniverse.maven.pilot.DependencyTreeModel.class,
                Set.class);
        m.setAccessible(true);
        m.invoke(mojo, transitive, depTree, Set.of());

        // No entries removed — pomAggregatorGAs was empty
        assertThat(transitive).hasSize(1);
    }

    @Test
    void suppressPomAggregatorCoveredTransitives_noCoveredGAs_noOp(@TempDir Path tmp) throws Exception {
        // pomAggregatorGAs non-empty but no transitive dep is covered — nothing removed
        var mojo = new DependenciesMojo(null);
        var dep = new DependenciesTui.DepEntry("com.example", "lib", "", "1.0", "compile", false);
        List<DependenciesTui.DepEntry> transitive = new ArrayList<>(List.of(dep));
        // Root only: no pom-type child, so collectPomAggregatorCoveredGAs returns empty
        var root = new eu.maveniverse.maven.pilot.DependencyTreeModel.TreeNode(
                "org.example", "bom", "1.0", "compile", false, 0);
        var depTree = new eu.maveniverse.maven.pilot.DependencyTreeModel(root, List.of(), 1);

        java.lang.reflect.Method m = DependenciesMojo.class.getDeclaredMethod(
                "suppressPomAggregatorCoveredTransitives",
                List.class,
                eu.maveniverse.maven.pilot.DependencyTreeModel.class,
                Set.class);
        m.setAccessible(true);
        m.invoke(mojo, transitive, depTree, Set.of("org.example:bom"));

        // Nothing covered → nothing removed
        assertThat(transitive).hasSize(1);
    }

    // --- buildArtifactMaps ---

    @Test
    void buildArtifactMaps_empty_returnsBothMapsEmpty() {
        var depReq = new org.eclipse.aether.resolution.DependencyRequest();
        var depResult = new org.eclipse.aether.resolution.DependencyResult(depReq);
        depResult.setArtifactResults(List.of());

        DependenciesMojo.ArtifactMaps maps = DependenciesMojo.buildArtifactMaps(depResult);

        assertThat(maps.gaToJar()).isEmpty();
        assertThat(maps.gaToVersion()).isEmpty();
    }

    @Test
    void buildArtifactMaps_artifactWithNullArtifact_skipped() {
        var depReq = new org.eclipse.aether.resolution.DependencyRequest();
        var depResult = new org.eclipse.aether.resolution.DependencyResult(depReq);

        var ar = new org.eclipse.aether.resolution.ArtifactResult(new org.eclipse.aether.resolution.ArtifactRequest());
        // ar.getArtifact() is null by default
        depResult.setArtifactResults(List.of(ar));

        DependenciesMojo.ArtifactMaps maps = DependenciesMojo.buildArtifactMaps(depResult);

        assertThat(maps.gaToJar()).isEmpty();
        assertThat(maps.gaToVersion()).isEmpty();
    }

    @Test
    void buildArtifactMaps_jarArtifact_addedToBothMaps(@TempDir Path tmp) throws Exception {
        var depReq = new org.eclipse.aether.resolution.DependencyRequest();
        var depResult = new org.eclipse.aether.resolution.DependencyResult(depReq);

        File jarFile = Files.createFile(tmp.resolve("foo.jar")).toFile();
        var artifact = new org.eclipse.aether.artifact.DefaultArtifact("com.example:foo:1.0");
        artifact = (org.eclipse.aether.artifact.DefaultArtifact) artifact.setFile(jarFile);

        var ar = new org.eclipse.aether.resolution.ArtifactResult(new org.eclipse.aether.resolution.ArtifactRequest());
        ar.setArtifact(artifact);
        depResult.setArtifactResults(List.of(ar));

        DependenciesMojo.ArtifactMaps maps = DependenciesMojo.buildArtifactMaps(depResult);

        assertThat(maps.gaToVersion()).containsEntry("com.example:foo", "1.0");
        assertThat(maps.gaToJar()).containsEntry("com.example:foo", jarFile);
    }

    @Test
    void buildArtifactMaps_nonJarArtifact_versionMappedButNotJar(@TempDir Path tmp) throws Exception {
        var depReq = new org.eclipse.aether.resolution.DependencyRequest();
        var depResult = new org.eclipse.aether.resolution.DependencyResult(depReq);

        File pomFile = Files.createFile(tmp.resolve("foo.pom")).toFile();
        var artifact = new org.eclipse.aether.artifact.DefaultArtifact("com.example:foo:pom:1.0");
        artifact = (org.eclipse.aether.artifact.DefaultArtifact) artifact.setFile(pomFile);

        var ar = new org.eclipse.aether.resolution.ArtifactResult(new org.eclipse.aether.resolution.ArtifactRequest());
        ar.setArtifact(artifact);
        depResult.setArtifactResults(List.of(ar));

        DependenciesMojo.ArtifactMaps maps = DependenciesMojo.buildArtifactMaps(depResult);

        assertThat(maps.gaToVersion()).containsEntry("com.example:foo", "1.0");
        // pom file → not added to gaToJar
        assertThat(maps.gaToJar()).doesNotContainKey("com.example:foo");
    }

    @Test
    void buildArtifactMaps_classifiedArtifact_keyedWithClassifier(@TempDir Path tmp) throws Exception {
        var depReq = new org.eclipse.aether.resolution.DependencyRequest();
        var depResult = new org.eclipse.aether.resolution.DependencyResult(depReq);

        File jarFile = Files.createFile(tmp.resolve("foo-tests.jar")).toFile();
        var artifact = new org.eclipse.aether.artifact.DefaultArtifact("com.example:foo:jar:tests:1.0");
        artifact = (org.eclipse.aether.artifact.DefaultArtifact) artifact.setFile(jarFile);

        var ar = new org.eclipse.aether.resolution.ArtifactResult(new org.eclipse.aether.resolution.ArtifactRequest());
        ar.setArtifact(artifact);
        depResult.setArtifactResults(List.of(ar));

        DependenciesMojo.ArtifactMaps maps = DependenciesMojo.buildArtifactMaps(depResult);

        // Classified artifact → key includes classifier
        assertThat(maps.gaToVersion()).containsEntry("com.example:foo:tests", "1.0");
        assertThat(maps.gaToJar()).containsEntry("com.example:foo:tests", jarFile);
    }

    @Test
    void buildArtifactMaps_nullFile_notAddedToJarMap() {
        var depReq = new org.eclipse.aether.resolution.DependencyRequest();
        var depResult = new org.eclipse.aether.resolution.DependencyResult(depReq);

        // Artifact with no local file (e.g. resolution failed or not downloaded)
        var artifact = new org.eclipse.aether.artifact.DefaultArtifact("com.example:foo:1.0");
        // No setFile call → artifact.getFile() returns null

        var ar = new org.eclipse.aether.resolution.ArtifactResult(new org.eclipse.aether.resolution.ArtifactRequest());
        ar.setArtifact(artifact);
        depResult.setArtifactResults(List.of(ar));

        DependenciesMojo.ArtifactMaps maps = DependenciesMojo.buildArtifactMaps(depResult);

        assertThat(maps.gaToVersion()).containsEntry("com.example:foo", "1.0");
        // No file → not in gaToJar
        assertThat(maps.gaToJar()).doesNotContainKey("com.example:foo");
    }

    // --- checkBuildOutputDirs ---

    @Test
    void checkBuildOutputDirs_neitherDirExists_noMainSources_passes(@TempDir Path tmp) throws Exception {
        // Project with no main sources and no test sources: neither directory check fires
        var mojo = new DependenciesMojo(null);

        MavenProject proj = new MavenProject();
        proj.setPackaging("jar");
        proj.getBuild().setOutputDirectory(tmp.resolve("classes").toString()); // non-existent
        proj.getBuild().setTestOutputDirectory(tmp.resolve("test-classes").toString()); // non-existent
        // No source roots → hasMainSources=false, hasTestSources=false

        // Should not throw
        java.lang.reflect.Method m = DependenciesMojo.class.getDeclaredMethod(
                "checkBuildOutputDirs", MavenProject.class, Path.class, Path.class);
        m.setAccessible(true);
        m.invoke(mojo, proj, tmp.resolve("classes"), tmp.resolve("test-classes"));
    }

    @Test
    void checkBuildOutputDirs_mainSourcesPresentClassesMissing_throws(@TempDir Path tmp) throws Exception {
        // Project with main sources but no compiled classes → must throw
        var mojo = new DependenciesMojo(null);
        Path mainSrcDir = Files.createDirectories(tmp.resolve("src/main/java"));
        Files.createFile(mainSrcDir.resolve("Foo.java"));

        MavenProject proj = new MavenProject();
        proj.setPackaging("jar");
        proj.addCompileSourceRoot(mainSrcDir.toString());
        proj.getBuild().setOutputDirectory(tmp.resolve("classes").toString()); // non-existent
        proj.getBuild().setTestOutputDirectory(tmp.resolve("test-classes").toString());

        java.lang.reflect.Method m = DependenciesMojo.class.getDeclaredMethod(
                "checkBuildOutputDirs", MavenProject.class, Path.class, Path.class);
        m.setAccessible(true);
        assertThatThrownBy(() -> {
                    try {
                        m.invoke(mojo, proj, tmp.resolve("classes"), tmp.resolve("test-classes"));
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                })
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("target/classes not found");
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
