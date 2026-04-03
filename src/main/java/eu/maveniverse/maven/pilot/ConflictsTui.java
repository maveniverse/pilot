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
import dev.tamboui.widgets.table.Cell;
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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Interactive TUI for dependency conflict resolution.
 */
class ConflictsTui {

    static class ConflictEntry {
        final String groupId;
        final String artifactId;
        final String requestedVersion;
        final String resolvedVersion;
        final String path; // dependency path
        final String scope;

        ConflictEntry(
                String groupId,
                String artifactId,
                String requestedVersion,
                String resolvedVersion,
                String path,
                String scope) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.requestedVersion = requestedVersion;
            this.resolvedVersion = resolvedVersion;
            this.path = path;
            this.scope = scope != null ? scope : "compile";
        }

        boolean isConflict() {
            return requestedVersion != null && !requestedVersion.equals(resolvedVersion);
        }
    }

    static class ConflictGroup {
        final String ga;
        final List<ConflictEntry> entries;

        ConflictGroup(String ga, List<ConflictEntry> entries) {
            this.ga = ga;
            this.entries = entries;
        }

        boolean hasConflict() {
            Set<String> versions = entries.stream().map(e -> e.requestedVersion).collect(Collectors.toSet());
            return versions.size() > 1;
        }

        String resolvedVersion() {
            return entries.isEmpty() ? "?" : entries.get(0).resolvedVersion;
        }
    }

    private final List<ConflictGroup> conflicts;
    private final String pomPath;
    private final String projectGav;
    private final TableState tableState = new TableState();
    private boolean showDetails = false;
    private String status;

    private TuiRunner runner;

    ConflictsTui(List<ConflictGroup> conflicts, String pomPath, String projectGav) {
        this.conflicts = conflicts;
        this.pomPath = pomPath;
        this.projectGav = projectGav;
        this.status = conflicts.size() + " dependency group(s) with version variance";
        if (!conflicts.isEmpty()) {
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
            configured.run();
        } finally {
            configured.close();
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
            tableState.selectNext(conflicts.size());
            return true;
        }

        if (key.isKey(KeyCode.ENTER) || key.isCharIgnoreCase(' ')) {
            showDetails = !showDetails;
            return true;
        }

        if (key.isCharIgnoreCase('p')) {
            pinVersion();
            return true;
        }

        return false;
    }

    private void pinVersion() {
        int sel = tableState.selected() != null ? tableState.selected() : -1;
        if (sel < 0 || sel >= conflicts.size()) return;
        var group = conflicts.get(sel);

        try {
            String pomContent = Files.readString(Path.of(pomPath));
            PomEditor editor = new PomEditor(Document.of(pomContent));

            // Pin the resolved version in dependencyManagement
            String resolvedVersion = group.resolvedVersion();
            var coords = Coordinates.of(group.entries.get(0).groupId, group.entries.get(0).artifactId, resolvedVersion);
            editor.dependencies().updateManagedDependency(true, coords);

            Files.writeString(Path.of(pomPath), editor.toXml());
            status = "Pinned " + group.ga + " to " + resolvedVersion + " in dependencyManagement";
        } catch (Exception e) {
            status = "Failed to pin version: " + e.getMessage();
        }
    }

    // -- Rendering --

    void render(Frame frame) {
        var zones = Layout.vertical()
                .constraints(
                        Constraint.length(3),
                        Constraint.fill(),
                        showDetails ? Constraint.percentage(30) : Constraint.length(0),
                        Constraint.length(3))
                .split(frame.area());

        renderHeader(frame, zones.get(0));
        renderConflicts(frame, zones.get(1));

        if (showDetails) {
            renderDetails(frame, zones.get(2));
        }

        renderInfoBar(frame, zones.get(3));
    }

    private void renderHeader(Frame frame, Rect area) {
        Block block = Block.builder()
                .title(" Pilot \u2014 Conflict Resolution ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().cyan())
                .build();

        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" " + projectGav).bold().cyan());

        Paragraph header = Paragraph.builder()
                .text(dev.tamboui.text.Text.from(Line.from(spans)))
                .block(block)
                .build();
        frame.renderWidget(header, area);
    }

    private void renderConflicts(Frame frame, Rect area) {
        long conflictCount =
                conflicts.stream().filter(ConflictGroup::hasConflict).count();
        String title = " Conflicts (" + conflictCount + " actual, " + conflicts.size() + " total) ";

        Block block = Block.builder()
                .title(title)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build();

        if (conflicts.isEmpty()) {
            Paragraph empty = Paragraph.builder()
                    .text("No version conflicts detected \u2713")
                    .block(block)
                    .centered()
                    .build();
            frame.renderWidget(empty, area);
            return;
        }

        Row header = Row.from("status", "groupId:artifactId", "resolved", "versions requested")
                .style(Style.create().bold().yellow());

        List<Row> rows = new ArrayList<>();
        for (var group : conflicts) {
            String icon = group.hasConflict() ? "\u26A0" : "\u2713";
            String versions = group.entries.stream()
                    .map(e -> e.requestedVersion)
                    .distinct()
                    .collect(Collectors.joining(", "));

            Style style = group.hasConflict()
                    ? Style.create().fg(Color.YELLOW)
                    : Style.create().fg(Color.GREEN);

            rows.add(Row.from(icon, group.ga, group.resolvedVersion(), versions).style(style));
        }

        Table table = Table.builder()
                .header(header)
                .rows(rows)
                .widths(
                        Constraint.length(6), Constraint.percentage(40),
                        Constraint.percentage(15), Constraint.fill())
                .highlightStyle(Style.create().reversed().bold())
                .highlightSymbol("\u25B8 ")
                .block(block)
                .build();

        frame.renderStatefulWidget(table, area, tableState);
    }

    private void renderDetails(Frame frame, Rect area) {
        int sel = tableState.selected() != null ? tableState.selected() : -1;
        if (sel < 0 || sel >= conflicts.size()) return;

        var group = conflicts.get(sel);
        Block block = Block.builder()
                .title(" " + group.ga + " \u2014 Dependency Paths ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().yellow())
                .build();

        List<Row> rows = new ArrayList<>();
        for (var entry : group.entries) {
            List<Span> spans = new ArrayList<>();
            spans.add(Span.raw(entry.requestedVersion).bold());
            spans.add(Span.raw(" via ").dim());
            spans.add(Span.raw(entry.path).fg(Color.DARK_GRAY));
            if (!entry.requestedVersion.equals(entry.resolvedVersion)) {
                spans.add(Span.raw(" \u2192 resolved " + entry.resolvedVersion).fg(Color.YELLOW));
            }
            rows.add(Row.from(Cell.from(Line.from(spans))));
        }

        Table table = Table.builder()
                .rows(rows)
                .widths(Constraint.fill())
                .block(block)
                .build();

        TableState detailState = new TableState();
        frame.renderStatefulWidget(table, area, detailState);
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
        spans.add(Span.raw("Enter").bold());
        spans.add(Span.raw(":Details  "));
        spans.add(Span.raw("p").bold());
        spans.add(Span.raw(":Pin version  "));
        spans.add(Span.raw("q").bold());
        spans.add(Span.raw(":Quit"));

        frame.renderWidget(Paragraph.from(Line.from(spans)), rows.get(2));
    }
}
