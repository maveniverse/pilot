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
            view = (view == View.DECLARED) ? View.TRANSITIVE : View.DECLARED;
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
            dep.pulledBy = null;
            declared.add(new DepEntry(dep.groupId, dep.artifactId, dep.classifier, dep.version, dep.scope, true));
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
     * Cycle the scope of the currently selected declared dependency and persist to POM.
     */
    private void changeScope() {
        if (view != View.DECLARED) return;
        int sel = selectedIndex();
        if (sel < 0 || sel >= declared.size()) return;
        var dep = declared.get(sel);

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
                    .flatMap(deps -> deps.childElements("dependency")
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
        var zones = Layout.vertical()
                .constraints(Constraint.length(3), Constraint.fill(), Constraint.length(3))
                .split(frame.area());

        renderHeader(frame, zones.get(0));
        lastContentHeight = zones.get(1).height();
        if (diffOverlay.isActive()) {
            diffOverlay.render(frame, zones.get(1), " POM Changes ");
        } else {
            renderTable(frame, zones.get(1));
        }
        renderInfoBar(frame, zones.get(2));
    }

    private void renderHeader(Frame frame, Rect area) {
        Block block = Block.builder()
                .title(" Pilot \u2014 Dependency Overview ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().cyan())
                .build();

        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" " + projectGav).bold().cyan());
        spans.add(Span.raw("  "));

        String declaredLabel = "Declared: " + declared.size();
        if (bytecodeAnalyzed) {
            long unused = declared.stream()
                    .filter(d -> d.usageStatus == DependencyUsageAnalyzer.UsageStatus.UNUSED)
                    .count();
            if (unused > 0) {
                declaredLabel += " (" + unused + " unused)";
            }
        }
        spans.add(Span.raw("[" + (view == View.DECLARED ? HIGHLIGHT_SYMBOL : "  ") + declaredLabel + "]")
                .fg(view == View.DECLARED ? Color.YELLOW : Color.DARK_GRAY));
        spans.add(Span.raw("  "));

        String transitiveLabel = "Transitive: " + transitive.size();
        if (bytecodeAnalyzed) {
            long used = transitive.stream()
                    .filter(d -> d.usageStatus == DependencyUsageAnalyzer.UsageStatus.USED)
                    .count();
            if (used > 0) {
                transitiveLabel += " (" + used + " used)";
            }
        }
        spans.add(Span.raw("[" + (view == View.TRANSITIVE ? HIGHLIGHT_SYMBOL : "  ") + transitiveLabel + "]")
                .fg(view == View.TRANSITIVE ? Color.YELLOW : Color.DARK_GRAY));

        if (dirty) {
            spans.add(Span.raw("  [modified]").fg(Color.YELLOW));
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
                spans.add(Span.raw("s").bold());
                spans.add(Span.raw(":Change scope  "));
            } else {
                spans.add(Span.raw("a/Enter").bold());
                spans.add(Span.raw(":Add to POM  "));
            }
            spans.add(Span.raw("d").bold());
            spans.add(Span.raw(":Diff  "));
            spans.add(Span.raw("q").bold());
            spans.add(Span.raw(":Quit"));
        }

        frame.renderWidget(Paragraph.from(Line.from(spans)), rows.get(2));
    }
}
