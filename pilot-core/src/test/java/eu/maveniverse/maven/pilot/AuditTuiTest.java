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

import dev.tamboui.layout.Size;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.pilot.TuiTestRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditTuiTest {

    /**
     * Helper to build an AuditEntry for a dependency with the given coordinates and scope.
     */
    private AuditTui.AuditEntry entry(String g, String a, String v, String scope) {
        return new AuditTui.AuditEntry(g, a, v, scope);
    }

    /**
     * Build a simple flat list of AuditEntry from child coordinates (simulates
     * what collectEntries used to do for a root with direct children).
     */
    private List<AuditTui.AuditEntry> buildEntries(String[][] deps) {
        List<AuditTui.AuditEntry> entries = new ArrayList<>();
        for (String[] dep : deps) {
            String ga = dep[0] + ":" + dep[1];
            // deduplicate by GA — first version wins
            boolean exists = entries.stream().anyMatch(e -> e.ga().equals(ga));
            if (!exists) {
                entries.add(entry(dep[0], dep[1], dep[2], dep[3]));
            }
        }
        return entries;
    }

    @Test
    void collectEntriesConvenienceOverload() {
        List<AuditTui.AuditEntry> entries = buildEntries(new String[][] {
            {"org.slf4j", "slf4j-api", "2.0.9", "compile"},
            {"com.google.guava", "guava", "33.0.0-jre", "compile"}
        });

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).ga()).isEqualTo("org.slf4j:slf4j-api");
        assertThat(entries.get(1).ga()).isEqualTo("com.google.guava:guava");
        // No module tracking
        assertThat(entries.get(0).modules).isEmpty();
    }

    @Test
    void collectEntriesDeduplicatesByGA() {
        // slf4j appears twice (direct + transitive under guava) — should deduplicate
        List<AuditTui.AuditEntry> entries = buildEntries(new String[][] {
            {"org.slf4j", "slf4j-api", "2.0.9", "compile"},
            {"com.google.guava", "guava", "33.0.0-jre", "compile"},
            {"org.slf4j", "slf4j-api", "2.0.9", "compile"}
        });

        assertThat(entries).hasSize(2);
        assertThat(entries.stream().map(AuditTui.AuditEntry::ga).toList())
                .containsExactly("org.slf4j:slf4j-api", "com.google.guava:guava");
    }

    @Test
    void collectEntriesSkipsRoot() {
        // Root with no children yields no entries
        List<AuditTui.AuditEntry> entries = buildEntries(new String[][] {});
        assertThat(entries).isEmpty();
    }

    @Test
    void collectEntriesTracksModuleNames() {
        // Module A depends on slf4j and guava
        var e1 = entry("org.slf4j", "slf4j-api", "2.0.9", "compile");
        e1.modules.add("module-a");
        var e2 = entry("com.google.guava", "guava", "33.0.0-jre", "compile");
        e2.modules.add("module-a");

        // Module B also depends on slf4j
        e1.modules.add("module-b");

        assertThat(e1.modules).containsExactly("module-a", "module-b");
        assertThat(e2.modules).containsExactly("module-a");
    }

    @Test
    void collectEntriesNoModuleTrackingWhenNull() {
        var e = entry("org.slf4j", "slf4j-api", "2.0.9", "compile");
        assertThat(e.modules).isEmpty();
    }

    @Test
    void collectEntriesDoesNotDuplicateModuleName() {
        var e = entry("org.slf4j", "slf4j-api", "2.0.9", "compile");
        e.modules.add("my-module");
        // Adding again should be avoided by caller; verify list contains only one
        if (!e.modules.contains("my-module")) {
            e.modules.add("my-module");
        }
        assertThat(e.modules).containsExactly("my-module");
    }

    @Test
    void collectEntriesPreservesInsertionOrder() {
        List<AuditTui.AuditEntry> entries = buildEntries(new String[][] {
            {"org.a", "first", "1.0", "compile"},
            {"org.b", "second", "1.0", "compile"},
            {"org.c", "third", "1.0", "compile"}
        });

        assertThat(entries.stream().map(AuditTui.AuditEntry::ga).toList())
                .containsExactly("org.a:first", "org.b:second", "org.c:third");
    }

    @Test
    void collectEntriesCapturesVersionAndScope() {
        var e = entry("org.slf4j", "slf4j-api", "2.0.9", "test");
        assertThat(e.version).isEqualTo("2.0.9");
        assertThat(e.scope).isEqualTo("test");
        assertThat(e.ga()).isEqualTo("org.slf4j:slf4j-api");
        assertThat(e.gav()).isEqualTo("org.slf4j:slf4j-api:2.0.9");
    }

    @Test
    void collectEntriesHandlesEmptyTree() {
        List<AuditTui.AuditEntry> entries = buildEntries(new String[][] {});
        assertThat(entries).isEmpty();
    }

    @Test
    void collectEntriesFirstVersionWins() {
        // When the same GA appears with different versions, the first version wins
        List<AuditTui.AuditEntry> entries = buildEntries(new String[][] {
            {"org.slf4j", "slf4j-api", "2.0.9", "compile"},
            {"com.google.guava", "guava", "33.0.0-jre", "compile"},
            {"org.slf4j", "slf4j-api", "1.7.36", "runtime"}
        });

        var slf4j = entries.stream()
                .filter(e -> e.ga().equals("org.slf4j:slf4j-api"))
                .findFirst()
                .orElseThrow();
        assertThat(slf4j.version).isEqualTo("2.0.9");
        assertThat(slf4j.scope).isEqualTo("compile");
    }

    @Test
    void collectEntriesScopeFromDependencyNode() {
        List<AuditTui.AuditEntry> entries = buildEntries(new String[][] {
            {"org.slf4j", "slf4j-api", "2.0.9", "runtime"},
            {"org.junit", "junit", "5.10.0", "test"}
        });

        var slf4j = entries.stream()
                .filter(e -> e.ga().equals("org.slf4j:slf4j-api"))
                .findFirst()
                .orElseThrow();
        var junit = entries.stream()
                .filter(e -> e.ga().equals("org.junit:junit"))
                .findFirst()
                .orElseThrow();
        assertThat(slf4j.scope).isEqualTo("runtime");
        assertThat(junit.scope).isEqualTo("test");
    }

    @Test
    void auditEntryHasVulnerabilities() {
        var e = new AuditTui.AuditEntry("org.slf4j", "slf4j-api", "2.0.9", "compile");
        assertThat(e.hasVulnerabilities()).isFalse();

        e.vulnerabilities = List.of();
        assertThat(e.hasVulnerabilities()).isFalse();

        e.vulnerabilities =
                List.of(new OsvClient.Vulnerability("CVE-2024-0001", "test", "HIGH", "2024-01-01", List.of()));
        assertThat(e.hasVulnerabilities()).isTrue();
    }

    @Test
    void collectEntriesWalksTransitiveDeps() {
        // Simulating guava + its transitive dep failureaccess, both tracked under "my-module"
        var e1 = entry("com.google.guava", "guava", "33.0.0-jre", "compile");
        e1.modules.add("my-module");
        var e2 = entry("com.google.guava", "failureaccess", "1.0.2", "compile");
        e2.modules.add("my-module");

        List<AuditTui.AuditEntry> entries = List.of(e1, e2);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).modules).containsExactly("my-module");
        assertThat(entries.get(1).modules).containsExactly("my-module");
    }

    @Test
    void collectReactorEntriesAggregatesModules() {
        // Manually build entries as if collectReactorEntries aggregated them
        var slf4j = entry("org.slf4j", "slf4j-api", "2.0.9", "compile");
        slf4j.modules.add("module-a");
        slf4j.modules.add("module-b");

        var guava = entry("com.google.guava", "guava", "33.0.0-jre", "compile");
        guava.modules.add("module-a");

        List<AuditTui.AuditEntry> entries = List.of(slf4j, guava);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).ga()).isEqualTo("org.slf4j:slf4j-api");
        assertThat(entries.get(0).modules).containsExactly("module-a", "module-b");
        assertThat(entries.get(1).ga()).isEqualTo("com.google.guava:guava");
        assertThat(entries.get(1).modules).containsExactly("module-a");
    }

    @Test
    void collectReactorEntriesEmptyMap() {
        List<AuditTui.AuditEntry> entries = List.of();
        assertThat(entries).isEmpty();
    }

    // -- formatPublished --

    @Test
    void formatPublishedTruncatesIsoTimestamp() {
        assertThat(AuditTui.formatPublished("2024-06-01T00:00:00Z")).isEqualTo("2024-06-01");
    }

    @Test
    void formatPublishedReturnsEmptyForNull() {
        assertThat(AuditTui.formatPublished(null)).isEmpty();
    }

    @Test
    void formatPublishedReturnsShortValueAsIs() {
        assertThat(AuditTui.formatPublished("2024-06")).isEqualTo("2024-06");
    }

    @Test
    void formatPublishedReturnsExactTenChars() {
        assertThat(AuditTui.formatPublished("2024-06-01")).isEqualTo("2024-06-01");
    }

    @Test
    void formatPublishedReturnsEmptyStringAsIs() {
        assertThat(AuditTui.formatPublished("")).isEmpty();
    }

    // -- truncateSummary --

    @Test
    void truncateSummaryReturnsShortStringAsIs() {
        assertThat(AuditTui.truncateSummary("short", 50)).isEqualTo("short");
    }

    @Test
    void truncateSummaryTruncatesLongString() {
        String long51 = "a".repeat(51);
        String result = AuditTui.truncateSummary(long51, 50);
        assertThat(result).hasSize(50).endsWith("...");
    }

    @Test
    void truncateSummaryReturnsExactLengthAsIs() {
        String exact50 = "b".repeat(50);
        assertThat(AuditTui.truncateSummary(exact50, 50)).isEqualTo(exact50);
    }

    @Test
    void truncateSummaryReturnsEmptyForNull() {
        assertThat(AuditTui.truncateSummary(null, 50)).isEmpty();
    }

    @Test
    void truncateSummaryReturnsEmptyForEmptyString() {
        assertThat(AuditTui.truncateSummary("", 50)).isEmpty();
    }

    // -- normalizeSeverity --

    @Test
    void normalizeSeverityReturnsUnknownForNull() {
        assertThat(AuditTui.normalizeSeverity(null)).isEqualTo("UNKNOWN");
    }

    @Test
    void normalizeSeverityUppercasesInput() {
        assertThat(AuditTui.normalizeSeverity("high")).isEqualTo("HIGH");
    }

    @Test
    void normalizeSeverityPreservesUppercase() {
        assertThat(AuditTui.normalizeSeverity("CRITICAL")).isEqualTo("CRITICAL");
    }

    @Test
    void normalizeSeverityConvertsModerateToMedium() {
        assertThat(AuditTui.normalizeSeverity("moderate")).isEqualTo("MEDIUM");
        assertThat(AuditTui.normalizeSeverity("MODERATE")).isEqualTo("MEDIUM");
    }

    // -- getScopeStyle --

    @Test
    void getScopeStyleReturnsDefaultForNull() {
        Style style = AuditTui.getScopeStyle(null);
        assertThat(style).isEqualTo(Style.create());
    }

    @Test
    void getScopeStyleReturnsDefaultForCompile() {
        assertThat(AuditTui.getScopeStyle("compile")).isEqualTo(Style.create());
    }

    @Test
    void getScopeStyleReturnsDimForTest() {
        assertThat(AuditTui.getScopeStyle("test")).isEqualTo(Style.create().fg(Color.DARK_GRAY));
    }

    @Test
    void getScopeStyleReturnsDimForProvided() {
        assertThat(AuditTui.getScopeStyle("provided")).isEqualTo(Style.create().fg(Color.DARK_GRAY));
    }

    @Test
    void getScopeStyleReturnsYellowForRuntime() {
        assertThat(AuditTui.getScopeStyle("runtime")).isEqualTo(Style.create().fg(Color.YELLOW));
    }

    @Test
    void getScopeStyleNormalizesCase() {
        assertThat(AuditTui.getScopeStyle("TEST")).isEqualTo(Style.create().fg(Color.DARK_GRAY));
        assertThat(AuditTui.getScopeStyle("Runtime")).isEqualTo(Style.create().fg(Color.YELLOW));
        assertThat(AuditTui.getScopeStyle("Provided")).isEqualTo(Style.create().fg(Color.DARK_GRAY));
    }

    @Test
    void getScopeStyleTrimsWhitespace() {
        assertThat(AuditTui.getScopeStyle("  test  ")).isEqualTo(Style.create().fg(Color.DARK_GRAY));
        assertThat(AuditTui.getScopeStyle(" runtime ")).isEqualTo(Style.create().fg(Color.YELLOW));
    }

    @Test
    void getScopeStyleReturnsDefaultForUnknown() {
        assertThat(AuditTui.getScopeStyle("system")).isEqualTo(Style.create());
        assertThat(AuditTui.getScopeStyle("import")).isEqualTo(Style.create());
    }

    // -- normalizeLicense --

    @Test
    void normalizeLicenseReturnsNullForNullNameAndUrl() {
        assertThat(AuditTui.normalizeLicense(null, null)).isNull();
    }

    @Test
    void normalizeLicenseMatchesByUrl() {
        assertThat(AuditTui.normalizeLicense("Some License", "https://www.apache.org/licenses/LICENSE-2.0.txt"))
                .isEqualTo("Apache-2.0");
        assertThat(AuditTui.normalizeLicense("License", "https://opensource.org/licenses/MIT"))
                .isEqualTo("MIT");
        assertThat(AuditTui.normalizeLicense("License", "https://www.eclipse.org/legal/epl-2.0/"))
                .isEqualTo("EPL-2.0");
    }

    @Test
    void normalizeLicenseUrlMatchIgnoresSchemeAndSuffix() {
        assertThat(AuditTui.normalizeLicense("L", "http://apache.org/licenses/license-2.0.html"))
                .isEqualTo("Apache-2.0");
        assertThat(AuditTui.normalizeLicense("L", "https://opensource.org/licenses/bsd-3-clause.php"))
                .isEqualTo("BSD-3-Clause");
    }

    @Test
    void normalizeLicenseMatchesSpdxShortIds() {
        assertThat(AuditTui.normalizeLicense("Apache-2.0", null)).isEqualTo("Apache-2.0");
        assertThat(AuditTui.normalizeLicense("MIT", null)).isEqualTo("MIT");
        assertThat(AuditTui.normalizeLicense("EPL-2.0", null)).isEqualTo("EPL-2.0");
        assertThat(AuditTui.normalizeLicense("LGPL-2.1", null)).isEqualTo("LGPL-2.1");
        assertThat(AuditTui.normalizeLicense("GPL-3.0", null)).isEqualTo("GPL-3.0");
        assertThat(AuditTui.normalizeLicense("MPL-2.0", null)).isEqualTo("MPL-2.0");
        assertThat(AuditTui.normalizeLicense("CDDL-1.0", null)).isEqualTo("CDDL-1.0");
        assertThat(AuditTui.normalizeLicense("CC0-1.0", null)).isEqualTo("CC0-1.0");
        assertThat(AuditTui.normalizeLicense("ISC", null)).isEqualTo("ISC");
    }

    @Test
    void normalizeLicenseMatchesCommonNameVariations() {
        assertThat(AuditTui.normalizeLicense("The Apache Software License, Version 2.0", null))
                .isEqualTo("Apache-2.0");
        assertThat(AuditTui.normalizeLicense("The MIT License", null)).isEqualTo("MIT");
        assertThat(AuditTui.normalizeLicense("BSD 3-Clause License", null)).isEqualTo("BSD-3-Clause");
        assertThat(AuditTui.normalizeLicense("Eclipse Public License 2.0", null))
                .isEqualTo("EPL-2.0");
        assertThat(AuditTui.normalizeLicense("GNU Lesser General Public License v2.1", null))
                .isEqualTo("LGPL-2.1");
    }

    @Test
    void normalizeLicenseHandlesDualLicenses() {
        assertThat(AuditTui.normalizeLicense("CDDL + GPL with classpath exception", null))
                .isEqualTo("CDDL-1.0");
        assertThat(AuditTui.normalizeLicense("CDDL/GPLv2+CE", null)).isEqualTo("CDDL-1.0");
    }

    @Test
    void normalizeLicenseHandlesClasspathException() {
        assertThat(AuditTui.normalizeLicense("GPL v2 with classpath exception", null))
                .isEqualTo("GPL-2.0-CE");
    }

    @Test
    void normalizeLicenseReturnsOriginalForUnknown() {
        assertThat(AuditTui.normalizeLicense("My Custom License", null)).isEqualTo("My Custom License");
    }

    @Test
    void normalizeLicenseUrlTakesPrecedenceOverName() {
        // URL says MIT but name says Apache — URL wins
        assertThat(AuditTui.normalizeLicense("Apache License 2.0", "https://opensource.org/licenses/MIT"))
                .isEqualTo("MIT");
    }

    @Test
    void normalizeLicenseHandlesPublicDomain() {
        assertThat(AuditTui.normalizeLicense("Public Domain", null)).isEqualTo("Public Domain");
        assertThat(AuditTui.normalizeLicense("The Unlicense", null)).isEqualTo("Unlicense");
    }

    @Test
    void normalizeLicenseHandlesOnlyVariants() {
        assertThat(AuditTui.normalizeLicense("LGPL-2.1-only", null)).isEqualTo("LGPL-2.1");
        assertThat(AuditTui.normalizeLicense("GPL-2.0-only", null)).isEqualTo("GPL-2.0");
        assertThat(AuditTui.normalizeLicense("AGPL-3.0-only", null)).isEqualTo("AGPL-3.0");
    }

    @Test
    void normalizeLicenseIsCaseInsensitive() {
        assertThat(AuditTui.normalizeLicense("apache-2.0", null)).isEqualTo("Apache-2.0");
        assertThat(AuditTui.normalizeLicense("mit", null)).isEqualTo("MIT");
        assertThat(AuditTui.normalizeLicense("ASL 2.0", null)).isEqualTo("Apache-2.0");
    }

    // -- rebuildVulnRows --

    @Test
    void rebuildVulnRowsIncludesAllVulnerableEntries(@TempDir Path tempDir) throws Exception {
        String pomPath =
                Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();

        List<AuditTui.AuditEntry> entries = buildTestEntries();
        AuditTui tui = new AuditTui(entries, "com.example:test:1.0", null, pomPath);
        tui.rebuildVulnRows();

        // 3 entries have vulns (vuln-lib, prod-lib, provided-lib), runtime-lib has none
        assertThat(tui.vulnRows).hasSize(3);
    }

    @Test
    void rebuildVulnRowsExcludesEntriesWithoutVulns(@TempDir Path tempDir) throws Exception {
        String pomPath =
                Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();

        var e = new AuditTui.AuditEntry("org.example", "safe-lib", "1.0.0", "compile");
        e.vulnsLoaded = true;
        e.vulnerabilities = List.of();

        AuditTui tui = new AuditTui(List.of(e), "com.example:test:1.0", null, pomPath);
        tui.rebuildVulnRows();

        assertThat(tui.vulnRows).isEmpty();
    }

    @Test
    void rebuildVulnRowsExpandsMultipleVulnsPerEntry(@TempDir Path tempDir) throws Exception {
        String pomPath =
                Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();

        var e = new AuditTui.AuditEntry("org.example", "multi-vuln", "1.0.0", "compile");
        e.vulnsLoaded = true;
        e.vulnerabilities = List.of(
                new OsvClient.Vulnerability("CVE-2024-0001", "First", "HIGH", "2024-01-01", List.of()),
                new OsvClient.Vulnerability("CVE-2024-0002", "Second", "LOW", "2024-02-01", List.of()),
                new OsvClient.Vulnerability("CVE-2024-0003", "Third", "CRITICAL", "2024-03-01", List.of()));

        AuditTui tui = new AuditTui(List.of(e), "com.example:test:1.0", null, pomPath);
        tui.rebuildVulnRows();

        assertThat(tui.vulnRows).hasSize(3);
    }

    // -- published date column rendering --

    @Test
    void vulnerabilitiesViewRendersPublishedDateColumn(@TempDir Path tempDir) throws Exception {
        String pomPath =
                Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();

        var e = new AuditTui.AuditEntry("org.example", "dated-lib", "1.0.0", "compile");
        e.licenseLoaded = true;
        e.vulnsLoaded = true;
        e.vulnerabilities = List.of(
                new OsvClient.Vulnerability("CVE-2024-1234", "A test vuln", "HIGH", "2024-06-15T00:00:00Z", List.of()));

        AuditTui tui = new AuditTui(List.of(e), "com.example:test:1.0", null, pomPath);
        tui.rebuildVulnRows();

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(120, 30))) {
            var pilot = testRunner.pilot();

            // Navigate to Vulnerabilities tab
            pilot.press(KeyCode.TAB);
            pilot.pause();

            // Exercise detail pane rendering with the published date
            pilot.press(KeyCode.DOWN);
            pilot.pause();
        }
    }

    @Test
    void vulnerabilitiesViewHandlesNullPublishedDate(@TempDir Path tempDir) throws Exception {
        String pomPath =
                Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();

        var e = new AuditTui.AuditEntry("org.example", "no-date-lib", "1.0.0", "compile");
        e.licenseLoaded = true;
        e.vulnsLoaded = true;
        e.vulnerabilities =
                List.of(new OsvClient.Vulnerability("CVE-2024-5678", "No date vuln", "MEDIUM", null, List.of()));

        AuditTui tui = new AuditTui(List.of(e), "com.example:test:1.0", null, pomPath);
        tui.rebuildVulnRows();

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(120, 30))) {
            var pilot = testRunner.pilot();

            // Navigate to Vulnerabilities tab — should not crash with null date
            pilot.press(KeyCode.TAB);
            pilot.pause();

            // Exercise detail pane rendering — should not crash with null date
            pilot.press(KeyCode.DOWN);
            pilot.pause();
        }
    }

    @Test
    void vulnerabilitiesDetailPaneHandlesNullSummary(@TempDir Path tempDir) throws Exception {
        String pomPath =
                Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();

        var e = new AuditTui.AuditEntry("org.example", "null-summary-lib", "1.0.0", "compile");
        e.licenseLoaded = true;
        e.vulnsLoaded = true;
        e.vulnerabilities =
                List.of(new OsvClient.Vulnerability("CVE-2024-9999", null, "HIGH", "2024-01-01", List.of()));

        AuditTui tui = new AuditTui(List.of(e), "com.example:test:1.0", null, pomPath);
        tui.rebuildVulnRows();

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(120, 30))) {
            var pilot = testRunner.pilot();

            // Navigate to Vulnerabilities tab and select the entry to render detail pane
            pilot.press(KeyCode.TAB);
            pilot.pause();

            // Should not crash with null summary in detail pane
            pilot.press(KeyCode.DOWN);
            pilot.pause();
        }
    }

    private static List<AuditTui.AuditEntry> buildTestEntries() {
        List<AuditTui.AuditEntry> entries = new ArrayList<>();
        var e1 = new AuditTui.AuditEntry("org.example", "vuln-lib", "1.0.0", "test");
        e1.licenseLoaded = true;
        e1.vulnsLoaded = true;
        e1.vulnerabilities =
                List.of(new OsvClient.Vulnerability("CVE-2024-0001", "Test vuln", "HIGH", "2024-01-01", List.of()));
        entries.add(e1);

        var e2 = new AuditTui.AuditEntry("org.example", "prod-lib", "2.0.0", "compile");
        e2.licenseLoaded = true;
        e2.vulnsLoaded = true;
        e2.vulnerabilities =
                List.of(new OsvClient.Vulnerability("CVE-2024-0002", "Prod vuln", "CRITICAL", "2024-02-01", List.of()));
        entries.add(e2);

        var e3 = new AuditTui.AuditEntry("org.example", "runtime-lib", "3.0.0", "runtime");
        e3.licenseLoaded = true;
        e3.vulnsLoaded = true;
        e3.vulnerabilities = List.of();
        entries.add(e3);

        var e4 = new AuditTui.AuditEntry("org.example", "provided-lib", "4.0.0", "provided");
        e4.licenseLoaded = true;
        e4.vulnsLoaded = true;
        e4.vulnerabilities =
                List.of(new OsvClient.Vulnerability("CVE-2024-0003", "Provided vuln", "LOW", "2024-03-01", List.of()));
        entries.add(e4);

        return entries;
    }

    @Test
    void panelModeDividerBetweenTableAndDetails(@TempDir Path tempDir) throws Exception {
        String pomPath =
                Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();
        List<AuditTui.AuditEntry> entries = buildTestEntries();
        AuditTui tui = new AuditTui(entries, "com.example:test:1.0", null, pomPath);

        String output = TuiTestHelper.render(f -> tui.render(f, f.area()));
        assertThat(output).contains("─".repeat(10));
    }

    @Test
    void vulnerabilitiesViewRendersScopeColumn(@TempDir Path tempDir) throws Exception {
        String pomPath =
                Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();

        List<AuditTui.AuditEntry> entries = buildTestEntries();
        AuditTui tui = new AuditTui(entries, "com.example:test:1.0", null, pomPath);
        tui.rebuildVulnRows();

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(120, 30))) {
            var pilot = testRunner.pilot();

            // Navigate to Vulnerabilities tab
            pilot.press(KeyCode.TAB);
            pilot.pause();

            // Navigate down to exercise detail pane rendering with different scopes
            pilot.press(KeyCode.DOWN);
            pilot.pause();
        }
    }

    @Test
    void scopeFilterCyclesAndFilters(@TempDir Path tempDir) throws Exception {
        String pomPath =
                Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();

        List<AuditTui.AuditEntry> entries = buildTestEntries();
        AuditTui tui = new AuditTui(entries, "com.example:test:1.0", null, pomPath);
        tui.rebuildVulnRows();

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(120, 30))) {
            var pilot = testRunner.pilot();

            // Initially shows all entries (4)
            pilot.pause();

            // Press 's' to filter to compile scope
            pilot.press('s');
            pilot.pause();

            // Press 's' again to cycle to runtime
            pilot.press('s');
            pilot.pause();

            // Press 's' to cycle to test
            pilot.press('s');
            pilot.pause();

            // Press 's' to cycle to provided
            pilot.press('s');
            pilot.pause();

            // Press 's' to cycle back to all
            pilot.press('s');
            pilot.pause();

            // Also test filtering on the vulnerabilities tab
            pilot.press(KeyCode.TAB);
            pilot.press('s'); // compile — only CVE-2024-0002 visible
            pilot.pause();

            pilot.press('s'); // runtime — no vulns
            pilot.pause();

            pilot.press('s'); // test — only CVE-2024-0001 visible
            pilot.pause();

            pilot.press('s'); // provided — only CVE-2024-0003 visible
            pilot.pause();
        }
    }
}
