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
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.paragraph.Paragraph;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for tool panels that render into the right-side area of the unified shell.
 *
 * <p>Each panel owns its full right-side area and manages its own internal layout
 * (sub-view tabs, content table, detail pane). The shell delegates rendering and
 * event handling to the active panel.</p>
 */
public abstract class ToolPanel {

    /** Whether this panel currently has focus (controls border color). */
    protected boolean focused;

    /** Theme for all color/style decisions. */
    protected final Theme theme = Theme.DEFAULT;

    /** Help overlay for standalone mode (shell has its own for panel mode). */
    protected final HelpOverlay helpOverlay = new HelpOverlay();

    /**
     * Handle help overlay slide-up rendering in standalone mode.
     * If help is active, animates it upward from the bottom of the content area,
     * renders the help panel, and returns the remaining area for normal content
     * (or {@code null} if help has taken the full area).
     * If help is not active, returns the original area unchanged.
     */
    protected Rect renderStandaloneHelp(Frame frame, Rect contentArea) {
        if (!helpOverlay.isActive()) return contentArea;
        int helpH = helpOverlay.animate(contentArea.height());
        if (helpH >= contentArea.height()) {
            helpOverlay.render(frame, contentArea);
            return null;
        } else if (helpH > 0) {
            var split = Layout.vertical()
                    .constraints(Constraint.fill(), Constraint.length(helpH))
                    .split(contentArea);
            helpOverlay.render(frame, split.get(1));
            return split.get(0);
        }
        return contentArea;
    }

    /** Tool display name for the header tool bar. */
    abstract String toolName();

    /**
     * Render the entire right-side area (sub-view tabs + content + detail + status).
     * The panel decides its own internal vertical layout.
     */
    abstract void render(Frame frame, Rect area);

    /**
     * Handle a key event. Only called when the content pane has focus.
     * Should handle: navigation, sub-view switching (←/→), sort, filter,
     * and tool-specific actions.
     *
     * @return {@code true} if the event was handled
     */
    abstract boolean handleKeyEvent(KeyEvent key);

    /**
     * Handle a mouse event. Only called when the click is within this panel's area.
     *
     * @return {@code true} if the event was handled
     */
    abstract boolean handleMouseEvent(MouseEvent mouse, Rect area);

    /** Status line text for the info panel. */
    abstract String status();

    /**
     * Key binding hints for the info panel (tool-specific keys only;
     * the shell adds global keys like Tab, h, q).
     */
    abstract List<Span> keyHints();

    /** Help sections for the help overlay. */
    abstract List<HelpOverlay.Section> helpSections();

    /** Number of sub-views (tabs) this panel has. Default is 1 (no tabs). */
    int subViewCount() {
        return 1;
    }

    /** Index of the currently active sub-view. */
    int activeSubView() {
        return 0;
    }

    /** Switch to the given sub-view index. */
    void setActiveSubView(int index) {}

    /** Names of the sub-views for tab rendering. */
    List<String> subViewNames() {
        return List.of();
    }

    /** Whether this panel has unsaved changes. */
    boolean isDirty() {
        return false;
    }

    /**
     * Save pending changes.
     *
     * @return {@code true} on success
     */
    boolean save() {
        return true;
    }

    /** Called when the TuiRunner is available (for async operations). */
    void setRunner(TuiRunner runner) {}

    /**
     * Handle a TUI event in standalone mode.
     *
     * @return {@code true} to request a re-render
     */
    abstract boolean handleEvent(Event event, TuiRunner runner);

    /** Render the panel in standalone mode (full terminal). */
    abstract void renderStandalone(Frame frame);

    /**
     * Run this panel as a standalone TUI (outside of PilotShell).
     * Creates a {@link TuiRunner}, wires up event handling and rendering,
     * and blocks until the user quits.
     */
    public void runStandalone() throws Exception {
        TuiRunner.ConfiguredRunner configured = TuiRunner.builder()
                .eventHandler(this::handleEvent)
                .renderer(this::renderStandalone)
                .tickRate(Duration.ofMillis(20))
                .mouseCapture(true)
                .build();
        try {
            setRunner(configured.runner());
            configured.run();
        } finally {
            configured.close();
            close();
        }
    }

