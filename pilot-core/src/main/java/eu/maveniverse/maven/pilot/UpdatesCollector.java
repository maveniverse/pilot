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
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;

/**
 * Shared logic for collecting dependency and plugin update information
 * from a MavenProject. Used by both the CLI and plugin modules.
 */
public final class UpdatesCollector {

    private UpdatesCollector() {}

    /**
     * Collects all dependencies and plugins with their raw version expressions.
     */
    public static List<UpdatesTui.DependencyInfo> collect(MavenProject project) {
        Map<String, String> rawVersions = collectRawVersions(project);
        List<UpdatesTui.DependencyInfo> dependencies = new ArrayList<>();

        for (Dependency dep : project.getDependencies()) {
            var info = new UpdatesTui.DependencyInfo(
                    dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), dep.getScope(), dep.getType());
            setPropertyExpression(info, rawVersions);
            dependencies.add(info);
        }

        if (project.getDependencyManagement() != null) {
            for (Dependency dep : project.getDependencyManagement().getDependencies()) {
                boolean alreadyListed = dependencies.stream()
                        .anyMatch(d -> d.groupId.equals(dep.getGroupId()) && d.artifactId.equals(dep.getArtifactId()));
                if (!alreadyListed) {
                    var info = new UpdatesTui.DependencyInfo(
                            dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), dep.getScope(), dep.getType());
                    info.managed = true;
                    setPropertyExpression(info, rawVersions);
                    dependencies.add(info);
                }
            }
        }

        collectPlugins(project, dependencies, rawVersions);
        return dependencies;
    }

    private static Map<String, String> collectRawVersions(MavenProject project) {
        Map<String, String> rawVersions = new HashMap<>();
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
        return rawVersions;
    }

    private static void collectPlugins(
            MavenProject project, List<UpdatesTui.DependencyInfo> dependencies, Map<String, String> rawVersions) {
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

    static void setPropertyExpression(UpdatesTui.DependencyInfo info, Map<String, String> rawVersions) {
        String rawVersion = rawVersions.get(info.groupId + ":" + info.artifactId);
        if (rawVersion != null && rawVersion.startsWith("${") && rawVersion.endsWith("}")) {
            info.propertyExpression = rawVersion;
        }
    }
}
