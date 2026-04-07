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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.graph.DependencyNode;

/**
 * Interactive TUI for dependency conflict resolution.
 *
 * <p>Usage:</p>
 * <pre>
 * mvn pilot:conflicts
 * </pre>
 *
 * @since 0.1.0
 */
@Mojo(name = "conflicts", requiresProject = true, threadSafe = true)
public class ConflictsMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Inject
    private RepositorySystem repoSystem;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            CollectResult result = repoSystem.collectDependencies(repoSession, MojoHelper.buildCollectRequest(project));

            // Detect conflicts: same GA with different versions requested
            Map<String, List<ConflictsTui.ConflictEntry>> conflictMap = new HashMap<>();
            collectConflicts(result.getRoot(), conflictMap, new ArrayList<>());

            List<ConflictsTui.ConflictGroup> conflicts = conflictMap.entrySet().stream()
                    .filter(e -> e.getValue().size() > 1
                            || e.getValue().stream()
                                    .anyMatch(c -> c.requestedVersion != null
                                            && !c.requestedVersion.equals(c.resolvedVersion)))
                    .map(e -> new ConflictsTui.ConflictGroup(e.getKey(), e.getValue()))
                    .collect(Collectors.toList());

            String pomPath = project.getFile().getAbsolutePath();
            String gav = project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion();

            ConflictsTui tui = new ConflictsTui(conflicts, pomPath, gav);
            tui.run();
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to analyze conflicts: " + e.getMessage(), e);
        }
    }

    private void collectConflicts(
            DependencyNode node, Map<String, List<ConflictsTui.ConflictEntry>> conflicts, List<String> path) {
        for (DependencyNode child : node.getChildren()) {
            if (child.getDependency() == null) continue;
            var art = child.getDependency().getArtifact();
            String ga = art.getGroupId() + ":" + art.getArtifactId();

            String requestedVersion = art.getVersion();
            String resolvedVersion = requestedVersion;

            // Check if there's version conflict data
            if (child.getData().get("conflict.originalVersion") instanceof String original) {
                requestedVersion = original;
            }

            List<String> currentPath = new ArrayList<>(path);
            currentPath.add(ga);

            var entry = new ConflictsTui.ConflictEntry(
                    art.getGroupId(),
                    art.getArtifactId(),
                    requestedVersion,
                    resolvedVersion,
                    String.join(" \u2192 ", currentPath),
                    child.getDependency().getScope());

            conflicts.computeIfAbsent(ga, k -> new ArrayList<>()).add(entry);
            collectConflicts(child, conflicts, currentPath);
        }
    }
}
