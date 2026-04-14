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
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import eu.maveniverse.domtrip.Document;
import eu.maveniverse.domtrip.maven.AlignOptions;
import eu.maveniverse.domtrip.maven.PomEditor;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Interactive TUI for dependency convention alignment.
 *
 * <p>Supports reactor-aware cross-POM alignment: when a child module's dependencies
 * are managed by a parent POM in the reactor, the MANAGED version style moves
 * managed dep entries to the parent POM and strips versions from the child POM.</p>
 */
class AlignTui extends ToolPanel {

    enum Phase {
        SELECT,
        PREVIEW
    }

    /**
     * Information about a reactor-local parent POM that holds (or should hold) dependency management.
     */
    record ParentPomInfo(String pomPath, String gav, AlignOptions detectedOptions) {}

    private static final int ROW_VERSION_STYLE = 0;
    private static final int ROW_VERSION_SOURCE = 1;
    private static final int ROW_PROPERTY_NAMING = 2;
    private static final int ROW_COUNT = 3;

    private final String pomPath;
    private final List<String> additionalPomPaths; // batch mode: additional POMs to align
    private final String projectGav;
    private final AlignOptions detectedOptions;
    private final ParentPomInfo parentInfo;

    // User-selected convention values (start matching detected)
    private AlignOptions.VersionStyle selectedStyle;
    private AlignOptions.VersionSource selectedSource;
    private AlignOptions.PropertyNamingConvention selectedNaming;

    private Phase phase = Phase.SELECT;
    private final TableState tableState = new TableState();
    private String status;

    // Preview state
    private final DiffOverlay diffOverlay = new DiffOverlay();
    private String alignedPomContent;
    private Map<Path, String> alignedPomContents; // cross-POM mode

    private TuiRunner runner;

    AlignTui(String pomPath, String projectGav, AlignOptions detectedOptions, ParentPomInfo parentInfo) {
        this(pomPath, List.of(), projectGav, detectedOptions, parentInfo);
    }

    AlignTui(
            String pomPath,
            List<String> additionalPomPaths,
            String projectGav,
            AlignOptions detectedOptions,
            ParentPomInfo parentInfo) {
        this.pomPath = pomPath;
        this.additionalPomPaths = additionalPomPaths;
        this.projectGav = projectGav;
        this.parentInfo = parentInfo;

        // When deps are managed by parent, use parent's source/naming conventions
        if (parentInfo != null && detectedOptions.versionStyle() == AlignOptions.VersionStyle.MANAGED) {
            this.detectedOptions = AlignOptions.builder()
                    .versionStyle(detectedOptions.versionStyle())
                    .versionSource(parentInfo.detectedOptions().versionSource())
                    .namingConvention(parentInfo.detectedOptions().namingConvention())
                    .build();
        } else {
            this.detectedOptions = detectedOptions;
        }

        this.selectedStyle = this.detectedOptions.versionStyle();
        this.selectedSource = this.detectedOptions.versionSource();
        this.selectedNaming = this.detectedOptions.namingConvention();
        if (!additionalPomPaths.isEmpty()) {
            this.status = "Batch mode: aligning " + (1 + additionalPomPaths.size()) + " modules";
        } else if (parentInfo != null) {
            this.status = "Reactor-aware mode: parent dependency management detected";
        } else {
            this.status = "Detected conventions from POM";
        }
        tableState.select(0);
    }

    /**
     * Whether cross-POM editing is active: MANAGED style selected with a reactor parent available.
     */
    boolean isCrossPomMode() {
        return parentInfo != null && selectedStyle == AlignOptions.VersionStyle.MANAGED;
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

        if (key.isCtrlC()) {
            runner.quit();
            return true;
        }

        // Delegate to handleKeyEvent for tool-specific keys
        if (handleKeyEvent(key)) return true;

        // Standalone-specific keys
        if (key.isCharIgnoreCase('q')) {
            runner.quit();
            return true;
        }
        if (key.isKey(KeyCode.ESCAPE)) {
            runner.quit();
            return true;
        }

        if (key.isCharIgnoreCase('h')) {
            helpOverlay.open(buildHelpStandalone());
            return true;
        }

        return false;
    }

