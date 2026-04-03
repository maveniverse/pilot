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

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Interactive TUI for license and security audit.
 */
class AuditTui {

    static class AuditEntry {
        final String groupId;
        final String artifactId;
        final String version;
        final String scope;
        String license;
        List<OsvClient.Vulnerability> vulnerabilities;
        boolean licenseLoaded;
        boolean vulnsLoaded;

        AuditEntry(String groupId, String artifactId, String version, String scope) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.scope = scope != null ? scope : "compile";
        }

        String ga() {
            return groupId + ":" + artifactId;
        }

        String gav() {
            return groupId + ":" + artifactId + ":" + version;
        }

        boolean hasVulnerabilities() {
            return vulnerabilities != null && !vulnerabilities.isEmpty();
        }
    }

    private enum View {
        LICENSES,
        VULNERABILITIES
    }

    private final List<AuditEntry> entries;
    private final String projectGav;
    private final TableState tableState = new TableState();
    private final ExecutorService httpPool = Executors.newFixedThreadPool(5);
    private final OsvClient osvClient = new OsvClient();

    private View view = View.LICENSES;
    private int licensesLoaded = 0;
    private int vulnsLoaded = 0;
    private int vulnCount = 0;
    private String status;

    private TuiRunner runner;

    AuditTui(List<AuditEntry> entries, String projectGav) {
        this.entries = entries;
        this.projectGav = projectGav;
        this.status = "Loading license and vulnerability data\u2026";
        if (!entries.isEmpty()) {
            tableState.select(0);
        }
    }

    void run() throws Exception {
        var configured = TuiRunner.builder()
                .eventHandler(this::handleEvent)
                .renderer(this::render)
                .tickRate(Duration.ofMillis(100))
                .build();
        try {
            runner = configured.runner();
            fetchAllData();
            configured.run();
        } finally {
            configured.close();
            httpPool.shutdownNow();
        }
    }

    private void fetchAllData() {
        for (var entry : entries) {
            // Fetch license info
            CompletableFuture.supplyAsync(
                            () -> SearchTui.fetchPomFromCentral(entry.groupId, entry.artifactId, entry.version),
                            httpPool)
                    .thenAccept(info -> runner.runOnRenderThread(() -> {
                        if (info != null && info.license != null) {
                            entry.license = info.license;
                        }
                        entry.licenseLoaded = true;
                        licensesLoaded++;
                        updateStatus();
                    }));

            // Fetch vulnerability info
            CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return osvClient.query(entry.groupId, entry.artifactId, entry.version);
                                } catch (Exception e) {
                                    return List.<OsvClient.Vulnerability>of();
                                }
                            },
                            httpPool)
                    .thenAccept(vulns -> runner.runOnRenderThread(() -> {
                        entry.vulnerabilities = vulns;
                        entry.vulnsLoaded = true;
                        vulnsLoaded++;
                        if (!vulns.isEmpty()) {
                            vulnCount += vulns.size();
                        }
                        updateStatus();
                    }));
        }
    }

    private void updateStatus() {
        if (licensesLoaded >= entries.size() && vulnsLoaded >= entries.size()) {
            long withLicense = entries.stream().filter(e -> e.license != null).count();
            status = withLicense + "/" + entries.size() + " with license info, " + vulnCount + " vulnerabilities found";
        } else {
            status = "Loading\u2026 licenses: " + licensesLoaded + "/" + entries.size() + ", vulnerabilities: "
                    + vulnsLoaded + "/" + entries.size();
        }
    }

    boolean handleEvent(Event event, TuiRunner runner) {
        if (!(event instanceof KeyEvent key)) {
            return true;
        }

        if (key.isCtrlC() || key.isKey(KeyCode.ESCAPE) || key.isCharIgnoreCase('q')) {
            runner.quit();
            return true;
        }

        if (key.isUp()) {
            tableState.selectPrevious();
            return true;
        }
        if (key.isDown()) {
            tableState.selectNext(entries.size());
            return true;
        }

        if (key.isKey(KeyCode.TAB)) {
            view = (view == View.LICENSES) ? View.VULNERABILITIES : View.LICENSES;
            return true;
        }

        return false;
    }

    // -- Rendering --

    void render(Frame frame) {
        var zones = Layout.vertical()
                .constraints(Constraint.length(3), Constraint.fill(), Constraint.length(3))
                .split(frame.area());

        renderHeader(frame, zones.get(0));

        if (view == View.LICENSES) {
            renderLicenses(frame, zones.get(1));
        } else {
            renderVulnerabilities(frame, zones.get(1));
        }

        renderInfoBar(frame, zones.get(2));
    }

    private void renderHeader(Frame frame, Rect area) {
        Block block = Block.builder()
                .title(" Pilot \u2014 License & Security Audit ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().cyan())
                .build();

        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" " + projectGav).bold().cyan());
        spans.add(Span.raw("  "));
        spans.add(Span.raw("[" + (view == View.LICENSES ? "\u25B8 " : "  ") + "Licenses]")
                .fg(view == View.LICENSES ? Color.YELLOW : Color.DARK_GRAY));
        spans.add(Span.raw("  "));
        spans.add(Span.raw("[" + (view == View.VULNERABILITIES ? "\u25B8 " : "  ") + "Vulnerabilities"
                        + (vulnCount > 0 ? " (" + vulnCount + ")" : "") + "]")
                .fg(view == View.VULNERABILITIES ? (vulnCount > 0 ? Color.RED : Color.YELLOW) : Color.DARK_GRAY));

        Paragraph header = Paragraph.builder()
                .text(dev.tamboui.text.Text.from(Line.from(spans)))
                .block(block)
                .build();
        frame.renderWidget(header, area);
    }

    private void renderLicenses(Frame frame, Rect area) {
        Block block = Block.builder()
                .title(" Licenses (" + entries.size() + " dependencies) ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build();

        Row header = Row.from("groupId:artifactId", "version", "license", "scope")
                .style(Style.create().bold().yellow());

        List<Row> rows = new ArrayList<>();
        for (var entry : entries) {
            String license = entry.license != null ? entry.license : (entry.licenseLoaded ? "-" : "\u2026");
            Style style = getLicenseStyle(entry.license);
            rows.add(Row.from(entry.ga(), entry.version, license, entry.scope).style(style));
        }

        Table table = Table.builder()
                .header(header)
                .rows(rows)
                .widths(
                        Constraint.percentage(40), Constraint.percentage(15),
                        Constraint.percentage(30), Constraint.percentage(15))
                .highlightStyle(Style.create().reversed().bold())
                .highlightSymbol("\u25B8 ")
                .block(block)
                .build();

        frame.renderStatefulWidget(table, area, tableState);
    }

    private void renderVulnerabilities(Frame frame, Rect area) {
        Block block = Block.builder()
                .title(" Vulnerabilities ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(
                        vulnCount > 0
                                ? Style.create().fg(Color.RED)
                                : Style.create().fg(Color.DARK_GRAY))
                .build();

        // Filter to entries with vulnerabilities
        List<AuditEntry> vulnEntries =
                entries.stream().filter(AuditEntry::hasVulnerabilities).toList();

        if (vulnEntries.isEmpty()) {
            String msg = (vulnsLoaded >= entries.size())
                    ? "No known vulnerabilities found \u2713"
                    : "Checking vulnerabilities\u2026 " + vulnsLoaded + "/" + entries.size();
            Paragraph empty =
                    Paragraph.builder().text(msg).block(block).centered().build();
            frame.renderWidget(empty, area);
            return;
        }

        List<Row> rows = new ArrayList<>();
        for (var entry : vulnEntries) {
            for (var vuln : entry.vulnerabilities) {
                String aliases = vuln.aliases.isEmpty() ? "" : String.join(", ", vuln.aliases);
                String summary = vuln.summary.length() > 60 ? vuln.summary.substring(0, 57) + "..." : vuln.summary;

                rows.add(Row.from(entry.ga() + ":" + entry.version, vuln.id, summary, aliases)
                        .style(Style.create().fg(Color.RED)));
            }
        }

        Row header = Row.from("artifact", "CVE/ID", "summary", "aliases")
                .style(Style.create().bold().yellow());

        Table table = Table.builder()
                .header(header)
                .rows(rows)
                .widths(
                        Constraint.percentage(30), Constraint.percentage(15),
                        Constraint.percentage(35), Constraint.percentage(20))
                .highlightStyle(Style.create().reversed().bold())
                .highlightSymbol("\u25B8 ")
                .block(block)
                .build();

        frame.renderStatefulWidget(table, area, tableState);
    }

    private Style getLicenseStyle(String license) {
        if (license == null) return Style.create();
        String lower = license.toLowerCase();
        // Flag potentially restrictive licenses
        if (lower.contains("gpl") && !lower.contains("lgpl")) {
            return Style.create().fg(Color.RED);
        }
        if (lower.contains("agpl")) {
            return Style.create().fg(Color.RED);
        }
        if (lower.contains("apache") || lower.contains("mit") || lower.contains("bsd")) {
            return Style.create().fg(Color.GREEN);
        }
        if (lower.contains("lgpl") || lower.contains("mpl") || lower.contains("epl") || lower.contains("cddl")) {
            return Style.create().fg(Color.YELLOW);
        }
        return Style.create();
    }

    private void renderInfoBar(Frame frame, Rect area) {
        var rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.length(1))
                .split(area);

        List<Span> statusSpans = new ArrayList<>();
        statusSpans.add(Span.raw(" " + status).fg(Color.GREEN));
        frame.renderWidget(Paragraph.from(Line.from(statusSpans)), rows.get(1));

        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" "));
        spans.add(Span.raw("\u2191\u2193").bold());
        spans.add(Span.raw(":Navigate  "));
        spans.add(Span.raw("Tab").bold());
        spans.add(Span.raw(":Licenses/Vulns  "));
        spans.add(Span.raw("q").bold());
        spans.add(Span.raw(":Quit"));

        frame.renderWidget(Paragraph.from(Line.from(spans)), rows.get(2));
    }
}
