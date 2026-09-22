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

import eu.maveniverse.domtrip.Document;
import eu.maveniverse.domtrip.maven.AlignOptions;
import eu.maveniverse.domtrip.maven.Coordinates;
import eu.maveniverse.domtrip.maven.PomEditor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Non-interactive report and fix logic for dependency analysis.
 *
 * <p>Produces plain-text output and applies POM fixes for unused declared
 * and used transitive dependency issues. Extracted from the former
 * {@code analyze-dependencies} goal for reuse.</p>
 *
 * @since 0.3.0
 */
public final class DependenciesReporter {

    private DependenciesReporter() {}

    /**
     * Format a text report of dependency findings.
     */
    public static String formatFindings(
            List<DependenciesTui.DepEntry> unusedDeclared, List<DependenciesTui.DepEntry> usedTransitive) {
        return formatFindings(unusedDeclared, List.of(), usedTransitive, List.of());
    }

    /**
     * Format a text report of dependency findings, including undetermined dependencies.
     *
     * @param unusedDeclared   declared dependencies confirmed unused (can be removed)
     * @param usedTransitive   transitive dependencies confirmed used (should be declared)
     * @param undetermined     dependencies whose usage could not be determined
     */
    public static String formatFindings(
            List<DependenciesTui.DepEntry> unusedDeclared,
            List<DependenciesTui.DepEntry> usedTransitive,
            List<DependenciesTui.DepEntry> undetermined) {
        return formatFindings(unusedDeclared, List.of(), usedTransitive, undetermined);
    }

