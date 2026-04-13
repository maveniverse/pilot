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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.project.MavenProject;

/**
 * Reusable module picker TUI for reactor-aware goals.
 *
 * <p>Displays the reactor module tree and lets the user select a module.
 * Returns a {@link PickResult} containing the selected project(s), or {@code null} if the user quits.
 * Supports single selection (Enter), run on all modules (r), and run on subtree (t).</p>
 */
class ModulePickerTui {

    record PickResult(List<MavenProject> projects, String directTool) {
        PickResult(List<MavenProject> projects) {
            this(projects, null);
        }
    }

    @FunctionalInterface
    interface ProjectAction {
        void execute(MavenProject project) throws Exception;
    }

    /**
     * Convenience loop: show the module picker, then run the given action on each selected project.
     * Repeats until the user quits the picker.
     */
    static void forEachSelected(List<MavenProject> projects, String goalName, ProjectAction action) throws Exception {
        if (projects == null || projects.isEmpty()) {
            throw new IllegalArgumentException("projects must be non-empty");
        }
        ReactorModel reactorModel = ReactorModel.build(projects);
        MavenProject root = projects.get(0);
        String reactorGav = root.getGroupId() + ":" + root.getArtifactId() + ":" + root.getVersion();

        while (true) {
            PickResult result = new ModulePickerTui(reactorModel, reactorGav, goalName).pick();
            if (result == null) break;
            for (MavenProject selected : result.projects()) {
                try {
                    action.execute(selected);
                } catch (Exception e) {
                    String gav = selected.getGroupId() + ":" + selected.getArtifactId() + ":" + selected.getVersion();
                    throw new Exception("Failed to execute " + goalName + " on " + gav + ": " + e.getMessage(), e);
                }
            }
        }
    }

    private final ReactorModel reactorModel;
    private final String reactorGav;
    private final String goalName;
    private final boolean searchEnabled;
    private final TableState tableState = new TableState();
    private final HelpOverlay helpOverlay = new HelpOverlay();
    private PickResult pickResult;
    private int lastContentHeight;

    PickResult getPickResult() {
        return pickResult;
    }

    ModulePickerTui(ReactorModel reactorModel, String reactorGav, String goalName) {
        this(reactorModel, reactorGav, goalName, false);
    }

    ModulePickerTui(ReactorModel reactorModel, String reactorGav, String goalName, boolean searchEnabled) {
        this.reactorModel = reactorModel;
        this.reactorGav = reactorGav;
        this.goalName = goalName;
        this.searchEnabled = searchEnabled;
        tableState.select(0);
    }

