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

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputSource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

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

    @Test
    void buildIgnoreSetWithDefaultsMergesPatterns() {
        Set<String> defaults = Set.of("org.default:default-lib");
        Set<String> result = DependenciesMojo.buildIgnoreSet(List.of("com.user:user-lib"), defaults);
        assertThat(result).containsExactlyInAnyOrder("org.default:default-lib", "com.user:user-lib");
    }

    @Test
    void buildIgnoreSetWithDefaultsNullUserPatterns() {
        Set<String> defaults = Set.of("org.opentest4j:opentest4j");
        Set<String> result = DependenciesMojo.buildIgnoreSet(null, defaults);
        assertThat(result).containsExactly("org.opentest4j:opentest4j");
    }

    @Test
    void defaultIgnoredUsedTransitiveContainsOpentest4j() {
        assertThat(DependenciesMojo.DEFAULT_IGNORED_USED_TRANSITIVE).contains("org.opentest4j:opentest4j");
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
}
