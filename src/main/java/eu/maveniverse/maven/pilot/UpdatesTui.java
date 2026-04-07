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
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import eu.maveniverse.domtrip.Document;
import eu.maveniverse.domtrip.maven.Coordinates;
import eu.maveniverse.domtrip.maven.PomEditor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Interactive TUI for viewing and applying dependency updates.
 */
class UpdatesTui {

    @FunctionalInterface
    interface VersionResolver {
        List<String> resolveVersions(String groupId, String artifactId);
    }

    static class DependencyInfo {
        final String groupId;
        final String artifactId;
        String version;
        final String scope;
        final String type;
        String newestVersion;
        VersionComparator.UpdateType updateType;
        boolean selected;
        boolean managed;

        DependencyInfo(String groupId, String artifactId, String version, String scope, String type) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version != null ? version : "";
            this.scope = scope != null ? scope : "compile";
            this.type = type != null ? type : "jar";
        }

        String ga() {
            return groupId + ":" + artifactId;
        }

        boolean hasUpdate() {
            return newestVersion != null && !newestVersion.equals(version) && !newestVersion.isEmpty();
        }
    }

    private enum Filter {
        ALL,
        PATCH,
        MINOR,
        MAJOR
    }

    private final List<DependencyInfo> allDeps;
    private List<DependencyInfo> displayDeps;
    private final String pomPath;
    private final String projectGav;
    private final VersionResolver versionResolver;
    private final TableState tableState = new TableState();
    private final ExecutorService httpPool = Executors.newFixedThreadPool(5);
    private final Map<String, SearchTui.PomInfo> pomInfoCache = new HashMap<>();

    private Filter filter = Filter.ALL;
    private String status = "Loading updates\u2026";
    private boolean loading = true;
    private int loadedCount = 0;

    private TuiRunner runner;

    /**
     * Get the currently selected table row index, or -1 if none is selected.
     *
     * @return the selected row index, or -1 when no selection exists
     */
    private int selectedIndex() {
        Integer sel = tableState.selected();
        return sel != null ? sel : -1;
    }

    /**
     * Creates a new UpdatesTui initialized with the given dependency list and target POM.
     *
     * Initializes internal display list as a copy of `dependencies`, stores the POM path and
     * project GAV for display, and selects the first row if there are any dependencies.
     *
     * @param dependencies list of dependencies to show and manage
     * @param pomPath path to the POM file that updates will be applied to
     * @param projectGav human-readable project identifier shown in the UI
     */
    UpdatesTui(List<DependencyInfo> dependencies, String pomPath, String projectGav, VersionResolver versionResolver) {
        this.allDeps = dependencies;
        this.displayDeps = new ArrayList<>(dependencies);
        this.pomPath = pomPath;
        this.projectGav = projectGav;
        this.versionResolver = versionResolver;
        if (!displayDeps.isEmpty()) {
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
            fetchAllUpdates();
            configured.run();
        } finally {
            configured.close();
            httpPool.shutdownNow();
            httpPool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private void fetchAllUpdates() {
        for (var dep : allDeps) {
            CompletableFuture.supplyAsync(() -> versionResolver.resolveVersions(dep.groupId, dep.artifactId), httpPool)
                    .thenAccept(versions -> runner.runOnRenderThread(() -> {
                        loadedCount++;
                        String newest = null;
                        for (String v : versions) {
                            if (VersionComparator.isPreview(v)) continue;
                            if (dep.version.isEmpty() || VersionComparator.isNewer(dep.version, v)) {
                                newest = v;
                                break; // versions are newest-first
                            }
                        }
                        if (newest != null) {
                            dep.newestVersion = newest;
                            dep.updateType = VersionComparator.classify(dep.version, newest);
                        }
                        if (loadedCount >= allDeps.size()) {
                            loading = false;
                            applyFilter();
                            long updates = allDeps.stream()
                                    .filter(DependencyInfo::hasUpdate)
                                    .count();
                            status = updates + " update(s) available out of " + allDeps.size() + " dependencies";
                        }
                    }));
        }
    }

    /**
     * Handle keyboard input for the updates TUI and perform the mapped action.
     *
     * Supported key bindings:
     * - Ctrl+C, Escape, or `q` : quit the UI
     * - Up / Down             : move table selection
     * - Space                 : toggle selection for the current row
     * - `a`                   : select all visible updatable dependencies
     * - `n`                   : deselect all visible dependencies
     * - Enter                 : apply selected updates to the POM
     * - `1` / `2` / `3` / `4` : set filter to ALL / PATCH / MINOR / MAJOR respectively
     *
     * @param event  the input event to handle; non-key events are ignored
     * @param runner the TUI runner instance used to control the UI (e.g. to quit)
     * @return       `true` if the event was handled and triggered an action, `false` otherwise
     */
    boolean handleEvent(Event event, TuiRunner runner) {
        if (!(event instanceof KeyEvent key)) {
            return true;
        }

        if (key.isCtrlC() || key.isKey(KeyCode.ESCAPE) || key.isCharIgnoreCase('q')) {
            runner.quit();
            return true;
        }

        if (key.isUp()) {
            tableState.selectPrevious();
            fetchPomInfoIfNeeded();
            return true;
        }
        if (key.isDown()) {
            tableState.selectNext(displayDeps.size());
            fetchPomInfoIfNeeded();
            return true;
        }

        if (key.isCharIgnoreCase(' ')) {
            toggleSelection();
            return true;
        }

        if (key.isCharIgnoreCase('a')) {
            selectAll();
            return true;
        }

        if (key.isCharIgnoreCase('n')) {
            deselectAll();
            return true;
        }

        if (key.isKey(KeyCode.ENTER)) {
            applyUpdates();
            return true;
        }

        if (key.isCharIgnoreCase('1')) {
            filter = Filter.ALL;
            applyFilter();
            return true;
        }
        if (key.isCharIgnoreCase('2')) {
            filter = Filter.PATCH;
            applyFilter();
            return true;
        }
        if (key.isCharIgnoreCase('3')) {
            filter = Filter.MINOR;
            applyFilter();
            return true;
        }
        if (key.isCharIgnoreCase('4')) {
            filter = Filter.MAJOR;
            applyFilter();
            return true;
        }

        return false;
    }

    /**
     * Toggle the selected state of the currently highlighted dependency when an update is available.
     *
     * If the table has a valid selected row and that dependency has an available update, flips its
     * `selected` flag; otherwise does nothing.
     */
    private void toggleSelection() {
        int sel = selectedIndex();
        if (sel >= 0 && sel < displayDeps.size()) {
            var dep = displayDeps.get(sel);
            if (dep.hasUpdate()) {
                dep.selected = !dep.selected;
            }
        }
    }

    private void selectAll() {
        for (var dep : displayDeps) {
            if (dep.hasUpdate()) dep.selected = true;
        }
    }

    private void deselectAll() {
        for (var dep : displayDeps) {
            dep.selected = false;
        }
    }

    private void applyFilter() {
        displayDeps = allDeps.stream()
                .filter(dep -> switch (filter) {
                    case ALL -> dep.hasUpdate();
                    case PATCH -> dep.updateType == VersionComparator.UpdateType.PATCH;
                    case MINOR -> dep.updateType == VersionComparator.UpdateType.MINOR;
                    case MAJOR -> dep.updateType == VersionComparator.UpdateType.MAJOR;
                })
                .toList();
        if (!displayDeps.isEmpty()) {
            tableState.select(0);
        }
    }

    private void applyUpdates() {
        List<DependencyInfo> toUpdate =
                displayDeps.stream().filter(d -> d.selected && d.hasUpdate()).toList();

        if (toUpdate.isEmpty()) {
            status = "No updates selected";
            return;
        }

        try {
            String pomContent = Files.readString(Path.of(pomPath));
            PomEditor editor = new PomEditor(Document.of(pomContent));

            for (var dep : toUpdate) {
                var coords = Coordinates.of(dep.groupId, dep.artifactId, dep.newestVersion);
                if (dep.managed) {
                    editor.dependencies().updateManagedDependency(true, coords);
                } else {
                    editor.dependencies().updateDependency(true, coords);
                }
            }

            Files.writeString(Path.of(pomPath), editor.toXml());
            status = "Updated " + toUpdate.size() + " dependencies in POM";

            // Update model
            for (var dep : toUpdate) {
                dep.version = dep.newestVersion;
                dep.newestVersion = null;
                dep.selected = false;
                dep.updateType = null;
            }
            applyFilter();
        } catch (Exception e) {
            status = "Failed to update POM: " + e.getMessage();
        }
    }

    // -- Rendering --

    void render(Frame frame) {
        var zones = Layout.vertical()
                .constraints(Constraint.length(3), Constraint.fill(), Constraint.length(3))
                .split(frame.area());

        renderHeader(frame, zones.get(0));
        renderUpdatesTable(frame, zones.get(1));
        renderInfoBar(frame, zones.get(2));
    }

    private void renderHeader(Frame frame, Rect area) {
        String title = loading ? " Pilot \u2014 Checking Updates\u2026 " : " Pilot \u2014 Dependency Updates ";
        Block block = Block.builder()
                .title(title)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().cyan())
                .build();

        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" " + projectGav).bold().cyan());
        if (loading) {
            spans.add(Span.raw("  Checking " + loadedCount + "/" + allDeps.size() + "\u2026")
                    .dim());
        }

        Paragraph header = Paragraph.builder()
                .text(dev.tamboui.text.Text.from(Line.from(spans)))
                .block(block)
                .build();
        frame.renderWidget(header, area);
    }

    private void renderUpdatesTable(Frame frame, Rect area) {
        long updateCount =
                displayDeps.stream().filter(DependencyInfo::hasUpdate).count();
        String title = " Updates (" + updateCount + " of " + allDeps.size() + " dependencies) " + "[" + filter + "] ";

        Block block = Block.builder()
                .title(title)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build();

        if (displayDeps.isEmpty()) {
            String msg = loading ? "Checking versions\u2026" : "No updates available";
            Paragraph empty =
                    Paragraph.builder().text(msg).block(block).centered().build();
            frame.renderWidget(empty, area);
            return;
        }

        Row header = Row.from("", "groupId:artifactId", "current", "", "available", "type")
                .style(Style.create().bold().yellow());

        List<Row> rows = new ArrayList<>();
        for (var dep : displayDeps) {
            rows.add(createUpdateRow(dep));
        }

        Table table = Table.builder()
                .header(header)
                .rows(rows)
                .widths(
                        Constraint.length(3), Constraint.percentage(40),
                        Constraint.percentage(15), Constraint.length(3),
                        Constraint.percentage(15), Constraint.percentage(10))
                .highlightStyle(Style.create().reversed().bold())
                .highlightSymbol("\u25B8 ")
                .block(block)
                .build();

        frame.renderStatefulWidget(table, area, tableState);
    }

    private Row createUpdateRow(DependencyInfo dep) {
        String check = dep.selected ? "[\u2713]" : "[ ]";
        String ga = dep.groupId + ":" + dep.artifactId;
        String current = dep.version;
        String arrow = dep.hasUpdate() ? "\u2192" : "";
        String available = dep.hasUpdate() ? dep.newestVersion : "";
        String type = dep.updateType != null ? dep.updateType.name().toLowerCase() : "";

        Style style = dep.updateType != null
                ? switch (dep.updateType) {
                    case PATCH -> Style.create().fg(Color.GREEN);
                    case MINOR -> Style.create().fg(Color.YELLOW);
                    case MAJOR -> Style.create().fg(Color.RED);
                }
                : Style.create();

        return Row.from(check, ga, current, arrow, available, type).style(style);
    }

    private void renderInfoBar(Frame frame, Rect area) {
        var rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.length(1))
                .split(area);

        // POM info
        renderPomInfo(frame, rows.get(0));

        // Status
        List<Span> statusSpans = new ArrayList<>();
        statusSpans.add(Span.raw(" " + status).fg(Color.GREEN));
        frame.renderWidget(Paragraph.from(Line.from(statusSpans)), rows.get(1));

        // Key bindings
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" "));
        spans.add(Span.raw("\u2191\u2193").bold());
        spans.add(Span.raw(":Nav  "));
        spans.add(Span.raw("Space").bold());
        spans.add(Span.raw(":Toggle  "));
        spans.add(Span.raw("a").bold());
        spans.add(Span.raw(":All  "));
        spans.add(Span.raw("n").bold());
        spans.add(Span.raw(":None  "));
        spans.add(Span.raw("Enter").bold());
        spans.add(Span.raw(":Apply  "));
        spans.add(Span.raw("1-4").bold());
        spans.add(Span.raw(":Filter  "));
        spans.add(Span.raw("q").bold());
        spans.add(Span.raw(":Quit"));

        frame.renderWidget(Paragraph.from(Line.from(spans)), rows.get(2));
    }

    /**
     * Renders a single-line POM metadata summary for the currently selected dependency.
     *
     * If cached POM info with a project name is available, renders the project name (bold cyan)
     * and, when present, the license (green) and organization (dim), separated by a dark-gray divider.
     * Otherwise renders the dependency's "groupId:artifactId" in cyan and appends " (managed)" in dim
     * if the dependency is managed.
     *
     * @param frame the frame to render into
     * @param area the rectangular area within the frame to use for rendering
     */
    private void renderPomInfo(Frame frame, Rect area) {
        List<Span> spans = new ArrayList<>();
        int sel = selectedIndex();
        if (sel >= 0 && sel < displayDeps.size()) {
            var dep = displayDeps.get(sel);
            String pomKey = dep.groupId + ":" + dep.artifactId + ":" + dep.version;
            var info = pomInfoCache.get(pomKey);
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
            } else {
                spans.add(Span.raw(" " + dep.ga()).cyan());
                if (dep.managed) {
                    spans.add(Span.raw(" (managed)").dim());
                }
            }
        }
        frame.renderWidget(Paragraph.from(Line.from(spans)), area);
    }

    /**
     * Fetches and caches POM metadata for the currently selected dependency if it is not already cached.
     *
     * If the selected index is invalid or the cache already contains an entry for the dependency (key
     * "groupId:artifactId:version"), the method returns immediately. Otherwise it inserts a placeholder
     * cache entry to prevent duplicate fetches, starts an asynchronous fetch using the internal HTTP
     * pool, and updates the cache on the renderer thread when the fetch completes.
     */
    private void fetchPomInfoIfNeeded() {
        int sel = selectedIndex();
        if (sel < 0 || sel >= displayDeps.size()) return;
        var dep = displayDeps.get(sel);
        String pomKey = dep.groupId + ":" + dep.artifactId + ":" + dep.version;
        if (pomInfoCache.containsKey(pomKey)) return;
        pomInfoCache.put(pomKey, new SearchTui.PomInfo(null, null, null, null, null, null));

        CompletableFuture.supplyAsync(
                        () -> SearchTui.fetchPomFromCentral(dep.groupId, dep.artifactId, dep.version), httpPool)
                .thenAccept(info -> runner.runOnRenderThread(() -> pomInfoCache.put(pomKey, info)));
    }
}