    /**
     * Release resources held by this panel (e.g. thread pools).
     * Called automatically by {@link #runStandalone()} in its {@code finally} block.
     * Default implementation does nothing.
     */
    void close() {}

    /** Called when the panel's focus state changes (controls border color). */
    void setFocused(boolean focused) {
        this.focused = focused;
    }

    /** Returns the border style to use based on focus state. */
    protected Style borderStyle() {
        return focused ? theme.focusedBorder() : theme.unfocusedBorder();
    }

    /**
     * Renders a standalone header block with a consistent "Pilot — title" format.
     * Each panel builds its own content {@link Line} (GAV, tabs, search state, etc.)
     * and passes it here to avoid duplicating the Block + Paragraph boilerplate.
     *
     * @param frame   the terminal frame
     * @param area    the header area (typically 3 lines high with border)
     * @param title   the tool-specific title (e.g. "Dependency Overview")
     * @param content the content line to display inside the header block
     */
    protected void renderStandaloneHeader(Frame frame, Rect area, String title, Line content) {
        Block block = Block.builder()
                .title(" Pilot — " + title + " ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(theme.focusedBorder())
                .build();
        Paragraph header =
                Paragraph.builder().text(Text.from(content)).block(block).build();
        frame.renderWidget(header, area);
    }

    // ── Sort infrastructure ──────────────────────────────────────────────────

    /** Sort state for table-based panels. Null if sorting is not supported. */
    protected SortState sortState;

    /**
     * Handle sort-related key events: {@code s} to cycle columns, {@code S} to reverse direction.
     *
     * @return {@code true} if the event was consumed
     */
    protected boolean handleSortInput(KeyEvent key) {
        if (sortState == null) return false;
        if (key.isChar('s')) {
            sortState.cycleNext();
            onSortChanged();
            return true;
        }
        if (key.isChar('S')) {
            sortState.reverseDirection();
            onSortChanged();
            return true;
        }
        return false;
    }

    /** Called when sort state changes. Override to re-sort display data. */
    protected void onSortChanged() {}

    /**
     * The area where the table was last rendered. Set by panels during render
     * so that mouse header-click detection works.
     */
    protected Rect lastTableArea;

    /**
     * Handle mouse click on a table header row to toggle column sort.
     * Panels should call this in {@link #handleMouseEvent} and set {@link #lastTableArea}
     * during rendering.
     *
     * @param mouse  the mouse event
     * @param widths the column constraints matching the table layout
     * @return {@code true} if the click was on the header and was handled
     */
    protected boolean handleMouseSortHeader(MouseEvent mouse, List<Constraint> widths) {
        if (sortState == null || lastTableArea == null || !mouse.isClick()) return false;
        int headerY = lastTableArea.y() + 1; // first row inside border
        if (mouse.y() != headerY) return false;
        Rect inner = new Rect(
                lastTableArea.x() + 1,
                lastTableArea.y() + 1,
                Math.max(0, lastTableArea.width() - 2),
                Math.max(0, lastTableArea.height() - 2));
        var cols = Layout.horizontal().constraints(widths).split(inner);
        for (int i = 0; i < cols.size(); i++) {
            Rect col = cols.get(i);
            if (mouse.x() >= col.x() && mouse.x() < col.x() + col.width()) {
                sortState.toggleColumn(i);
                onSortChanged();
                return true;
            }
        }
        return false;
    }

    /** Returns sort-specific key hints, or empty list if sorting is not active. */
    protected List<Span> sortKeyHints() {
        if (sortState == null) return List.of();
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw("s").bold());
        spans.add(Span.raw(":Sort  "));
        if (sortState.isSorted()) {
            spans.add(Span.raw("S").bold());
            spans.add(Span.raw(":Reverse  "));
        }
        return spans;
    }

