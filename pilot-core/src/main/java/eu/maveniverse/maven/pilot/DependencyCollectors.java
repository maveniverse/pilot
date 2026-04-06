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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.aether.graph.DependencyNode;

/**
 * Shared helpers for walking Aether dependency trees and collecting
 * data for various TUI screens. Used by both the CLI and plugin modules.
 */
public final class DependencyCollectors {

    private DependencyCollectors() {}

    /**
     * Collects transitive (undeclared) dependencies from the tree.
     */
    public static List<AnalyzeTui.DepEntry> collectTransitive(DependencyNode root, Set<String> declaredGAs) {
        List<AnalyzeTui.DepEntry> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        doCollectTransitive(root, declaredGAs, seen, result);
        return result;
    }

    private static void doCollectTransitive(
            DependencyNode node, Set<String> declaredGAs, Set<String> seen, List<AnalyzeTui.DepEntry> result) {
        for (DependencyNode child : node.getChildren()) {
            if (child.getDependency() == null) continue;
            var art = child.getDependency().getArtifact();
            String ga = art.getGroupId() + ":" + art.getArtifactId();

            if (!declaredGAs.contains(ga) && seen.add(ga)) {
                String via = "";
                if (node.getDependency() != null) {
                    via = node.getDependency().getArtifact().getGroupId() + ":"
                            + node.getDependency().getArtifact().getArtifactId();
                }
                var entry = new AnalyzeTui.DepEntry(
                        art.getGroupId(),
                        art.getArtifactId(),
                        art.getVersion(),
                        child.getDependency().getScope(),
                        false);
                entry.pulledBy = via;
                result.add(entry);
            }
            doCollectTransitive(child, declaredGAs, seen, result);
        }
    }

    /**
     * Collects dependency version conflicts from the tree.
     */
    public static List<ConflictsTui.ConflictGroup> collectConflicts(DependencyNode root) {
        Map<String, List<ConflictsTui.ConflictEntry>> conflictMap = new HashMap<>();
        doCollectConflicts(root, conflictMap, new ArrayList<>());

        return conflictMap.entrySet().stream()
                .filter(e -> e.getValue().size() > 1
                        || e.getValue().stream()
                                .anyMatch(c ->
                                        c.requestedVersion != null && !c.requestedVersion.equals(c.resolvedVersion)))
                .map(e -> new ConflictsTui.ConflictGroup(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    private static void doCollectConflicts(
            DependencyNode node, Map<String, List<ConflictsTui.ConflictEntry>> conflicts, List<String> path) {
        for (DependencyNode child : node.getChildren()) {
            if (child.getDependency() == null) continue;
            var art = child.getDependency().getArtifact();
            String ga = art.getGroupId() + ":" + art.getArtifactId();

            String requestedVersion = art.getVersion();
            String resolvedVersion = requestedVersion;
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
            doCollectConflicts(child, conflicts, currentPath);
        }
    }

    /**
     * Collects audit entries (deduplicated by GA) from the tree.
     */
    public static List<AuditTui.AuditEntry> collectAuditEntries(DependencyNode root) {
        List<AuditTui.AuditEntry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        doCollectAuditEntries(root, entries, seen, true);
        return entries;
    }

    private static void doCollectAuditEntries(
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
            doCollectAuditEntries(child, entries, seen, false);
        }
    }
}
