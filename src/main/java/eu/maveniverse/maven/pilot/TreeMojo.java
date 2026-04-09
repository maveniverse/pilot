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
import org.eclipse.aether.collection.CollectResult;

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
@Mojo(name = "tree", requiresProject = true, aggregator = true, threadSafe = true)
public class TreeMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

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
            List<MavenProject> projects = session.getProjects();
            if (projects.size() > 1) {
                executeReactor(projects);
            } else {
                executeForProject(project);
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to display dependency tree: " + e.getMessage(), e);
        }
    }

    private void executeReactor(List<MavenProject> projects) throws Exception {
        ReactorModel reactorModel = ReactorModel.build(projects);
        MavenProject root = projects.get(0);
        String reactorGav = root.getGroupId() + ":" + root.getArtifactId() + ":" + root.getVersion();

        while (true) {
            MavenProject selected = new ModulePickerTui(reactorModel, reactorGav, "tree").pick();
            if (selected == null) break;
            executeForProject(selected);
        }
    }

    private void executeForProject(MavenProject proj) throws Exception {
        CollectResult result = repoSystem.collectDependencies(repoSession, MojoHelper.buildCollectRequest(proj));
        DependencyTreeModel model = DependencyTreeModel.fromDependencyNode(result.getRoot(), scope);
        TreeTui tui = new TreeTui(model, proj.getGroupId() + ":" + proj.getArtifactId() + ":" + proj.getVersion());
        tui.run();
    }
}
