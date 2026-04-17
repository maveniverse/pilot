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
import dev.tamboui.text.Text;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import eu.maveniverse.domtrip.Document;
import eu.maveniverse.domtrip.maven.AlignOptions;
import eu.maveniverse.domtrip.maven.Coordinates;
import eu.maveniverse.domtrip.maven.PomEditor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Interactive TUI showing declared vs transitive dependency overview.
 */
public class DependenciesTui extends ToolPanel {

    private static final String COMPILE_SCOPE = "compile";
    private static final String COL_GA = "groupId:artifactId";
    private static final String COL_VERSION = "version";
    private static final String COL_SCOPE = "scope";
    private static final String SECTION_DEPENDENCIES = "dependencies";
    private static final String TARGET_DEPENDENCY = "dependency";
    private static final String HIGHLIGHT_SYMBOL = "▸ ";

    // Maven 3 scopes (modelVersion 4.0.0)
    private static final List<String> SCOPES_3X = List.of(COMPILE_SCOPE, "provided", "runtime", "test");
    // Maven 4 scopes (modelVersion 4.1.0+), see org.apache.maven.api.DependencyScope
    private static final List<String> SCOPES_4X =
            List.of(COMPILE_SCOPE, "compile-only", "provided", "runtime", "test", "test-only", "test-runtime");

    public static class DepEntry {
        final String groupId;
        final String artifactId;
        final String classifier;
        final String version;
        public String scope;
        final boolean declared;
        String pulledBy; // for transitive deps: who pulled this in
        public DependencyUsageAnalyzer.UsageStatus usageStatus; // set after bytecode analysis
        public Map<String, List<String>> usedMembers; // class -> list of member references (methods/fields)
        public int totalClasses; // total classes provided by this dep
        public List<String> spiServices; // SPI service interfaces provided by this dep

        /**
         * Creates a DepEntry representing a dependency row in the TUI.
         *
         * @param groupId    the dependency groupId
         * @param artifactId the dependency artifactId
         * @param classifier the dependency classifier; null is normalized to an empty string
         * @param version    the dependency version; null is normalized to an empty string
         * @param scope      the dependency scope; null is normalized to "compile"
         * @param declared   true if this entry corresponds to a declared dependency in the POM UI, false for a transitive entry
         */
        public DepEntry(
                String groupId, String artifactId, String classifier, String version, String scope, boolean declared) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.classifier = classifier != null ? classifier : "";
            this.version = version != null ? version : "";
            this.scope = scope != null ? scope : COMPILE_SCOPE;
            this.declared = declared;
        }

        /**
         * Format the dependency's group and artifact (including the classifier when present).
         *
         * @return `groupId:artifactId:classifier` if `classifier` is non-empty, otherwise `groupId:artifactId`.
         */
        public String ga() {
            return hasClassifier() ? groupId + ":" + artifactId + ":" + classifier : groupId + ":" + artifactId;
        }

        /**
         * Build the dependency coordinates string including version.
         *
         * @return the coordinates in the form `groupId:artifactId:version`, with an extra `:classifier` inserted between `artifactId` and `version` when the classifier is non-empty
         */
        String gav() {
            return ga() + ":" + version;
        }

