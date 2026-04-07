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
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.graph.DependencyNode;

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

    /**
     * Performs dependency analysis: collects declared dependencies from the project POM, resolves the full transitive
     * dependency tree, identifies transitive (undeclared) dependencies, and presents the results using the Analyze TUI.
     *
     * @throws MojoExecutionException if dependency collection, traversal, or the TUI run fails
     */
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
            CollectResult result = repoSystem.collectDependencies(repoSession, MojoHelper.buildCollectRequest(project));

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

    /**
     * Traverse a resolved dependency tree and collect dependencies that are present in the tree
     * but not declared in the project's POM.
     *
     * For each discovered undeclared dependency this method adds an AnalyzeTui.DepEntry to
     * {@code result} and records its GA in {@code seen} to avoid duplicates.
     *
     * @param node the dependency tree node to traverse
     * @param declaredGAs set of declared `groupId:artifactId` values to skip
     * @param seen mutable set used to deduplicate discovered GAs; entries will be added as discovered
     * @param result mutable list that will be populated with undeclared dependency entries
     * @param pulledBy the `groupId:artifactId` of the dependency that pulled the current node (recursion context)
     */
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
}
