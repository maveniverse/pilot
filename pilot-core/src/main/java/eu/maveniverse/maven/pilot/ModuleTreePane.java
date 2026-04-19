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
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Span;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Left-panel component that renders the reactor module tree.
 *
 * <p>Supports three width modes (full with GA column, narrow tree-only, hidden)
 * with keyboard navigation and {@code /} filter mode. Notifies the shell when
 * the selected module changes.</p>
 */
class ModuleTreePane {

    private final Theme theme = Theme.DEFAULT;
    private final ReactorModel reactorModel;
    private final TableState tableState = new TableState();
    private final Consumer<PilotProject> onSelectionChanged;
    private boolean focused;

    private final SearchController searchController;

    ModuleTreePane(ReactorModel reactorModel, Consumer<PilotProject> onSelectionChanged) {
        this.reactorModel = reactorModel;
        this.onSelectionChanged = onSelectionChanged;
        this.searchController = new SearchController(this::findSearchMatches, rowIndex -> {
            tableState.select(rowIndex);
            notifySelection();
        });
        if (!reactorModel.allModules.isEmpty()) {
            tableState.select(0);
        }
    }

    private List<Integer> findSearchMatches(String query) {
        List<ReactorModel.ModuleNode> visible = visibleNodes();
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < visible.size(); i++) {
            var node = visible.get(i);
            String searchable = node.project.ga();
            if (searchable.toLowerCase().contains(query)
                    || node.name.toLowerCase().contains(query)) {
                matches.add(i);
            }
        }
        return matches;
    }

    void setFocused(boolean focused) {
        this.focused = focused;
    }

    /** Returns the currently selected project, or null. */
    PilotProject selectedProject() {
        var visible = visibleNodes();
        Integer sel = tableState.selected();
        if (sel != null && sel >= 0 && sel < visible.size()) {
            return visible.get(sel).project;
        }
        return null;
    }

    /** Returns the subtree projects for the current selection. */
    List<PilotProject> selectedSubtree() {
        var visible = visibleNodes();
        Integer sel = tableState.selected();
        if (sel != null && sel >= 0 && sel < visible.size()) {
            return reactorModel.collectSubtree(visible.get(sel));
        }
        return List.of();
    }

    /** Returns whether the selected module is a parent (has children). */
    boolean isSelectedParent() {
        var visible = visibleNodes();
        Integer sel = tableState.selected();
        if (sel != null && sel >= 0 && sel < visible.size()) {
            return visible.get(sel).hasChildren();
        }
        return false;
    }

    private List<ReactorModel.ModuleNode> visibleNodes() {
        return reactorModel.visibleNodes();
    }

    // -- Event handling --

    boolean handleKeyEvent(KeyEvent key) {
        if (searchController.handleSearchInput(key)) return true;

        List<ReactorModel.ModuleNode> visible = visibleNodes();

        if (key.isUp()) {
            tableState.selectPrevious();
            notifySelection();
            return true;
        }
        if (key.isDown()) {
            tableState.selectNext(visible.size());
            notifySelection();
            return true;
        }
        if (key.isKey(KeyCode.PAGE_UP)) {
            for (int i = 0; i < 10; i++) tableState.selectPrevious();
            notifySelection();
            return true;
        }
        if (key.isKey(KeyCode.PAGE_DOWN)) {
            for (int i = 0; i < 10; i++) tableState.selectNext(visible.size());
            notifySelection();
            return true;
        }
        if (key.isKey(KeyCode.HOME)) {
            tableState.select(0);
            notifySelection();
            return true;
        }
        if (key.isKey(KeyCode.END)) {
            if (!visible.isEmpty()) {
                tableState.select(visible.size() - 1);
                notifySelection();
            }
            return true;
        }

        // Expand/collapse
        if (key.isKey(KeyCode.RIGHT) || key.isCharIgnoreCase(' ')) {
            Integer sel = tableState.selected();
            if (sel != null && sel >= 0 && sel < visible.size()) {
                var node = visible.get(sel);
                if (node.hasChildren() && !node.expanded) {
                    node.expanded = true;
                } else {
                    tableState.selectNext(visibleNodes().size());
                    notifySelection();
                }
            }
            return true;
        }
        if (key.isKey(KeyCode.LEFT)) {
            Integer sel = tableState.selected();
            if (sel != null && sel >= 0 && sel < visible.size()) {
                var node = visible.get(sel);
                if (node.expanded && node.hasChildren()) {
                    node.expanded = false;
                } else {
                    for (int i = sel - 1; i >= 0; i--) {
                        if (visible.get(i).depth < node.depth) {
                            tableState.select(i);
                            notifySelection();
                            break;
                        }
                    }
                }
            }
            return true;
        }

        if (key.isChar('E')) {
            expandAll(reactorModel.root);
            return true;
        }
        if (key.isChar('W')) {
            Integer sel = tableState.selected();
            ReactorModel.ModuleNode selectedNode =
                    (sel != null && sel >= 0 && sel < visible.size()) ? visible.get(sel) : null;
            collapseAll(reactorModel.root);
            reactorModel.root.expanded = true;
            if (selectedNode != null) {
                var newVisible = visibleNodes();
                int newIndex = newVisible.indexOf(selectedNode);
                if (newIndex < 0) {
                    int targetDepth = selectedNode.depth;
                    for (int i = sel - 1; i >= 0; i--) {
                        if (visible.get(i).depth < targetDepth) {
                            newIndex = newVisible.indexOf(visible.get(i));
                            if (newIndex >= 0) break;
                            targetDepth = visible.get(i).depth;
                        }
                    }
                    if (newIndex < 0) newIndex = 0;
                }
                tableState.select(newIndex);
            }
            return true;
        }

        return false;
    }

    boolean handleMouseEvent(MouseEvent mouse, Rect area) {
        if (mouse.isClick()) {
            // Calculate which row was clicked (area has border, no header + scroll offset)
            int row = mouse.y() - area.y() - 1 + tableState.offset(); // border + scroll
            var visible = visibleNodes();
            if (row >= 0 && row < visible.size()) {
                tableState.select(row);
                notifySelection();
                return true;
            }
        }
        if (mouse.isScroll()) {
            var visible = visibleNodes();
            if (visible.isEmpty()) return false;
            int sel = tableState.selected();
            if (mouse.kind() == MouseEventKind.SCROLL_UP) {
                tableState.select(Math.max(0, sel - 1));
            } else {
                tableState.select(Math.min(visible.size() - 1, sel + 1));
            }
            notifySelection();
            return true;
        }
        return false;
    }

    private void notifySelection() {
        PilotProject project = selectedProject();
        if (project != null) {
            onSelectionChanged.accept(project);
        }
    }

    String searchStatus() {
        return searchController.searchStatus();
    }

    List<Span> searchKeyHints() {
        return searchController.searchKeyHints();
    }

    private void expandAll(ReactorModel.ModuleNode node) {
        node.expanded = true;
        for (var child : node.children) {
            expandAll(child);
        }
    }

    private void collapseAll(ReactorModel.ModuleNode node) {
        node.expanded = false;
        for (var child : node.children) {
            collapseAll(child);
        }
    }

    // -- Rendering --

    /**
     * Render the module tree in full mode (tree name + GA column).
     */
    void renderFull(Frame frame, Rect area) {
        Style borderStyle = focused ? theme.focusedBorder() : theme.unfocusedBorder();
        Block block = Block.builder()
                .title(" Modules (" + reactorModel.allModules.size() + ") ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(borderStyle)
                .build();

        List<ReactorModel.ModuleNode> visible = visibleNodes();

        if (visible.isEmpty()) {
            Paragraph empty = Paragraph.builder()
                    .text("No modules")
                    .block(block)
                    .centered()
                    .build();
            frame.renderWidget(empty, area);
            return;
        }

        renderTable(frame, area, block, visible, true);
    }

    /**
     * Render the module tree in narrow mode (tree name only, no GA column).
     */
    void renderNarrow(Frame frame, Rect area) {
        Style borderStyle = focused ? theme.focusedBorder() : theme.unfocusedBorder();
        Block block = Block.builder()
                .title(" Modules ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(borderStyle)
                .build();

        List<ReactorModel.ModuleNode> visible = visibleNodes();

        if (visible.isEmpty()) {
            Paragraph empty = Paragraph.builder()
                    .text("No modules")
                    .block(block)
                    .centered()
                    .build();
            frame.renderWidget(empty, area);
            return;
        }

        renderTable(frame, area, block, visible, false);
    }

    private void renderTable(
            Frame frame, Rect area, Block block, List<ReactorModel.ModuleNode> visible, boolean showGa) {
        // GA column width = half the area minus borders (2) and highlight symbol (2)
        int gaColWidth = showGa ? (area.width() - 4) / 2 : 0;

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < visible.size(); i++) {
            Row row = buildRow(visible.get(i), showGa, gaColWidth);
            if (searchController.isSearchMatch(i)) {
                row = row.style(row.style().bg(theme.searchHighlightBg()));
            }
            rows.add(row);
        }

        Table.Builder builder = Table.builder()
                .rows(rows)
                .highlightStyle(theme.highlightStyle())
                .highlightSymbol(theme.highlightSymbol())
                .block(block);

        if (showGa) {
            builder.widths(Constraint.percentage(50), Constraint.percentage(50));
        } else {
            builder.widths(Constraint.fill());
        }

        frame.renderStatefulWidget(builder.build(), area, tableState);
    }

    private Row buildRow(ReactorModel.ModuleNode node, boolean showGa, int gaColWidth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < node.depth; i++) {
            sb.append("  ");
        }
        if (node.hasChildren()) {
            sb.append(node.expanded ? "▾ " : "▸ ");
        } else {
            sb.append("  ");
        }
        sb.append(node.name);

        if (showGa) {
            String ga = compactGa(node.project.groupId, node.project.artifactId, gaColWidth);
            return Row.from(sb.toString(), ga);
        } else {
            return Row.from(sb.toString());
        }
    }

    /**
     * Compact a groupId:artifactId to fit within maxWidth by progressively
     * shortening groupId segments from left to right.
     * e.g. org.apache.camel:core → o.apache.camel:core → o.a.camel:core → o.a.c:core
     */
    static String compactGa(String groupId, String artifactId, int maxWidth) {
        String full = groupId + ":" + artifactId;
        if (maxWidth <= 0 || full.length() <= maxWidth) {
            return full;
        }

        String[] segments = groupId.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].length() > 1) {
                segments[i] = segments[i].substring(0, 1);
                String compacted = String.join(".", segments) + ":" + artifactId;
                if (compacted.length() <= maxWidth) {
                    return compacted;
                }
            }
        }
        // All segments shortened, return as-is
        return String.join(".", segments) + ":" + artifactId;
    }

    List<Span> keyHints() {
        List<Span> hints = new ArrayList<>();
        hints.add(Span.raw("↑↓").bold());
        hints.add(Span.raw(":Nav  "));
        hints.add(Span.raw("←→").bold());
        hints.add(Span.raw(":Expand  "));
        hints.add(Span.raw("/").bold());
        hints.add(Span.raw(":Search  "));
        return hints;
    }

    List<HelpOverlay.Section> helpSections() {
        return List.of(new HelpOverlay.Section(
                "Module Tree",
                List.of(
                        new HelpOverlay.Entry("", "Navigate the reactor module hierarchy."),
                        new HelpOverlay.Entry("", "Selecting a module updates the content pane."),
                        new HelpOverlay.Entry("", ""),
                        new HelpOverlay.Entry("↑ / ↓", "Move selection up / down"),
                        new HelpOverlay.Entry("← / →", "Collapse / expand tree node"),
                        new HelpOverlay.Entry("Space", "Expand node or move down"),
                        new HelpOverlay.Entry("E / W", "Expand all / collapse all"),
                        new HelpOverlay.Entry("/", "Search modules by name or GA"),
                        new HelpOverlay.Entry("n / N", "Next / previous search match"),
                        new HelpOverlay.Entry("Enter", "Switch focus to content pane"))));
    }
}