        /**
         * Indicates whether this dependency includes a classifier.
         *
         * @return {@code true} if the {@code classifier} is not empty, {@code false} otherwise.
         */
        boolean hasClassifier() {
            return !classifier.isEmpty();
        }
    }

    /**
     * Create and register a declared dependency entry.
     *
     * Adds a DepEntry marked as declared to the provided list and records its
     * groupId:artifactId[:classifier] identifier in the provided set.
     *
     * @param declaredGAs set where the dependency's GA (`groupId:artifactId[:classifier]`) will be recorded
     * @param declared list to append the new declared DepEntry to
     * @param groupId dependency groupId
     * @param artifactId dependency artifactId
     * @param classifier dependency classifier (may be empty)
     * @param version dependency version
     * @param scope dependency scope
     */
    public static void addDeclaredEntry(
            Set<String> declaredGAs,
            List<DepEntry> declared,
            String groupId,
            String artifactId,
            String classifier,
            String version,
            String scope) {
        var entry = new DepEntry(groupId, artifactId, classifier, version, scope, true);
        declaredGAs.add(entry.ga());
        declared.add(entry);
    }

    /**
     * Traverse the resolved dependency tree and add transitive dependencies to {@code result}.
     *
     * Each discovered transitive dependency that is not present in {@code declaredGAs} and
     * has not been seen before (by its GA) is appended to {@code result}. When a dependency
     * is discovered from a parent node, its {@code pulledBy} field is set to the parent's
     * {@code groupId:artifactId}.
     *
     * @param node the current dependency tree node to traverse
     * @param declaredGAs set of declared dependency GAs (groupId:artifactId[:classifier]) to exclude
     * @param seen set used to deduplicate discovered GAs; entries are added as they are recorded
     * @param result list that will receive new transitive {@code DepEntry} instances
     */
    public static void collectTransitive(
            DependencyTreeModel.TreeNode node, Set<String> declaredGAs, Set<String> seen, List<DepEntry> result) {
        for (DependencyTreeModel.TreeNode child : node.children) {
            var entry =
                    new DepEntry(child.groupId, child.artifactId, child.classifier, child.version, child.scope, false);
            if (!declaredGAs.contains(entry.ga()) && seen.add(entry.ga())) {
                entry.pulledBy = node.ga();
                result.add(entry);
            }
            collectTransitive(child, declaredGAs, seen, result);
        }
    }

    /** Entry representing a managed dependency or BOM import in the Managed tab. */
    public static class ManagedEntry {
        final String groupId;
        final String artifactId;
        final String version;
        final String scope;
        final String type;
        final boolean isBom;
        final String source; // "own" or "inherited"

        public ManagedEntry(
                String groupId, String artifactId, String version, String scope, String type, String source) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version != null ? version : "";
            this.scope = scope != null ? scope : COMPILE_SCOPE;
            this.type = type != null ? type : "jar";
            this.isBom = "pom".equals(this.type) && "import".equals(this.scope);
            this.source = source;
        }

        String ga() {
            return groupId + ":" + artifactId;
        }
    }

    private enum View {
        DECLARED,
        TRANSITIVE,
        MANAGED
    }

    private final List<DepEntry> declared;
    private final List<DepEntry> transitive;
    private final List<ManagedEntry> managed;
    private final String projectGav;
    private final boolean bytecodeAnalyzed;
    private final TableState tableState = new TableState();

    private View view = View.DECLARED;
    private String status;
    private boolean pendingQuit;
    private final DiffOverlay diffOverlay = new DiffOverlay();
    private int lastContentHeight;

    /** Returns the current in-memory POM content (package-private for testing). */
    String currentPomContent() {
        return editSession.currentXml();
    }

    /**
     * Get the currently selected table row index.
     *
     * @return `-1` if no row is selected, otherwise the selected row index.
     */
    private int selectedIndex() {
        Integer sel = tableState.selected();
        return sel != null ? sel : -1;
    }

    /**
     * Create a new DependenciesTui bound to the given dependency lists and POM metadata.
     *
     * Initializes the backing declared and transitive dependency lists, the POM file path,
     * and the displayed project GAV. Sets the status message to "<declared.size()> declared, <transitive.size()> transitive dependencies".
     * If the declared list is non-empty, selects the first row in the table.
     *
     * @param declared   the list of declared (direct) dependencies shown in the Declared view
     * @param transitive the list of transitive dependencies shown in the Transitive view
     * @param pomPath    filesystem path to the target POM file that will be edited when modifying dependencies
     * @param projectGav the project GAV string displayed in the UI header
     */
    public DependenciesTui(List<DepEntry> declared, List<DepEntry> transitive, String pomPath, String projectGav) {
        this(declared, transitive, List.of(), new PomEditSession(Path.of(pomPath)), projectGav, false);
    }

    /** Standalone / test constructor with bytecode analysis flag. */
    public DependenciesTui(
            List<DepEntry> declared,
            List<DepEntry> transitive,
            String pomPath,
            String projectGav,
            boolean bytecodeAnalyzed) {
        this(declared, transitive, List.of(), new PomEditSession(Path.of(pomPath)), projectGav, bytecodeAnalyzed);
    }

    /** Panel-mode constructor — uses a shared PomEditSession from the shell. */
    public DependenciesTui(
            List<DepEntry> declared,
            List<DepEntry> transitive,
            PomEditSession session,
            String projectGav,
            boolean bytecodeAnalyzed) {
        this(declared, transitive, List.of(), session, projectGav, bytecodeAnalyzed);
    }

    /** Full constructor with managed dependencies. */
    public DependenciesTui(
            List<DepEntry> declared,
            List<DepEntry> transitive,
            List<ManagedEntry> managed,
            PomEditSession session,
            String projectGav,
            boolean bytecodeAnalyzed) {
        this.editSession = session;
        this.declared = declared;
        this.transitive = transitive;
        this.managed = managed;
        this.projectGav = projectGav;
        this.bytecodeAnalyzed = bytecodeAnalyzed;
        this.sortState = new SortState(sortColumnCount());
        updateStatus();
        if (!declared.isEmpty()) {
            tableState.select(0);
        }
    }

    @Override
    boolean onSessionChanged() {
        if (editSession == null) return true;
        Set<String> existingGAs = managed.stream().map(ManagedEntry::ga).collect(java.util.stream.Collectors.toSet());
        for (PomEditSession.Change change : editSession.changes()) {
            if (change.type() != PomEditSession.ChangeType.ADD
                    || !"managed".equals(change.target())
                    || existingGAs.contains(change.ga())) {
                continue;
            }
            String[] parts = change.ga().split(":");
            if (parts.length == 2) {
                String version = parseVersionFromDescription(change.description());
                managed.add(new ManagedEntry(parts[0], parts[1], version, COMPILE_SCOPE, "jar", "own"));
                existingGAs.add(change.ga());
            }
        }
        return true;
    }

    private static String parseVersionFromDescription(String desc) {
        int idx = desc.indexOf("pinned to ");
        if (idx >= 0) return desc.substring(idx + 10);
        idx = desc.indexOf("added ");
        if (idx >= 0) {
            int toIdx = desc.indexOf(" to ");
            if (toIdx > idx) return desc.substring(idx + 6, toIdx);
        }
        return "";
    }

    private void updateStatus() {
        if (bytecodeAnalyzed) {
            long unused = declared.stream()
                    .filter(d -> d.usageStatus == DependencyUsageAnalyzer.UsageStatus.UNUSED)
                    .count();
            long usedTransitive = transitive.stream()
                    .filter(d -> d.usageStatus == DependencyUsageAnalyzer.UsageStatus.USED)
                    .count();
            this.status = declared.size() + " declared (" + unused + " unused), " + transitive.size() + " transitive ("
                    + usedTransitive + " used)";
        } else {
            this.status = declared.size() + " declared, " + transitive.size() + " transitive dependencies";
        }
    }

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
        return "Deps";
    }

    @Override
    public void render(Frame frame, Rect area) {
        if (diffOverlay.isActive()) {
            diffOverlay.render(frame, area, " POM Changes ");
            lastContentHeight = area.height();
            return;
        }
        Rect contentArea = renderTabBar(frame, area);
        if (view == View.MANAGED) {
            lastContentHeight = contentArea.height();
            renderManagedTable(frame, contentArea);
        } else {
            var zones = Layout.vertical()
                    .constraints(Constraint.fill(), Constraint.length(1), Constraint.percentage(25))
                    .split(contentArea);
            lastContentHeight = zones.get(0).height();
            renderTable(frame, zones.get(0), null);
            renderDivider(frame, zones.get(1));
            renderDetails(frame, zones.get(2));
        }
    }

    @Override
    public boolean handleKeyEvent(KeyEvent key) {
        // Diff overlay (panel mode — standalone handles before delegation)
        if (diffOverlay.isActive()) {
            if (key.isKey(KeyCode.ESCAPE)) {
                diffOverlay.close();
                return true;
            }
            if (diffOverlay.handleScrollKey(key, lastContentHeight)) return true;
            return true; // consume all keys during overlay
        }

        if (handleSearchInput(key)) return true;
        if (handleSortInput(key)) return true;

        if (key.isUp()) {
            tableState.selectPrevious();
            return true;
        }
        if (key.isDown()) {
            tableState.selectNext(currentListSize());
            return true;
        }
        if (TableNavigation.handlePageKeys(key, tableState, currentListSize(), lastContentHeight)) {
            return true;
        }

        if (key.isKey(KeyCode.TAB)) {
            view = TabBar.next(view, View.values());
            tableState.select(0);
            return true;
        }

        if (view == View.MANAGED) {
            if (key.isCharIgnoreCase('x')) {
                removeManaged();
                return true;
            }
            return false;
        }

        if (key.isKey(KeyCode.ENTER)) {
            fixSelected();
            return true;
        }

        if (key.isCharIgnoreCase('x')) {
            removeDeclared();
            return true;
        }

        if (key.isCharIgnoreCase('a')) {
            addTransitive();
            return true;
        }

        if (key.isCharIgnoreCase('c')) {
            changeScope();
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
        if (handleMouseTabBar(mouse)) return true;
        if (view != View.MANAGED && handleMouseSortHeader(mouse, List.of(getTableWidths()))) return true;
        if (mouse.isClick()) {
            int row = mouseToTableRow(mouse, currentListSize(), tableState);
            if (row >= 0) {
                tableState.select(row);
                return true;
            }
        }
        if (mouse.isScroll()) {
            int size = currentListSize();
            if (size == 0) return false;
            int sel = tableState.selected();
            if (mouse.kind() == MouseEventKind.SCROLL_UP) {
                tableState.select(Math.max(0, sel - 1));
            } else {
                tableState.select(Math.min(size - 1, sel + 1));
            }
            return true;
        }
        return false;
    }

    @Override
    int subViewCount() {
        return 3;
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
        sortState = new SortState(sortColumnCount());
    }

    @Override
    List<String> subViewNames() {
        return List.of(
                "Declared: " + declared.size(), "Transitive: " + transitive.size(), "Managed: " + managed.size());
    }

    @Override
    public String status() {
        String search = searchStatus();
        if (search != null) {
            return searchMode ? search : status + " — " + search;
        }
        return status;
    }

    @Override
    public List<Span> keyHints() {
        List<Span> searchHints = searchKeyHints();
        if (!searchHints.isEmpty()) {
            return searchHints;
        }
        List<Span> spans = new ArrayList<>();
        if (diffOverlay.isActive()) {
            spans.add(Span.raw("↑↓").bold());
            spans.add(Span.raw(":Scroll  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Close  "));
        } else {
            spans.add(Span.raw("↑↓").bold());
            spans.add(Span.raw(":Nav  "));
            if (view == View.DECLARED) {
                spans.add(Span.raw("x/Enter").bold());
                spans.add(Span.raw(":Delete  "));
                spans.add(Span.raw("c").bold());
                spans.add(Span.raw(":Scope  "));
            } else if (view == View.TRANSITIVE) {
                spans.add(Span.raw("a/Enter").bold());
                spans.add(Span.raw(":Add  "));
                spans.add(Span.raw("c").bold());
                spans.add(Span.raw(":Scope  "));
            } else {
                spans.add(Span.raw("x").bold());
                spans.add(Span.raw(":Remove  "));
            }
            spans.addAll(sortKeyHints());
            spans.add(Span.raw("/").bold());
            spans.add(Span.raw(":Search  "));
            spans.add(Span.raw("d").bold());
            spans.add(Span.raw(":Diff  "));
        }
        return spans;
    }

    @Override
    public List<HelpOverlay.Section> helpSections() {
        return HelpOverlay.parse("""
                ## Dependency Analysis
                Uses bytecode analysis to compare what is declared
                in the POM against what is actually used in code.
                Declared view: dependencies in the POM that are not
                referenced in compiled bytecode. These may be safe
                to remove (but check for runtime/reflection use).
                Transitive view: classes used in your code that come
                from transitive dependencies. These should be declared
                explicitly to avoid breakage when transitives change.

                ## Table Columns
                status          unused (declared) or undeclared (transitive)
                dependency      groupId:artifactId
                scope           Maven scope (compile, test, runtime, provided)
                classifier      Artifact classifier (e.g. test-fixtures)

                ## Colors
                yellow          Issue flag — unused or undeclared dependency
                cyan            Header and view tab indicators
                dim             Informational / secondary text

                ## Dependencies Actions
                ↑ / ↓           Move selection up / down
                Tab             Switch Declared / Transitive / Managed view
                x / Enter       Remove selected (Declared view)
                a / Enter       Add to POM (Transitive view)
                x               Remove managed entry (Managed view)
                c               Cycle scope (compile → test → runtime → ...)
                s / S           Sort by column / reverse direction
                d               Preview POM changes as unified diff
                """);
    }

    private List<DepEntry> currentList() {
        return view == View.DECLARED ? declared : transitive;
    }

    private int currentListSize() {
        return switch (view) {
            case DECLARED -> declared.size();
            case TRANSITIVE -> transitive.size();
            case MANAGED -> managed.size();
        };
    }

    private int sortColumnCount() {
        int count = 3; // ga, version, scope
        if (bytecodeAnalyzed) count++; // icon column
        if (view == View.TRANSITIVE) count++; // pulled by column
        return count;
    }

    private List<Function<DepEntry, String>> sortExtractors() {
        List<Function<DepEntry, String>> extractors = new ArrayList<>();
        if (bytecodeAnalyzed) {
            extractors.add(this::usageIcon);
        }
        extractors.add(DepEntry::ga);
        extractors.add(dep -> dep.version);
        extractors.add(dep -> dep.scope);
        if (view == View.TRANSITIVE) {
            extractors.add(dep -> dep.pulledBy != null ? dep.pulledBy : "");
        }
        return extractors;
    }

    @Override
    protected void onSortChanged() {
        if (view != View.MANAGED) {
            sortState.sort(currentList(), sortExtractors());
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
        if (view == View.MANAGED) {
            for (int i = 0; i < managed.size(); i++) {
                if (managedMatchesSearch(managed.get(i), query)) {
                    searchMatches.add(i);
                }
            }
        } else {
            var deps = currentList();
            for (int i = 0; i < deps.size(); i++) {
                if (depMatchesSearch(deps.get(i), query)) {
                    searchMatches.add(i);
                }
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

    private boolean depMatchesSearch(DepEntry dep, String query) {
        String searchable = dep.groupId + ":" + dep.artifactId + ":" + dep.version;
        if (dep.scope != null) searchable += ":" + dep.scope;
        return searchable.toLowerCase().contains(query);
    }

    private boolean managedMatchesSearch(ManagedEntry entry, String query) {
        String searchable = entry.groupId + ":" + entry.artifactId + ":" + entry.version + ":" + entry.source;
        return searchable.toLowerCase().contains(query);
    }

    /**
     * Perform the context-appropriate mutation for the currently selected dependency.
     *
     * If the view is DECLARED, removes the selected declared dependency from the POM;
     * if the view is TRANSITIVE, adds the selected transitive dependency to the POM.
     */
    private void fixSelected() {
        if (view == View.DECLARED) {
            removeDeclared();
        } else {
            addTransitive();
        }
    }

    /**
     * Remove the currently selected dependency from the declared list and persist that removal to the POM.
     *
     * If no row is selected or the selection is out of range this method does nothing. When a valid declared
     * dependency is selected it is removed from the POM on disk, removed from the in-memory `declared` list,
     * the status message is updated to reflect success, and the table selection is adjusted to remain within bounds.
     * If an error occurs while modifying the POM the status is set to a failure message containing the exception text.
     */
    private void removeDeclared() {
        int sel = selectedIndex();
        if (sel < 0 || sel >= declared.size()) return;
        var dep = declared.get(sel);

        try {
            editSession.beforeMutation();
            editSession
                    .editor()
                    .dependencies()
                    .deleteDependency(Coordinates.of(dep.groupId, dep.artifactId, dep.version));
            editSession.recordChange(
                    PomEditSession.ChangeType.REMOVE, TARGET_DEPENDENCY, dep.ga(), "removed", SECTION_DEPENDENCIES);
            declared.remove(sel);
            updateStatus();
            if (sel >= declared.size() && !declared.isEmpty()) {
                tableState.select(declared.size() - 1);
            }
        } catch (Exception e) {
            status = "Failed to remove: " + e.getMessage();
        }
    }

    /**
     * Add the selected transitive dependency to the project's POM and mark it as declared in memory.
     *
     * Updates the POM to declare the dependency, moves the entry from the transitive list into the declared list
     * (clearing its `pulledBy`), updates the status message, and adjusts the table selection if the previous index
     * becomes out of range.
     */
    private void addTransitive() {
        int sel = selectedIndex();
        if (sel < 0 || sel >= transitive.size()) return;
        var dep = transitive.get(sel);

        try {
            editSession.beforeMutation();
            Coordinates coords = (dep.hasClassifier())
                    ? Coordinates.of(dep.groupId, dep.artifactId, dep.version, dep.classifier, "jar")
                    : Coordinates.of(dep.groupId, dep.artifactId, dep.version);
            PomEditor editor = editSession.editor();
            if (!COMPILE_SCOPE.equals(dep.scope)) {
                AlignOptions detected = editor.dependencies().detectConventions();
                AlignOptions options = AlignOptions.builder()
                        .versionStyle(detected.versionStyle())
                        .versionSource(detected.versionSource())
                        .namingConvention(detected.namingConvention())
                        .scope(dep.scope)
                        .build();
                editor.dependencies().addAligned(coords, options);
            } else {
                editor.dependencies().addAligned(coords);
            }
            editSession.recordChange(
                    PomEditSession.ChangeType.ADD,
                    TARGET_DEPENDENCY,
                    dep.ga(),
                    "added from transitive",
                    SECTION_DEPENDENCIES);

            // Move from transitive to declared
            transitive.remove(sel);
            var promoted = new DepEntry(dep.groupId, dep.artifactId, dep.classifier, dep.version, dep.scope, true);
            promoted.usageStatus = dep.usageStatus;
            promoted.usedMembers = dep.usedMembers;
            promoted.totalClasses = dep.totalClasses;
            declared.add(promoted);
            updateStatus();
            if (sel >= transitive.size() && !transitive.isEmpty()) {
                tableState.select(transitive.size() - 1);
            }
        } catch (Exception e) {
            status = "Failed to add: " + e.getMessage();
        }
    }

    private void removeManaged() {
        int sel = selectedIndex();
        if (sel < 0 || sel >= managed.size()) return;
        var entry = managed.get(sel);
        if (!"own".equals(entry.source)) {
            status = "Cannot remove inherited managed dependency";
            return;
        }
        try {
            editSession.beforeMutation();
            Coordinates coords = entry.isBom
                    ? Coordinates.of(entry.groupId, entry.artifactId, entry.version, "", "pom")
                    : Coordinates.of(entry.groupId, entry.artifactId, entry.version);
            editSession.editor().dependencies().deleteManagedDependency(coords);
            editSession.recordChange(
                    PomEditSession.ChangeType.REMOVE,
                    "managed",
                    entry.ga(),
                    entry.isBom ? "removed BOM import" : "removed managed dependency",
                    SECTION_DEPENDENCIES);
            managed.remove(sel);
            updateStatus();
            if (sel >= managed.size() && !managed.isEmpty()) {
                tableState.select(managed.size() - 1);
            }
        } catch (Exception e) {
            status = "Failed to remove: " + e.getMessage();
        }
    }

    private boolean isOk(DepEntry dep) {
        // For declared deps, USED = ok. For transitive deps, UNUSED = ok (not directly referenced).
        return dep.declared
                ? dep.usageStatus == DependencyUsageAnalyzer.UsageStatus.USED
                : dep.usageStatus == DependencyUsageAnalyzer.UsageStatus.UNUSED;
    }

    private String usageIcon(DepEntry dep) {
        if (dep.usageStatus == null) {
            return " ";
        }
        if (dep.usageStatus == DependencyUsageAnalyzer.UsageStatus.UNDETERMINED) {
            return "?";
        }
        return isOk(dep) ? "✓" : "✗";
    }

    private Style usageRowStyle(DepEntry dep) {
        if (dep.usageStatus == null || dep.usageStatus == DependencyUsageAnalyzer.UsageStatus.UNDETERMINED) {
            return dep.usageStatus == null ? Style.create() : theme.usageUndetermined();
        }
        return isOk(dep) ? Style.create() : theme.usageIssue();
    }

    /**
     * Cycle the scope of the currently selected dependency and persist to POM (declared) or update in-memory (transitive).
     */
    private void changeScope() {
        var deps = currentList();
        int sel = selectedIndex();
        if (sel < 0 || sel >= deps.size()) return;
        var dep = deps.get(sel);

        if (view == View.TRANSITIVE) {
            List<String> scopes = getScopesForModel(editSession.editor().document());
            if (!scopes.contains(dep.scope)) {
                scopes = new ArrayList<>(scopes);
                scopes.add(dep.scope);
            }
            int current = scopes.indexOf(dep.scope);
            dep.scope = scopes.get((current + 1) % scopes.size());
            return;
        }

        try {
            PomEditor editor = editSession.editor();
            Document doc = editor.document();
            List<String> scopes = getScopesForModel(doc);
            if (!scopes.contains(dep.scope)) {
                scopes = new ArrayList<>(scopes);
                scopes.add(dep.scope);
            }
            int current = scopes.indexOf(dep.scope);
            String newScope = scopes.get((current + 1) % scopes.size());
            String oldScope = dep.scope;
            Coordinates coords = Coordinates.of(dep.groupId, dep.artifactId, dep.version);

            doc.root()
                    .childElement("dependencies")
                    .flatMap(depsEl -> depsEl.childElements("dependency")
                            .filter(coords.predicateGA())
                            .findFirst())
                    .ifPresentOrElse(
                            el -> {
                                try {
                                    editSession.beforeMutation();
                                    if (COMPILE_SCOPE.equals(newScope)) {
                                        el.childElement(COL_SCOPE).ifPresent(el::removeChild);
                                    } else {
                                        editor.updateOrCreateChildElement(el, COL_SCOPE, newScope);
                                    }
                                    dep.scope = newScope;
                                    editSession.recordChange(
                                            PomEditSession.ChangeType.MODIFY,
                                            TARGET_DEPENDENCY,
                                            dep.ga(),
                                            "scope: " + oldScope + " → " + newScope,
                                            SECTION_DEPENDENCIES);
                                    updateStatus();
                                } catch (Exception e) {
                                    status = "Failed to change scope: " + e.getMessage();
                                }
                            },
                            () -> status = "Dependency " + dep.ga() + " not found in POM");
        } catch (Exception e) {
            status = "Failed to change scope: " + e.getMessage();
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

    private List<HelpOverlay.Section> buildHelpStandalone() {
        List<HelpOverlay.Section> sections = new ArrayList<>(helpSections());
        sections.addAll(HelpOverlay.parse("""
                ## General
                """ + NAV_KEYS + """
                Tab             Switch between Declared, Transitive, and Managed views
                d               Preview POM changes as a unified diff
                h               Toggle this help screen
                q / Esc         Quit (prompts to save if modified)
                """));
        return sections;
    }

    private void toggleDiffView() {
        long changes = diffOverlay.open(editSession.originalContent(), editSession.currentXml());
        status = changes == 0 ? "No changes to show" : changes + " line(s) changed";
    }

    /**
     * Determine which scope list to use based on the POM's modelVersion.
     */
    private static List<String> getScopesForModel(Document doc) {
        String modelVersion = doc.root()
                .childElement("modelVersion")
                .map(e -> e.textContentTrimmedOr("4.0.0"))
                .orElse("4.0.0");
        return modelVersion.compareTo("4.1.0") >= 0 ? SCOPES_4X : SCOPES_3X;
    }

    /**
     * Add a dependency to the POM using convention-aligned placement.
     * Detects the project's existing conventions (managed vs inline, literal vs property,
     * property naming pattern) and follows them.
     *
     * @param pomContent the current POM XML content
     * @param groupId the dependency groupId
     * @param artifactId the dependency artifactId
     * @param version the dependency version
     * @param classifier the dependency classifier (may be null or empty)
     * @param scope the dependency scope (may be null or empty; "compile" is the default)
     * @return the updated POM XML content
     */
    static String addDependencyAligned(
            String pomContent, String groupId, String artifactId, String version, String classifier, String scope) {
        PomEditor editor = new PomEditor(Document.of(pomContent));
        Coordinates coords = (classifier != null && !classifier.isEmpty())
                ? Coordinates.of(groupId, artifactId, version, classifier, "jar")
                : Coordinates.of(groupId, artifactId, version);
        if (scope != null && !scope.isEmpty() && !COMPILE_SCOPE.equals(scope)) {
            AlignOptions detected = editor.dependencies().detectConventions();
            AlignOptions options = AlignOptions.builder()
                    .versionStyle(detected.versionStyle())
                    .versionSource(detected.versionSource())
                    .namingConvention(detected.namingConvention())
                    .scope(scope)
                    .build();
            editor.dependencies().addAligned(coords, options);
        } else {
            editor.dependencies().addAligned(coords);
        }
        return editor.toXml();
    }

    // -- Rendering --

    void renderStandalone(Frame frame) {
        boolean detailsVisible = !helpOverlay.isActive() && !diffOverlay.isActive();
        var zones = Layout.vertical()
                .constraints(
                        Constraint.length(3),
                        Constraint.fill(),
                        detailsVisible ? Constraint.percentage(25) : Constraint.length(0),
                        Constraint.length(3))
                .split(frame.area());

        renderHeader(frame, zones.get(0));
        Rect contentArea = renderStandaloneHelp(frame, zones.get(1));
        if (contentArea != null) {
            lastContentHeight = contentArea.height();
            if (diffOverlay.isActive()) {
                diffOverlay.render(frame, contentArea, " POM Changes ");
            } else if (view == View.MANAGED) {
                renderManagedTable(frame, contentArea);
            } else {
                renderTable(frame, contentArea);
                renderDetails(frame, zones.get(2));
            }
        }
        renderInfoBar(frame, zones.get(3));
    }

    private void renderHeader(Frame frame, Rect area) {
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" " + projectGav).bold().cyan());
        long unused = bytecodeAnalyzed
                ? declared.stream()
                        .filter(d -> d.usageStatus == DependencyUsageAnalyzer.UsageStatus.UNUSED)
                        .count()
                : 0;
        long used = bytecodeAnalyzed
                ? transitive.stream()
                        .filter(d -> d.usageStatus == DependencyUsageAnalyzer.UsageStatus.USED)
                        .count()
                : 0;
        String declaredLabel = "Declared: " + declared.size() + (unused > 0 ? " (" + unused + " unused)" : "");
        String transitiveLabel = "Transitive: " + transitive.size() + (used > 0 ? " (" + used + " used)" : "");
        String managedLabel = "Managed: " + managed.size();
        spans.addAll(TabBar.render(view, View.values(), v -> switch (v) {
            case DECLARED -> declaredLabel;
            case TRANSITIVE -> transitiveLabel;
            case MANAGED -> managedLabel;
        }));

        if (isDirty()) {
            spans.add(theme.dirtyIndicator());
        }

        if (!bytecodeAnalyzed) {
            spans.add(Span.raw("  ⚠ Not compiled — run 'mvn compile' for usage analysis")
                    .fg(theme.statusWarningColor())
                    .bold());
        }
        renderStandaloneHeader(frame, area, "Dependency Overview", Line.from(spans));
    }

    private void renderManagedTable(Frame frame, Rect area) {
        Block block = Block.builder()
                .title(Title.from(" Managed Dependencies (" + managed.size() + ") "))
                .borderType(BorderType.ROUNDED)
                .borderStyle(borderStyle())
                .build();

        if (managed.isEmpty()) {
            Paragraph empty = Paragraph.builder()
                    .text("No managed dependencies")
                    .block(block)
                    .centered()
                    .build();
            frame.renderWidget(empty, area);
            return;
        }

        Row header = Row.from("", COL_GA, COL_VERSION, "type", "source").style(theme.tableHeader());
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < managed.size(); i++) {
            ManagedEntry e = managed.get(i);
            boolean changed = editSession != null && editSession.isChanged(e.ga());
            String icon;
            if (changed) {
                icon = "✓";
            } else if (e.isBom) {
                icon = "B";
            } else {
                icon = " ";
            }
            String typeLabel = e.isBom ? "bom" : e.scope;
            Style rowStyle = "inherited".equals(e.source) ? Style.create().dim() : Style.create();
            if (isSearchMatch(i)) {
                rowStyle = rowStyle.bg(theme.searchHighlightBg());
            }
            rows.add(Row.from(icon, e.ga(), e.version, typeLabel, e.source).style(rowStyle));
        }

        Table table = Table.builder()
                .header(header)
                .rows(rows)
                .highlightStyle(theme.highlightStyle())
                .highlightSymbol(theme.highlightSymbol())
                .block(block)
                .widths(
                        Constraint.length(3),
                        Constraint.percentage(40),
                        Constraint.percentage(25),
                        Constraint.percentage(15),
                        Constraint.percentage(15))
                .build();

        setTableArea(area, block);
        frame.renderStatefulWidget(table, area, tableState);
    }

    private void renderTable(Frame frame, Rect area) {
        renderTable(
                frame,
                area,
                Title.from(
                        view == View.DECLARED
                                ? " Declared Dependencies (" + declared.size() + ") "
                                : " Transitive Dependencies (" + transitive.size() + ") "));
    }

    private void renderTable(Frame frame, Rect area, Title title) {

        Block.Builder blockBuilder =
                Block.builder().borderType(BorderType.ROUNDED).borderStyle(borderStyle());
        if (title != null) {
            blockBuilder.title(title);
        }
        Block block = blockBuilder.build();

        var deps = currentList();
        if (deps.isEmpty()) {
            Paragraph empty = Paragraph.builder()
                    .text("No dependencies")
                    .block(block)
                    .centered()
                    .build();
            frame.renderWidget(empty, area);
            clearTableArea();
            return;
        }

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < deps.size(); i++) {
            Row row = isSearchMatch(i) ? buildSearchRow(deps.get(i)) : buildRow(deps.get(i));
            rows.add(row);
        }

        Table table = Table.builder()
                .header(buildTableHeader())
                .rows(rows)
                .highlightStyle(theme.highlightStyle())
                .highlightSymbol(theme.highlightSymbol())
                .block(block)
                .widths(getTableWidths())
                .build();

        setTableArea(area, block);
        frame.renderStatefulWidget(table, area, tableState);
    }

    private Row buildTableHeader() {
        List<String> headers = new ArrayList<>();
        if (bytecodeAnalyzed) headers.add("");
        headers.add(COL_GA);
        headers.add(COL_VERSION);
        headers.add(COL_SCOPE);
        if (view == View.TRANSITIVE) headers.add("pulled by");
        return sortState.decorateHeader(headers, theme.tableHeader());
    }

    private Row buildRow(DepEntry dep) {
        String via = dep.pulledBy != null ? "(via " + dep.pulledBy + ")" : "";
        if (bytecodeAnalyzed) {
            String icon = usageIcon(dep);
            Row row = (view == View.DECLARED)
                    ? Row.from(icon, dep.ga(), dep.version, dep.scope)
                    : Row.from(icon, dep.ga(), dep.version, dep.scope, via);
            return row.style(usageRowStyle(dep));
        }
        return (view == View.DECLARED)
                ? Row.from(dep.ga(), dep.version, dep.scope)
                : Row.from(dep.ga(), dep.version, dep.scope, via);
    }

    private Row buildSearchRow(DepEntry dep) {
        String via = dep.pulledBy != null ? "(via " + dep.pulledBy + ")" : "";
        if (bytecodeAnalyzed) {
            String icon = usageIcon(dep);
            Row row = (view == View.DECLARED)
                    ? Row.from(icon, dep.ga(), dep.version, dep.scope)
                    : Row.from(icon, dep.ga(), dep.version, dep.scope, via);
            return row.style(usageRowStyle(dep).bg(theme.searchHighlightBg()));
        }
        Style highlight = theme.searchHighlight();
        return (view == View.DECLARED)
                ? Row.from(dep.ga(), dep.version, dep.scope).style(highlight)
                : Row.from(dep.ga(), dep.version, dep.scope, via).style(highlight);
    }

    private Constraint[] getTableWidths() {
        if (bytecodeAnalyzed) {
            return (view == View.DECLARED)
                    ? new Constraint[] {
                        Constraint.length(4),
                        Constraint.percentage(46),
                        Constraint.percentage(25),
                        Constraint.percentage(25)
                    }
                    : new Constraint[] {
                        Constraint.length(4),
                        Constraint.percentage(33),
                        Constraint.percentage(17),
                        Constraint.percentage(17),
                        Constraint.percentage(33)
                    };
        }
        return (view == View.DECLARED)
                ? new Constraint[] {Constraint.percentage(50), Constraint.percentage(25), Constraint.percentage(25)}
                : new Constraint[] {
                    Constraint.percentage(35),
                    Constraint.percentage(15),
                    Constraint.percentage(15),
                    Constraint.percentage(35)
                };
    }

    private void renderDetails(Frame frame, Rect area) {
        var deps = currentList();
        int sel = selectedIndex();
        if (sel < 0 || sel >= deps.size()) return;
        var dep = deps.get(sel);

        Block block = Block.builder()
                .title(" " + dep.gav() + " ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(theme.unfocusedBorder())
                .build();

        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" Scope: ").bold());
        spans.add(Span.raw(dep.scope));

        if (dep.pulledBy != null) {
            spans.add(Span.raw("  │ ").fg(theme.detailSeparatorColor()));
            spans.add(Span.raw("Pulled by: ").bold());
            spans.add(Span.raw(dep.pulledBy).dim());
        }

        if (dep.hasClassifier()) {
            spans.add(Span.raw("  │ ").fg(theme.detailSeparatorColor()));
            spans.add(Span.raw("Classifier: ").bold());
            spans.add(Span.raw(dep.classifier));
        }

        boolean hasSpi = dep.spiServices != null && !dep.spiServices.isEmpty();

        if (bytecodeAnalyzed && dep.usageStatus != null) {
            spans.add(Span.raw("  │ ").fg(theme.detailSeparatorColor()));
            spans.add(Span.raw("Usage: ").bold());
            int used = dep.usedMembers != null ? dep.usedMembers.size() : 0;
            String usageText;
            if (dep.usageStatus == DependencyUsageAnalyzer.UsageStatus.UNDETERMINED) {
                usageText = "Could not determine (no classes found in JAR)";
            } else if (used > 0) {
                usageText = used + " of " + dep.totalClasses + " classes referenced";
                if (!dep.declared) {
                    usageText += " (should be declared)";
                }
            } else if (hasSpi && dep.usageStatus == DependencyUsageAnalyzer.UsageStatus.USED) {
                usageText = "Used via SPI/ServiceLoader";
            } else {
                usageText = dep.declared
                        ? "0 of " + dep.totalClasses + " classes referenced (may be safe to remove)"
                        : "Not directly referenced";
            }
            Color usageColor =
                    switch (dep.usageStatus) {
                        case USED -> dep.declared ? theme.usageUsedColor() : theme.usageIssueColor();
                        case UNUSED -> dep.declared ? theme.usageIssueColor() : theme.usageUsedColor();
                        case UNDETERMINED -> theme.usageUndeterminedColor();
                    };
            spans.add(Span.raw(usageText).fg(usageColor));
        }

        List<Line> lines = new ArrayList<>();
        lines.add(Line.from(spans));

        int maxLines = Math.max(1, area.height() - 4);
        int lineCount = 0;

        if (bytecodeAnalyzed && dep.usedMembers != null && !dep.usedMembers.isEmpty()) {
            int remainingClasses = dep.usedMembers.size();
            for (var entry : dep.usedMembers.entrySet()) {
                if (lineCount >= maxLines) break;
                String className = entry.getKey();
                List<String> members = entry.getValue();
                remainingClasses--;

                int lastDot = className.lastIndexOf('.');
                String shortName = lastDot >= 0 ? className.substring(lastDot + 1) : className;
                String pkg = lastDot >= 0 ? className.substring(0, lastDot) : "";

                List<Span> classSpans = new ArrayList<>();
                classSpans.add(Span.raw(" "));
                classSpans.add(Span.raw(pkg + ".").fg(theme.detailSeparatorColor()));
                classSpans.add(Span.raw(shortName).cyan().bold());
                if (!members.isEmpty()) {
                    classSpans.add(Span.raw(": ").fg(theme.detailSeparatorColor()));
                    int maxMembers = Math.min(members.size(), 6);
                    for (int i = 0; i < maxMembers; i++) {
                        if (i > 0) classSpans.add(Span.raw(", ").fg(theme.detailSeparatorColor()));
                        classSpans.add(Span.raw(members.get(i)).dim());
                    }
                    if (members.size() > maxMembers) {
                        classSpans.add(Span.raw(" +" + (members.size() - maxMembers) + " more")
                                .fg(theme.detailSeparatorColor()));
                    }
                }
                lines.add(Line.from(classSpans));
                lineCount++;
            }
            if (remainingClasses > 0) {
                lines.add(Line.from(
                        Span.raw("  ... +" + remainingClasses + " more classes").dim()));
                lineCount++;
            }
        }

        if (hasSpi && lineCount < maxLines) {
            List<Span> spiHeader = new ArrayList<>();
            spiHeader.add(Span.raw(" SPI services: ").bold().fg(theme.spiColor()));
            for (int i = 0; i < dep.spiServices.size(); i++) {
                if (lineCount >= maxLines) break;
                if (i > 0) spiHeader.add(Span.raw(", ").fg(theme.detailSeparatorColor()));
                String svc = dep.spiServices.get(i);
                int lastDot = svc.lastIndexOf('.');
                String shortName = lastDot >= 0 ? svc.substring(lastDot + 1) : svc;
                String pkg = lastDot >= 0 ? svc.substring(0, lastDot) : "";
                spiHeader.add(Span.raw(pkg + ".").fg(theme.detailSeparatorColor()));
                spiHeader.add(Span.raw(shortName).fg(theme.spiColor()));
            }
            lines.add(Line.from(spiHeader));
        }

        Paragraph details =
                Paragraph.builder().text(Text.from(lines)).block(block).build();
        frame.renderWidget(details, area);
    }

    private void renderInfoBar(Frame frame, Rect area) {
        var rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.length(1))
                .split(area);

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
            spans.add(Span.raw(":Navigate  "));
            spans.add(Span.raw("Tab").bold());
            spans.add(Span.raw(":Switch view  "));
            if (view == View.DECLARED) {
                spans.add(Span.raw("x/Enter").bold());
                spans.add(Span.raw(":Delete  "));
            } else {
                spans.add(Span.raw("a/Enter").bold());
                spans.add(Span.raw(":Add to POM  "));
            }
            spans.add(Span.raw("s").bold());
            spans.add(Span.raw(":Change scope  "));
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