    // ── Search infrastructure ───────────────────────────────────────────────

    /** Whether the panel is currently in search-input mode. */
    protected boolean searchMode;

    /** Buffer for the search query being typed. */
    protected final StringBuilder searchBuffer = new StringBuilder();

    /** The confirmed (active) search query, or null if no search is active. */
    protected String activeSearch;

    /** Row indices that match the current search query. */
    protected List<Integer> searchMatches = List.of();

    /** Index into {@link #searchMatches} for the currently focused match. */
    protected int searchMatchIndex = -1;

    /**
     * Handle search-related key events. Call this at the beginning of handleKeyEvent().
     * Handles: '/' to start search, typing in search mode, Enter/Escape, n/N navigation.
     *
     * @return {@code true} if the event was consumed
     */
    protected boolean handleSearchInput(KeyEvent key) {
        if (searchMode) {
            if (key.isKey(KeyCode.ESCAPE)) {
                searchMode = false;
                activeSearch = null;
                searchMatches = List.of();
                searchMatchIndex = -1;
                return true;
            }
            if (key.isKey(KeyCode.ENTER)) {
                searchMode = false;
                if (!searchBuffer.isEmpty()) {
                    activeSearch = searchBuffer.toString().toLowerCase();
                }
                return true;
            }
            if (key.isKey(KeyCode.BACKSPACE) && !searchBuffer.isEmpty()) {
                searchBuffer.deleteCharAt(searchBuffer.length() - 1);
                updateSearchMatches();
                return true;
            }
            if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
                searchBuffer.append(key.character());
                updateSearchMatches();
                return true;
            }
            return true; // consume all keys in search mode
        }

