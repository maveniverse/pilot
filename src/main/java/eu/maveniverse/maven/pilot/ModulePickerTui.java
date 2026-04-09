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
 * Returns the selected {@link MavenProject} or {@code null} if the user quits.</p>
 */
class ModulePickerTui {

    private final ReactorModel reactorModel;
    private final String reactorGav;
    private final String goalName;
    private final TableState tableState = new TableState();
    private final HelpOverlay helpOverlay = new HelpOverlay();
    private MavenProject selectedProject;

    ModulePickerTui(ReactorModel reactorModel, String reactorGav, String goalName) {
        this.reactorModel = reactorModel;
        this.reactorGav = reactorGav;
        this.goalName = goalName;
        tableState.select(0);
    }

    /**
     * Run the picker TUI and return the selected project.
     *
     * @return the selected MavenProject, or null if the user quit without selecting
     */
    MavenProject pick() throws Exception {
        selectedProject = null;
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
        return selectedProject;
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

        if (key.isKey(KeyCode.ENTER)) {
            Integer sel = tableState.selected();
            if (sel != null && sel >= 0 && sel < visible.size()) {
                selectedProject = visible.get(sel).project;
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
                new HelpOverlay.Section(
                        "Keys",
                        List.of(
                                new HelpOverlay.Entry("\u2191 / \u2193", "Move selection up / down"),
                                new HelpOverlay.Entry("\u2190 / \u2192", "Collapse / expand tree node"),
                                new HelpOverlay.Entry("Space", "Expand node or move down"),
                                new HelpOverlay.Entry("e / w", "Expand all / collapse all"),
                                new HelpOverlay.Entry("Enter", "Select the highlighted module"),
                                new HelpOverlay.Entry("h", "Toggle this help screen"),
                                new HelpOverlay.Entry("q / Esc", "Quit without selecting"))));
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
        spans.add(Span.raw("h").bold());
        spans.add(Span.raw(":Help  "));
        spans.add(Span.raw("q").bold());
        spans.add(Span.raw(":Quit"));

        frame.renderWidget(Paragraph.from(Line.from(spans)), rows.get(2));
    }
}
