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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UpdatesReporterTest {

    private ReactorCollector.AggregatedDependency dep(String g, String a, String current) {
        var d = new ReactorCollector.AggregatedDependency(g, a);
        d.primaryVersion = current;
        return d;
    }

    private ReactorCollector.CollectionResult result(
            List<ReactorCollector.AggregatedDependency> all,
            List<ReactorCollector.PropertyGroup> groups,
            List<ReactorCollector.AggregatedDependency> ungrouped) {
        return new ReactorCollector.CollectionResult(all, groups, ungrouped);
    }

    // -- formatReport --

    @Test
    void formatReportNoUpdates() {
        var d = dep("org.example", "lib", "1.0.0");
        var res = result(List.of(d), List.of(), List.of(d));

        String report = UpdatesReporter.formatReport(res, "org.example:project:1.0");

        assertThat(report)
                .contains("=== Pilot Updates Report ===")
                .contains("No updates available")
                .contains("0 update(s)");
    }

    @Test
    void formatReportWithUpdates() {
        var d1 = dep("org.example", "major-lib", "1.0.0");
        d1.newestVersion = "2.0.0";
        d1.updateType = VersionComparator.UpdateType.MAJOR;
        d1.libYears = 1.5f;

        var d2 = dep("org.example", "minor-lib", "3.0.0");
        d2.newestVersion = "3.1.0";
        d2.updateType = VersionComparator.UpdateType.MINOR;
        d2.libYears = 0.3f;

        var d3 = dep("org.example", "patch-lib", "2.0.0");
        d3.newestVersion = "2.0.1";
        d3.updateType = VersionComparator.UpdateType.PATCH;
        d3.libYears = 0.1f;

        var all = List.of(d1, d2, d3);
        var res = result(all, List.of(), all);

        String report = UpdatesReporter.formatReport(res, "org.example:project:1.0");

        assertThat(report)
                .contains("3 found")
                .contains("MAJOR")
                .contains("MINOR")
                .contains("PATCH")
                .contains("1.0.0")
                .contains("->")
                .contains("2.0.0")
                .contains("3.1.0")
                .contains("2.0.1")
                .contains("3 update(s) available");
    }

    @Test
    void formatReportWithPropertyGroups() {
        var d1 = dep("com.fasterxml.jackson.core", "jackson-databind", "2.14.0");
        d1.newestVersion = "2.17.0";
        d1.updateType = VersionComparator.UpdateType.MINOR;
        d1.propertyName = "jackson.version";

        var d2 = dep("com.fasterxml.jackson.core", "jackson-core", "2.14.0");
        d2.newestVersion = "2.17.0";
        d2.updateType = VersionComparator.UpdateType.MINOR;
        d2.propertyName = "jackson.version";

        var group = new ReactorCollector.PropertyGroup("jackson.version", "${jackson.version}", "2.14.0", null);
        group.dependencies.add(d1);
        group.dependencies.add(d2);
        group.newestVersion = "2.17.0";
        group.updateType = VersionComparator.UpdateType.MINOR;

        var all = List.of(d1, d2);
        var res = result(all, List.of(group), List.of());

        String report = UpdatesReporter.formatReport(res, "org.example:project:1.0");

        assertThat(report)
                .contains("Property Groups")
                .contains("${jackson.version}")
                .contains("2.14.0 -> 2.17.0")
                .contains("2 artifacts");
    }

    @Test
    void formatReportWithLibYears() {
        var d = dep("org.example", "old-lib", "1.0.0");
        d.newestVersion = "2.0.0";
        d.updateType = VersionComparator.UpdateType.MAJOR;
        d.libYears = 2.3f;

        var res = result(List.of(d), List.of(), List.of(d));

        String report = UpdatesReporter.formatReport(res, "test:project:1.0");

        assertThat(report).contains("2.3y").contains("2.3 libyear(s) behind");
    }

    // -- totalLibYears --

    @Test
    void totalLibYearsEmpty() {
        assertThat(UpdatesReporter.totalLibYears(List.of())).isZero();
    }

    @Test
    void totalLibYearsDeduplicatesByGA() {
        var d1 = dep("org.example", "lib", "1.0.0");
        d1.newestVersion = "2.0.0";
        d1.libYears = 1.5f;

        var d2 = dep("org.example", "lib", "1.0.0");
        d2.newestVersion = "2.0.0";
        d2.libYears = 1.5f;

        assertThat(UpdatesReporter.totalLibYears(List.of(d1, d2))).isEqualTo(1.5f);
    }

    @Test
    void totalLibYearsSkipsNegative() {
        var d1 = dep("org.example", "known", "1.0.0");
        d1.newestVersion = "2.0.0";
        d1.libYears = 1.0f;

        var d2 = dep("org.example", "unknown", "1.0.0");
        d2.newestVersion = "2.0.0";
        d2.libYears = -1;

        assertThat(UpdatesReporter.totalLibYears(List.of(d1, d2))).isEqualTo(1.0f);
    }

    @Test
    void totalLibYearsSumsCorrectly() {
        var d1 = dep("org.a", "lib1", "1.0");
        d1.newestVersion = "2.0";
        d1.libYears = 1.5f;

        var d2 = dep("org.b", "lib2", "1.0");
        d2.newestVersion = "2.0";
        d2.libYears = 2.3f;

        assertThat(UpdatesReporter.totalLibYears(List.of(d1, d2))).isEqualTo(3.8f);
    }

    @Test
    void totalLibYearsSkipsDepsWithoutUpdate() {
        var d = dep("org.example", "current", "2.0.0");
        d.libYears = 0.5f;

        assertThat(UpdatesReporter.totalLibYears(List.of(d))).isZero();
    }

    // -- formatCheckFailure --

    @Test
    void formatCheckFailureIncludesThreshold() {
        var d = dep("org.example", "lib", "1.0.0");
        d.newestVersion = "2.0.0";
        d.libYears = 3.5f;

        var res = result(List.of(d), List.of(), List.of(d));
        String msg = UpdatesReporter.formatCheckFailure(res, 2.0f);

        assertThat(msg)
                .contains("3.5")
                .contains("2.0")
                .contains("exceeds threshold")
                .contains("-Dpilot.action=report");
    }

    // -- computePropertyGroupVersions --

    @Test
    void computePropertyGroupVersionsSetsFromDeps() {
        var d1 = dep("org.a", "lib1", "1.0");
        d1.newestVersion = "1.2";
        d1.updateType = VersionComparator.UpdateType.MINOR;

        var group = new ReactorCollector.PropertyGroup("my.version", "${my.version}", "1.0", null);
        group.dependencies.add(d1);

        UpdatesReporter.computePropertyGroupVersions(List.of(group));

        assertThat(group.newestVersion).isEqualTo("1.2");
        assertThat(group.updateType).isEqualTo(VersionComparator.UpdateType.MINOR);
    }

    @Test
    void computePropertyGroupVersionsMultipleDeps() {
        var d1 = dep("org.a", "lib1", "1.0");
        d1.newestVersion = "1.1";
        d1.updateType = VersionComparator.UpdateType.MINOR;

        var d2 = dep("org.a", "lib2", "1.0");
        d2.newestVersion = "2.0";
        d2.updateType = VersionComparator.UpdateType.MAJOR;

        var group = new ReactorCollector.PropertyGroup("my.version", "${my.version}", "1.0", null);
        group.dependencies.add(d1);
        group.dependencies.add(d2);

        UpdatesReporter.computePropertyGroupVersions(List.of(group));

        // Group version is set from dependencies that have updates
        assertThat(group.newestVersion).isNotNull();
        assertThat(group.hasUpdate()).isTrue();
    }

    @Test
    void computePropertyGroupVersionsNoUpdates() {
        var d = dep("org.a", "lib", "1.0");

        var group = new ReactorCollector.PropertyGroup("my.version", "${my.version}", "1.0", null);
        group.dependencies.add(d);

        UpdatesReporter.computePropertyGroupVersions(List.of(group));

        assertThat(group.newestVersion).isNull();
    }

    // -- applyVersionResult --

    @Test
    void applyVersionResultSkipsPreview() {
        var d = dep("org.example", "lib", "1.0.0");

        UpdatesReporter.applyVersionResult(d, List.of("2.0.0-beta", "2.0.0-alpha", "1.1.0"));

        assertThat(d.newestVersion).isEqualTo("1.1.0");
        assertThat(d.updateType).isEqualTo(VersionComparator.UpdateType.MINOR);
    }

    @Test
    void applyVersionResultPicksNewestStable() {
        var d = dep("org.example", "lib", "1.0.0");

        UpdatesReporter.applyVersionResult(d, List.of("3.0.0", "2.0.0", "1.1.0"));

        assertThat(d.newestVersion).isEqualTo("3.0.0");
        assertThat(d.updateType).isEqualTo(VersionComparator.UpdateType.MAJOR);
    }

    @Test
    void applyVersionResultNoNewerVersion() {
        var d = dep("org.example", "lib", "3.0.0");

        UpdatesReporter.applyVersionResult(d, List.of("3.0.0", "2.0.0", "1.0.0"));

        assertThat(d.newestVersion).isNull();
    }

    // -- resolveUpdates --

    @Test
    void resolveUpdatesCallsResolverForEachDep() {
        var d1 = dep("org.a", "lib1", "1.0.0");
        var d2 = dep("org.b", "lib2", "2.0.0");
        List<String> called = new ArrayList<>();

        UpdatesReporter.resolveUpdates(List.of(d1, d2), (g, a) -> {
            called.add(g + ":" + a);
            return List.of("99.0.0");
        });

        assertThat(called).containsExactly("org.a:lib1", "org.b:lib2");
        assertThat(d1.newestVersion).isEqualTo("99.0.0");
        assertThat(d2.newestVersion).isEqualTo("99.0.0");
    }

    @Test
    void resolveUpdatesSurvivesResolverFailure() {
        var d1 = dep("org.a", "lib1", "1.0.0");
        var d2 = dep("org.b", "lib2", "2.0.0");

        UpdatesReporter.resolveUpdates(List.of(d1, d2), (g, a) -> {
            if ("org.a".equals(g)) throw new RuntimeException("fail");
            return List.of("99.0.0");
        });

        assertThat(d1.newestVersion).isNull();
        assertThat(d2.newestVersion).isEqualTo("99.0.0");
    }

    // -- findUpdateLocation --

    @Test
    void findUpdateLocationPrefersManaged() {
        var d = dep("org.example", "lib", "1.0.0");
        var project = new PilotProject(
                "org.example",
                "proj",
                "1.0",
                "jar",
                java.nio.file.Path.of("/proj"),
                java.nio.file.Path.of("/proj/pom.xml"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new java.util.Properties(),
                null,
                null);
        var managedProject = new PilotProject(
                "org.example",
                "parent",
                "1.0",
                "pom",
                java.nio.file.Path.of("/parent"),
                java.nio.file.Path.of("/parent/pom.xml"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new java.util.Properties(),
                null,
                null);
        d.usages.add(new ReactorCollector.ModuleUsage(project, "1.0.0", "compile", false));
        d.usages.add(new ReactorCollector.ModuleUsage(managedProject, "1.0.0", "compile", true));

        var loc = UpdatesReporter.findUpdateLocation(d);

        assertThat(loc.managed()).isTrue();
        assertThat(loc.pomPath()).isEqualTo(java.nio.file.Path.of("/parent/pom.xml"));
    }

    @Test
    void findUpdateLocationFallsBackToFirst() {
        var d = dep("org.example", "lib", "1.0.0");
        var project = new PilotProject(
                "org.example",
                "proj",
                "1.0",
                "jar",
                java.nio.file.Path.of("/proj"),
                java.nio.file.Path.of("/proj/pom.xml"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new java.util.Properties(),
                null,
                null);
        d.usages.add(new ReactorCollector.ModuleUsage(project, "1.0.0", "compile", false));

        var loc = UpdatesReporter.findUpdateLocation(d);

        assertThat(loc.managed()).isFalse();
        assertThat(loc.pomPath()).isEqualTo(java.nio.file.Path.of("/proj/pom.xml"));
    }

    // -- resolveAndFormatReport --

    @Test
    void resolveAndFormatReportCombinesSteps() {
        var d = dep("org.example", "lib", "1.0.0");
        var res = result(List.of(d), List.of(), List.of(d));

        String report = UpdatesReporter.resolveAndFormatReport(res, (g, a) -> List.of("2.0.0"), "test:p:1.0");

        assertThat(report).contains("=== Pilot Updates Report ===").contains("test:p:1.0");
    }

    // -- resolveAndCheck --

    @Test
    void resolveAndCheckReturnsMetrics() {
        var d = dep("org.example", "lib", "1.0.0");
        d.newestVersion = "2.0.0";
        d.updateType = VersionComparator.UpdateType.MAJOR;
        d.libYears = 1.5f;
        var res = result(List.of(d), List.of(), List.of(d));

        var check = UpdatesReporter.resolveAndCheck(res, (g, a) -> List.of("2.0.0"), "test:p:1.0");

        assertThat(check.report).contains("=== Pilot Updates Report ===");
        assertThat(check.updateCount).isEqualTo(1);
        assertThat(check.totalLibYears).isEqualTo(1.5f);
    }

    // -- applyAllUpdates --

    @Test
    void applyAllUpdatesWithNoUpdatesReturnsZero() {
        var d = dep("org.example", "lib", "1.0.0");
        var res = result(List.of(d), List.of(), List.of(d));
        List<String> logs = new ArrayList<>();

        int count = UpdatesReporter.applyAllUpdates(res, UpdatesReporter.defaultSessionProvider(), logs::add);

        assertThat(count).isZero();
        assertThat(logs).isEmpty();
    }

    // -- defaultSessionProvider --

    @Test
    void defaultSessionProviderReturnsNonNull() {
        assertThat(UpdatesReporter.defaultSessionProvider()).isNotNull();
    }

    // -- CheckResult --

    @Test
    void checkResultFieldsAccessible() {
        var cr = new UpdatesReporter.CheckResult("report", 2.5f, 3);

        assertThat(cr.report).isEqualTo("report");
        assertThat(cr.totalLibYears).isEqualTo(2.5f);
        assertThat(cr.updateCount).isEqualTo(3);
    }

    @Test
    void checkResultFormatFailureIncludesHint() {
        var cr = new UpdatesReporter.CheckResult("report", 5.0f, 2);
        String msg = cr.formatFailure(3.0f);

        assertThat(msg).contains("5.0").contains("3.0").contains("-Dpilot.action=report");
    }
}
