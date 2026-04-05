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
import dev.tamboui.terminal.Backend;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.tui.TuiConfig;
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

/**
 * Interactive TUI for dependency analysis.
 */
public class AnalyzeTui {

    public static class DepEntry {
        public final String groupId;
        public final String artifactId;
        public final String version;
        public final String scope;
        public final boolean declared;
        public String pulledBy; // for transitive deps: who pulled this in

        public DepEntry(String groupId, String artifactId, String version, String scope, boolean declared) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version != null ? version : "";
            this.scope = scope != null ? scope : "compile";
            this.declared = declared;
        }

        String ga() {
            return groupId + ":" + artifactId;
        }

        String gav() {
            return groupId + ":" + artifactId + ":" + version;
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
    private boolean confirmPending;
    private String confirmAction; // "remove" or "add"

    private Backend sharedBackend;

    public void setBackend(Backend backend) {
        this.sharedBackend = backend;
    }

    private TuiRunner runner;

    public AnalyzeTui(List<DepEntry> declared, List<DepEntry> transitive, String pomPath, String projectGav) {
        this.declared = declared;
        this.transitive = transitive;
        this.pomPath = pomPath;
        this.projectGav = projectGav;
        this.status = declared.size() + " declared, " + transitive.size() + " transitive dependencies";
        if (!declared.isEmpty()) {
            tableState.select(0);
        }
    }

    public void run() throws Exception {
        var configBuilder = TuiConfig.builder().tickRate(Duration.ofMillis(100));
        if (sharedBackend != null) {
            configBuilder.backend(sharedBackend).shutdownHook(false);
        }
        TuiRunner r = TuiRunner.create(configBuilder.build());
        try {
            runner = r;
            r.run(this::handleEvent, this::render);
        } finally {
            r.close();
        }
    }

    boolean handleEvent(Event event, TuiRunner runner) {
        if (!(event instanceof KeyEvent key)) {
            return true;
        }

        if (confirmPending) {
            if (key.isKey(KeyCode.ENTER) || key.isCharIgnoreCase('y')) {
                if ("remove".equals(confirmAction)) {
                    removeDeclared();
                } else {
                    addTransitive();
                }
                confirmPending = false;
                return true;
            }
            if (key.isKey(KeyCode.ESCAPE) || key.isCharIgnoreCase('n')) {
                confirmPending = false;
                status = "Cancelled";
                return true;
            }
            return true; // consume all other keys while confirming
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
            requestConfirmation();
            return true;
        }

        if (key.isCharIgnoreCase('d')) {
            if (view == View.DECLARED) {
                requestConfirmation();
            }
            return true;
        }

        if (key.isCharIgnoreCase('a')) {
            if (view == View.TRANSITIVE) {
                requestConfirmation();
            }
            return true;
        }

        return false;
    }

    private List<DepEntry> currentList() {
        return view == View.DECLARED ? declared : transitive;
    }

    private void requestConfirmation() {
        List<DepEntry> list = currentList();
        int sel = tableState.selected() != null ? tableState.selected() : -1;
        if (sel < 0 || sel >= list.size()) return;
        DepEntry dep = list.get(sel);

        if (view == View.DECLARED) {
            confirmAction = "remove";
            status = "Remove " + dep.ga() + " from POM? (y/Enter=confirm, n/Esc=cancel)";
        } else {
            confirmAction = "add";
            status = "Add " + dep.ga() + " to POM? (y/Enter=confirm, n/Esc=cancel)";
        }
        confirmPending = true;
    }

    private void removeDeclared() {
        int sel = tableState.selected() != null ? tableState.selected() : -1;
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

    private void addTransitive() {
        int sel = tableState.selected() != null ? tableState.selected() : -1;
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
            declared.add(new DepEntry(dep.groupId, dep.artifactId, dep.version, dep.scope, true));
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
                .title(" Pilot \u2014 Dependency Analysis ")
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
            List<Span> spans = new ArrayList<>();
            spans.add(Span.raw(dep.ga()));

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
        statusSpans.add(Span.raw(" " + status).fg(confirmPending ? Color.YELLOW : Color.GREEN));
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
