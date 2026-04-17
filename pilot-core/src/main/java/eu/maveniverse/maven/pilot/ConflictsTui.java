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
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import eu.maveniverse.domtrip.maven.Coordinates;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Interactive TUI for dependency conflict resolution.
 */
public class ConflictsTui extends ToolPanel {

    public static class ConflictEntry {
        final String groupId;
        final String artifactId;
        public final String requestedVersion;
        public final String resolvedVersion;
        final String path; // dependency path
        final String scope;

        public ConflictEntry(
                String groupId,
                String artifactId,
                String requestedVersion,
                String resolvedVersion,
                String path,
                String scope) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.requestedVersion = requestedVersion;
            this.resolvedVersion = resolvedVersion;
            this.path = path;
            this.scope = scope != null ? scope : "compile";
        }

        boolean isConflict() {
            return requestedVersion != null && !requestedVersion.equals(resolvedVersion);
        }
    }

    public static class ConflictGroup {
        final String ga;
        final List<ConflictEntry> entries;

        public ConflictGroup(String ga, List<ConflictEntry> entries) {
            this.ga = ga;
            this.entries = entries;
        }

        boolean hasConflict() {
            Set<String> versions = entries.stream().map(e -> e.requestedVersion).collect(Collectors.toSet());
            return versions.size() > 1;
        }

        String resolvedVersion() {
            return entries.isEmpty() ? "?" : entries.get(0).resolvedVersion;
        }
    }

    @FunctionalInterface
    public interface TreeResolver {
        DependencyTreeModel resolve(PilotProject project);
    }

    private List<ConflictGroup> conflicts;
    private final String projectGav;
    private final TableState tableState = new TableState();
    private boolean showDetails = true;
    private boolean showAll = false;
    private String status;

    private boolean loading;
    private int loadedCount;
    private int totalModules;
    private volatile String loadingModule;
    private TreeResolver treeResolver;
    private List<PilotProject> pendingProjects;

    private boolean pendingQuit;
    private final DiffOverlay diffOverlay = new DiffOverlay();
    private int lastContentHeight;

    private TuiRunner runner;

    /**
     * Get the currently selected table-row index, or -1 when no selection exists.
     *
     * @return the selected index, or -1 if no row is selected
     */
    private int selectedIndex() {
        Integer sel = tableState.selected();
        return sel != null ? sel : -1;
    }

    /**
     * Returns the list of conflict groups currently visible based on the {@code showAll} toggle.
     * When {@code showAll} is false, only groups with actual version conflicts are included.
     */
    private List<ConflictGroup> displayedConflicts() {
        if (showAll) {
            return conflicts;
        }
        return conflicts.stream().filter(ConflictGroup::hasConflict).collect(Collectors.toList());
    }

    /** Standalone constructor — creates its own PomEditSession from the POM file path. */
    public ConflictsTui(List<ConflictGroup> conflicts, String pomPath, String projectGav) {
        this(conflicts, new PomEditSession(Path.of(pomPath)), projectGav);
    }

    /** Panel-mode constructor — uses a shared PomEditSession from the shell. */
    public ConflictsTui(List<ConflictGroup> conflicts, PomEditSession session, String projectGav) {
        this.editSession = session;
        this.conflicts = conflicts;
        this.projectGav = projectGav;
        this.status = conflicts.size() + " dependency group(s) with version variance";
        this.sortState = new SortState(4);
        if (!displayedConflicts().isEmpty()) {
            tableState.select(0);
        }
    }

    /** Async-loading constructor — collects conflicts in the background after setRunner(). */
    public ConflictsTui(
            List<PilotProject> projects, PomEditSession session, String projectGav, TreeResolver treeResolver) {
        this.editSession = session;
        this.conflicts = new ArrayList<>();
        this.projectGav = projectGav;
        this.loading = true;
        this.totalModules = projects.size();
        this.treeResolver = treeResolver;
        this.pendingProjects = projects;
        this.status = "Collecting conflicts…";
        this.sortState = new SortState(4);
    }

    /**
     * Handle keyboard events and execute corresponding TUI actions such as navigation, toggling details,
     * pinning a group's resolved version, or quitting the runner.
     *
     * @param event  the input event to handle
     * @param runner the TUI runner used to control application lifecycle (e.g., to quit)
     * @return `true` if the event was handled and should not be processed further, `false` otherwise
     */
    boolean handleEvent(Event event, TuiRunner runner) {
        if (!(event instanceof KeyEvent key)) {
            return true;
        }

        // Save prompt mode
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

        // Diff overlay mode — standalone allows q to quit
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

        // Help overlay mode
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

        // Delegate to handleKeyEvent for tool-specific keys
        if (handleKeyEvent(key)) return true;

        // Standalone-specific keys
        if (key.isCharIgnoreCase('q') || key.isKey(KeyCode.ESCAPE)) {
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
        return "Conflicts";
    }

    @Override
    public void render(Frame frame, Rect area) {
        lastContentHeight = area.height();
        if (loading) {
            renderConflicts(frame, area);
            return;
        }
        if (diffOverlay.isActive()) {
            diffOverlay.render(frame, area, " POM Changes ");
        } else if (helpOverlay.isActive()) {
            helpOverlay.render(frame, area);
        } else {
            boolean detailsVisible = showDetails;
            if (detailsVisible) {
                var zones = Layout.vertical()
                        .constraints(Constraint.fill(), Constraint.length(1), Constraint.percentage(30))
                        .split(area);
                lastContentHeight = zones.get(0).height();
                renderConflicts(frame, zones.get(0));
                renderDivider(frame, zones.get(1));
                renderDetails(frame, zones.get(2));
            } else {
                renderConflicts(frame, area);
            }
        }
    }

    @Override
    public boolean handleKeyEvent(KeyEvent key) {
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
            return true;
        }
        if (key.isDown()) {
            tableState.selectNext(displayedConflicts().size());
            return true;
        }
        if (TableNavigation.handlePageKeys(key, tableState, displayedConflicts().size(), lastContentHeight)) {
            return true;
        }

        if (key.isKey(KeyCode.ENTER) || key.isCharIgnoreCase(' ')) {
            showDetails = !showDetails;
            return true;
        }

        if (key.isCharIgnoreCase('p')) {
            pinVersion();
            return true;
        }

        if (key.isCharIgnoreCase('d')) {
            toggleDiffView();
            return true;
        }

        if (key.isCharIgnoreCase('t')) {
            showAll = !showAll;
            List<ConflictGroup> displayed = displayedConflicts();
            if (displayed.isEmpty()) {
                tableState.clearSelection();
            } else {
                int sel = selectedIndex();
                if (sel < 0 || sel >= displayed.size()) {
                    tableState.select(0);
                }
            }
            status = showAll ? "Showing all dependency groups" : "Showing conflicts only";
            return true;
        }

        return false;
    }

    @Override
    public boolean handleMouseEvent(MouseEvent mouse, Rect area) {
        if (handleMouseSortHeader(
                mouse,
                List.of(
                        Constraint.length(6), Constraint.percentage(40),
                        Constraint.percentage(15), Constraint.fill()))) {
            return true;
        }
        if (mouse.isClick()) {
            var displayed = displayedConflicts();
            int row = mouseToTableRow(mouse, displayed.size(), tableState);
            if (row >= 0) {
                tableState.select(row);
                return true;
            }
        }
        if (mouse.isScroll()) {
            var displayed = displayedConflicts();
            if (displayed.isEmpty()) return false;
            int sel = tableState.selected();
            if (mouse.kind() == MouseEventKind.SCROLL_UP) {
                tableState.select(Math.max(0, sel - 1));
            } else {
                tableState.select(Math.min(displayed.size() - 1, sel + 1));
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
        if (diffOverlay.isActive()) {
            spans.add(Span.raw("↑↓").bold());
            spans.add(Span.raw(":Scroll  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Close"));
        } else {
            spans.add(Span.raw("↑↓").bold());
            spans.add(Span.raw(":Navigate  "));
            spans.add(Span.raw("Enter").bold());
            spans.add(Span.raw(":Details  "));
            spans.add(Span.raw("t").bold());
            spans.add(Span.raw(showAll ? ":Conflicts only  " : ":Show all  "));
            spans.add(Span.raw("p").bold());
            spans.add(Span.raw(":Pin version  "));
            spans.addAll(sortKeyHints());
            spans.add(Span.raw("d").bold());
            spans.add(Span.raw(":Diff"));
        }
        return spans;
    }

    @Override
    public List<HelpOverlay.Section> helpSections() {
        return HelpOverlay.parse("""
                ## Conflict Resolution
                When multiple dependencies request different versions
                of the same artifact, Maven picks one (the 'resolved'
                version). This screen shows all such groups.
                The details pane shows the dependency path for each
                request, so you can see which transitive chain
                asked for which version.
                Use 'p' to pin a group's resolved version into
                <dependencyManagement>, ensuring all modules use
                that version explicitly.

                ## Table Columns
                status          ⚠ = conflict (versions differ), ✓ = consistent
                dependency      groupId:artifactId
                resolved        The version Maven selected
                versions        All distinct versions requested

                ## Colors
                white           Actual conflict — different versions requested
                dim             No conflict — all requests agree on version

                ## Conflict Actions
                ↑ / ↓           Move selection up / down
                Enter / Space   Toggle dependency path details
                t               Toggle between conflicts only / all groups
                p               Pin resolved version in dependencyManagement
                d               Preview POM changes as a unified diff
                """);
    }

    @Override
    protected void onSortChanged() {
        List<Function<ConflictGroup, String>> extractors = List.of(
                g -> g.hasConflict() ? "⚠" : "✓",
                g -> g.ga,
                ConflictGroup::resolvedVersion,
                g -> g.entries.stream().map(e -> e.requestedVersion).distinct().collect(Collectors.joining(", ")));
        sortState.sort(conflicts, extractors);
    }

    @Override
    public void setRunner(TuiRunner runner) {
        this.runner = runner;
        if (loading && pendingProjects != null) {
            startConflictCollection();
        }
    }

    private void startConflictCollection() {
        ExecutorService pool =
                Executors.newFixedThreadPool(Math.min(4, Runtime.getRuntime().availableProcessors()), r -> {
                    Thread t = new Thread(r, "pilot-conflicts");
                    t.setDaemon(true);
                    return t;
                });

        List<PilotProject> projects = this.pendingProjects;
        boolean multiModule = projects.size() > 1;
        Map<String, List<ConflictEntry>> mergedMap = new HashMap<>();

        for (PilotProject project : projects) {
            CompletableFuture.supplyAsync(() -> resolveModule(project, multiModule), pool)
                    .thenAccept(localMap -> runner.runOnRenderThread(() -> mergeAndAdvance(localMap, mergedMap, pool)))
                    .exceptionally(ex -> {
                        runner.runOnRenderThread(() -> mergeAndAdvance(Map.of(), mergedMap, pool));
                        return null;
                    });
        }
    }

    private Map<String, List<ConflictEntry>> resolveModule(PilotProject project, boolean multiModule) {
        loadingModule = project.artifactId;
        DependencyTreeModel tree = treeResolver.resolve(project);
        Map<String, List<ConflictEntry>> localMap = new HashMap<>();
        List<String> modulePath = new ArrayList<>();
        if (multiModule) modulePath.add("[" + project.artifactId + "]");
        collectConflicts(tree.root, localMap, modulePath);
        return localMap;
    }

    private void mergeAndAdvance(
            Map<String, List<ConflictEntry>> localMap,
            Map<String, List<ConflictEntry>> mergedMap,
            ExecutorService pool) {
        for (var entry : localMap.entrySet()) {
            mergedMap.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
        }
        loadedCount++;
        status = "Collecting conflicts… " + loadedCount + "/" + totalModules;
        if (loadedCount >= totalModules) {
            onCollectionComplete(mergedMap);
            pool.shutdown();
        }
    }

    private void onCollectionComplete(Map<String, List<ConflictEntry>> mergedMap) {
        conflicts = filterConflictGroups(mergedMap);
        loading = false;
        status = conflicts.size() + " dependency group(s) with version variance";
        if (!displayedConflicts().isEmpty()) {
            tableState.select(0);
        }
    }

    static void collectConflicts(
            DependencyTreeModel.TreeNode node, Map<String, List<ConflictEntry>> conflicts, List<String> path) {
        for (DependencyTreeModel.TreeNode child : node.children) {
            String ga = child.ga();
            String requestedVersion = child.version;
            String resolvedVersion = child.version;
            if (child.requestedVersion != null) {
                requestedVersion = child.requestedVersion;
            }

            List<String> currentPath = new ArrayList<>(path);
            currentPath.add(ga);

            var entry = new ConflictEntry(
                    child.groupId,
                    child.artifactId,
                    requestedVersion,
                    resolvedVersion,
                    String.join(" → ", currentPath),
                    child.scope);

            conflicts.computeIfAbsent(ga, k -> new ArrayList<>()).add(entry);
            collectConflicts(child, conflicts, currentPath);
        }
    }

    static List<ConflictGroup> filterConflictGroups(Map<String, List<ConflictEntry>> conflictMap) {
        return conflictMap.entrySet().stream()
                .filter(e -> e.getValue().size() > 1
                        || e.getValue().stream()
                                .anyMatch(c ->
                                        c.requestedVersion != null && !c.requestedVersion.equals(c.resolvedVersion)))
                .map(e -> new ConflictGroup(e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * Pins the currently selected conflict group's resolved version into the project's POM
     * dependencyManagement section and updates the TUI status message.
     *
     * If no row is selected or the selection is out of range, the method returns without action.
     * On success the status is set to indicate the GA and pinned version; on failure the status
     * contains the error message.
     */
    private void pinVersion() {
        List<ConflictGroup> displayed = displayedConflicts();
        int sel = selectedIndex();
        if (sel < 0 || sel >= displayed.size()) return;
        var group = displayed.get(sel);

        try {
            editSession.beforeMutation();
            String resolvedVersion = group.resolvedVersion();
            var coords = Coordinates.of(group.entries.get(0).groupId, group.entries.get(0).artifactId, resolvedVersion);
            editSession.editor().dependencies().updateManagedDependency(true, coords);
            editSession.recordChange(
                    PomEditSession.ChangeType.ADD, "managed", group.ga, "pinned to " + resolvedVersion, "conflicts");

            status = "Pinned " + group.ga + " to " + resolvedVersion + " — save on exit";
        } catch (Exception e) {
            status = "Failed to pin version: " + e.getMessage();
        }
    }

    private void requestQuit() {
        if (isDirty()) {
            pendingQuit = true;
            status = "Save changes to POM?";
        } else {
            runner.quit();
        }
    }

    private void saveAndQuit() {
        PomEditSession.SaveResult result = editSession.save();
        if (result.success()) {
            runner.quit();
        } else {
            pendingQuit = false;
            status = result.message();
        }
    }

    private void toggleDiffView() {
        long changes = diffOverlay.open(editSession.originalContent(), editSession.currentXml());
        status = changes == 0 ? "No changes to show" : changes + " line(s) changed";
    }

    private List<HelpOverlay.Section> buildHelpStandalone() {
        List<HelpOverlay.Section> sections = new ArrayList<>(helpSections());
        List<HelpOverlay.Section> parsed = HelpOverlay.parse("""
                ## Keys
                """ + NAV_KEYS + """
                Enter / Space   Toggle dependency path details
                t               Toggle between conflicts only / all groups
                p               Pin resolved version in dependencyManagement
                d               Preview POM changes as a unified diff
                h               Toggle this help screen
                q / Esc         Quit (prompts to save if modified)
                """);
        if (!parsed.isEmpty()) {
            sections.set(sections.size() - 1, parsed.get(0));
        }
        return sections;
    }

    // -- Rendering --

    void renderStandalone(Frame frame) {
        boolean detailsVisible = showDetails && !diffOverlay.isActive() && !helpOverlay.isActive();
        var zones = Layout.vertical()
                .constraints(
                        Constraint.length(3),
                        Constraint.fill(),
                        detailsVisible ? Constraint.length(1) : Constraint.length(0),
                        detailsVisible ? Constraint.percentage(30) : Constraint.length(0),
                        Constraint.length(3))
                .split(frame.area());

        renderHeader(frame, zones.get(0));
        lastContentHeight = zones.get(1).height();

        if (helpOverlay.isActive()) {
            helpOverlay.render(frame, zones.get(1));
        } else if (diffOverlay.isActive()) {
            diffOverlay.render(frame, zones.get(1), " POM Changes ");
        } else {
            renderConflicts(frame, zones.get(1));
            if (detailsVisible) {
                renderDivider(frame, zones.get(2));
                renderDetails(frame, zones.get(3));
            }
        }

        renderInfoBar(frame, zones.get(4));
    }

    private void renderHeader(Frame frame, Rect area) {
        String title = loading ? "Collecting Conflicts…" : "Conflict Resolution";
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" " + projectGav).bold().cyan());
        if (isDirty()) {
            spans.add(theme.dirtyIndicator());
        }
        if (loading) {
            spans.add(Span.raw("  Collecting " + loadedCount + "/" + totalModules + "…")
                    .dim());
        }
        renderStandaloneHeader(frame, area, title, Line.from(spans));
    }

    /**
     * Renders the conflicts section: header with counts and a table listing each dependency group,
     * an icon for conflict status, resolved version, and requested versions; when no conflicts are
     * present renders a centered success message.
     *
     * @param frame the terminal frame to draw into
     * @param area the rectangular region to render the conflicts widget
     */
    private void renderConflicts(Frame frame, Rect area) {
        Block block;
        if (loading) {
            block = Block.builder()
                    .title(" Conflicts ")
                    .borderType(BorderType.ROUNDED)
                    .borderStyle(borderStyle())
                    .build();
            String loadingText = "Collecting conflicts… " + loadedCount + "/" + totalModules;
            String currentModule = loadingModule;
            if (currentModule != null) {
                loadingText += "\n" + currentModule;
            }
            Paragraph loadingMsg = Paragraph.builder()
                    .text(loadingText)
                    .block(block)
                    .centered()
                    .build();
            frame.renderWidget(loadingMsg, area);
            clearTableArea();
            return;
        }

        long conflictCount =
                conflicts.stream().filter(ConflictGroup::hasConflict).count();
        List<ConflictGroup> displayed = displayedConflicts();
        String title = showAll
                ? " Conflicts (" + conflictCount + " actual, " + conflicts.size() + " total) "
                : " Conflicts (" + conflictCount + ") ";

        block = Block.builder()
                .title(title)
                .borderType(BorderType.ROUNDED)
                .borderStyle(borderStyle())
                .build();

        if (displayed.isEmpty()) {
            Paragraph empty = Paragraph.builder()
                    .text("No version conflicts detected ✓")
                    .block(block)
                    .centered()
                    .build();
            frame.renderWidget(empty, area);
            clearTableArea();
            return;
        }

        Row header = sortState.decorateHeader(
                List.of("status", "groupId:artifactId", "resolved", "versions requested"),
                Style.create().bold().yellow());

        List<Row> rows = new ArrayList<>();
        for (var group : displayed) {
            String icon = group.hasConflict() ? "⚠" : "✓";
            String versions = group.entries.stream()
                    .map(e -> e.requestedVersion)
                    .distinct()
                    .collect(Collectors.joining(", "));

            Style style = group.hasConflict() ? Style.create() : Style.create().dim();

            rows.add(Row.from(icon, group.ga, group.resolvedVersion(), versions).style(style));
        }

        Table table = Table.builder()
                .header(header)
                .rows(rows)
                .widths(
                        Constraint.length(6), Constraint.percentage(40),
                        Constraint.percentage(15), Constraint.fill())
                .highlightStyle(Style.create().reversed().bold())
                .highlightSymbol("▸ ")
                .block(block)
                .build();

        setTableArea(area, block);
        frame.renderStatefulWidget(table, area, tableState);
    }

    /**
     * Render the details pane for the currently selected conflict group.
     *
     * <p>If no valid row is selected, the method returns without rendering.
     * When a group is selected, a rounded, yellow-tinted block titled
     * "<ga> — Dependency Paths" is rendered containing a table with one row
     * per entry. Each row presents the requested version in bold, the text
     * " via " dimmed, the dependency path in dark gray, and—when the
     * requested version differs from the resolved version—an appended
     * "→ resolved <resolvedVersion>" fragment in yellow.
     */
    private void renderDetails(Frame frame, Rect area) {
        List<ConflictGroup> displayed = displayedConflicts();
        int sel = selectedIndex();
        if (sel < 0 || sel >= displayed.size()) return;

        var group = displayed.get(sel);
        Block block = Block.builder()
                .title(" " + group.ga + " — Dependency Paths ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().yellow())
                .build();

        List<Row> rows = new ArrayList<>();
        for (var entry : group.entries) {
            List<Span> spans = new ArrayList<>();
            spans.add(Span.raw(entry.requestedVersion).bold());
            spans.add(Span.raw(" via ").dim());
            spans.add(Span.raw(entry.path).fg(theme.detailSeparatorColor()));
            if (!entry.requestedVersion.equals(entry.resolvedVersion)) {
                spans.add(Span.raw(" → resolved " + entry.resolvedVersion).fg(theme.statusWarningColor()));
            }
            rows.add(Row.from(Cell.from(Line.from(spans))));
        }

        Table table = Table.builder()
                .rows(rows)
                .widths(Constraint.fill())
                .block(block)
                .build();

        TableState detailState = new TableState();
        frame.renderStatefulWidget(table, area, detailState);
    }

    private void renderInfoBar(Frame frame, Rect area) {
        var rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.length(1))
                .split(area);

        List<Span> statusSpans = new ArrayList<>();
        statusSpans.add(
                Span.raw(" " + status).fg(pendingQuit ? theme.statusWarningColor() : theme.standaloneStatusColor()));
        frame.renderWidget(Paragraph.from(Line.from(statusSpans)), rows.get(1));

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
            spans.add(Span.raw(":Navigate  "));
            spans.add(Span.raw("Enter").bold());
            spans.add(Span.raw(":Details  "));
            spans.add(Span.raw("t").bold());
            spans.add(Span.raw(showAll ? ":Conflicts only  " : ":Show all  "));
            spans.add(Span.raw("p").bold());
            spans.add(Span.raw(":Pin version  "));
            spans.add(Span.raw("d").bold());
            spans.add(Span.raw(":Diff  "));
            spans.add(Span.raw("h").bold());
            spans.add(Span.raw(":Help  "));
            spans.add(Span.raw("q").bold());
            spans.add(Span.raw(":Quit"));
        }

        frame.renderWidget(Paragraph.from(Line.from(spans)), rows.get(2));
    }
}
