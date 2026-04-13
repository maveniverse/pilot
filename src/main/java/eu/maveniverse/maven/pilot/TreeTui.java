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
import org.eclipse.aether.graph.DependencyNode;

/**
 * Interactive TUI for browsing the dependency tree.
 */
class TreeTui {

    private enum Mode {
        TREE,
        FILTER,
        REVERSE_PATH
    }

    private static final List<String> SCOPES = List.of("compile", "runtime", "test");

    private final DependencyNode rootNode;
    private final String projectGav;
    private final TableState tableState = new TableState();
    private final ExecutorService httpPool = MojoHelper.newHttpPool();
    private final Map<String, SearchTui.PomInfo> pomInfoCache = new HashMap<>();
    private final HelpOverlay helpOverlay = new HelpOverlay();

    private DependencyTreeModel model;
    private String scope;
    private Mode mode = Mode.TREE;
    private List<DependencyTreeModel.TreeNode> displayNodes;
    private int conflictIndex = -1;

    // Filter
    private final StringBuilder filterBuffer = new StringBuilder();
    private List<DependencyTreeModel.TreeNode> filteredNodes;

    // Reverse path
    private List<DependencyTreeModel.TreeNode> reversePath;

    private TuiRunner runner;
    private int lastContentHeight;

    TreeTui(DependencyNode rootNode, String scope, String projectGav) {
        this.rootNode = rootNode;
        this.scope = scope;
        this.projectGav = projectGav;
        this.model = DependencyTreeModel.fromDependencyNode(rootNode, scope);
        this.displayNodes = model.visibleNodes();
        if (!displayNodes.isEmpty()) {
            tableState.select(0);
        }
    }

