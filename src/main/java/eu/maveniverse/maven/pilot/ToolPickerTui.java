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

/**
 * Tool selection TUI for the pilot launcher.
 *
 * <p>Displays a list of available tools and lets the user select one.
 * Returns the selected tool name or {@code null} if the user quits.</p>
 */
class ToolPickerTui {

    record Tool(String name, String description, boolean aggregatable) {}

    private static final List<Tool> ALL_TOOLS = List.of(
            new Tool("tree", "Browse dependency tree", false),
            new Tool("dependencies", "Analyze declared vs used dependencies", false),
            new Tool("pom", "Browse effective POM", false),
            new Tool("align", "Align dependency conventions", true),
            new Tool("updates", "Check for dependency updates", true),
            new Tool("conflicts", "Detect version conflicts", true),
            new Tool("audit", "Security audit of dependencies", true),
            new Tool("search", "Search Maven Central", false));

    private final String contextLabel;
    private final boolean isReactor;
    private final boolean isParentSelected;
    private final List<Tool> tools;
    private final TableState tableState = new TableState();
    private final HelpOverlay helpOverlay = new HelpOverlay();
    private String selectedTool;

    ToolPickerTui(String contextLabel, boolean isReactor) {
        this(contextLabel, isReactor, false);
    }

    ToolPickerTui(String contextLabel, boolean isReactor, boolean isParentSelected) {
        this.contextLabel = contextLabel;
        this.isReactor = isReactor;
        this.isParentSelected = isParentSelected;
        this.tools = isParentSelected
                ? ALL_TOOLS.stream().filter(t -> !"dependencies".equals(t.name)).toList()
                : ALL_TOOLS;
        tableState.select(0);
    }

    String pick() throws Exception {
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
        return selectedTool;
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

        if (key.isUp()) {
            tableState.selectPrevious();
            return true;
        }
        if (key.isDown()) {
            tableState.selectNext(tools.size());
            return true;
        }

        if (key.isKey(KeyCode.ENTER)) {
            Integer sel = tableState.selected();
            if (sel != null && sel >= 0 && sel < tools.size()) {
                selectedTool = tools.get(sel).name;
                runner.quit();
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
            renderTools(frame, zones.get(1));
        }
        renderInfoBar(frame, zones.get(2));
    }

    private void renderHeader(Frame frame, Rect area) {
        Block block = Block.builder()
                .title(" Pilot \u2014 select tool ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().cyan())
                .build();

        Paragraph header = Paragraph.builder()
                .text(dev.tamboui.text.Text.from(
                        Line.from(Span.raw(" " + contextLabel).bold().cyan())))
                .block(block)
                .build();
        frame.renderWidget(header, area);
    }

    private void renderTools(Frame frame, Rect area) {
        Block block = Block.builder()
                .title(" Tools (" + tools.size() + ") ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build();

        Row header = Row.from("tool", "description", "scope")
                .style(Style.create().bold().yellow());

        List<Row> rows = new ArrayList<>();
        for (Tool tool : tools) {
            String scope = "";
            if (isReactor && !"search".equals(tool.name)) {
                if (tool.aggregatable && isParentSelected) {
                    scope = "subtree";
                } else {
                    scope = "module";
                }
            }
            rows.add(Row.from(tool.name, tool.description, scope));
        }

        Table table = Table.builder()
                .header(header)
                .rows(rows)
                .widths(Constraint.percentage(20), Constraint.percentage(60), Constraint.percentage(20))
                .highlightStyle(Style.create().reversed().bold())
                .highlightSymbol("\u25B8 ")
                .block(block)
                .build();

        frame.renderStatefulWidget(table, area, tableState);
    }

    private void renderInfoBar(Frame frame, Rect area) {
        var rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.length(1))
                .split(area);

        List<Span> statusSpans = new ArrayList<>();
        statusSpans.add(Span.raw(" Select a tool to run").fg(Color.GREEN));
        frame.renderWidget(Paragraph.from(Line.from(statusSpans)), rows.get(1));

        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" "));
        spans.add(Span.raw("\u2191\u2193").bold());
        spans.add(Span.raw(":Navigate  "));
        spans.add(Span.raw("Enter").bold());
        spans.add(Span.raw(":Select  "));
        spans.add(Span.raw("h").bold());
        spans.add(Span.raw(":Help  "));
        spans.add(Span.raw("q").bold());
        spans.add(Span.raw(":Back"));

        frame.renderWidget(Paragraph.from(Line.from(spans)), rows.get(2));
    }

    private List<HelpOverlay.Section> buildHelp() {
        return List.of(
                new HelpOverlay.Section(
                        "Tool Picker",
                        List.of(
                                new HelpOverlay.Entry("", "Choose an analysis tool to run. In a reactor build,"),
                                new HelpOverlay.Entry("", "the 'scope' column indicates whether the tool runs"),
                                new HelpOverlay.Entry("", "on a single module or across the entire reactor."))),
                new HelpOverlay.Section(
                        "Available Tools",
                        List.of(
                                new HelpOverlay.Entry("tree", "Browse the resolved dependency tree \u2014 see all"),
                                new HelpOverlay.Entry("", "  direct and transitive dependencies with versions"),
                                new HelpOverlay.Entry(
                                        "dependencies", "Bytecode analysis of declared vs used deps \u2014"),
                                new HelpOverlay.Entry("", "  find unused or undeclared dependencies"),
                                new HelpOverlay.Entry("pom", "Browse raw and effective POM as an XML tree \u2014"),
                                new HelpOverlay.Entry("", "  see where inherited elements are defined"),
                                new HelpOverlay.Entry("align", "Restructure dependency version declarations \u2014"),
                                new HelpOverlay.Entry("", "  apply consistent property/management conventions"),
                                new HelpOverlay.Entry("updates", "Check Maven Central for newer versions \u2014"),
                                new HelpOverlay.Entry("", "  select and apply updates to POM files"),
                                new HelpOverlay.Entry("conflicts", "Detect version conflicts in the dep tree \u2014"),
                                new HelpOverlay.Entry("", "  pin resolved versions in dependencyManagement"),
                                new HelpOverlay.Entry("audit", "License compliance and CVE vulnerability scan \u2014"),
                                new HelpOverlay.Entry("", "  review licenses and known security issues"),
                                new HelpOverlay.Entry("search", "Search Maven Central interactively \u2014"),
                                new HelpOverlay.Entry("", "  browse artifacts, versions, and POM metadata"))),
                new HelpOverlay.Section(
                        "Keys",
                        List.of(
                                new HelpOverlay.Entry("\u2191 / \u2193", "Move selection up / down"),
                                new HelpOverlay.Entry("Enter", "Run the selected tool"),
                                new HelpOverlay.Entry("h", "Toggle this help screen"),
                                new HelpOverlay.Entry("q / Esc", "Go back / quit"))));
    }
}
