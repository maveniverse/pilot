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
import eu.maveniverse.domtrip.Document;
import eu.maveniverse.domtrip.maven.Coordinates;
import eu.maveniverse.domtrip.maven.PomEditor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Interactive TUI for viewing and applying dependency updates.
 */
public class UpdatesTui extends ToolPanel {

    @FunctionalInterface
    public interface VersionResolver {
        List<String> resolveVersions(String groupId, String artifactId);
    }

    /**
     * Convert a list of version objects to strings ordered newest-first.
     */
    public static List<String> versionsNewestFirst(List<?> versions) {
        List<String> result = versions.stream().map(Object::toString).collect(Collectors.toCollection(ArrayList::new));
        Collections.reverse(result);
        return result;
    }

    public static class DependencyInfo {
        public final String groupId;
        public final String artifactId;
        String version;
        final String scope;
        final String type;
        String newestVersion;
        VersionComparator.UpdateType updateType;
        boolean selected;
        public boolean managed;
        String versionProperty; // shared property name, e.g. "mavenVersion" from "${mavenVersion}"

        public DependencyInfo(String groupId, String artifactId, String version, String scope, String type) {
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
    private final ExecutorService httpPool = PilotUtil.newHttpPool();
    private final Map<String, SearchTui.PomInfo> pomInfoCache = new HashMap<>();

    private Filter filter = Filter.ALL;
    String status = "Loading updates…";
    boolean loading = true;
    int loadedCount = 0;
    int failedCount = 0;
    private final String originalPomContent;
    private final PomEditor editor;
    private boolean dirty;
    private boolean pendingQuit;
    private final DiffOverlay diffOverlay = new DiffOverlay();
    private int lastContentHeight;

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
    public UpdatesTui(
            List<DependencyInfo> dependencies, String pomPath, String projectGav, VersionResolver versionResolver) {
        this.allDeps = dependencies;
        this.displayDeps = new ArrayList<>(dependencies);
        this.pomPath = pomPath;
        this.projectGav = projectGav;
        this.versionResolver = versionResolver;
        String pom;
        try {
            pom = Files.readString(Path.of(pomPath));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read POM: " + pomPath, e);
        }
        this.originalPomContent = pom;
        this.editor = new PomEditor(Document.of(pom));
        this.sortState = new SortState(6); // "", ga, current, "", available, type
        detectPropertyGroups();
        if (!displayDeps.isEmpty()) {
            tableState.select(0);
        }
    }

    private void detectPropertyGroups() {
        try {
            eu.maveniverse.domtrip.Element root = editor.document().root();
            scanDependencyVersions(root.childElement("dependencies").orElse(null));
            root.childElement("dependencyManagement")
                    .flatMap(dm -> dm.childElement("dependencies"))
                    .ifPresent(this::scanDependencyVersions);
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private void scanDependencyVersions(eu.maveniverse.domtrip.Element deps) {
        if (deps == null) return;
        deps.childElements("dependency").forEach(dep -> {
            String gid = dep.childTextOr("groupId", "");
            String aid = dep.childTextOr("artifactId", "");
            String rawVersion = dep.childTextOr("version", null);
            if (rawVersion != null && rawVersion.startsWith("${") && rawVersion.endsWith("}")) {
                String prop = rawVersion.substring(2, rawVersion.length() - 1);
                for (DependencyInfo di : allDeps) {
                    if (di.groupId.equals(gid) && di.artifactId.equals(aid)) {
                        di.versionProperty = prop;
                    }
                }
            }
        });
    }

    private void fetchAllUpdates() {
        fetchUpdates(runner::runOnRenderThread, httpPool);
    }

    CompletableFuture<Void> fetchUpdates(java.util.function.Consumer<Runnable> renderThread, ExecutorService executor) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (var dep : allDeps) {
            var future = CompletableFuture.supplyAsync(
                            () -> versionResolver.resolveVersions(dep.groupId, dep.artifactId), executor)
                    .thenAccept(versions -> renderThread.accept(() -> {
                        applyVersionResult(dep, versions);
                        updateStatusIfDone();
                    }))
                    .exceptionally(ex -> {
                        renderThread.accept(() -> {
                            loadedCount++;
                            failedCount++;
                            updateStatusIfDone();
                        });
                        return null;
                    });
            futures.add(future);
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * Apply resolved versions to a dependency, selecting the newest non-preview version.
     */
    void applyVersionResult(DependencyInfo dep, List<String> versions) {
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
    }

    void updateStatusIfDone() {
        if (loadedCount >= allDeps.size()) {
            loading = false;
            applyFilter();
            long updates = allDeps.stream().filter(DependencyInfo::hasUpdate).count();
            status = updates + " update(s) available out of " + allDeps.size() + " dependencies";
            if (failedCount > 0) {
                status += "; " + failedCount + " lookup(s) failed";
            }
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

        // Save prompt mode (standalone only)
        if (pendingQuit) {
            if (key.isCharIgnoreCase('y')) {
                saveAndQuit();
                return true;
            }
            if (key.isCharIgnoreCase('n')) {
                runner.quit();
                return true;
            }
            if (key.isKey(KeyCode.ESCAPE)) {
                pendingQuit = false;
                status = "Quit cancelled";
                return true;
            }
            return false;
        }

        // Diff overlay mode (standalone — panel mode handles in handleKeyEvent)
        if (diffOverlay.isActive()) {
            if (key.isKey(KeyCode.ESCAPE)) {
                diffOverlay.close();
                return true;
            }
            if (diffOverlay.handleScrollKey(key, lastContentHeight)) return true;
            if (key.isCharIgnoreCase('q') || key.isCtrlC()) {
                requestQuit();
                return true;
            }
            return false;
        }

        // Help overlay mode (standalone only)
        if (helpOverlay.isActive()) {
            if (helpOverlay.handleKey(key)) return true;
            if (key.isCharIgnoreCase('q') || key.isCtrlC()) {
                requestQuit();
                return true;
            }
            return false;
        }

        if (key.isCtrlC()) {
            requestQuit();
            return true;
        }

        // Try panel-level handling first
        if (handleKeyEvent(key)) return true;

        // Standalone-only keys
        if (key.isKey(KeyCode.ESCAPE) || key.isCharIgnoreCase('q')) {
            requestQuit();
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
        return "Updates";
    }

    @Override
    public void render(Frame frame, Rect area) {
        if (diffOverlay.isActive()) {
            diffOverlay.render(frame, area, " POM Changes ");
            lastContentHeight = area.height();
            return;
        }
        lastContentHeight = area.height();
        renderUpdatesTable(frame, area);
    }

    @Override
    public boolean handleKeyEvent(KeyEvent key) {
        // Diff overlay (panel mode)
        if (diffOverlay.isActive()) {
            if (key.isKey(KeyCode.ESCAPE)) {
                diffOverlay.close();
                return true;
            }
            if (diffOverlay.handleScrollKey(key, lastContentHeight)) return true;
            return true; // consume all keys during overlay
        }

        if (handleSortInput(key)) return true;

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
        if (TableNavigation.handlePageKeys(key, tableState, displayDeps.size(), lastContentHeight)) {
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

        if (key.isCharIgnoreCase('d')) {
            toggleDiffView();
            return true;
        }

        return false;
    }

    @Override
    public boolean handleMouseEvent(MouseEvent mouse, Rect area) {
        if (handleMouseSortHeader(
                mouse,
                List.of(
                        Constraint.length(3), Constraint.percentage(40),
                        Constraint.percentage(15), Constraint.length(3),
                        Constraint.percentage(15), Constraint.percentage(10)))) {
            return true;
        }
        if (mouse.isClick()) {
            int row = mouse.y() - area.y() - 2 + tableState.offset(); // border + header + scroll
            if (row >= 0 && row < displayDeps.size()) {
                tableState.select(row);
                fetchPomInfoIfNeeded();
                return true;
            }
        }
        if (mouse.isScroll()) {
            if (displayDeps.isEmpty()) return false;
            int sel = tableState.selected();
            if (mouse.kind() == MouseEventKind.SCROLL_UP) {
                tableState.select(Math.max(0, sel - 1));
            } else {
                tableState.select(Math.min(displayDeps.size() - 1, sel + 1));
            }
            fetchPomInfoIfNeeded();
            return true;
        }
        return false;
    }

    @Override
    protected void onSortChanged() {
        sortState.sort(displayDeps, sortExtractors());
    }

    private List<Function<DependencyInfo, String>> sortExtractors() {
        return List.of(
                dep -> dep.selected ? "1" : "0", // "" (checkbox)
                DependencyInfo::ga, // groupId:artifactId
                dep -> dep.version, // current
                dep -> dep.hasUpdate() ? "1" : "0", // "" (arrow)
                dep -> dep.newestVersion != null ? dep.newestVersion : "", // available
                dep -> dep.updateType != null ? dep.updateType.name() : "" // type
                );
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public List<Span> keyHints() {
        List<Span> spans = new ArrayList<>();
        if (diffOverlay.isActive()) {
            spans.add(Span.raw("↑↓").bold());
            spans.add(Span.raw(":Scroll  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Close  "));
        } else {
            spans.add(Span.raw("↑↓").bold());
            spans.add(Span.raw(":Nav  "));
            spans.add(Span.raw("Space").bold());
            spans.add(Span.raw(":Toggle  "));
            spans.add(Span.raw("a").bold());
            spans.add(Span.raw(":All  "));
            spans.add(Span.raw("n").bold());
            spans.add(Span.raw(":None  "));
            spans.add(Span.raw("Enter").bold());
            spans.add(Span.raw(":Apply  "));
            spans.add(Span.raw("f").bold());
            spans.add(Span.raw(":Filter  "));
            spans.addAll(sortKeyHints());
            spans.add(Span.raw("d").bold());
            spans.add(Span.raw(":Diff  "));
        }
        return spans;
    }

    @Override
    public List<HelpOverlay.Section> helpSections() {
        return HelpOverlay.parse("""
                ## Dependency Updates
                Checks Maven Central for newer versions of each
                declared dependency. Versions are resolved in
                the background — status bar shows progress.
                Select updates with Space, then press Enter to
                apply them to the POM. Use 'd' to preview the
                changes as a unified diff before committing.

                ## Filters
                f / F           Cycle filter: All > Patch > Minor > Major
                All — show all dependencies (default)
                Patch — only patch updates (bug fixes)
                Minor — only minor updates (new features)
                Major — only major updates (breaking changes)

                ## Colors
                green           Patch update — safe to apply
                yellow          Minor update — usually compatible
                red             Major update — breaking changes possible
                dim             No update available or up-to-date

                ## Actions
                ↑ / ↓           Move selection up / down
                Space           Toggle selection of current dependency
                a / n           Select all / deselect all
                Enter           Apply selected updates to POM
                d               Preview POM changes as unified diff
                s / S           Sort by column / reverse sort
                /               Search dependencies by name
                n / N           Next / previous search match
                """);
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public boolean save() {
        return doSave();
    }

    @Override
    public void setRunner(TuiRunner runner) {
        this.runner = runner;
        if (loading) {
            fetchAllUpdates();
        }
    }

    @Override
    void close() {
        httpPool.shutdownNow();
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
                boolean newState = !dep.selected;
                dep.selected = newState;
                if (dep.versionProperty != null) {
                    for (var other : allDeps) {
                        if (other != dep && dep.versionProperty.equals(other.versionProperty)) {
                            other.selected = newState;
                        }
                    }
                }
            }
        }
    }

    private void requestQuit() {
        if (dirty) {
            pendingQuit = true;
            status = "Save changes to POM?";
        } else {
            runner.quit();
        }
    }

    private boolean doSave() {
        try {
            String currentOnDisk = Files.readString(Path.of(pomPath));
            if (!currentOnDisk.equals(originalPomContent)) {
                status = "POM modified externally — save aborted";
                return false;
            }
            Files.writeString(Path.of(pomPath), editor.toXml());
            dirty = false;
            status = "Saved";
            return true;
        } catch (Exception e) {
            status = "Failed to save: " + e.getMessage();
            return false;
        }
    }

    private void saveAndQuit() {
        if (doSave()) {
            runner.quit();
        } else {
            pendingQuit = false;
        }
    }

    private void toggleDiffView() {
        // Include both staged changes and currently selected (not yet applied) updates
        // Use allDeps to honor property-linked peers outside the filtered view
        List<DependencyInfo> pendingSelected =
                allDeps.stream().filter(d -> d.selected && d.hasUpdate()).toList();

        String modifiedPom;
        if (pendingSelected.isEmpty()) {
            modifiedPom = editor.toXml();
        } else {
            PomEditor preview = new PomEditor(Document.of(editor.toXml()));
            for (var dep : pendingSelected) {
                var coords = Coordinates.of(dep.groupId, dep.artifactId, dep.newestVersion);
                if (dep.managed) {
                    preview.dependencies().updateManagedDependency(true, coords);
                } else {
                    preview.dependencies().updateDependency(true, coords);
                }
            }
            modifiedPom = preview.toXml();
        }

        long changes = diffOverlay.open(originalPomContent, modifiedPom);
        status = changes == 0 ? "No changes to show" : changes + " line(s) changed";
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
                .collect(Collectors.toCollection(ArrayList::new));
        onSortChanged();
        if (!displayDeps.isEmpty()) {
            tableState.select(0);
        }
    }

    private void applyUpdates() {
        // Use allDeps to honor property-linked peers outside the filtered view
        List<DependencyInfo> toUpdate =
                allDeps.stream().filter(d -> d.selected && d.hasUpdate()).toList();

        if (toUpdate.isEmpty()) {
            status = "No updates selected";
            return;
        }

        try {
            // Validate on a preview editor first to avoid partial mutations
            PomEditor preview = new PomEditor(Document.of(editor.toXml()));
            for (var dep : toUpdate) {
                var coords = Coordinates.of(dep.groupId, dep.artifactId, dep.newestVersion);
                if (dep.managed) {
                    preview.dependencies().updateManagedDependency(true, coords);
                } else {
                    preview.dependencies().updateDependency(true, coords);
                }
            }

            // All updates succeeded on preview — apply to real editor
            for (var dep : toUpdate) {
                var coords = Coordinates.of(dep.groupId, dep.artifactId, dep.newestVersion);
                if (dep.managed) {
                    editor.dependencies().updateManagedDependency(true, coords);
                } else {
                    editor.dependencies().updateDependency(true, coords);
                }
            }

            dirty = true;
            status = "Staged " + toUpdate.size() + " update(s) — save on exit";

            for (var dep : toUpdate) {
                dep.version = dep.newestVersion;
                dep.newestVersion = null;
                dep.selected = false;
                dep.updateType = null;
            }
            applyFilter();
        } catch (Exception e) {
            status = "Failed to update: " + e.getMessage();
        }
    }

    // -- Rendering --

    void renderStandalone(Frame frame) {
        var zones = Layout.vertical()
                .constraints(Constraint.length(3), Constraint.fill(), Constraint.length(3))
                .split(frame.area());

        renderHeader(frame, zones.get(0));
        Rect contentArea = renderStandaloneHelp(frame, zones.get(1));
        if (contentArea != null) {
            lastContentHeight = contentArea.height();
            if (diffOverlay.isActive()) {
                diffOverlay.render(frame, contentArea, " POM Changes ");
            } else {
                renderUpdatesTable(frame, contentArea);
            }
        }
        renderInfoBar(frame, zones.get(2));
    }

    private void renderHeader(Frame frame, Rect area) {
        String title = loading ? "Checking Updates…" : "Dependency Updates";
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" " + projectGav).bold().cyan());
        if (loading) {
            spans.add(Span.raw("  Checking " + loadedCount + "/" + allDeps.size() + "…")
                    .dim());
        }
        if (dirty) {
            spans.add(theme.dirtyIndicator());
        }
        renderStandaloneHeader(frame, area, title, Line.from(spans));
    }

    private void renderUpdatesTable(Frame frame, Rect area) {
        long updateCount =
                displayDeps.stream().filter(DependencyInfo::hasUpdate).count();
        String title = " Updates (" + updateCount + " of " + allDeps.size() + " dependencies) " + "[" + filter + "] ";

        Block block = Block.builder()
                .title(title)
                .borderType(BorderType.ROUNDED)
                .borderStyle(borderStyle())
                .build();

        if (displayDeps.isEmpty()) {
            String msg = loading ? "Checking versions…" : "No updates available";
            Paragraph empty =
                    Paragraph.builder().text(msg).block(block).centered().build();
            frame.renderWidget(empty, area);
            return;
        }

        Row header = sortState.decorateHeader(
                List.of("", "groupId:artifactId", "current", "", "available", "type"), theme.tableHeader());

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
                .highlightSymbol("▸ ")
                .block(block)
                .build();

        lastTableArea = area;
        frame.renderStatefulWidget(table, area, tableState);
    }

    private Row createUpdateRow(DependencyInfo dep) {
        String check = dep.selected ? "[✓]" : "[ ]";
        String ga = dep.groupId + ":" + dep.artifactId;
        String current = dep.version;
        String arrow = dep.hasUpdate() ? "→" : "";
        String available = dep.hasUpdate() ? dep.newestVersion : "";
        String type = dep.updateType != null ? dep.updateType.name().toLowerCase() : "";

        Style style = dep.updateType != null
                ? switch (dep.updateType) {
                    case PATCH -> theme.updatePatch();
                    case MINOR -> theme.updateMinor();
                    case MAJOR -> theme.updateMajor();
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
        statusSpans.add(
                Span.raw(" " + status).fg(pendingQuit ? theme.statusWarningColor() : theme.standaloneStatusColor()));
        frame.renderWidget(Paragraph.from(Line.from(statusSpans)), rows.get(1));

        // Key bindings
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" "));
        if (pendingQuit) {
            spans.add(Span.raw("y").bold());
            spans.add(Span.raw(":Save and quit  "));
            spans.add(Span.raw("n").bold());
            spans.add(Span.raw(":Discard and quit  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Cancel"));
        } else if (diffOverlay.isActive()) {
            spans.add(Span.raw("↑↓").bold());
            spans.add(Span.raw(":Scroll  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Close  "));
            spans.add(Span.raw("q").bold());
            spans.add(Span.raw(":Quit"));
        } else {
            spans.add(Span.raw("↑↓").bold());
            spans.add(Span.raw(":Nav  "));
            spans.add(Span.raw("Space").bold());
            spans.add(Span.raw(":Toggle  "));
            spans.add(Span.raw("a").bold());
            spans.add(Span.raw(":All  "));
            spans.add(Span.raw("n").bold());
            spans.add(Span.raw(":None  "));
            spans.add(Span.raw("Enter").bold());
            spans.add(Span.raw(":Apply  "));
            spans.add(Span.raw("f").bold());
            spans.add(Span.raw(":Filter  "));
            spans.add(Span.raw("d").bold());
            spans.add(Span.raw(":Diff  "));
            spans.add(Span.raw("h").bold());
            spans.add(Span.raw(":Help  "));
            spans.add(Span.raw("q").bold());
            spans.add(Span.raw(":Quit"));
        }

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
                    spans.add(Span.raw(" │ ").fg(theme.separatorColor()));
                    spans.add(Span.raw(info.license).fg(theme.metadataValueColor()));
                }
                if (info.organization != null) {
                    spans.add(Span.raw(" │ ").fg(theme.separatorColor()));
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

    private List<HelpOverlay.Section> buildHelpStandalone() {
        List<HelpOverlay.Section> sections = new ArrayList<>(helpSections());
        sections.addAll(HelpOverlay.parse("""
                ## General
                """ + NAV_KEYS + """
                d               Preview POM changes as a unified diff
                h               Toggle this help screen
                q / Esc         Quit (prompts to save if modified)
                """));
        return sections;
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
        pomInfoCache.put(pomKey, new SearchTui.PomInfo(null, null, null, null, null, null, null));

        CompletableFuture.supplyAsync(
                        () -> SearchTui.fetchPomFromCentral(dep.groupId, dep.artifactId, dep.version), httpPool)
                .thenAccept(info -> runner.runOnRenderThread(() -> pomInfoCache.put(pomKey, info)));
    }
}
