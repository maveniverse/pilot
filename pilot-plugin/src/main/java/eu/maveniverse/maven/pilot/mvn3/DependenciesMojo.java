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

import eu.maveniverse.maven.pilot.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.InputLocation;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResult;

/**
 * Dependency analysis: CI-friendly report/check/fix modes.
 *
 * <p>Three actions via {@code -Dpilot.action}:</p>
 * <ul>
 *   <li><b>report</b> (default) — prints unused declared and used transitive dependencies
 *       without failing; undetermined deps are hidden unless {@code -Dpilot.showUndetermined=true}</li>
 *   <li><b>check</b> — reports issues and fails the build if any are found</li>
 *   <li><b>fix</b> — removes unused declared and adds used transitive dependencies to the POM</li>
 * </ul>
 *
 * <p>Runs once per module in a multi-module reactor. For an interactive TUI,
 * use {@code pilot:pilot} instead.</p>
 *
 * <p>When the project has been compiled ({@code target/classes} exists), performs bytecode-level
 * analysis to determine which dependencies are actually referenced in code.
 * For accurate analysis of test-scoped dependencies, {@code target/test-classes} must also
 * exist (i.e. at least the {@code package} phase must have run). If test classes are absent
 * and the project declares test-scoped dependencies, the mojo fails — use
 * {@code -Dpilot.skipTestScope=true} to skip test-scope analysis entirely.</p>
 *
 * <p>Dependencies whose usage <em>cannot</em> be determined (e.g. resource-only JARs, deps
 * without any classes) are classified as <em>undetermined</em>. They are <strong>hidden by
 * default</strong> — use {@code -Dpilot.showUndetermined=true} to include them in the report.
 * Use {@code knownUsed} and {@code knownUnused} to annotate them explicitly. Annotating a dep
 * whose status is already confidently known (USED or UNUSED) as the opposite is treated as an
 * error — it means the annotation is stale.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * mvn package pilot:dependencies                                            # full analysis (recommended)
 * mvn package pilot:dependencies -Dpilot.showUndetermined=true             # also show undetermined
 * mvn package pilot:dependencies -Dpilot.action=check
 * mvn package pilot:dependencies -Dpilot.action=check -Dpilot.failOnUndetermined=true
 * mvn package pilot:dependencies -Dpilot.action=fix
 * mvn compile pilot:dependencies -Dpilot.skipTestScope=true                # skip test-scope analysis
 * </pre>
 *
 * @since 0.1.0
 */
