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

    private final HelpOverlay helpOverlay = new HelpOverlay();

    private TuiRunner runner;
    private int lastContentHeight;

    /**
     * Get the currently selected table row index.
     *
     * @return the selected row index, or -1 if nothing is selected
     */
    private int selectedIndex() {
        Integer sel = tableState.selected();
        return sel != null ? sel : -1;
    }

    /**
     * Create a PomTui backed by the provided raw and effective POM XML texts.
     *
     * The constructor parses the effective POM into the internal model, initializes origin and parent-POM lookups as empty, and prepares the raw POM for the raw view; the initial selection is set to the first row.
     *
     * @param rawPom      the raw POM XML text used for the Raw view and origin fallback search
     * @param effectivePom the effective POM XML text used to build the Effective view
     * @param fileName    a label for the source (displayed in headers and used when reporting origin snippets)
     */
    PomTui(String rawPom, String effectivePom, String fileName) {
        this(rawPom, XmlTreeModel.parse(effectivePom), new IdentityHashMap<>(), fileName, Map.of());
    }

    /**
     * Creates a PomTui for viewing a raw POM and its effective model in the terminal UI.
     *
     * Parses the provided raw POM into the internal raw model, stores the effective model and
     * origin mapping (or an empty map if `null`), splits the raw POM into lines for later
     * origin lookups, and selects the initial table row (index 0).
     *
     * @param rawPom             the raw POM XML text
     * @param effectiveModel     the pre-parsed effective POM model to display
     * @param originMap          mapping of nodes to origin information; may be `null` to indicate empty
     * @param fileName           a label used as the source name when the raw POM is shown in origin snippets
     * @param parentPomContents  optional map of parent POM source names to their lines; may be `null` to indicate none
     */
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

    /**
     * Handle keyboard input events and perform corresponding UI actions such as navigation,
     * search entry/processing, toggling between Raw and Effective views, expanding/collapsing
     * nodes, and quitting the runner.
     *
     * @param event  the input event to handle; only {@code KeyEvent} instances are processed
     * @param runner the UI runner, used here to request quitting when appropriate
     * @return       {@code true} if the event was handled (no further processing needed),
     *               {@code false} if the event was not handled by this method
     */
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

        if (helpOverlay.isActive()) {
            if (helpOverlay.handleKey(key)) return true;
            if (key.isCharIgnoreCase('q') || key.isCtrlC()) {
                runner.quit();
                return true;
            }
            return false;
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
        if (key.isKey(KeyCode.PAGE_UP)) {
            int pageSize = Math.max(1, lastContentHeight - 3);
            tableState.select(Math.max(0, selectedIndex() - pageSize));
            return true;
        }
        if (key.isKey(KeyCode.PAGE_DOWN)) {
            int pageSize = Math.max(1, lastContentHeight - 3);
            tableState.select(Math.min(visible.size() - 1, selectedIndex() + pageSize));
            return true;
        }
        if (key.isHome()) {
            tableState.select(0);
            return true;
        }
        if (key.isEnd()) {
            tableState.select(visible.size() - 1);
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

        if (key.isCharIgnoreCase('h')) {
            helpOverlay.open(buildHelp());
            return true;
        }

        return false;
    }

    private List<HelpOverlay.Section> buildHelp() {
        return List.of(
                new HelpOverlay.Section(
                        "POM Browser",
                        List.of(
                                new HelpOverlay.Entry("", "Browse the project's POM as an expandable XML tree."),
                                new HelpOverlay.Entry("", ""),
                                new HelpOverlay.Entry("", "Raw POM: the actual pom.xml file content as written."),
                                new HelpOverlay.Entry("", ""),
                                new HelpOverlay.Entry("", "Effective POM: the fully resolved POM after parent"),
                                new HelpOverlay.Entry("", "inheritance, profile activation, and property"),
                                new HelpOverlay.Entry("", "interpolation. Shows what Maven actually uses."),
                                new HelpOverlay.Entry("", ""),
                                new HelpOverlay.Entry("", "The detail pane (bottom) shows origin info: which"),
                                new HelpOverlay.Entry("", "POM file defines the selected element (useful for"),
                                new HelpOverlay.Entry("", "understanding inherited configuration)."))),
                new HelpOverlay.Section(
                        "Colors",
                        List.of(
                                new HelpOverlay.Entry("cyan", "XML element names"),
                                new HelpOverlay.Entry("yellow", "Attribute values and search highlights"),
                                new HelpOverlay.Entry("green", "Search match count indicator"),
                                new HelpOverlay.Entry("dim", "XML structure characters, metadata"))),
                new HelpOverlay.Section(
                        "Navigation",
                        List.of(
                                new HelpOverlay.Entry("\u2191 / \u2193", "Move selection up / down"),
                                new HelpOverlay.Entry("PgUp / PgDn", "Move selection up / down by one page"),
                                new HelpOverlay.Entry("Home / End", "Jump to first / last row"),
                                new HelpOverlay.Entry("\u2190 / \u2192", "Collapse / expand tree node"),
                                new HelpOverlay.Entry("e", "Expand all nodes"),
                                new HelpOverlay.Entry("w", "Collapse all (keeps root expanded)"),
                                new HelpOverlay.Entry("Tab", "Switch Raw POM / Effective POM"))),
                new HelpOverlay.Section(
                        "Search",
                        List.of(
                                new HelpOverlay.Entry("/", "Enter search mode \u2014 type to search"),
                                new HelpOverlay.Entry("n / N", "Next / previous search match"),
                                new HelpOverlay.Entry("Esc", "Clear search or quit"))),
                new HelpOverlay.Section(
                        "General",
                        List.of(
                                new HelpOverlay.Entry("h", "Toggle this help screen"),
                                new HelpOverlay.Entry("q / Esc", "Quit"))));
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

    /**
     * Updates the list of visible-node indices that match the current search buffer
     * and adjusts the active match index and table selection accordingly.
     *
     * <p>If the search buffer is empty, clears matches and resets the active index.
     * Otherwise computes matching visible-node indices, sets the active match to
     * the first result (if any) and selects that row in {@code tableState}.</p>
     */
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

    /**
     * Determines whether the given node's searchable text contains the provided query.
     *
     * For a Comment node the searchable text is the comment content; for an Element node it is
     * the element name concatenated with its trimmed text content. Other node types are not searched.
     *
     * @param node  the node to test
     * @param query the lowercased search substring to match against the node's searchable text
     * @return      `true` if the node's searchable text contains `query`, `false` otherwise
     */
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

    /**
     * Chooses the XML tree model that should be used for rendering and interaction.
     *
     * @return the raw model when the view is RAW, otherwise the effective model
     */
    private XmlTreeModel currentModel() {
        return view == View.RAW ? rawModel : effectiveModel;
    }

    /**
     * Render the complete TUI into the given frame.
     *
     * Renders a header, the XML tree view, an optional origin snippet detail (only when in Effective view and a snippet exists),
     * and a footer, laying out vertical regions and allocating extra rows for the detail section when shown.
     *
     * @param frame the frame to render the UI into
     */
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
        lastContentHeight = zones.get(1).height();
        if (helpOverlay.isActive()) {
            helpOverlay.render(frame, zones.get(idx++));
        } else {
            renderXmlTree(frame, zones.get(idx++));
        }
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

    /**
     * Render the current XML tree view (raw or effective) into the provided frame area.
     *
     * Renders a titled, bordered table of the model's visible nodes, highlights rows that
     * match the active or in-progress search query, and shows a centered "Empty" placeholder
     * when there are no visible nodes.
     *
     * @param frame the frame used for drawing widgets
     * @param area the rectangular area inside the frame to render the tree into
     */
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
            spans.add(Span.raw("h").bold());
            spans.add(Span.raw(":Help  "));
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
     * Locate the source snippet for the currently selected effective POM node.
     *
     * Attempts to resolve origin information in three steps: use the identity-mapped
     * OriginInfo for the selected node when available; otherwise search the raw POM
     * text for a matching element; finally search configured parent POM contents.
     *
     * @return the SnippetInfo containing formatted source lines and their source name,
     *         or `null` if no origin snippet can be determined
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

    /**
     * Builds a formatted snippet of source lines surrounding a matched line for display.
     *
     * The snippet includes the matched line plus up to two lines of context before and after,
     * each prefixed with a 1-based, four-wide line number and a separator. The matched line
     * is additionally prefixed with an arrow marker.
     *
     * @param lines the source lines to extract the snippet from
     * @param matchLine the zero-based index of the matched line within `lines`
     * @return a list of formatted strings representing the snippet, in display order
     */
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

    /**
     * Finds the line index in the provided source lines that contains the element's opening tag.
     *
     * <p>Matches all tag forms: {@code <name>}, {@code <name ...>}, {@code <name/>}, and {@code <name />}.
     * When the element has same-named siblings under its parent, returns the Nth matching line
     * corresponding to this element's position among those siblings.</p>
     *
     * @param node  the node to locate in the source lines; only {@link Element} nodes are considered
     * @param lines the source text split into lines to search
     * @return the zero-based index of the matching line, or -1 if no match is found or the node is not an element
     */
    private int findInLines(Node node, String[] lines) {
        if (!(node instanceof Element element)) return -1;
        String name = element.name();

        // Match all opening-tag forms
        String tagOpen = "<" + name + ">";
        String tagOpenAttr = "<" + name + " ";
        String tagSelfClose = "<" + name + "/>";
        String tagSelfCloseSpace = "<" + name + " />";

        // Compute occurrence index among same-named siblings
        int occurrenceIndex = 0;
        Node parent = element.parent();
        if (parent instanceof Element parentElement) {
            for (Node sibling : XmlTreeModel.treeChildren(parentElement)) {
                if (sibling == element) break;
                if (sibling instanceof Element se && se.name().equals(name)) {
                    occurrenceIndex++;
                }
            }
        }

        // Find the Nth matching line
        int matchCount = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains(tagOpen)
                    || line.contains(tagOpenAttr)
                    || line.contains(tagSelfClose)
                    || line.contains(tagSelfCloseSpace)) {
                if (matchCount == occurrenceIndex) {
                    return i;
                }
                matchCount++;
            }
        }
        return -1;
    }
}