    @Override
    public boolean handleKeyEvent(KeyEvent key) {
        if (phase == Phase.PREVIEW) {
            if (key.isKey(KeyCode.ESCAPE)) {
                phase = Phase.SELECT;
                diffOverlay.close();
                return true;
            }
            if (diffOverlay.handleScrollKey(key, lastContentHeight)) return true;
            if (key.isKey(KeyCode.ENTER) || key.isCharIgnoreCase('w')) {
                writeAlignedPom();
                return true;
            }
            return true; // consume all keys during preview
        }

        // SELECT phase
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

    private boolean isBatchMode() {
        return !additionalPomPaths.isEmpty();
    }

    private void showPreview() {
        if (isBatchMode()) {
            showBatchPreview();
            return;
        }
        if (isCrossPomMode()) {
            showCrossPomPreview();
            return;
        }
        try {
            String currentPom = Files.readString(Path.of(pomPath));
            PomEditor editor = new PomEditor(Document.of(currentPom));
            int count = editor.dependencies().alignAllDependencies(buildSelectedOptions());
            alignedPomContent = editor.toXml();

            long changes = diffOverlay.open(currentPom, alignedPomContent);

            if (changes == 0) {
                status = count + " dependency(ies) processed — no POM changes";
                return;
            }

            phase = Phase.PREVIEW;
            status = count + " dependency(ies) aligned, " + changes + " line(s) changed";
        } catch (Exception e) {
            status = "Failed to compute preview: " + e.getMessage();
        }
    }

    private void applyAlignment() {
        if (isBatchMode()) {
            applyBatchAlignment();
            return;
        }
        if (isCrossPomMode()) {
            applyCrossPomAlignment();
            return;
        }
        try {
            AlignOptions opts = buildSelectedOptions();
            String currentPom = Files.readString(Path.of(pomPath));
            PomEditor editor = new PomEditor(Document.of(currentPom));
            int count = editor.dependencies().alignAllDependencies(opts);
            String aligned = editor.toXml();

            if (currentPom.equals(aligned)) {
                status = count + " dependency(ies) processed — no POM changes";
            } else {
                Files.writeString(Path.of(pomPath), aligned);
                status = "Aligned POM";
            }
        } catch (Exception e) {
            status = "Failed to apply alignment: " + e.getMessage();
        }
    }

    private void writeAlignedPom() {
        if ((isBatchMode() || isCrossPomMode()) && alignedPomContents != null) {
            writeCrossPomChanges();
            return;
        }
        try {
            AlignOptions opts = buildSelectedOptions();
            String currentPom = Files.readString(Path.of(pomPath));
            PomEditor editor = new PomEditor(Document.of(currentPom));
            editor.dependencies().alignAllDependencies(opts);
            String aligned = editor.toXml();

            if (currentPom.equals(aligned)) {
                status = "No POM changes to write";
            } else {
                Files.writeString(Path.of(pomPath), aligned);
                status = "Changes written to POM";
            }
            phase = Phase.SELECT;
            diffOverlay.close();
            alignedPomContent = null;
        } catch (Exception e) {
            status = "Failed to write POM: " + e.getMessage();
        }
    }

    // -- Batch alignment (parent selected, children as additional POMs) --

    void showBatchPreview() {
        try {
            String parentContent = Files.readString(Path.of(pomPath));
            PomEditor parentEditor = new PomEditor(Document.of(parentContent));
            int totalCount = 0;

            // Single pass: align all children against parent, collect diffs and results
            Map<String, Map.Entry<String, String>> fileDiffs = new LinkedHashMap<>();
            alignedPomContents = new LinkedHashMap<>();

            for (String childPath : additionalPomPaths) {
                String childContent = Files.readString(Path.of(childPath));
                PomEditor childEditor = new PomEditor(Document.of(childContent));
                totalCount += childEditor.dependencies().alignAllToParent(parentEditor, buildSelectedOptions());
                String childModified = childEditor.toXml();
                if (!childContent.equals(childModified)) {
                    String label = Path.of(childPath).getParent().getFileName() + "/pom.xml";
                    fileDiffs.put(label, Map.entry(childContent, childModified));
                    alignedPomContents.put(Path.of(childPath), childModified);
                }
            }

            // Parent diff (accumulated dependency management entries from all children)
            String parentModified = parentEditor.toXml();
            if (!parentContent.equals(parentModified)) {
                String label = Path.of(pomPath).getParent().getFileName() + "/pom.xml";
                fileDiffs.put(label, Map.entry(parentContent, parentModified));
                alignedPomContents.put(Path.of(pomPath), parentModified);
            }

            if (fileDiffs.isEmpty()) {
                status = totalCount + " dependency(ies) processed — no POM changes";
                alignedPomContents = null;
                return;
            }

            long changes = diffOverlay.openMulti(fileDiffs);
            phase = Phase.PREVIEW;
            status = totalCount + " dependency(ies) aligned across " + fileDiffs.size() + " file(s), " + changes
                    + " line(s) changed";
        } catch (Exception e) {
            status = "Failed to compute preview: " + e.getMessage();
        }
    }

    void applyBatchAlignment() {
        try {
            String parentContent = Files.readString(Path.of(pomPath));
            PomEditor parentEditor = new PomEditor(Document.of(parentContent));
            int totalCount = 0;
            int filesChanged = 0;

            for (String childPath : additionalPomPaths) {
                String childContent = Files.readString(Path.of(childPath));
                PomEditor childEditor = new PomEditor(Document.of(childContent));
                totalCount += childEditor.dependencies().alignAllToParent(parentEditor, buildSelectedOptions());
                String childModified = childEditor.toXml();
                if (!childContent.equals(childModified)) {
                    Files.writeString(Path.of(childPath), childModified);
                    filesChanged++;
                }
            }

            String parentModified = parentEditor.toXml();
            if (!parentContent.equals(parentModified)) {
                Files.writeString(Path.of(pomPath), parentModified);
                filesChanged++;
            }

            if (filesChanged == 0) {
                status = totalCount + " dependency(ies) processed — no POM changes";
            } else {
                status = "Aligned " + totalCount + " dependency(ies) across " + filesChanged + " POM(s)";
            }
        } catch (Exception e) {
            status = "Failed to apply alignment: " + e.getMessage();
        }
    }

    // -- Cross-POM alignment --

    void showCrossPomPreview() {
        try {
            String childContent = Files.readString(Path.of(pomPath));
            String parentContent = Files.readString(Path.of(parentInfo.pomPath()));

            PomEditor childEditor = new PomEditor(Document.of(childContent));
            PomEditor parentEditor = new PomEditor(Document.of(parentContent));

            int count = childEditor.dependencies().alignAllToParent(parentEditor, buildSelectedOptions());

            String childModified = childEditor.toXml();
            String parentModified = parentEditor.toXml();

            // Build multi-file diff with distinguishing labels (both are pom.xml)
            Path pp = Path.of(parentInfo.pomPath());
            Path ppParent = pp.getParent();
            String parentLabel = (ppParent != null ? ppParent.getFileName() + "/" : "") + pp.getFileName();
            Path cp = Path.of(pomPath);
            Path cpParent = cp.getParent();
            String childLabel = (cpParent != null ? cpParent.getFileName() + "/" : "") + cp.getFileName();
            Map<String, Map.Entry<String, String>> fileDiffs = new LinkedHashMap<>();
            if (!parentContent.equals(parentModified)) {
                fileDiffs.put(parentLabel, Map.entry(parentContent, parentModified));
            }
            if (!childContent.equals(childModified)) {
                fileDiffs.put(childLabel, Map.entry(childContent, childModified));
            }

            if (fileDiffs.isEmpty()) {
                status = "No changes needed — POM already aligned";
                alignedPomContents = null;
                return;
            }

            // Store for writing
            alignedPomContents = new LinkedHashMap<>();
            if (!parentContent.equals(parentModified)) {
                alignedPomContents.put(Path.of(parentInfo.pomPath()), parentModified);
            }
            if (!childContent.equals(childModified)) {
                alignedPomContents.put(Path.of(pomPath), childModified);
            }

            long changes = diffOverlay.openMulti(fileDiffs);
            phase = Phase.PREVIEW;
            status = count + " dependency(ies) aligned, " + changes + " line(s) changed across " + fileDiffs.size()
                    + " file(s)";
        } catch (Exception e) {
            status = "Failed to compute preview: " + e.getMessage();
        }
    }

    void applyCrossPomAlignment() {
        try {
            Path parentPath = Path.of(parentInfo.pomPath());
            Path childPath = Path.of(pomPath);

            String childContent = Files.readString(childPath);
            String parentContent = Files.readString(parentPath);

            PomEditor childEditor = new PomEditor(Document.of(childContent));
            PomEditor parentEditor = new PomEditor(Document.of(parentContent));

            int count = childEditor.dependencies().alignAllToParent(parentEditor, buildSelectedOptions());

            // Write to temp files first, then atomically replace originals
            atomicWritePair(parentPath, parentEditor.toXml(), childPath, childEditor.toXml());
            status = "Aligned " + count + " dependency(ies) across 2 POMs";
        } catch (Exception e) {
            status = "Failed to apply alignment: " + e.getMessage();
        }
    }

    /**
     * Writes two POM files atomically: first to temp files, then moved to replace originals.
     * Falls back to non-atomic move if the filesystem does not support ATOMIC_MOVE.
     */
    static void atomicWritePair(Path path1, String content1, Path path2, String content2) throws IOException {
        Path tmp1 = Files.createTempFile(path1.getParent(), ".pom", ".tmp");
        Path tmp2 = Files.createTempFile(path2.getParent(), ".pom", ".tmp");
        try {
            Files.writeString(tmp1, content1);
            Files.writeString(tmp2, content2);
            atomicMove(tmp1, path1);
            tmp1 = null; // moved successfully
            atomicMove(tmp2, path2);
            tmp2 = null; // moved successfully
        } finally {
            if (tmp1 != null) Files.deleteIfExists(tmp1);
            if (tmp2 != null) Files.deleteIfExists(tmp2);
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    void writeCrossPomChanges() {
        try {
            for (var entry : alignedPomContents.entrySet()) {
                Files.writeString(entry.getKey(), entry.getValue());
            }
            status = "Changes written to " + alignedPomContents.size() + " POM(s)";
            phase = Phase.SELECT;
            diffOverlay.close();
            alignedPomContents = null;
            alignedPomContent = null;
        } catch (Exception e) {
            status = "Failed to write POMs: " + e.getMessage();
        }
    }

    // -- Help --

    @Override
    public List<HelpOverlay.Section> helpSections() {
        List<HelpOverlay.Entry> descEntries = new ArrayList<>();
        descEntries.add(new HelpOverlay.Entry("", "Restructures dependency declarations to follow a"));
        descEntries.add(new HelpOverlay.Entry("", "consistent convention. Configure three options:"));
        descEntries.add(new HelpOverlay.Entry("", ""));
        descEntries.add(new HelpOverlay.Entry("", "Version Style: how versions are expressed — inline"));
        descEntries.add(new HelpOverlay.Entry("", "in <version> tags, via <properties>, or managed"));
        descEntries.add(new HelpOverlay.Entry("", "through <dependencyManagement>."));
        descEntries.add(new HelpOverlay.Entry("", ""));
        descEntries.add(new HelpOverlay.Entry("", "Version Source: where version values come from —"));
        descEntries.add(new HelpOverlay.Entry("", "keep current versions, import from a BOM, etc."));
        descEntries.add(new HelpOverlay.Entry("", ""));
        descEntries.add(new HelpOverlay.Entry("", "Property Naming: convention for property names"));
        descEntries.add(new HelpOverlay.Entry("", "(e.g. groupId.artifactId.version or artifact.version)."));
        descEntries.add(new HelpOverlay.Entry("", ""));
        descEntries.add(new HelpOverlay.Entry("", "Preview the diff before applying to verify changes."));

        if (parentInfo != null) {
            descEntries.add(new HelpOverlay.Entry("", ""));
            descEntries.add(new HelpOverlay.Entry("", "Cross-POM mode: when MANAGED style is selected,"));
            descEntries.add(new HelpOverlay.Entry("", "managed deps are written to the parent POM and"));
            descEntries.add(new HelpOverlay.Entry("", "child deps become version-less."));
            descEntries.add(new HelpOverlay.Entry("", "Parent: " + parentInfo.gav()));
        }

        return List.of(
                new HelpOverlay.Section("BOM Alignment", descEntries),
                new HelpOverlay.Section(
                        "Alignment Actions",
                        List.of(
                                new HelpOverlay.Entry("↑ / ↓", "Move between options"),
                                new HelpOverlay.Entry("← / → / Enter", "Cycle through option values"),
                                new HelpOverlay.Entry("p", "Preview the POM changes as a diff"),
                                new HelpOverlay.Entry("w", "Apply alignment and write to POM"))));
    }

    private List<HelpOverlay.Section> buildHelpStandalone() {
        List<HelpOverlay.Section> sections = new ArrayList<>(helpSections());
        sections.set(
                sections.size() - 1,
                new HelpOverlay.Section(
                        "Keys",
                        List.of(
                                new HelpOverlay.Entry("↑ / ↓", "Move between options"),
                                new HelpOverlay.Entry("← / → / Enter", "Cycle through option values"),
                                new HelpOverlay.Entry("p", "Preview the POM changes as a diff"),
                                new HelpOverlay.Entry("w", "Apply alignment and write to POM"),
                                new HelpOverlay.Entry("h", "Toggle this help screen"),
                                new HelpOverlay.Entry("q / Esc", "Quit"))));
        return sections;
    }

    // ── ToolPanel interface ─────────────────────────────────────────────────

    @Override
    public String toolName() {
        return "Align";
    }

    @Override
    public boolean handleMouseEvent(MouseEvent mouse, Rect area) {
        if (mouse.isScroll()) {
            int sel = tableState.selected();
            if (mouse.kind() == MouseEventKind.SCROLL_UP) {
                tableState.selectPrevious();
            } else {
                tableState.selectNext(ROW_COUNT);
            }
            return true;
        }
        return false;
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public List<Span> keyHints() {
        List<Span> spans = new ArrayList<>();
        if (phase == Phase.PREVIEW) {
            spans.add(Span.raw("↑↓").bold());
            spans.add(Span.raw(":Scroll  "));
            spans.add(Span.raw("PgUp/PgDn").bold());
            spans.add(Span.raw(":Page  "));
            spans.add(Span.raw("Enter/w").bold());
            spans.add(Span.raw(":Apply  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Back"));
        } else {
            spans.add(Span.raw("↑↓").bold());
            spans.add(Span.raw(":Navigate  "));
            spans.add(Span.raw("←→/Enter").bold());
            spans.add(Span.raw(":Cycle  "));
            spans.add(Span.raw("p").bold());
            spans.add(Span.raw(":Preview  "));
            spans.add(Span.raw("w").bold());
            spans.add(Span.raw(":Apply"));
        }
        return spans;
    }

    @Override
    public void setRunner(TuiRunner runner) {
        this.runner = runner;
    }

    // -- Rendering --

    private int lastContentHeight;

    @Override
    public void render(Frame frame, Rect area) {
        lastContentHeight = area.height();
        if (helpOverlay.isActive()) {
            helpOverlay.render(frame, area);
        } else if (phase == Phase.PREVIEW && diffOverlay.isActive()) {
            diffOverlay.render(frame, area, " POM Changes Preview ");
        } else {
            renderConventionTable(frame, area);
        }
    }

    void renderStandalone(Frame frame) {
        var zones = Layout.vertical()
                .constraints(Constraint.length(3), Constraint.fill(), Constraint.length(3))
                .split(frame.area());

        renderHeader(frame, zones.get(0));
        lastContentHeight = zones.get(1).height();

        if (helpOverlay.isActive()) {
            helpOverlay.render(frame, zones.get(1));
        } else if (phase == Phase.PREVIEW && diffOverlay.isActive()) {
            diffOverlay.render(frame, zones.get(1), " POM Changes Preview ");
        } else {
            renderConventionTable(frame, zones.get(1));
        }

        renderInfoBar(frame, zones.get(2));
    }

    private void renderHeader(Frame frame, Rect area) {
        String title = phase == Phase.PREVIEW ? "Alignment Preview" : "Convention Alignment";
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" " + projectGav).bold().cyan());
        if (isCrossPomMode()) {
            spans.add(Span.raw("  managed → ").fg(theme.separatorColor()));
            spans.add(Span.raw(parentInfo.gav()).yellow());
        }
        renderStandaloneHeader(frame, area, title, Line.from(spans));
    }

    private void renderConventionTable(Frame frame, Rect area) {
        Block block = Block.builder()
                .title(" Dependency Conventions ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(borderStyle())
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
                .highlightSymbol("▸ ")
                .block(block)
                .build();

        frame.renderStatefulWidget(table, area, tableState);
    }

    private Row createConventionRow(String name, String detected, String selected, boolean changed) {
        Style style = changed ? theme.changedValue() : Style.create();
        String selectedDisplay = changed ? selected + " ←" : selected;
        return Row.from(name, detected, selectedDisplay).style(style);
    }

    private void renderInfoBar(Frame frame, Rect area) {
        var rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.length(1))
                .split(area);

        // Status
        List<Span> statusSpans = new ArrayList<>();
        statusSpans.add(Span.raw(" " + status).fg(theme.standaloneStatusColor()));
        frame.renderWidget(Paragraph.from(Line.from(statusSpans)), rows.get(1));

        // Key bindings
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" "));
        if (phase == Phase.PREVIEW) {
            spans.add(Span.raw("↑↓").bold());
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
            spans.add(Span.raw("↑↓").bold());
            spans.add(Span.raw(":Navigate  "));
            spans.add(Span.raw("←→/Enter").bold());
            spans.add(Span.raw(":Cycle  "));
            spans.add(Span.raw("p").bold());
            spans.add(Span.raw(":Preview  "));
            spans.add(Span.raw("w").bold());
            spans.add(Span.raw(":Apply  "));
            spans.add(Span.raw("h").bold());
            spans.add(Span.raw(":Help  "));
            spans.add(Span.raw("q").bold());
            spans.add(Span.raw(":Quit"));
        }

        frame.renderWidget(Paragraph.from(Line.from(spans)), rows.get(2));
    }
}