    TreeTui(DependencyTreeModel model, String projectGav) {
        this.rootNode = null;
        this.scope = "compile";
        this.projectGav = projectGav;
        this.model = model;
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

        if (helpOverlay.isActive()) {
            if (helpOverlay.handleKey(key)) return true;
            if (key.isCharIgnoreCase('q') || key.isCtrlC()) {
                runner.quit();
                return true;
            }
            return false;
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

    /**
     * Handle keyboard input while in the tree view (navigation, expansion, and mode actions).
     *
     * Processes movement (up/down/left/right), expand/collapse, switching to filter or reverse-path modes,
     * cycling conflicts, expanding/collapsing all nodes, and quit. Updates selection, node expansion state,
     * and may trigger background metadata fetches or UI refreshes.
     *
     * @param key the key event to handle
     * @return `true` if the key was handled by the tree view, `false` otherwise
     */
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
        if (TableNavigation.handlePageKeys(key, tableState, displayNodes.size(), lastContentHeight)) {
            fetchPomInfoIfNeeded();
            return true;
        }

        if (key.isRight()) {
            int sel = selectedIndex();
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
            int sel = selectedIndex();
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

        if (key.isCharIgnoreCase('s')) {
            cycleScope();
            return true;
        }

        if (key.isCharIgnoreCase('h')) {
            helpOverlay.open(buildHelp());
            return true;
        }

        return false;
    }

    private void cycleScope() {
        if (rootNode == null) return;
        int idx = SCOPES.indexOf(scope);
        scope = SCOPES.get((idx + 1) % SCOPES.size());
        model = DependencyTreeModel.fromDependencyNode(rootNode, scope);
        conflictIndex = -1;
        displayNodes = model.visibleNodes();
        if (!displayNodes.isEmpty()) {
            tableState.select(0);
        } else {
            tableState.clearSelection();
        }
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

    /**
     * Advances the internal conflict pointer to the next conflicting node, makes that node visible, and selects it in the table.
     *
     * <p>If there are no conflicts, this method has no effect.</p>
     */
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

    /**
     * Switches the view to the reverse-path mode for the currently selected node.
     *
     * If a valid node is selected, computes the path from that node to the tree root,
     * stores it in {@code reversePath}, and sets the UI mode to {@code REVERSE_PATH}.
     * If no valid selection exists, no state is changed.
     */
    private void showReversePath() {
        int sel = selectedIndex();
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
        // Remember the selected node before collapsing
        int sel = selectedIndex();
        DependencyTreeModel.TreeNode selectedNode =
                (sel >= 0 && sel < displayNodes.size()) ? displayNodes.get(sel) : null;

        collapseNode(model.root);
        model.root.expanded = true; // keep root expanded
        displayNodes = model.visibleNodes();

        // Find the selected node or its nearest visible ancestor
        if (selectedNode != null) {
            int newIndex = displayNodes.indexOf(selectedNode);
            if (newIndex < 0) {
                // Node is hidden — find nearest visible ancestor via path from root
                List<DependencyTreeModel.TreeNode> path = model.pathToRoot(selectedNode);
                for (int i = path.size() - 2; i >= 0; i--) {
                    newIndex = displayNodes.indexOf(path.get(i));
                    if (newIndex >= 0) break;
                }
                if (newIndex < 0) newIndex = 0;
            }
            tableState.select(newIndex);
        }
    }

    private void expandNode(DependencyTreeModel.TreeNode node) {
        node.expanded = true;
        for (var child : node.children) {
            expandNode(child);
        }
    }

    /**
     * Collapse the given tree node and all of its descendants.
     *
     * @param node the tree node to collapse; its {@code expanded} flag (and that of every descendant) will be set to {@code false}
     */
    private void collapseNode(DependencyTreeModel.TreeNode node) {
        node.expanded = false;
        for (var child : node.children) {
            collapseNode(child);
        }
    }

    private List<HelpOverlay.Section> buildHelp() {
        return List.of(
                new HelpOverlay.Section(
                        "Dependency Tree",
                        List.of(
                                new HelpOverlay.Entry("", "Shows the fully resolved dependency tree of the project."),
                                new HelpOverlay.Entry("", "Each row is a dependency (groupId:artifactId:version)."),
                                new HelpOverlay.Entry("", "Indentation shows the transitive dependency chain:"),
                                new HelpOverlay.Entry("", "a child was pulled in by its parent in the tree."),
                                new HelpOverlay.Entry("", ""),
                                new HelpOverlay.Entry("", "Scope (compile, test, runtime, provided) is shown"),
                                new HelpOverlay.Entry("", "in brackets when not 'compile'. Each scope has a"),
                                new HelpOverlay.Entry("", "distinct color. The status bar shows artifact"),
                                new HelpOverlay.Entry("", "metadata (name, license) fetched from Central."))),
                new HelpOverlay.Section(
                        "Colors (by scope)",
                        List.of(
                                new HelpOverlay.Entry("default", "compile scope"),
                                new HelpOverlay.Entry("blue", "runtime scope"),
                                new HelpOverlay.Entry("magenta", "provided scope"),
                                new HelpOverlay.Entry("dark gray", "test scope"),
                                new HelpOverlay.Entry("red", "system scope"),
                                new HelpOverlay.Entry("yellow", "Version conflict \u2014 multiple versions requested"),
                                new HelpOverlay.Entry("dim", "Scope label, optional marker"))),
                new HelpOverlay.Section(
                        "Navigation",
                        List.of(
                                new HelpOverlay.Entry("\u2191 / \u2193", "Move selection up / down"),
                                new HelpOverlay.Entry("PgUp / PgDn", "Move selection up / down by one page"),
                                new HelpOverlay.Entry("Home / End", "Jump to first / last row"),
                                new HelpOverlay.Entry("\u2190 / \u2192", "Collapse / expand tree node"),
                                new HelpOverlay.Entry("e", "Expand all nodes"),
                                new HelpOverlay.Entry("w", "Collapse all (keeps root expanded)"))),
                new HelpOverlay.Section(
                        "Actions",
                        List.of(
                                new HelpOverlay.Entry("/", "Filter \u2014 type to search by groupId or artifactId"),
                                new HelpOverlay.Entry("c", "Jump to next conflict (only when conflicts exist)"),
                                new HelpOverlay.Entry("r", "Reverse path \u2014 show how root depends on selection"),
                                new HelpOverlay.Entry("s", "Cycle scope filter: compile \u2192 runtime \u2192 test"))),
                new HelpOverlay.Section(
                        "General",
                        List.of(
                                new HelpOverlay.Entry("h", "Toggle this help screen"),
                                new HelpOverlay.Entry("q / Esc", "Quit"))));
    }

    /**
     * Refreshes the list of nodes shown in the UI from the model and preserves a valid selection.
     *
     * Saves the current selected index, replaces {@code displayNodes} with {@code model.visibleNodes()},
     * and if the previous selection is now out of range selects the last available row (or 0 if the list is empty).
     */
    private void refreshDisplay() {
        int selBefore = selectedIndex();
        displayNodes = model.visibleNodes();
        if (selBefore >= displayNodes.size()) {
            tableState.select(Math.max(0, displayNodes.size() - 1));
        }
    }

    /**
     * Get the currently selected row index from the table state, defaulting to -1 when no selection is set.
     *
     * @return `-1` if no selection is set, otherwise the selected row index.
     */
    private int selectedIndex() {
        Integer sel = tableState.selected();
        return sel != null ? sel : -1;
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
        } else if (mode == Mode.REVERSE_PATH) {
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
        String title = " Tree [" + scope + "] (" + model.totalNodes + " nodes" + conflictInfo + ") ";

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

    /**
     * Renders the selected artifact's details into the given area of the frame.
     *
     * If cached POM metadata for the selected node exists and contains a name, renders the artifact
     * name and, when available, its license, organization, and date. Otherwise renders the node's
     * GAV and, if applicable, its scope.
     *
     * @param frame the frame to render into
     * @param area the rectangle area within the frame where details are drawn
     */
    private void renderArtifactDetails(Frame frame, Rect area) {
        List<Span> spans = new ArrayList<>();
        int sel = selectedIndex();
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

    /**
     * Renders the single-line key binding hint bar for the current UI mode.
     *
     * Displays mode-specific key labels and short action hints (Filter, Navigate,
     * Expand/Collapse, Conflicts, Reverse, Quit) and paints them into the given
     * area of the frame.
     *
     * @param frame the frame to render widgets into
     * @param area the rectangular area within the frame where the key hints are drawn
     */
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
            if (!model.conflicts.isEmpty()) {
                spans.add(Span.raw("c").bold());
                spans.add(Span.raw(":Conflicts  "));
            }
            spans.add(Span.raw("r").bold());
            spans.add(Span.raw(":Reverse  "));
            spans.add(Span.raw("s").bold());
            spans.add(Span.raw(":Scope(" + scope + ")  "));
            spans.add(Span.raw("e/w").bold());
            spans.add(Span.raw(":Expand/Collapse all  "));
            spans.add(Span.raw("h").bold());
            spans.add(Span.raw(":Help  "));
            spans.add(Span.raw("q").bold());
            spans.add(Span.raw(":Quit"));
        }

        Paragraph line = Paragraph.from(Line.from(spans));
        frame.renderWidget(line, area);
    }

    /**
     * Ensures POM metadata for the currently selected dependency is being fetched and cached.
     *
     * If the selected row corresponds to a node whose GAV is not already cached, inserts a
     * placeholder entry and starts an asynchronous fetch from Maven Central. When the fetch
     * completes the retrieved `PomInfo` is stored in the cache on the render thread.
     */
    private void fetchPomInfoIfNeeded() {
        int sel = selectedIndex();
        if (sel < 0 || sel >= displayNodes.size()) return;
        var node = displayNodes.get(sel);
        String pomKey = node.gav();
        if (pomInfoCache.containsKey(pomKey)) return;
        pomInfoCache.put(pomKey, new SearchTui.PomInfo(null, null, null, null, null, null, null)); // placeholder

        CompletableFuture.supplyAsync(
                        () -> SearchTui.fetchPomFromCentral(node.groupId, node.artifactId, node.version), httpPool)
                .thenAccept(info -> runner.runOnRenderThread(() -> pomInfoCache.put(pomKey, info)));
    }
}
