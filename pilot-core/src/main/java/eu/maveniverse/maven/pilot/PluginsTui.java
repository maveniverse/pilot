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
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Interactive TUI for browsing Maven plugins and their available version updates.
 */
public class PluginsTui extends ToolPanel {

    static class PluginEntry {
        final String groupId;
        final String artifactId;
        String version;
        final List<String> modules = new ArrayList<>();
        String newestVersion;
        VersionComparator.UpdateType updateType;
        LocalDate currentReleaseDate;
        LocalDate newestReleaseDate;
        float libYears = -1;
        boolean managed;

        PluginEntry(String groupId, String artifactId, String version, boolean managed) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version != null ? version : "";
            this.managed = managed;
        }

        String ga() {
            return groupId + ":" + artifactId;
        }

        String gav() {
            return groupId + ":" + artifactId + ":" + version;
        }

        boolean hasUpdate() {
            return newestVersion != null && !newestVersion.isEmpty() && !newestVersion.equals(version);
        }
    }

    private enum View {
        PLUGINS,
        MANAGED,
        UPDATES
    }

    private enum Filter {
        ALL,
        PATCH,
        MINOR,
        MAJOR
    }

    private static final String KEY_NAV = ":Nav  ";
    private static final String CHECKING_VERSIONS = "Checking versions\u2026";

    private final List<PluginEntry> plugins;
    private final List<PluginEntry> managed;
    private final List<PluginEntry> updates = new ArrayList<>();
    private final boolean singleModule;
    private final UpdatesTui.VersionResolver versionResolver;
    private final ExecutorService httpPool = PilotUtil.newHttpPool();
    private final TableState tableState = new TableState();
    private final TableState detailTableState = new TableState();

    private View view = View.PLUGINS;
    private Filter filter = Filter.ALL;
    private String statusText = "Loading updates\u2026";
    boolean loading = true;
    int loadedCount;
    int failedCount;
    int dateFetchesPending;
    boolean datesLoading;
    private int lastContentHeight;

    public PluginsTui(
            PilotProject project, List<PilotProject> allProjects, UpdatesTui.VersionResolver versionResolver) {
        this.versionResolver = versionResolver;
        this.singleModule = allProjects.size() <= 1;

        Map<String, PluginEntry> pluginsMap = new LinkedHashMap<>();
        Map<String, PluginEntry> managedMap = new LinkedHashMap<>();

        List<PilotProject> projects = allProjects.isEmpty() ? List.of(project) : allProjects;
        for (PilotProject p : projects) {
            collectPlugins(p, pluginsMap, managedMap);
        }

        this.plugins = new ArrayList<>(pluginsMap.values());
        this.managed = new ArrayList<>(managedMap.values());
        this.sortState = new SortState(4);

        if (!this.plugins.isEmpty()) {
            tableState.select(0);
        }
    }

    private static void collectPlugins(
            PilotProject p, Map<String, PluginEntry> pluginsMap, Map<String, PluginEntry> managedMap) {
        String moduleName = p.artifactId;
        for (PilotProject.Plugin plugin : p.plugins) {
            PluginEntry entry = pluginsMap.computeIfAbsent(
                    plugin.ga(), k -> new PluginEntry(plugin.groupId(), plugin.artifactId(), plugin.version(), false));
            mergeModule(entry, moduleName, plugin.version());
        }
        for (PilotProject.Plugin plugin : p.managedPlugins) {
            PluginEntry entry = managedMap.computeIfAbsent(
                    plugin.ga(), k -> new PluginEntry(plugin.groupId(), plugin.artifactId(), plugin.version(), true));
            mergeModule(entry, moduleName, plugin.version());
        }
    }

    private static void mergeModule(PluginEntry entry, String moduleName, String version) {
        if (!entry.modules.contains(moduleName)) {
            entry.modules.add(moduleName);
        }
        if (entry.version.isEmpty() && version != null) {
            entry.version = version;
        }
    }

    // -- Version resolution --

    private void fetchAllUpdates() {
        List<PluginEntry> all = new ArrayList<>();
        all.addAll(plugins);
        all.addAll(managed);

        // Deduplicate by GA
        Map<String, PluginEntry> deduped = new LinkedHashMap<>();
        for (PluginEntry e : all) {
            deduped.putIfAbsent(e.ga(), e);
        }
        List<PluginEntry> toResolve = new ArrayList<>(deduped.values());

        if (toResolve.isEmpty()) {
            loading = false;
            statusText = buildStatusMessage();
            return;
        }

        for (PluginEntry entry : toResolve) {
            CompletableFuture.supplyAsync(
                            () -> versionResolver.resolveVersions(entry.groupId, entry.artifactId), httpPool)
                    .thenAccept(versions -> runner.runOnRenderThread(() -> {
                        applyVersionResult(entry, versions);
                        loadedCount++;
                        if (loadedCount >= toResolve.size()) {
                            onVersionsComplete(toResolve);
                        }
                    }))
                    .exceptionally(ex -> {
                        runner.runOnRenderThread(() -> {
                            loadedCount++;
                            failedCount++;
                            if (loadedCount >= toResolve.size()) {
                                onVersionsComplete(toResolve);
                            }
                        });
                        return null;
                    });
        }
    }

    private void applyVersionResult(PluginEntry entry, List<String> versions) {
        versions.stream()
                .filter(v -> !VersionComparator.isPreview(v))
                .filter(v -> entry.version.isEmpty() || VersionComparator.isNewer(entry.version, v))
                .findFirst()
                .ifPresent(v -> {
                    entry.newestVersion = v;
                    entry.updateType = VersionComparator.classify(entry.version, v);
                });
    }

    private void onVersionsComplete(List<PluginEntry> resolved) {
        loading = false;
        updates.clear();
        for (PluginEntry entry : resolved) {
            if (entry.hasUpdate()) {
                updates.add(entry);
            }
        }
        applyFilter();
        statusText = buildStatusMessage();
        fetchReleaseDates(resolved);
    }

    private void applyFilter() {
        // updates list is rebuilt when filter changes
        List<PluginEntry> all = new ArrayList<>();
        all.addAll(plugins);
        all.addAll(managed);
        Map<String, PluginEntry> deduped = new LinkedHashMap<>();
        for (PluginEntry e : all) {
            deduped.putIfAbsent(e.ga(), e);
        }
        updates.clear();
        for (PluginEntry entry : deduped.values()) {
            if (!entry.hasUpdate()) continue;
            boolean show =
                    switch (filter) {
                        case ALL -> true;
                        case PATCH -> entry.updateType == VersionComparator.UpdateType.PATCH;
                        case MINOR -> entry.updateType == VersionComparator.UpdateType.MINOR;
                        case MAJOR -> entry.updateType == VersionComparator.UpdateType.MAJOR;
                    };
            if (show) {
                updates.add(entry);
            }
        }
        if (view == View.UPDATES) {
            onSortChanged();
        }
    }

    private void fetchReleaseDates(List<PluginEntry> entries) {
        int count = 0;
        for (PluginEntry e : entries) {
            if (e.hasUpdate()) count += 2;
        }
        dateFetchesPending = count;
        if (dateFetchesPending == 0) return;
        datesLoading = true;

        for (PluginEntry entry : entries) {
            if (entry.hasUpdate()) {
                fetchEntryDates(entry);
            }
        }
    }

    private void fetchEntryDates(PluginEntry entry) {
        fetchDate(entry.groupId, entry.artifactId, entry.version, date -> {
            entry.currentReleaseDate = date;
            computeLibYear(entry);
        });
        fetchDate(entry.groupId, entry.artifactId, entry.newestVersion, date -> {
            entry.newestReleaseDate = date;
            computeLibYear(entry);
        });
    }

    private void fetchDate(String groupId, String artifactId, String version, Consumer<LocalDate> onDate) {
        CompletableFuture.supplyAsync(() -> MavenCentralClient.fetchReleaseDate(groupId, artifactId, version), httpPool)
                .thenAccept(date -> runner.runOnRenderThread(() -> {
                    onDate.accept(date);
                    if (--dateFetchesPending <= 0) onDatesComplete();
                }))
                .exceptionally(ex -> {
                    runner.runOnRenderThread(() -> {
                        if (--dateFetchesPending <= 0) onDatesComplete();
                    });
                    return null;
                });
    }

    private void computeLibYear(PluginEntry entry) {
        if (entry.currentReleaseDate != null && entry.newestReleaseDate != null) {
            long weeks = ChronoUnit.WEEKS.between(entry.currentReleaseDate, entry.newestReleaseDate);
            entry.libYears = Math.max(0, weeks / 52.0f);
        }
    }

    private void onDatesComplete() {
        datesLoading = false;
        statusText = buildStatusMessage();
        onSortChanged();
    }

    private String buildStatusMessage() {
        int updateCount = updates.size();
        String msg = singleModule
                ? updateCount + " plugin update(s) available"
                : updateCount + " plugin update(s) available across modules";
        if (failedCount > 0) {
            msg += "; " + failedCount + " lookup(s) failed";
        }
        if (!datesLoading) {
            float total = totalLibYears();
            if (total > 0) {
                int tenths = Math.round(total * 10);
                msg += " \u2014 " + (tenths / 10) + "." + (tenths % 10) + " libyear(s) behind";
            }
        }
        return msg;
    }

    private float totalLibYears() {
        float total = 0;
        for (PluginEntry e : updates) {
            if (e.libYears >= 0) total += e.libYears;
        }
        return total;
    }

    private String formatAge(PluginEntry entry) {
        if (!entry.hasUpdate()) return "";
        if (entry.libYears < 0) return datesLoading ? "\u2026" : "";
        int tenths = Math.round(entry.libYears * 10);
        return (tenths / 10) + "." + (tenths % 10) + "y";
    }

    private static String updateTypeLabel(VersionComparator.UpdateType type) {
        if (type == null) return "";
        return switch (type) {
            case PATCH -> "patch";
            case MINOR -> "minor";
            case MAJOR -> "major";
        };
    }

    private Style updateTypeStyle(VersionComparator.UpdateType type) {
        if (type == null) return Style.create().dim();
        return switch (type) {
            case PATCH -> Style.create().dim();
            case MINOR -> Style.create();
            case MAJOR -> Style.create().fg(Color.YELLOW);
        };
    }

    // -- Sorting --

    private List<Function<PluginEntry, String>> updatesSortExtractors() {
        List<Function<PluginEntry, String>> extractors = new ArrayList<>();
        extractors.add(this::extractUpdateIcon);
        extractors.add(PluginEntry::ga);
        extractors.add(e -> e.version);
        extractors.add(e -> e.newestVersion != null ? e.newestVersion : "");
        extractors.add(this::extractAge);
        if (!singleModule) {
            extractors.add(e -> String.valueOf(e.modules.size()));
        }
        return extractors;
    }

    private String extractUpdateIcon(PluginEntry e) {
        return e.updateType != null ? e.updateType.name() : "";
    }

    private String extractAge(PluginEntry e) {
        if (e.libYears < 0) return "";
        return String.format(java.util.Locale.US, "%010.3f", e.libYears);
    }

    @Override
    protected void onSortChanged() {
        if (view == View.UPDATES && sortState != null) {
            sortState.sort(updates, updatesSortExtractors());
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
        List<PluginEntry> list = currentList();
        for (int i = 0; i < list.size(); i++) {
            PluginEntry e = list.get(i);
            String searchable = e.groupId + ":" + e.artifactId + ":" + e.version;
            if (e.newestVersion != null) searchable += ":" + e.newestVersion;
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
    }

    private List<PluginEntry> currentList() {
        return switch (view) {
            case PLUGINS -> plugins;
            case MANAGED -> managed;
            case UPDATES -> updates;
        };
    }

    // -- Event handling --

    @Override
    public boolean handleKeyEvent(KeyEvent key) {
        if (handleSearchInput(key)) return true;
        if (handleSortInput(key)) return true;

        if (key.isUp()) {
            tableState.selectPrevious();
            return true;
        }
        if (key.isDown()) {
            tableState.selectNext(currentList().size());
            return true;
        }
        if (TableNavigation.handlePageKeys(key, tableState, currentList().size(), lastContentHeight)) {
            return true;
        }

        if (view == View.UPDATES) {
            if (key.isChar('f')) {
                filter = Filter.values()[(filter.ordinal() + 1) % Filter.values().length];
                applyFilter();
                return true;
            }
            if (key.isChar('F')) {
                int len = Filter.values().length;
                filter = Filter.values()[(filter.ordinal() - 1 + len) % len];
                applyFilter();
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean handleMouseEvent(MouseEvent mouse, Rect area) {
        if (handleMouseTabBar(mouse)) return true;
        if (view == View.UPDATES) {
            if (handleMouseSortHeader(mouse, updatesTableWidths())) return true;
        } else {
            if (handleMouseSortHeader(mouse, pluginTableWidths())) return true;
        }
        return handleMouseTableInteraction(mouse, currentList().size(), tableState);
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
        if (handleKeyEvent(key)) return true;
        if (key.isCharIgnoreCase('q') || key.isKey(KeyCode.ESCAPE)) {
            runner.quit();
            return true;
        }
        if (key.isCharIgnoreCase('h')) {
            helpOverlay.open(helpSections());
            return true;
        }
        return false;
    }

    // -- ToolPanel methods --

    @Override
    public String toolName() {
        return "Plugins";
    }

    @Override
    int subViewCount() {
        return View.values().length;
    }

    @Override
    int activeSubView() {
        return view.ordinal();
    }

    @Override
    void setActiveSubView(int index) {
        view = View.values()[index];
        tableState.select(0);
        clearSearch();
        int cols = view == View.UPDATES ? updatesColumnCount() : 3;
        sortState = new SortState(cols);
    }

    @Override
    List<String> subViewNames() {
        String updatesLabel = "Updates" + (loading ? "" : " (" + updates.size() + ")");
        return List.of("Plugins (" + plugins.size() + ")", "Managed (" + managed.size() + ")", updatesLabel);
    }

    @Override
    public String status() {
        String search = searchStatus();
        if (search != null) {
            return searchMode ? search : statusText + " \u2014 " + search;
        }
        return statusText;
    }

    @Override
    public List<Span> keyHints() {
        List<Span> searchHints = searchKeyHints();
        if (!searchHints.isEmpty()) {
            return searchHints;
        }
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw("\u2191\u2193").bold());
        spans.add(Span.raw(KEY_NAV));
        spans.addAll(sortKeyHints());
        spans.add(Span.raw("/").bold());
        spans.add(Span.raw(":Search  "));
        if (view == View.UPDATES) {
            spans.add(Span.raw("f").bold());
            spans.add(Span.raw(":Filter"));
        }
        return spans;
    }

    @Override
    public List<HelpOverlay.Section> helpSections() {
        return List.of(
                new HelpOverlay.Section(
                        "Plugin Browser",
                        List.of(
                                new HelpOverlay.Entry("", "Browse Maven plugins declared and managed in the reactor."),
                                new HelpOverlay.Entry("", "Updates tab shows plugins with newer versions available."))),
                new HelpOverlay.Section(
                        "Colors",
                        List.of(
                                new HelpOverlay.Entry("dim", "Patch update"),
                                new HelpOverlay.Entry("white", "Minor update"),
                                new HelpOverlay.Entry("yellow", "Major update"))),
                new HelpOverlay.Section(
                        "Actions",
                        List.of(
                                new HelpOverlay.Entry("\u2191 / \u2193", "Move selection up / down"),
                                new HelpOverlay.Entry("PgUp / PgDn", "Move selection up / down by one page"),
                                new HelpOverlay.Entry("Home / End", "Jump to first / last row"),
                                new HelpOverlay.Entry("1-3", "Switch Plugins / Managed / Updates view"),
                                new HelpOverlay.Entry(
                                        "f / F",
                                        "Cycle filter: all \u2192 patch \u2192 minor \u2192 major (Updates tab)"),
                                new HelpOverlay.Entry("s / S", "Sort by column / reverse direction"),
                                new HelpOverlay.Entry("/", "Search / filter"),
                                new HelpOverlay.Entry("n / N", "Next / previous search match"))));
    }

    @Override
    public void render(Frame frame, Rect area) {
        Rect contentArea = renderTabBar(frame, area);
        lastContentHeight = contentArea.height();
        if (currentList().isEmpty() && !loading) {
            renderEmptyPlaceholder(frame, contentArea);
            return;
        }
        if (view == View.UPDATES && !loading) {
            var split = Layout.vertical()
                    .constraints(Constraint.fill(), Constraint.length(1), Constraint.percentage(25))
                    .split(contentArea);
            renderUpdatesTable(frame, split.get(0));
            renderDivider(frame, split.get(1));
            renderDetailPane(frame, split.get(2));
        } else {
            var split = Layout.vertical()
                    .constraints(Constraint.fill(), Constraint.length(1), Constraint.percentage(25))
                    .split(contentArea);
            renderPluginsTable(frame, split.get(0));
            renderDivider(frame, split.get(1));
            renderDetailPane(frame, split.get(2));
        }
    }

    void renderStandalone(Frame frame) {
        var zones = Layout.vertical()
                .constraints(Constraint.length(3), Constraint.fill(), Constraint.length(3))
                .split(frame.area());
        renderStandaloneHeader(frame, zones.get(0), "Plugin Browser", buildStandaloneHeaderLine());
        lastContentHeight = zones.get(1).height();
        if (helpOverlay.isActive()) {
            helpOverlay.render(frame, zones.get(1));
        } else {
            render(frame, zones.get(1));
        }
        renderStandaloneInfoBar(frame, zones.get(2));
    }

    private Line buildStandaloneHeaderLine() {
        List<Span> spans = new ArrayList<>();
        if (loading) {
            spans.add(Span.raw("  Loading… (" + loadedCount + " done)").dim());
        } else if (filter != Filter.ALL && view == View.UPDATES) {
            spans.add(Span.raw(" [" + filter + "]").fg(Color.YELLOW));
        }
        return Line.from(spans);
    }

    private void renderEmptyPlaceholder(Frame frame, Rect area) {
        String msg =
                switch (view) {
                    case PLUGINS -> "No plugins declared";
                    case MANAGED -> "No managed plugins";
                    case UPDATES -> loading ? CHECKING_VERSIONS : "No plugin updates available";
                };
        Block block = Block.builder()
                .borderType(BorderType.ROUNDED)
                .borderStyle(borderStyle())
                .build();
        Paragraph para = Paragraph.builder().text(msg).block(block).centered().build();
        frame.renderWidget(para, area);
    }

    private void renderPluginsTable(Frame frame, Rect area) {
        Block block = Block.builder()
                .borderType(BorderType.ROUNDED)
                .borderStyle(borderStyle())
                .build();

        List<PluginEntry> list = currentList();

        if (list.isEmpty()) {
            renderLoadingPlaceholder(frame, area, block, loading ? CHECKING_VERSIONS : "No plugins", null);
            return;
        }

        List<String> headers = new ArrayList<>();
        headers.add("groupId:artifactId");
        headers.add("version");
        if (!singleModule) headers.add("modules");
        Row header = sortState.decorateHeader(headers, theme.tableHeader());

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            PluginEntry e = list.get(i);
            Style style = isSearchMatch(i) ? Style.create().bg(theme.searchHighlightBg()) : Style.create();
            if (!singleModule) {
                rows.add(Row.from(e.ga(), e.version, String.join(", ", e.modules))
                        .style(style));
            } else {
                rows.add(Row.from(e.ga(), e.version).style(style));
            }
        }

        Table table = Table.builder()
                .header(header)
                .rows(rows)
                .widths(pluginTableWidths())
                .highlightStyle(theme.highlightStyle())
                .highlightSymbol(theme.highlightSymbol())
                .block(block)
                .build();

        setTableArea(area, block);
        frame.renderStatefulWidget(table, area, tableState);
    }

    private List<Constraint> pluginTableWidths() {
        if (!singleModule) {
            return List.of(Constraint.percentage(45), Constraint.percentage(20), Constraint.percentage(35));
        }
        return List.of(Constraint.percentage(65), Constraint.percentage(35));
    }

    private void renderUpdatesTable(Frame frame, Rect area) {
        Block block = Block.builder()
                .borderType(BorderType.ROUNDED)
                .borderStyle(borderStyle())
                .build();

        if (updates.isEmpty()) {
            String msg = loading ? CHECKING_VERSIONS : "No plugin updates available";
            renderLoadingPlaceholder(frame, area, block, msg, null);
            return;
        }

        List<String> headers = new ArrayList<>();
        headers.add("");
        headers.add("groupId:artifactId");
        headers.add("current");
        headers.add("latest");
        headers.add("libyear");
        if (!singleModule) headers.add("modules");
        Row header = sortState.decorateHeader(headers, theme.tableHeader());

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < updates.size(); i++) {
            rows.add(createUpdateRow(updates.get(i), isSearchMatch(i)));
        }

        Table table = Table.builder()
                .header(header)
                .rows(rows)
                .widths(updatesTableWidths())
                .highlightStyle(theme.highlightStyle())
                .highlightSymbol("▸ ")
                .highlightSpacing(Table.HighlightSpacing.ALWAYS)
                .block(block)
                .build();

        setTableArea(area, block);
        frame.renderStatefulWidget(table, area, tableState);
    }

    private Row createUpdateRow(PluginEntry entry, boolean highlight) {
        String icon = updateTypeLabel(entry.updateType);
        String current = entry.version;
        String latest = entry.newestVersion != null ? entry.newestVersion : "";
        String age = formatAge(entry);
        Style style = updateTypeStyle(entry.updateType);
        if (highlight) style = style.bg(theme.searchHighlightBg());
        if (!singleModule) {
            return Row.from(icon, entry.ga(), current, latest, age, String.valueOf(entry.modules.size()) + " mod")
                    .style(style);
        }
        return Row.from(icon, entry.ga(), current, latest, age).style(style);
    }

    private List<Constraint> updatesTableWidths() {
        if (!singleModule) {
            return List.of(
                    Constraint.length(6),
                    Constraint.percentage(30),
                    Constraint.percentage(15),
                    Constraint.percentage(15),
                    Constraint.length(6),
                    Constraint.percentage(10));
        }
        return List.of(
                Constraint.length(6),
                Constraint.percentage(40),
                Constraint.percentage(20),
                Constraint.percentage(20),
                Constraint.length(6));
    }

    private int updatesColumnCount() {
        return singleModule ? 5 : 6;
    }

    private void renderDetailPane(Frame frame, Rect area) {
        List<PluginEntry> list = currentList();
        Integer sel = tableState.selected();
        if (sel == null || sel >= list.size()) return;

        PluginEntry entry = list.get(sel);
        String title = " " + entry.ga() + " \u2014 Details ";

        Block block = Block.builder()
                .title(title)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().cyan())
                .build();

        List<Row> rows = new ArrayList<>();
        rows.add(Row.from(Cell.from(Line.from(List.of(Span.raw("GAV:       ").bold(), Span.raw(entry.gav()))))));

        if (entry.managed) {
            rows.add(Row.from(Cell.from(Line.from(
                    List.of(Span.raw("Managed:   ").bold(), Span.raw("yes").dim())))));
        }

        if (view == View.UPDATES && entry.hasUpdate()) {
            addUpdateDetails(rows, entry);
        }

        addModuleDetails(rows, entry);

        Table table = Table.builder()
                .rows(rows)
                .widths(Constraint.fill())
                .block(block)
                .build();

        frame.renderStatefulWidget(table, area, detailTableState);
    }

    private void addUpdateDetails(List<Row> rows, PluginEntry entry) {
        rows.add(Row.from(Cell.from(Line.from(List.of(
                Span.raw("Update:    ").bold(),
                Span.raw(entry.version),
                Span.raw(" \u2192 ").dim(),
                Span.raw(entry.newestVersion).fg(Color.GREEN))))));
        String typeStr = updateTypeLabel(entry.updateType);
        if (!typeStr.isEmpty()) {
            rows.add(
                    Row.from(Cell.from(Line.from(List.of(Span.raw("Type:      ").bold(), Span.raw(typeStr))))));
        }
        String age = formatAge(entry);
        if (!age.isEmpty()) {
            rows.add(
                    Row.from(Cell.from(Line.from(List.of(Span.raw("LibYear:   ").bold(), Span.raw(age))))));
        }
    }

    private void addModuleDetails(List<Row> rows, PluginEntry entry) {
        if (!entry.modules.isEmpty() && !singleModule) {
            List<Span> modSpans = new ArrayList<>();
            modSpans.add(Span.raw("Modules:   ").bold());
            for (int i = 0; i < entry.modules.size(); i++) {
                if (i > 0) modSpans.add(Span.raw(", ").dim());
                modSpans.add(Span.raw(entry.modules.get(i)));
            }
            rows.add(Row.from(Cell.from(Line.from(modSpans))));
        }
    }

    @Override
    boolean needsTickRedraw() {
        return loading || datesLoading;
    }

    @Override
    public void setRunner(TuiRunner runner) {
        super.setRunner(runner);
        if (loading) {
            fetchAllUpdates();
        }
    }

    @Override
    void close() {
        httpPool.shutdownNow();
    }
}
