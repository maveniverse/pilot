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
import java.util.IdentityHashMap;
import java.util.List;
import javax.inject.Inject;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.transfer.AbstractTransferListener;

/**
 * Interactive launcher that displays the reactor module tree and lets the user
 * pick a tool to run on a selected module.
 *
 * <p>For single-module projects, shows the tool picker directly.
 * For multi-module reactors, shows the module tree first, then the tool picker.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * mvn pilot:pilot
 * </pre>
 *
 * @since 0.2.0
 */
@Mojo(name = "pilot", requiresProject = true, aggregator = true, threadSafe = true)
public class PilotMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Inject
    private RepositorySystem repoSystem;

    @Parameter(property = "scope", defaultValue = "compile")
    private String scope;

    private final IdentityHashMap<MavenProject, PilotProject> mavenToPilot = new IdentityHashMap<>();
    private final IdentityHashMap<PilotProject, MavenProject> pilotToMaven = new IdentityHashMap<>();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        // Suppress transfer progress output to prevent corruption of TUI rendering
        DefaultRepositorySystemSession quietSession = new DefaultRepositorySystemSession(repoSession);
        quietSession.setTransferListener(new AbstractTransferListener() {});
        this.repoSession = quietSession;

        try {
            List<MavenProject> mavenProjects = session.getProjects();
            List<PilotProject> projects =
                    mavenProjects.stream().map(this::toPilotProject).toList();

            PilotResolver resolver = new Maven3PilotResolver(repoSystem, repoSession, project, pilotToMaven);
            PilotEngine engine = new PilotEngine(resolver, projects, scope);

            ReactorModel reactorModel = projects.size() > 1 ? ReactorModel.build(projects) : null;
            new PilotShell(
                            reactorModel,
                            projects,
                            (toolId, proj, scope2, session, sessionProvider, progress) ->
                                    engine.createPanel(toolId, proj, scope2, session, sessionProvider, progress))
                    .run();
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to run pilot: " + e.getMessage(), e);
        }
    }

    PilotProject toPilotProject(MavenProject mp) {
        PilotProject cached = mavenToPilot.get(mp);
        if (cached != null) return cached;

        PilotProject pp = MojoHelper.toPilotProject(mp, mavenToPilot);
        pilotToMaven.put(pp, mp);

        // Also map parents into pilotToMaven
        PilotProject current = pp;
        MavenProject currentMp = mp;
        while (currentMp.getParent() != null && current.parent != null) {
            currentMp = currentMp.getParent();
            current = current.parent;
            pilotToMaven.putIfAbsent(current, currentMp);
        }

        return pp;
    }
}
