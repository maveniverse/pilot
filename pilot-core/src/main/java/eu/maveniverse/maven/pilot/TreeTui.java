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
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Interactive TUI for browsing the dependency tree.
 *
 * <p>Implements {@link ToolPanel} for embedding in the unified shell.</p>
 */
public class TreeTui extends ToolPanel {

    private static final List<String> SCOPES = List.of("compile", "runtime", "test");

    private final DependencyTreeModel fullModel;
    private final String projectGav;
    private final TableState tableState = new TableState();
    private final ExecutorService httpPool = PilotUtil.newHttpPool();
    private final Map<String, SearchTui.PomInfo> pomInfoCache = new HashMap<>();

    private DependencyTreeModel model;
    private String scope;
    private List<DependencyTreeModel.TreeNode> displayNodes;
    private int conflictIndex = -1;

    // Reverse path
    private List<DependencyTreeModel.TreeNode> reversePath;
    private boolean showReversePath;

    private int lastContentHeight;

    private static final int COL_GA = 0;
    private static final int COL_VERSION = 1;
    private static final int COL_SCOPE = 2;

    public TreeTui(DependencyTreeModel fullModel, String scope, String projectGav) {
        this.fullModel = fullModel;
        this.scope = scope;
        this.projectGav = projectGav;
        this.model = fullModel.filterByScope(scope);
        this.displayNodes = model.visibleNodes();
        this.sortState = new SortState(3);
        if (!displayNodes.isEmpty()) {
            tableState.select(0);
            fetchPomInfoIfNeeded();
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

        // Try panel-level handling first
        if (handleKeyEvent(key)) return true;

        // Standalone-only keys
        if (key.isKey(KeyCode.ESCAPE) || key.isCharIgnoreCase('q')) {
            runner.quit();
            return true;
        }
        if (key.isCharIgnoreCase('h')) {
            helpOverlay.open(buildHelpStandalone());
            return true;
        }

        return false;
    }

    void renderStandalone(Frame frame) {
        var zones = Layout.vertical()
                .constraints(Constraint.length(3), Constraint.fill(), Constraint.length(3))
                .split(frame.area());

        renderHeader(frame, zones.get(0));

        Rect contentArea = renderStandaloneHelp(frame, zones.get(1));
        if (contentArea != null) {
            render(frame, contentArea);
        }

        renderInfoBar(frame, zones.get(2));
    }

    // ── ToolPanel interface ─────────────────────────────────────────────────

    @Override
    public String toolName() {
        return "Tree";
    }

    int nodeCount() {
        return model.totalNodes;
    }

    @Override
    public void render(Frame frame, Rect area) {
        if (showReversePath) {
            renderReversePath(frame, area);
        } else {
            renderTree(frame, area);
        }
    }

    @Override
    public boolean handleKeyEvent(KeyEvent key) {
        if (showReversePath) {
            return handleReversePathKeys(key);
        }
        if (handleSearchInput(key)) return true;
        if (handleSortInput(key)) return true;
        return handleTreeKeys(key);
    }

    @Override
    public boolean handleMouseEvent(MouseEvent mouse, Rect area) {
        if (handleMouseSortHeader(mouse, List.of(TREE_WIDTHS))) return true;
        if (mouse.isClick()) {
            int row = mouse.y() - area.y() - 1 + tableState.offset(); // header + scroll
            if (row >= 0 && row < displayNodes.size()) {
                tableState.select(row);
                var node = displayNodes.get(row);
                if (node.hasChildren()) {
                    int arrowX = area.x() + 2 + node.depth * 2; // highlight(2) + indent
                    if (mouse.x() >= arrowX && mouse.x() < arrowX + 2) {
                        node.expanded = !node.expanded;
                        refreshDisplay();
                    }
                }
                fetchPomInfoIfNeeded();
                return true;
            }
        }
        if (mouse.isScroll()) {
            if (displayNodes.isEmpty()) return false;
            int sel = tableState.selected();
            if (mouse.kind() == MouseEventKind.SCROLL_UP) {
                tableState.select(Math.max(0, sel - 1));
            } else {
                tableState.select(Math.min(displayNodes.size() - 1, sel + 1));
            }
            fetchPomInfoIfNeeded();
            return true;
        }
        return false;
    }

    @Override
    public String status() {
        int sel = selectedIndex();
        if (sel < 0 || sel >= displayNodes.size()) return "";
        var node = displayNodes.get(sel);
        String pomKey = node.gav();
        SearchTui.PomInfo info = pomInfoCache.get(pomKey);
        if (info != null && info.name != null) {
            StringBuilder sb = new StringBuilder(info.name);
            if (info.license != null) sb.append(" │ ").append(info.license);
            if (info.organization != null) sb.append(" │ ").append(info.organization);
            if (info.date != null) sb.append(" │ ").append(info.date);
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder(node.gav());
        if (!node.scope.isEmpty() && !"compile".equals(node.scope)) {
            sb.append(" [").append(node.scope).append("]");
        }
        return sb.toString();
    }

    @Override
    public List<Span> keyHints() {
        List<Span> searchHints = searchKeyHints();
        if (!searchHints.isEmpty()) {
            return searchHints;
        }
        List<Span> spans = new ArrayList<>();
        if (showReversePath) {
            spans.add(Span.raw("Esc/Enter").bold());
            spans.add(Span.raw(":Back  "));
        } else {
            spans.add(Span.raw("↑↓").bold());
            spans.add(Span.raw(":Nav  "));
            spans.add(Span.raw("←→").bold());
            spans.add(Span.raw(":Expand  "));
            spans.add(Span.raw("/").bold());
            spans.add(Span.raw(":Search  "));
            if (!model.conflicts.isEmpty()) {
                spans.add(Span.raw("c").bold());
                spans.add(Span.raw(":Conflicts  "));
            }
            spans.add(Span.raw("r").bold());
            spans.add(Span.raw(":Reverse  "));
            spans.addAll(sortKeyHints());
            spans.add(Span.raw("f").bold());
            spans.add(Span.raw(":Scope(" + scope + ")  "));
            spans.add(Span.raw("E/W").bold());
            spans.add(Span.raw(":All  "));
        }
        return spans;
    }

    @Override
    public List<HelpOverlay.Section> helpSections() {
        return HelpOverlay.parse("""
                ## Dependency Tree
                Shows the resolved dependency tree.
                ↑ / ↓           Move selection up / down
                ← / →           Collapse / expand tree node
                E / W           Expand all / collapse all
                /               Search by groupId or artifactId
                c               Jump to next conflict
                r               Reverse path (why was this pulled in?)
                f               Cycle scope: compile → runtime → test
                """);
    }

    @Override
    void close() {
        httpPool.shutdownNow();
    }

    // ── Key handling ────────────────────────────────────────────────────────

    private boolean handleTreeKeys(KeyEvent key) {
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
        if (TableNavigation.handlePageKeys(
                key, tableState, displayNodes.size(), lastContentHeight, TableNavigation.BORDERED_NO_HEADER)) {
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

        if (key.isCharIgnoreCase('c')) {
            cycleConflict();
            return true;
        }

        if (key.isCharIgnoreCase('r')) {
            enterReversePath();
            return true;
        }

        if (key.isChar('E')) {
            expandAll();
            return true;
        }

        if (key.isChar('W')) {
            collapseAll();
            return true;
        }

        if (key.isCharIgnoreCase('f')) {
            cycleScope();
            return true;
        }

        return false;
    }

    private boolean handleReversePathKeys(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE) || key.isKey(KeyCode.ENTER)) {
            showReversePath = false;
            return true;
        }
        return false;
    }

    // ── Tree logic ──────────────────────────────────────────────────────────

    private void cycleScope() {
        int idx = SCOPES.indexOf(scope);
        scope = SCOPES.get((idx + 1) % SCOPES.size());
        model = fullModel.filterByScope(scope);
        conflictIndex = -1;
        displayNodes = model.visibleNodes();
        if (!displayNodes.isEmpty()) {
            tableState.select(0);
        } else {
            tableState.clearSelection();
        }
    }

    private void cycleConflict() {
        if (model.conflicts.isEmpty()) return;
        conflictIndex = (conflictIndex + 1) % model.conflicts.size();
        var conflict = model.conflicts.get(conflictIndex);

        var path = model.pathToRoot(conflict);
        for (var node : path) {
            node.expanded = true;
        }
        refreshDisplay();

        for (int i = 0; i < displayNodes.size(); i++) {
            if (displayNodes.get(i) == conflict) {
                tableState.select(i);
                break;
            }
        }
    }

    private void enterReversePath() {
        int sel = selectedIndex();
        if (sel < 0 || sel >= displayNodes.size()) return;
        var node = displayNodes.get(sel);
        reversePath = model.pathToRoot(node);
        showReversePath = true;
    }

    private void expandAll() {
        expandNode(model.root);
        refreshDisplay();
    }

    private void collapseAll() {
        int sel = selectedIndex();
        DependencyTreeModel.TreeNode selectedNode =
                (sel >= 0 && sel < displayNodes.size()) ? displayNodes.get(sel) : null;

        collapseNode(model.root);
        model.root.expanded = true;
        displayNodes = model.visibleNodes();

        if (selectedNode != null) {
            int newIndex = displayNodes.indexOf(selectedNode);
            if (newIndex < 0) {
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

    private void collapseNode(DependencyTreeModel.TreeNode node) {
        node.expanded = false;
        for (var child : node.children) {
            collapseNode(child);
        }
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

    @Override
    protected void updateSearchMatches() {
        String query = searchBuffer.toString().toLowerCase();
        if (query.isEmpty()) {
            searchMatches = List.of();
            searchMatchIndex = -1;
            return;
        }
        searchMatches = new ArrayList<>();
        for (int i = 0; i < displayNodes.size(); i++) {
            var node = displayNodes.get(i);
            String searchable = node.groupId + ":" + node.artifactId + ":" + node.version;
            if (searchable.toLowerCase().contains(query)) {
                searchMatches.add(i);
            }
        }
        if (!searchMatches.isEmpty()) {
            searchMatchIndex = 0;
            selectSearchMatch(0);
        } else {
            searchMatchIndex = -1;
        }
    }

    @Override
    protected void selectSearchMatch(int matchIndex) {
        tableState.select(searchMatches.get(matchIndex));
        fetchPomInfoIfNeeded();
    }

    private int selectedIndex() {
        Integer sel = tableState.selected();
        return sel != null ? sel : -1;
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private static final Constraint[] TREE_WIDTHS = {
        Constraint.percentage(55), Constraint.percentage(25), Constraint.percentage(20)
    };

    private void renderTree(Frame frame, Rect area) {
        if (displayNodes.isEmpty()) {
            Paragraph empty =
                    Paragraph.builder().text("No dependencies").centered().build();
            frame.renderWidget(empty, area);
            return;
        }

        var zones = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1), Constraint.percentage(25))
                .split(area);

        Row header = sortState.decorateHeader(List.of("groupId:artifactId", "version", "scope"), theme.tableHeader());

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < displayNodes.size(); i++) {
            Row row = createTreeRow(displayNodes.get(i));
            if (isSearchMatch(i)) {
                row = row.style(row.style().bg(theme.searchHighlightBg()));
            }
            rows.add(row);
        }

        Table table = Table.builder()
                .header(header)
                .rows(rows)
                .widths(TREE_WIDTHS)
                .highlightStyle(theme.highlightStyle())
                .highlightSymbol(theme.highlightSymbol())
                .build();

        lastContentHeight = zones.get(0).height();
        setTableArea(zones.get(0), null);
        frame.renderStatefulWidget(table, zones.get(0), tableState);
        renderDivider(frame, zones.get(1));
        renderDetails(frame, zones.get(2));
    }

    private Row createTreeRow(DependencyTreeModel.TreeNode node) {
        // Column 1: indented groupId:artifactId with expand/collapse arrow
        List<Span> gaSpans = new ArrayList<>();
        String indent = "  ".repeat(node.depth);
        gaSpans.add(Span.raw(indent));
        if (node.hasChildren()) {
            gaSpans.add(Span.raw(node.expanded ? "▾ " : "▸ ").bold());
        } else {
            gaSpans.add(Span.raw("  "));
        }
        String ga = node.groupId + ":" + node.artifactId;
        gaSpans.add(Span.styled(ga, scopeStyle(node.scope)));
        if (node.optional) {
            gaSpans.add(Span.raw(" (opt)").dim());
        }

        // Column 2: version with conflict info
        List<Span> versionSpans = new ArrayList<>();
        versionSpans.add(Span.raw(node.version));
        if (node.isConflict()) {
            versionSpans.add(Span.raw(" ⚠ " + node.requestedVersion).fg(theme.statusWarningColor()));
        }

        // Column 3: scope
        String scopeLabel = node.scope;

        return Row.from(Cell.from(Line.from(gaSpans)), Cell.from(Line.from(versionSpans)), Cell.from(scopeLabel));
    }

    private void renderDetails(Frame frame, Rect area) {
        int sel = selectedIndex();
        if (sel < 0 || sel >= displayNodes.size()) return;
        var node = displayNodes.get(sel);

        Block block = Block.builder()
                .title(" " + node.gav() + " ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(theme.unfocusedBorder())
                .build();

        List<Line> lines = new ArrayList<>();
        lines.add(buildNodeSummaryLine(node));
        buildConflictLine(node, lines);
        buildPomInfoLines(node, lines, area.width());

        Paragraph details =
                Paragraph.builder().text(Text.from(lines)).block(block).build();
        frame.renderWidget(details, area);
    }

    private Line buildNodeSummaryLine(DependencyTreeModel.TreeNode node) {
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" Scope: ").bold());
        spans.add(Span.styled(node.scope, scopeStyle(node.scope)));

        if (node.classifier != null && !node.classifier.isEmpty()) {
            spans.add(Span.raw("  │ ").fg(theme.detailSeparatorColor()));
            spans.add(Span.raw("Classifier: ").bold());
            spans.add(Span.raw(node.classifier));
        }
        if (node.optional) {
            spans.add(Span.raw("  │ ").fg(theme.detailSeparatorColor()));
            spans.add(Span.raw("Optional").bold().fg(theme.statusWarningColor()));
        }
        spans.add(Span.raw("  │ ").fg(theme.detailSeparatorColor()));
        spans.add(Span.raw("Depth: ").bold());
        spans.add(Span.raw(String.valueOf(node.depth)));
        if (node.hasChildren()) {
            spans.add(Span.raw("  │ ").fg(theme.detailSeparatorColor()));
            spans.add(Span.raw("Children: ").bold());
            spans.add(Span.raw(String.valueOf(node.children.size())));
        }
        if (node.repository != null && !node.repository.isEmpty()) {
            spans.add(Span.raw("  │ ").fg(theme.detailSeparatorColor()));
            spans.add(Span.raw("Repo: ").bold());
            spans.add(Span.raw(node.repository).dim());
        }
        return Line.from(spans);
    }

    private void buildConflictLine(DependencyTreeModel.TreeNode node, List<Line> lines) {
        if (node.isConflict()) {
            List<Span> spans = new ArrayList<>();
            spans.add(Span.raw(" ⚠ Conflict: ").bold().fg(theme.statusWarningColor()));
            spans.add(Span.raw("requested ").dim());
            spans.add(Span.raw(node.requestedVersion).fg(theme.statusWarningColor()));
            spans.add(Span.raw(" → resolved ").dim());
            spans.add(Span.raw(node.version).bold());
            if (node.managedFrom != null) {
                spans.add(Span.raw("  │ ").fg(theme.detailSeparatorColor()));
                spans.add(Span.raw("Managed by: ").bold());
                spans.add(Span.raw(node.managedFrom).dim());
            }
            lines.add(Line.from(spans));
        } else if (node.managedFrom != null) {
            lines.add(Line.from(
                    Span.raw(" Managed by: ").bold(), Span.raw(node.managedFrom).dim()));
        }
    }

    private void buildPomInfoLines(DependencyTreeModel.TreeNode node, List<Line> lines, int areaWidth) {
        SearchTui.PomInfo info = pomInfoCache.get(node.gav());
        if (info == null || info.name == null) return;

        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" ").bold());
        spans.add(Span.raw(info.name).bold().cyan());
        if (info.license != null) {
            spans.add(Span.raw("  │ ").fg(theme.detailSeparatorColor()));
            spans.add(Span.raw(info.license).fg(theme.metadataValueColor()));
        }
        if (info.organization != null) {
            spans.add(Span.raw("  │ ").fg(theme.detailSeparatorColor()));
            spans.add(Span.raw(info.organization).dim());
        }
        if (info.date != null) {
            spans.add(Span.raw("  │ ").fg(theme.detailSeparatorColor()));
            spans.add(Span.raw(info.date).dim());
        }
        lines.add(Line.from(spans));

        if (info.description != null) {
            int maxWidth = Math.max(10, areaWidth - 4);
            String desc = info.description.length() > maxWidth
                    ? info.description.substring(0, maxWidth - 3) + "..."
                    : info.description;
            lines.add(Line.from(Span.raw(" " + desc).dim()));
        }
    }

