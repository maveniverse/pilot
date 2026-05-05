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

import eu.maveniverse.domtrip.Document;
import eu.maveniverse.domtrip.maven.Coordinates;
import eu.maveniverse.domtrip.maven.PomEditor;
import eu.maveniverse.maven.pilot.ClassFileScanner;
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
 * <p>Requires prior compilation ({@code target/classes} must exist). Two actions:</p>
 * <ul>
 *   <li><b>check</b> (default) — reports unused declared dependencies and fails the build</li>
 *   <li><b>fix</b> — removes unused declared dependencies from the POM</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>
 * mvn compile pilot:analyze-dependencies
 * mvn compile pilot:analyze-dependencies -Dpilot.action=fix
 * </pre>
 *
 * <p>Allowlists can be configured to prevent false positives:</p>
 * <pre>{@code
 * <plugin>
 *   <groupId>eu.maveniverse.maven</groupId>
 *   <artifactId>pilot-plugin</artifactId>
 *   <configuration>
 *     <runtimeArtifacts>
 *       <runtimeArtifact>org.postgresql:postgresql</runtimeArtifact>
 *       <runtimeArtifact>com.oracle.database.jdbc:*</runtimeArtifact>
 *     </runtimeArtifacts>
 *     <annotationOnlyArtifacts>
 *       <annotationOnlyArtifact>org.projectlombok:lombok</annotationOnlyArtifact>
 *     </annotationOnlyArtifacts>
 *   </configuration>
 * </plugin>
 * }</pre>
 *
 * @since 0.2.3
 */
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

    private final RepositorySystem repoSystem;

    @Inject
    AnalyzeDependenciesMojo(RepositorySystem repoSystem) {
        this.repoSystem = repoSystem;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!"check".equals(action) && !"fix".equals(action)) {
            throw new MojoExecutionException("Invalid action '" + action + "'. Use 'check' or 'fix'.");
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

        // Build GA-to-JAR map
        Map<String, File> gaToJar = new HashMap<>();
        for (ArtifactResult ar : depResult.getArtifactResults()) {
            var art = ar.getArtifact();
            if (art != null && art.getFile() != null && art.getFile().getName().endsWith(".jar")) {
                gaToJar.put(art.getGroupId() + ":" + art.getArtifactId(), art.getFile());
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
        List<DependenciesTui.DepEntry> unused = new ArrayList<>();
        for (var dep : declared) {
            DependencyUsageAnalyzer.UsageStatus status =
                    usage.declaredUsage().getOrDefault(dep.ga(), DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
            if (status == DependencyUsageAnalyzer.UsageStatus.UNUSED) {
                unused.add(dep);
            }
        }

        if (unused.isEmpty()) {
            getLog().info("No unused declared dependencies found.");
            return;
        }

        if ("fix".equals(action)) {
            fix(proj, unused);
        } else {
            check(unused);
        }
    }

    private DependencyUsageAnalyzer buildAnalyzer() {
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

    private void check(List<DependenciesTui.DepEntry> unused) throws MojoFailureException {
        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(unused.size()).append(" unused declared dependenc");
        sb.append(unused.size() == 1 ? "y" : "ies").append(":\n");
        for (var dep : unused) {
            sb.append("  - ").append(dep.ga());
            if (dep.scope != null && !dep.scope.isEmpty() && !"compile".equals(dep.scope)) {
                sb.append(" (").append(dep.scope).append(")");
            }
            sb.append("\n");
        }
        sb.append("\nRun with -Dpilot.action=fix to remove them, or configure allowlists for false positives.");
        throw new MojoFailureException(sb.toString());
    }

    private void fix(MavenProject proj, List<DependenciesTui.DepEntry> unused) throws Exception {
        Path pomPath = proj.getFile().toPath();
        String pomContent = Files.readString(pomPath);
        PomEditor editor = new PomEditor(Document.of(pomContent));

        for (var dep : unused) {
            String[] parts = dep.ga().split(":");
            Coordinates coords = parts.length > 2
                    ? Coordinates.of(parts[0], parts[1], null, parts[2], "jar")
                    : Coordinates.of(parts[0], parts[1], null);
            editor.dependencies().deleteDependency(coords);
            getLog().info("Removed unused dependency: " + dep.ga());
        }

        Files.writeString(pomPath, editor.toXml());
        getLog().info("Updated " + pomPath);
    }
}