    /**
     * Run the picker TUI and return the selected project(s).
     *
     * @return a PickResult with the selected project(s), or null if the user quit without selecting
     */
    PickResult pick() throws Exception {
        pickResult = null;
        var configured = TuiRunner.builder()
                .eventHandler(this::handleEvent)
                .renderer(this::render)
                .tickRate(Duration.ofMillis(100))
                .build();
        try {
            configured.run();
        } finally {
            configured.close();
        }
        return pickResult;
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

        if (key.isCtrlC() || key.isCharIgnoreCase('q') || key.isKey(KeyCode.ESCAPE)) {
            runner.quit();
            return true;
        }

        List<ReactorModel.ModuleNode> visible = reactorModel.visibleNodes();

        if (key.isUp()) {
            tableState.selectPrevious();
            return true;
        }
        if (key.isDown()) {
            tableState.selectNext(visible.size());
            return true;
        }
        if (TableNavigation.handlePageKeys(key, tableState, visible.size(), lastContentHeight)) {
            return true;
        }

        if (key.isKey(KeyCode.ENTER)) {
            Integer sel = tableState.selected();
            if (sel != null && sel >= 0 && sel < visible.size()) {
                pickResult = new PickResult(List.of(visible.get(sel).project));
                runner.quit();
            }
            return true;
        }

        // Expand/collapse
        if (key.isKey(KeyCode.RIGHT) || key.isCharIgnoreCase(' ')) {
            Integer sel = tableState.selected();
            if (sel != null && sel >= 0 && sel < visible.size()) {
                var node = visible.get(sel);
                if (node.hasChildren() && !node.expanded) {
                    node.expanded = true;
                } else {
                    tableState.selectNext(visible.size());
                }
            }
            return true;
        }
        if (key.isKey(KeyCode.LEFT)) {
            Integer sel = tableState.selected();
            if (sel != null && sel >= 0 && sel < visible.size()) {
                var node = visible.get(sel);
                if (node.expanded && node.hasChildren()) {
                    node.expanded = false;
                } else {
                    // Go to parent node
                    for (int i = sel - 1; i >= 0; i--) {
                        if (visible.get(i).depth < node.depth) {
                            tableState.select(i);
                            break;
                        }
                    }
                }
            }
            return true;
        }

        if (key.isCharIgnoreCase('e')) {
            expandNode(reactorModel.root);
            return true;
        }
        if (key.isCharIgnoreCase('w')) {
            Integer sel = tableState.selected();
            ReactorModel.ModuleNode selectedNode =
                    (sel != null && sel >= 0 && sel < visible.size()) ? visible.get(sel) : null;
            collapseNode(reactorModel.root);
            reactorModel.root.expanded = true;
            if (selectedNode != null) {
                var newVisible = reactorModel.visibleNodes();
                int newIndex = newVisible.indexOf(selectedNode);
                if (newIndex < 0) {
                    // Walk backward in old visible list to find nearest visible ancestor
                    int targetDepth = selectedNode.depth;
                    for (int i = sel - 1; i >= 0; i--) {
                        if (visible.get(i).depth < targetDepth) {
                            newIndex = newVisible.indexOf(visible.get(i));
                            if (newIndex >= 0) break;
                            targetDepth = visible.get(i).depth;
                        }
                    }
                    if (newIndex < 0) newIndex = 0;
                }
                tableState.select(newIndex);
            }
            return true;
        }

        if (searchEnabled && key.isCharIgnoreCase('s')) {
            pickResult = new PickResult(List.of(), "search");
            runner.quit();
            return true;
        }

        if (key.isCharIgnoreCase('h')) {
            helpOverlay.open(buildHelp());
            return true;
        }

        return false;
    }

    void render(Frame frame) {
        var zones = Layout.vertical()
                .constraints(Constraint.length(3), Constraint.fill(), Constraint.length(3))
                .split(frame.area());

        renderHeader(frame, zones.get(0));
        lastContentHeight = zones.get(1).height();
        if (helpOverlay.isActive()) {
            helpOverlay.render(frame, zones.get(1));
        } else {
            renderModules(frame, zones.get(1));
        }
        renderInfoBar(frame, zones.get(2));
    }

    private void renderHeader(Frame frame, Rect area) {
        Block block = Block.builder()
                .title(" Pilot \u2014 " + goalName + " (select module) ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().cyan())
                .build();

        Paragraph header = Paragraph.builder()
                .text(dev.tamboui.text.Text.from(Line.from(
                        Span.raw(" " + reactorGav).bold().cyan(),
                        Span.raw("  reactor: " + reactorModel.allModules.size() + " modules")
                                .fg(Color.DARK_GRAY))))
                .block(block)
                .build();
        frame.renderWidget(header, area);
    }

    private void renderModules(Frame frame, Rect area) {
        Block block = Block.builder()
                .title(" Modules (" + reactorModel.allModules.size() + ") ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build();

        List<ReactorModel.ModuleNode> visible = reactorModel.visibleNodes();

        if (visible.isEmpty()) {
            Paragraph empty = Paragraph.builder()
                    .text("No modules")
                    .block(block)
                    .centered()
                    .build();
            frame.renderWidget(empty, area);
            return;
        }

        Row header = Row.from("module", "groupId:artifactId")
                .style(Style.create().bold().yellow());

        List<Row> rows = new ArrayList<>();
        for (var node : visible) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < node.depth; i++) {
                sb.append("  ");
            }
            if (node.hasChildren()) {
                sb.append(node.expanded ? "\u25BE " : "\u25B8 ");
            } else {
                sb.append("  ");
            }
            sb.append(node.name);

            rows.add(Row.from(sb.toString(), node.ga()));
        }

        Table table = Table.builder()
                .header(header)
                .rows(rows)
                .widths(Constraint.percentage(50), Constraint.percentage(50))
                .highlightStyle(Style.create().reversed().bold())
                .highlightSymbol("\u25B8 ")
                .block(block)
                .build();

        frame.renderStatefulWidget(table, area, tableState);
    }

