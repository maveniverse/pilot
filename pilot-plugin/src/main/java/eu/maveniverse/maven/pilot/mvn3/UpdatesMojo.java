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
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.transfer.AbstractTransferListener;

/**
 * Interactive TUI showing which dependencies have newer versions available.
 *
 * <p>In a multi-module reactor, aggregates dependencies across all modules,
 * groups by shared version properties, and applies updates to the correct POM.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * mvn pilot:updates
 * </pre>
 *
 * @since 0.1.0
 */
@Mojo(name = "updates", requiresProject = true, aggregator = true, threadSafe = true)
public class UpdatesMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    private final RepositorySystem repoSystem;

    @Inject
    UpdatesMojo(RepositorySystem repoSystem) {
        this.repoSystem = repoSystem;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            UpdatesTui.VersionResolver versionResolver = createVersionResolver();
            List<MavenProject> projects = session.getProjects();

            if (projects.size() > 1) {
                executeReactor(projects, versionResolver);
            } else {
                executeSingleProject(versionResolver);
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to check updates: " + e.getMessage(), e);
        }
    }

    private void executeSingleProject(UpdatesTui.VersionResolver versionResolver) throws Exception {
        List<UpdatesTui.DependencyInfo> dependencies = new ArrayList<>();

        for (Dependency dep : project.getDependencies()) {
            dependencies.add(new UpdatesTui.DependencyInfo(
                    dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), dep.getScope(), dep.getType()));
        }

        if (project.getDependencyManagement() != null) {
            for (Dependency dep : project.getDependencyManagement().getDependencies()) {
                boolean alreadyListed = dependencies.stream()
                        .anyMatch(d -> d.groupId.equals(dep.getGroupId()) && d.artifactId.equals(dep.getArtifactId()));
                if (!alreadyListed) {
                    var info = new UpdatesTui.DependencyInfo(
                            dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), dep.getScope(), dep.getType());
                    info.managed = true;
                    dependencies.add(info);
                }
            }
        }

        String pomPath = project.getFile().getAbsolutePath();
        String gav = project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion();

        UpdatesTui tui = new UpdatesTui(dependencies, pomPath, gav, versionResolver);
        tui.runStandalone();
    }

    private void executeReactor(List<MavenProject> mavenProjects, UpdatesTui.VersionResolver versionResolver)
            throws Exception {
        List<PilotProject> projects = MojoHelper.toPilotProjects(mavenProjects);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(projects);
        ReactorModel reactorModel = ReactorModel.build(projects);

        PilotProject root = projects.get(0);
        String reactorGav = root.gav();

        ReactorUpdatesTui tui = new ReactorUpdatesTui(result, reactorModel, reactorGav, versionResolver);
        tui.runStandalone();
    }

    private UpdatesTui.VersionResolver createVersionResolver() {
        DefaultRepositorySystemSession quietSession = new DefaultRepositorySystemSession(repoSession);
        quietSession.setTransferListener(new AbstractTransferListener() {});
        return (groupId, artifactId) -> {
            try {
                VersionRangeRequest request = new VersionRangeRequest();
                request.setArtifact(new DefaultArtifact(groupId, artifactId, "jar", "[0,)"));
                request.setRepositories(project.getRemoteProjectRepositories());
                VersionRangeResult result = repoSystem.resolveVersionRange(quietSession, request);
                return UpdatesTui.versionsNewestFirst(result.getVersions());
            } catch (VersionRangeResolutionException e) {
                throw new IllegalStateException("Failed to resolve versions for " + groupId + ":" + artifactId, e);
            }
        };
    }
}
