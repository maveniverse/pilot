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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
import org.eclipse.aether.util.graph.manager.DependencyManagerUtils;

/**
 * Detect version conflicts across the dependency tree — report or check mode.
 *
 * <p>Two actions via {@code -Dpilot.action}:</p>
 * <ul>
 *   <li><b>report</b> (default) — prints conflicts as plain text, exits 0 even when conflicts exist</li>
 *   <li><b>check</b> — prints conflicts and fails the build when any are found</li>
 * </ul>
 *
 * <p>For interactive conflict resolution (pinning versions to {@code dependencyManagement}),
 * use {@code pilot:pilot} instead.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * mvn pilot:conflicts
 * mvn pilot:conflicts -Dpilot.action=check
 * </pre>
 *
 * @since 0.1.0
 */
@Mojo(name = "conflicts", requiresProject = true, aggregator = true, threadSafe = true)
public class ConflictsMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Inject
    private RepositorySystem repoSystem;

    /**
     * Action to perform: {@code report} (default) prints conflicts as plain text (exits 0);
     * {@code check} prints conflicts and fails the build if any are found.
     */
    @Parameter(property = "pilot.action", defaultValue = "report")
    String action = "report";

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!"report".equals(action) && !"check".equals(action)) {
            throw new MojoExecutionException("Invalid action '" + action + "'. Supported values: report, check.");
        }
        try {
            List<MavenProject> projects = session.getProjects();
            if (projects.size() > 1) {
                executeReactor(projects);
            } else {
                executeSingleProject(project);
            }
        } catch (MojoExecutionException | MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to analyze conflicts: " + e.getMessage(), e);
        }
    }

    private void executeSingleProject(MavenProject proj) throws Exception {
        List<ConflictsTui.ConflictGroup> conflicts = collectConflictsForProject(proj);
        String gav = proj.getGroupId() + ":" + proj.getArtifactId() + ":" + proj.getVersion();
        executeNonInteractive(conflicts, gav);
    }

    private void executeReactor(List<MavenProject> projects) throws Exception {
        Map<String, List<ConflictsTui.ConflictEntry>> mergedMap = new HashMap<>();
        for (MavenProject proj : projects) {
            CollectResult result =
                    repoSystem.collectDependencies(repoSession, MojoHelper.buildCollectRequest(proj, repoSession));
            collectConflicts(result.getRoot(), mergedMap, new ArrayList<>(), proj.getArtifactId());
        }

        List<ConflictsTui.ConflictGroup> conflicts = mergedMap.entrySet().stream()
                .filter(e -> e.getValue().size() > 1
                        || e.getValue().stream()
                                .anyMatch(c ->
                                        c.requestedVersion != null && !c.requestedVersion.equals(c.resolvedVersion)))
                .map(e -> new ConflictsTui.ConflictGroup(e.getKey(), e.getValue()))
                .collect(Collectors.toList());

        MavenProject root = projects.get(0);
        String gav = root.getGroupId() + ":" + root.getArtifactId() + ":" + root.getVersion() + " (reactor: "
                + projects.size() + " modules)";

        executeNonInteractive(conflicts, gav);
    }

    private void executeNonInteractive(List<ConflictsTui.ConflictGroup> conflicts, String gav)
            throws MojoFailureException {
        // Retain only groups with a real version conflict: either multiple distinct requested
        // versions, or at least one entry where the requested version differs from the resolved one.
        conflicts = conflicts.stream()
                .filter(group -> group.entries.stream()
                                        .map(e -> e.requestedVersion)
                                        .distinct()
                                        .limit(2)
                                        .count()
                                > 1
                        || group.entries.stream().anyMatch(e -> !e.requestedVersion.equals(e.resolvedVersion)))
                .toList();

        if (conflicts.isEmpty()) {
            getLog().info("No dependency conflicts found in " + gav + ".");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Dependency conflicts in ").append(gav).append(":\n");
        for (ConflictsTui.ConflictGroup group : conflicts) {
            sb.append("\n  ").append(group.ga).append(":\n");
            for (ConflictsTui.ConflictEntry entry : group.entries) {
                sb.append("    - requested ")
                        .append(entry.requestedVersion)
                        .append(", resolved ")
                        .append(entry.resolvedVersion);
                if (!entry.requestedVersion.equals(entry.resolvedVersion)) {
                    sb.append(" [CONFLICT]");
                }
                sb.append("\n");
                sb.append("      via: ").append(entry.path).append("\n");
            }
        }

        if ("check".equals(action)) {
            throw new MojoFailureException(sb.toString());
        } else {
            getLog().info(sb.toString());
        }
    }

    private List<ConflictsTui.ConflictGroup> collectConflictsForProject(MavenProject proj) throws Exception {
        CollectResult result =
                repoSystem.collectDependencies(repoSession, MojoHelper.buildCollectRequest(proj, repoSession));
        Map<String, List<ConflictsTui.ConflictEntry>> conflictMap = new HashMap<>();
        collectConflicts(result.getRoot(), conflictMap, new ArrayList<>());
        return conflictMap.entrySet().stream()
                .filter(e -> e.getValue().size() > 1
                        || e.getValue().stream()
                                .anyMatch(c ->
                                        c.requestedVersion != null && !c.requestedVersion.equals(c.resolvedVersion)))
                .map(e -> new ConflictsTui.ConflictGroup(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    /**
     * Recursively traverses a dependency subtree and records each dependency occurrence keyed by its
     * "groupId:artifactId" (GA), including a human-readable path to that occurrence.
     *
     * Each recorded entry captures groupId, artifactId, the requested version (using
     * {@link DependencyManagerUtils#getPremanagedVersion(DependencyNode)} when a
     * dependency-management override is present), the resolved version, the dependency scope, and the
     * path from the project root joined with " → ".
     *
     * @param node the current dependency node whose children will be processed
     * @param conflicts a map from GA ("groupId:artifactId") to a list of ConflictEntry occurrences
     * @param path the GA path from the project root to the parent of {@code node}; the method appends the
     *             current child GA when recording entries
     */
    // package-private for testing
    void collectConflicts(
            DependencyNode node, Map<String, List<ConflictsTui.ConflictEntry>> conflicts, List<String> path) {
        for (DependencyNode child : node.getChildren()) {
            if (child.getDependency() == null) continue;
            var art = child.getDependency().getArtifact();
            String ga = art.getGroupId() + ":" + art.getArtifactId();

            String resolvedVersion = art.getVersion();
            String requestedVersion = resolvedVersion;

            // Detect dependency-management overrides: the ClassicDependencyManager records the
            // pre-management version via DependencyManagerUtils when it overrides a version.
            String premanagedVersion = DependencyManagerUtils.getPremanagedVersion(child);
            if (premanagedVersion != null && !premanagedVersion.equals(resolvedVersion)) {
                requestedVersion = premanagedVersion;
            }

            List<String> currentPath = new ArrayList<>(path);
            currentPath.add(ga);

            var entry = new ConflictsTui.ConflictEntry(
                    art.getGroupId(),
                    art.getArtifactId(),
                    requestedVersion,
                    resolvedVersion,
                    String.join(" → ", currentPath),
                    child.getDependency().getScope());

            conflicts.computeIfAbsent(ga, k -> new ArrayList<>()).add(entry);
            collectConflicts(child, conflicts, currentPath);
        }
    }

    private void collectConflicts(
            DependencyNode node,
            Map<String, List<ConflictsTui.ConflictEntry>> conflicts,
            List<String> path,
            String moduleName) {
        List<String> modulePath = new ArrayList<>();
        modulePath.add("[" + moduleName + "]");
        modulePath.addAll(path);
        collectConflicts(node, conflicts, modulePath);
    }
}
