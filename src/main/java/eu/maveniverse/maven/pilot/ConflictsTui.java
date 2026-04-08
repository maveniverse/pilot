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

    private final String originalPomContent;
    private final PomEditor editor;
    private boolean dirty;
    private boolean pendingQuit;
    private final DiffOverlay diffOverlay = new DiffOverlay();
    private int lastContentHeight;

    private TuiRunner runner;

    /**
     * Get the currently selected table-row index, or -1 when no selection exists.
     *
     * @return the selected index, or -1 if no row is selected
     */
    private int selectedIndex() {
        Integer sel = tableState.selected();
        return sel != null ? sel : -1;
    }

    /**
     * Creates a ConflictsTui configured with the provided conflict groups, POM path, and project GAV.
     *
     * Initializes the status message to reflect the number of provided conflict groups and selects
     * the first row when the list of conflicts is not empty.
     *
     * @param conflicts list of conflict groups to display
     * @param pomPath path to the project's POM file used for pinning resolved versions
     * @param projectGav project coordinates shown in the header
     */
    ConflictsTui(List<ConflictGroup> conflicts, String pomPath, String projectGav) {
        this.conflicts = conflicts;
        this.pomPath = pomPath;
        this.projectGav = projectGav;
        this.status = conflicts.size() + " dependency group(s) with version variance";
        String pom;
        try {
            pom = Files.readString(Path.of(pomPath));
        } catch (java.nio.file.NoSuchFileException e) {
            pom = "<project/>";
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read POM: " + pomPath, e);
        }
        this.originalPomContent = pom;
        this.editor = new PomEditor(Document.of(pom));
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

    /**
     * Handle keyboard events and execute corresponding TUI actions such as navigation, toggling details,
     * pinning a group's resolved version, or quitting the runner.
     *
     * @param event  the input event to handle
     * @param runner the TUI runner used to control application lifecycle (e.g., to quit)
     * @return `true` if the event was handled and should not be processed further, `false` otherwise
     */
    boolean handleEvent(Event event, TuiRunner runner) {
        if (!(event instanceof KeyEvent key)) {
            return true;
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
                status = "Quit cancelled";
                return true;
            }
            return false;
        }

        // Diff overlay mode — Esc closes overlay first
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

        if (key.isCtrlC() || key.isCharIgnoreCase('q') || key.isKey(KeyCode.ESCAPE)) {
            requestQuit();
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

        if (key.isCharIgnoreCase('d')) {
            toggleDiffView();
            return true;
        }

        return false;
    }

    /**
     * Pins the currently selected conflict group's resolved version into the project's POM
     * dependencyManagement section and updates the TUI status message.
     *
     * If no row is selected or the selection is out of range, the method returns without action.
     * On success the status is set to indicate the GA and pinned version; on failure the status
     * contains the error message.
     */
    private void pinVersion() {
        int sel = selectedIndex();
        if (sel < 0 || sel >= conflicts.size()) return;
        var group = conflicts.get(sel);

        try {
            String resolvedVersion = group.resolvedVersion();
            var coords = Coordinates.of(group.entries.get(0).groupId, group.entries.get(0).artifactId, resolvedVersion);
            editor.dependencies().updateManagedDependency(true, coords);

            dirty = true;
            status = "Pinned " + group.ga + " to " + resolvedVersion + " \u2014 save on exit";
        } catch (Exception e) {
            status = "Failed to pin version: " + e.getMessage();
        }
    }

    private void requestQuit() {
        if (dirty) {
            pendingQuit = true;
            status = "Save changes to POM?";
        } else {
            runner.quit();
        }
    }

    private void saveAndQuit() {
        try {
            Files.writeString(Path.of(pomPath), editor.toXml());
            runner.quit();
        } catch (Exception e) {
            pendingQuit = false;
            status = "Failed to save: " + e.getMessage();
        }
    }

    private void toggleDiffView() {
        long changes = diffOverlay.open(originalPomContent, editor.toXml());
        status = changes == 0 ? "No changes to show" : changes + " line(s) changed";
    }

    // -- Rendering --

    void render(Frame frame) {
        var zones = Layout.vertical()
                .constraints(
                        Constraint.length(3),
                        Constraint.fill(),
                        showDetails && !diffOverlay.isActive() ? Constraint.percentage(30) : Constraint.length(0),
                        Constraint.length(3))
                .split(frame.area());

        renderHeader(frame, zones.get(0));
        lastContentHeight = zones.get(1).height();

        if (diffOverlay.isActive()) {
            diffOverlay.render(frame, zones.get(1), " POM Changes ");
        } else {
            renderConflicts(frame, zones.get(1));
            if (showDetails) {
                renderDetails(frame, zones.get(2));
            }
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
        if (dirty) {
            spans.add(Span.raw("  [modified]").fg(Color.YELLOW));
        }

        Paragraph header = Paragraph.builder()
                .text(dev.tamboui.text.Text.from(Line.from(spans)))
                .block(block)
                .build();
        frame.renderWidget(header, area);
    }

    /**
     * Renders the conflicts section: header with counts and a table listing each dependency group,
     * an icon for conflict status, resolved version, and requested versions; when no conflicts are
     * present renders a centered success message.
     *
     * @param frame the terminal frame to draw into
     * @param area the rectangular region to render the conflicts widget
     */
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

    /**
     * Render the details pane for the currently selected conflict group.
     *
     * <p>If no valid row is selected, the method returns without rendering.
     * When a group is selected, a rounded, yellow-tinted block titled
     * "<ga> — Dependency Paths" is rendered containing a table with one row
     * per entry. Each row presents the requested version in bold, the text
     * " via " dimmed, the dependency path in dark gray, and—when the
     * requested version differs from the resolved version—an appended
     * "→ resolved <resolvedVersion>" fragment in yellow.
     */
    private void renderDetails(Frame frame, Rect area) {
        int sel = selectedIndex();
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
        statusSpans.add(Span.raw(" " + status).fg(pendingQuit ? Color.YELLOW : Color.GREEN));
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
            spans.add(Span.raw("Enter").bold());
            spans.add(Span.raw(":Details  "));
            spans.add(Span.raw("p").bold());
            spans.add(Span.raw(":Pin version  "));
            spans.add(Span.raw("d").bold());
            spans.add(Span.raw(":Diff  "));
            spans.add(Span.raw("q").bold());
            spans.add(Span.raw(":Quit"));
        }

        frame.renderWidget(Paragraph.from(Line.from(spans)), rows.get(2));
    }
}
