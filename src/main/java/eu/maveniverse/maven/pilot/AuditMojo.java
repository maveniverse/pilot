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
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.Exclusion;

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

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            CollectRequest collectRequest = new CollectRequest();
            collectRequest.setRootArtifact(new DefaultArtifact(
                    project.getGroupId(), project.getArtifactId(),
                    project.getPackaging(), project.getVersion()));
            collectRequest.setDependencies(
                    project.getDependencies().stream().map(this::convert).collect(Collectors.toList()));
            if (project.getDependencyManagement() != null) {
                collectRequest.setManagedDependencies(project.getDependencyManagement().getDependencies().stream()
                        .map(this::convert)
                        .collect(Collectors.toList()));
            }
            collectRequest.setRepositories(project.getRemoteProjectRepositories());

            CollectResult result = repoSystem.collectDependencies(repoSession, collectRequest);

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

    private Dependency convert(org.apache.maven.model.Dependency dep) {
        var artifact = new DefaultArtifact(
                dep.getGroupId(),
                dep.getArtifactId(),
                dep.getClassifier() != null ? dep.getClassifier() : "",
                dep.getType() != null ? dep.getType() : "jar",
                dep.getVersion());
        var d = new Dependency(artifact, dep.getScope(), dep.isOptional());
        if (dep.getExclusions() != null && !dep.getExclusions().isEmpty()) {
            d = d.setExclusions(dep.getExclusions().stream()
                    .map(e -> new Exclusion(e.getGroupId(), e.getArtifactId(), "*", "*"))
                    .collect(Collectors.toList()));
        }
        return d;
    }
}
