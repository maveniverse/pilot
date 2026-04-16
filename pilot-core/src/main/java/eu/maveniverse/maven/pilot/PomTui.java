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
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
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
import eu.maveniverse.domtrip.Comment;
import eu.maveniverse.domtrip.Element;
import eu.maveniverse.domtrip.Node;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Interactive POM viewer with switchable Raw/Effective views and origin detail pane.
 */
public class PomTui extends ToolPanel {

    private enum View {
        RAW,
        EFFECTIVE
    }

    public static class OriginInfo {
        final String source;
        final int lineNumber;
        final List<String> sourceLines;

        public OriginInfo(String source, int lineNumber, List<String> sourceLines) {
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
    public PomTui(
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

        if (helpOverlay.isActive()) {
            if (helpOverlay.handleKey(key)) return true;
            if (key.isCharIgnoreCase('q') || key.isCtrlC()) {
                runner.quit();
                return true;
            }
            return false;
        }

        // Delegate tool-specific keys
        if (handleKeyEvent(key)) return true;

        // Standalone: Tab switches views
        if (key.isKey(KeyCode.TAB)) {
            view = (view == View.RAW) ? View.EFFECTIVE : View.RAW;
            tableState.select(0);
            clearSearch();
            return true;
        }

        if (key.isKey(KeyCode.ESCAPE)) {
            runner.quit();
            return true;
        }
        if (key.isCharIgnoreCase('q')) {
            runner.quit();
            return true;
        }

        if (key.isCharIgnoreCase('h')) {
            helpOverlay.open(buildHelpStandalone());
            return true;
        }

        return false;
    }

    // ── ToolPanel interface ─────────────────────────────────────────────────

    @Override
    public String toolName() {
        return "Pom";
    }

    @Override
    public void render(Frame frame, Rect area) {
        SnippetInfo snippet = getSelectedOriginSnippet();

        Rect contentArea = renderTabBar(frame, area);
        if (view == View.EFFECTIVE) {
            int detailHeight = snippet != null ? Math.min(snippet.lines().size() + 2, 9) : 7;
            var zones = Layout.vertical()
                    .constraints(Constraint.fill(), Constraint.length(1), Constraint.length(detailHeight))
                    .split(contentArea);
            renderXmlTree(frame, zones.get(0), snippet);
            renderOriginDetail(frame, zones.get(2), snippet);
        } else {
            renderXmlTree(frame, contentArea, null);
        }
    }

    @Override
    public boolean handleKeyEvent(KeyEvent key) {
        if (handleSearchInput(key)) return true;

        if (key.isChar('1')) {
            if (view != View.RAW) {
                view = View.RAW;
                tableState.select(0);
                clearSearch();
            }
            return true;
        }
        if (key.isChar('2')) {
            if (view != View.EFFECTIVE) {
                view = View.EFFECTIVE;
                tableState.select(0);
                clearSearch();
            }
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
        if (TableNavigation.handlePageKeys(
                key, tableState, visible.size(), lastContentHeight, TableNavigation.BORDERED_NO_HEADER)) {
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

    @Override
    public boolean handleMouseEvent(MouseEvent mouse, Rect area) {
        if (handleMouseTabBar(mouse)) return true;
        if (mouse.isClick()) {
            var visible = currentModel().visibleNodes();
            int row = mouse.y() - area.y() - 2 + tableState.offset(); // tab bar + border
            if (row >= 0 && row < visible.size()) {
                tableState.select(row);
                // Toggle expand/collapse on click
                var node = visible.get(row);
                if (node instanceof Element e && XmlTreeModel.hasTreeChildren(e)) {
                    currentModel().setExpanded(e, !currentModel().isExpanded(e));
                }
                return true;
            }
        }
        if (mouse.isScroll()) {
            var visible = currentModel().visibleNodes();
            if (visible.isEmpty()) return false;
            int sel = tableState.selected();
            if (mouse.kind() == MouseEventKind.SCROLL_UP) {
                tableState.select(Math.max(0, sel - 1));
            } else {
                tableState.select(Math.min(visible.size() - 1, sel + 1));
            }
            return true;
        }
        return false;
    }

    @Override
    public String status() {
        String viewName = view == View.RAW ? "Raw POM" : "Effective POM";
        String search = searchStatus();
        if (search != null) {
            return searchMode ? search : viewName + " — " + search;
        }
        return viewName;
    }

    @Override
    public List<Span> keyHints() {
        List<Span> searchHints = searchKeyHints();
        if (!searchHints.isEmpty()) {
            return searchHints;
        }
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw("←→").bold());
        spans.add(Span.raw(":Expand  "));
        spans.add(Span.raw("/").bold());
        spans.add(Span.raw(":Search  "));
        spans.add(Span.raw("e/w").bold());
        spans.add(Span.raw(":Expand/Collapse all"));
        return spans;
    }

    @Override
    public List<HelpOverlay.Section> helpSections() {
        return HelpOverlay.parse("""
                ## POM Browser
                Browse the project's POM as an expandable XML tree.
                Raw POM: the actual pom.xml file content as written.
                Effective POM: the fully resolved POM after parent
                inheritance, profile activation, and property
                interpolation. Shows what Maven actually uses.
                The detail pane (bottom) shows origin info: which
                POM file defines the selected element (useful for
                understanding inherited configuration).

                ## Colors
                cyan            XML element names
                yellow          Attribute values and search highlights
                green           Search match count indicator
                dim             XML structure characters, metadata

                ## Navigation
                \u2191 / \u2193           Move selection up / down
                PgUp / PgDn     Move selection up / down by one page
                Home / End      Jump to first / last row
                \u2190 / \u2192           Collapse / expand tree node
                e               Expand all nodes
                w               Collapse all (keeps root expanded)
                Tab             Switch Raw POM / Effective POM
                """ + SEARCH_HELP);
    }

    @Override
    public void setRunner(TuiRunner runner) {
        this.runner = runner;
    }

    @Override
    int subViewCount() {
        return 2;
    }

    @Override
    int activeSubView() {
        return view.ordinal();
    }

    @Override
    void setActiveSubView(int index) {
        view = View.values()[index];
        tableState.select(0);
    }

    @Override
    List<String> subViewNames() {
        return List.of("Raw", "Effective");
    }

    private List<HelpOverlay.Section> buildHelpStandalone() {
        List<HelpOverlay.Section> sections = new ArrayList<>(helpSections());
        sections.addAll(HelpOverlay.parse(GENERAL_STANDALONE_HELP));
        return sections;
    }

    @Override
    protected void selectSearchMatch(int matchIndex) {
        tableState.select(searchMatches.get(matchIndex));
    }

    /**
     * Updates the list of visible-node indices that match the current search buffer
     * and adjusts the active match index and table selection accordingly.
     *
     * <p>If the search buffer is empty, clears matches and resets the active index.
     * Otherwise computes matching visible-node indices, sets the active match to
     * the first result (if any) and selects that row in {@code tableState}.</p>
     */
    @Override
    protected void updateSearchMatches() {
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
     * Render the complete standalone TUI into the given frame.
     *
     * Renders a header, the XML tree view, an optional origin snippet detail (only when in Effective view and a snippet exists),
     * and a footer, laying out vertical regions and allocating extra rows for the detail section when shown.
     *
     * @param frame the frame to render the UI into
     */
    void renderStandalone(Frame frame) {
        SnippetInfo snippet = getSelectedOriginSnippet();
        boolean showDetail = view == View.EFFECTIVE;

        List<Constraint> constraints = new ArrayList<>();
        constraints.add(Constraint.length(3));
        constraints.add(Constraint.fill());
        if (showDetail) {
            int detailHeight = snippet != null ? Math.min(snippet.lines().size() + 2, 9) : 7;
            constraints.add(Constraint.length(1)); // spacer above detail
            constraints.add(Constraint.length(detailHeight));
        }
        constraints.add(Constraint.length(showDetail ? 1 : 2));

        var zones = Layout.vertical()
                .constraints(constraints.toArray(Constraint[]::new))
                .split(frame.area());

        int idx = 0;
        renderHeader(frame, zones.get(idx++));
        lastContentHeight = zones.get(1).height();
        Rect treeArea = renderStandaloneHelp(frame, zones.get(idx));
        if (treeArea != null) {
            renderXmlTree(frame, treeArea, showDetail ? snippet : null);
        }
        idx++;
        if (showDetail) {
            idx++; // skip spacer
            renderOriginDetail(frame, zones.get(idx++), snippet);
        }
        renderFooter(frame, zones.get(idx), showDetail);
    }

    private void renderHeader(Frame frame, Rect area) {
        List<Span> spans = new ArrayList<>();
        spans.addAll(TabBar.render(view, View.values(), v -> switch (v) {
            case RAW -> "Raw POM";
            case EFFECTIVE -> "Effective POM";
        }));

        if (searchMode) {
            spans.add(Span.raw("   Search: ").fg(theme.searchBarLabelColor()));
            spans.add(Span.raw(searchBuffer.toString()));
            spans.add(Span.raw("█").fg(theme.searchBarLabelColor()));
            if (!searchMatches.isEmpty()) {
                spans.add(Span.raw("  " + (searchMatchIndex + 1) + "/" + searchMatches.size())
                        .fg(theme.searchMatchCountColor()));
            } else if (!searchBuffer.isEmpty()) {
                spans.add(Span.raw("  no matches").fg(theme.searchNoMatchColor()));
            }
        } else if (activeSearch != null) {
            spans.add(Span.raw("   [" + activeSearch + "] ").fg(theme.inactiveViewTabColor()));
            if (!searchMatches.isEmpty()) {
                spans.add(Span.raw((searchMatchIndex + 1) + "/" + searchMatches.size())
                        .fg(theme.searchMatchCountColor()));
            }
        }
        renderStandaloneHeader(frame, area, "POM Viewer", Line.from(spans));
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
    private void renderXmlTree(Frame frame, Rect area, SnippetInfo snippet) {
        var model = currentModel();

        Block block = Block.builder()
                .borderType(BorderType.ROUNDED)
                .borderStyle(borderStyle())
                .build();

        var visible = model.visibleNodes();
        if (visible.isEmpty()) {
            Paragraph empty =
                    Paragraph.builder().text("Empty").block(block).centered().build();
            frame.renderWidget(empty, area);
            return;
        }

        String searchQuery = currentSearchQuery();

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < visible.size(); i++) {
            var node = visible.get(i);
            Line line = model.renderNode(node);

            if (searchQuery != null && !searchQuery.isEmpty() && nodeMatchesSearch(node, searchQuery)) {
                rows.add(Row.from(Cell.from(line)).style(theme.searchHighlight()));
                continue;
            }

            rows.add(Row.from(Cell.from(line)));
        }

        Table table = Table.builder()
                .rows(rows)
                .widths(Constraint.fill())
                .highlightStyle(theme.highlightStyle())
                .highlightSymbol(theme.highlightSymbol())
                .block(block)
                .build();

        frame.renderStatefulWidget(table, area, tableState);
    }

    private void renderOriginDetail(Frame frame, Rect area, SnippetInfo snippet) {
        Block.Builder blockBuilder =
                Block.builder().borderType(BorderType.ROUNDED).borderStyle(theme.originDetailBorder());
        if (snippet != null) {
            blockBuilder.title(" Source: " + snippet.source() + " ");
        } else {
            blockBuilder.title(" Source ");
        }
        Block block = blockBuilder.build();

        if (snippet == null) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text("  origin unknown")
                            .style(dev.tamboui.style.Style.create().fg(Color.RED))
                            .block(block)
                            .build(),
                    area);
            return;
        }

        List<Row> rows = new ArrayList<>();
        for (String sourceLine : snippet.lines()) {
            List<Span> spans = new ArrayList<>();
            boolean isTarget = sourceLine.startsWith("→");
            if (isTarget) {
                spans.add(Span.raw(sourceLine).bold().fg(theme.originTargetColor()));
            } else {
                spans.add(Span.raw(sourceLine).fg(theme.originContextColor()));
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
            spans.add(Span.raw("←→").bold());
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
        if (origin != null) {
            if (origin.sourceLines != null && !origin.sourceLines.isEmpty()) {
                return new SnippetInfo(origin.sourceLines, origin.source);
            }
            // Pre-computed snippet was empty but we have a line number — build snippet now
            if (origin.lineNumber > 0) {
                String[] lines = resolveSourceLines(origin.source);
                if (lines != null) {
                    return new SnippetInfo(buildSnippet(lines, origin.lineNumber - 1), origin.source);
                }
                // Can't load source file but we know where it's from
                return new SnippetInfo(
                        List.of("Source: " + origin.source + ", line " + origin.lineNumber), origin.source);
            }
        }

        // No origin info in map — fall back to raw POM text search
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
            String prefix = (i == matchLine) ? "→ " : "  ";
            String lineNum = String.format("%4d", i + 1);
            snippet.add(prefix + lineNum + " │ " + lines[i]);
        }
        return snippet;
    }

    private String[] resolveSourceLines(String source) {
        if (fileName.equals(source) || "this POM".equals(source)) {
            return rawPomLines;
        }
        return parentPomContents.get(source);
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
