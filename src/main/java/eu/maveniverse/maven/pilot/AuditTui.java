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
import eu.maveniverse.domtrip.Document;
import eu.maveniverse.domtrip.maven.Coordinates;
import eu.maveniverse.domtrip.maven.PomEditor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Interactive TUI for license and security audit.
 */
class AuditTui {

    static class AuditEntry {
        final String groupId;
        final String artifactId;
        final String version;
        final String scope;
        final List<String> modules = new ArrayList<>();
        String license;
        String licenseUrl;
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
        BY_LICENSE,
        VULNERABILITIES
    }

    /** Row in the by-license view: either a license group header or a dependency. */
    private static class LicenseRow {
        final String licenseName;
        final String licenseUrl;
        final List<AuditEntry> deps; // non-null for group headers
        final AuditEntry entry; // non-null for dependency rows
        boolean expanded;

        static LicenseRow group(String name, String url, List<AuditEntry> deps) {
            var r = new LicenseRow(name, url, deps, null);
            return r;
        }

        static LicenseRow dep(AuditEntry entry) {
            return new LicenseRow(null, null, null, entry);
        }

        private LicenseRow(String licenseName, String licenseUrl, List<AuditEntry> deps, AuditEntry entry) {
            this.licenseName = licenseName;
            this.licenseUrl = licenseUrl;
            this.deps = deps;
            this.entry = entry;
        }

        boolean isGroup() {
            return deps != null;
        }
    }

    private final List<AuditEntry> entries;
    private final String projectGav;
    private final DependencyTreeModel treeModel;
    private final String pomPath;
    private final String originalPomContent;
    private final PomEditor editor;
    private final TableState tableState = new TableState();
    private final ExecutorService httpPool = MojoHelper.newHttpPool();
    private final OsvClient osvClient = new OsvClient();

    private final HelpOverlay helpOverlay = new HelpOverlay();
    private final DiffOverlay diffOverlay = new DiffOverlay();
    private final TableState vulnTableState = new TableState();
    private final TableState byLicenseTableState = new TableState();

    private View view = View.LICENSES;
    private int licensesLoaded = 0;
    private int vulnsLoaded = 0;
    private int vulnCount = 0;
    private String status;
    private boolean dirty;
    private boolean pendingQuit;
    private int lastContentHeight;
    private List<VulnRow> vulnRows = new ArrayList<>();
    private List<LicenseRow> byLicenseRows = new ArrayList<>();

    /** Flattened vulnerability row linking back to its parent entry. */
    private record VulnRow(AuditEntry entry, OsvClient.Vulnerability vuln) {}

    private TuiRunner runner;

