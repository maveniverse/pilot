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
import eu.maveniverse.domtrip.maven.AlignOptions;
import eu.maveniverse.domtrip.maven.Coordinates;
import eu.maveniverse.domtrip.maven.PomEditor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.aether.graph.DependencyNode;

/**
 * Interactive TUI showing declared vs transitive dependency overview.
 */
class DependenciesTui {

    private static final String COMPILE_SCOPE = "compile";
    private static final String COL_GA = "groupId:artifactId";
    private static final String COL_VERSION = "version";
    private static final String COL_SCOPE = "scope";
    private static final String HIGHLIGHT_SYMBOL = "\u25B8 ";

    // Maven 3 scopes (modelVersion 4.0.0)
    private static final List<String> SCOPES_3X = List.of(COMPILE_SCOPE, "provided", "runtime", "test");
    // Maven 4 scopes (modelVersion 4.1.0+), see org.apache.maven.api.DependencyScope
    private static final List<String> SCOPES_4X =
            List.of(COMPILE_SCOPE, "compile-only", "provided", "runtime", "test", "test-only", "test-runtime");

    static class DepEntry {
        final String groupId;
        final String artifactId;
        final String classifier;
        final String version;
        String scope;
        final boolean declared;
        String pulledBy; // for transitive deps: who pulled this in
        DependencyUsageAnalyzer.UsageStatus usageStatus; // set after bytecode analysis
        Map<String, List<String>> usedMembers; // class -> list of member references (methods/fields)
        int totalClasses; // total classes provided by this dep
        List<String> spiServices; // SPI service interfaces provided by this dep

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
        DepEntry(String groupId, String artifactId, String classifier, String version, String scope, boolean declared) {
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
        String ga() {
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
    static void addDeclaredEntry(
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
    static void collectTransitive(
            DependencyNode node, Set<String> declaredGAs, Set<String> seen, List<DepEntry> result) {
        for (DependencyNode child : node.getChildren()) {
            if (child.getDependency() == null) continue;
            var art = child.getDependency().getArtifact();
            var entry = new DepEntry(
                    art.getGroupId(),
                    art.getArtifactId(),
                    art.getClassifier(),
                    art.getVersion(),
                    child.getDependency().getScope(),
                    false);
            if (!declaredGAs.contains(entry.ga()) && seen.add(entry.ga())) {
                if (node.getDependency() != null) {
                    entry.pulledBy = node.getDependency().getArtifact().getGroupId() + ":"
                            + node.getDependency().getArtifact().getArtifactId();
                }
                result.add(entry);
            }
            collectTransitive(child, declaredGAs, seen, result);
        }
    }

    private enum View {
        DECLARED,
        TRANSITIVE
    }

    private final List<DepEntry> declared;
    private final List<DepEntry> transitive;
    private final String pomPath;
    private final String projectGav;
    private final boolean bytecodeAnalyzed;
    private final TableState tableState = new TableState();

    private View view = View.DECLARED;
    private String status;
    private final String originalPomContent;
    private final PomEditor editor;
    private boolean dirty;
    private boolean pendingQuit;
    private final DiffOverlay diffOverlay = new DiffOverlay();
    private final HelpOverlay helpOverlay = new HelpOverlay();
    private int lastContentHeight;
    private TuiRunner runner;

    /** Returns the current in-memory POM content (package-private for testing). */
    String currentPomContent() {
        return editor.toXml();
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
    DependenciesTui(List<DepEntry> declared, List<DepEntry> transitive, String pomPath, String projectGav) {
        this(declared, transitive, pomPath, projectGav, false);
    }

    DependenciesTui(
            List<DepEntry> declared,
            List<DepEntry> transitive,
            String pomPath,
            String projectGav,
            boolean bytecodeAnalyzed) {
        this.declared = declared;
        this.transitive = transitive;
        this.pomPath = pomPath;
        this.projectGav = projectGav;
        this.bytecodeAnalyzed = bytecodeAnalyzed;
        String pom;
        try {
            pom = Files.readString(Path.of(pomPath));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read POM: " + pomPath, e);
        }
        this.originalPomContent = pom;
        this.editor = new PomEditor(Document.of(pom));
        updateStatus();
        if (!declared.isEmpty()) {
            tableState.select(0);
        }
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

        // Diff overlay mode — Esc closes overlay first
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

        if (key.isCtrlC() || key.isCharIgnoreCase('q') || key.isKey(KeyCode.ESCAPE)) {
            requestQuit();
            return true;
        }

        if (key.isUp()) {
            tableState.selectPrevious();
            return true;
        }
        if (key.isDown()) {
            tableState.selectNext(currentList().size());
            return true;
        }

        if (key.isKey(KeyCode.TAB)) {
            view = TabBar.next(view, View.values());
            tableState.select(0);
            return true;
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

        if (key.isCharIgnoreCase('s')) {
            changeScope();
            return true;
        }

        if (key.isCharIgnoreCase('d')) {
            toggleDiffView();
            return true;
        }

        if (key.isCharIgnoreCase('h')) {
            helpOverlay.open(buildHelp());
            return true;
        }

        return false;
    }

    private List<DepEntry> currentList() {
        return view == View.DECLARED ? declared : transitive;
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
            editor.dependencies().deleteDependency(Coordinates.of(dep.groupId, dep.artifactId, dep.version));
            dirty = true;
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
            dirty = true;

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
        return isOk(dep) ? "\u2713" : "\u2717";
    }

    private Style usageRowStyle(DepEntry dep) {
        if (dep.usageStatus == null || dep.usageStatus == DependencyUsageAnalyzer.UsageStatus.UNDETERMINED) {
            return Style.create().fg(dep.usageStatus == null ? null : Color.DARK_GRAY);
        }
        return isOk(dep) ? Style.create() : Style.create().fg(Color.YELLOW);
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
            List<String> scopes = getScopesForModel(editor.document());
            if (!scopes.contains(dep.scope)) {
                scopes = new ArrayList<>(scopes);
                scopes.add(dep.scope);
            }
            int current = scopes.indexOf(dep.scope);
            dep.scope = scopes.get((current + 1) % scopes.size());
            return;
        }

        try {
            Document doc = editor.document();
            List<String> scopes = getScopesForModel(doc);
            if (!scopes.contains(dep.scope)) {
                // Unknown scope (e.g. "system") — include it in the cycling list
                scopes = new ArrayList<>(scopes);
                scopes.add(dep.scope);
            }
            int current = scopes.indexOf(dep.scope);
            String newScope = scopes.get((current + 1) % scopes.size());
            Coordinates coords = Coordinates.of(dep.groupId, dep.artifactId, dep.version);

            // Find the <dependency> element matching this GA
            doc.root()
                    .childElement("dependencies")
                    .flatMap(depsEl -> depsEl.childElements("dependency")
                            .filter(coords.predicateGA())
                            .findFirst())
                    .ifPresentOrElse(
                            el -> {
                                try {
                                    if (COMPILE_SCOPE.equals(newScope)) {
                                        // Remove <scope> element — compile is the default
                                        el.childElement(COL_SCOPE).ifPresent(el::removeChild);
                                    } else {
                                        editor.updateOrCreateChildElement(el, COL_SCOPE, newScope);
                                    }
                                    dep.scope = newScope;
                                    dirty = true;
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
        if (dirty) {
            pendingQuit = true;
            status = "Save changes to POM?";
        } else {
            runner.quit();
        }
    }

    private void saveAndQuit() {
        try {
            String currentOnDisk = Files.readString(Path.of(pomPath));
            if (!currentOnDisk.equals(originalPomContent)) {
                pendingQuit = false;
                status = "POM modified externally \u2014 save aborted";
                return;
            }
            Files.writeString(Path.of(pomPath), editor.toXml());
            runner.quit();
        } catch (Exception e) {
            pendingQuit = false;
            status = "Failed to save: " + e.getMessage();
        }
    }

    private List<HelpOverlay.Section> buildHelp() {
        return List.of(
                new HelpOverlay.Section(
                        "Dependency Analysis",
                        List.of(
                                new HelpOverlay.Entry("", "Uses bytecode analysis to compare what is declared"),
                                new HelpOverlay.Entry("", "in the POM against what is actually used in code."),
                                new HelpOverlay.Entry("", ""),
                                new HelpOverlay.Entry("", "Declared view: dependencies in the POM that are not"),
                                new HelpOverlay.Entry("", "referenced in compiled bytecode. These may be safe"),
                                new HelpOverlay.Entry("", "to remove (but check for runtime/reflection use)."),
                                new HelpOverlay.Entry("", ""),
                                new HelpOverlay.Entry("", "Transitive view: classes used in your code that come"),
                                new HelpOverlay.Entry("", "from transitive dependencies. These should be declared"),
                                new HelpOverlay.Entry("", "explicitly to avoid breakage when transitives change."))),
                new HelpOverlay.Section(
                        "Table Columns",
                        List.of(
                                new HelpOverlay.Entry("status", "unused (declared) or undeclared (transitive)"),
                                new HelpOverlay.Entry("dependency", "groupId:artifactId"),
                                new HelpOverlay.Entry("scope", "Maven scope (compile, test, runtime, provided)"),
                                new HelpOverlay.Entry("classifier", "Artifact classifier (e.g. test-fixtures)"))),
                new HelpOverlay.Section(
                        "Colors",
                        List.of(
                                new HelpOverlay.Entry("yellow", "Issue flag \u2014 unused or undeclared dependency"),
                                new HelpOverlay.Entry("cyan", "Header and view tab indicators"),
                                new HelpOverlay.Entry("dim", "Informational / secondary text"))),
                new HelpOverlay.Section(
                        "Declared View Actions",
                        List.of(
                                new HelpOverlay.Entry("x / Enter", "Remove the selected unused dependency"),
                                new HelpOverlay.Entry(
                                        "s", "Cycle scope (compile \u2192 test \u2192 runtime \u2192 ...)"))),
                new HelpOverlay.Section(
                        "Transitive View Actions",
                        List.of(
                                new HelpOverlay.Entry("a / Enter", "Add the dependency to the POM"),
                                new HelpOverlay.Entry("s", "Cycle scope before adding"))),
                new HelpOverlay.Section(
                        "General",
                        List.of(
                                new HelpOverlay.Entry("\u2191 / \u2193", "Move selection up / down"),
                                new HelpOverlay.Entry("Tab", "Switch between Declared and Transitive views"),
                                new HelpOverlay.Entry("d", "Preview POM changes as a unified diff"),
                                new HelpOverlay.Entry("h", "Toggle this help screen"),
                                new HelpOverlay.Entry("q / Esc", "Quit (prompts to save if modified)"))));
    }

    private void toggleDiffView() {
        long changes = diffOverlay.open(originalPomContent, editor.toXml());
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

    void render(Frame frame) {
        boolean detailsVisible = !helpOverlay.isActive() && !diffOverlay.isActive();
        var zones = Layout.vertical()
                .constraints(
                        Constraint.length(3),
                        Constraint.fill(),
                        detailsVisible ? Constraint.percentage(25) : Constraint.length(0),
                        Constraint.length(3))
                .split(frame.area());

        renderHeader(frame, zones.get(0));
        lastContentHeight = zones.get(1).height();
        if (helpOverlay.isActive()) {
            helpOverlay.render(frame, zones.get(1));
        } else if (diffOverlay.isActive()) {
            diffOverlay.render(frame, zones.get(1), " POM Changes ");
        } else {
            renderTable(frame, zones.get(1));
            renderDetails(frame, zones.get(2));
        }
        renderInfoBar(frame, zones.get(3));
    }

    private void renderHeader(Frame frame, Rect area) {
        Block block = Block.builder()
                .title(" Pilot \u2014 Dependency Overview ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().cyan())
                .build();

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
        spans.addAll(TabBar.render(view, View.values(), v -> switch (v) {
            case DECLARED -> declaredLabel;
            case TRANSITIVE -> transitiveLabel;
        }));

        if (dirty) {
            spans.add(Span.raw("  [modified]").fg(Color.YELLOW));
        }

        if (!bytecodeAnalyzed) {
            spans.add(Span.raw("  ⚠ Not compiled — run 'mvn compile' for usage analysis")
                    .fg(Color.YELLOW)
                    .bold());
        }

        Paragraph header = Paragraph.builder()
                .text(dev.tamboui.text.Text.from(Line.from(spans)))
                .block(block)
                .build();
        frame.renderWidget(header, area);
    }

    private void renderTable(Frame frame, Rect area) {
        String title = view == View.DECLARED
                ? " Declared Dependencies (" + declared.size() + ") "
                : " Transitive Dependencies (" + transitive.size() + ") ";

        Block block = Block.builder()
                .title(title)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build();

        var deps = currentList();
        if (deps.isEmpty()) {
            Paragraph empty = Paragraph.builder()
                    .text("No dependencies")
                    .block(block)
                    .centered()
                    .build();
            frame.renderWidget(empty, area);
            return;
        }

        List<Row> rows = new ArrayList<>();
        for (var dep : deps) {
            rows.add(buildRow(dep));
        }

        Table table = Table.builder()
                .header(buildTableHeader())
                .rows(rows)
                .highlightStyle(Style.create().reversed().bold())
                .highlightSymbol(HIGHLIGHT_SYMBOL)
                .block(block)
                .widths(getTableWidths())
                .build();

        frame.renderStatefulWidget(table, area, tableState);
    }

    private Row buildTableHeader() {
        boolean showTransitiveCol = view == View.TRANSITIVE;
        Style headerStyle = Style.create().bold().yellow();
        if (bytecodeAnalyzed) {
            return showTransitiveCol
                    ? Row.from("", COL_GA, COL_VERSION, COL_SCOPE, "pulled by").style(headerStyle)
                    : Row.from("", COL_GA, COL_VERSION, COL_SCOPE).style(headerStyle);
        }
        return showTransitiveCol
                ? Row.from(COL_GA, COL_VERSION, COL_SCOPE, "pulled by").style(headerStyle)
                : Row.from(COL_GA, COL_VERSION, COL_SCOPE).style(headerStyle);
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
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build();

        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" Scope: ").bold());
        spans.add(Span.raw(dep.scope));

        if (dep.pulledBy != null) {
            spans.add(Span.raw("  \u2502 ").fg(Color.DARK_GRAY));
            spans.add(Span.raw("Pulled by: ").bold());
            spans.add(Span.raw(dep.pulledBy).dim());
        }

        if (dep.hasClassifier()) {
            spans.add(Span.raw("  \u2502 ").fg(Color.DARK_GRAY));
            spans.add(Span.raw("Classifier: ").bold());
            spans.add(Span.raw(dep.classifier));
        }

        boolean hasSpi = dep.spiServices != null && !dep.spiServices.isEmpty();

        if (bytecodeAnalyzed && dep.usageStatus != null) {
            spans.add(Span.raw("  \u2502 ").fg(Color.DARK_GRAY));
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
                        case USED -> dep.declared ? Color.GREEN : Color.YELLOW;
                        case UNUSED -> dep.declared ? Color.YELLOW : Color.GREEN;
                        case UNDETERMINED -> Color.DARK_GRAY;
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
                classSpans.add(Span.raw(pkg + ".").fg(Color.DARK_GRAY));
                classSpans.add(Span.raw(shortName).cyan().bold());
                if (!members.isEmpty()) {
                    classSpans.add(Span.raw(": ").fg(Color.DARK_GRAY));
                    int maxMembers = Math.min(members.size(), 6);
                    for (int i = 0; i < maxMembers; i++) {
                        if (i > 0) classSpans.add(Span.raw(", ").fg(Color.DARK_GRAY));
                        classSpans.add(Span.raw(members.get(i)).dim());
                    }
                    if (members.size() > maxMembers) {
                        classSpans.add(Span.raw(" +" + (members.size() - maxMembers) + " more")
                                .fg(Color.DARK_GRAY));
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
            spiHeader.add(Span.raw(" SPI services: ").bold().fg(Color.MAGENTA));
            for (int i = 0; i < dep.spiServices.size(); i++) {
                if (lineCount >= maxLines) break;
                if (i > 0) spiHeader.add(Span.raw(", ").fg(Color.DARK_GRAY));
                String svc = dep.spiServices.get(i);
                int lastDot = svc.lastIndexOf('.');
                String shortName = lastDot >= 0 ? svc.substring(lastDot + 1) : svc;
                String pkg = lastDot >= 0 ? svc.substring(0, lastDot) : "";
                spiHeader.add(Span.raw(pkg + ".").fg(Color.DARK_GRAY));
                spiHeader.add(Span.raw(shortName).fg(Color.MAGENTA));
            }
            lines.add(Line.from(spiHeader));
        }

        Paragraph details = Paragraph.builder()
                .text(dev.tamboui.text.Text.from(lines))
                .block(block)
                .build();
        frame.renderWidget(details, area);
    }

    private void renderInfoBar(Frame frame, Rect area) {
        var rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.length(1))
                .split(area);

        // Status
        List<Span> statusSpans = new ArrayList<>();
        statusSpans.add(Span.raw(" " + status).fg(pendingQuit ? Color.YELLOW : Color.GREEN));
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
            spans.add(Span.raw("\u2191\u2193").bold());
            spans.add(Span.raw(":Scroll  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Close  "));
            spans.add(Span.raw("q").bold());
            spans.add(Span.raw(":Quit"));
        } else {
            spans.add(Span.raw("\u2191\u2193").bold());
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
