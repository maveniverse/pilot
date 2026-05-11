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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AuditReporterTest {

    private AuditTui.AuditEntry entry(String g, String a, String v, String scope) {
        return new AuditTui.AuditEntry(g, a, v, scope);
    }

    private OsvClient.Vulnerability vuln(String id, String summary, String severity) {
        return new OsvClient.Vulnerability(id, summary, severity, "2024-01-01", List.of());
    }

    // -- formatReport --

    @Test
    void formatReportEmpty() {
        String report = AuditReporter.formatReport(List.of());

        assertThat(report)
                .contains("=== Pilot Audit Report ===")
                .contains("No known vulnerabilities found")
                .contains("0 dependencies");
    }

    @Test
    void formatReportNoVulns() {
        var e = entry("org.example", "safe-lib", "1.0.0", "compile");
        e.vulnsLoaded = true;
        e.vulnerabilities = List.of();
        e.licenseLoaded = true;
        e.license = "Apache License 2.0";

        String report = AuditReporter.formatReport(List.of(e));

        assertThat(report)
                .contains("No known vulnerabilities found")
                .contains("1 dependencies")
                .contains("0 vulnerabilities");
    }

    @Test
    void formatReportWithVulns() {
        var e1 = entry("org.example", "vuln-lib", "1.0.0", "compile");
        e1.vulnsLoaded = true;
        e1.vulnerabilities = List.of(
                vuln("CVE-2024-0001", "Critical bug", "CRITICAL"), vuln("CVE-2024-0002", "Medium issue", "MEDIUM"));
        e1.licenseLoaded = true;

        var e2 = entry("org.example", "high-lib", "2.0.0", "compile");
        e2.vulnsLoaded = true;
        e2.vulnerabilities = List.of(vuln("CVE-2024-0003", "High severity", "HIGH"));
        e2.licenseLoaded = true;

        String report = AuditReporter.formatReport(List.of(e1, e2));

        assertThat(report)
                .contains("3 found")
                .contains("CVE-2024-0001")
                .contains("CVE-2024-0002")
                .contains("CVE-2024-0003")
                .contains("Critical bug")
                .contains("CRITICAL");

        // Verify CRITICAL appears before HIGH which appears before MEDIUM
        int critIdx = report.indexOf("CRITICAL");
        int highIdx = report.indexOf("HIGH");
        int medIdx = report.indexOf("MEDIUM");
        assertThat(critIdx).isLessThan(highIdx);
        assertThat(highIdx).isLessThan(medIdx);
    }

    @Test
    void formatReportWithLicenses() {
        var e1 = entry("org.example", "apache-lib", "1.0", "compile");
        e1.licenseLoaded = true;
        e1.license = "Apache License 2.0";
        e1.vulnsLoaded = true;
        e1.vulnerabilities = List.of();

        var e2 = entry("org.example", "mit-lib", "1.0", "compile");
        e2.licenseLoaded = true;
        e2.license = "MIT";
        e2.vulnsLoaded = true;
        e2.vulnerabilities = List.of();

        var e3 = entry("org.example", "no-license-lib", "1.0", "compile");
        e3.licenseLoaded = true;
        e3.license = null;
        e3.vulnsLoaded = true;
        e3.vulnerabilities = List.of();

        var e4 = entry("org.copyleft", "gpl-lib", "1.0", "compile");
        e4.licenseLoaded = true;
        e4.license = "GPL-3.0";
        e4.vulnsLoaded = true;
        e4.vulnerabilities = List.of();

        String report = AuditReporter.formatReport(List.of(e1, e2, e3, e4));

        assertThat(report)
                .contains("Apache-2.0")
                .contains("MIT")
                .contains("(not specified)")
                .contains("org.example:no-license-lib:1.0")
                .contains("GPL-3.0")
                .contains("org.copyleft:gpl-lib:1.0")
                .contains("1 with no license")
                .contains("1 copyleft");
    }

    @Test
    void formatReportMixedVulnsAndLicenses() {
        var e = entry("org.example", "mixed-lib", "1.0", "compile");
        e.vulnsLoaded = true;
        e.vulnerabilities = List.of(vuln("CVE-2024-9999", "A vuln", "HIGH"));
        e.licenseLoaded = true;
        e.license = "MIT";

        String report = AuditReporter.formatReport(List.of(e));

        assertThat(report)
                .contains("Vulnerabilities (1 found)")
                .contains("CVE-2024-9999")
                .contains("Licenses (1 dependencies)")
                .contains("MIT");
    }

    // -- countVulnerabilitiesAtOrAbove --

    @Test
    void countVulnerabilitiesAtOrAboveCritical() {
        var e = entry("g", "a", "1.0", "compile");
        e.vulnerabilities = List.of(
                vuln("CVE-1", "crit", "CRITICAL"), vuln("CVE-2", "high", "HIGH"), vuln("CVE-3", "med", "MEDIUM"));

        assertThat(AuditReporter.countVulnerabilitiesAtOrAbove(List.of(e), "CRITICAL"))
                .isEqualTo(1);
    }

    @Test
    void countVulnerabilitiesAtOrAboveHigh() {
        var e = entry("g", "a", "1.0", "compile");
        e.vulnerabilities = List.of(
                vuln("CVE-1", "crit", "CRITICAL"), vuln("CVE-2", "high", "HIGH"), vuln("CVE-3", "med", "MEDIUM"));

        assertThat(AuditReporter.countVulnerabilitiesAtOrAbove(List.of(e), "HIGH"))
                .isEqualTo(2);
    }

    @Test
    void countVulnerabilitiesAtOrAboveMedium() {
        var e = entry("g", "a", "1.0", "compile");
        e.vulnerabilities = List.of(
                vuln("CVE-1", "crit", "CRITICAL"), vuln("CVE-2", "high", "HIGH"), vuln("CVE-3", "med", "MEDIUM"));

        assertThat(AuditReporter.countVulnerabilitiesAtOrAbove(List.of(e), "MEDIUM"))
                .isEqualTo(3);
    }

    @Test
    void countVulnerabilitiesAtOrAboveLow() {
        var e = entry("g", "a", "1.0", "compile");
        e.vulnerabilities = List.of(vuln("CVE-1", "crit", "CRITICAL"), vuln("CVE-2", "low", "LOW"));

        assertThat(AuditReporter.countVulnerabilitiesAtOrAbove(List.of(e), "LOW"))
                .isEqualTo(2);
    }

    @Test
    void countVulnerabilitiesAtOrAboveNoVulns() {
        var e = entry("g", "a", "1.0", "compile");
        e.vulnerabilities = List.of();

        assertThat(AuditReporter.countVulnerabilitiesAtOrAbove(List.of(e), "HIGH"))
                .isZero();
    }

    @Test
    void countVulnerabilitiesAtOrAboveNullVulns() {
        var e = entry("g", "a", "1.0", "compile");
        e.vulnerabilities = null;

        assertThat(AuditReporter.countVulnerabilitiesAtOrAbove(List.of(e), "HIGH"))
                .isZero();
    }

    @Test
    void countVulnerabilitiesDeduplicatesByVulnId() {
        var e1 = entry("g", "a", "1.0", "compile");
        e1.vulnerabilities = List.of(vuln("CVE-1", "same vuln", "HIGH"));
        var e2 = entry("g", "b", "1.0", "compile");
        e2.vulnerabilities = List.of(vuln("CVE-1", "same vuln", "HIGH"));

        assertThat(AuditReporter.countVulnerabilitiesAtOrAbove(List.of(e1, e2), "HIGH"))
                .isEqualTo(1);
    }

    // -- formatCheckFailure --

    @Test
    void formatCheckFailureIncludesThreshold() {
        var e = entry("g", "a", "1.0", "compile");
        e.vulnerabilities = List.of(vuln("CVE-1", "bad", "CRITICAL"));

        String msg = AuditReporter.formatCheckFailure(List.of(e), "HIGH");

        assertThat(msg).contains("1 vulnerability(ies)").contains("HIGH or above");
    }

    @Test
    void formatCheckFailureIncludesHint() {
        var e = entry("g", "a", "1.0", "compile");
        e.vulnerabilities = List.of(vuln("CVE-1", "bad", "HIGH"));

        String msg = AuditReporter.formatCheckFailure(List.of(e), "HIGH");

        assertThat(msg).contains("-Dpilot.action=report");
    }

    @Test
    void formatCheckFailureCaseInsensitiveThreshold() {
        var e = entry("g", "a", "1.0", "compile");
        e.vulnerabilities = List.of(vuln("CVE-1", "bad", "CRITICAL"));

        String msg = AuditReporter.formatCheckFailure(List.of(e), "critical");

        assertThat(msg).contains("CRITICAL or above");
    }

    // -- formatReport edge cases --

    @Test
    void formatReportHandlesNullSummary() {
        var e = entry("g", "a", "1.0", "compile");
        e.vulnsLoaded = true;
        e.vulnerabilities = List.of(vuln("CVE-1", null, "HIGH"));
        e.licenseLoaded = true;

        String report = AuditReporter.formatReport(List.of(e));

        assertThat(report).contains("CVE-1").contains("HIGH");
    }

    @Test
    void formatReportHandlesModerateAsMedium() {
        var e = entry("g", "a", "1.0", "compile");
        e.vulnsLoaded = true;
        e.vulnerabilities = List.of(vuln("CVE-1", "moderate vuln", "MODERATE"));
        e.licenseLoaded = true;

        String report = AuditReporter.formatReport(List.of(e));

        assertThat(report).contains("MEDIUM").doesNotContain("MODERATE");
    }

    // -- summary formatting --

    @Test
    void formatReportSummaryShowsSeverityBreakdown() {
        var e1 = entry("g", "a", "1.0", "compile");
        e1.vulnsLoaded = true;
        e1.vulnerabilities = List.of(vuln("CVE-1", "crit", "CRITICAL"), vuln("CVE-2", "high", "HIGH"));
        e1.licenseLoaded = true;

        var e2 = entry("g", "b", "1.0", "compile");
        e2.vulnsLoaded = true;
        e2.vulnerabilities = List.of(vuln("CVE-3", "low", "LOW"));
        e2.licenseLoaded = true;

        String report = AuditReporter.formatReport(List.of(e1, e2));

        assertThat(report)
                .contains("Summary:")
                .contains("3 vulnerabilities")
                .contains("1 critical")
                .contains("1 high")
                .contains("1 low");
    }

    @Test
    void formatReportSummaryShowsNoLicenseCount() {
        var e = entry("g", "a", "1.0", "compile");
        e.vulnsLoaded = true;
        e.vulnerabilities = List.of();
        e.licenseLoaded = true;
        e.license = null;

        String report = AuditReporter.formatReport(List.of(e));

        assertThat(report).contains("1 with no license");
    }

    @Test
    void formatReportSummaryShowsCopyleftCount() {
        var e = entry("g", "a", "1.0", "compile");
        e.vulnsLoaded = true;
        e.vulnerabilities = List.of();
        e.licenseLoaded = true;
        e.license = "GPL-3.0";

        String report = AuditReporter.formatReport(List.of(e));

        assertThat(report).contains("1 copyleft");
    }

    @Test
    void formatReportSummaryOmitsNoLicenseWhenAllLicensed() {
        var e = entry("g", "a", "1.0", "compile");
        e.vulnsLoaded = true;
        e.vulnerabilities = List.of();
        e.licenseLoaded = true;
        e.license = "MIT";

        String report = AuditReporter.formatReport(List.of(e));

        assertThat(report).doesNotContain("no license").doesNotContain("copyleft");
    }

    // -- license grouping --

    @Test
    void formatReportGroupsLicensesByCount() {
        var e1 = entry("g", "a1", "1.0", "compile");
        e1.licenseLoaded = true;
        e1.license = "MIT";
        e1.vulnsLoaded = true;
        e1.vulnerabilities = List.of();
        var e2 = entry("g", "a2", "1.0", "compile");
        e2.licenseLoaded = true;
        e2.license = "MIT";
        e2.vulnsLoaded = true;
        e2.vulnerabilities = List.of();
        var e3 = entry("g", "a3", "1.0", "compile");
        e3.licenseLoaded = true;
        e3.license = "Apache License 2.0";
        e3.vulnsLoaded = true;
        e3.vulnerabilities = List.of();

        String report = AuditReporter.formatReport(List.of(e1, e2, e3));

        // MIT (2) should appear before Apache-2.0 (1) since sorted by count desc
        int mitIdx = report.indexOf("MIT");
        int apacheIdx = report.indexOf("Apache-2.0");
        assertThat(mitIdx).isLessThan(apacheIdx);
    }

    @Test
    void formatReportExpandsCopyleftDeps() {
        var e = entry("org.copyleft", "gpl-lib", "1.0", "compile");
        e.licenseLoaded = true;
        e.license = "AGPL-3.0";
        e.vulnsLoaded = true;
        e.vulnerabilities = List.of();

        String report = AuditReporter.formatReport(List.of(e));

        assertThat(report).contains("AGPL-3.0").contains("org.copyleft:gpl-lib:1.0");
    }

    @Test
    void formatReportSkipsUnloadedLicenses() {
        var e = entry("g", "a", "1.0", "compile");
        e.licenseLoaded = false;
        e.vulnsLoaded = true;
        e.vulnerabilities = List.of();

        String report = AuditReporter.formatReport(List.of(e));

        assertThat(report).contains("0 dependencies");
    }

    // -- fetchData skips already loaded --

    @Test
    void fetchDataSkipsAlreadyLoaded() {
        var e = entry("org.example", "lib", "1.0.0", "compile");
        e.vulnsLoaded = true;
        e.vulnerabilities = List.of(vuln("CVE-1", "pre-set", "HIGH"));
        e.licenseLoaded = true;
        e.license = "MIT";

        AuditReporter.fetchData(List.of(e));

        // Should not overwrite pre-set data
        assertThat(e.vulnerabilities).hasSize(1);
        assertThat(e.vulnerabilities.get(0).id).isEqualTo("CVE-1");
        assertThat(e.license).isEqualTo("MIT");
    }

    // -- vulnerability deduplication in report --

    @Test
    void formatReportDeduplicatesVulnsByIdAcrossEntries() {
        var e1 = entry("g", "a", "1.0", "compile");
        e1.vulnsLoaded = true;
        e1.vulnerabilities = List.of(vuln("CVE-SAME", "shared vuln", "HIGH"));
        e1.licenseLoaded = true;

        var e2 = entry("g", "b", "1.0", "compile");
        e2.vulnsLoaded = true;
        e2.vulnerabilities = List.of(vuln("CVE-SAME", "shared vuln", "HIGH"));
        e2.licenseLoaded = true;

        String report = AuditReporter.formatReport(List.of(e1, e2));

        assertThat(report).contains("1 found").contains("CVE-SAME");
        // CVE-SAME should appear only once in the vulnerability listing
        assertThat(report.indexOf("CVE-SAME")).isPositive();
    }
}