    private Style scopeStyle(String scope) {
        return switch (scope) {
            case "compile" -> theme.scopeCompile();
            case "runtime" -> theme.scopeRuntime();
            case "test" -> theme.scopeTest();
            case "provided" -> theme.scopeProvided();
            case "system" -> theme.scopeSystem();
            default -> theme.scopeCompile();
        };
    }

    @Override
    protected void onSortChanged() {
        int col = sortState.sortColumn();
        if (col < 0) {
            model = fullModel.filterByScope(scope);
        } else {
            java.util.function.Function<DependencyTreeModel.TreeNode, String> extractor =
                    switch (col) {
                        case COL_GA -> n -> n.groupId + ":" + n.artifactId;
                        case COL_VERSION -> n -> n.version;
                        case COL_SCOPE -> n -> n.scope;
                        default -> n -> "";
                    };
            java.util.Comparator<DependencyTreeModel.TreeNode> cmp =
                    java.util.Comparator.comparing(extractor, String.CASE_INSENSITIVE_ORDER);
            if (!sortState.ascending()) {
                cmp = cmp.reversed();
            }
            sortChildrenRecursive(model.root, cmp);
        }
        refreshDisplay();
        fetchPomInfoIfNeeded();
    }

    private void sortChildrenRecursive(
            DependencyTreeModel.TreeNode node, java.util.Comparator<DependencyTreeModel.TreeNode> cmp) {
        node.children.sort(cmp);
        for (var child : node.children) {
            sortChildrenRecursive(child, cmp);
        }
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
                spans.add(Span.raw("└─ ").fg(theme.treeConnectorColor()));
            }

