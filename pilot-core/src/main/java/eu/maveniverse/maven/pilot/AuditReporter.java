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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Non-interactive report formatter for license and security audit data.
 *
 * <p>Produces plain-text output suitable for CI logs. Uses the same data model
 * as {@link AuditTui} but has no TUI dependencies.</p>
 *
 * @since 0.3.0
 */
public final class AuditReporter {

    private static final List<String> SEVERITY_ORDER = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN");

    private AuditReporter() {}

    /**
     * Format a complete audit report covering vulnerabilities and licenses.
     */
    public static String formatReport(List<AuditTui.AuditEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Pilot Audit Report ===\n\n");
        appendVulnerabilities(sb, entries);
        sb.append('\n');
        appendLicenses(sb, entries);
        sb.append('\n');
        appendSummary(sb, entries);
        return sb.toString();
    }

    /**
     * Count unique vulnerabilities at or above the given severity threshold.
     *
     * @param entries the audit entries to inspect
     * @param threshold minimum severity level (CRITICAL, HIGH, MEDIUM, or LOW)
     * @return number of unique vulnerability IDs at or above threshold
     */
    public static int countVulnerabilitiesAtOrAbove(List<AuditTui.AuditEntry> entries, String threshold) {
        int thresholdIdx = severityIndex(threshold);
        Set<String> seen = new HashSet<>();
        for (var entry : entries) {
            if (entry.vulnerabilities == null) continue;
            for (var vuln : entry.vulnerabilities) {
                String severity = AuditTui.normalizeSeverity(vuln.severity);
                if (severityIndex(severity) <= thresholdIdx && seen.add(vuln.id)) {
                    // counted
                }
            }
        }
        return seen.size();
    }

    /**
     * Format a failure message for check mode.
     */
    public static String formatCheckFailure(List<AuditTui.AuditEntry> entries, String threshold) {
        int count = countVulnerabilitiesAtOrAbove(entries, threshold);
        return "Audit check failed: " + count + " vulnerability(ies) found at severity "
                + threshold.toUpperCase(Locale.ROOT) + " or above.\n"
                + "Run with -Dpilot.action=report to see full details, or -Dpilot.action=tui for interactive mode.";
    }

    /**
     * Fetch vulnerability and license data for all entries synchronously.
     * Must be called before {@link #formatReport} in non-interactive mode.
     */
    public static void fetchData(List<AuditTui.AuditEntry> entries) {
        OsvClient osvClient = new OsvClient();
        for (AuditTui.AuditEntry entry : entries) {
            if (!entry.vulnsLoaded) {
                try {
                    entry.vulnerabilities = osvClient.query(entry.groupId, entry.artifactId, entry.version);
                } catch (Exception e) {
                    entry.vulnFetchFailed = true;
                    entry.vulnerabilities = List.of();
                }
                entry.vulnsLoaded = true;
            }

            if (!entry.licenseLoaded) {
                MavenCentralClient.PomInfo info =
                        MavenCentralClient.fetchPom(entry.groupId, entry.artifactId, entry.version);
                if (info != null && info.license != null) {
                    entry.license = info.license;
                    entry.licenseUrl = info.licenseUrl;
                }
                entry.licenseLoaded = true;
            }
        }
    }

    // -- internals --

    private static int severityIndex(String severity) {
        String normalized = AuditTui.normalizeSeverity(severity);
        int idx = SEVERITY_ORDER.indexOf(normalized);
        return idx >= 0 ? idx : SEVERITY_ORDER.size();
    }