    private void expandNode(ReactorModel.ModuleNode node) {
        node.expanded = true;
        for (var child : node.children) {
            expandNode(child);
        }
    }

    private void collapseNode(ReactorModel.ModuleNode node) {
        node.expanded = false;
        for (var child : node.children) {
            collapseNode(child);
        }
    }

    private List<HelpOverlay.Section> buildHelp() {
        return List.of(
                new HelpOverlay.Section(
                        "Module Picker",
                        List.of(
                                new HelpOverlay.Entry("", "Select a module from the reactor build. The tree"),
                                new HelpOverlay.Entry("", "mirrors the Maven reactor hierarchy \u2014 parent"),
                                new HelpOverlay.Entry("", "modules contain their child modules as sub-nodes."),
                                new HelpOverlay.Entry("", ""),
                                new HelpOverlay.Entry("", "After selecting a module, you will be prompted"),
                                new HelpOverlay.Entry("", "to choose which tool to run against it."),
                                new HelpOverlay.Entry("", ""),
                                new HelpOverlay.Entry("", "The two columns show the module directory name"),
                                new HelpOverlay.Entry("", "and its Maven coordinates (groupId:artifactId)."))),
                new HelpOverlay.Section("Keys", buildKeyEntries()));
    }

    private List<HelpOverlay.Entry> buildKeyEntries() {
        List<HelpOverlay.Entry> entries = new ArrayList<>();
        entries.add(new HelpOverlay.Entry("\u2191 / \u2193", "Move selection up / down"));
        entries.add(new HelpOverlay.Entry("PgUp / PgDn", "Move selection up / down by one page"));
        entries.add(new HelpOverlay.Entry("Home / End", "Jump to first / last row"));
        entries.add(new HelpOverlay.Entry("\u2190 / \u2192", "Collapse / expand tree node"));
        entries.add(new HelpOverlay.Entry("Space", "Expand node or move down"));
        entries.add(new HelpOverlay.Entry("e / w", "Expand all / collapse all"));
        entries.add(new HelpOverlay.Entry("Enter", "Select the highlighted module"));
        if (searchEnabled) {
            entries.add(new HelpOverlay.Entry("s", "Search Maven Central"));
        }
        entries.add(new HelpOverlay.Entry("h", "Toggle this help screen"));
        entries.add(new HelpOverlay.Entry("q / Esc", "Quit without selecting"));
        return entries;
    }

    private void renderInfoBar(Frame frame, Rect area) {
        var rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.length(1))
                .split(area);

        List<Span> statusSpans = new ArrayList<>();
        statusSpans.add(Span.raw(" Select a module to run " + goalName).fg(Color.GREEN));
        frame.renderWidget(Paragraph.from(Line.from(statusSpans)), rows.get(1));

        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" "));
        spans.add(Span.raw("\u2191\u2193").bold());
        spans.add(Span.raw(":Navigate  "));
        spans.add(Span.raw("\u2190\u2192").bold());
        spans.add(Span.raw(":Collapse/Expand  "));
        spans.add(Span.raw("e").bold());
        spans.add(Span.raw(":Expand All  "));
        spans.add(Span.raw("w").bold());
        spans.add(Span.raw(":Collapse All  "));
        spans.add(Span.raw("Enter").bold());
        spans.add(Span.raw(":Select  "));
        if (searchEnabled) {
            spans.add(Span.raw("s").bold());
            spans.add(Span.raw(":Search  "));
        }
        spans.add(Span.raw("h").bold());
        spans.add(Span.raw(":Help  "));
        spans.add(Span.raw("q").bold());
        spans.add(Span.raw(":Quit"));

        frame.renderWidget(Paragraph.from(Line.from(spans)), rows.get(2));
    }
}
