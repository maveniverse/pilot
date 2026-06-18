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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 * Dependency analysis: interactive TUI and CI-friendly report/check/fix modes.
 *
 * <p>Four actions via {@code -Dpilot.action}:</p>
 * <ul>
 *   <li><b>tui</b> (default) — interactive TUI showing declared vs transitive dependencies</li>
 *   <li><b>report</b> — prints unused declared and used transitive dependencies without failing</li>
 *   <li><b>check</b> — reports issues and fails the build</li>
 *   <li><b>fix</b> — removes unused declared and adds used transitive dependencies</li>
 * </ul>
 *
 * <p>When the project has been compiled ({@code target/classes} exists), performs bytecode-level
 * analysis to determine which dependencies are actually referenced in code.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * mvn compile pilot:dependencies
 * mvn compile pilot:dependencies -Dpilot.action=report
 * mvn compile pilot:dependencies -Dpilot.action=check
 * mvn compile pilot:dependencies -Dpilot.action=fix
 * </pre>
 *
 * @since 0.1.0
 */
@Mojo(name = "dependencies", requiresProject = true, aggregator = true, threadSafe = true)
public class DependenciesMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Parameter(property = "pilot.action", defaultValue = "tui")
    String action = "tui";

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
    DependenciesMojo(RepositorySystem repoSystem) {
        this.repoSystem = repoSystem;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!"tui".equals(action) && !"report".equals(action) && !"check".equals(action) && !"fix".equals(action)) {
            throw new MojoExecutionException(
                    "Invalid action '" + action + "'. Use 'tui', 'report', 'check', or 'fix'.");
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

        Path classesDir = Path.of(proj.getBuild().getOutputDirectory());
        Path testClassesDir = Path.of(proj.getBuild().getTestOutputDirectory());
        boolean bytecodeAnalyzed = false;

        DependencyUsageAnalyzer.AnalysisResult usage = null;
        if (Files.isDirectory(classesDir)) {
            bytecodeAnalyzed = true;

            ClassFileScanner.ScanResult mainScan = ClassFileScanner.scanDirectory(classesDir);
            ClassFileScanner.ScanResult testScan = Files.isDirectory(testClassesDir)
                    ? ClassFileScanner.scanDirectory(testClassesDir)
                    : new ClassFileScanner.ScanResult(Set.of(), Map.of());

            Map<String, String> classIndex = DependencyUsageAnalyzer.buildClassIndex(gaToJar);
            usage = buildAnalyzer()
                    .analyze(
                            mainScan.referencedClasses(),
                            testScan.referencedClasses(),
                            classIndex,
                            gaToJar,
                            declared,
                            transitive);

            if ("tui".equals(action)) {
                enrichForTui(declared, transitive, classIndex, gaToJar, mainScan, testScan, usage);
            } else {
                applyUsageStatus(declared, transitive, usage);
            }
        } else if (!"tui".equals(action)) {
            throw new MojoExecutionException(
                    "target/classes not found — run 'mvn compile' before dependencies report/check/fix.");
        } else {
            getLog().warn("target/classes not found — skipping bytecode analysis. Run 'mvn compile' first.");
        }

        if ("tui".equals(action)) {
            String pomPath = proj.getFile().getAbsolutePath();
            String gav = proj.getGroupId() + ":" + proj.getArtifactId() + ":" + proj.getVersion();
            DependenciesTui tui = new DependenciesTui(declared, transitive, pomPath, gav, bytecodeAnalyzed);
            tui.runStandalone();
        } else {
            executeNonInteractive(proj, declared, transitive, gaToVersion);
        }
    }

    void executeNonInteractive(
            MavenProject proj,
            List<DependenciesTui.DepEntry> declared,
            List<DependenciesTui.DepEntry> transitive,
            Map<String, String> gaToVersion)
            throws Exception {
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

    private void enrichForTui(
            List<DependenciesTui.DepEntry> declared,
            List<DependenciesTui.DepEntry> transitive,
            Map<String, String> classIndex,
            Map<String, File> gaToJar,
            ClassFileScanner.ScanResult mainScan,
            ClassFileScanner.ScanResult testScan,
            DependencyUsageAnalyzer.AnalysisResult usage) {
        Map<String, Set<String>> gaToClasses = new HashMap<>();
        for (var entry : classIndex.entrySet()) {
            gaToClasses.computeIfAbsent(entry.getValue(), k -> new HashSet<>()).add(entry.getKey());
        }

        Map<String, Set<String>> allMembers = new HashMap<>(mainScan.memberReferences());
        for (var entry : testScan.memberReferences().entrySet()) {
            allMembers.computeIfAbsent(entry.getKey(), k -> new HashSet<>()).addAll(entry.getValue());
        }

        Set<String> allClassRefs = new HashSet<>(mainScan.referencedClasses());
        allClassRefs.addAll(testScan.referencedClasses());

        for (var dep : declared) {
            dep.usageStatus =
                    usage.declaredUsage().getOrDefault(dep.ga(), DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
            enrichUsageDetail(dep, gaToClasses, gaToJar, mainScan.referencedClasses(), allClassRefs, allMembers);
        }
        for (var dep : transitive) {
            dep.usageStatus =
                    usage.transitiveUsage().getOrDefault(dep.ga(), DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
            enrichUsageDetail(dep, gaToClasses, gaToJar, mainScan.referencedClasses(), allClassRefs, allMembers);
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

    private static final Set<String> TEST_SCOPES = Set.of("test", "test-only", "test-runtime");

    private static void enrichUsageDetail(
            DependenciesTui.DepEntry dep,
            Map<String, Set<String>> gaToClasses,
            Map<String, File> gaToJar,
            Set<String> mainClassRefs,
            Set<String> allClassRefs,
            Map<String, Set<String>> allMemberRefs) {
        Set<String> depClasses = gaToClasses.get(dep.ga());
        if (depClasses == null) {
            dep.totalClasses = 0;
            dep.usedMembers = Map.of();
        } else {
            dep.totalClasses = depClasses.size();
            Set<String> refs = TEST_SCOPES.contains(dep.scope) ? allClassRefs : mainClassRefs;
            Map<String, List<String>> members = new TreeMap<>();
            for (String className : depClasses) {
                if (refs.contains(className)) {
                    Set<String> memberSet = allMemberRefs.get(className);
                    members.put(
                            className,
                            memberSet != null ? memberSet.stream().sorted().toList() : List.of());
                }
            }
            dep.usedMembers = members;
        }

        File jarFile = gaToJar.get(dep.ga());
        if (jarFile != null) {
            Set<String> spi = DependencyUsageAnalyzer.getRuntimeDiscoveryClasses(jarFile);
            dep.spiServices = spi.isEmpty() ? List.of() : spi.stream().sorted().toList();
        } else {
            dep.spiServices = List.of();
        }
    }
}
