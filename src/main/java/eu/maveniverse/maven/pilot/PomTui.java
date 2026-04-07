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
import eu.maveniverse.domtrip.Comment;
import eu.maveniverse.domtrip.Element;
import eu.maveniverse.domtrip.Node;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Interactive POM viewer with switchable Raw/Effective views and origin detail pane.
 */
class PomTui {

    private enum View {
        RAW,
        EFFECTIVE
    }

    static class OriginInfo {
        final String source;
        final int lineNumber;
        final List<String> sourceLines;

        OriginInfo(String source, int lineNumber, List<String> sourceLines) {
            this.source = source;
            this.lineNumber = lineNumber;
            this.sourceLines = sourceLines;
        }
    }

    record SnippetInfo(List<String> lines, String source) {}

    private final XmlTreeModel rawModel;
    private final XmlTreeModel effectiveModel;
    private final IdentityHashMap<Node, OriginInfo> originMap;
    private final String fileName;
    private final String[] rawPomLines;
    private final Map<String, String[]> parentPomContents;

    private View view = View.RAW;
    private final TableState tableState = new TableState();
    private boolean searchMode = false;
    private final StringBuilder searchBuffer = new StringBuilder();
    private String activeSearch = null;
    private List<Integer> searchMatches = List.of();
    private int searchMatchIndex = -1;

    private TuiRunner runner;

    private int selectedIndex() {
        Integer sel = tableState.selected();
        return sel != null ? sel : 0;
    }

    PomTui(String rawPom, String effectivePom, String fileName) {
        this(rawPom, XmlTreeModel.parse(effectivePom), new IdentityHashMap<>(), fileName, Map.of());
    }

    PomTui(
            String rawPom,
            XmlTreeModel effectiveModel,
            IdentityHashMap<Node, OriginInfo> originMap,
            String fileName,
            Map<String, String[]> parentPomContents) {
        this.rawModel = XmlTreeModel.parse(rawPom);
        this.effectiveModel = effectiveModel;
        this.originMap = originMap != null ? originMap : new IdentityHashMap<>();
        this.fileName = fileName;
        this.rawPomLines = rawPom.split("\n");
        this.parentPomContents = parentPomContents != null ? parentPomContents : Map.of();
        tableState.select(0);
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

        if (key.isCtrlC()) {
            runner.quit();
            return true;
        }

        if (searchMode) {
            return handleSearchKeys(key);
        }

        if (key.isKey(KeyCode.ESCAPE)) {
            if (activeSearch != null) {
                activeSearch = null;
                searchMatches = List.of();
                searchMatchIndex = -1;
                return true;
            }
            runner.quit();
            return true;
        }
        if (key.isCharIgnoreCase('q')) {
            runner.quit();
            return true;
        }

        if (key.isKey(KeyCode.TAB)) {
            view = (view == View.RAW) ? View.EFFECTIVE : View.RAW;
            tableState.select(0);
            activeSearch = null;
            searchMatches = List.of();
            searchMatchIndex = -1;
            return true;
        }

        if (key.isCharIgnoreCase('/')) {
            searchMode = true;
            searchBuffer.setLength(0);
            activeSearch = null;
            searchMatches = List.of();
            searchMatchIndex = -1;
            return true;
        }

        if (key.isChar('n') && activeSearch != null && !searchMatches.isEmpty()) {
            searchMatchIndex = (searchMatchIndex + 1) % searchMatches.size();
            tableState.select(searchMatches.get(searchMatchIndex));
            return true;
        }
        if (key.isChar('N') && activeSearch != null && !searchMatches.isEmpty()) {
            searchMatchIndex = (searchMatchIndex - 1 + searchMatches.size()) % searchMatches.size();
            tableState.select(searchMatches.get(searchMatchIndex));
            return true;
        }

        var model = currentModel();
        var visible = model.visibleNodes();

        if (key.isUp()) {
            tableState.selectPrevious();
            return true;
        }
        if (key.isDown()) {
            tableState.selectNext(visible.size());
            return true;
        }

        int sel = selectedIndex();
        if (sel >= 0 && sel < visible.size()) {
            var node = visible.get(sel);
            if (key.isRight()) {
                if (node instanceof Element e && XmlTreeModel.hasTreeChildren(e) && !model.isExpanded(e)) {
                    model.setExpanded(e, true);
                } else {
                    tableState.selectNext(visible.size());
                }
                return true;
            }
            if (key.isLeft()) {
                if (node instanceof Element e && model.isExpanded(e) && XmlTreeModel.hasTreeChildren(e)) {
                    model.setExpanded(e, false);
                } else {
                    for (int i = sel - 1; i >= 0; i--) {
                        if (visible.get(i).depth() < node.depth()) {
                            tableState.select(i);
                            break;
                        }
                    }
                }
                return true;
            }
        }

        if (key.isCharIgnoreCase('e')) {
            model.expandAll(model.root);
            return true;
        }
        if (key.isCharIgnoreCase('w')) {
            model.collapseAll(model.root);
            model.setExpanded(model.root, true);
            return true;
        }

        return false;
    }