    private static void appendVulnerabilities(StringBuilder sb, List<AuditTui.AuditEntry> entries) {
        List<VulnLine> vulns = collectVulns(entries);
        if (vulns.isEmpty()) {
            sb.append("--- Vulnerabilities ---\n");
            sb.append("  No known vulnerabilities found.\n");
            return;
        }
        sb.append("--- Vulnerabilities (").append(vulns.size()).append(" found) ---\n");
        vulns.sort(Comparator.comparingInt(v -> severityIndex(v.severity)));
        int maxSev = vulns.stream().mapToInt(v -> v.severity.length()).max().orElse(8);
        int maxId = vulns.stream().mapToInt(v -> v.id.length()).max().orElse(16);
        int maxGav = vulns.stream().mapToInt(v -> v.gav.length()).max().orElse(30);
        for (var v : vulns) {
            sb.append("  ");
            sb.append(pad(v.severity, maxSev));
            sb.append("  ");
            sb.append(pad(v.id, maxId));
            sb.append("  ");
            sb.append(pad(v.gav, maxGav));
            sb.append("  ");
            sb.append(v.summary != null ? v.summary : "");
            sb.append('\n');
        }
    }

    private static List<VulnLine> collectVulns(List<AuditTui.AuditEntry> entries) {
        Set<String> seen = new HashSet<>();
        List<VulnLine> result = new ArrayList<>();
        for (var entry : entries) {
            if (entry.vulnerabilities == null) continue;
            for (var vuln : entry.vulnerabilities) {
                if (seen.add(vuln.id)) {
                    result.add(new VulnLine(
                            AuditTui.normalizeSeverity(vuln.severity), vuln.id, entry.gav(), vuln.summary));
                }
            }
        }
        return result;
    }

    private static void appendLicenses(StringBuilder sb, List<AuditTui.AuditEntry> entries) {
        List<AuditTui.AuditEntry> loaded =
                entries.stream().filter(e -> e.licenseLoaded).toList();
        sb.append("--- Licenses (").append(loaded.size()).append(" dependencies) ---\n");

        Map<String, List<AuditTui.AuditEntry>> groups = new LinkedHashMap<>();
        for (var entry : loaded) {
            String key = entry.license != null
                    ? AuditTui.normalizeLicense(entry.license, entry.licenseUrl)
                    : "(not specified)";
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
        }

        List<Map.Entry<String, List<AuditTui.AuditEntry>>> sorted = new ArrayList<>(groups.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));

        boolean expandCopyleft = true;
        for (var group : sorted) {
            String name = group.getKey();
            int count = group.getValue().size();
            String label = count == 1 ? " dependency" : " dependencies";
            sb.append("  ").append(pad(name, 20)).append(count).append(label).append('\n');
            boolean isCopyleft = name.startsWith("GPL") || name.startsWith("AGPL");
            boolean isUnknown = "(not specified)".equals(name);
            if (expandCopyleft && (isCopyleft || isUnknown)) {
                for (var entry : group.getValue()) {
                    sb.append("    - ").append(entry.gav()).append('\n');
                }
            }
        }
    }

    private static void appendSummary(StringBuilder sb, List<AuditTui.AuditEntry> entries) {
        List<VulnLine> vulns = collectVulns(entries);
        long noLicense = entries.stream()
                .filter(e -> e.licenseLoaded && e.license == null)
                .count();
        long copyleft = entries.stream()
                .filter(e -> {
                    if (e.license == null) return false;
                    String norm = AuditTui.normalizeLicense(e.license, e.licenseUrl);
                    return norm.startsWith("GPL") || norm.startsWith("AGPL");
                })
                .count();

        sb.append("Summary: ");
        sb.append(entries.size()).append(" dependencies");
        sb.append(", ").append(vulns.size()).append(" vulnerabilities");
        if (!vulns.isEmpty()) {
            sb.append(" (");
            boolean first = true;
            for (String sev : SEVERITY_ORDER) {
                long count = vulns.stream().filter(v -> sev.equals(v.severity)).count();
                if (count > 0) {
                    if (!first) sb.append(", ");
                    sb.append(count).append(' ').append(sev.toLowerCase(Locale.ROOT));
                    first = false;
                }
            }
            sb.append(')');
        }
        if (noLicense > 0) {
            sb.append(", ").append(noLicense).append(" with no license");
        }
        if (copyleft > 0) {
            sb.append(", ").append(copyleft).append(" copyleft");
        }
        sb.append('\n');
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    private record VulnLine(String severity, String id, String gav, String summary) {}
}
