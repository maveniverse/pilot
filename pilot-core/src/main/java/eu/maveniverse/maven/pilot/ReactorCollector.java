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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects and aggregates dependencies across all reactor modules.
 *
 * <p>Deduplicates by groupId:artifactId, tracks which modules use each dependency,
 * detects property-based version expressions, and identifies which POM defines the property.</p>
 */
public class ReactorCollector {

    private static final Pattern PROPERTY_PATTERN = Pattern.compile("\\$\\{(.+?)\\}");

    static class ModuleUsage {
        final PilotProject project;
        final String version;
        final String scope;
        final boolean managed;

        ModuleUsage(PilotProject project, String version, String scope, boolean managed) {
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
        PilotProject propertyOrigin;

        String newestVersion;
        VersionComparator.UpdateType updateType;
        boolean applied;
        LocalDate currentReleaseDate;
        LocalDate newestReleaseDate;
        float libYears = -1;

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
        final PilotProject origin;
        final List<AggregatedDependency> dependencies = new ArrayList<>();

        String newestVersion;
        VersionComparator.UpdateType updateType;
        boolean applied;
        boolean expanded = false;
        float libYears = -1;

        PropertyGroup(String propertyName, String rawExpression, String resolvedVersion, PilotProject origin) {
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

    public static class CollectionResult {
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

    public static CollectionResult collect(List<PilotProject> projects) {
        Map<String, AggregatedDependency> byGA = new LinkedHashMap<>();

        for (PilotProject project : projects) {
            collectFromProject(project, byGA);
        }

        List<AggregatedDependency> allDeps = new ArrayList<>(byGA.values());

        Map<String, PropertyGroup> groupsByProperty = new LinkedHashMap<>();
        List<AggregatedDependency> ungrouped = new ArrayList<>();

        for (AggregatedDependency dep : allDeps) {
            if (dep.isPropertyManaged() && dep.propertyOrigin != null) {
                String groupKey = dep.propertyName + "@" + dep.propertyOrigin.pomPath;
                PropertyGroup group = groupsByProperty.computeIfAbsent(
                        groupKey,
                        k -> new PropertyGroup(
                                dep.propertyName, dep.rawVersionExpr, dep.primaryVersion, dep.propertyOrigin));
                group.dependencies.add(dep);
            } else {
                ungrouped.add(dep);
            }
        }

        return new CollectionResult(allDeps, new ArrayList<>(groupsByProperty.values()), ungrouped);
    }

    private static void collectFromProject(PilotProject project, Map<String, AggregatedDependency> byGA) {
        List<PilotProject.Dep> mgmtDeps = project.managedDependencies;
        List<PilotProject.Dep> originalMgmtDeps =
                project.originalManagedDependencies != null ? project.originalManagedDependencies : List.of();

        if (mgmtDeps != null && !mgmtDeps.isEmpty()) {
            // Only include dependencies explicitly declared in this project's POM,
            // not those inherited from imported BOMs
            Set<String> declaredManagedGAs = new HashSet<>();
            for (PilotProject.Dep dep : originalMgmtDeps) {
                if (!dep.isBomImport()) {
                    declaredManagedGAs.add(dep.ga());
                }
            }

            for (PilotProject.Dep dep : mgmtDeps) {
                String ga = dep.ga();
                if (!declaredManagedGAs.contains(ga)) {
                    continue;
                }
                AggregatedDependency agg =
                        byGA.computeIfAbsent(ga, k -> new AggregatedDependency(dep.groupId(), dep.artifactId()));

                agg.usages.add(new ModuleUsage(project, dep.version(), dep.scope(), true));

                if (agg.primaryVersion == null) {
                    agg.primaryVersion = dep.version();
                }

                if (agg.rawVersionExpr == null) {
                    detectPropertyVersion(agg, dep, originalMgmtDeps, project);
                }
            }
        }

        List<PilotProject.Dep> originalDeps =
                project.originalDependencies != null ? project.originalDependencies : List.of();

        for (PilotProject.Dep dep : project.dependencies) {
            String ga = dep.ga();
            AggregatedDependency agg =
                    byGA.computeIfAbsent(ga, k -> new AggregatedDependency(dep.groupId(), dep.artifactId()));

            boolean alreadyTracked = agg.usages.stream().anyMatch(u -> u.project == project);
            if (!alreadyTracked) {
                agg.usages.add(new ModuleUsage(project, dep.version(), dep.scope(), false));
            }

            if (agg.primaryVersion == null) {
                agg.primaryVersion = dep.version();
            }

            if (agg.rawVersionExpr == null) {
                detectPropertyVersion(agg, dep, originalDeps, project);
            }
        }
    }

    private static void detectPropertyVersion(
            AggregatedDependency agg,
            PilotProject.Dep resolvedDep,
            List<PilotProject.Dep> originalDeps,
            PilotProject project) {
        String rawVersion = findRawVersion(resolvedDep, originalDeps);

        // If not found in local originals, check parent chain's managed deps
        if (rawVersion == null) {
            PilotProject ancestor = project.parent;
            while (ancestor != null && rawVersion == null) {
                if (ancestor.originalManagedDependencies != null) {
                    rawVersion = findRawVersion(resolvedDep, ancestor.originalManagedDependencies);
                }
                ancestor = ancestor.parent;
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

    private static String findRawVersion(PilotProject.Dep target, List<PilotProject.Dep> deps) {
        for (PilotProject.Dep dep : deps) {
            if (dep.groupId().equals(target.groupId()) && dep.artifactId().equals(target.artifactId())) {
                return dep.version();
            }
        }
        return null;
    }

    private static PilotProject findPropertyOrigin(PilotProject project, String propertyName) {
        PilotProject current = project;
        while (current != null) {
            Properties props = current.originalProperties;
            if (props != null && props.containsKey(propertyName)) {
                return current;
            }
            current = current.parent;
        }
        return null;
    }
}
