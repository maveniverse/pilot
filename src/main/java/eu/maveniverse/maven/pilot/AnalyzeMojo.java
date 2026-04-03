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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.Exclusion;

/**
 * Interactive dependency analysis — shows unused declared and used undeclared dependencies.
 *
 * <p>Note: This is a heuristic analysis based on the dependency tree structure.
 * For bytecode-level analysis, use maven-dependency-analyzer.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * mvn pilot:analyze
 * </pre>
 *
 * @since 0.1.0
 */
@Mojo(name = "analyze", requiresProject = true, threadSafe = true)
public class AnalyzeMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Inject
    private RepositorySystem repoSystem;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            // Collect direct declared dependencies
            Set<String> declaredGAs = new HashSet<>();
            List<AnalyzeTui.DepEntry> declared = new ArrayList<>();
            for (Dependency dep : project.getDependencies()) {
                String ga = dep.getGroupId() + ":" + dep.getArtifactId();
                declaredGAs.add(ga);
                declared.add(new AnalyzeTui.DepEntry(
                        dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), dep.getScope(), true));
            }

            // Resolve full transitive tree
            CollectRequest collectRequest = new CollectRequest();
            collectRequest.setRootArtifact(new DefaultArtifact(
                    project.getGroupId(), project.getArtifactId(),
                    project.getPackaging(), project.getVersion()));
            collectRequest.setDependencies(
                    project.getDependencies().stream().map(this::convert).collect(Collectors.toList()));
            if (project.getDependencyManagement() != null) {
                collectRequest.setManagedDependencies(project.getDependencyManagement().getDependencies().stream()
                        .map(this::convert)
                        .collect(Collectors.toList()));
            }
            collectRequest.setRepositories(project.getRemoteProjectRepositories());

            CollectResult result = repoSystem.collectDependencies(repoSession, collectRequest);

            // Find transitive (undeclared) dependencies
            Set<String> transitiveGAs = new HashSet<>();
            List<AnalyzeTui.DepEntry> transitive = new ArrayList<>();
            collectTransitive(result.getRoot(), declaredGAs, transitiveGAs, transitive, "");

            String pomPath = project.getFile().getAbsolutePath();
            String gav = project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion();

            AnalyzeTui tui = new AnalyzeTui(declared, transitive, pomPath, gav);
            tui.run();
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to analyze dependencies: " + e.getMessage(), e);
        }
    }

    private void collectTransitive(
            DependencyNode node,
            Set<String> declaredGAs,
            Set<String> seen,
            List<AnalyzeTui.DepEntry> result,
            String pulledBy) {
        for (DependencyNode child : node.getChildren()) {
            if (child.getDependency() == null) continue;
            var art = child.getDependency().getArtifact();
            String ga = art.getGroupId() + ":" + art.getArtifactId();

            if (!declaredGAs.contains(ga) && seen.add(ga)) {
                String via = "";
                if (node.getDependency() != null) {
                    via = node.getDependency().getArtifact().getGroupId() + ":"
                            + node.getDependency().getArtifact().getArtifactId();
                }
                var entry = new AnalyzeTui.DepEntry(
                        art.getGroupId(),
                        art.getArtifactId(),
                        art.getVersion(),
                        child.getDependency().getScope(),
                        false);
                entry.pulledBy = via;
                result.add(entry);
            }

            collectTransitive(child, declaredGAs, seen, result, ga);
        }
    }

    private org.eclipse.aether.graph.Dependency convert(Dependency dep) {
        var artifact = new DefaultArtifact(
                dep.getGroupId(),
                dep.getArtifactId(),
                dep.getClassifier() != null ? dep.getClassifier() : "",
                dep.getType() != null ? dep.getType() : "jar",
                dep.getVersion());
        var d = new org.eclipse.aether.graph.Dependency(artifact, dep.getScope(), dep.isOptional());
        if (dep.getExclusions() != null && !dep.getExclusions().isEmpty()) {
            d = d.setExclusions(dep.getExclusions().stream()
                    .map(e -> new Exclusion(e.getGroupId(), e.getArtifactId(), "*", "*"))
                    .collect(Collectors.toList()));
        }
        return d;
    }
}
