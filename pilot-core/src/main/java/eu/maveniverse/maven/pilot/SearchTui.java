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
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Interactive TUI for Maven Central artifact search.
 *
 * @since 0.1.0
 */
public class SearchTui extends ToolPanel {

    @FunctionalInterface
    public interface SearchClient {
        JsonObject query(String searchTerm, int rows, int start) throws Exception;
    }

    static class PomInfo {
        final String name;
        final String description;
        final String url;
        final String organization;
        final String license;
        final String licenseUrl;
        final String date;

        PomInfo(
                String name,
                String description,
                String url,
                String organization,
                String license,
                String licenseUrl,
                String date) {
            this.name = name;
            this.description = description;
            this.url = url;
            this.organization = organization;
            this.license = license;
            this.licenseUrl = licenseUrl;
            this.date = date;
        }
    }

    private enum Focus {
        SEARCH,
        TABLE
    }

    private Focus focus = Focus.SEARCH;

    // Search field state
    private final StringBuilder searchBuffer;
    private int cursorPos;

    // Search tracking
    private String lastSearchedQuery;
    private int searchGeneration;

    // Table state
    private final TableState tableState = new TableState();
    private List<String[]> artifacts = new ArrayList<>();
    private int totalFound;

    // Version cycling
    private final Map<String, List<String>> versionCache = new HashMap<>();
    private int[] versionIndices = new int[0];

    // POM info
    private final Map<String, PomInfo> pomInfoCache = new HashMap<>();

    // Package-private for testing
    void cachePomInfo(String groupId, String artifactId, String version, PomInfo info) {
        pomInfoCache.put(groupId + ":" + artifactId + ":" + version, info);
    }

    // Status
    private String status;
    private boolean loading;

    // Async pagination
    private boolean prefetchingMore;

    // Output
    private String selectedGav;

    // Background HTTP pool (limited to avoid saturating connections)
    private final ExecutorService httpPool = PilotUtil.newHttpPool();

    // Dependencies
    private final SearchClient client;
    private TuiRunner runner;
    private int lastContentHeight;

    /**
     * Get the currently selected table row index, defaulting to -1 when no row is selected.
     *
     * @return the selected row index, or -1 if no selection exists
     */
    private int selectedIndex() {
        Integer sel = tableState.selected();
        return sel != null ? sel : -1;
    }

    /**
     * Initializes a SearchTui instance and its interactive state from optional initial data.
     *
     * Sets up the search buffer and cursor from {@code initialQuery} (accepting {@code null}),
     * records the trimmed last searched query, and, if {@code initialResults} is non-empty,
     * copies them into the internal artifacts list, initializes per-row version indices,
     * records {@code totalFound}, updates the status message, and selects the first table row.
     * If no initial results are supplied, sets the status to "Type to search".
     *
     * @param client the SearchClient used for performing remote queries
     * @param initialQuery an optional initial search string (may be {@code null})
     * @param initialResults an optional initial list of artifact rows (may be {@code null} or empty)
     * @param totalFound the total number of results corresponding to {@code initialResults}
     */
    public SearchTui(SearchClient client, String initialQuery, List<String[]> initialResults, int totalFound) {
        this.client = client;
        this.searchBuffer = new StringBuilder(initialQuery != null ? initialQuery : "");
        this.cursorPos = searchBuffer.length();
        this.lastSearchedQuery = initialQuery != null ? initialQuery.trim() : "";
        this.sortState = new SortState(3);
        if (initialResults != null && !initialResults.isEmpty()) {
            this.artifacts = new ArrayList<>(initialResults);
            this.versionIndices = new int[artifacts.size()];
            this.totalFound = totalFound;
            this.status = totalFound + " result(s) found";
            this.tableState.select(0);
        } else {
            this.status = "Type to search";
        }
    }

    public String runAndSelect() throws Exception {
        runStandalone();
        return selectedGav;
    }

    @Override
    void close() {
        httpPool.shutdownNow();
    }

    // -- Event handling -----------------------------------------------------

    boolean handleEvent(Event event, TuiRunner runner) {
        if (!(event instanceof KeyEvent key)) {
            return true; // re-render on tick/resize so async updates are visible
        }

        if (key.isCtrlC()) {
            runner.quit();
            return true;
        }

        // Delegate tool-specific keys
        if (handleKeyEvent(key)) return true;

        // Standalone: Tab switches focus
        if (key.isKey(KeyCode.TAB)) {
            focus = (focus == Focus.SEARCH) ? Focus.TABLE : Focus.SEARCH;
            return true;
        }

        // Standalone: q quits (from table mode)
        if (key.isCharIgnoreCase('q') && focus == Focus.TABLE) {
            runner.quit();
            return true;
        }

        if (key.isKey(KeyCode.ESCAPE)) {
            runner.quit();
            return true;
        }

        return false;
    }