            spans.add(Span.raw(node.gav()).bold());

            if (i == reversePath.size() - 1) {
                spans.add(Span.raw(" ◀ selected").fg(theme.statusWarningColor()));
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

    // ── Standalone-only rendering ───────────────────────────────────────────

    private void renderHeader(Frame frame, Rect area) {
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" " + projectGav).bold().cyan());

        if (searchMode) {
            spans.add(Span.raw("  Search: ").fg(theme.searchBarLabelColor()));
            spans.add(Span.raw(searchBuffer.toString()));
            spans.add(Span.raw("█").fg(theme.searchBarLabelColor()));
            if (!searchMatches.isEmpty()) {
                spans.add(Span.raw("  " + (searchMatchIndex + 1) + "/" + searchMatches.size())
                        .fg(theme.searchMatchCountColor()));
            } else if (!searchBuffer.isEmpty()) {
                spans.add(Span.raw("  no matches").fg(theme.searchNoMatchColor()));
            }
        } else if (activeSearch != null) {
            spans.add(Span.raw("  [" + activeSearch + "] ").fg(theme.inactiveViewTabColor()));
            if (!searchMatches.isEmpty()) {
                spans.add(Span.raw((searchMatchIndex + 1) + "/" + searchMatches.size())
                        .fg(theme.searchMatchCountColor()));
            }
        }
        renderStandaloneHeader(frame, area, "Dependency Tree", Line.from(spans));
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
        int sel = selectedIndex();
        if (sel >= 0 && sel < displayNodes.size()) {
            var node = displayNodes.get(sel);
            String pomKey = node.gav();
            SearchTui.PomInfo info = pomInfoCache.get(pomKey);

            if (info != null && info.name != null) {
                spans.add(Span.raw(" "));
                spans.add(Span.raw(info.name).bold().cyan());
                if (info.license != null) {
                    spans.add(Span.raw(" │ ").fg(theme.separatorColor()));
                    spans.add(Span.raw(info.license).fg(theme.metadataValueColor()));
                }
                if (info.organization != null) {
                    spans.add(Span.raw(" │ ").fg(theme.separatorColor()));
                    spans.add(Span.raw(info.organization).dim());
                }
                if (info.date != null) {
                    spans.add(Span.raw(" │ ").fg(theme.separatorColor()));
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

        List<Span> searchHints = searchKeyHints();
        if (!searchHints.isEmpty()) {
            spans.addAll(searchHints);
            Paragraph line = Paragraph.from(Line.from(spans));
            frame.renderWidget(line, area);
            return;
        }
        if (showReversePath) {
            spans.add(Span.raw("Esc/Enter").bold());
            spans.add(Span.raw(":Back"));
        } else {
            spans.add(Span.raw("↑↓").bold());
            spans.add(Span.raw(":Navigate  "));
            spans.add(Span.raw("←→").bold());
            spans.add(Span.raw(":Expand/Collapse  "));
            spans.add(Span.raw("/").bold());
            spans.add(Span.raw(":Search  "));
            if (!model.conflicts.isEmpty()) {
                spans.add(Span.raw("c").bold());
                spans.add(Span.raw(":Conflicts  "));
            }
            spans.add(Span.raw("r").bold());
            spans.add(Span.raw(":Reverse  "));
            spans.add(Span.raw("f").bold());
            spans.add(Span.raw(":Scope(" + scope + ")  "));
            spans.add(Span.raw("E/W").bold());
            spans.add(Span.raw(":Expand/Collapse all  "));
            spans.add(Span.raw("h").bold());
            spans.add(Span.raw(":Help  "));
            spans.add(Span.raw("q").bold());
            spans.add(Span.raw(":Quit"));
        }

        Paragraph line = Paragraph.from(Line.from(spans));
        frame.renderWidget(line, area);
    }

    private List<HelpOverlay.Section> buildHelpStandalone() {
        List<HelpOverlay.Section> sections = new ArrayList<>(helpSections());
        sections.addAll(HelpOverlay.parse("""
                ## Colors (by scope)
                default         compile scope
                blue            runtime scope
                magenta         provided scope
                dark gray       test scope
                red             system scope
                yellow          Version conflict
                dim             Scope label, optional marker
                """ + GENERAL_STANDALONE_HELP));
        return sections;
    }

    // ── Async ───────────────────────────────────────────────────────────────

    private void fetchPomInfoIfNeeded() {
        int sel = selectedIndex();
        if (sel < 0 || sel >= displayNodes.size()) return;
        var node = displayNodes.get(sel);
        String pomKey = node.gav();
        if (pomInfoCache.containsKey(pomKey)) return;
        pomInfoCache.put(pomKey, new SearchTui.PomInfo(null, null, null, null, null, null, null)); // placeholder

        CompletableFuture.supplyAsync(
                        () -> SearchTui.fetchPomFromCentral(node.groupId, node.artifactId, node.version), httpPool)
                .thenAccept(info -> {
                    if (runner != null) {
                        runner.runOnRenderThread(() -> pomInfoCache.put(pomKey, info));
                    }
                });
    }
}
