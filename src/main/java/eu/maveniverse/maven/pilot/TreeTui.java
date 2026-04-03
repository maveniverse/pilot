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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Interactive TUI for browsing the dependency tree.
 */
class TreeTui {

    private enum Mode {
        TREE,
        FILTER,
        REVERSE_PATH
    }

    private final DependencyTreeModel model;
    private final String projectGav;
    private final TableState tableState = new TableState();
    private final ExecutorService httpPool = Executors.newFixedThreadPool(3);
    private final Map<String, SearchTui.PomInfo> pomInfoCache = new HashMap<>();

    private Mode mode = Mode.TREE;
    private List<DependencyTreeModel.TreeNode> displayNodes;
    private int conflictIndex = -1;

    // Filter
    private final StringBuilder filterBuffer = new StringBuilder();
    private List<DependencyTreeModel.TreeNode> filteredNodes;

    // Reverse path
    private List<DependencyTreeModel.TreeNode> reversePath;

    private TuiRunner runner;

    TreeTui(DependencyTreeModel model, String projectGav) {
        this.model = model;
        this.projectGav = projectGav;
        this.displayNodes = model.visibleNodes();
        if (!displayNodes.isEmpty()) {
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
            httpPool.shutdownNow();
        }
    }

    boolean handleEvent(Event event, TuiRunner runner) {
        if (!(event instanceof KeyEvent key)) {
            return true;
        }

        if (key.isCtrlC()) {
            runner.quit();
            return true;
        }

        if (mode == Mode.FILTER) {
            return handleFilterKeys(key);
        }
        if (mode == Mode.REVERSE_PATH) {
            return handleReversePathKeys(key);
        }
        return handleTreeKeys(key);
    }

