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
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.project.MavenProject;

/**
 * Collects and aggregates dependencies across all reactor modules.
 *
 * <p>Deduplicates by groupId:artifactId, tracks which modules use each dependency,
 * detects property-based version expressions, and identifies which POM defines the property.</p>
 */
class ReactorCollector {

    private static final Pattern PROPERTY_PATTERN = Pattern.compile("\\$\\{(.+?)\\}");

    static class ModuleUsage {
        final MavenProject project;
        final String version;
        final String scope;
        final boolean managed;

        ModuleUsage(MavenProject project, String version, String scope, boolean managed) {
            this.project = project;
            this.version = version;
            this.scope = scope;
            this.managed = managed;
        }
    }

    static class AggregatedDependency {
        final String groupId;
        final String artifactId;
        final List<ModuleUsage> usages = new ArrayList<>();
        String primaryVersion;
        String rawVersionExpr;
        String propertyName;
        MavenProject propertyOrigin;

        String newestVersion;
        VersionComparator.UpdateType updateType;
        boolean selected;

        AggregatedDependency(String groupId, String artifactId) {
            this.groupId = groupId;
            this.artifactId = artifactId;
        }

        String ga() {
            return groupId + ":" + artifactId;
        }

        boolean hasUpdate() {
            return newestVersion != null && !newestVersion.equals(primaryVersion) && !newestVersion.isEmpty();
        }

        boolean isPropertyManaged() {
            return propertyName != null;
        }

        int moduleCount() {
            return usages.size();
        }
    }

    static class PropertyGroup {
        final String propertyName;
        final String rawExpression;
        String resolvedVersion;
        final MavenProject origin;
        final List<AggregatedDependency> dependencies = new ArrayList<>();

        String newestVersion;
        VersionComparator.UpdateType updateType;
        boolean selected;

        PropertyGroup(String propertyName, String rawExpression, String resolvedVersion, MavenProject origin) {
            this.propertyName = propertyName;
            this.rawExpression = rawExpression;
            this.resolvedVersion = resolvedVersion;
            this.origin = origin;
        }

        boolean hasUpdate() {
            return newestVersion != null && !newestVersion.equals(resolvedVersion) && !newestVersion.isEmpty();
        }

        int totalModuleCount() {
            return dependencies.stream()
                    .mapToInt(AggregatedDependency::moduleCount)
                    .max()
                    .orElse(0);
        }
    }

    static class CollectionResult {
        final List<AggregatedDependency> allDependencies;
        final List<PropertyGroup> propertyGroups;
        final List<AggregatedDependency> ungroupedDependencies;

        CollectionResult(
                List<AggregatedDependency> allDependencies,
                List<PropertyGroup> propertyGroups,
                List<AggregatedDependency> ungroupedDependencies) {
            this.allDependencies = allDependencies;
            this.propertyGroups = propertyGroups;
            this.ungroupedDependencies = ungroupedDependencies;
        }
    }

    static CollectionResult collect(List<MavenProject> projects) {
        Map<String, AggregatedDependency> byGA = new LinkedHashMap<>();

        for (MavenProject project : projects) {
            collectFromProject(project, byGA);
        }

        List<AggregatedDependency> allDeps = new ArrayList<>(byGA.values());

        Map<String, PropertyGroup> groupsByProperty = new LinkedHashMap<>();
        List<AggregatedDependency> ungrouped = new ArrayList<>();

        for (AggregatedDependency dep : allDeps) {
            if (dep.isPropertyManaged()) {
                PropertyGroup group = groupsByProperty.computeIfAbsent(
                        dep.propertyName,
                        k -> new PropertyGroup(
                                dep.propertyName, dep.rawVersionExpr, dep.primaryVersion, dep.propertyOrigin));
                group.dependencies.add(dep);
            } else {
                ungrouped.add(dep);
            }
        }

        return new CollectionResult(allDeps, new ArrayList<>(groupsByProperty.values()), ungrouped);
    }

    private static void collectFromProject(MavenProject project, Map<String, AggregatedDependency> byGA) {
        DependencyManagement mgmt = project.getDependencyManagement();
        DependencyManagement originalMgmt = project.getOriginalModel().getDependencyManagement();
        if (mgmt != null && mgmt.getDependencies() != null) {
            List<Dependency> originalMgmtDeps = originalMgmt != null && originalMgmt.getDependencies() != null
                    ? originalMgmt.getDependencies()
                    : List.of();

            for (Dependency dep : mgmt.getDependencies()) {
                String ga = dep.getGroupId() + ":" + dep.getArtifactId();
                AggregatedDependency agg =
                        byGA.computeIfAbsent(ga, k -> new AggregatedDependency(dep.getGroupId(), dep.getArtifactId()));

                agg.usages.add(new ModuleUsage(project, dep.getVersion(), dep.getScope(), true));

                if (agg.primaryVersion == null) {
                    agg.primaryVersion = dep.getVersion();
                }

                if (agg.rawVersionExpr == null) {
                    detectPropertyVersion(agg, dep, originalMgmtDeps, project);
                }
            }
        }

        List<Dependency> originalDeps = project.getOriginalModel().getDependencies() != null
                ? project.getOriginalModel().getDependencies()
                : List.of();

        for (Dependency dep : project.getDependencies()) {
            String ga = dep.getGroupId() + ":" + dep.getArtifactId();
            AggregatedDependency agg =
                    byGA.computeIfAbsent(ga, k -> new AggregatedDependency(dep.getGroupId(), dep.getArtifactId()));

            boolean alreadyTracked = agg.usages.stream().anyMatch(u -> u.project == project);
            if (!alreadyTracked) {
                agg.usages.add(new ModuleUsage(project, dep.getVersion(), dep.getScope(), false));
            }

            if (agg.primaryVersion == null) {
                agg.primaryVersion = dep.getVersion();
            }

            if (agg.rawVersionExpr == null) {
                detectPropertyVersion(agg, dep, originalDeps, project);
            }
        }
    }

    private static void detectPropertyVersion(
            AggregatedDependency agg, Dependency resolvedDep, List<Dependency> originalDeps, MavenProject project) {
        String rawVersion = null;
        for (Dependency orig : originalDeps) {
            if (orig.getGroupId().equals(resolvedDep.getGroupId())
                    && orig.getArtifactId().equals(resolvedDep.getArtifactId())) {
                rawVersion = orig.getVersion();
                break;
            }
        }

        if (rawVersion == null) {
            return;
        }

        Matcher matcher = PROPERTY_PATTERN.matcher(rawVersion);
        if (matcher.matches()) {
            String propertyName = matcher.group(1);
            agg.rawVersionExpr = rawVersion;
            agg.propertyName = propertyName;
            agg.propertyOrigin = findPropertyOrigin(project, propertyName);
        }
    }

    private static MavenProject findPropertyOrigin(MavenProject project, String propertyName) {
        MavenProject current = project;
        while (current != null) {
            Properties props = current.getOriginalModel().getProperties();
            if (props != null && props.containsKey(propertyName)) {
                return current;
            }
            current = current.getParent();
        }
        return project;
    }
}
