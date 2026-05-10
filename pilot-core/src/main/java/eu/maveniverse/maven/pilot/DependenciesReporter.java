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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
        if (!usedTransitive.isEmpty()) {
            if (!unusedDeclared.isEmpty()) {
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
        return sb.toString();
    }

    /**
     * Format a check-failure message.
     */
    public static String formatCheckFailure(
            List<DependenciesTui.DepEntry> unusedDeclared, List<DependenciesTui.DepEntry> usedTransitive) {
        return formatFindings(unusedDeclared, usedTransitive)
                + "\nRun with -Dpilot.action=fix to apply changes, or configure allowlists for false positives.";
    }

    /**
     * Apply fixes to the POM: remove unused declared and add used transitive dependencies.
     */
    public static void fix(
            Path pomPath,
            List<DependenciesTui.DepEntry> unusedDeclared,
            List<DependenciesTui.DepEntry> usedTransitive,
            Map<String, String> gaToVersion,
            FixLogger logger)
            throws Exception {
        String pomContent = Files.readString(pomPath);

        for (var dep : unusedDeclared) {
            PomEditor editor = new PomEditor(Document.of(pomContent));
            String[] parts = dep.ga().split(":");
            Coordinates coords = parts.length > 2
                    ? Coordinates.of(parts[0], parts[1], null, parts[2], "jar")
                    : Coordinates.of(parts[0], parts[1], null);
            editor.dependencies().deleteDependency(coords);
            pomContent = editor.toXml();
            logger.log("Removed unused dependency: " + dep.ga());
        }

        for (var dep : usedTransitive) {
            String[] parts = dep.ga().split(":");
            String groupId = parts[0];
            String artifactId = parts[1];
            String classifier = parts.length > 2 ? parts[2] : null;
            String version = gaToVersion.getOrDefault(dep.ga(), "");
            String scope = dep.scope;

            PomEditor editor = new PomEditor(Document.of(pomContent));
            Coordinates coords = (classifier != null && !classifier.isEmpty())
                    ? Coordinates.of(groupId, artifactId, version, classifier, "jar")
                    : Coordinates.of(groupId, artifactId, version);
            AlignOptions detected = editor.dependencies().detectConventions();
            AlignOptions.Builder optBuilder = AlignOptions.builder()
                    .versionStyle(detected.versionStyle())
                    .versionSource(detected.versionSource())
                    .namingConvention(detected.namingConvention());
            if (scope != null && !scope.isEmpty() && !"compile".equals(scope)) {
                optBuilder.scope(scope);
            }
            editor.dependencies().addAligned(coords, optBuilder.build());
            pomContent = editor.toXml();
            logger.log("Added used transitive dependency: " + dep.ga());
        }

        Files.writeString(pomPath, pomContent);
        logger.log("Updated " + pomPath);
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
