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

import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Non-interactive report formatter for dependency update data.
 *
 * <p>Produces plain-text output suitable for CI logs. Uses the same data model
 * as {@link UpdatesTui} but has no TUI dependencies.</p>
 *
 * @since 0.3.0
 */
public final class UpdatesReporter {

    private UpdatesReporter() {}

    /**
     * Resolve available versions for all dependencies synchronously.
     */
    public static void resolveUpdates(
            List<ReactorCollector.AggregatedDependency> dependencies, UpdatesTui.VersionResolver resolver) {
        for (var dep : dependencies) {
            try {
                List<String> versions = resolver.resolveVersions(dep.groupId, dep.artifactId);
                applyVersionResult(dep, versions);
            } catch (Exception e) {
                // skip failures
            }
        }
    }

    /**
     * Compute newest version for each property group from its member dependencies.
     */
    public static void computePropertyGroupVersions(List<ReactorCollector.PropertyGroup> propertyGroups) {
        for (var group : propertyGroups) {
            for (var dep : group.dependencies) {
                if (dep.hasUpdate()
                        && (group.newestVersion == null
                                || VersionComparator.isNewer(dep.newestVersion, group.newestVersion))) {
                    group.newestVersion = dep.newestVersion;
                    group.updateType = dep.updateType;
                }
            }
        }
    }

    /**
     * Fetch release dates and compute libyears for all dependencies with updates.
     */
    public static void fetchDatesAndComputeLibYears(List<ReactorCollector.AggregatedDependency> dependencies) {
        for (var dep : dependencies) {
            if (!dep.hasUpdate()) continue;
            dep.currentReleaseDate =
                    MavenCentralClient.fetchReleaseDate(dep.groupId, dep.artifactId, dep.primaryVersion);
            dep.newestReleaseDate = MavenCentralClient.fetchReleaseDate(dep.groupId, dep.artifactId, dep.newestVersion);
            if (dep.currentReleaseDate != null && dep.newestReleaseDate != null) {
                long weeks = ChronoUnit.WEEKS.between(dep.currentReleaseDate, dep.newestReleaseDate);
                dep.libYears = Math.max(0, weeks / 52.0f);
            }
        }
    }

    /**
     * Compute total libyears across all dependencies, deduplicating by GA.
     */
    public static float totalLibYears(List<ReactorCollector.AggregatedDependency> dependencies) {
        float total = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (var dep : dependencies) {
            if (dep.hasUpdate() && dep.libYears >= 0 && seen.add(dep.ga())) {
                total += dep.libYears;
            }
        }
        return total;
    }