    /**
     * Format a text report of dependency findings, including test-scope narrowing and undetermined dependencies.
     *
     * @param unusedDeclared     declared dependencies confirmed unused (can be removed)
     * @param testScopedDeclared declared dependencies used only in tests (scope should be narrowed to test)
     * @param usedTransitive     transitive dependencies confirmed used (should be declared)
     * @param undetermined       dependencies whose usage could not be determined
     */
    public static String formatFindings(
            List<DependenciesTui.DepEntry> unusedDeclared,
            List<DependenciesTui.DepEntry> testScopedDeclared,
            List<DependenciesTui.DepEntry> usedTransitive,
            List<DependenciesTui.DepEntry> undetermined) {
        StringBuilder sb = new StringBuilder();
        if (!unusedDeclared.isEmpty()) {
            sb.append("Unused declared dependenc");
            sb.append(unusedDeclared.size() == 1 ? "y" : "ies");
            sb.append(" (can be removed):\n");
            for (var dep : unusedDeclared) {
                sb.append("  - ").append(dep.ga());
                appendScope(sb, dep);
                sb.append("\n");
            }
        }
        if (!testScopedDeclared.isEmpty()) {
            if (!unusedDeclared.isEmpty()) {
                sb.append("\n");
            }
            sb.append("Declared dependenc");
            sb.append(testScopedDeclared.size() == 1 ? "y" : "ies");
            sb.append(" used only in tests (scope should be narrowed to test):\n");
            for (var dep : testScopedDeclared) {
                sb.append("  - ").append(dep.ga());
                appendScope(sb, dep);
                sb.append("\n");
            }
        }
        if (!usedTransitive.isEmpty()) {
            if (!unusedDeclared.isEmpty() || !testScopedDeclared.isEmpty()) {
                sb.append("\n");
            }
            sb.append("Used transitive dependenc");
            sb.append(usedTransitive.size() == 1 ? "y" : "ies");
            sb.append(" (should be declared):\n");
            for (var dep : usedTransitive) {
                sb.append("  - ").append(dep.ga());
                appendScope(sb, dep);
                sb.append("\n");
            }
        }
        if (!undetermined.isEmpty()) {
            if (!unusedDeclared.isEmpty() || !testScopedDeclared.isEmpty() || !usedTransitive.isEmpty()) {
                sb.append("\n");
            }
            sb.append("Undetermined dependenc");
            sb.append(undetermined.size() == 1 ? "y" : "ies");
            sb.append(" (usage could not be determined — use knownUsed/knownUnused to resolve):\n");
            for (var dep : undetermined) {
                sb.append("  - ").append(dep.ga());
                appendScope(sb, dep);
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Format a check-failure message.
     */
    public static String formatCheckFailure(
            List<DependenciesTui.DepEntry> unusedDeclared, List<DependenciesTui.DepEntry> usedTransitive) {
        return formatCheckFailure(unusedDeclared, List.of(), usedTransitive, List.of());
    }

    /**
     * Format a check-failure message, including undetermined dependencies.
     */
    public static String formatCheckFailure(
            List<DependenciesTui.DepEntry> unusedDeclared,
            List<DependenciesTui.DepEntry> usedTransitive,
            List<DependenciesTui.DepEntry> undetermined) {
        return formatCheckFailure(unusedDeclared, List.of(), usedTransitive, undetermined);
    }

    /**
     * Format a check-failure message, including test-scope narrowing and undetermined dependencies.
     */
    public static String formatCheckFailure(
            List<DependenciesTui.DepEntry> unusedDeclared,
            List<DependenciesTui.DepEntry> testScopedDeclared,
            List<DependenciesTui.DepEntry> usedTransitive,
            List<DependenciesTui.DepEntry> undetermined) {
        String findings = formatFindings(unusedDeclared, testScopedDeclared, usedTransitive, undetermined);
        boolean hasRealIssues = !unusedDeclared.isEmpty() || !usedTransitive.isEmpty() || !testScopedDeclared.isEmpty();
        if (hasRealIssues) {
            return findings
                    + "\nRun with -Dpilot.action=fix to apply changes, or configure allowlists for false positives.";
        } else {
            return findings + "\nAnnotate undetermined dependencies with knownUsed or knownUnused to resolve them.";
        }
    }

    /**
     * Apply fixes to the POM: remove unused declared, narrow test-only declared to test scope,
     * and add used transitive dependencies.
     *
     * <p>When adding used transitive dependencies, honours ancestor dependency management:</p>
     * <ul>
     *   <li>If the dependency is already managed by an ancestor BOM/parent POM
     *       ({@code ancestorManagedGAs} contains its GA key), it is added <em>without</em> a
     *       {@code <version>} element — the inherited management already pins the version.</li>
     *   <li>Otherwise the resolved literal version from {@code gaToVersion} is used.</li>
     * </ul>
     *
     * @param pomPath               path to the POM file to modify
     * @param unusedDeclared        declared dependencies that are unused
     * @param testScopedDeclared    declared dependencies used only in tests (scope narrowed to test)
     * @param usedTransitive        transitive dependencies that are used directly
     * @param gaToVersion           resolved (literal) versions keyed by {@code groupId:artifactId}
     * @param ancestorManagedGAs    GAs already version-managed by an ancestor; version is omitted for these
     * @param logger                callback for progress messages
     */
    public static void fix(
            Path pomPath,
            List<DependenciesTui.DepEntry> unusedDeclared,
            List<DependenciesTui.DepEntry> testScopedDeclared,
            List<DependenciesTui.DepEntry> usedTransitive,
            Map<String, String> gaToVersion,
            Set<String> ancestorManagedGAs,
            FixLogger logger)
            throws IOException {
        String pomContent = Files.readString(pomPath);

        for (var dep : unusedDeclared) {
            PomEditor editor = new PomEditor(Document.of(pomContent));
            String[] parts = dep.ga().split(":");
            Coordinates coords = parts.length > 2
                    ? Coordinates.of(parts[0], parts[1], null, parts[2], "jar")
                    : Coordinates.of(parts[0], parts[1], null);
            boolean removed = editor.dependencies().deleteDependency(coords);
            pomContent = editor.toXml();
            if (removed) {
                logger.log("Removed unused dependency: " + dep.ga());
            }
        }

        for (var dep : testScopedDeclared) {
            PomEditor editor = new PomEditor(Document.of(pomContent));
            String[] parts = dep.ga().split(":");
            Coordinates coords = parts.length > 2
                    ? Coordinates.of(parts[0], parts[1], null, parts[2], "jar")
                    : Coordinates.of(parts[0], parts[1], null);
            editor.document()
                    .root()
                    .childElement("dependencies")
                    .flatMap(depsEl -> depsEl.childElements("dependency")
                            .filter(coords.predicateGA())
                            .findFirst())
                    .ifPresent(depEl -> {
                        boolean alreadyTest = depEl.childElement("scope")
                                .map(scopeEl -> "test".equals(scopeEl.textContentTrimmedOr("")))
                                .orElse(false);
                        if (!alreadyTest) {
                            editor.updateOrCreateChildElement(depEl, "scope", "test");
                            logger.log("Narrowed to test scope (used only in tests): " + dep.ga());
                        }
                    });
            pomContent = editor.toXml();
        }

        for (var dep : usedTransitive) {
            String[] parts = dep.ga().split(":");
            String groupId = parts[0];
            String artifactId = parts[1];
            String classifier = parts.length > 2 ? parts[2] : null;
            String scope = dep.scope;

            PomEditor editor = new PomEditor(Document.of(pomContent));

            boolean ancestorManaged = ancestorManagedGAs.contains(dep.ga());
            // null version = ancestor-managed (no <version> emitted); resolved version otherwise
            String version = ancestorManaged ? null : gaToVersion.getOrDefault(dep.ga(), "");
            Coordinates coords = (classifier != null && !classifier.isEmpty())
                    ? Coordinates.of(groupId, artifactId, version, classifier, "jar")
                    : Coordinates.of(groupId, artifactId, version);
            AlignOptions detected = editor.dependencies().detectConventions();
            AlignOptions.Builder optBuilder = AlignOptions.builder()
                    .versionStyle(detected.versionStyle())
                    .versionSource(detected.versionSource())
                    .namingConvention(detected.namingConvention())
                    .insertionOrdering(detected.insertionOrdering());
            if (scope != null && !scope.isEmpty() && !"compile".equals(scope)) {
                optBuilder.scope(scope);
            }
            boolean added = editor.dependencies().addAligned(coords, optBuilder.build());
            if (added) {
                logger.log("Added used transitive dependency"
                        + (ancestorManaged ? " (version managed by ancestor)" : "")
                        + ": " + dep.ga());
            }

            pomContent = editor.toXml();
        }

        Files.writeString(pomPath, pomContent);
        logger.log("Updated " + pomPath);
    }

    /**
     * Apply fixes to the POM without test-scope awareness (backward-compatible overload).
     *
     * @param pomPath            path to the POM file to modify
     * @param unusedDeclared     declared dependencies that are unused
     * @param usedTransitive     transitive dependencies that are used directly
     * @param gaToVersion        resolved (literal) versions keyed by {@code groupId:artifactId}
     * @param ancestorManagedGAs GAs already version-managed by an ancestor; version is omitted for these
     * @param logger             callback for progress messages
     */
    public static void fix(
            Path pomPath,
            List<DependenciesTui.DepEntry> unusedDeclared,
            List<DependenciesTui.DepEntry> usedTransitive,
            Map<String, String> gaToVersion,
            Set<String> ancestorManagedGAs,
            FixLogger logger)
            throws IOException {
        fix(pomPath, unusedDeclared, List.of(), usedTransitive, gaToVersion, ancestorManagedGAs, logger);
    }

    /**
     * Apply fixes to the POM without ancestor-management awareness (backward-compatible overload).
     *
     * @param pomPath        path to the POM file to modify
     * @param unusedDeclared declared dependencies that are unused
     * @param usedTransitive transitive dependencies that are used directly
     * @param gaToVersion    resolved (literal) versions keyed by {@code groupId:artifactId}
     * @param logger         callback for progress messages
     */
    public static void fix(
            Path pomPath,
            List<DependenciesTui.DepEntry> unusedDeclared,
            List<DependenciesTui.DepEntry> usedTransitive,
            Map<String, String> gaToVersion,
            FixLogger logger)
            throws IOException {
        fix(pomPath, unusedDeclared, List.of(), usedTransitive, gaToVersion, Set.of(), logger);
    }

    public static void appendScope(StringBuilder sb, DependenciesTui.DepEntry dep) {
        if (dep.scope != null && !dep.scope.isEmpty() && !"compile".equals(dep.scope)) {
            sb.append(" (").append(dep.scope).append(")");
        }
    }

    @FunctionalInterface
    public interface FixLogger {
        void log(String message);
    }
}
