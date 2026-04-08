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
import eu.maveniverse.domtrip.maven.PomEditor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Interactive TUI for dependency convention alignment.
 */
class AlignTui {

    enum Phase {
        SELECT,
        PREVIEW
    }

    private static final int ROW_VERSION_STYLE = 0;
    private static final int ROW_VERSION_SOURCE = 1;
    private static final int ROW_PROPERTY_NAMING = 2;
    private static final int ROW_COUNT = 3;

    private final String pomPath;
    private final String projectGav;
    private final String originalPomContent;
    private final AlignOptions detectedOptions;

    // User-selected convention values (start matching detected)
    private AlignOptions.VersionStyle selectedStyle;
    private AlignOptions.VersionSource selectedSource;
    private AlignOptions.PropertyNamingConvention selectedNaming;

    private Phase phase = Phase.SELECT;
    private final TableState tableState = new TableState();
    private String status;

    // Preview state
    private List<UnifiedDiff.DiffLine> diffLines;
    private int diffScroll;
    private String alignedPomContent;

    private TuiRunner runner;

    AlignTui(String pomPath, String projectGav, String originalPomContent, AlignOptions detectedOptions) {
        this.pomPath = pomPath;
        this.projectGav = projectGav;
        this.originalPomContent = originalPomContent;
        this.detectedOptions = detectedOptions;
        this.selectedStyle = detectedOptions.versionStyle();
        this.selectedSource = detectedOptions.versionSource();
        this.selectedNaming = detectedOptions.namingConvention();
        this.status = "Detected conventions from POM";
        tableState.select(0);
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

        if (key.isCtrlC() || key.isCharIgnoreCase('q')) {
            runner.quit();
            return true;
        }

        if (phase == Phase.PREVIEW) {
            return handlePreviewEvent(key);
        }
        return handleSelectEvent(key);
    }

