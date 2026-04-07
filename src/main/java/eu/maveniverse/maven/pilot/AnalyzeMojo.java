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

/**
 * Interactive TUI showing declared vs transitive dependency overview.
 *
 * <p>Displays two views: declared dependencies (from the POM) and transitive
 * dependencies (pulled in indirectly). Allows promoting transitive dependencies
 * to declared, or removing declared dependencies from the POM.</p>
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
     * Collects declared dependencies from the project POM, resolves the full transitive
     * dependency tree, and presents a side-by-side view of declared vs transitive dependencies
     * using the Analyze TUI.
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
                AnalyzeTui.addDeclaredEntry(
                        declaredGAs,
                        declared,
                        dep.getGroupId(),
                        dep.getArtifactId(),
                        dep.getClassifier(),
                        dep.getVersion(),
                        dep.getScope());
            }

            // Resolve full transitive tree
            CollectResult result = repoSystem.collectDependencies(repoSession, MojoHelper.buildCollectRequest(project));

            // Find transitive (undeclared) dependencies
            Set<String> transitiveGAs = new HashSet<>();
            List<AnalyzeTui.DepEntry> transitive = new ArrayList<>();
            AnalyzeTui.collectTransitive(result.getRoot(), declaredGAs, transitiveGAs, transitive);

            String pomPath = project.getFile().getAbsolutePath();
            String gav = project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion();

            AnalyzeTui tui = new AnalyzeTui(declared, transitive, pomPath, gav);
            tui.run();
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to analyze dependencies: " + e.getMessage(), e);
        }
    }
}
