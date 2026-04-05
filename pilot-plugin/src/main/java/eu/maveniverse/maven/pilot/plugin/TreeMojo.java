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

import eu.maveniverse.maven.pilot.DependencyTreeModel;
import eu.maveniverse.maven.pilot.TreeTui;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
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
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;

/**
 * Interactive TUI for browsing the project dependency tree.
 *
 * <p>Usage:</p>
 * <pre>
 * mvn pilot:tree
 * mvn pilot:tree -Dscope=compile
 * </pre>
 *
 * @since 0.1.0
 */
@Mojo(name = "tree", requiresProject = true, threadSafe = true)
public class TreeMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Inject
    private RepositorySystem repoSystem;

    /**
     * The dependency scope to display. One of: compile, runtime, test.
     */
    @Parameter(property = "scope", defaultValue = "compile")
    private String scope;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            CollectRequest collectRequest = new CollectRequest();

            org.eclipse.aether.artifact.Artifact rootArtifact = new DefaultArtifact(
                    project.getGroupId(), project.getArtifactId(),
                    project.getPackaging(), project.getVersion());
            collectRequest.setRootArtifact(rootArtifact);

            List<Dependency> dependencies = convertDependencies(project.getDependencies());
            collectRequest.setDependencies(dependencies);

            if (project.getDependencyManagement() != null) {
                collectRequest.setManagedDependencies(
                        convertDependencies(project.getDependencyManagement().getDependencies()));
            }

            collectRequest.setRepositories(project.getRemoteProjectRepositories());

            CollectResult result = repoSystem.collectDependencies(repoSession, collectRequest);
            DependencyTreeModel model = DependencyNodeConverter.convert(result.getRoot());

            TreeTui tui = new TreeTui(
                    model, project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion());
            tui.run();
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to display dependency tree: " + e.getMessage(), e);
        }
    }

    private List<Dependency> convertDependencies(List<org.apache.maven.model.Dependency> deps) {
        if (deps == null) {
            return Collections.emptyList();
        }
        return deps.stream().map(this::convertDependency).collect(Collectors.toList());
    }

    private Dependency convertDependency(org.apache.maven.model.Dependency dep) {
        org.eclipse.aether.artifact.Artifact artifact = new DefaultArtifact(
                dep.getGroupId(),
                dep.getArtifactId(),
                dep.getClassifier() != null ? dep.getClassifier() : "",
                dep.getType() != null ? dep.getType() : "jar",
                dep.getVersion());

        Dependency aetherDep = new Dependency(artifact, dep.getScope(), dep.isOptional());

        if (dep.getExclusions() != null && !dep.getExclusions().isEmpty()) {
            List<Exclusion> exclusions = dep.getExclusions().stream()
                    .map(e -> new Exclusion(e.getGroupId(), e.getArtifactId(), "*", "*"))
                    .collect(Collectors.toList());
            aetherDep = aetherDep.setExclusions(exclusions);
        }

        return aetherDep;
    }
}