    private boolean handleSelectEvent(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE)) {
            runner.quit();
            return true;
        }

        if (key.isUp()) {
            tableState.selectPrevious();
            return true;
        }
        if (key.isDown()) {
            tableState.selectNext(ROW_COUNT);
            return true;
        }

        if (key.isRight() || key.isKey(KeyCode.ENTER)) {
            cycleForward();
            return true;
        }
        if (key.isLeft()) {
            cycleBackward();
            return true;
        }

        if (key.isCharIgnoreCase('p')) {
            showPreview();
            return true;
        }

        if (key.isCharIgnoreCase('w')) {
            applyAlignment();
            return true;
        }

        return false;
    }

    private boolean handlePreviewEvent(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE)) {
            phase = Phase.SELECT;
            diffLines = null;
            diffScroll = 0;
            return true;
        }

        if (key.isUp()) {
            if (diffScroll > 0) diffScroll--;
            return true;
        }
        if (key.isDown()) {
            if (diffLines != null) {
                diffScroll = Math.min(diffScroll + 1, UnifiedDiff.maxScroll(diffLines, lastContentHeight));
            }
            return true;
        }
        if (key.isKey(KeyCode.PAGE_UP)) {
            diffScroll = Math.max(0, diffScroll - 10);
            return true;
        }
        if (key.isKey(KeyCode.PAGE_DOWN)) {
            if (diffLines != null) {
                diffScroll = Math.min(diffScroll + 10, UnifiedDiff.maxScroll(diffLines, lastContentHeight));
            }
            return true;
        }

        if (key.isKey(KeyCode.ENTER) || key.isCharIgnoreCase('w')) {
            writeAlignedPom();
            return true;
        }

        return false;
    }

    // -- Convention cycling --

    void cycleForward() {
        int row = selectedRow();
        switch (row) {
            case ROW_VERSION_STYLE -> selectedStyle = nextEnum(AlignOptions.VersionStyle.values(), selectedStyle);
            case ROW_VERSION_SOURCE -> selectedSource = nextEnum(AlignOptions.VersionSource.values(), selectedSource);
            case ROW_PROPERTY_NAMING ->
                selectedNaming = nextEnum(AlignOptions.PropertyNamingConvention.values(), selectedNaming);
        }
    }

    void cycleBackward() {
        int row = selectedRow();
        switch (row) {
            case ROW_VERSION_STYLE -> selectedStyle = prevEnum(AlignOptions.VersionStyle.values(), selectedStyle);
            case ROW_VERSION_SOURCE -> selectedSource = prevEnum(AlignOptions.VersionSource.values(), selectedSource);
            case ROW_PROPERTY_NAMING ->
                selectedNaming = prevEnum(AlignOptions.PropertyNamingConvention.values(), selectedNaming);
        }
    }

    static <E extends Enum<E>> E nextEnum(E[] values, E current) {
        int idx = current.ordinal();
        return values[(idx + 1) % values.length];
    }

    static <E extends Enum<E>> E prevEnum(E[] values, E current) {
        int idx = current.ordinal();
        return values[(idx - 1 + values.length) % values.length];
    }

    private int selectedRow() {
        Integer sel = tableState.selected();
        return sel != null ? sel : 0;
    }

    AlignOptions buildSelectedOptions() {
        return AlignOptions.builder()
                .versionStyle(selectedStyle)
                .versionSource(selectedSource)
                .namingConvention(selectedNaming)
                .build();
    }

    // -- Actions --

    private void showPreview() {
        try {
            String currentPom = Files.readString(Path.of(pomPath));
            PomEditor editor = new PomEditor(Document.of(currentPom));
            int count = editor.dependencies().alignAllDependencies(buildSelectedOptions());
            alignedPomContent = editor.toXml();

            var fullDiff = UnifiedDiff.compute(currentPom, alignedPomContent);
            long changes = UnifiedDiff.changeCount(fullDiff);

            if (changes == 0) {
                status = "No changes needed \u2014 POM already aligned";
                return;
            }

            diffLines = UnifiedDiff.filterContext(fullDiff, 3);
            diffScroll = 0;
            phase = Phase.PREVIEW;
            status = count + " dependency(ies) aligned, " + changes + " line(s) changed";
        } catch (Exception e) {
            status = "Failed to compute preview: " + e.getMessage();
        }
    }

    private void applyAlignment() {
        try {
            String currentPom = Files.readString(Path.of(pomPath));
            PomEditor editor = new PomEditor(Document.of(currentPom));
            int count = editor.dependencies().alignAllDependencies(buildSelectedOptions());
            Files.writeString(Path.of(pomPath), editor.toXml());
            status = "Aligned " + count + " dependency(ies) in POM";
        } catch (Exception e) {
            status = "Failed to apply alignment: " + e.getMessage();
        }
    }

    private void writeAlignedPom() {
        if (alignedPomContent == null) return;
        try {
            Files.writeString(Path.of(pomPath), alignedPomContent);
            status = "Changes written to POM";
            phase = Phase.SELECT;
            diffLines = null;
            diffScroll = 0;
            alignedPomContent = null;
        } catch (Exception e) {
            status = "Failed to write POM: " + e.getMessage();
        }
    }

    // -- Rendering --

    private int lastContentHeight;

    void render(Frame frame) {
        var zones = Layout.vertical()
                .constraints(Constraint.length(3), Constraint.fill(), Constraint.length(3))
                .split(frame.area());

        renderHeader(frame, zones.get(0));
        lastContentHeight = zones.get(1).height();

        if (phase == Phase.PREVIEW && diffLines != null) {
            UnifiedDiff.render(frame, zones.get(1), diffLines, diffScroll, " POM Changes Preview ");
        } else {
            renderConventionTable(frame, zones.get(1));
        }

        renderInfoBar(frame, zones.get(2));
    }

    private void renderHeader(Frame frame, Rect area) {
        String title =
                phase == Phase.PREVIEW ? " Pilot \u2014 Alignment Preview " : " Pilot \u2014 Convention Alignment ";
        Block block = Block.builder()
                .title(title)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().cyan())
                .build();

        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" " + projectGav).bold().cyan());

        Paragraph header = Paragraph.builder()
                .text(dev.tamboui.text.Text.from(Line.from(spans)))
                .block(block)
                .build();
        frame.renderWidget(header, area);
    }

    private void renderConventionTable(Frame frame, Rect area) {
        Block block = Block.builder()
                .title(" Dependency Conventions ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build();

        Row header = Row.from("Convention", "Detected", "Selected")
                .style(Style.create().bold().yellow());

        List<Row> rows = new ArrayList<>();
        rows.add(createConventionRow(
                "Version Style",
                detectedOptions.versionStyle().name(),
                selectedStyle.name(),
                selectedStyle != detectedOptions.versionStyle()));
        rows.add(createConventionRow(
                "Version Source",
                detectedOptions.versionSource().name(),
                selectedSource.name(),
                selectedSource != detectedOptions.versionSource()));
        rows.add(createConventionRow(
                "Property Naming",
                detectedOptions.namingConvention().name(),
                selectedNaming.name(),
                selectedNaming != detectedOptions.namingConvention()));

        Table table = Table.builder()
                .header(header)
                .rows(rows)
                .widths(Constraint.percentage(30), Constraint.percentage(35), Constraint.percentage(35))
                .highlightStyle(Style.create().reversed().bold())
                .highlightSymbol("\u25B8 ")
                .block(block)
                .build();

        frame.renderStatefulWidget(table, area, tableState);
    }

    private Row createConventionRow(String name, String detected, String selected, boolean changed) {
        Style style = changed ? Style.create().fg(Color.YELLOW) : Style.create();
        String selectedDisplay = changed ? selected + " \u2190" : selected;
        return Row.from(name, detected, selectedDisplay).style(style);
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
        if (phase == Phase.PREVIEW) {
            spans.add(Span.raw("\u2191\u2193").bold());
            spans.add(Span.raw(":Scroll  "));
            spans.add(Span.raw("PgUp/PgDn").bold());
            spans.add(Span.raw(":Page  "));
            spans.add(Span.raw("Enter/w").bold());
            spans.add(Span.raw(":Apply  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Back  "));
            spans.add(Span.raw("q").bold());
            spans.add(Span.raw(":Quit"));
        } else {
            spans.add(Span.raw("\u2191\u2193").bold());
            spans.add(Span.raw(":Navigate  "));
            spans.add(Span.raw("\u2190\u2192/Enter").bold());
            spans.add(Span.raw(":Cycle  "));
            spans.add(Span.raw("p").bold());
            spans.add(Span.raw(":Preview  "));
            spans.add(Span.raw("w").bold());
            spans.add(Span.raw(":Apply  "));
            spans.add(Span.raw("q").bold());
            spans.add(Span.raw(":Quit"));
        }

        frame.renderWidget(Paragraph.from(Line.from(spans)), rows.get(2));
    }
}
