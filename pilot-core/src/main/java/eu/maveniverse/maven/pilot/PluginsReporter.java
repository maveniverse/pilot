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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Non-interactive report formatter for plugin update data.
 *
 * <p>Produces plain-text output suitable for CI logs. Uses the same data model
 * as {@link PluginsTui} but has no TUI dependencies.</p>
 *
 * @since 0.3.0
 */
public final class PluginsReporter {

    private PluginsReporter() {}

    /**
     * A resolved plugin entry: the plugin coordinates plus the newest available version (if any).
     */
    public record PluginUpdate(
            String groupId, String artifactId, String currentVersion, String newestVersion, boolean managed) {

        public String ga() {
            return groupId + ":" + artifactId;
        }
    }

    /**
     * Result of {@link #resolveAndCheck}: the plain-text report, the list of available updates,
     * and the set of plugin GAs whose version lookup failed.
     *
     * <p>{@code updates} is an unmodifiable list. {@code unresolved} is an unmodifiable set.</p>
     */
    public record CheckResult(
            /** Plain-text report suitable for logging. */
            String report,
            /** Plugin updates found. Empty when all plugins are up to date. */
            List<PluginUpdate> updates,
            /**
             * GAs for which version resolution failed (e.g. repository unreachable).
             * Non-empty means the check result is incomplete.
             */
            Set<String> unresolved) {

        public String formatFailure() {
            StringBuilder sb = new StringBuilder();
            if (!updates.isEmpty()) {
                sb.append(updates.size()).append(" plugin update(s) available. Run pilot:plugins to review.");
            }
            if (!unresolved.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(unresolved.size())
                        .append(" plugin(s) could not be resolved: ")
                        .append(unresolved)
                        .append('.');
            }
            return sb.toString();
        }

        /** Returns {@code true} when the result is complete (no resolver failures). */
        public boolean isComplete() {
            return unresolved.isEmpty();
        }
    }

    /**
     * A thin holder used during collection so we can track managed vs declared without
     * leaking TUI-specific types.
     */
    private static final class Entry {
        final String groupId;
        final String artifactId;
        final String version;
        final boolean managed;

        Entry(String groupId, String artifactId, String version, boolean managed) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version != null ? version : "";
            this.managed = managed;
        }

        String ga() {
            return groupId + ":" + artifactId;
        }
    }

    /**
     * Resolves available versions for all plugins across the given projects synchronously and
     * builds a plain-text report.
     *
     * <p>Declared plugins take precedence over managed plugins when both share the same GA.</p>
     *
     * @param projects     projects to scan (reactor or single-module)
     * @param resolver     version resolver (newest-first list; empty = lookup failed)
     * @param projectGav   project or reactor identifier for the report header
     * @return check result with a plain-text report, the list of updates found, and the set of plugin GAs whose version lookup failed
     */
    public static CheckResult resolveAndCheck(
            List<PilotProject> projects, UpdatesTui.VersionResolver resolver, String projectGav) {
        // Collect plugins: declared wins over managed for the same GA.
        Map<String, Entry> managedMap = new LinkedHashMap<>();
        Map<String, Entry> pluginsMap = new LinkedHashMap<>();
        for (PilotProject p : projects) {
            for (PilotProject.Plugin plugin : p.getManagedPlugins()) {
                managedMap.computeIfAbsent(
                        plugin.ga(), k -> new Entry(plugin.groupId(), plugin.artifactId(), plugin.version(), true));
            }
            for (PilotProject.Plugin plugin : p.getPlugins()) {
                pluginsMap.computeIfAbsent(
                        plugin.ga(), k -> new Entry(plugin.groupId(), plugin.artifactId(), plugin.version(), false));
            }
        }
        Map<String, Entry> allByGa = new LinkedHashMap<>(managedMap);
        allByGa.putAll(pluginsMap);

        // Resolve updates.
        List<PluginUpdate> updates = new ArrayList<>();
        Set<String> unresolved = new LinkedHashSet<>();
        for (Entry entry : allByGa.values()) {
            if (entry.version.isEmpty()) continue;
            List<String> versions;
            try {
                versions = resolver.resolveVersions(entry.groupId, entry.artifactId);
            } catch (Exception e) {
                unresolved.add(entry.ga()); // record lookup failure
                continue;
            }
            if (versions.isEmpty()) {
                unresolved.add(entry.ga()); // empty result also means unresolved
                continue;
            }
            versions.stream()
                    .filter(v -> !VersionComparator.isPreview(v))
                    .filter(v -> VersionComparator.isNewer(entry.version, v))
                    .findFirst()
                    .ifPresent(v -> updates.add(
                            new PluginUpdate(entry.groupId, entry.artifactId, entry.version, v, entry.managed)));
        }

        // Build report.
        StringBuilder sb = new StringBuilder();
        sb.append("Plugin updates for ").append(projectGav).append(":\n\n");
        if (updates.isEmpty() && unresolved.isEmpty()) {
            sb.append("  All plugins are up to date.\n");
        } else {
            for (PluginUpdate u : updates) {
                VersionComparator.UpdateType type = VersionComparator.classify(u.currentVersion(), u.newestVersion());
                sb.append(String.format(
                        Locale.US,
                        "  %-60s %s -> %s  [%s]%s%n",
                        u.ga(),
                        u.currentVersion(),
                        u.newestVersion(),
                        VersionComparator.updateTypeLabel(type),
                        u.managed() ? " (managed)" : ""));
            }
            if (!unresolved.isEmpty()) {
                sb.append("\n  Could not resolve versions for:\n");
                for (String ga : unresolved) {
                    sb.append("    ").append(ga).append('\n');
                }
            }
        }

        return new CheckResult(sb.toString(), List.copyOf(updates), Set.copyOf(unresolved));
    }
}