    private boolean handleTreeKeys(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE) || key.isCharIgnoreCase('q')) {
            runner.quit();
            return true;
        }

        if (key.isUp()) {
            tableState.selectPrevious();
            fetchPomInfoIfNeeded();
            return true;
        }
        if (key.isDown()) {
            tableState.selectNext(displayNodes.size());
            fetchPomInfoIfNeeded();
            return true;
        }

        if (key.isRight()) {
            int sel = selected();
            if (sel >= 0 && sel < displayNodes.size()) {
                var node = displayNodes.get(sel);
                if (node.hasChildren() && !node.expanded) {
                    node.expanded = true;
                    refreshDisplay();
                } else {
                    tableState.selectNext(displayNodes.size());
                    fetchPomInfoIfNeeded();
                }
            }
            return true;
        }
        if (key.isLeft()) {
            int sel = selected();
            if (sel >= 0 && sel < displayNodes.size()) {
                var node = displayNodes.get(sel);
                if (node.expanded && node.hasChildren()) {
                    node.expanded = false;
                    refreshDisplay();
                } else {
                    // Move to parent: find nearest preceding node at depth-1
                    for (int i = sel - 1; i >= 0; i--) {
                        if (displayNodes.get(i).depth < node.depth) {
                            tableState.select(i);
                            fetchPomInfoIfNeeded();
                            break;
                        }
                    }
                }
            }
            return true;
        }

        if (key.isCharIgnoreCase('/')) {
            mode = Mode.FILTER;
            filterBuffer.setLength(0);
            filteredNodes = null;
            return true;
        }

        if (key.isCharIgnoreCase('c')) {
            cycleConflict();
            return true;
        }

        if (key.isCharIgnoreCase('r')) {
            showReversePath();
            return true;
        }

        if (key.isCharIgnoreCase('e')) {
            expandAll();
            return true;
        }

        if (key.isCharIgnoreCase('w')) {
            collapseAll();
            return true;
        }

        return false;
    }

    private boolean handleFilterKeys(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE)) {
            mode = Mode.TREE;
            displayNodes = model.visibleNodes();
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            mode = Mode.TREE;
            if (filteredNodes != null && !filteredNodes.isEmpty()) {
                displayNodes = filteredNodes;
                tableState.select(0);
            }
            return true;
        }
        if (key.code() == KeyCode.CHAR) {
            filterBuffer.append(key.character());
            applyFilter();
            return true;
        }
        if (key.isKey(KeyCode.BACKSPACE) && filterBuffer.length() > 0) {
            filterBuffer.deleteCharAt(filterBuffer.length() - 1);
            applyFilter();
            return true;
        }
        return false;
    }

    private boolean handleReversePathKeys(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE) || key.isKey(KeyCode.ENTER)) {
            mode = Mode.TREE;
            return true;
        }
        return false;
    }

    private void applyFilter() {
        if (filterBuffer.length() == 0) {
            filteredNodes = null;
            displayNodes = model.visibleNodes();
        } else {
            filteredNodes = model.filter(filterBuffer.toString());
            displayNodes = filteredNodes;
        }
        if (!displayNodes.isEmpty()) {
            tableState.select(0);
        }
    }

    private void cycleConflict() {
        if (model.conflicts.isEmpty()) return;
        conflictIndex = (conflictIndex + 1) % model.conflicts.size();
        var conflict = model.conflicts.get(conflictIndex);

        // Expand parents to make conflict visible
        var path = model.pathToRoot(conflict);
        for (var node : path) {
            node.expanded = true;
        }
        refreshDisplay();

        // Select the conflict node
        for (int i = 0; i < displayNodes.size(); i++) {
            if (displayNodes.get(i) == conflict) {
                tableState.select(i);
                break;
            }
        }
    }

    private void showReversePath() {
        int sel = selected();
        if (sel < 0 || sel >= displayNodes.size()) return;
        var node = displayNodes.get(sel);
        reversePath = model.pathToRoot(node);
        mode = Mode.REVERSE_PATH;
    }

    private void expandAll() {
        expandNode(model.root);
        refreshDisplay();
    }

    private void collapseAll() {
        collapseNode(model.root);
        model.root.expanded = true; // keep root expanded
        refreshDisplay();
    }

    private void expandNode(DependencyTreeModel.TreeNode node) {
        node.expanded = true;
        for (var child : node.children) {
            expandNode(child);
        }
    }

    private void collapseNode(DependencyTreeModel.TreeNode node) {
        node.expanded = false;
        for (var child : node.children) {
            collapseNode(child);
        }
    }

    private void refreshDisplay() {
        int selBefore = selected();
        displayNodes = model.visibleNodes();
        if (selBefore >= displayNodes.size()) {
            tableState.select(Math.max(0, displayNodes.size() - 1));
        }
    }

    private int selected() {
        return tableState.selected() != null ? tableState.selected() : 0;
    }

    // -- Rendering --

    void render(Frame frame) {
        var zones = Layout.vertical()
                .constraints(Constraint.length(3), Constraint.fill(), Constraint.length(3))
                .split(frame.area());

        renderHeader(frame, zones.get(0));

        if (mode == Mode.REVERSE_PATH) {
            renderReversePath(frame, zones.get(1));
        } else {
            renderTree(frame, zones.get(1));
        }

        renderInfoBar(frame, zones.get(2));
    }

    private void renderHeader(Frame frame, Rect area) {
        String title = " Pilot \u2014 Dependency Tree ";
        Block block = Block.builder()
                .title(title)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().cyan())
                .build();

        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" " + projectGav).bold().cyan());

        if (mode == Mode.FILTER) {
            spans.add(Span.raw("  Filter: ").fg(Color.YELLOW));
            spans.add(Span.raw(filterBuffer.toString()));
            spans.add(Span.raw("\u2588").fg(Color.YELLOW));
        }

        Paragraph header = Paragraph.builder()
                .text(dev.tamboui.text.Text.from(Line.from(spans)))
                .block(block)
                .build();
        frame.renderWidget(header, area);
    }

    private void renderTree(Frame frame, Rect area) {
        String conflictInfo = model.conflicts.isEmpty() ? "" : ", " + model.conflicts.size() + " conflicts";
        String title = " Tree (" + model.totalNodes + " nodes" + conflictInfo + ") ";

        Block block = Block.builder()
                .title(title)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build();

        if (displayNodes.isEmpty()) {
            Paragraph empty = Paragraph.builder()
                    .text("No dependencies")
                    .block(block)
                    .centered()
                    .build();
            frame.renderWidget(empty, area);
            return;
        }

        List<Row> rows = new ArrayList<>();
        for (var node : displayNodes) {
            rows.add(createTreeRow(node));
        }

        Table table = Table.builder()
                .rows(rows)
                .widths(Constraint.fill())
                .highlightStyle(Style.create().reversed().bold())
                .highlightSymbol("\u25B8 ")
                .block(block)
                .build();

        frame.renderStatefulWidget(table, area, tableState);
    }

    private Row createTreeRow(DependencyTreeModel.TreeNode node) {
        List<Span> spans = new ArrayList<>();

        // Indentation
        String indent = "  ".repeat(node.depth);
        spans.add(Span.raw(indent));

        // Expand/collapse indicator
        if (node.hasChildren()) {
            spans.add(Span.raw(node.expanded ? "\u25BE " : "\u25B8 ").bold());
        } else {
            spans.add(Span.raw("  "));
        }

        // Scope coloring
        Style style = scopeStyle(node.scope);

        // Artifact coordinates
        String coords = node.groupId + ":" + node.artifactId + ":" + node.version;
        spans.add(Span.styled(coords, style));

        // Optional marker
        if (node.optional) {
            spans.add(Span.raw(" (optional)").dim());
        }

        // Conflict marker
        if (node.isConflict()) {
            spans.add(Span.raw(" \u26A0 conflict").fg(Color.YELLOW));
            spans.add(Span.raw(" (wanted " + node.requestedVersion + ")").dim());
        }

        // Scope label (if not compile)
        if (!"compile".equals(node.scope) && !node.scope.isEmpty()) {
            spans.add(Span.raw(" [" + node.scope + "]").dim());
        }

        return Row.from(Cell.from(Line.from(spans)));
    }

    private Style scopeStyle(String scope) {
        return switch (scope) {
            case "compile" -> Style.create();
            case "runtime" -> Style.create().fg(Color.BLUE);
            case "test" -> Style.create().fg(Color.DARK_GRAY);
            case "provided" -> Style.create().fg(Color.MAGENTA);
            case "system" -> Style.create().fg(Color.RED);
            default -> Style.create();
        };
    }

    private void renderReversePath(Frame frame, Rect area) {
        Block block = Block.builder()
                .title(" Reverse Path (why was this pulled in?) ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().yellow())
                .build();

        if (reversePath == null || reversePath.isEmpty()) {
            Paragraph empty = Paragraph.builder()
                    .text("No path found")
                    .block(block)
                    .centered()
                    .build();
            frame.renderWidget(empty, area);
            return;
        }

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < reversePath.size(); i++) {
            var node = reversePath.get(i);
            List<Span> spans = new ArrayList<>();

            if (i > 0) {
                spans.add(Span.raw("  ".repeat(i - 1)));
                spans.add(Span.raw("\u2514\u2500 ").fg(Color.DARK_GRAY));
            }

            spans.add(Span.raw(node.gav()).bold());

            if (i == reversePath.size() - 1) {
                spans.add(Span.raw(" \u25C0 selected").fg(Color.YELLOW));
            }

            rows.add(Row.from(Cell.from(Line.from(spans))));
        }

        Table table = Table.builder()
                .rows(rows)
                .widths(Constraint.fill())
                .block(block)
                .build();

        TableState reverseState = new TableState();
        frame.renderStatefulWidget(table, area, reverseState);
    }

    private void renderInfoBar(Frame frame, Rect area) {
        var rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.length(1))
                .split(area);

        renderArtifactDetails(frame, rows.get(1));
        renderKeyBindings(frame, rows.get(2));
    }

    private void renderArtifactDetails(Frame frame, Rect area) {
        List<Span> spans = new ArrayList<>();
        int sel = selected();
        if (sel >= 0 && sel < displayNodes.size()) {
            var node = displayNodes.get(sel);
            String pomKey = node.gav();
            SearchTui.PomInfo info = pomInfoCache.get(pomKey);

            if (info != null && info.name != null) {
                spans.add(Span.raw(" "));
                spans.add(Span.raw(info.name).bold().cyan());
                if (info.license != null) {
                    spans.add(Span.raw(" \u2502 ").fg(Color.DARK_GRAY));
                    spans.add(Span.raw(info.license).fg(Color.GREEN));
                }
                if (info.organization != null) {
                    spans.add(Span.raw(" \u2502 ").fg(Color.DARK_GRAY));
                    spans.add(Span.raw(info.organization).dim());
                }
                if (info.date != null) {
                    spans.add(Span.raw(" \u2502 ").fg(Color.DARK_GRAY));
                    spans.add(Span.raw(info.date).dim());
                }
            } else {
                spans.add(Span.raw(" " + node.gav()).cyan());
                if (!node.scope.isEmpty() && !"compile".equals(node.scope)) {
                    spans.add(Span.raw(" [" + node.scope + "]").dim());
                }
            }
        }

        Paragraph line = Paragraph.from(Line.from(spans));
        frame.renderWidget(line, area);
    }

    private void renderKeyBindings(Frame frame, Rect area) {
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" "));

        if (mode == Mode.FILTER) {
            spans.add(Span.raw("Type").bold());
            spans.add(Span.raw(":Filter  "));
            spans.add(Span.raw("Enter").bold());
            spans.add(Span.raw(":Apply  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Cancel"));
        } else if (mode == Mode.REVERSE_PATH) {
            spans.add(Span.raw("Esc/Enter").bold());
            spans.add(Span.raw(":Back"));
        } else {
            spans.add(Span.raw("\u2191\u2193").bold());
            spans.add(Span.raw(":Navigate  "));
            spans.add(Span.raw("\u2190\u2192").bold());
            spans.add(Span.raw(":Expand/Collapse  "));
            spans.add(Span.raw("/").bold());
            spans.add(Span.raw(":Filter  "));
            spans.add(Span.raw("c").bold());
            spans.add(Span.raw(":Conflicts  "));
            spans.add(Span.raw("r").bold());
            spans.add(Span.raw(":Reverse  "));
            spans.add(Span.raw("e/w").bold());
            spans.add(Span.raw(":Expand/Collapse all  "));
            spans.add(Span.raw("q").bold());
            spans.add(Span.raw(":Quit"));
        }

        Paragraph line = Paragraph.from(Line.from(spans));
        frame.renderWidget(line, area);
    }

    private void fetchPomInfoIfNeeded() {
        int sel = selected();
        if (sel < 0 || sel >= displayNodes.size()) return;
        var node = displayNodes.get(sel);
        String pomKey = node.gav();
        if (pomInfoCache.containsKey(pomKey)) return;
        pomInfoCache.put(pomKey, new SearchTui.PomInfo(null, null, null, null, null, null)); // placeholder

        CompletableFuture.supplyAsync(
                        () -> SearchTui.fetchPomFromCentral(node.groupId, node.artifactId, node.version), httpPool)
                .thenAccept(info -> runner.runOnRenderThread(() -> pomInfoCache.put(pomKey, info)));
    }
}
