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

import eu.maveniverse.maven.pilot.ClassFileScanner;
import eu.maveniverse.maven.pilot.DependenciesReporter;
import eu.maveniverse.maven.pilot.DependenciesTui;
import eu.maveniverse.maven.pilot.DependencyTreeModel;
import eu.maveniverse.maven.pilot.DependencyUsageAnalyzer;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
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
 * Analyzes declared dependencies for usage and reports or removes unused ones.
 *
 * @deprecated Use {@code pilot:dependencies -Dpilot.action=check} instead.
 *     This goal will be removed in a future release.
 *
 * @since 0.2.3
 */
@Deprecated
@Mojo(name = "analyze-dependencies", requiresProject = true, aggregator = true, threadSafe = true)
public class AnalyzeDependenciesMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Parameter(property = "pilot.action", defaultValue = "check")
    private String action;

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

    private final RepositorySystem repoSystem;

    @Inject
    AnalyzeDependenciesMojo(RepositorySystem repoSystem) {
        this.repoSystem = repoSystem;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().warn("pilot:analyze-dependencies is deprecated. Use pilot:dependencies -Dpilot.action=" + action
                + " instead.");
        if (!"check".equals(action) && !"report".equals(action) && !"fix".equals(action)) {
            throw new MojoExecutionException("Invalid action '" + action + "'. Use 'check', 'report', or 'fix'.");
        }
        try {
            executeForProject(project);
        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to analyze dependencies: " + e.getMessage(), e);
        }
    }

    private void executeForProject(MavenProject proj) throws Exception {
        // Collect declared dependencies
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

        // Resolve transitive tree
        DependencyRequest depRequest = new DependencyRequest(MojoHelper.buildCollectRequest(proj), null);
        DependencyResult depResult = repoSystem.resolveDependencies(repoSession, depRequest);

        DependencyTreeModel depTree = MojoHelper.fromDependencyNode(depResult.getRoot());
        Set<String> transitiveGAs = new HashSet<>();
        List<DependenciesTui.DepEntry> transitive = new ArrayList<>();
        DependenciesTui.collectTransitive(depTree.root, declaredGAs, transitiveGAs, transitive);

        // Build GA-to-JAR and GA-to-version maps from resolved artifacts
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

        // Bytecode analysis
        Path classesDir = Path.of(proj.getBuild().getOutputDirectory());
        if (!Files.isDirectory(classesDir)) {
            throw new MojoExecutionException(
                    "target/classes not found — run 'mvn compile' before analyze-dependencies.");
        }

        Path testClassesDir = Path.of(proj.getBuild().getTestOutputDirectory());
        ClassFileScanner.ScanResult mainScan = ClassFileScanner.scanDirectory(classesDir);
        ClassFileScanner.ScanResult testScan = Files.isDirectory(testClassesDir)
                ? ClassFileScanner.scanDirectory(testClassesDir)
                : new ClassFileScanner.ScanResult(Set.of(), Map.of());

        Map<String, String> classIndex = DependencyUsageAnalyzer.buildClassIndex(gaToJar);

        DependencyUsageAnalyzer analyzer = buildAnalyzer();
        DependencyUsageAnalyzer.AnalysisResult usage = analyzer.analyze(
                mainScan.referencedClasses(), testScan.referencedClasses(), classIndex, gaToJar, declared, transitive);

        // Find unused declared dependencies
        List<DependenciesTui.DepEntry> unusedDeclared = new ArrayList<>();
        for (var dep : declared) {
            DependencyUsageAnalyzer.UsageStatus status =
                    usage.declaredUsage().getOrDefault(dep.ga(), DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
            if (status == DependencyUsageAnalyzer.UsageStatus.UNUSED) {
                unusedDeclared.add(dep);
            }
        }

        // Find used transitive dependencies (should be declared)
        List<DependenciesTui.DepEntry> usedTransitive = new ArrayList<>();
        for (var dep : transitive) {
            DependencyUsageAnalyzer.UsageStatus status =
                    usage.transitiveUsage().getOrDefault(dep.ga(), DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
            if (status == DependencyUsageAnalyzer.UsageStatus.USED) {
                usedTransitive.add(dep);
            }
        }

        // Apply ignore lists
        Set<String> ignoredUnused = buildIgnoreSet(ignoredUnusedDeclared);
        Set<String> ignoredTransitive = buildIgnoreSet(ignoredUsedTransitive);
        unusedDeclared.removeIf(dep -> DependencyUsageAnalyzer.matchesArtifactPattern(dep.ga(), ignoredUnused));
        usedTransitive.removeIf(dep -> DependencyUsageAnalyzer.matchesArtifactPattern(dep.ga(), ignoredTransitive));

        if (unusedDeclared.isEmpty() && usedTransitive.isEmpty()) {
            getLog().info("No dependency issues found.");
            return;
        }

        switch (action) {
            case "fix" ->
                DependenciesReporter.fix(
                        proj.getFile().toPath(), unusedDeclared, usedTransitive, gaToVersion, getLog()::info);
            case "report" -> getLog().warn(DependenciesReporter.formatFindings(unusedDeclared, usedTransitive));
            default ->
                throw new MojoFailureException(DependenciesReporter.formatCheckFailure(unusedDeclared, usedTransitive));
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

    // Kept for backwards-compatible test access; delegates to DependenciesReporter
    String formatFindings(
            List<DependenciesTui.DepEntry> unusedDeclared, List<DependenciesTui.DepEntry> usedTransitive) {
        return DependenciesReporter.formatFindings(unusedDeclared, usedTransitive);
    }

    static void appendScope(StringBuilder sb, DependenciesTui.DepEntry dep) {
        DependenciesReporter.appendScope(sb, dep);
    }

    void report(List<DependenciesTui.DepEntry> unusedDeclared, List<DependenciesTui.DepEntry> usedTransitive) {
        getLog().warn(DependenciesReporter.formatFindings(unusedDeclared, usedTransitive));
    }

    void check(List<DependenciesTui.DepEntry> unusedDeclared, List<DependenciesTui.DepEntry> usedTransitive)
            throws MojoFailureException {
        throw new MojoFailureException(DependenciesReporter.formatCheckFailure(unusedDeclared, usedTransitive));
    }
}