@Mojo(name = "dependencies", requiresProject = true, threadSafe = true)
public class DependenciesMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Parameter(property = "pilot.action", defaultValue = "report")
    String action = "report";

    /**
     * When {@code true}, test-scoped dependencies are excluded from analysis entirely.
     * Use this when {@code target/test-classes} has not been compiled (e.g. after
     * {@code mvn compile} only) and you want to proceed without test-scope analysis.
     * <p>
     * When {@code false} (default) and {@code target/test-classes} is absent but
     * the project has test sources and test-scoped dependencies, the mojo fails with an error.
     * Projects with no test sources are handled gracefully regardless of this setting.
     * </p>
     */
    @Parameter(property = "pilot.skipTestScope", defaultValue = "false")
    boolean skipTestScope = false;

    @Parameter
    private List<String> runtimeArtifacts;

    @Parameter
    private List<String> annotationOnlyArtifacts;

    @Parameter
    private Map<String, String> reflectionLoadedClasses;

    @Parameter
    private List<String> ignoredUnusedDeclared;

    @Parameter
    private List<String> ignoredUsedTransitive;

    /**
     * Dependencies explicitly declared as used, overriding the bytecode analyser result.
     *
     * <p>Each entry is a {@code groupId:artifactId} pattern (same syntax as
     * {@code ignoredUnusedDeclared}). Matching <em>declared</em> dependencies whose status is
     * {@code UNDETERMINED} are treated as {@code USED} — they will not appear in
     * the undetermined list and will not be removed by the fix action.
     * This annotation has no effect on transitive dependencies.</p>
     *
     * <p>It is an error to mark a dependency as {@code knownUsed} when the analyser
     * has already confidently determined it to be {@code UNUSED}: the annotation is
     * stale and the build fails with a descriptive message.</p>
     *
     * @since 0.4.0
     */
    @Parameter
    private List<String> knownUsed;

    /**
     * Dependencies explicitly declared as unused, overriding the bytecode analyser result.
     *
     * <p>Each entry is a {@code groupId:artifactId} pattern (same syntax as
     * {@code ignoredUnusedDeclared}). Matching <em>declared</em> dependencies whose status is
     * {@code UNDETERMINED} are treated as {@code UNUSED} — they will appear in the
     * unused-declared list and can be removed by the fix action.
     * This annotation has no effect on transitive dependencies.</p>
     *
     * <p>It is an error to mark a dependency as {@code knownUnused} when the analyser
     * has already confidently determined it to be {@code USED}: the annotation is
     * stale and the build fails with a descriptive message.</p>
     *
     * @since 0.4.0
     */
    @Parameter
    private List<String> knownUnused;

    /**
     * When {@code true}, include {@code UNDETERMINED} dependencies in the report and check
     * output. Opt-in because undetermined deps (resource-only JARs, annotation processors,
     * BOMs-as-jars) are expected in many projects and add noise when shown unconditionally.
     *
     * <p>Has no effect on the {@code fix} action — undetermined deps are never removed.</p>
     *
     * @since 0.5.0
     */
    @Parameter(property = "pilot.showUndetermined", defaultValue = "false")
    private boolean showUndetermined = false;

    /**
     * When {@code true} and {@code action=check}, fail the build if any dependencies
     * remain {@code UNDETERMINED} after applying {@code knownUsed}/{@code knownUnused}
     * overrides. Useful in CI to enforce that every dependency is explicitly accounted for.
     * Implies {@code showUndetermined=true}.
     *
     * @since 0.4.0
     */
    @Parameter(property = "pilot.failOnUndetermined", defaultValue = "false")
    private boolean failOnUndetermined = false;

    private final RepositorySystem repoSystem;

    @Inject
    DependenciesMojo(RepositorySystem repoSystem) {
        this.repoSystem = repoSystem;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!"report".equals(action) && !"check".equals(action) && !"fix".equals(action)) {
            throw new MojoExecutionException("Invalid action '" + action + "'. Use 'report', 'check', or 'fix'.");
        }
        try {
            executeForProject(project);
        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to analyze dependencies: " + e.getMessage(), e);
        }
    }

    void executeForProject(MavenProject proj) throws Exception {
        if ("pom".equals(proj.getPackaging())) {
            getLog().debug("Skipping " + proj.getArtifactId() + " (pom packaging, no classes to analyse).");
            return;
        }

        // Early-exit guards: check filesystem state before the expensive dependency resolution.
        Path classesDir = Path.of(proj.getBuild().getOutputDirectory());
        Path testClassesDir = Path.of(proj.getBuild().getTestOutputDirectory());

        boolean hasMainSources = hasMainSources(proj);
        if (hasMainSources && !Files.isDirectory(classesDir)) {
            throw new MojoExecutionException("target/classes not found. Run 'mvn compile pilot:dependencies' first.");
        }
        boolean hasTestSources = hasTestSources(proj);
        if (!skipTestScope
                && hasTestSources
                && !Files.isDirectory(testClassesDir)
                && proj.getDependencies().stream()
                        .anyMatch(dep -> DependencyUsageAnalyzer.isTestScope(dep.getScope()))) {
            throw new MojoExecutionException(
                    "target/test-classes not found but the project declares test-scoped dependencies."
                            + " Run 'mvn test-compile pilot:dependencies' for accurate analysis,"
                            + " or use -Dpilot.skipTestScope=true to exclude test-scope analysis.");
        }

        Set<String> declaredGAs = new HashSet<>();
        List<DependenciesTui.DepEntry> declared = new ArrayList<>();
        for (Dependency dep : proj.getDependencies()) {
            DependenciesTui.addDeclaredEntry(
                    declaredGAs,
                    declared,
                    dep.getGroupId(),
                    dep.getArtifactId(),
                    dep.getClassifier(),
                    dep.getVersion(),
                    dep.getScope());
        }

        DependencyRequest depRequest = new DependencyRequest(MojoHelper.buildCollectRequest(proj), null);
        DependencyResult depResult = repoSystem.resolveDependencies(repoSession, depRequest);

        DependencyTreeModel depTree = MojoHelper.fromDependencyNode(depResult.getRoot());
        Set<String> transitiveGAs = new HashSet<>();
        List<DependenciesTui.DepEntry> transitive = new ArrayList<>();
        DependenciesTui.collectTransitive(depTree.root, declaredGAs, transitiveGAs, transitive);

        Map<String, File> gaToJar = new HashMap<>();
        Map<String, String> gaToVersion = new HashMap<>();
        for (ArtifactResult ar : depResult.getArtifactResults()) {
            var art = ar.getArtifact();
            if (art != null) {
                String classifier = art.getClassifier();
                String ga = (classifier != null && !classifier.isEmpty())
                        ? art.getGroupId() + ":" + art.getArtifactId() + ":" + classifier
                        : art.getGroupId() + ":" + art.getArtifactId();
                gaToVersion.put(ga, art.getVersion());
                if (art.getFile() != null && art.getFile().getName().endsWith(".jar")) {
                    gaToJar.put(ga, art.getFile());
                }
            }
        }

        // Build set of GAs already managed by an ancestor BOM/parent POM (not by this module itself).
        // When a transitive dependency is already version-managed by an ancestor, the fix action
        // should add it without a <version> element rather than hardcoding the resolved literal.
        Set<String> ancestorManagedGAs = buildAncestorManagedGAs(proj);

        // Scan compiled bytecode — absent when project has no sources (e.g. POM packaging).
        ClassFileScanner.ScanResult mainScan = Files.isDirectory(classesDir)
                ? ClassFileScanner.scanDirectory(classesDir)
                : new ClassFileScanner.ScanResult(Set.of(), Map.of());
        boolean testClassesScanned = Files.isDirectory(testClassesDir);
        ClassFileScanner.ScanResult testScan;

        if (skipTestScope) {
            getLog().info("Skipping test-scope dependency analysis (pilot.skipTestScope=true).");
            // Exclude test-scoped deps from both lists before analysis so they are never
            // classified and never added/removed by the fix action.
            declared.removeIf(dep -> DependencyUsageAnalyzer.isTestScope(dep.scope));
            transitive.removeIf(dep -> DependencyUsageAnalyzer.isTestScope(dep.scope));
            testScan = new ClassFileScanner.ScanResult(Set.of(), Map.of());
        } else if (hasTestSources && testClassesScanned) {
            testScan = ClassFileScanner.scanDirectory(testClassesDir);
        } else {
            // No test sources or no compiled test classes — proceed without test bytecode.
            testScan = new ClassFileScanner.ScanResult(Set.of(), Map.of());
        }

        Map<String, String> classIndex = DependencyUsageAnalyzer.buildClassIndex(gaToJar);
        DependencyUsageAnalyzer.AnalysisResult usage = buildAnalyzer()
                .analyze(
                        mainScan.referencedClasses(),
                        testScan.referencedClasses(),
                        classIndex,
                        gaToJar,
                        declared,
                        transitive);
        applyUsageStatus(declared, transitive, usage);

        executeNonInteractive(proj, declared, transitive, gaToVersion, ancestorManagedGAs);
    }

    /**
     * Computes the set of {@code groupId:artifactId} keys that are version-managed by an ancestor
     * POM or imported BOM, but <em>not</em> declared in this module's own {@code
     * <dependencyManagement>} section.
     *
     * <p>When a used-transitive dependency is already managed by an ancestor, the fix action
     * should add it to {@code <dependencies>} without a {@code <version>} element.</p>
     *
     * <p>Note: dependencies provided by a BOM that is <em>imported</em> in this module's own
     * {@code <dependencyManagement>} (via {@code <scope>import</scope>}) are classified as
     * ancestor-managed. This is intentional — those dependencies are version-pinned by the BOM
     * import, so omitting {@code <version>} is correct as long as the import remains present.</p>
     */
    static Set<String> buildAncestorManagedGAs(MavenProject proj) {
        // Use the effective (merged) model and filter by InputLocation source to distinguish
        // entries declared in this module's own POM from those inherited from ancestors.
        // InputLocation.getSource().getLocation() resolves to the physical POM file path,
        // so entries whose source matches proj.getFile() are own-managed; all others are
        // ancestor-managed. This works correctly even when the module's own DM entries use
        // property expressions, because we compare file paths, not resolved GA strings.
        String ownPomPath = proj.getFile().toPath().normalize().toString();

        Set<String> ancestorManagedGAs = new HashSet<>();
        if (proj.getModel().getDependencyManagement() == null
                || proj.getModel().getDependencyManagement().getDependencies() == null) {
            return ancestorManagedGAs;
        }
        for (Dependency dep : proj.getModel().getDependencyManagement().getDependencies()) {
            InputLocation loc = dep.getLocation("");
            String rawSourcePath =
                    (loc != null && loc.getSource() != null) ? loc.getSource().getLocation() : null;
            // Normalize local file paths (which may contain "." or "..") before comparison.
            // URL-style locations (containing "://") are left unchanged; they cannot match a local path.
            String sourcePath = (rawSourcePath != null && !rawSourcePath.contains("://"))
                    ? Path.of(rawSourcePath).normalize().toString()
                    : rawSourcePath;
            if (sourcePath == null || !sourcePath.equals(ownPomPath)) {
                String classifier = dep.getClassifier();
                String ga = (classifier != null && !classifier.isEmpty())
                        ? dep.getGroupId() + ":" + dep.getArtifactId() + ":" + classifier
                        : dep.getGroupId() + ":" + dep.getArtifactId();
                ancestorManagedGAs.add(ga);
            }
        }
        return ancestorManagedGAs;
    }

    void executeNonInteractive(
            MavenProject proj,
            List<DependenciesTui.DepEntry> declared,
            List<DependenciesTui.DepEntry> transitive,
            Map<String, String> gaToVersion)
            throws Exception {
        executeNonInteractive(proj, declared, transitive, gaToVersion, Set.of());
    }

    void executeNonInteractive(
            MavenProject proj,
            List<DependenciesTui.DepEntry> declared,
            List<DependenciesTui.DepEntry> transitive,
            Map<String, String> gaToVersion,
            Set<String> ancestorManagedGAs)
            throws Exception {

        // --- Apply knownUsed / knownUnused overrides ---
        Set<String> knownUsedSet = buildIgnoreSet(knownUsed);
        Set<String> knownUnusedSet = buildIgnoreSet(knownUnused);

        List<String> contradictions = new ArrayList<>();
        for (var dep : declared) {
            if (DependencyUsageAnalyzer.matchesArtifactPattern(dep.ga(), knownUsedSet)) {
                if (dep.usageStatus == DependencyUsageAnalyzer.UsageStatus.UNUSED) {
                    contradictions.add("'" + dep.ga() + "' is declared knownUsed but analyser found it UNUSED");
                } else if (dep.usageStatus == DependencyUsageAnalyzer.UsageStatus.UNDETERMINED) {
                    dep.usageStatus = DependencyUsageAnalyzer.UsageStatus.USED;
                }
            }
            if (DependencyUsageAnalyzer.matchesArtifactPattern(dep.ga(), knownUnusedSet)) {
                if (dep.usageStatus == DependencyUsageAnalyzer.UsageStatus.USED) {
                    contradictions.add("'" + dep.ga() + "' is declared knownUnused but analyser found it USED");
                } else if (dep.usageStatus == DependencyUsageAnalyzer.UsageStatus.UNDETERMINED) {
                    dep.usageStatus = DependencyUsageAnalyzer.UsageStatus.UNUSED;
                }
            }
        }
        if (!contradictions.isEmpty()) {
            StringBuilder msg = new StringBuilder("Stale knownUsed/knownUnused annotations detected:\n");
            for (String c : contradictions) {
                msg.append("  - ").append(c).append("\n");
            }
            msg.append("Remove or update the annotation to match the actual usage.");
            throw new MojoFailureException(msg.toString());
        }

        // --- Bucket deps by status ---
        List<DependenciesTui.DepEntry> unusedDeclared = new ArrayList<>();
        for (var dep : declared) {
            if (dep.usageStatus == DependencyUsageAnalyzer.UsageStatus.UNUSED) {
                unusedDeclared.add(dep);
            }
        }

        List<DependenciesTui.DepEntry> usedTransitive = new ArrayList<>();
        for (var dep : transitive) {
            if (dep.usageStatus == DependencyUsageAnalyzer.UsageStatus.USED) {
                usedTransitive.add(dep);
            }
        }

        List<DependenciesTui.DepEntry> undetermined = new ArrayList<>();
        for (var dep : declared) {
            if (dep.usageStatus == DependencyUsageAnalyzer.UsageStatus.UNDETERMINED) {
                undetermined.add(dep);
            }
        }
        // Transitive UNDETERMINED is not surfaced — the project never declared those deps,
        // so their usage status is not actionable regardless.

        // --- Apply ignore-lists (remove false positives) ---
        Set<String> ignoredUnused = buildIgnoreSet(ignoredUnusedDeclared);
        Set<String> ignoredTransitive = buildIgnoreSet(ignoredUsedTransitive);
        unusedDeclared.removeIf(dep -> DependencyUsageAnalyzer.matchesArtifactPattern(dep.ga(), ignoredUnused));
        usedTransitive.removeIf(dep -> DependencyUsageAnalyzer.matchesArtifactPattern(dep.ga(), ignoredTransitive));

        // showUndetermined is opt-in; failOnUndetermined implies showing them
        List<DependenciesTui.DepEntry> visibleUndetermined =
                (showUndetermined || failOnUndetermined) ? undetermined : List.of();

        if (unusedDeclared.isEmpty() && usedTransitive.isEmpty() && visibleUndetermined.isEmpty()) {
            if (!undetermined.isEmpty()) {
                getLog().debug(undetermined.size()
                        + " undetermined dep(s) hidden — use -Dpilot.showUndetermined=true to see them.");
            }
            getLog().info("No dependency issues found.");
            return;
        }

        switch (action) {
            case "fix" ->
                DependenciesReporter.fix(
                        proj.getFile().toPath(),
                        unusedDeclared,
                        usedTransitive,
                        gaToVersion,
                        ancestorManagedGAs,
                        getLog()::info);
            case "report" ->
                getLog().warn(DependenciesReporter.formatFindings(unusedDeclared, usedTransitive, visibleUndetermined));
            default -> {
                boolean hasIssues = !unusedDeclared.isEmpty() || !usedTransitive.isEmpty();
                boolean hasUndetermined = !undetermined.isEmpty();
                if (hasIssues || (hasUndetermined && failOnUndetermined)) {
                    throw new MojoFailureException(DependenciesReporter.formatCheckFailure(
                            unusedDeclared, usedTransitive, visibleUndetermined));
                } else if (!visibleUndetermined.isEmpty()) {
                    // undetermined visible but failOnUndetermined=false — warn only
                    getLog().warn(DependenciesReporter.formatFindings(
                            unusedDeclared, usedTransitive, visibleUndetermined));
                }
            }
        }
    }

    private void applyUsageStatus(
            List<DependenciesTui.DepEntry> declared,
            List<DependenciesTui.DepEntry> transitive,
            DependencyUsageAnalyzer.AnalysisResult usage) {
        for (var dep : declared) {
            dep.usageStatus =
                    usage.declaredUsage().getOrDefault(dep.ga(), DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
        }
        for (var dep : transitive) {
            dep.usageStatus =
                    usage.transitiveUsage().getOrDefault(dep.ga(), DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
        }
    }

    DependencyUsageAnalyzer buildAnalyzer() {
        DependencyUsageAnalyzer.Builder builder = DependencyUsageAnalyzer.builder();
        if (runtimeArtifacts != null && !runtimeArtifacts.isEmpty()) {
            builder.runtimeArtifacts(new HashSet<>(runtimeArtifacts));
        }
        if (annotationOnlyArtifacts != null && !annotationOnlyArtifacts.isEmpty()) {
            builder.annotationOnlyArtifacts(new HashSet<>(annotationOnlyArtifacts));
        }
        if (reflectionLoadedClasses != null && !reflectionLoadedClasses.isEmpty()) {
            Map<String, List<String>> parsed = new HashMap<>();
            for (var entry : reflectionLoadedClasses.entrySet()) {
                parsed.put(entry.getKey(), List.of(entry.getValue().split(",")));
            }
            builder.reflectionLoadedClasses(parsed);
        }
        return builder.build();
    }

    static Set<String> buildIgnoreSet(List<String> patterns) {
        return patterns != null && !patterns.isEmpty() ? new HashSet<>(patterns) : Set.of();
    }

    /**
     * Returns {@code true} if the project has at least one main source directory that contains at least one
     * regular file (walking recursively). When a project has no main sources (e.g. POM packaging, BOM,
     * parent POM), the absence of {@code target/classes} is expected and should not be treated as an error.
     *
     * <p>Iterates {@link MavenProject#getCompileSourceRoots()} so that source roots registered by
     * annotation processors or build-helper-maven-plugin are included alongside the primary source
     * directory.</p>
     */
    static boolean hasMainSources(MavenProject proj) throws IOException {
        for (String root : proj.getCompileSourceRoots()) {
            if (root != null) {
                Path rootPath = Path.of(root);
                if (Files.isDirectory(rootPath)) {
                    try (var walk = Files.walk(rootPath)) {
                        if (walk.anyMatch(Files::isRegularFile)) return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if the project has at least one test source directory that contains at least one
     * regular file (walking recursively). When a project has no test sources, the absence of
     * {@code target/test-classes} is expected and should not be treated as an error.
     *
     * <p>Iterates {@link MavenProject#getTestCompileSourceRoots()} so that generated test source roots
     * registered by annotation processors (Quarkus Panache, MapStruct, etc.) or build-helper-maven-plugin
     * are included alongside the primary test source directory.</p>
     */
    static boolean hasTestSources(MavenProject proj) throws IOException {
        for (String root : proj.getTestCompileSourceRoots()) {
            if (root != null) {
                Path rootPath = Path.of(root);
                if (Files.isDirectory(rootPath)) {
                    try (var walk = Files.walk(rootPath)) {
                        if (walk.anyMatch(Files::isRegularFile)) return true;
                    }
                }
            }
        }
        return false;
    }
}
