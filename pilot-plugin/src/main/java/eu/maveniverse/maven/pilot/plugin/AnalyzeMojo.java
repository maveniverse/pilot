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
package eu.maveniverse.maven.pilot.plugin;

import eu.maveniverse.maven.pilot.AnalyzeTui;
import eu.maveniverse.maven.pilot.DependencyCollectors;
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
            CollectResult result = repoSystem.collectDependencies(repoSession, MojoHelper.buildCollectRequest(project));

            // Find transitive (undeclared) dependencies
            List<AnalyzeTui.DepEntry> transitive =
                    DependencyCollectors.collectTransitive(result.getRoot(), declaredGAs);

            String pomPath = project.getFile().getAbsolutePath();
            AnalyzeTui tui = new AnalyzeTui(declared, transitive, pomPath, MojoHelper.projectGav(project));
            tui.run();
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to analyze dependencies: " + e.getMessage(), e);
        }
    }
}
