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
import org.eclipse.aether.graph.DependencyNode;

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
        CollectResult result = ResolutionProgress.resolve(
                "Collecting Dependencies",
                repoSession,
                ps -> repoSystem.collectDependencies(ps, MojoHelper.buildCollectRequest(proj)));
        DependencyTreeModel treeModel = MojoHelper.fromDependencyNode(result.getRoot());
        Map<String, AuditTui.AuditEntry> entryMap = new LinkedHashMap<>();
        collectEntries(result.getRoot(), entryMap, null, true);
        List<AuditTui.AuditEntry> entries = new ArrayList<>(entryMap.values());
        String gav = proj.getGroupId() + ":" + proj.getArtifactId() + ":" + proj.getVersion();
        String pomPath = proj.getFile().getAbsolutePath();
        AuditTui tui = new AuditTui(entries, gav, treeModel, pomPath);
        tui.runStandalone();
    }

    private void executeReactor(List<MavenProject> projects) throws Exception {
        MavenProject root = projects.get(0);
        List<CollectResult> collectResults = ResolutionProgress.resolve("Collecting Dependencies", repoSession, ps -> {
            List<CollectResult> results = new ArrayList<>();
            for (MavenProject proj : projects) {
                results.add(repoSystem.collectDependencies(ps, MojoHelper.buildCollectRequest(proj)));
            }
            return results;
        });
        DependencyTreeModel treeModel =
                MojoHelper.fromDependencyNode(collectResults.get(0).getRoot());

        Map<String, AuditTui.AuditEntry> entryMap = new LinkedHashMap<>();
        for (int i = 0; i < projects.size(); i++) {
            collectEntries(
                    collectResults.get(i).getRoot(), entryMap, projects.get(i).getArtifactId(), true);
        }
        List<AuditTui.AuditEntry> entries = new ArrayList<>(entryMap.values());

        String gav = root.getGroupId() + ":" + root.getArtifactId() + ":" + root.getVersion();
        String pomPath = root.getFile().getAbsolutePath();
        AuditTui tui = new AuditTui(entries, gav + " (reactor: " + projects.size() + " modules)", treeModel, pomPath);
        tui.runStandalone();
    }

    private static void collectEntries(
            DependencyNode node, Map<String, AuditTui.AuditEntry> entryMap, String moduleName, boolean isRoot) {
        if (!isRoot && node.getDependency() != null) {
            var art = node.getDependency().getArtifact();
            String ga = art.getGroupId() + ":" + art.getArtifactId();
            AuditTui.AuditEntry entry = entryMap.computeIfAbsent(
                    ga,
                    k -> new AuditTui.AuditEntry(
                            art.getGroupId(), art.getArtifactId(),
                            art.getVersion(), node.getDependency().getScope()));
            if (moduleName != null && !entry.modules.contains(moduleName)) {
                entry.modules.add(moduleName);
            }
        }
        for (DependencyNode child : node.getChildren()) {
            collectEntries(child, entryMap, moduleName, false);
        }
    }
}
