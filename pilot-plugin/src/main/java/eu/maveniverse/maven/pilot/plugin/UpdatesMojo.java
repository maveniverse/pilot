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
package eu.maveniverse.maven.pilot.plugin;

import eu.maveniverse.maven.pilot.UpdatesTui;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Interactive TUI showing which dependencies have newer versions available.
 *
 * <p>Usage:</p>
 * <pre>
 * mvn pilot:updates
 * </pre>
 *
 * @since 0.1.0
 */
@Mojo(name = "updates", requiresProject = true, threadSafe = true)
public class UpdatesMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            // Build a map of raw (un-interpolated) versions from the original model
            Map<String, String> rawVersions = new java.util.HashMap<>();
            if (project.getOriginalModel().getDependencies() != null) {
                for (Dependency dep : project.getOriginalModel().getDependencies()) {
                    rawVersions.put(dep.getGroupId() + ":" + dep.getArtifactId(), dep.getVersion());
                }
            }
            if (project.getOriginalModel().getDependencyManagement() != null
                    && project.getOriginalModel().getDependencyManagement().getDependencies() != null) {
                for (Dependency dep :
                        project.getOriginalModel().getDependencyManagement().getDependencies()) {
                    rawVersions.put(dep.getGroupId() + ":" + dep.getArtifactId(), dep.getVersion());
                }
            }

            List<UpdatesTui.DependencyInfo> dependencies = new ArrayList<>();

            for (Dependency dep : project.getDependencies()) {
                var info = new UpdatesTui.DependencyInfo(
                        dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), dep.getScope(), dep.getType());
                setPropertyExpression(info, rawVersions);
                dependencies.add(info);
            }

            // Also include managed dependencies
            if (project.getDependencyManagement() != null) {
                for (Dependency dep : project.getDependencyManagement().getDependencies()) {
                    boolean alreadyListed = dependencies.stream()
                            .anyMatch(d ->
                                    d.groupId.equals(dep.getGroupId()) && d.artifactId.equals(dep.getArtifactId()));
                    if (!alreadyListed) {
                        var info = new UpdatesTui.DependencyInfo(
                                dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), dep.getScope(), dep.getType());
                        info.managed = true;
                        setPropertyExpression(info, rawVersions);
                        dependencies.add(info);
                    }
                }
            }

            // Collect plugins
            collectPlugins(dependencies, rawVersions);

            String pomPath = project.getFile().getAbsolutePath();
            String gav = project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion();

            UpdatesTui tui = new UpdatesTui(dependencies, pomPath, gav);
            tui.run();
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to check updates: " + e.getMessage(), e);
        }
    }

    private void collectPlugins(List<UpdatesTui.DependencyInfo> dependencies, Map<String, String> rawVersions) {
        // Collect raw plugin versions from original model
        if (project.getOriginalModel().getBuild() != null) {
            if (project.getOriginalModel().getBuild().getPlugins() != null) {
                for (Plugin p : project.getOriginalModel().getBuild().getPlugins()) {
                    if (p.getVersion() != null) {
                        rawVersions.put(p.getGroupId() + ":" + p.getArtifactId(), p.getVersion());
                    }
                }
            }
            if (project.getOriginalModel().getBuild().getPluginManagement() != null
                    && project.getOriginalModel()
                                    .getBuild()
                                    .getPluginManagement()
                                    .getPlugins()
                            != null) {
                for (Plugin p : project.getOriginalModel()
                        .getBuild()
                        .getPluginManagement()
                        .getPlugins()) {
                    if (p.getVersion() != null) {
                        rawVersions.put(p.getGroupId() + ":" + p.getArtifactId(), p.getVersion());
                    }
                }
            }
        }

        // Add build plugins with versions
        if (project.getBuild() != null && project.getBuild().getPlugins() != null) {
            for (Plugin p : project.getBuild().getPlugins()) {
                if (p.getVersion() == null || p.getVersion().isEmpty()) continue;
                var info = new UpdatesTui.DependencyInfo(
                        p.getGroupId(), p.getArtifactId(), p.getVersion(), "", "maven-plugin");
                info.plugin = true;
                setPropertyExpression(info, rawVersions);
                dependencies.add(info);
            }
        }

        // Add managed plugins
        if (project.getPluginManagement() != null
                && project.getPluginManagement().getPlugins() != null) {
            for (Plugin p : project.getPluginManagement().getPlugins()) {
                if (p.getVersion() == null || p.getVersion().isEmpty()) continue;
                boolean alreadyListed = dependencies.stream()
                        .anyMatch(d -> d.groupId.equals(p.getGroupId()) && d.artifactId.equals(p.getArtifactId()));
                if (!alreadyListed) {
                    var info = new UpdatesTui.DependencyInfo(
                            p.getGroupId(), p.getArtifactId(), p.getVersion(), "", "maven-plugin");
                    info.plugin = true;
                    info.managed = true;
                    setPropertyExpression(info, rawVersions);
                    dependencies.add(info);
                }
            }
        }
    }

    private static void setPropertyExpression(UpdatesTui.DependencyInfo info, Map<String, String> rawVersions) {
        String rawVersion = rawVersions.get(info.groupId + ":" + info.artifactId);
        if (rawVersion != null && rawVersion.startsWith("${") && rawVersion.endsWith("}")) {
            info.propertyExpression = rawVersion;
        }
    }
}