    @Override
    public String toolName() {
        return "Search";
    }

    @Override
    public void render(Frame frame, Rect area) {
        var zones = Layout.vertical()
                .constraints(Constraint.length(3), Constraint.fill(), Constraint.length(2))
                .split(area);

        renderSearchBar(frame, zones.get(0));
        renderResultsTable(frame, zones.get(1));

        // Simplified info: just details + key bindings (no syntax help)
        var infoRows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1))
                .split(zones.get(2));
        renderArtifactDetails(frame, infoRows.get(0));
        renderKeyBindings(frame, infoRows.get(1));
    }

    @Override
    public boolean handleKeyEvent(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE)) {
            if (focus == Focus.TABLE) {
                focus = Focus.SEARCH;
                return true;
            }
            return false; // let shell handle
        }

        if (focus == Focus.TABLE && handleSortInput(key)) return true;

        if (focus == Focus.SEARCH) {
            return handleSearchKeys(key);
        } else {
            return handleTableKeys(key);
        }
    }

    @Override
    public boolean handleMouseEvent(MouseEvent mouse, Rect area) {
        if (handleMouseSortHeader(
                mouse, List.of(Constraint.percentage(45), Constraint.percentage(30), Constraint.percentage(25)))) {
            return true;
        }
        if (mouse.isClick()) {
            int row = mouse.y() - area.y() - 2 + tableState.offset(); // border + header
            if (row >= 0 && row < artifacts.size()) {
                tableState.select(row);
                return true;
            }
        }
        if (mouse.isScroll()) {
            if (artifacts.isEmpty()) return false;
            int sel = tableState.selected();
            if (mouse.kind() == MouseEventKind.SCROLL_UP) {
                tableState.select(Math.max(0, sel - 1));
            } else {
                tableState.select(Math.min(artifacts.size() - 1, sel + 1));
            }
            return true;
        }
        return false;
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public List<Span> keyHints() {
        List<Span> spans = new ArrayList<>();
        if (focus == Focus.SEARCH) {
            spans.add(Span.raw("↑↓").bold());
            spans.add(Span.raw(":Results  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Back"));
        } else {
            spans.add(Span.raw("↑↓").bold());
            spans.add(Span.raw(":Navigate  "));
            spans.add(Span.raw("←→").bold());
            spans.add(Span.raw(":Version  "));
            spans.addAll(sortKeyHints());
            spans.add(Span.raw("Enter").bold());
            spans.add(Span.raw(":Select  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Back"));
        }
        return spans;
    }

    @Override
    public List<HelpOverlay.Section> helpSections() {
        return HelpOverlay.parse("""
                ## Maven Central Search
                Search for artifacts on Maven Central.
                Type to search, use arrows to navigate.

                ## Search Syntax
                free text       Full-text search
                g:groupId       Search by groupId
                a:artifactId    Search by artifactId
                g:... AND a:...  Combined search

                ## Search Actions
                ↑ / ↓           Navigate results
                ← / →           Cycle through versions
                Enter           Select artifact / confirm search
                Esc             Back to search / quit
                """);
    }

    @Override
    public void setRunner(TuiRunner runner) {
        this.runner = runner;
    }

    private boolean handleSearchKeys(KeyEvent key) {
        if (key.isKey(KeyCode.ENTER)) {
            if (!artifacts.isEmpty()) {
                focus = Focus.TABLE;
                fetchPomInfoIfNeeded();
            } else {
                triggerSearch();
            }
            return true;
        }
        if (key.isDown() && !artifacts.isEmpty()) {
            focus = Focus.TABLE;
            fetchPomInfoIfNeeded();
            return true;
        }
        if (key.code() == KeyCode.CHAR) {
            searchBuffer.insert(cursorPos, key.character());
            cursorPos++;
            onQueryChanged();
            return true;
        }
        if (key.isKey(KeyCode.BACKSPACE)) {
            if (cursorPos > 0) {
                searchBuffer.deleteCharAt(cursorPos - 1);
                cursorPos--;
                onQueryChanged();
            }
            return true;
        }
        if (key.isKey(KeyCode.DELETE)) {
            if (cursorPos < searchBuffer.length()) {
                searchBuffer.deleteCharAt(cursorPos);
                onQueryChanged();
            }
            return true;
        }
        if (key.isLeft()) {
            if (cursorPos > 0) {
                cursorPos--;
            }
            return true;
        }
        if (key.isRight()) {
            if (cursorPos < searchBuffer.length()) {
                cursorPos++;
            }
            return true;
        }
        if (key.isHome()) {
            cursorPos = 0;
            return true;
        }
        if (key.isEnd()) {
            cursorPos = searchBuffer.length();
            return true;
        }
        return false;
    }

    /**
     * Handle a key event when the UI is in the table focus, performing navigation,
     * selection, version cycling, or switching back to the search editor as needed.
     *
     * Handles: typing (moves focus to search and inserts the character),
     * up/down navigation (moves selection and may fetch or prefetch results/POMs),
     * left/right version cycling, and confirm/select to choose the artifact.
     *
     * @return `true` if the key was handled (event consumed), `false` otherwise.
     */
    private boolean handleTableKeys(KeyEvent key) {
        if (artifacts.isEmpty()) {
            return false;
        }

        // Typing in table mode jumps back to search
        if (key.code() == KeyCode.CHAR) {
            focus = Focus.SEARCH;
            searchBuffer.insert(cursorPos, key.character());
            cursorPos++;
            onQueryChanged();
            return true;
        }

        if (key.isUp()) {
            tableState.selectPrevious();
            fetchPomInfoIfNeeded();
            return true;
        }
        if (key.isDown()) {
            handleDownKey();
            return true;
        }
        if (TableNavigation.handlePageKeys(key, tableState, artifacts.size(), lastContentHeight)) {
            prefetchIfNearBottom();
            fetchPomInfoIfNeeded();
            return true;
        }
        if (key.isLeft()) {
            int selected = selectedIndex();
            cycleVersion(selected, -1);
            return true;
        }
        if (key.isRight()) {
            int selected = selectedIndex();
            cycleVersion(selected, +1);
            return true;
        }
        if (key.isSelect() || key.isConfirm()) {
            int selected = selectedIndex();
            selectArtifact(selected);
            return true;
        }
        return false;
    }

    private void handleDownKey() {
        int before = selectedIndex();
        tableState.selectNext(artifacts.size());
        int after = selectedIndex();
        if (before == after && artifacts.size() < totalFound && !prefetchingMore) {
            fetchMoreResultsSync();
        } else {
            prefetchIfNearBottom();
        }
        fetchPomInfoIfNeeded();
    }

    private void prefetchIfNearBottom() {
        int sel = selectedIndex();
        if (sel >= artifacts.size() / 2 && artifacts.size() < totalFound) {
            prefetchMoreResults();
        }
    }

    private void onQueryChanged() {
        String q = searchBuffer.toString().trim();
        if (q.isEmpty()) {
            artifacts = new ArrayList<>();
            versionIndices = new int[0];
            totalFound = 0;
            status = "Type to search";
            loading = false;
            lastSearchedQuery = "";
        } else {
            triggerSearch();
        }
    }

    // -- Rendering ----------------------------------------------------------

    void renderStandalone(Frame frame) {
        List<Rect> zones = Layout.vertical()
                .constraints(Constraint.length(3), Constraint.fill(), Constraint.length(4))
                .split(frame.area());

        renderSearchBar(frame, zones.get(0));
        lastContentHeight = zones.get(1).height();
        renderResultsTable(frame, zones.get(1));
        renderInfoBar(frame, zones.get(2));
    }

    private void renderSearchBar(Frame frame, Rect area) {
        Style borderStyle = focus == Focus.SEARCH ? theme.focusedBorder() : theme.unfocusedBorder();

        String title = loading ? " Searching… " : " Pilot — Maven Central Search ";
        Block block = Block.builder()
                .title(title)
                .borderType(BorderType.ROUNDED)
                .borderStyle(borderStyle)
                .build();

        String text = searchBuffer.toString();
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" 🔍 ").bold());
        if (focus == Focus.SEARCH) {
            String before = text.substring(0, cursorPos);
            String cursorChar = cursorPos < text.length() ? String.valueOf(text.charAt(cursorPos)) : " ";
            String after = cursorPos < text.length() ? text.substring(cursorPos + 1) : "";
            spans.add(Span.raw(before));
            spans.add(Span.raw(cursorChar).reversed());
            spans.add(Span.raw(after));
        } else {
            spans.add(Span.raw(text).dim());
        }

        Paragraph searchLine = Paragraph.builder()
                .text(Text.from(Line.from(spans)))
                .block(block)
                .build();
        frame.renderWidget(searchLine, area);
    }

    /**
     * Render the search results area: either a centered empty-state message or a table of artifacts.
     *
     * <p>If there are no artifacts, displays a centered "Searching…" or "No results" message.
     * Otherwise renders a three-column table (groupId, artifactId, version) with the current
     * selection highlighted and the total/result count shown in the block title.
     *
     * @param frame the TUI frame to render widgets into
     * @param area the rectangular region within the frame reserved for the results table
     */
    private void renderResultsTable(Frame frame, Rect area) {
        Style borderStyle = focused
                ? (focus == Focus.TABLE ? theme.focusedBorder() : theme.unfocusedBorder())
                : theme.unfocusedBorder();

        String title;
        if (artifacts.isEmpty()) {
            title = " Results ";
        } else {
            title = " Results (" + artifacts.size() + " of " + totalFound + ") ";
        }

        Block block = Block.builder()
                .title(title)
                .borderType(BorderType.ROUNDED)
                .borderStyle(borderStyle)
                .build();

        if (artifacts.isEmpty()) {
            String msg = loading ? "Searching…" : "No results";
            Paragraph empty =
                    Paragraph.builder().text(msg).block(block).centered().build();
            frame.renderWidget(empty, area);
            return;
        }

        Row header = sortState.decorateHeader(
                List.of("groupId", "artifactId", "version"),
                Style.create().bold().yellow());

        List<Row> rows = new ArrayList<>();
        int selected = selectedIndex();
        for (int i = 0; i < artifacts.size(); i++) {
            String[] a = artifacts.get(i);
            String version = getDisplayVersion(i, i == selected);
            rows.add(Row.from(a[0], a[1], version));
        }

        Table table = Table.builder()
                .header(header)
                .rows(rows)
                .widths(Constraint.percentage(45), Constraint.percentage(30), Constraint.percentage(25))
                .highlightStyle(Style.create().reversed().bold())
                .highlightSymbol("▸ ") // triangular bullet
                .block(block)
                .build();

        lastTableArea = area;
        frame.renderStatefulWidget(table, area, tableState);
    }

    private void renderInfoBar(Frame frame, Rect area) {
        List<Rect> rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.length(1), Constraint.length(1))
                .split(area);

        // rows.get(0) is an empty spacer line
        renderArtifactDetails(frame, rows.get(1));
        renderSyntaxHelp(frame, rows.get(2));
        renderKeyBindings(frame, rows.get(3));
    }

    private void renderArtifactDetails(Frame frame, Rect area) {
        List<Span> spans = new ArrayList<>();
        int sel = selectedIndex();
        if (!artifacts.isEmpty() && sel >= 0) {
            String[] a = artifacts.get(sel);
            String version = getDisplayVersionPlain(sel);
            String pomKey = a[0] + ":" + a[1] + ":" + version;
            PomInfo info = pomInfoCache.get(pomKey);

            if (info != null) {
                // Name or artifactId
                String displayName = (info.name != null && !info.name.isEmpty()) ? info.name : a[1];
                spans.add(Span.raw(" "));
                spans.add(Span.raw(displayName).bold().cyan());
                // Organization
                if (info.organization != null && !info.organization.isEmpty()) {
                    spans.add(Span.raw(" by ").fg(theme.separatorColor()));
                    spans.add(Span.raw(info.organization).dim());
                }
                // License
                if (info.license != null && !info.license.isEmpty()) {
                    spans.add(Span.raw(" │ ").fg(theme.separatorColor()));
                    spans.add(Span.raw(info.license).fg(theme.metadataValueColor()));
                }
                // URL
                if (info.url != null && !info.url.isEmpty()) {
                    spans.add(Span.raw(" │ ").fg(theme.separatorColor()));
                    spans.add(Span.raw(info.url).dim());
                }
                // Publication date from Last-Modified header
                if (info.date != null && !info.date.isEmpty()) {
                    spans.add(Span.raw(" │ ").fg(theme.separatorColor()));
                    spans.add(Span.raw(info.date).dim());
                }
            } else {
                spans.add(Span.raw(" "));
                spans.add(Span.raw(a[0] + ":" + a[1]).cyan());
                if (pomInfoCache.containsKey(pomKey + ":loading")) {
                    spans.add(Span.raw(" │ ").fg(theme.separatorColor()));
                    spans.add(Span.raw("loading…").dim());
                }
            }
        } else {
            spans.add(Span.raw(" " + status).fg(theme.standaloneStatusColor()));
        }
        Paragraph line = Paragraph.from(Line.from(spans));
        frame.renderWidget(line, area);
    }

    private void renderSyntaxHelp(Frame frame, Rect area) {
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" Syntax: ").fg(theme.separatorColor()));
        spans.add(Span.raw("free text").dim());
        spans.add(Span.raw(" | ").fg(theme.separatorColor()));
        spans.add(Span.raw("g:").bold().dim());
        spans.add(Span.raw("groupId").dim());
        spans.add(Span.raw(" | ").fg(theme.separatorColor()));
        spans.add(Span.raw("a:").bold().dim());
        spans.add(Span.raw("artifactId").dim());
        spans.add(Span.raw(" | ").fg(theme.separatorColor()));
        spans.add(Span.raw("g:").bold().dim());
        spans.add(Span.raw("... ").dim());
        spans.add(Span.raw("AND").bold().dim());
        spans.add(Span.raw(" a:").bold().dim());
        spans.add(Span.raw("...").dim());
        Paragraph line = Paragraph.from(Line.from(spans));
        frame.renderWidget(line, area);
    }

    private void renderKeyBindings(Frame frame, Rect area) {
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" "));
        if (focus == Focus.SEARCH) {
            spans.add(Span.raw("↑↓").bold());
            spans.add(Span.raw(":Results  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Quit  "));
        } else {
            spans.add(Span.raw("↑↓").bold());
            spans.add(Span.raw(":Navigate  "));
            spans.add(Span.raw("PgUp/PgDn").bold());
            spans.add(Span.raw(":Page  "));
            spans.add(Span.raw("Home/End").bold());
            spans.add(Span.raw(":Top/Bottom  "));
            spans.add(Span.raw("←→").bold());
            spans.add(Span.raw(":Version  "));
            spans.add(Span.raw("Enter").bold());
            spans.add(Span.raw(":Select  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Back  "));
            spans.add(Span.raw("q").bold());
            spans.add(Span.raw(":Quit"));
        }
        Paragraph line = Paragraph.from(Line.from(spans));
        frame.renderWidget(line, area);
    }

    // -- Sorting ------------------------------------------------------------

    private List<Function<String[], String>> sortExtractors() {
        List<Function<String[], String>> extractors = new ArrayList<>();
        extractors.add(a -> a[0]); // groupId
        extractors.add(a -> a[1]); // artifactId
        extractors.add(a -> a[2]); // version
        return extractors;
    }

    @Override
    protected void onSortChanged() {
        sortState.sort(artifacts, sortExtractors());
    }

    // -- Version display ----------------------------------------------------

    private String getDisplayVersion(int rowIndex, boolean isSelected) {
        String[] a = artifacts.get(rowIndex);
        String key = a[0] + ":" + a[1];
        List<String> versions = versionCache.get(key);

        if (versions == null || versions.isEmpty()) {
            return "  " + a[2];
        }

        int idx = versionIndices[rowIndex];
        String v = versions.get(idx);

        if (!isSelected) {
            return "  " + v;
        }

        boolean hasLeft = idx > 0;
        boolean hasRight = idx < versions.size() - 1;
        return (hasLeft ? "◀ " : "  ") + v + (hasRight ? " ▶" : "");
    }

    private String getDisplayVersionPlain(int rowIndex) {
        String[] a = artifacts.get(rowIndex);
        String key = a[0] + ":" + a[1];
        List<String> versions = versionCache.get(key);
        if (versions != null && !versions.isEmpty()) {
            return versions.get(versionIndices[rowIndex]);
        }
        return a[2];
    }

    // -- Search -------------------------------------------------------------

    private void triggerSearch() {
        String q = searchBuffer.toString().trim();
        if (q.isEmpty() || q.equals(lastSearchedQuery)) {
            return;
        }

        loading = true;
        prefetchingMore = false;
        lastSearchedQuery = q;
        final int gen = ++searchGeneration;
        final String searchQuery = q;
        status = "Searching…";

        CompletableFuture.supplyAsync(() -> {
                    try {
                        return client.query(searchQuery, 100, 0);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .thenAccept(response -> runner.runOnRenderThread(() -> {
                    if (gen != searchGeneration) {
                        return; // stale
                    }
                    loading = false;
                    if (response == null) {
                        status = "Search failed";
                        return;
                    }
                    JsonObject responseBody = response.getJsonObject("response");
                    totalFound = responseBody.getInt("numFound");
                    artifacts = extractArtifacts(responseBody);
                    versionIndices = new int[artifacts.size()];
                    versionCache.clear();
                    sortState.reset();

                    if (artifacts.isEmpty()) {
                        status = "No results";
                        tableState.clearSelection();
                    } else {
                        status = totalFound + " result(s) found";
                        tableState.select(0);
                        prefetchVersions(gen);
                        if (artifacts.size() < totalFound) {
                            prefetchMoreResults();
                        }
                    }
                }));
    }

    private void prefetchVersions(int gen) {
        for (int i = 0; i < artifacts.size(); i++) {
            String[] a = artifacts.get(i);
            String key = a[0] + ":" + a[1];
            if (versionCache.containsKey(key)) {
                continue;
            }
            final String g = a[0];
            final String artId = a[1];
            CompletableFuture.supplyAsync(() -> fetchVersionsFromMetadata(g, artId), httpPool)
                    .thenAccept(vers -> runner.runOnRenderThread(() -> {
                        if (gen != searchGeneration) {
                            return; // stale
                        }
                        String k = g + ":" + artId;
                        if (!versionCache.containsKey(k)) {
                            versionCache.put(k, vers.isEmpty() ? Collections.singletonList("") : vers);
                        }
                    }));
        }
    }

    /**
     * Appends more results to the artifacts list. Called both from the sync
     * fallback (on the render thread) and the async prefetch callback.
     * The Sonatype API's core=ga mode does not support start-based pagination
     * (start > 0 returns 0 docs), so we re-query with a larger rows value.
     */
    private void appendResults(JsonObject response, int expectedPreviousSize) {
        JsonObject responseBody = response.getJsonObject("response");
        totalFound = responseBody.getInt("numFound");
        List<String[]> all = extractArtifacts(responseBody);
        if (all.size() <= expectedPreviousSize) {
            totalFound = artifacts.size();
            return;
        }
        List<String[]> more = new ArrayList<>(all.subList(expectedPreviousSize, all.size()));
        artifacts.addAll(more);
        int[] newIndices = new int[artifacts.size()];
        System.arraycopy(versionIndices, 0, newIndices, 0, versionIndices.length);
        versionIndices = newIndices;
        status = artifacts.size() + " of " + totalFound + " result(s)";
        prefetchVersions(searchGeneration);
    }

    /**
     * Fetches the next page synchronously. Used as fallback when the user
     * reaches the bottom before the async prefetch has completed.
     */
    private void fetchMoreResultsSync() {
        if (lastSearchedQuery.isEmpty()) {
            return;
        }
        int currentSize = artifacts.size();
        try {
            JsonObject response = client.query(lastSearchedQuery, currentSize + 1000, 0);
            appendResults(response, currentSize);
            if (artifacts.size() > currentSize) {
                tableState.select(currentSize);
            }
        } catch (Exception e) {
            status = "Failed to load more";
        }
    }

    /**
     * Prefetches the next page asynchronously in the background.
     * Results are silently appended so the user can keep scrolling.
     */
    private void prefetchMoreResults() {
        if (prefetchingMore || lastSearchedQuery.isEmpty()) {
            return;
        }
        prefetchingMore = true;
        final int gen = searchGeneration;
        final String searchQuery = lastSearchedQuery;
        final int currentSize = artifacts.size();

        CompletableFuture.supplyAsync(() -> {
                    try {
                        return client.query(searchQuery, currentSize + 1000, 0);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .thenAccept(response -> runner.runOnRenderThread(() -> {
                    prefetchingMore = false;
                    if (gen != searchGeneration || response == null) {
                        return;
                    }
                    appendResults(response, currentSize);
                }));
    }

    // -- Version cycling ----------------------------------------------------

    private void cycleVersion(int rowIndex, int direction) {
        String[] a = artifacts.get(rowIndex);
        String key = a[0] + ":" + a[1];

        List<String> versions = versionCache.get(key);
        if (versions == null) {
            status = "Loading versions…";
            final int dir = direction;
            CompletableFuture.supplyAsync(() -> fetchVersionsFromMetadata(a[0], a[1]), httpPool)
                    .thenAccept(vers -> runner.runOnRenderThread(() -> {
                        if (vers.isEmpty()) {
                            List<String> fallback = new ArrayList<>();
                            fallback.add(a[2]);
                            versionCache.put(key, fallback);
                        } else {
                            versionCache.put(key, vers);
                        }
                        int idx = versionIndices[rowIndex];
                        int newIdx = idx + dir;
                        List<String> cached = versionCache.get(key);
                        if (newIdx >= 0 && newIdx < cached.size()) {
                            versionIndices[rowIndex] = newIdx;
                        }
                        status = cached.size() + " version(s) available";
                    }));
            return;
        }

        int idx = versionIndices[rowIndex];
        int newIdx = idx + direction;
        if (newIdx >= 0 && newIdx < versions.size()) {
            versionIndices[rowIndex] = newIdx;
            fetchPomInfoIfNeeded();
        }
    }

    // -- POM info fetching --------------------------------------------------

    private void fetchPomInfoIfNeeded() {
        int sel = selectedIndex();
        if (artifacts.isEmpty() || sel < 0) {
            return;
        }
        String[] a = artifacts.get(sel);
        String version = getDisplayVersionPlain(sel);
        String pomKey = a[0] + ":" + a[1] + ":" + version;
        if (pomInfoCache.containsKey(pomKey)) {
            return; // already cached
        }
        String loadingKey = pomKey + ":loading";
        if (pomInfoCache.containsKey(loadingKey)) {
            return; // already loading
        }
        pomInfoCache.put(loadingKey, null); // mark as loading

        final String g = a[0];
        final String artId = a[1];
        final String ver = version;
        CompletableFuture.supplyAsync(() -> fetchPomFromCentral(g, artId, ver), httpPool)
                .thenAccept(info -> runner.runOnRenderThread(() -> {
                    pomInfoCache.remove(loadingKey);
                    pomInfoCache.put(pomKey, info);
                }));
    }

    /**
     * Fetches a POM file from Maven Central for the given coordinates and returns extracted metadata.
     *
     * The returned PomInfo holds the POM's name, description, URL, organization name, license name,
     * and publication date (ISO YYYY-MM-DD) when available.
     *
     * @return a PomInfo containing `name`, `description`, `url`, `organization`, `license`, and publication date;
     *         returns a PomInfo with all fields set to `null` if the POM cannot be retrieved or parsed
     */
    static PomInfo fetchPomFromCentral(String groupId, String artifactId, String version) {
        // Try local Maven repository first (handles SNAPSHOTs and offline artifacts)
        PomInfo local = fetchPomFromLocal(groupId, artifactId, version);
        if (local != null) return local;

        // Fall back to Maven Central
        try {
            String path = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-"
                    + version + ".pom";
            String pomUrl = "https://repo1.maven.org/maven2/" + path;

            HttpURLConnection conn =
                    (HttpURLConnection) URI.create(pomUrl).toURL().openConnection();
            try {
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5_000);
                conn.setReadTimeout(5_000);

                if (conn.getResponseCode() != 200) {
                    return new PomInfo(null, null, null, null, null, null, null);
                }

                // Capture Last-Modified as publication date
                String date = null;
                long lastModified = conn.getLastModified();
                if (lastModified > 0) {
                    date = Instant.ofEpochMilli(lastModified)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                            .toString();
                }

                try (InputStream is = conn.getInputStream()) {
                    return parsePomInfo(is, date);
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return new PomInfo(null, null, null, null, null, null, null);
        }
    }

    private static PomInfo fetchPomFromLocal(String groupId, String artifactId, String version) {
        try {
            String localRepo = System.getProperty("maven.repo.local");
            if (localRepo == null) {
                localRepo = System.getProperty("user.home") + "/.m2/repository";
            }
            Path pomFile = Path.of(
                    localRepo, groupId.replace('.', '/'), artifactId, version, artifactId + "-" + version + ".pom");
            if (!pomFile.toFile().isFile()) return null;

            String date = Instant.ofEpochMilli(pomFile.toFile().lastModified())
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .toString();

            try (InputStream is = java.nio.file.Files.newInputStream(pomFile)) {
                return parsePomInfo(is, date);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private record LicenseInfo(String name, String url) {}

    private static PomInfo parsePomInfo(InputStream is, String date) throws Exception {
        DocumentBuilder db = createSafeDocumentBuilder();
        Document doc = db.parse(is);
        Element root = doc.getDocumentElement();

        String name = getChildText(root, "name");
        String description = getChildText(root, "description");
        String url = getChildText(root, "url");

        String org = null;
        Element orgEl = getChildElement(root, "organization");
        if (orgEl != null) {
            org = getChildText(orgEl, "name");
        }

        LicenseInfo licInfo = extractLicenseInfo(root);

        // If no license found, follow parent POM chain
        if (licInfo == null) {
            licInfo = fetchLicenseInfoFromParent(root);
        }

        String license = licInfo != null ? licInfo.name : null;
        String licenseUrl = licInfo != null ? licInfo.url : null;

        return new PomInfo(name, description, url, org, license, licenseUrl, date);
    }

    /**
     * Follow the parent POM chain to find a license declaration.
     * Checks local Maven repository first, then Maven Central.
     */
    private static LicenseInfo fetchLicenseInfoFromParent(Element pomRoot) {
        Element parentEl = getChildElement(pomRoot, "parent");
        if (parentEl == null) return null;
        String pGroupId = getChildText(parentEl, "groupId");
        String pArtifactId = getChildText(parentEl, "artifactId");
        String pVersion = getChildText(parentEl, "version");
        if (pGroupId == null || pArtifactId == null || pVersion == null) return null;

        Element parentRoot = loadPomElement(pGroupId, pArtifactId, pVersion);
        if (parentRoot == null) return null;

        LicenseInfo licInfo = extractLicenseInfo(parentRoot);
        if (licInfo != null) return licInfo;
        return fetchLicenseInfoFromParent(parentRoot);
    }

    /**
     * Load a POM's root element, trying local repo first then Central.
     */
    private static Element loadPomElement(String groupId, String artifactId, String version) {
        String relPath = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version
                + ".pom";

        // Try local repo first
        try {
            String localRepo = System.getProperty("maven.repo.local");
            if (localRepo == null) {
                localRepo = System.getProperty("user.home") + "/.m2/repository";
            }
            Path pomFile = Path.of(
                    localRepo, groupId.replace('.', '/'), artifactId, version, artifactId + "-" + version + ".pom");
            if (pomFile.toFile().isFile()) {
                try (InputStream is = java.nio.file.Files.newInputStream(pomFile)) {
                    return createSafeDocumentBuilder().parse(is).getDocumentElement();
                }
            }
        } catch (Exception e) {
            // fall through to Central
        }

        // Try Maven Central
        try {
            String pomUrl = "https://repo1.maven.org/maven2/" + relPath;
            HttpURLConnection conn =
                    (HttpURLConnection) URI.create(pomUrl).toURL().openConnection();
            try {
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5_000);
                conn.setReadTimeout(5_000);
                if (conn.getResponseCode() != 200) return null;
                try (InputStream is = conn.getInputStream()) {
                    return createSafeDocumentBuilder().parse(is).getDocumentElement();
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extract license name and URL from a POM root element using direct child traversal.
     */
    private static LicenseInfo extractLicenseInfo(Element pomRoot) {
        Element licensesEl = getChildElement(pomRoot, "licenses");
        if (licensesEl == null) return null;
        Element licenseEl = getChildElement(licensesEl, "license");
        if (licenseEl == null) return null;
        String name = getChildText(licenseEl, "name");
        if (name == null) return null;
        String url = getChildText(licenseEl, "url");
        return new LicenseInfo(name, url);
    }

    /**
     * Find a direct child element by tag name.
     */
    private static Element getChildElement(Element parent, String tagName) {
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            if (list.item(i) instanceof Element el && el.getTagName().equals(tagName)) {
                return el;
            }
        }
        return null;
    }

    private static DocumentBuilder createSafeDocumentBuilder() throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return dbf.newDocumentBuilder();
    }

    private static String getChildText(Element parent, String tagName) {
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            if (list.item(i) instanceof Element el) {
                if (el.getTagName().equals(tagName)) {
                    String text = el.getTextContent();
                    return (text != null && !text.trim().isEmpty()) ? text.trim() : null;
                }
            }
        }
        return null;
    }

    // -- Selection ----------------------------------------------------------

    private void selectArtifact(int rowIndex) {
        String[] a = artifacts.get(rowIndex);
        String key = a[0] + ":" + a[1];

        List<String> versions = versionCache.get(key);
        String version;
        if (versions != null && !versions.isEmpty()) {
            version = versions.get(versionIndices[rowIndex]);
        } else {
            version = a[2];
        }

        selectedGav = a[0] + ":" + a[1] + ":" + version;
        runner.quit();
    }

    // -- JSON helpers -------------------------------------------------------

    public static List<String[]> extractArtifacts(JsonObject responseBody) {
        List<String[]> results = new ArrayList<>();
        JsonArray docs = responseBody.getJsonArray("docs");
        if (docs == null) {
            return results;
        }
        for (int i = 0; i < docs.size(); i++) {
            JsonObject doc = docs.getJsonObject(i);
            String g = doc.getString("g", "");
            String a = doc.getString("a", "");
            String v = doc.containsKey("latestVersion") ? doc.getString("latestVersion", "") : doc.getString("v", "");
            String p = doc.getString("p", "");
            String vc = doc.containsKey("versionCount") ? String.valueOf(doc.getInt("versionCount")) : "";
            String ts = "";
            if (doc.containsKey("timestamp")) {
                long millis = doc.getJsonNumber("timestamp").longValue();
                ts = Instant.ofEpochMilli(millis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                        .toString();
            }
            if (!g.isEmpty() && !a.isEmpty()) {
                results.add(new String[] {g, a, v, p, vc, ts});
            }
        }
        return results;
    }

    /**
     * Obtains artifact versions from Maven Central's maven-metadata.xml.
     *
     * @param groupId    the artifact's groupId (dot-separated)
     * @param artifactId the artifact's artifactId
     * @return a list of version strings with the newest versions first; may be empty if none are found or on error
     */
    static List<String> fetchVersionsFromMetadata(String groupId, String artifactId) {
        List<String> versions = new ArrayList<>();
        try {
            String path = groupId.replace('.', '/') + "/" + artifactId + "/maven-metadata.xml";
            String metaUrl = "https://repo1.maven.org/maven2/" + path;

            HttpURLConnection conn =
                    (HttpURLConnection) URI.create(metaUrl).toURL().openConnection();
            try {
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5_000);
                conn.setReadTimeout(5_000);

                if (conn.getResponseCode() != 200) {
                    return versions;
                }

                try (InputStream is = conn.getInputStream()) {
                    DocumentBuilder db = createSafeDocumentBuilder();
                    Document doc = db.parse(is);

                    NodeList versionNodes = doc.getElementsByTagName("version");
                    for (int i = 0; i < versionNodes.getLength(); i++) {
                        String v = versionNodes.item(i).getTextContent();
                        if (v != null && !v.trim().isEmpty()) {
                            versions.add(v.trim());
                        }
                    }
                }

                // Reverse so newest versions come first
                Collections.reverse(versions);
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            // return whatever we have
        }
        return versions;
    }
}