    AuditTui(List<AuditEntry> entries, String projectGav, DependencyTreeModel treeModel, String pomPath) {
        this.entries = entries;
        this.projectGav = projectGav;
        this.treeModel = treeModel;
        this.pomPath = pomPath;
        String pom;
        try {
            pom = Files.readString(Path.of(pomPath));
        } catch (Exception e) {
            pom = "";
        }
        this.originalPomContent = pom;
        this.editor = pom.isEmpty() ? null : new PomEditor(Document.of(pom));
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
            httpPool.awaitTermination(5, TimeUnit.SECONDS);
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
                            entry.licenseUrl = info.licenseUrl;
                        }
                        entry.licenseLoaded = true;
                        licensesLoaded++;
                        rebuildByLicenseRows();
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
                            rebuildVulnRows();
                        }
                        updateStatus();
                    }));
        }
    }

    private void rebuildVulnRows() {
        vulnRows = new ArrayList<>();
        for (var entry : entries) {
            if (entry.hasVulnerabilities()) {
                for (var vuln : entry.vulnerabilities) {
                    vulnRows.add(new VulnRow(entry, vuln));
                }
            }
        }
        if (!vulnRows.isEmpty() && vulnTableState.selected() == null) {
            vulnTableState.select(0);
        }
    }

    private void rebuildByLicenseRows() {
        // Preserve expansion state
        var expanded = new HashSet<String>();
        for (var row : byLicenseRows) {
            if (row.isGroup() && row.expanded) expanded.add(row.licenseName);
        }

        // Group entries by normalized license name
        var groups = new LinkedHashMap<String, List<AuditEntry>>();
        var urls = new HashMap<String, String>();
        for (var entry : entries) {
            if (!entry.licenseLoaded) continue;
            String key = entry.license != null ? normalizeLicense(entry.license, entry.licenseUrl) : "(not specified)";
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
            if (entry.licenseUrl != null && !urls.containsKey(key)) {
                urls.put(key, entry.licenseUrl);
            }
        }

        // Sort groups by usage count (descending)
        var sortedGroups = new ArrayList<>(groups.entrySet());
        sortedGroups.sort(
                (a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));

        // Build flat row list
        byLicenseRows = new ArrayList<>();
        for (var e : sortedGroups) {
            var group = LicenseRow.group(e.getKey(), urls.get(e.getKey()), e.getValue());
            group.expanded = expanded.contains(e.getKey());
            byLicenseRows.add(group);
            if (group.expanded) {
                for (var dep : e.getValue()) {
                    byLicenseRows.add(LicenseRow.dep(dep));
                }
            }
        }

        if (!byLicenseRows.isEmpty() && byLicenseTableState.selected() == null) {
            byLicenseTableState.select(0);
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

        if (helpOverlay.isActive()) {
            if (helpOverlay.handleKey(key)) return true;
            if (key.isCharIgnoreCase('q') || key.isCtrlC()) {
                requestQuit();
                return true;
            }
            return false;
        }

        // Save prompt mode
        if (pendingQuit) {
            if (key.isCharIgnoreCase('y')) {
                saveAndQuit();
                return true;
            }
            if (key.isCharIgnoreCase('n')) {
                runner.quit();
                return true;
            }
            if (key.isKey(KeyCode.ESCAPE)) {
                pendingQuit = false;
                updateStatus();
                return true;
            }
            return false;
        }

        // Diff overlay mode
        if (diffOverlay.isActive()) {
            if (key.isKey(KeyCode.ESCAPE)) {
                diffOverlay.close();
                return true;
            }
            if (diffOverlay.handleScrollKey(key, lastContentHeight)) return true;
            if (key.isCharIgnoreCase('q') || key.isCtrlC()) {
                requestQuit();
                return true;
            }
            return false;
        }

        if (key.isCtrlC() || key.isKey(KeyCode.ESCAPE) || key.isCharIgnoreCase('q')) {
            requestQuit();
            return true;
        }

        if (key.isUp()) {
            activeTableState().selectPrevious();
            return true;
        }
        if (key.isDown()) {
            activeTableState().selectNext(activeRowCount());
            return true;
        }

        if (key.isKey(KeyCode.TAB)) {
            view = switch (view) {
                case LICENSES -> View.BY_LICENSE;
                case BY_LICENSE -> View.VULNERABILITIES;
                case VULNERABILITIES -> View.LICENSES;
            };
            return true;
        }

        // Expand/collapse and navigate license groups in BY_LICENSE view
        if (view == View.BY_LICENSE && (key.isKey(KeyCode.ENTER) || key.isChar(' '))) {
            int idx = byLicenseTableState.selected() != null ? byLicenseTableState.selected() : -1;
            if (idx >= 0 && idx < byLicenseRows.size() && byLicenseRows.get(idx).isGroup()) {
                byLicenseRows.get(idx).expanded = !byLicenseRows.get(idx).expanded;
                rebuildByLicenseRows();
            }
            return true;
        }
        if (view == View.BY_LICENSE && key.isRight()) {
            int idx = byLicenseTableState.selected() != null ? byLicenseTableState.selected() : -1;
            if (idx >= 0 && idx < byLicenseRows.size() && byLicenseRows.get(idx).isGroup()) {
                if (!byLicenseRows.get(idx).expanded) {
                    byLicenseRows.get(idx).expanded = true;
                    rebuildByLicenseRows();
                } else {
                    // Move to first child
                    byLicenseTableState.selectNext(byLicenseRows.size());
                }
            }
            return true;
        }
        if (view == View.BY_LICENSE && key.isLeft()) {
            int idx = byLicenseTableState.selected() != null ? byLicenseTableState.selected() : -1;
            if (idx >= 0 && idx < byLicenseRows.size()) {
                LicenseRow row = byLicenseRows.get(idx);
                if (row.isGroup() && row.expanded) {
                    row.expanded = false;
                    rebuildByLicenseRows();
                } else if (!row.isGroup()) {
                    // Move to parent group: find nearest preceding group row
                    for (int i = idx - 1; i >= 0; i--) {
                        if (byLicenseRows.get(i).isGroup()) {
                            byLicenseTableState.select(i);
                            break;
                        }
                    }
                }
            }
            return true;
        }

        if (key.isCharIgnoreCase('m')) {
            addManagedDependency();
            return true;
        }

        if (key.isCharIgnoreCase('d')) {
            toggleDiffView();
            return true;
        }

        if (key.isCharIgnoreCase('h')) {
            helpOverlay.open(buildHelp());
            return true;
        }

        return false;
    }

    private AuditEntry selectedEntry() {
        return switch (view) {
            case LICENSES -> {
                int idx = tableState.selected() != null ? tableState.selected() : -1;
                yield (idx >= 0 && idx < entries.size()) ? entries.get(idx) : null;
            }
            case VULNERABILITIES -> {
                int idx = vulnTableState.selected() != null ? vulnTableState.selected() : -1;
                yield (idx >= 0 && idx < vulnRows.size()) ? vulnRows.get(idx).entry : null;
            }
            case BY_LICENSE -> {
                int idx = byLicenseTableState.selected() != null ? byLicenseTableState.selected() : -1;
                yield (idx >= 0
                                && idx < byLicenseRows.size()
                                && !byLicenseRows.get(idx).isGroup())
                        ? byLicenseRows.get(idx).entry
                        : null;
            }
        };
    }

    private void addManagedDependency() {
        AuditEntry entry = selectedEntry();
        if (entry == null || editor == null) {
            status = "No dependency selected";
            return;
        }
        try {
            var coords = Coordinates.of(entry.groupId, entry.artifactId, entry.version);
            editor.dependencies().updateManagedDependency(true, coords);
            dirty = true;
            status = "Added " + entry.ga() + ":" + entry.version + " to dependencyManagement";
        } catch (Exception e) {
            status = "Failed: " + e.getMessage();
        }
    }

    private void requestQuit() {
        if (dirty) {
            pendingQuit = true;
            status = "Save changes to POM? (y/n/Esc)";
        } else {
            runner.quit();
        }
    }

    private void saveAndQuit() {
        try {
            String currentOnDisk = Files.readString(Path.of(pomPath));
            if (!currentOnDisk.equals(originalPomContent)) {
                pendingQuit = false;
                status = "POM modified externally \u2014 save aborted";
                return;
            }
            Files.writeString(Path.of(pomPath), editor.toXml());
            runner.quit();
        } catch (Exception e) {
            pendingQuit = false;
            status = "Failed to save: " + e.getMessage();
        }
    }

    private void toggleDiffView() {
        if (editor == null) return;
        long changes = diffOverlay.open(originalPomContent, editor.toXml());
        status = changes == 0 ? "No changes to show" : changes + " line(s) changed";
    }

    // -- Rendering --

    void render(Frame frame) {
        var zones = Layout.vertical()
                .constraints(Constraint.length(3), Constraint.fill(), Constraint.length(3))
                .split(frame.area());

        renderHeader(frame, zones.get(0));

        lastContentHeight = zones.get(1).height();
        if (helpOverlay.isActive()) {
            helpOverlay.render(frame, zones.get(1));
        } else if (diffOverlay.isActive()) {
            diffOverlay.render(frame, zones.get(1), " POM Changes ");
        } else {
            switch (view) {
                case LICENSES -> renderLicenses(frame, zones.get(1));
                case BY_LICENSE -> renderByLicense(frame, zones.get(1));
                case VULNERABILITIES -> renderVulnerabilities(frame, zones.get(1));
            }
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
        if (dirty) {
            spans.add(Span.raw("  [modified]").fg(Color.YELLOW));
        }
        spans.add(Span.raw("  "));
        spans.add(Span.raw("[" + (view == View.LICENSES ? "\u25B8 " : "  ") + "Licenses]")
                .fg(view == View.LICENSES ? Color.YELLOW : Color.DARK_GRAY));
        spans.add(Span.raw("  "));
        spans.add(Span.raw("[" + (view == View.BY_LICENSE ? "\u25B8 " : "  ") + "By License]")
                .fg(view == View.BY_LICENSE ? Color.YELLOW : Color.DARK_GRAY));
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
        // Split into table + separator + detail pane
        var zones = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1), Constraint.length(6))
                .split(area);

        Block block = Block.builder()
                .title(" Licenses (" + entries.size() + " dependencies) ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build();

        Row header = Row.from("groupId:artifactId", "version", "license", "scope")
                .style(Style.create().bold().yellow());

        List<Row> rows = new ArrayList<>();
        for (var entry : entries) {
            String license = entry.license != null
                    ? normalizeLicense(entry.license, entry.licenseUrl)
                    : (entry.licenseLoaded ? "-" : "\u2026");
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

        frame.renderStatefulWidget(table, zones.get(0), tableState);

        // -- Detail pane --
        renderLicenseDetail(frame, zones.get(2));
    }

    private void renderLicenseDetail(Frame frame, Rect area) {
        Block block = Block.builder()
                .title(" Details ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build();

        int idx = tableState.selected() != null ? tableState.selected() : -1;
        if (idx < 0 || idx >= entries.size()) {
            frame.renderWidget(Paragraph.builder().text("").block(block).build(), area);
            return;
        }

        AuditEntry entry = entries.get(idx);
        List<Span> spans = new ArrayList<>();
        String centralUrl = centralUrl(entry.groupId, entry.artifactId);
        spans.add(Span.raw(entry.gav()).bold().cyan().hyperlink(centralUrl));
        spans.add(Span.raw("  scope: ").fg(Color.DARK_GRAY));
        spans.add(Span.raw(entry.scope));
        spans.add(Span.raw("  \u2197 ").fg(Color.DARK_GRAY));
        spans.add(Span.raw(centralUrl).fg(Color.BLUE).hyperlink(centralUrl));

        List<Span> licSpans = new ArrayList<>();
        if (entry.license != null) {
            licSpans.add(Span.raw("License: ").fg(Color.DARK_GRAY));
            if (entry.licenseUrl != null && !entry.licenseUrl.isEmpty()) {
                licSpans.add(Span.raw(entry.license)
                        .style(getLicenseStyle(entry.license))
                        .hyperlink(entry.licenseUrl));
                licSpans.add(Span.raw("  \u2197 ").fg(Color.DARK_GRAY));
                licSpans.add(Span.raw(entry.licenseUrl).fg(Color.BLUE).hyperlink(entry.licenseUrl));
            } else {
                licSpans.add(Span.raw(entry.license).style(getLicenseStyle(entry.license)));
            }
        } else if (entry.licenseLoaded) {
            licSpans.add(Span.raw("License: ").fg(Color.DARK_GRAY));
            licSpans.add(Span.raw("not specified").fg(Color.DARK_GRAY));
        } else {
            licSpans.add(Span.raw("Loading\u2026").fg(Color.DARK_GRAY));
        }

        List<Line> lines = new ArrayList<>();
        lines.add(Line.from(spans));
        lines.add(Line.from(licSpans));
        Line modulesLine = buildModulesLine(entry);
        if (modulesLine != null) lines.add(modulesLine);

        Paragraph detail = Paragraph.builder()
                .text(dev.tamboui.text.Text.from(lines))
                .block(block)
                .build();
        frame.renderWidget(detail, area);
    }

    private Line buildModulesLine(AuditEntry entry) {
        if (entry.modules.isEmpty()) return null;
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw("Modules: ").fg(Color.DARK_GRAY));
        for (int i = 0; i < entry.modules.size(); i++) {
            if (i > 0) spans.add(Span.raw(", ").fg(Color.DARK_GRAY));
            spans.add(Span.raw(entry.modules.get(i)).fg(Color.MAGENTA));
        }
        return Line.from(spans);
    }

    private Line buildPathLine(AuditEntry entry) {
        if (treeModel == null) return null;
        var node = treeModel.findByGA(entry.ga());
        if (node == null) return null;
        var path = treeModel.pathToRoot(node);
        if (path.size() <= 1) return null; // root or direct dep — no path to show
        List<Span> pathSpans = new ArrayList<>();
        pathSpans.add(Span.raw("Path: ").fg(Color.DARK_GRAY));
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) pathSpans.add(Span.raw(" \u2192 ").fg(Color.DARK_GRAY));
            var n = path.get(i);
            if (i == 0) {
                pathSpans.add(Span.raw(n.artifactId).dim());
            } else if (i == path.size() - 1) {
                pathSpans.add(Span.raw(n.ga()).bold());
            } else if (i == 1) {
                // Direct dependency — highlight
                pathSpans.add(Span.raw(n.ga()).cyan());
            } else {
                pathSpans.add(Span.raw(n.artifactId).dim());
            }
        }
        return Line.from(pathSpans);
    }

    private TableState activeTableState() {
        return switch (view) {
            case VULNERABILITIES -> vulnTableState;
            case BY_LICENSE -> byLicenseTableState;
            default -> tableState;
        };
    }

    private int activeRowCount() {
        return switch (view) {
            case VULNERABILITIES -> vulnRows.size();
            case BY_LICENSE -> byLicenseRows.size();
            default -> entries.size();
        };
    }

    private void renderByLicense(Frame frame, Rect area) {
        if (byLicenseRows.isEmpty()) {
            Block block = Block.builder()
                    .title(" By License ")
                    .borderType(BorderType.ROUNDED)
                    .borderStyle(Style.create().fg(Color.DARK_GRAY))
                    .build();
            String msg = (licensesLoaded >= entries.size())
                    ? "No license data available"
                    : "Loading licenses\u2026 " + licensesLoaded + "/" + entries.size();
            frame.renderWidget(
                    Paragraph.builder().text(msg).block(block).centered().build(), area);
            return;
        }

        // Split into table + separator + detail pane
        var zones = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1), Constraint.length(6))
                .split(area);

        Block block = Block.builder()
                .title(" By License (" + countLicenseGroups() + " licenses) ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build();

        List<Row> rows = new ArrayList<>();
        for (var row : byLicenseRows) {
            if (row.isGroup()) {
                String arrow = row.expanded ? "\u25BE " : "\u25B8 ";
                String label = arrow + row.licenseName + " (" + row.deps.size() + ")";
                rows.add(Row.from(label, "", "", "")
                        .style(getLicenseStyle("(not specified)".equals(row.licenseName) ? null : row.licenseName)
                                .bold()));
            } else {
                rows.add(Row.from("    " + row.entry.ga(), row.entry.version, row.entry.scope, "")
                        .style(Style.create().fg(Color.DARK_GRAY)));
            }
        }

        Row header = Row.from("license / artifact", "version", "scope", "")
                .style(Style.create().bold().yellow());

        Table table = Table.builder()
                .header(header)
                .rows(rows)
                .widths(
                        Constraint.percentage(55), Constraint.percentage(20),
                        Constraint.percentage(15), Constraint.percentage(10))
                .highlightStyle(Style.create().reversed().bold())
                .highlightSymbol("\u25B8 ")
                .block(block)
                .build();

        frame.renderStatefulWidget(table, zones.get(0), byLicenseTableState);

        // -- Detail pane --
        renderByLicenseDetail(frame, zones.get(2));
    }

    private long countLicenseGroups() {
        return byLicenseRows.stream().filter(LicenseRow::isGroup).count();
    }

    private void renderByLicenseDetail(Frame frame, Rect area) {
        Block block = Block.builder()
                .title(" Details ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build();

        int idx = byLicenseTableState.selected() != null ? byLicenseTableState.selected() : -1;
        if (idx < 0 || idx >= byLicenseRows.size()) {
            frame.renderWidget(Paragraph.builder().text("").block(block).build(), area);
            return;
        }

        LicenseRow row = byLicenseRows.get(idx);
        List<Line> lines = new ArrayList<>();

        if (row.isGroup()) {
            List<Span> titleSpans = new ArrayList<>();
            titleSpans.add(Span.raw(row.licenseName).bold().cyan());
            titleSpans.add(Span.raw("  " + row.deps.size() + " dependencies").fg(Color.DARK_GRAY));
            lines.add(Line.from(titleSpans));

            if (row.licenseUrl != null && !row.licenseUrl.isEmpty()) {
                lines.add(Line.from(
                        Span.raw("URL: ").fg(Color.DARK_GRAY),
                        Span.raw(row.licenseUrl).fg(Color.BLUE).hyperlink(row.licenseUrl)));
            }
        } else {
            String centralUrl = centralUrl(row.entry.groupId, row.entry.artifactId);
            lines.add(Line.from(
                    Span.raw(row.entry.gav()).bold().cyan().hyperlink(centralUrl),
                    Span.raw("  scope: ").fg(Color.DARK_GRAY),
                    Span.raw(row.entry.scope),
                    Span.raw("  \u2197 ").fg(Color.DARK_GRAY),
                    Span.raw(centralUrl).fg(Color.BLUE).hyperlink(centralUrl)));
            if (row.entry.license != null) {
                List<Span> licSpans = new ArrayList<>();
                licSpans.add(Span.raw("License: ").fg(Color.DARK_GRAY));
                if (row.entry.licenseUrl != null && !row.entry.licenseUrl.isEmpty()) {
                    licSpans.add(Span.raw(row.entry.license)
                            .style(getLicenseStyle(row.entry.license))
                            .hyperlink(row.entry.licenseUrl));
                    licSpans.add(Span.raw("  \u2197 ").fg(Color.DARK_GRAY));
                    licSpans.add(Span.raw(row.entry.licenseUrl).fg(Color.BLUE).hyperlink(row.entry.licenseUrl));
                } else {
                    licSpans.add(Span.raw(row.entry.license).style(getLicenseStyle(row.entry.license)));
                }
                lines.add(Line.from(licSpans));
            }
            Line modulesLine = buildModulesLine(row.entry);
            if (modulesLine != null) lines.add(modulesLine);
        }

        Paragraph detail = Paragraph.builder()
                .text(dev.tamboui.text.Text.from(lines))
                .block(block)
                .build();
        frame.renderWidget(detail, area);
    }

    private void renderVulnerabilities(Frame frame, Rect area) {
        if (vulnRows.isEmpty()) {
            Block block = Block.builder()
                    .title(" Vulnerabilities ")
                    .borderType(BorderType.ROUNDED)
                    .borderStyle(Style.create().fg(Color.DARK_GRAY))
                    .build();
            String msg = (vulnsLoaded >= entries.size())
                    ? "No known vulnerabilities found \u2713"
                    : "Checking vulnerabilities\u2026 " + vulnsLoaded + "/" + entries.size();
            Paragraph empty =
                    Paragraph.builder().text(msg).block(block).centered().build();
            frame.renderWidget(empty, area);
            return;
        }

        // Split into table + separator + detail pane
        var zones = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1), Constraint.length(8))
                .split(area);

        // -- Vulnerability table --
        Block block = Block.builder()
                .title(" Vulnerabilities (" + vulnRows.size() + ") ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().fg(Color.RED))
                .build();

        List<Row> rows = new ArrayList<>();
        for (var vr : vulnRows) {
            String severity = normalizeSeverity(vr.vuln.severity);
            String summary = vr.vuln.summary.length() > 60 ? vr.vuln.summary.substring(0, 57) + "..." : vr.vuln.summary;
            rows.add(Row.from(vr.entry.ga() + ":" + vr.entry.version, vr.vuln.id, severity, summary)
                    .style(getSeverityStyle(severity)));
        }

        Row header = Row.from("artifact", "CVE/ID", "severity", "summary")
                .style(Style.create().bold().yellow());

        Table table = Table.builder()
                .header(header)
                .rows(rows)
                .widths(
                        Constraint.percentage(30), Constraint.percentage(18),
                        Constraint.percentage(10), Constraint.percentage(42))
                .highlightStyle(Style.create().reversed().bold())
                .highlightSymbol("\u25B8 ")
                .block(block)
                .build();

        frame.renderStatefulWidget(table, zones.get(0), vulnTableState);

        // -- Detail pane --
        renderVulnDetail(frame, zones.get(2));
    }

    private void renderVulnDetail(Frame frame, Rect area) {
        Block block = Block.builder()
                .title(" Details ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build();

        int idx = vulnTableState.selected() != null ? vulnTableState.selected() : -1;
        if (idx < 0 || idx >= vulnRows.size()) {
            frame.renderWidget(Paragraph.builder().text("").block(block).build(), area);
            return;
        }

        VulnRow vr = vulnRows.get(idx);
        var vuln = vr.vuln;
        List<Line> lines = new ArrayList<>();
        String url = vulnUrl(vuln.id);
        String severity = normalizeSeverity(vuln.severity);
        String centralUrl = centralUrl(vr.entry.groupId, vr.entry.artifactId);
        lines.add(Line.from(
                Span.raw(vuln.id).bold().cyan().hyperlink(url),
                Span.raw("  "),
                Span.raw(severity).style(getSeverityStyle(severity).bold()),
                Span.raw("  "),
                Span.raw(vr.entry.ga() + ":" + vr.entry.version)
                        .fg(Color.DARK_GRAY)
                        .hyperlink(centralUrl)));
        lines.add(Line.from(
                Span.raw("\u2197 ").fg(Color.DARK_GRAY),
                Span.raw(url).fg(Color.BLUE).hyperlink(url)));
        if (!vuln.aliases.isEmpty()) {
            List<Span> aliasSpans = new ArrayList<>();
            aliasSpans.add(Span.raw("Aliases: ").fg(Color.DARK_GRAY));
            for (int i = 0; i < vuln.aliases.size(); i++) {
                if (i > 0) aliasSpans.add(Span.raw(", "));
                String alias = vuln.aliases.get(i);
                aliasSpans.add(Span.raw(alias).hyperlink(vulnUrl(alias)));
            }
            lines.add(Line.from(aliasSpans));
        }
        if (vuln.published != null && !vuln.published.isEmpty()) {
            String pub = vuln.published.length() > 10 ? vuln.published.substring(0, 10) : vuln.published;
            lines.add(Line.from(Span.raw("Published: ").fg(Color.DARK_GRAY), Span.raw(pub)));
        }
        lines.add(Line.from(Span.raw(vuln.summary)));
        Line modulesLine = buildModulesLine(vr.entry);
        if (modulesLine != null) lines.add(modulesLine);

        Paragraph detail = Paragraph.builder()
                .text(dev.tamboui.text.Text.from(lines))
                .block(block)
                .build();
        frame.renderWidget(detail, area);
    }

    private static String centralUrl(String groupId, String artifactId) {
        return "https://central.sonatype.com/artifact/" + groupId + "/" + artifactId;
    }

    private static String vulnUrl(String id) {
        if (id.startsWith("CVE-")) {
            return "https://nvd.nist.gov/vuln/detail/" + id;
        }
        if (id.startsWith("GHSA-")) {
            return "https://github.com/advisories/" + id;
        }
        return "https://osv.dev/vulnerability/" + id;
    }

    private static String normalizeSeverity(String severity) {
        if (severity == null) return "UNKNOWN";
        return severity.toUpperCase();
    }

    private static Style getSeverityStyle(String severity) {
        if (severity == null) return Style.create().fg(Color.DARK_GRAY);
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> Style.create().fg(Color.RED).bold();
            case "HIGH" -> Style.create().fg(Color.LIGHT_RED);
            case "MEDIUM" -> Style.create().fg(Color.YELLOW);
            case "LOW" -> Style.create();
            default -> {
                if (severity.toUpperCase().startsWith("CVSS")) {
                    yield Style.create().fg(Color.RED);
                }
                yield Style.create().fg(Color.DARK_GRAY);
            }
        };
    }

    private Style getLicenseStyle(String license) {
        if (license == null) return Style.create().fg(Color.DARK_GRAY);
        String normalized = normalizeLicense(license, null);
        // GPL with classpath exception is weak copyleft
        if (normalized.equals("GPL-2.0-CE")) {
            return Style.create().fg(Color.YELLOW);
        }
        // Strong copyleft
        if (normalized.startsWith("AGPL") || normalized.startsWith("GPL")) {
            return Style.create().fg(Color.RED);
        }
        // Permissive
        if (normalized.startsWith("Apache")
                || normalized.equals("MIT")
                || normalized.startsWith("BSD")
                || normalized.equals("ISC")
                || normalized.equals("Unlicense")
                || normalized.equals("CC0-1.0")
                || normalized.equals("Public Domain")
                || normalized.startsWith("EDL")) {
            return Style.create();
        }
        // Weak copyleft
        if (normalized.startsWith("LGPL")
                || normalized.startsWith("MPL")
                || normalized.startsWith("EPL")
                || normalized.startsWith("CDDL")) {
            return Style.create().fg(Color.YELLOW);
        }
        return Style.create().fg(Color.DARK_GRAY);
    }

    // -- Well-known license URL → SPDX mapping --
    private static final Map<String, String> LICENSE_URL_MAP = Map.ofEntries(
            // Apache
            Map.entry("apache.org/licenses/license-2.0", "Apache-2.0"),
            Map.entry("www.apache.org/licenses/license-2.0", "Apache-2.0"),
            // MIT
            Map.entry("opensource.org/licenses/mit", "MIT"),
            Map.entry("mit-license.org", "MIT"),
            // BSD
            Map.entry("opensource.org/licenses/bsd-2-clause", "BSD-2-Clause"),
            Map.entry("opensource.org/licenses/bsd-3-clause", "BSD-3-Clause"),
            // EPL
            Map.entry("eclipse.org/legal/epl-2.0", "EPL-2.0"),
            Map.entry("eclipse.org/legal/epl-v20", "EPL-2.0"),
            Map.entry("eclipse.org/legal/epl-v10", "EPL-1.0"),
            Map.entry("eclipse.org/org/documents/epl-2.0", "EPL-2.0"),
            // EDL
            Map.entry("eclipse.org/org/documents/edl-v10", "EDL-1.0"),
            Map.entry("eclipse.org/legal/edl-v10", "EDL-1.0"),
            // LGPL
            Map.entry("gnu.org/licenses/lgpl-2.1", "LGPL-2.1"),
            Map.entry("gnu.org/licenses/lgpl-3.0", "LGPL-3.0"),
            // GPL
            Map.entry("gnu.org/licenses/gpl-2.0", "GPL-2.0"),
            Map.entry("gnu.org/licenses/gpl-3.0", "GPL-3.0"),
            // AGPL
            Map.entry("gnu.org/licenses/agpl-3.0", "AGPL-3.0"),
            // MPL
            Map.entry("mozilla.org/mpl/2.0", "MPL-2.0"),
            // CDDL
            Map.entry("opensource.org/licenses/cddl-1.0", "CDDL-1.0"),
            Map.entry("glassfish.dev.java.net/public/cddl", "CDDL-1.0"),
            // CC0
            Map.entry("creativecommons.org/publicdomain/zero/1.0", "CC0-1.0"),
            // Unlicense
            Map.entry("unlicense.org", "Unlicense"),
            // ISC
            Map.entry("opensource.org/licenses/isc", "ISC"),
            // WTFPL
            Map.entry("sam.zoy.org/wtfpl", "WTFPL"));

    /**
     * Normalize a license name (and optional URL) to a canonical SPDX-like identifier
     * for grouping purposes. Uses URL matching first, then name pattern matching.
     */
    static String normalizeLicense(String name, String url) {
        // Try URL match first (most reliable)
        if (url != null) {
            String normalizedUrl = url.toLowerCase()
                    .replaceAll("^https?://", "")
                    .replaceAll("[/.]$", "")
                    .replace(".html", "")
                    .replace(".txt", "")
                    .replace(".php", "");
            for (var entry : LICENSE_URL_MAP.entrySet()) {
                if (normalizedUrl.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }

        if (name == null) return null;
        String lower = name.toLowerCase().trim();

        // Dual-license: pick the most permissive option
        // CDDL + GPL with classpath exception (common in Java EE / Jakarta EE)
        if ((lower.contains("cddl") && lower.contains("gpl"))
                || lower.contains("cddl+gpl")
                || lower.contains("cddl/gpl")) {
            return "CDDL-1.0";
        }
        // Apache OR MIT / BSD — pick Apache
        if ((lower.contains("apache") || lower.contains("asl"))
                && (lower.contains(" or ") || lower.contains("/"))
                && (lower.contains("mit") || lower.contains("bsd"))) {
            return "Apache-2.0";
        }
        // "GPL with classpath exception" alone is weak copyleft (like LGPL)
        if (lower.contains("classpath exception") || lower.contains("class path exception")) {
            return "GPL-2.0-CE";
        }

        // SPDX short identifiers (already normalized)
        if (lower.equals("apache-2.0") || lower.equals("asl 2.0")) return "Apache-2.0";
        if (lower.equals("mit")) return "MIT";
        if (lower.equals("bsd-2-clause")) return "BSD-2-Clause";
        if (lower.equals("bsd-3-clause")) return "BSD-3-Clause";
        if (lower.equals("epl-1.0")) return "EPL-1.0";
        if (lower.equals("epl-2.0")) return "EPL-2.0";
        if (lower.equals("edl 1.0")) return "EDL-1.0";
        if (lower.equals("lgpl-2.1") || lower.equals("lgpl-2.1-only")) return "LGPL-2.1";
        if (lower.equals("lgpl-3.0") || lower.equals("lgpl-3.0-only")) return "LGPL-3.0";
        if (lower.equals("gpl-2.0") || lower.equals("gpl-2.0-only")) return "GPL-2.0";
        if (lower.equals("gpl-3.0") || lower.equals("gpl-3.0-only")) return "GPL-3.0";
        if (lower.equals("agpl-3.0") || lower.equals("agpl-3.0-only")) return "AGPL-3.0";
        if (lower.equals("mpl-2.0")) return "MPL-2.0";
        if (lower.equals("cddl-1.0")) return "CDDL-1.0";
        if (lower.equals("cc0-1.0")) return "CC0-1.0";
        if (lower.equals("isc")) return "ISC";

        // Pattern matching on common name variations
        if (lower.contains("apache") && lower.contains("2")) return "Apache-2.0";
        if (lower.equals("the mit license") || lower.equals("mit license")) return "MIT";
        if (lower.contains("bsd") && lower.contains("2")) return "BSD-2-Clause";
        if (lower.contains("bsd") && lower.contains("3")) return "BSD-3-Clause";
        if (lower.contains("bsd") && !lower.contains("2") && !lower.contains("3")) return "BSD-3-Clause";
        if (lower.contains("eclipse public") && lower.contains("2")) return "EPL-2.0";
        if (lower.contains("eclipse public") && lower.contains("1")) return "EPL-1.0";
        if (lower.contains("eclipse distribution") || lower.equals("edl 1.0")) return "EDL-1.0";
        if (lower.contains("lesser general public") && lower.contains("2")) return "LGPL-2.1";
        if (lower.contains("lesser general public") && lower.contains("3")) return "LGPL-3.0";
        if (lower.contains("lgpl") && lower.contains("2")) return "LGPL-2.1";
        if (lower.contains("lgpl") && lower.contains("3")) return "LGPL-3.0";
        if (lower.contains("agpl") || lower.contains("affero")) return "AGPL-3.0";
        if (lower.contains("general public") && lower.contains("2")) return "GPL-2.0";
        if (lower.contains("general public") && lower.contains("3")) return "GPL-3.0";
        if (lower.contains("gpl") && lower.contains("2")) return "GPL-2.0";
        if (lower.contains("gpl") && lower.contains("3")) return "GPL-3.0";
        if (lower.contains("mozilla") && lower.contains("2")) return "MPL-2.0";
        if (lower.contains("common development") || lower.contains("cddl")) return "CDDL-1.0";
        if (lower.contains("creative commons") && lower.contains("zero")) return "CC0-1.0";
        if (lower.contains("unlicense") || lower.equals("the unlicense")) return "Unlicense";
        if (lower.contains("public domain")) return "Public Domain";

        // No match — return original name
        return name;
    }

    private List<HelpOverlay.Section> buildHelp() {
        return List.of(
                new HelpOverlay.Section(
                        "License & Security Audit",
                        List.of(
                                new HelpOverlay.Entry("", "Audits all resolved dependencies for license"),
                                new HelpOverlay.Entry("", "compliance and known security vulnerabilities."),
                                new HelpOverlay.Entry("", ""),
                                new HelpOverlay.Entry("", "Licenses view: shows each dependency's license"),
                                new HelpOverlay.Entry("", "(from POM metadata). Review for compatibility"),
                                new HelpOverlay.Entry("", "with your project's licensing requirements."),
                                new HelpOverlay.Entry("", ""),
                                new HelpOverlay.Entry("", "Vulnerabilities view: queries for known CVEs"),
                                new HelpOverlay.Entry("", "affecting your dependencies. Shows severity,"),
                                new HelpOverlay.Entry("", "CVE identifier, and affected version range."))),
                new HelpOverlay.Section(
                        "License Colors",
                        List.of(
                                new HelpOverlay.Entry("default", "Permissive license (Apache, MIT, BSD)"),
                                new HelpOverlay.Entry("yellow", "Weak copyleft (LGPL, MPL, EPL, CDDL)"),
                                new HelpOverlay.Entry("red", "Strong copyleft (GPL, AGPL)"),
                                new HelpOverlay.Entry("dim", "Unknown or not yet loaded"))),
                new HelpOverlay.Section(
                        "Vulnerability Colors",
                        List.of(
                                new HelpOverlay.Entry("red bold", "Critical severity"),
                                new HelpOverlay.Entry("red", "High severity"),
                                new HelpOverlay.Entry("yellow", "Medium severity"),
                                new HelpOverlay.Entry("default", "Low severity"),
                                new HelpOverlay.Entry("dim", "Unknown severity"))),
                new HelpOverlay.Section(
                        "Keys",
                        List.of(
                                new HelpOverlay.Entry("\u2191 / \u2193", "Move selection up / down"),
                                new HelpOverlay.Entry("\u2190 / \u2192", "Collapse / expand (By License view)"),
                                new HelpOverlay.Entry("Tab", "Switch between Licenses / By License / Vulns"),
                                new HelpOverlay.Entry("m", "Add selected dep to dependencyManagement"),
                                new HelpOverlay.Entry("d", "Preview POM changes as a unified diff"),
                                new HelpOverlay.Entry("h", "Toggle this help screen"),
                                new HelpOverlay.Entry("q / Esc", "Quit (prompts to save if modified)"))));
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
        if (pendingQuit) {
            spans.add(Span.raw("y").bold());
            spans.add(Span.raw(":Save and quit  "));
            spans.add(Span.raw("n").bold());
            spans.add(Span.raw(":Discard and quit  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Cancel"));
        } else if (diffOverlay.isActive()) {
            spans.add(Span.raw("\u2191\u2193").bold());
            spans.add(Span.raw(":Scroll  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Close  "));
            spans.add(Span.raw("q").bold());
            spans.add(Span.raw(":Quit"));
        } else {
            spans.add(Span.raw("\u2191\u2193").bold());
            spans.add(Span.raw(":Navigate  "));
            spans.add(Span.raw("Tab").bold());
            spans.add(Span.raw(":Licenses/Vulns  "));
            spans.add(Span.raw("m").bold());
            spans.add(Span.raw(":Manage dep  "));
            spans.add(Span.raw("d").bold());
            spans.add(Span.raw(":Diff  "));
            spans.add(Span.raw("h").bold());
            spans.add(Span.raw(":Help  "));
            spans.add(Span.raw("q").bold());
            spans.add(Span.raw(":Quit"));
        }

        frame.renderWidget(Paragraph.from(Line.from(spans)), rows.get(2));
    }
}
