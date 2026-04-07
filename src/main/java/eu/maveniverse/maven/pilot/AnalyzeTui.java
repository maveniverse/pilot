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
import java.util.List;
import java.util.Set;
import org.eclipse.aether.graph.DependencyNode;

/**
 * Interactive TUI showing declared vs transitive dependency overview.
 */
class AnalyzeTui {

    static class DepEntry {
        final String groupId;
        final String artifactId;
        final String classifier;
        final String version;
        final String scope;
        final boolean declared;
        String pulledBy; // for transitive deps: who pulled this in

        DepEntry(String groupId, String artifactId, String classifier, String version, String scope, boolean declared) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.classifier = classifier != null ? classifier : "";
            this.version = version != null ? version : "";
            this.scope = scope != null ? scope : "compile";
            this.declared = declared;
        }

        String ga() {
            return hasClassifier() ? groupId + ":" + artifactId + ":" + classifier : groupId + ":" + artifactId;
        }

        String gav() {
            return ga() + ":" + version;
        }

        boolean hasClassifier() {
            return !classifier.isEmpty();
        }
    }

    /**
     * Create a declared dependency entry and register it.
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
     * Recursively collect transitive dependencies from the resolved dependency tree.
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
    private final TableState tableState = new TableState();

    private View view = View.DECLARED;
    private String status;

    private TuiRunner runner;

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
     * Create a new AnalyzeTui bound to the given dependency lists and POM metadata.
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
    AnalyzeTui(List<DepEntry> declared, List<DepEntry> transitive, String pomPath, String projectGav) {
        this.declared = declared;
        this.transitive = transitive;
        this.pomPath = pomPath;
        this.projectGav = projectGav;
        this.status = declared.size() + " declared, " + transitive.size() + " transitive dependencies";
        if (!declared.isEmpty()) {
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
            configured.run();
        } finally {
            configured.close();
        }
    }

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

        if (key.isCharIgnoreCase('d')) {
            removeDeclared();
            return true;
        }

        if (key.isCharIgnoreCase('a')) {
            addTransitive();
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
            String pomContent = Files.readString(Path.of(pomPath));
            PomEditor editor = new PomEditor(Document.of(pomContent));
            editor.dependencies().deleteDependency(Coordinates.of(dep.groupId, dep.artifactId, dep.version));
            Files.writeString(Path.of(pomPath), editor.toXml());
            declared.remove(sel);
            status = "Removed " + dep.ga() + " from POM";
            if (sel >= declared.size() && !declared.isEmpty()) {
                tableState.select(declared.size() - 1);
            }
        } catch (Exception e) {
            status = "Failed to remove: " + e.getMessage();
        }
    }

    /**
     * Adds the currently selected transitive dependency to the project's POM and moves it into the declared list.
     *
     * Updates the POM file to declare the dependency, removes the entry from the transitive list, clears its
     * `pulledBy` field, appends a new declared `DepEntry`, updates the status message, and adjusts the table
     * selection if the previously selected index becomes out of range.
     */
    private void addTransitive() {
        int sel = selectedIndex();
        if (sel < 0 || sel >= transitive.size()) return;
        var dep = transitive.get(sel);

        try {
            String pomContent = Files.readString(Path.of(pomPath));
            PomEditor editor = new PomEditor(Document.of(pomContent));
            editor.dependencies().updateDependency(true, Coordinates.of(dep.groupId, dep.artifactId, dep.version));
            Files.writeString(Path.of(pomPath), editor.toXml());

            // Move from transitive to declared
            transitive.remove(sel);
            dep.pulledBy = null;
            declared.add(new DepEntry(dep.groupId, dep.artifactId, dep.classifier, dep.version, dep.scope, true));
            status = "Added " + dep.ga() + " to POM";
            if (sel >= transitive.size() && !transitive.isEmpty()) {
                tableState.select(transitive.size() - 1);
            }
        } catch (Exception e) {
            status = "Failed to add: " + e.getMessage();
        }
    }

    // -- Rendering --

    void render(Frame frame) {
        var zones = Layout.vertical()
                .constraints(Constraint.length(3), Constraint.fill(), Constraint.length(3))
                .split(frame.area());

        renderHeader(frame, zones.get(0));
        renderTable(frame, zones.get(1));
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
        spans.add(Span.raw("[" + (view == View.DECLARED ? "\u25B8 " : "  ") + "Declared: " + declared.size() + "]")
                .fg(view == View.DECLARED ? Color.YELLOW : Color.DARK_GRAY));
        spans.add(Span.raw("  "));
        spans.add(
                Span.raw("[" + (view == View.TRANSITIVE ? "\u25B8 " : "  ") + "Transitive: " + transitive.size() + "]")
                        .fg(view == View.TRANSITIVE ? Color.YELLOW : Color.DARK_GRAY));

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

        Row header;
        if (view == View.DECLARED) {
            header = Row.from("groupId:artifactId", "version", "scope")
                    .style(Style.create().bold().yellow());
        } else {
            header = Row.from("groupId:artifactId", "version", "scope", "pulled by")
                    .style(Style.create().bold().yellow());
        }

        List<Row> rows = new ArrayList<>();
        for (var dep : deps) {
            if (view == View.DECLARED) {
                rows.add(Row.from(dep.ga(), dep.version, dep.scope));
            } else {
                String via = dep.pulledBy != null ? "(via " + dep.pulledBy + ")" : "";
                rows.add(Row.from(dep.ga(), dep.version, dep.scope, via));
            }
        }

        Table.Builder tableBuilder = Table.builder()
                .header(header)
                .rows(rows)
                .highlightStyle(Style.create().reversed().bold())
                .highlightSymbol("\u25B8 ")
                .block(block);

        if (view == View.DECLARED) {
            tableBuilder.widths(Constraint.percentage(50), Constraint.percentage(25), Constraint.percentage(25));
        } else {
            tableBuilder.widths(
                    Constraint.percentage(35),
                    Constraint.percentage(15),
                    Constraint.percentage(15),
                    Constraint.percentage(35));
        }

        frame.renderStatefulWidget(tableBuilder.build(), area, tableState);
    }

    private void renderInfoBar(Frame frame, Rect area) {
        var rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.length(1))
                .split(area);

        // Status
        List<Span> statusSpans = new ArrayList<>();
        statusSpans.add(Span.raw(" " + status).fg(Color.GREEN));
        frame.renderWidget(Paragraph.from(Line.from(statusSpans)), rows.get(1));

        // Key bindings
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" "));
        spans.add(Span.raw("\u2191\u2193").bold());
        spans.add(Span.raw(":Navigate  "));
        spans.add(Span.raw("Tab").bold());
        spans.add(Span.raw(":Switch view  "));
        if (view == View.DECLARED) {
            spans.add(Span.raw("d/Enter").bold());
            spans.add(Span.raw(":Remove from POM  "));
        } else {
            spans.add(Span.raw("a/Enter").bold());
            spans.add(Span.raw(":Add to POM  "));
        }
        spans.add(Span.raw("q").bold());
        spans.add(Span.raw(":Quit"));

        frame.renderWidget(Paragraph.from(Line.from(spans)), rows.get(2));
    }
}
