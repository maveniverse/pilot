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
 * License and security audit dashboard.
 *
 * <p>Three actions via {@code -Dpilot.action}:</p>
 * <ul>
 *   <li><b>tui</b> (default) — interactive TUI dashboard</li>
 *   <li><b>report</b> — prints a text report to the console</li>
 *   <li><b>check</b> — prints a report and fails the build if vulnerabilities
 *       at or above the configured severity threshold are found</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>
 * mvn pilot:audit
 * mvn pilot:audit -Dpilot.action=report
 * mvn pilot:audit -Dpilot.action=check
 * mvn pilot:audit -Dpilot.action=check -Dpilot.audit.severity=CRITICAL
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

    @Parameter(property = "pilot.action", defaultValue = "tui")
    String action = "tui";

    @Parameter(property = "pilot.audit.severity", defaultValue = "HIGH")
    String severityThreshold = "HIGH";

    @Inject
    private RepositorySystem repoSystem;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!"tui".equals(action) && !"report".equals(action) && !"check".equals(action)) {
            throw new MojoExecutionException("Invalid action '" + action + "'. Use 'tui', 'report', or 'check'.");
        }
        try {
            List<MavenProject> projects = session.getProjects();
            Map<String, AuditTui.AuditEntry> entryMap = new LinkedHashMap<>();
            DependencyTreeModel treeModel = null;

            if (projects.size() > 1) {
                MavenProject root = projects.get(0);
                CollectResult rootResult =
                        repoSystem.collectDependencies(repoSession, MojoHelper.buildCollectRequest(root));
                treeModel = MojoHelper.fromDependencyNode(rootResult.getRoot());
                for (MavenProject proj : projects) {
                    CollectResult result =
                            repoSystem.collectDependencies(repoSession, MojoHelper.buildCollectRequest(proj));
                    collectEntries(result.getRoot(), entryMap, proj.getArtifactId(), true);
                }
            } else {
                CollectResult result =
                        repoSystem.collectDependencies(repoSession, MojoHelper.buildCollectRequest(project));
                treeModel = MojoHelper.fromDependencyNode(result.getRoot());
                collectEntries(result.getRoot(), entryMap, null, true);
            }

            List<AuditTui.AuditEntry> entries = new ArrayList<>(entryMap.values());
            String gav = project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion();
            if (projects.size() > 1) {
                gav += " (reactor: " + projects.size() + " modules)";
            }

            if ("tui".equals(action)) {
                String pomPath = project.getFile().getAbsolutePath();
                AuditTui tui = new AuditTui(entries, gav, treeModel, pomPath);
                tui.runStandalone();
            } else {
                executeNonInteractive(entries);
            }
        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to run audit: " + e.getMessage(), e);
        }
    }

    void executeNonInteractive(List<AuditTui.AuditEntry> entries) throws MojoFailureException {
        AuditReporter.fetchData(entries);

        String report = AuditReporter.formatReport(entries);

        if ("report".equals(action)) {
            getLog().info("\n" + report);
        } else {
            getLog().info("\n" + report);
            int count = AuditReporter.countVulnerabilitiesAtOrAbove(entries, severityThreshold);
            if (count > 0) {
                throw new MojoFailureException(AuditReporter.formatCheckFailure(entries, severityThreshold));
            }
            getLog().info("Audit check passed: no vulnerabilities at severity " + severityThreshold + " or above.");
        }
    }

    private static void collectEntries(
            DependencyNode node, Map<String, AuditTui.AuditEntry> entryMap, String moduleName, boolean isRoot) {
        if (!isRoot && node.getDependency() != null) {
            var art = node.getDependency().getArtifact();
            String ga = art.getGroupId() + ":" + art.getArtifactId();
            AuditTui.AuditEntry entry = entryMap.computeIfAbsent(
                    ga,
                    k -> new AuditTui.AuditEntry(
                            art.getGroupId(),
                            art.getArtifactId(),
                            art.getVersion(),
                            node.getDependency().getScope()));
            if (moduleName != null && !entry.modules.contains(moduleName)) {
                entry.modules.add(moduleName);
            }
        }
        for (DependencyNode child : node.getChildren()) {
            collectEntries(child, entryMap, moduleName, false);
        }
    }
}