    /**
     * Format a complete updates report.
     */
    public static String formatReport(ReactorCollector.CollectionResult result, String projectGav) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Pilot Updates Report ===\n");
        sb.append(projectGav).append('\n');
        sb.append('\n');
        appendUpdates(sb, result);
        sb.append('\n');
        appendPropertyGroups(sb, result);
        sb.append('\n');
        appendSummary(sb, result);
        return sb.toString();
    }

    /**
     * Format a failure message for check mode.
     */
    public static String formatCheckFailure(ReactorCollector.CollectionResult result, float libyearsThreshold) {
        float total = totalLibYears(result.allDependencies);
        return String.format(
                Locale.US,
                "Updates check failed: total libyears (%.1f) exceeds threshold (%.1f).%n"
                        + "Run with -Dpilot.action=report to see full details, or -Dpilot.action=tui for interactive mode.",
                total,
                libyearsThreshold);
    }

    /**
     * Resolve updates, fetch dates, and format report in one call.
     * Convenience method for callers outside this package.
     */
    public static String resolveAndFormatReport(
            ReactorCollector.CollectionResult result, UpdatesTui.VersionResolver resolver, String projectGav) {
        resolveUpdates(result.allDependencies, resolver);
        computePropertyGroupVersions(result.propertyGroups);
        fetchDatesAndComputeLibYears(result.allDependencies);
        return formatReport(result, projectGav);
    }

    /**
     * Resolve updates, fetch dates, compute libyears, and check against threshold.
     * Returns the report text. Throws MojoFailureException-compatible message via the checker.
     */
    public static CheckResult resolveAndCheck(
            ReactorCollector.CollectionResult result, UpdatesTui.VersionResolver resolver, String projectGav) {
        resolveUpdates(result.allDependencies, resolver);
        computePropertyGroupVersions(result.propertyGroups);
        fetchDatesAndComputeLibYears(result.allDependencies);
        String report = formatReport(result, projectGav);
        float total = totalLibYears(result.allDependencies);
        long updateCount = result.allDependencies.stream()
                .filter(ReactorCollector.AggregatedDependency::hasUpdate)
                .count();
        return new CheckResult(report, total, updateCount);
    }

    /**
     * Result of a check operation, providing the report and metrics.
     */
    public static class CheckResult {
        public final String report;
        public final float totalLibYears;
        public final long updateCount;

        public CheckResult(String report, float totalLibYears, long updateCount) {
            this.report = report;
            this.totalLibYears = totalLibYears;
            this.updateCount = updateCount;
        }

        public String formatFailure(float threshold) {
            return String.format(
                    java.util.Locale.US,
                    "Updates check failed: total libyears (%.1f) exceeds threshold (%.1f).%n"
                            + "Run with -Dpilot.action=report to see full details, or -Dpilot.action=tui for interactive mode.",
                    totalLibYears,
                    threshold);
        }
    }

    // -- internals --

    static void applyVersionResult(ReactorCollector.AggregatedDependency dep, List<String> versions) {
        String newest = null;
        for (String v : versions) {
            if (VersionComparator.isPreview(v)) continue;
            if (dep.primaryVersion == null
                    || dep.primaryVersion.isEmpty()
                    || VersionComparator.isNewer(dep.primaryVersion, v)) {
                newest = v;
                break;
            }
        }
        if (newest != null) {
            dep.newestVersion = newest;
            dep.updateType = VersionComparator.classify(dep.primaryVersion, newest);
        }
    }

    private static void appendUpdates(StringBuilder sb, ReactorCollector.CollectionResult result) {
        List<ReactorCollector.AggregatedDependency> withUpdates = result.allDependencies.stream()
                .filter(ReactorCollector.AggregatedDependency::hasUpdate)
                .toList();

        if (withUpdates.isEmpty()) {
            sb.append("--- Updates ---\n");
            sb.append("  No updates available.\n");
            return;
        }

        sb.append("--- Updates Available (").append(withUpdates.size()).append(" found) ---\n");

        int maxGa = withUpdates.stream().mapToInt(d -> d.ga().length()).max().orElse(30);
        int maxCur = withUpdates.stream()
                .mapToInt(d -> d.primaryVersion != null ? d.primaryVersion.length() : 0)
                .max()
                .orElse(10);
        int maxNew = withUpdates.stream()
                .mapToInt(d -> d.newestVersion != null ? d.newestVersion.length() : 0)
                .max()
                .orElse(10);

        for (var dep : withUpdates) {
            String type = dep.updateType != null ? dep.updateType.name() : "";
            String current = dep.primaryVersion != null ? dep.primaryVersion : "";
            String newest = dep.newestVersion != null ? dep.newestVersion : "";
            String age = formatAge(dep.libYears, true);
            sb.append("  ");
            sb.append(pad(type, 5));
            sb.append("  ");
            sb.append(pad(dep.ga(), maxGa));
            sb.append("  ");
            sb.append(pad(current, maxCur));
            sb.append("  ->  ");
            sb.append(pad(newest, maxNew));
            if (!age.isEmpty()) {
                sb.append("  ").append(age);
            }
            sb.append('\n');
        }
    }

    private static void appendPropertyGroups(StringBuilder sb, ReactorCollector.CollectionResult result) {
        List<ReactorCollector.PropertyGroup> withUpdates = result.propertyGroups.stream()
                .filter(ReactorCollector.PropertyGroup::hasUpdate)
                .toList();

        if (withUpdates.isEmpty()) return;

        sb.append("--- Property Groups ---\n");
        for (var group : withUpdates) {
            sb.append("  ${").append(group.propertyName).append("}  ");
            sb.append(group.resolvedVersion != null ? group.resolvedVersion : "?");
            sb.append(" -> ").append(group.newestVersion);
            sb.append("  (").append(group.dependencies.size()).append(" artifacts)");
            sb.append('\n');
        }
    }

    private static void appendSummary(StringBuilder sb, ReactorCollector.CollectionResult result) {
        long updateCount = result.allDependencies.stream()
                .filter(ReactorCollector.AggregatedDependency::hasUpdate)
                .count();
        float libYears = totalLibYears(result.allDependencies);
        sb.append("Summary: ").append(updateCount).append(" update(s) available");
        if (libYears > 0) {
            sb.append(String.format(Locale.US, ", %.1f libyear(s) behind", libYears));
        }
        sb.append('\n');
    }

    private static String formatAge(float libYears, boolean hasUpdate) {
        if (!hasUpdate || libYears < 0) return "";
        int tenths = Math.round(libYears * 10);
        return (tenths / 10) + "." + (tenths % 10) + "y";
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }
}
