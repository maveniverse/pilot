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

import eu.maveniverse.maven.pilot.PilotProject;
import eu.maveniverse.maven.pilot.PluginsReporter;
import eu.maveniverse.maven.pilot.UpdatesTui;
import java.util.List;
import javax.inject.Inject;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;

/**
 * Plugin updates checker — reports declared and managed Maven plugins with available version updates.
 *
 * <p>Two actions via {@code -Dpilot.action}:</p>
 * <ul>
 *   <li><b>report</b> (default) — prints a text summary to the console, exits 0 even when updates exist</li>
 *   <li><b>check</b> — prints a text summary and fails the build if any plugin updates are found</li>
 * </ul>
 *
 * <p>In a multi-module reactor, aggregates plugins across all modules.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * mvn pilot:plugins
 * mvn pilot:plugins -Dpilot.action=check
 * </pre>
 *
 * @since 0.3.0
 */
@Mojo(name = "plugins", requiresProject = true, aggregator = true, threadSafe = true)
public class PluginsMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    /**
     * Action to perform: {@code report} (default) prints plugin updates as plain text (exits 0);
     * {@code check} prints updates and fails the build if any are found.
     */
    @Parameter(property = "pilot.action", defaultValue = "report")
    String action = "report";

    private final RepositorySystem repoSystem;

    @Inject
    PluginsMojo(RepositorySystem repoSystem) {
        this.repoSystem = repoSystem;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!"report".equals(action) && !"check".equals(action)) {
            throw new MojoExecutionException("Invalid action '" + action + "'. Supported values: report, check.");
        }
        try {
            List<PilotProject> projects = MojoHelper.toPilotProjects(session.getProjects());
            UpdatesTui.VersionResolver versionResolver = createVersionResolver();
            executeNonInteractive(projects, versionResolver, projects.get(0).gav());
        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to check plugin updates: " + e.getMessage(), e);
        }
    }

    void executeNonInteractive(
            List<PilotProject> projects, UpdatesTui.VersionResolver versionResolver, String projectGav)
            throws MojoFailureException {
        PluginsReporter.CheckResult result = PluginsReporter.resolveAndCheck(projects, versionResolver, projectGav);
        getLog().info("\n" + result.report());

        if ("check".equals(action) && (!result.updates().isEmpty() || !result.isComplete())) {
            throw new MojoFailureException(result.formatFailure());
        }
    }

    private UpdatesTui.VersionResolver createVersionResolver() {
        return (groupId, artifactId) -> {
            try {
                VersionRangeRequest request = new VersionRangeRequest();
                request.setArtifact(new DefaultArtifact(groupId, artifactId, "jar", "[0,)"));
                request.setRepositories(project.getRemotePluginRepositories());
                VersionRangeResult result = repoSystem.resolveVersionRange(repoSession, request);
                return UpdatesTui.versionsNewestFirst(result.getVersions());
            } catch (VersionRangeResolutionException e) {
                throw new IllegalStateException("Failed to resolve versions for " + groupId + ":" + artifactId, e);
            }
        };
    }
}