        // Not in search mode
        if (key.isCharIgnoreCase('/')) {
            searchMode = true;
            searchBuffer.setLength(0);
            activeSearch = null;
            searchMatches = List.of();
            searchMatchIndex = -1;
            return true;
        }
        if (key.isKey(KeyCode.ESCAPE) && activeSearch != null) {
            activeSearch = null;
            searchMatches = List.of();
            searchMatchIndex = -1;
            return true;
        }
        if (key.isChar('n') && activeSearch != null && !searchMatches.isEmpty()) {
            searchMatchIndex = (searchMatchIndex + 1) % searchMatches.size();
            selectSearchMatch(searchMatchIndex);
            return true;
        }
        if (key.isChar('N') && activeSearch != null && !searchMatches.isEmpty()) {
            searchMatchIndex = (searchMatchIndex - 1 + searchMatches.size()) % searchMatches.size();
            selectSearchMatch(searchMatchIndex);
            return true;
        }
        return false;
    }

    /** Override to update search matches when the query changes (called during live typing). */
    protected void updateSearchMatches() {}

    /** Override to select the row at the given match index. */
    protected void selectSearchMatch(int matchIndex) {}

    /** Clear all search state. Useful when switching sub-views. */
    protected void clearSearch() {
        searchMode = false;
        searchBuffer.setLength(0);
        activeSearch = null;
        searchMatches = List.of();
        searchMatchIndex = -1;
    }

    /** Returns search-specific status text, or null if no search is active. */
    protected String searchStatus() {
        if (searchMode) {
            return "Search: " + searchBuffer + "█";
        }
        if (activeSearch != null && !searchMatches.isEmpty()) {
            return (searchMatchIndex + 1) + "/" + searchMatches.size() + " matches";
        }
        if (activeSearch != null) {
            return "No matches for: " + activeSearch;
        }
        return null;
    }

    /** Returns search-specific key hints, or empty list if no search is active. */
    protected List<Span> searchKeyHints() {
        List<Span> spans = new ArrayList<>();
        if (searchMode) {
            spans.add(Span.raw("Type").bold());
            spans.add(Span.raw(":Search  "));
            spans.add(Span.raw("Enter").bold());
            spans.add(Span.raw(":Confirm  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Cancel"));
        } else if (activeSearch != null) {
            spans.add(Span.raw("n/N").bold());
            spans.add(Span.raw(":Next/Prev  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Clear"));
        }
        return spans;
    }

    /** Returns the current search query (live buffer in search mode, confirmed query otherwise). */
    protected String currentSearchQuery() {
        if (searchMode && !searchBuffer.isEmpty()) {
            return searchBuffer.toString().toLowerCase();
        }
        return activeSearch;
    }

    /** Check if a given row index is a search match (for highlighting). */
    protected boolean isSearchMatch(int rowIndex) {
        return searchMatches.contains(rowIndex);
    }

    // ── Sub-view tab bar mouse support ──────────────────────────────────────

    private Rect lastTabBarArea;
    private int[] tabStarts;
    private int[] tabEnds;

    /**
     * Renders the sub-view tab bar (if this panel has multiple sub-views) and returns the
     * remaining content area. When {@link #subViewCount()} is 1, returns the full area unchanged.
     *
     * <p>Call this in {@link #render(Frame, Rect)} after handling any full-area overlays
     * (diff, help) to avoid duplicating the tab-bar split in every per-view render method.</p>
     *
     * @return the {@link Rect} below the tab bar, or the original area if there is only one view
     */
    protected Rect renderTabBar(Frame frame, Rect area) {
        if (subViewCount() <= 1) {
            lastTabBarArea = null;
            return area;
        }
        var zones = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.fill())
                .split(area);
        lastTabBarArea = zones.get(0);

        // Compute tab hit-test positions
        List<String> names = subViewNames();
        int active = activeSubView();
        tabStarts = new int[names.size()];
        tabEnds = new int[names.size()];
        int xPos = 0;
        for (int i = 0; i < names.size(); i++) {
            tabStarts[i] = xPos;
            // Active: " [▸N:Label]", Inactive: " [N:Label]"
            String numPrefix = (i + 1) + ":";
            int width = 1
                    + 1
                    + numPrefix.length()
                    + names.get(i).length()
                    + 1
                    + (i == active ? 1 : 0); // space + [ + N: + label + ] + (▸ if active)
            xPos += width;
            tabEnds[i] = xPos;
        }

        frame.renderWidget(Paragraph.from(buildSubViewTabLine()), zones.get(0));
        return zones.get(1);
    }

    /**
     * Check if a mouse click hit a sub-view tab label. Call this early in
     * {@link #handleMouseEvent(MouseEvent, Rect)}.
     *
     * @return {@code true} if a tab was clicked and the view was switched
     */
    protected boolean handleMouseTabBar(MouseEvent mouse) {
        if (!mouse.isClick() || lastTabBarArea == null || tabStarts == null) return false;
        if (mouse.y() != lastTabBarArea.y()) return false;
        int mx = mouse.x() - lastTabBarArea.x();
        for (int i = 0; i < tabStarts.length; i++) {
            if (mx >= tabStarts[i] && mx < tabEnds[i]) {
                if (i != activeSubView()) {
                    setActiveSubView(i);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Builds a styled tab line from {@link #subViewNames()}, highlighting the active sub-view.
     * Active tab is cyan+bold when focused, white+bold when not.
     * Inactive tabs are white when focused, dark gray when not.
     * Appends a {@code [modified]} indicator if {@link #isDirty()} returns true.
     */
    protected Line buildSubViewTabLine() {
        List<String> names = subViewNames();
        int active = activeSubView();
        List<Span> spans = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            if (i == active) {
                spans.add(theme.activeTab(names.get(i), focused, i + 1));
            } else {
                spans.add(theme.inactiveTab(names.get(i), focused, i + 1));
            }
        }
        if (isDirty()) {
            spans.add(theme.dirtyIndicator());
        }
        return Line.from(spans);
    }
}