    private boolean handleSearchKeys(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE)) {
            searchMode = false;
            activeSearch = null;
            searchMatches = List.of();
            searchMatchIndex = -1;
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            searchMode = false;
            if (searchBuffer.length() > 0) {
                activeSearch = searchBuffer.toString().toLowerCase();
            }
            return true;
        }
        if (key.code() == KeyCode.CHAR) {
            searchBuffer.append(key.character());
            updateSearchMatches();
            return true;
        }
        if (key.isKey(KeyCode.BACKSPACE) && searchBuffer.length() > 0) {
            searchBuffer.deleteCharAt(searchBuffer.length() - 1);
            updateSearchMatches();
            return true;
        }
        return false;
    }

    private void updateSearchMatches() {
        String query = searchBuffer.toString().toLowerCase();
        if (query.isEmpty()) {
            searchMatches = List.of();
            searchMatchIndex = -1;
            return;
        }
        var visible = currentModel().visibleNodes();
        searchMatches = new ArrayList<>();
        for (int i = 0; i < visible.size(); i++) {
            if (nodeMatchesSearch(visible.get(i), query)) {
                searchMatches.add(i);
            }
        }
        if (!searchMatches.isEmpty()) {
            searchMatchIndex = 0;
            tableState.select(searchMatches.get(0));
        } else {
            searchMatchIndex = -1;
        }
    }

    private boolean nodeMatchesSearch(Node node, String query) {
        String text;
        if (node instanceof Comment comment) {
            text = comment.content();
        } else if (node instanceof Element element) {
            text = element.name() + element.textContentTrimmedOr("");
        } else {
            return false;
        }
        return text.toLowerCase().contains(query);
    }

    private XmlTreeModel currentModel() {
        return view == View.RAW ? rawModel : effectiveModel;
    }

    // -- Rendering --

    void render(Frame frame) {
        SnippetInfo snippet = getSelectedOriginSnippet();
        boolean showDetail = view == View.EFFECTIVE && snippet != null;

        List<Constraint> constraints = new ArrayList<>();
        constraints.add(Constraint.length(3));
        constraints.add(Constraint.fill());
        if (showDetail) {
            constraints.add(Constraint.length(1)); // spacer above detail
            constraints.add(Constraint.length(Math.min(snippet.lines().size() + 2, 9)));
        }
        constraints.add(Constraint.length(showDetail ? 1 : 2));

        var zones = Layout.vertical()
                .constraints(constraints.toArray(Constraint[]::new))
                .split(frame.area());

        int idx = 0;
        renderHeader(frame, zones.get(idx++));
        renderXmlTree(frame, zones.get(idx++));
        if (showDetail) {
            idx++; // skip spacer
            renderOriginDetail(frame, zones.get(idx++), snippet);
        }
        renderFooter(frame, zones.get(idx), showDetail);
    }

    private void renderHeader(Frame frame, Rect area) {
        Block block = Block.builder()
                .title(" Pilot \u2014 POM Viewer ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().cyan())
                .build();

        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" "));

        // View tabs
        spans.add(Span.raw("[" + (view == View.RAW ? "\u25B8 " : "  ") + "Raw POM]")
                .fg(view == View.RAW ? Color.YELLOW : Color.DARK_GRAY));
        spans.add(Span.raw("  "));
        spans.add(Span.raw("[" + (view == View.EFFECTIVE ? "\u25B8 " : "  ") + "Effective POM]")
                .fg(view == View.EFFECTIVE ? Color.YELLOW : Color.DARK_GRAY));

        if (searchMode) {
            spans.add(Span.raw("   Search: ").fg(Color.YELLOW));
            spans.add(Span.raw(searchBuffer.toString()));
            spans.add(Span.raw("\u2588").fg(Color.YELLOW));
            if (!searchMatches.isEmpty()) {
                spans.add(Span.raw("  " + (searchMatchIndex + 1) + "/" + searchMatches.size())
                        .fg(Color.GREEN));
            } else if (searchBuffer.length() > 0) {
                spans.add(Span.raw("  no matches").fg(Color.RED));
            }
        } else if (activeSearch != null) {
            spans.add(Span.raw("   [" + activeSearch + "] ").fg(Color.DARK_GRAY));
            if (!searchMatches.isEmpty()) {
                spans.add(Span.raw((searchMatchIndex + 1) + "/" + searchMatches.size())
                        .fg(Color.GREEN));
            }
        }

        Paragraph header = Paragraph.builder()
                .text(dev.tamboui.text.Text.from(Line.from(spans)))
                .block(block)
                .build();
        frame.renderWidget(header, area);
    }

    private void renderXmlTree(Frame frame, Rect area) {
        var model = currentModel();
        String title = view == View.RAW ? " " + fileName + " " : " Effective POM ";

        Block block = Block.builder()
                .title(title)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().cyan())
                .build();

        var visible = model.visibleNodes();
        if (visible.isEmpty()) {
            Paragraph empty =
                    Paragraph.builder().text("Empty").block(block).centered().build();
            frame.renderWidget(empty, area);
            return;
        }

        String searchQuery = searchMode ? searchBuffer.toString().toLowerCase() : activeSearch;
        List<Row> rows = new ArrayList<>();
        for (var node : visible) {
            Line line = model.renderNode(node);

            if (searchQuery != null && !searchQuery.isEmpty() && nodeMatchesSearch(node, searchQuery)) {
                rows.add(Row.from(Cell.from(line)).style(Style.create().bg(Color.DARK_GRAY)));
                continue;
            }

            rows.add(Row.from(Cell.from(line)));
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

    private void renderOriginDetail(Frame frame, Rect area, SnippetInfo snippet) {
        Block block = Block.builder()
                .title(" Source: " + snippet.source() + " ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().yellow())
                .build();

        List<Row> rows = new ArrayList<>();
        for (String sourceLine : snippet.lines()) {
            List<Span> spans = new ArrayList<>();
            boolean isTarget = sourceLine.startsWith("\u2192");
            if (isTarget) {
                spans.add(Span.raw(sourceLine).bold().fg(Color.YELLOW));
            } else {
                spans.add(Span.raw(sourceLine).fg(Color.DARK_GRAY));
            }
            rows.add(Row.from(Cell.from(Line.from(spans))));
        }

        Table table = Table.builder()
                .rows(rows)
                .widths(Constraint.fill())
                .block(block)
                .build();

        frame.renderStatefulWidget(table, area, new TableState());
    }

    private void renderFooter(Frame frame, Rect area, boolean detailVisible) {
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" "));
        if (searchMode) {
            spans.add(Span.raw("Type").bold());
            spans.add(Span.raw(":Search  "));
            spans.add(Span.raw("Enter").bold());
            spans.add(Span.raw(":Confirm  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Cancel"));
        } else if (activeSearch != null) {
            spans.add(Span.raw("n/N").bold());
            spans.add(Span.raw(":Next/Prev match  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Clear search  "));
            spans.add(Span.raw("q").bold());
            spans.add(Span.raw(":Quit"));
        } else {
            spans.add(Span.raw("Tab").bold());
            spans.add(Span.raw(":Switch view  "));
            spans.add(Span.raw("\u2190\u2192").bold());
            spans.add(Span.raw(":Expand/Collapse  "));
            spans.add(Span.raw("/").bold());
            spans.add(Span.raw(":Search  "));
            spans.add(Span.raw("e/w").bold());
            spans.add(Span.raw(":Expand/Collapse all  "));
            spans.add(Span.raw("q").bold());
            spans.add(Span.raw(":Quit"));
        }

        Paragraph line = Paragraph.from(Line.from(spans));
        if (detailVisible) {
            // 1-line footer, render directly
            frame.renderWidget(line, area);
        } else {
            // 2-line footer: blank line + keybindings
            var rows = Layout.vertical()
                    .constraints(Constraint.length(1), Constraint.length(1))
                    .split(area);
            frame.renderWidget(line, rows.get(1));
        }
    }

    /**
     * Find origin info for the selected effective POM node.
     * Uses the IdentityHashMap populated by PomMojo's parallel Model/XmlTree walk,
     * then falls back to raw POM text search.
     */
    private SnippetInfo getSelectedOriginSnippet() {
        if (view != View.EFFECTIVE) return null;
        int sel = selectedIndex();
        var visible = effectiveModel.visibleNodes();
        if (sel < 0 || sel >= visible.size()) return null;

        var node = visible.get(sel);
        if (!(node instanceof Element)) return null;

        // Direct lookup via IdentityHashMap — no path building needed
        OriginInfo origin = originMap.get(node);
        if (origin != null && origin.sourceLines != null && !origin.sourceLines.isEmpty()) {
            return new SnippetInfo(origin.sourceLines, origin.source);
        }

        // Fall back to raw POM text search
        int matchLine = findInLines(node, rawPomLines);
        if (matchLine >= 0) {
            return new SnippetInfo(buildSnippet(rawPomLines, matchLine), fileName);
        }

        // Last resort: search parent POMs for the element
        for (var entry : parentPomContents.entrySet()) {
            String[] parentLines = entry.getValue();
            int parentMatch = findInLines(node, parentLines);
            if (parentMatch >= 0) {
                return new SnippetInfo(buildSnippet(parentLines, parentMatch), entry.getKey());
            }
        }

        return null;
    }

    private List<String> buildSnippet(String[] lines, int matchLine) {
        List<String> snippet = new ArrayList<>();
        int start = Math.max(0, matchLine - 2);
        int end = Math.min(lines.length - 1, matchLine + 2);
        for (int i = start; i <= end; i++) {
            String prefix = (i == matchLine) ? "\u2192 " : "  ";
            String lineNum = String.format("%4d", i + 1);
            snippet.add(prefix + lineNum + " \u2502 " + lines[i]);
        }
        return snippet;
    }

    private int findInLines(Node node, String[] lines) {
        if (!(node instanceof Element element)) return -1;
        if (XmlTreeModel.isLeaf(element)) {
            String search = "<" + element.name() + ">";
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(search)) return i;
            }
        } else if (XmlTreeModel.hasTreeChildren(element)) {
            String search1 = "<" + element.name() + ">";
            String search2 = "<" + element.name() + " ";
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(search1) || lines[i].contains(search2)) return i;
            }
        }
        return -1;
    }
}
