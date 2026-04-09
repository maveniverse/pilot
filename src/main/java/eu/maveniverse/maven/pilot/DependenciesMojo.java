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
 * Interactive TUI showing declared vs transitive dependency overview.
 *
 * <p>Displays two views: declared dependencies (from the POM) and transitive
 * dependencies (pulled in indirectly). Allows promoting transitive dependencies
 * to declared, or removing declared dependencies from the POM.</p>
 *
 * <p>When the project has been compiled ({@code target/classes} exists), performs bytecode-level
 * analysis by scanning class file constant pools to determine which dependencies are actually
 * referenced in code. Without compilation, falls back to a structural overview.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * mvn compile pilot:dependencies
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

    private final RepositorySystem repoSystem;

    @Inject
    DependenciesMojo(RepositorySystem repoSystem) {
        this.repoSystem = repoSystem;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            List<MavenProject> projects = session.getProjects();
            if (projects.size() > 1) {
                executeReactor(projects);
            } else {
                executeForProject(project);
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to analyze dependencies: " + e.getMessage(), e);
        }
    }

    private void executeReactor(List<MavenProject> projects) throws Exception {
        ReactorModel reactorModel = ReactorModel.build(projects);
        MavenProject root = projects.get(0);
        String reactorGav = root.getGroupId() + ":" + root.getArtifactId() + ":" + root.getVersion();

        while (true) {
            MavenProject selected = new ModulePickerTui(reactorModel, reactorGav, "dependencies").pick();
            if (selected == null) break;
            executeForProject(selected);
        }
    }

    private void executeForProject(MavenProject proj) throws Exception {
        // Collect direct declared dependencies
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

        // Resolve full transitive tree and artifact files
        DependencyRequest depRequest = new DependencyRequest(MojoHelper.buildCollectRequest(proj), null);
        DependencyResult depResult = repoSystem.resolveDependencies(repoSession, depRequest);

        // Find transitive (undeclared) dependencies
        Set<String> transitiveGAs = new HashSet<>();
        List<DependenciesTui.DepEntry> transitive = new ArrayList<>();
        DependenciesTui.collectTransitive(depResult.getRoot(), declaredGAs, transitiveGAs, transitive);

        // Build GA-to-JAR map from resolved artifacts
        Map<String, File> gaToJar = new HashMap<>();
        for (ArtifactResult ar : depResult.getArtifactResults()) {
            var art = ar.getArtifact();
            if (art != null && art.getFile() != null && art.getFile().getName().endsWith(".jar")) {
                gaToJar.put(art.getGroupId() + ":" + art.getArtifactId(), art.getFile());
            }
        }

        // Bytecode usage analysis
        Path classesDir = Path.of(proj.getBuild().getOutputDirectory());
        Path testClassesDir = Path.of(proj.getBuild().getTestOutputDirectory());
        boolean bytecodeAnalyzed = false;

        if (Files.isDirectory(classesDir)) {
            bytecodeAnalyzed = true;

            Set<String> mainRefs = ClassFileScanner.scanDirectory(classesDir);
            Set<String> testRefs =
                    Files.isDirectory(testClassesDir) ? ClassFileScanner.scanDirectory(testClassesDir) : Set.of();

            Map<String, String> classIndex = DependencyUsageAnalyzer.buildClassIndex(gaToJar);
            DependencyUsageAnalyzer.AnalysisResult usage =
                    DependencyUsageAnalyzer.analyze(mainRefs, testRefs, classIndex, gaToJar, declared, transitive);

            for (var dep : declared) {
                dep.usageStatus =
                        usage.declaredUsage().getOrDefault(dep.ga(), DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
            }
            for (var dep : transitive) {
                dep.usageStatus = usage.transitiveUsage()
                        .getOrDefault(dep.ga(), DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
            }
        } else {
            getLog().warn("target/classes not found — skipping bytecode analysis. Run 'mvn compile' first.");
        }

        String pomPath = proj.getFile().getAbsolutePath();
        String gav = proj.getGroupId() + ":" + proj.getArtifactId() + ":" + proj.getVersion();

        DependenciesTui tui = new DependenciesTui(declared, transitive, pomPath, gav, bytecodeAnalyzed);
        tui.run();
    }
}
