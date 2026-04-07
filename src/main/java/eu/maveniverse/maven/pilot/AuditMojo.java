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
@Mojo(name = "audit", requiresProject = true, threadSafe = true)
public class AuditMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Inject
    private RepositorySystem repoSystem;

    /**
     * Runs the interactive license and security audit dashboard for the current Maven project.
     *
     * Collects the project's transitive dependencies, builds audit entries, and launches the AuditTui UI.
     *
     * @throws MojoExecutionException if dependency collection or the audit UI fails to run
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            CollectResult result = repoSystem.collectDependencies(repoSession, MojoHelper.buildCollectRequest(project));

            // Collect all transitive dependencies
            List<AuditTui.AuditEntry> entries = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            collectEntries(result.getRoot(), entries, seen, true);

            String gav = project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion();
            AuditTui tui = new AuditTui(entries, gav);
            tui.run();
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to run audit: " + e.getMessage(), e);
        }
    }

    /**
     * Recursively walks the Maven dependency tree and appends unique non-root artifacts to the provided entries list.
     *
     * @param node the current dependency tree node to process
     * @param entries mutable list that will be populated with AuditTui.AuditEntry for each discovered artifact
     * @param seen a set of `"groupId:artifactId"` keys used to suppress duplicate artifacts across the tree
     * @param isRoot true when the current node is the root project node (the root node itself is not recorded)
     */
    private void collectEntries(
            DependencyNode node, List<AuditTui.AuditEntry> entries, Set<String> seen, boolean isRoot) {
        if (!isRoot && node.getDependency() != null) {
            var art = node.getDependency().getArtifact();
            String ga = art.getGroupId() + ":" + art.getArtifactId();
            if (seen.add(ga)) {
                entries.add(new AuditTui.AuditEntry(
                        art.getGroupId(), art.getArtifactId(),
                        art.getVersion(), node.getDependency().getScope()));
            }
        }
        for (DependencyNode child : node.getChildren()) {
            collectEntries(child, entries, seen, false);
        }
    }
}
