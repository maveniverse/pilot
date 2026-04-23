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
    private static final String HINT_REMOVE = ":Remove  ";
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
        public final List<String> modules = new ArrayList<>(); // reactor mode: which modules have this dep
        public final List<Path> modulePomPaths = new ArrayList<>(); // reactor mode: POM paths for each module

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
        TREE,
        DECLARED,
        TRANSITIVE,
        MANAGED,
        DM_TREE,
        UNUSED_DECLARED,
        USED_TRANSITIVE
    }

    private final TreeTui treeTui;
    private final TreeTui dmTreeTui;
    private final View[] views;
    private final List<DepEntry> declared;
    private final List<DepEntry> transitive;
    private final List<ManagedEntry> managed;
    private final String projectGav;
    private final boolean bytecodeAnalyzed;
    private final boolean reactorMode;
    private final PomEditSession managementSession;
    private final java.util.function.Function<Path, PomEditSession> sessionProvider;
    private final TableState tableState = new TableState();

    private View view = View.DECLARED;
    private String status;
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
        this(declared, transitive, managed, session, null, projectGav, bytecodeAnalyzed, null);
    }

    /** Panel-mode constructor with embedded tree view and management session. */
    @SuppressWarnings("squid:S107") // delegation target for simpler constructors
    public DependenciesTui(
            List<DepEntry> declared,
            List<DepEntry> transitive,
            List<ManagedEntry> managed,
            PomEditSession session,
            PomEditSession managementSession,
            String projectGav,
            boolean bytecodeAnalyzed,
            TreeTui treeTui) {
        this(declared, transitive, managed, session, managementSession, projectGav, bytecodeAnalyzed, treeTui, null);
    }

    /** Panel-mode constructor with embedded tree view, DM tree view, and management session. */
    @SuppressWarnings("squid:S107") // delegation target for simpler constructors
    public DependenciesTui(
            List<DepEntry> declared,
            List<DepEntry> transitive,
            List<ManagedEntry> managed,
            PomEditSession session,
            PomEditSession managementSession,
            String projectGav,
            boolean bytecodeAnalyzed,
            TreeTui treeTui,
            TreeTui dmTreeTui) {
        this.editSession = session;
        this.managementSession = managementSession;
        this.sessionProvider = null;
        this.declared = declared;
        this.transitive = transitive;
        this.managed = managed;
        this.projectGav = projectGav;
        this.bytecodeAnalyzed = bytecodeAnalyzed;
        this.reactorMode = false;
        this.treeTui = treeTui;
        this.dmTreeTui = dmTreeTui;
        List<View> v = new ArrayList<>();
        if (treeTui != null) v.add(View.TREE);
        v.add(View.DECLARED);
        v.add(View.TRANSITIVE);
        v.add(View.MANAGED);
        if (dmTreeTui != null) v.add(View.DM_TREE);
        this.views = v.toArray(new View[0]);
        this.view = views[0];
        this.sortState = view != View.TREE ? new SortState(sortColumnCount()) : null;
        updateStatus();
        if (!declared.isEmpty()) {
            tableState.select(0);
        }
    }

    /** Reactor-mode constructor — aggregated dependency hygiene issues across modules. */
    public DependenciesTui(
            List<DepEntry> unusedDeclared,
            List<DepEntry> usedTransitive,
            String projectGav,
            int modulesScanned,
            int modulesSkipped,
            java.util.function.Function<Path, PomEditSession> sessionProvider,
            PomEditSession managementSession) {
        this.editSession = null;
        this.managementSession = managementSession;
        this.sessionProvider = sessionProvider;
        this.declared = unusedDeclared;
        this.transitive = usedTransitive;
        this.managed = List.of();
        this.projectGav = projectGav;
        this.bytecodeAnalyzed = true;
        this.reactorMode = true;
        this.treeTui = null;
        this.dmTreeTui = null;
        this.views = new View[] {View.UNUSED_DECLARED, View.USED_TRANSITIVE};
        this.view = views[0];
        this.sortState = new SortState(sortColumnCount());
        this.status = modulesScanned + " modules scanned"
                + (modulesSkipped > 0 ? " (" + modulesSkipped + " skipped — not compiled)" : "") + ", "
                + unusedDeclared.size() + " unused declared, " + usedTransitive.size() + " used transitive";
        if (!unusedDeclared.isEmpty()) {
            tableState.select(0);
        }
    }

    @Override
    boolean onSessionChanged() {
        if (editSession == null) return true;
        Set<String> existingGAs = managed.stream().map(ManagedEntry::ga).collect(java.util.stream.Collectors.toSet());
        for (PomEditSession.Change change : editSession.changes()) {
            if (change.type() == PomEditSession.ChangeType.REMOVE) {
                return false;
            }
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
        if (handlePendingQuit(key)) return true;

        // Diff overlay mode (standalone — panel mode handles in handleKeyEvent)
        if (diffOverlay.isActive()) {
            if (key.isKey(KeyCode.ESCAPE) || key.isCharIgnoreCase('q') || key.isCharIgnoreCase('d')) {
                diffOverlay.close();
                return true;
            }
            if (diffOverlay.handleScrollKey(key, lastContentHeight)) return true;
            if (key.isCtrlC()) {
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
        if (view == View.TREE && treeTui != null) {
            treeTui.render(frame, contentArea);
            return;
        }
        if (view == View.DM_TREE && dmTreeTui != null) {
            dmTreeTui.render(frame, contentArea);
            return;
        }
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
            if (key.isKey(KeyCode.ESCAPE) || key.isCharIgnoreCase('q') || key.isCharIgnoreCase('d')) {
                diffOverlay.close();
                return true;
            }
            if (diffOverlay.handleScrollKey(key, lastContentHeight)) return true;
            return true; // consume all keys during overlay
        }

        // Number keys switch sub-views
        if (key.code() == KeyCode.CHAR && key.character() >= '1' && key.character() <= '9') {
            int idx = key.character() - '1';
            if (idx < views.length && views[idx] != view) {
                view = views[idx];
                if (view != View.TREE && view != View.DM_TREE) {
                    tableState.select(0);
                    clearSearch();
                    sortState = new SortState(sortColumnCount());
                }
                return true;
            }
        }

        // Tree view: delegate to TreeTui
        if (view == View.TREE && treeTui != null) {
            return treeTui.handleKeyEvent(key);
        }
        if (view == View.DM_TREE && dmTreeTui != null) {
            return dmTreeTui.handleKeyEvent(key);
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

        if (view == View.MANAGED) {
            if (key.isCharIgnoreCase('x')) {
                removeManaged();
                return true;
            }
            return false;
        }

        if (reactorMode) {
            return false;
        }

        if (key.isKey(KeyCode.ENTER)) {
            fixSelected();
            return true;
        }

        if (key.isCharIgnoreCase('x') && (view == View.DECLARED || view == View.UNUSED_DECLARED)) {
            removeDeclared();
            return true;
        }

        if (key.isCharIgnoreCase('a') && (view == View.TRANSITIVE || view == View.USED_TRANSITIVE)) {
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
        if (diffOverlay.isActive()) {
            diffOverlay.handleMouseScroll(mouse, lastContentHeight);
            return true;
        }
        if (handleMouseTabBar(mouse)) return true;
        if (view == View.TREE && treeTui != null) {
            return treeTui.handleMouseEvent(mouse, area);
        }
        if (view == View.DM_TREE && dmTreeTui != null) {
            return dmTreeTui.handleMouseEvent(mouse, area);
        }
        if (view != View.MANAGED && handleMouseSortHeader(mouse, List.of(getTableWidths()))) return true;
        return handleMouseTableInteraction(mouse, currentListSize(), tableState);
    }

    @Override
    int subViewCount() {
        return views.length;
    }

    @Override
    int activeSubView() {
        for (int i = 0; i < views.length; i++) {
            if (views[i] == view) return i;
        }
        return 0;
    }

    @Override
    void setActiveSubView(int index) {
        view = views[index];
        if (view != View.TREE && view != View.DM_TREE) {
            tableState.select(0);
            clearSearch();
            sortState = new SortState(sortColumnCount());
        }
    }

    @Override
    List<String> subViewNames() {
        List<String> names = new ArrayList<>();
        for (View v : views) {
            names.add(
                    switch (v) {
                        case TREE -> "Tree: " + (treeTui != null ? treeTui.nodeCount() : 0);
                        case DECLARED -> "Declared: " + declared.size();
                        case TRANSITIVE -> "Transitive: " + transitive.size();
                        case MANAGED -> "Managed: " + managed.size();
                        case DM_TREE -> "DM Tree: " + (dmTreeTui != null ? dmTreeTui.nodeCount() : 0);
                        case UNUSED_DECLARED -> "Unused Declared: " + declared.size();
                        case USED_TRANSITIVE -> "Used Transitive: " + transitive.size();
                    });
        }
        return names;
    }

    @Override
    public String status() {
        if (view == View.TREE && treeTui != null) {
            return treeTui.status();
        }
        if (view == View.DM_TREE && dmTreeTui != null) {
            return dmTreeTui.status();
        }
        String search = searchStatus();
        if (search != null) {
            return searchMode ? search : status + " — " + search;
        }
        return status;
    }

    @Override
    public List<Span> keyHints() {
        if (view == View.TREE && treeTui != null) {
            return treeTui.keyHints();
        }
        if (view == View.DM_TREE && dmTreeTui != null) {
            return dmTreeTui.keyHints();
        }
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
            if (view == View.DECLARED || view == View.UNUSED_DECLARED) {
                spans.add(Span.raw("x/Enter").bold());
                spans.add(Span.raw(HINT_REMOVE));
            } else if (view == View.TRANSITIVE || view == View.USED_TRANSITIVE) {
                spans.add(Span.raw("a/Enter").bold());
                spans.add(Span.raw(":Add  "));
            } else {
                spans.add(Span.raw("x").bold());
                spans.add(Span.raw(HINT_REMOVE));
            }
            if (!reactorMode) {
                spans.add(Span.raw("c").bold());
                spans.add(Span.raw(":Scope  "));
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
        List<HelpOverlay.Section> sections = new ArrayList<>();
        if (treeTui != null) {
            sections.addAll(treeTui.helpSections());
        }
        sections.addAll(HelpOverlay.parse("""
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
                1-4             Switch Declared / Transitive / Managed view
                x / Enter       Remove selected (Declared view)
                a / Enter       Add to POM (Transitive view)
                x               Remove managed entry (Managed view)
                c               Cycle scope (compile → test → runtime → ...)
                s / S           Sort by column / reverse direction
                d               Preview POM changes as unified diff
                """));
        return sections;
    }

    @Override
    void close() {
        if (treeTui != null) {
            treeTui.close();
        }
    }

    @Override
    void setRunner(TuiRunner runner) {
        super.setRunner(runner);
        if (treeTui != null) {
            treeTui.setRunner(runner);
        }
    }

    @Override
    void setFocused(boolean focused) {
        super.setFocused(focused);
        if (treeTui != null) {
            treeTui.setFocused(focused);
        }
    }

    private List<DepEntry> currentList() {
        return switch (view) {
            case DECLARED, UNUSED_DECLARED -> declared;
            case TRANSITIVE, USED_TRANSITIVE -> transitive;
            default -> declared;
        };
    }

    private int currentListSize() {
        return switch (view) {
            case TREE, DM_TREE -> 0;
            case DECLARED, UNUSED_DECLARED -> declared.size();
            case TRANSITIVE, USED_TRANSITIVE -> transitive.size();
            case MANAGED -> managed.size();
        };
    }

    private int sortColumnCount() {
        if (reactorMode) return 4; // icon, ga, version, modules
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
        if (reactorMode) {
            extractors.add(dep -> String.join(", ", dep.modules));
        } else {
            extractors.add(dep -> dep.scope);
            if (view == View.TRANSITIVE) {
                extractors.add(dep -> dep.pulledBy != null ? dep.pulledBy : "");
            }
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
        if (view == View.DECLARED || view == View.UNUSED_DECLARED) {
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
            Coordinates coords = dep.hasClassifier()
                    ? Coordinates.of(dep.groupId, dep.artifactId, dep.version, dep.classifier, "jar")
                    : Coordinates.of(dep.groupId, dep.artifactId, dep.version);
            if (reactorMode && sessionProvider != null) {
                for (Path pomPath : dep.modulePomPaths) {
                    PomEditSession session = sessionProvider.apply(pomPath);
                    session.beforeMutation();
                    session.editor().dependencies().deleteDependency(coords);
                    session.recordChange(
                            PomEditSession.ChangeType.REMOVE,
                            TARGET_DEPENDENCY,
                            dep.ga(),
                            "removed",
                            SECTION_DEPENDENCIES);
                }
            } else if (editSession != null) {
                editSession.beforeMutation();
                editSession.editor().dependencies().deleteDependency(coords);
                editSession.recordChange(
                        PomEditSession.ChangeType.REMOVE, TARGET_DEPENDENCY, dep.ga(), "removed", SECTION_DEPENDENCIES);
            }
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
            Coordinates coords = (dep.hasClassifier())
                    ? Coordinates.of(dep.groupId, dep.artifactId, dep.version, dep.classifier, "jar")
                    : Coordinates.of(dep.groupId, dep.artifactId, dep.version);
            if (reactorMode && sessionProvider != null) {
                for (Path pomPath : dep.modulePomPaths) {
                    PomEditSession session = sessionProvider.apply(pomPath);
                    addDependencyToSession(session, coords, dep.scope);
                    session.recordChange(
                            PomEditSession.ChangeType.ADD,
                            TARGET_DEPENDENCY,
                            dep.ga(),
                            "added from transitive",
                            SECTION_DEPENDENCIES);
                }
            } else if (editSession != null) {
                addDependencyToSession(editSession, coords, dep.scope);
                editSession.recordChange(
                        PomEditSession.ChangeType.ADD,
                        TARGET_DEPENDENCY,
                        dep.ga(),
                        "added from transitive",
                        SECTION_DEPENDENCIES);
            }

            // Move from transitive to declared
            transitive.remove(sel);
            if (!reactorMode) {
                var promoted = new DepEntry(dep.groupId, dep.artifactId, dep.classifier, dep.version, dep.scope, true);
                promoted.usageStatus = dep.usageStatus;
                promoted.usedMembers = dep.usedMembers;
                promoted.totalClasses = dep.totalClasses;
                declared.add(promoted);
            }
            updateStatus();
            if (sel >= transitive.size() && !transitive.isEmpty()) {
                tableState.select(transitive.size() - 1);
            }
        } catch (Exception e) {
            status = "Failed to add: " + e.getMessage();
        }
    }

    private void addDependencyToSession(PomEditSession session, Coordinates coords, String scope) {
        session.beforeMutation();
        PomEditor editor = session.editor();
        AlignOptions detected = editor.dependencies().detectConventions();
        // TODO: replace with editor.dependencies().findManagedVersion(coords) != null
        //  when DomTrip exposes it (https://github.com/maveniverse/domtrip/issues/215)
        boolean hadManagedEntry = editor.document()
                .root()
                .childElement("dependencyManagement")
                .flatMap(dm -> dm.childElement(SECTION_DEPENDENCIES))
                .flatMap(deps -> deps.childElements(TARGET_DEPENDENCY)
                        .filter(coords.predicateGATC())
                        .findFirst())
                .isPresent();
        AlignOptions.Builder builder = AlignOptions.builder()
                .versionStyle(detected.versionStyle())
                .versionSource(detected.versionSource())
                .namingConvention(detected.namingConvention());
        if (!COMPILE_SCOPE.equals(scope)) {
            builder.scope(scope);
        }
        editor.dependencies().addAligned(coords, builder.build());
        // If MANAGED style: the managed entry and version property belong in the
        // management POM (parent), not the local module POM.
        if (detected.versionStyle() == AlignOptions.VersionStyle.MANAGED && !hadManagedEntry) {
            // Remove the local managed entry that addAligned just created
            editor.dependencies().deleteManagedDependency(coords);
            // Clean up empty <dependencyManagement> wrapper left behind
            editor.document().root().childElement("dependencyManagement").ifPresent(dm -> {
                boolean empty = dm.childElement("dependencies")
                        .map(deps ->
                                deps.childElements("dependency").findFirst().isEmpty())
                        .orElse(true);
                if (empty) {
                    editor.document().root().removeChild(dm);
                }
            });
            // Add the managed entry (and version property) to the management POM
            PomEditSession mgmt = managementSession != null ? managementSession : session;
            session.addManagedDependencyAligned(coords, mgmt);
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

    @Override
    protected void onStatusChange(String message) {
        this.status = message;
    }

    @Override
    protected void onPendingQuitCancelled() {
        status = "Quit cancelled";
    }

    private List<HelpOverlay.Section> buildHelpStandalone() {
        List<HelpOverlay.Section> sections = new ArrayList<>(helpSections());
        sections.addAll(HelpOverlay.parse("""
                ## General
                """ + NAV_KEYS + """
                1-4             Switch between Declared, Transitive, and Managed views
                d               Preview POM changes as a unified diff
                h               Toggle this help screen
                q / Esc         Quit (prompts to save if modified)
                """));
        return sections;
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
        AlignOptions detected = editor.dependencies().detectConventions();
        AlignOptions.Builder builder = AlignOptions.builder()
                .versionStyle(detected.versionStyle())
                .versionSource(detected.versionSource())
                .namingConvention(detected.namingConvention());
        if (scope != null && !scope.isEmpty() && !COMPILE_SCOPE.equals(scope)) {
            builder.scope(scope);
        }
        editor.dependencies().addAligned(coords, builder.build());
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
        renderStandaloneInfoBar(frame, zones.get(3));
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
        spans.addAll(TabBar.render(view, views, v -> switch (v) {
            case TREE -> "Tree: " + (treeTui != null ? treeTui.nodeCount() : 0);
            case DECLARED -> declaredLabel;
            case TRANSITIVE -> transitiveLabel;
            case UNUSED_DECLARED -> "Unused Declared: " + declared.size();
            case USED_TRANSITIVE -> "Used Transitive: " + transitive.size();
            case MANAGED -> managedLabel;
            case DM_TREE -> "DM Tree: " + (dmTreeTui != null ? dmTreeTui.nodeCount() : 0);
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
        if (reactorMode) {
            headers.add("modules");
        } else {
            headers.add(COL_SCOPE);
            if (view == View.TRANSITIVE) headers.add("pulled by");
        }
        return sortState.decorateHeader(headers, theme.tableHeader());
    }

    private Row buildRow(DepEntry dep) {
        if (reactorMode) {
            String icon = usageIcon(dep);
            String modules = String.join(", ", dep.modules);
            return Row.from(icon, dep.ga(), dep.version, modules);
        }
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
        if (reactorMode) {
            String icon = usageIcon(dep);
            String modules = String.join(", ", dep.modules);
            return Row.from(icon, dep.ga(), dep.version, modules).style(theme.searchHighlight());
        }
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
        if (reactorMode) {
            return new Constraint[] {
                Constraint.length(4), Constraint.percentage(35), Constraint.percentage(15), Constraint.percentage(46)
            };
        }
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
            String usageText;
            if (dep.usageStatus == DependencyUsageAnalyzer.UsageStatus.UNDETERMINED) {
                usageText = "Could not determine (no classes found in JAR)";
            } else if (dep.usageStatus == DependencyUsageAnalyzer.UsageStatus.USED) {
                int usedCount = dep.usedMembers != null ? dep.usedMembers.size() : 0;
                String classInfo = dep.totalClasses > 0 ? " (" + usedCount + "/" + dep.totalClasses + " classes)" : "";
                usageText = dep.declared
                        ? "Directly referenced" + classInfo
                        : "Directly referenced" + classInfo + " — should be declared";
            } else {
                usageText =
                        dep.declared ? "Not directly referenced (may be safe to remove)" : "Not directly referenced";
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

        if (!dep.modules.isEmpty()) {
            List<Span> moduleSpans = new ArrayList<>();
            moduleSpans.add(Span.raw(" Modules (" + dep.modules.size() + "): ").bold());
            for (int i = 0; i < dep.modules.size(); i++) {
                if (i > 0) moduleSpans.add(Span.raw(", ").fg(theme.detailSeparatorColor()));
                moduleSpans.add(Span.raw(dep.modules.get(i)).cyan());
            }
            lines.add(Line.from(moduleSpans));
        }

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

    @Override
    protected List<Span> standaloneKeyHints() {
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw("↑↓").bold());
        spans.add(Span.raw(":Navigate  "));
        spans.add(Span.raw("1-" + views.length).bold());
        spans.add(Span.raw(":Switch view  "));
        if (view == View.DECLARED || view == View.UNUSED_DECLARED) {
            spans.add(Span.raw("x/Enter").bold());
            spans.add(Span.raw(":Delete  "));
        } else if (view == View.TRANSITIVE || view == View.USED_TRANSITIVE) {
            spans.add(Span.raw("a/Enter").bold());
            spans.add(Span.raw(":Add to POM  "));
        } else if (view == View.MANAGED) {
            spans.add(Span.raw("x").bold());
            spans.add(Span.raw(HINT_REMOVE));
        }
        spans.add(Span.raw("c").bold());
        spans.add(Span.raw(":Change scope  "));
        spans.add(Span.raw("d").bold());
        spans.add(Span.raw(":Diff  "));
        spans.add(Span.raw("h").bold());
        spans.add(Span.raw(":Help  "));
        spans.add(Span.raw("q").bold());
        spans.add(Span.raw(":Quit"));
        return spans;
    }
}
