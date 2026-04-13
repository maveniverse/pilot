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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Interactive license and security audit dashboard.
 *
 * <p>Usage:</p>
 * <pre>
 * mvn pilot:audit
 * </pre>
 *
 * @since 0.1.0
 */
@Mojo(name = "audit", requiresProject = true, aggregator = true, threadSafe = true)
public class AuditMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Inject
    private RepositorySystem repoSystem;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            List<MavenProject> projects = session.getProjects();
            if (projects.size() > 1) {
                executeReactor(projects);
            } else {
                executeSingleProject(project);
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to run audit: " + e.getMessage(), e);
        }
    }

    private void executeSingleProject(MavenProject proj) throws Exception {
        CollectResult result = repoSystem.collectDependencies(repoSession, MojoHelper.buildCollectRequest(proj));
        DependencyTreeModel treeModel = DependencyTreeModel.fromDependencyNode(result.getRoot());
        List<AuditTui.AuditEntry> entries = AuditTui.collectEntries(result.getRoot());
        String gav = proj.getGroupId() + ":" + proj.getArtifactId() + ":" + proj.getVersion();
        String pomPath = proj.getFile().getAbsolutePath();
        AuditTui tui = new AuditTui(entries, gav, treeModel, pomPath);
        tui.run();
    }

    private void executeReactor(List<MavenProject> projects) throws Exception {
        // Aggregate all transitive deps across modules, deduped by GA
        // Use root project's tree for dependency path tracing
        MavenProject root = projects.get(0);
        CollectResult rootResult = repoSystem.collectDependencies(repoSession, MojoHelper.buildCollectRequest(root));
        DependencyTreeModel treeModel = DependencyTreeModel.fromDependencyNode(rootResult.getRoot());

        Map<String, AuditTui.AuditEntry> entryMap = new LinkedHashMap<>();
        for (MavenProject proj : projects) {
            CollectResult result = repoSystem.collectDependencies(repoSession, MojoHelper.buildCollectRequest(proj));
            AuditTui.collectEntries(result.getRoot(), entryMap, proj.getArtifactId(), true);
        }
        List<AuditTui.AuditEntry> entries = new ArrayList<>(entryMap.values());

        String gav = root.getGroupId() + ":" + root.getArtifactId() + ":" + root.getVersion();
        String pomPath = root.getFile().getAbsolutePath();
        AuditTui tui = new AuditTui(entries, gav + " (reactor: " + projects.size() + " modules)", treeModel, pomPath);
        tui.run();
    }
}
