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
import eu.maveniverse.domtrip.maven.PomEditor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Interactive TUI for viewing and applying dependency updates across a multi-module reactor.
 *
 * <p>Provides two views switchable via Tab:</p>
 * <ul>
 *   <li><b>Dependencies</b> — aggregated list grouped by shared version property</li>
 *   <li><b>Modules</b> — reactor tree with per-module update counts</li>
 * </ul>
 */
class ReactorUpdatesTui {

    private enum View {
        DEPENDENCIES,
        MODULES
    }

    private enum Filter {
        ALL,
        PATCH,
        MINOR,
        MAJOR
    }

    private static class ReactorRow {
        final ReactorCollector.PropertyGroup propertyGroup;
        final ReactorCollector.AggregatedDependency dependency;

        private ReactorRow(ReactorCollector.PropertyGroup pg, ReactorCollector.AggregatedDependency dep) {
            this.propertyGroup = pg;
            this.dependency = dep;
        }

        static ReactorRow group(ReactorCollector.PropertyGroup pg) {
            return new ReactorRow(pg, null);
        }

        static ReactorRow dep(ReactorCollector.AggregatedDependency dep) {
            return new ReactorRow(null, dep);
        }

        boolean isGroupHeader() {
            return propertyGroup != null;
        }
    }

    private final ReactorCollector.CollectionResult reactorResult;
    private final ReactorModel reactorModel;
    private final String projectGav;
    private final UpdatesTui.VersionResolver versionResolver;
    private final TableState tableState = new TableState();
    private final TableState moduleTableState = new TableState();
    private final ExecutorService httpPool = MojoHelper.newHttpPool();

    private View view = View.DEPENDENCIES;
    private List<ReactorRow> displayRows = new ArrayList<>();
    private Filter filter = Filter.ALL;
    private final DiffOverlay diffOverlay = new DiffOverlay();
    private final HelpOverlay helpOverlay = new HelpOverlay();
    private int lastContentHeight;
    String status = "Loading updates\u2026";
    boolean loading = true;
    int loadedCount = 0;
    int failedCount = 0;

    private TuiRunner runner;

    ReactorUpdatesTui(
            ReactorCollector.CollectionResult result,
            ReactorModel reactorModel,
            String projectGav,
            UpdatesTui.VersionResolver versionResolver) {
        this.reactorResult = result;
        this.reactorModel = reactorModel;
        this.projectGav = projectGav;
        this.versionResolver = versionResolver;
        if (!reactorModel.allModules.isEmpty()) {
            moduleTableState.select(0);
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
            fetchAllUpdates();
            configured.run();
        } finally {
            configured.close();
            httpPool.shutdownNow();
            httpPool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // -- Version resolution --

    private void fetchAllUpdates() {
        for (var dep : reactorResult.allDependencies) {
            CompletableFuture.supplyAsync(() -> versionResolver.resolveVersions(dep.groupId, dep.artifactId), httpPool)
                    .thenAccept(versions -> runner.runOnRenderThread(() -> {
                        applyVersionResult(dep, versions);
                        loadedCount++;
                        updateStatusIfDone();
                    }))
                    .exceptionally(ex -> {
                        runner.runOnRenderThread(() -> {
                            loadedCount++;
                            failedCount++;
                            updateStatusIfDone();
                        });
                        return null;
                    });
        }
    }

    private void applyVersionResult(ReactorCollector.AggregatedDependency dep, List<String> versions) {
        String newest = null;
        for (String v : versions) {
            if (VersionComparator.isPreview(v)) continue;
            if (dep.primaryVersion == null
                    || dep.primaryVersion.isEmpty()
                    || VersionComparator.isNewer(dep.primaryVersion, v)) {
                newest = v;
                break;
            }
        }
        if (newest != null) {
            dep.newestVersion = newest;
            dep.updateType = VersionComparator.classify(dep.primaryVersion, newest);
        }
    }

    private void updateStatusIfDone() {
        if (loadedCount >= reactorResult.allDependencies.size()) {
            loading = false;
            for (var group : reactorResult.propertyGroups) {
                for (var dep : group.dependencies) {
                    if (dep.hasUpdate()) {
                        if (group.newestVersion == null
                                || VersionComparator.isNewer(dep.newestVersion, group.newestVersion)) {
                            // Pick the most conservative (smallest) version available for all deps
                            group.newestVersion = dep.newestVersion;
                            group.updateType = dep.updateType;
                        }
                    }
                }
            }
            updateReactorCounts();
            buildDisplayRows();
            long updates = reactorResult.allDependencies.stream()
                    .filter(ReactorCollector.AggregatedDependency::hasUpdate)
                    .count();
            status = updates + " update(s) available across " + reactorModel.allModules.size() + " modules";
            if (failedCount > 0) {
                status += "; " + failedCount + " lookup(s) failed";
            }
        }
    }

    private void updateReactorCounts() {
        Map<String, Integer> countsByModule = new LinkedHashMap<>();
        for (var dep : reactorResult.allDependencies) {
            if (dep.hasUpdate()) {
                for (var usage : dep.usages) {
                    String ga = usage.project.getGroupId() + ":" + usage.project.getArtifactId();
                    countsByModule.merge(ga, 1, Integer::sum);
                }
            }
        }
        for (var node : reactorModel.allModules) {
            Integer count = countsByModule.get(node.ga());
            node.ownUpdateCount = count != null ? count : 0;
        }
        reactorModel.recomputeCounts();
    }

    private void buildDisplayRows() {
        displayRows = new ArrayList<>();
        for (var group : reactorResult.propertyGroups) {
            boolean groupHasUpdate = group.hasUpdate()
                    || group.dependencies.stream().anyMatch(ReactorCollector.AggregatedDependency::hasUpdate);
            if (filter != Filter.ALL) {
                groupHasUpdate = matchesFilter(group.updateType);
            }
            if (groupHasUpdate) {
                displayRows.add(ReactorRow.group(group));
                for (var dep : group.dependencies) {
                    displayRows.add(ReactorRow.dep(dep));
                }
            }
        }
        for (var dep : reactorResult.ungroupedDependencies) {
            boolean show =
                    switch (filter) {
                        case ALL -> dep.hasUpdate();
                        case PATCH -> dep.updateType == VersionComparator.UpdateType.PATCH;
                        case MINOR -> dep.updateType == VersionComparator.UpdateType.MINOR;
                        case MAJOR -> dep.updateType == VersionComparator.UpdateType.MAJOR;
                    };
            if (show) {
                displayRows.add(ReactorRow.dep(dep));
            }
        }
        if (!displayRows.isEmpty()) {
            tableState.select(0);
        }
    }

    private boolean matchesFilter(VersionComparator.UpdateType type) {
        return switch (filter) {
            case ALL -> true;
            case PATCH -> type == VersionComparator.UpdateType.PATCH;
            case MINOR -> type == VersionComparator.UpdateType.MINOR;
            case MAJOR -> type == VersionComparator.UpdateType.MAJOR;
        };
    }

    // -- Event handling --

    boolean handleEvent(Event event, TuiRunner runner) {
        if (!(event instanceof KeyEvent key)) {
            return true;
        }

        // Diff overlay mode
        if (diffOverlay.isActive()) {
            if (key.isKey(KeyCode.ESCAPE)) {
                diffOverlay.close();
                return true;
            }
            if (diffOverlay.handleScrollKey(key, lastContentHeight)) return true;
            if (key.isCharIgnoreCase('q') || key.isCtrlC()) {
                runner.quit();
                return true;
            }
            return false;
        }

        // Help overlay mode
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

        if (key.isKey(KeyCode.TAB)) {
            view = view == View.DEPENDENCIES ? View.MODULES : View.DEPENDENCIES;
            return true;
        }

        if (view == View.DEPENDENCIES) {
            return handleDepsEvent(key);
        } else {
            return handleModulesEvent(key);
        }
    }

    private boolean handleDepsEvent(KeyEvent key) {
        if (key.isUp()) {
            tableState.selectPrevious();
            return true;
        }
        if (key.isDown()) {
            tableState.selectNext(displayRows.size());
            return true;
        }
        if (key.isCharIgnoreCase(' ')) {
            toggleSelection();
            return true;
        }
        if (key.isCharIgnoreCase('a')) {
            selectAll();
            return true;
        }
        if (key.isCharIgnoreCase('n')) {
            deselectAll();
            return true;
        }
        if (key.isCharIgnoreCase('d')) {
            toggleDiffView();
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            applyUpdates();
            return true;
        }
        if (key.isCharIgnoreCase('1')) {
            filter = Filter.ALL;
            buildDisplayRows();
            return true;
        }
        if (key.isCharIgnoreCase('2')) {
            filter = Filter.PATCH;
            buildDisplayRows();
            return true;
        }
        if (key.isCharIgnoreCase('3')) {
            filter = Filter.MINOR;
            buildDisplayRows();
            return true;
        }
        if (key.isCharIgnoreCase('4')) {
            filter = Filter.MAJOR;
            buildDisplayRows();
            return true;
        }
        if (key.isCharIgnoreCase('h')) {
            helpOverlay.open(buildHelp());
            return true;
        }
        return false;
    }

    private boolean handleModulesEvent(KeyEvent key) {
        List<ReactorModel.ModuleNode> visible = reactorModel.visibleNodes();

        if (key.isUp()) {
            moduleTableState.selectPrevious();
            return true;
        }
        if (key.isDown()) {
            moduleTableState.selectNext(visible.size());
            return true;
        }
        if (key.isKey(KeyCode.ENTER) || key.isKey(KeyCode.RIGHT)) {
            Integer sel = moduleTableState.selected();
            if (sel != null && sel < visible.size()) {
                var node = visible.get(sel);
                if (node.hasChildren()) {
                    node.expanded = !node.expanded;
                }
            }
            return true;
        }
        if (key.isKey(KeyCode.LEFT)) {
            Integer sel = moduleTableState.selected();
            if (sel != null && sel < visible.size()) {
                var node = visible.get(sel);
                if (node.expanded && node.hasChildren()) {
                    node.expanded = false;
                }
            }
            return true;
        }
        if (key.isCharIgnoreCase('h')) {
            helpOverlay.open(buildHelp());
            return true;
        }
        return false;
    }

    private void toggleSelection() {
        Integer sel = tableState.selected();
        if (sel == null || sel >= displayRows.size()) return;

        var row = displayRows.get(sel);
        if (row.isGroupHeader()) {
            var group = row.propertyGroup;
            boolean newState = !group.selected;
            group.selected = newState;
            for (var dep : group.dependencies) {
                dep.selected = newState;
            }
        } else if (row.dependency != null && row.dependency.hasUpdate()) {
            var dep = row.dependency;
            boolean newState = !dep.selected;
            dep.selected = newState;
            // If part of a property group, toggle the whole group
            if (dep.isPropertyManaged()) {
                for (var group : reactorResult.propertyGroups) {
                    if (group.dependencies.contains(dep)) {
                        group.selected = newState;
                        for (var other : group.dependencies) {
                            other.selected = newState;
                        }
                        break;
                    }
                }
            }
        }
    }

    private void selectAll() {
        for (var row : displayRows) {
            if (row.isGroupHeader() && row.propertyGroup.hasUpdate()) {
                row.propertyGroup.selected = true;
                for (var dep : row.propertyGroup.dependencies) {
                    dep.selected = true;
                }
            } else if (row.dependency != null && !row.dependency.isPropertyManaged() && row.dependency.hasUpdate()) {
                row.dependency.selected = true;
            }
        }
    }

    private void deselectAll() {
        for (var group : reactorResult.propertyGroups) {
            group.selected = false;
            for (var dep : group.dependencies) {
                dep.selected = false;
            }
        }
        for (var dep : reactorResult.ungroupedDependencies) {
            dep.selected = false;
        }
    }

    // -- Diff preview --

    private void toggleDiffView() {
        Map<String, Map.Entry<String, String>> fileDiffs = new LinkedHashMap<>();

        // Build preview editors for all affected POMs
        Map<Path, PomEditor> previewEditors = new LinkedHashMap<>();

        for (var group : reactorResult.propertyGroups) {
            if (group.selected && group.hasUpdate()) {
                Path pomPath = group.origin.getFile().toPath();
                PomEditor editor = previewEditors.computeIfAbsent(pomPath, p -> {
                    try {
                        return new PomEditor(Document.of(Files.readString(p)));
                    } catch (Exception e) {
                        throw new IllegalStateException("Cannot read POM: " + p, e);
                    }
                });
                editor.properties().updateProperty(true, group.propertyName, group.newestVersion);
            }
        }

        for (var dep : reactorResult.ungroupedDependencies) {
            if (dep.selected && dep.hasUpdate() && !dep.usages.isEmpty()) {
                var managedUsage = dep.usages.stream().filter(u -> u.managed).findFirst();
                Path pomPath;
                boolean managed;
                if (managedUsage.isPresent()) {
                    pomPath = managedUsage.orElseThrow().project.getFile().toPath();
                    managed = true;
                } else {
                    pomPath = dep.usages.get(0).project.getFile().toPath();
                    managed = false;
                }
                PomEditor editor = previewEditors.computeIfAbsent(pomPath, p -> {
                    try {
                        return new PomEditor(Document.of(Files.readString(p)));
                    } catch (Exception e) {
                        throw new IllegalStateException("Cannot read POM: " + p, e);
                    }
                });
                var coords =
                        eu.maveniverse.domtrip.maven.Coordinates.of(dep.groupId, dep.artifactId, dep.newestVersion);
                if (managed) {
                    editor.dependencies().updateManagedDependency(true, coords);
                } else {
                    editor.dependencies().updateDependency(true, coords);
                }
            }
        }

        if (previewEditors.isEmpty()) {
            status = "No updates selected";
            return;
        }

        for (var entry : previewEditors.entrySet()) {
            try {
                String original = Files.readString(entry.getKey());
                String modified = entry.getValue().toXml();
                fileDiffs.put(entry.getKey().getFileName().toString(), Map.entry(original, modified));
            } catch (Exception e) {
                status = "Failed to compute diff: " + e.getMessage();
                return;
            }
        }

        long changes = diffOverlay.openMulti(fileDiffs);
        status = changes == 0
                ? "No changes to show"
                : changes + " line(s) changed across " + fileDiffs.size() + " file(s)";
    }

    // -- Apply --

    private void applyUpdates() {
        Map<Path, List<Map.Entry<String, String>>> propertyUpdates = new LinkedHashMap<>();
        for (var group : reactorResult.propertyGroups) {
            if (group.selected && group.hasUpdate()) {
                Path pomPath = group.origin.getFile().toPath();
                propertyUpdates
                        .computeIfAbsent(pomPath, k -> new ArrayList<>())
                        .add(Map.entry(group.propertyName, group.newestVersion));
            }
        }

        Map<Path, List<Map.Entry<ReactorCollector.AggregatedDependency, Boolean>>> depUpdates = new LinkedHashMap<>();
        for (var dep : reactorResult.ungroupedDependencies) {
            if (dep.selected && dep.hasUpdate() && !dep.usages.isEmpty()) {
                // Prefer managed usage (version is declared in <dependencyManagement>)
                var managedUsage = dep.usages.stream().filter(u -> u.managed).findFirst();
                Path pomPath;
                boolean managed;
                if (managedUsage.isPresent()) {
                    pomPath = managedUsage.orElseThrow().project.getFile().toPath();
                    managed = true;
                } else {
                    pomPath = dep.usages.get(0).project.getFile().toPath();
                    managed = false;
                }
                depUpdates.computeIfAbsent(pomPath, k -> new ArrayList<>()).add(Map.entry(dep, managed));
            }
        }

        if (propertyUpdates.isEmpty() && depUpdates.isEmpty()) {
            status = "No updates selected";
            return;
        }

        try {
            int updatedFiles = 0;
            int totalUpdates = 0;

            // Collect all POM paths that need editing
            Map<Path, PomEditor> editors = new LinkedHashMap<>();

            for (var entry : propertyUpdates.entrySet()) {
                Path pomPath = entry.getKey();
                PomEditor pomEditor = editors.computeIfAbsent(pomPath, p -> {
                    try {
                        return new PomEditor(Document.of(Files.readString(p)));
                    } catch (Exception e) {
                        throw new IllegalStateException("Cannot read POM: " + p, e);
                    }
                });

                for (var propEntry : entry.getValue()) {
                    pomEditor.properties().updateProperty(true, propEntry.getKey(), propEntry.getValue());
                    totalUpdates++;
                }
            }

            for (var entry : depUpdates.entrySet()) {
                Path pomPath = entry.getKey();
                PomEditor pomEditor = editors.computeIfAbsent(pomPath, p -> {
                    try {
                        return new PomEditor(Document.of(Files.readString(p)));
                    } catch (Exception e) {
                        throw new IllegalStateException("Cannot read POM: " + p, e);
                    }
                });

                for (var depEntry : entry.getValue()) {
                    var dep = depEntry.getKey();
                    boolean managed = depEntry.getValue();
                    var coords =
                            eu.maveniverse.domtrip.maven.Coordinates.of(dep.groupId, dep.artifactId, dep.newestVersion);
                    if (managed) {
                        pomEditor.dependencies().updateManagedDependency(true, coords);
                    } else {
                        pomEditor.dependencies().updateDependency(true, coords);
                    }
                    totalUpdates++;
                }
            }

            // Write all modified POMs
            for (var entry : editors.entrySet()) {
                Files.writeString(entry.getKey(), entry.getValue().toXml());
                updatedFiles++;
            }

            // Update model state
            for (var group : reactorResult.propertyGroups) {
                if (group.selected && group.hasUpdate()) {
                    group.resolvedVersion = group.newestVersion;
                    group.newestVersion = null;
                    group.selected = false;
                    group.updateType = null;
                    for (var dep : group.dependencies) {
                        dep.primaryVersion = group.resolvedVersion;
                        dep.newestVersion = null;
                        dep.selected = false;
                        dep.updateType = null;
                    }
                }
            }
            for (var dep : reactorResult.ungroupedDependencies) {
                if (dep.selected && dep.hasUpdate()) {
                    dep.primaryVersion = dep.newestVersion;
                    dep.newestVersion = null;
                    dep.selected = false;
                    dep.updateType = null;
                }
            }

            updateReactorCounts();
            buildDisplayRows();
            status = "Applied " + totalUpdates + " update(s) to " + updatedFiles + " POM file(s)";
        } catch (Exception e) {
            status = "Failed to apply: " + e.getMessage();
        }
    }

    // -- Rendering --

    void render(Frame frame) {
        var zones = Layout.vertical()
                .constraints(Constraint.length(3), Constraint.fill(), Constraint.length(4))
                .split(frame.area());

        renderHeader(frame, zones.get(0));
        lastContentHeight = zones.get(1).height();
        if (helpOverlay.isActive()) {
            helpOverlay.render(frame, zones.get(1));
        } else if (diffOverlay.isActive()) {
            diffOverlay.render(frame, zones.get(1), " POM Changes ");
        } else if (view == View.DEPENDENCIES) {
            renderDepsTable(frame, zones.get(1));
        } else {
            renderModulesTable(frame, zones.get(1));
        }
        renderInfoBar(frame, zones.get(2));
    }

    private void renderHeader(Frame frame, Rect area) {
        String title = loading ? " Pilot \u2014 Checking Updates\u2026 " : " Pilot \u2014 Reactor Updates ";
        Block block = Block.builder()
                .title(title)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().cyan())
                .build();

        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" " + projectGav).bold().cyan());
        spans.add(Span.raw("  (" + reactorModel.allModules.size() + " modules)").dim());
        if (loading) {
            spans.add(Span.raw("  Checking " + loadedCount + "/" + reactorResult.allDependencies.size() + "\u2026")
                    .dim());
        }

        Paragraph header = Paragraph.builder()
                .text(dev.tamboui.text.Text.from(Line.from(spans)))
                .block(block)
                .build();
        frame.renderWidget(header, area);
    }

    private void renderDepsTable(Frame frame, Rect area) {
        long updateCount = reactorResult.allDependencies.stream()
                .filter(ReactorCollector.AggregatedDependency::hasUpdate)
                .count();
        String title = " Dependencies (" + updateCount + " updates) [" + filter + "] ";

        Block block = Block.builder()
                .title(title)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().fg(Color.DARK_GRAY))
                .build();

        if (displayRows.isEmpty()) {
            String msg = loading ? "Checking versions\u2026" : "No updates available";
            Paragraph empty =
                    Paragraph.builder().text(msg).block(block).centered().build();
            frame.renderWidget(empty, area);
            return;
        }

        Row header = Row.from("", "dependency / property", "current", "", "available", "info")
                .style(Style.create().bold().yellow());

        List<Row> rows = new ArrayList<>();
        for (var row : displayRows) {
            rows.add(createReactorRow(row));
        }

        Table table = Table.builder()
                .header(header)
                .rows(rows)
                .widths(
                        Constraint.length(3),
                        Constraint.percentage(40),
                        Constraint.percentage(15),
                        Constraint.length(3),
                        Constraint.percentage(15),
                        Constraint.percentage(10))
                .highlightStyle(Style.create().reversed().bold())
                .highlightSymbol("\u25B8 ")
                .block(block)
                .build();

        frame.renderStatefulWidget(table, area, tableState);
    }

    private Row createReactorRow(ReactorRow row) {
        if (row.isGroupHeader()) {
            var group = row.propertyGroup;
            String check = group.selected ? "[\u2713]" : "[ ]";
            String name = "${" + group.propertyName + "}";
            String current = group.resolvedVersion != null ? group.resolvedVersion : "";
            String arrow = group.hasUpdate() ? "\u2192" : "";
            String available = group.hasUpdate() ? group.newestVersion : "";
            int modCount = group.totalModuleCount();
            String info = modCount + " mod";

            Style style = Style.create().fg(Color.CYAN).bold();
            return Row.from(check, name, current, arrow, available, info).style(style);
        } else {
            var dep = row.dependency;
            String check = dep.selected ? "[\u2713]" : "   ";
            String ga = "  \u21B3 " + dep.artifactId;
            boolean inGroup = dep.isPropertyManaged();
            String current = inGroup ? "" : (dep.primaryVersion != null ? dep.primaryVersion : "");
            String arrow = (!inGroup && dep.hasUpdate()) ? "\u2192" : "";
            String available = (!inGroup && dep.hasUpdate()) ? dep.newestVersion : "";
            int modCount = dep.moduleCount();
            String info = modCount + " mod";
            if (dep.usages.stream().anyMatch(u -> u.managed)) {
                info += " M";
            }

            Style style = dep.updateType != null
                    ? switch (dep.updateType) {
                        case PATCH -> Style.create().fg(Color.GREEN);
                        case MINOR -> Style.create().fg(Color.YELLOW);
                        case MAJOR -> Style.create().fg(Color.RED);
                    }
                    : Style.create();

            return Row.from(check, ga, current, arrow, available, info).style(style);
        }
    }

    private void renderModulesTable(Frame frame, Rect area) {
        String title = " Modules (" + reactorModel.allModules.size() + ") ";

        Block block = Block.builder()
                .title(title)
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

        Row header = Row.from("module", "updates").style(Style.create().bold().yellow());

        List<Row> rows = new ArrayList<>();
        for (var node : visible) {
            rows.add(createModuleRow(node));
        }

        Table table = Table.builder()
                .header(header)
                .rows(rows)
                .widths(Constraint.percentage(75), Constraint.percentage(25))
                .highlightStyle(Style.create().reversed().bold())
                .highlightSymbol("\u25B8 ")
                .block(block)
                .build();

        frame.renderStatefulWidget(table, area, moduleTableState);
    }

    private Row createModuleRow(ReactorModel.ModuleNode node) {
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

        String counts;
        if (node.hasChildren()) {
            counts = node.ownUpdateCount + "/" + node.totalUpdateCount;
        } else {
            counts = String.valueOf(node.ownUpdateCount);
        }

        Style style;
        if (node.totalUpdateCount > 0 || node.ownUpdateCount > 0) {
            style = Style.create().fg(Color.YELLOW);
        } else {
            style = Style.create();
        }

        return Row.from(sb.toString(), counts).style(style);
    }

    private void renderInfoBar(Frame frame, Rect area) {
        var rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.length(1), Constraint.length(1))
                .split(area);

        // View indicator
        List<Span> viewSpans = new ArrayList<>();
        viewSpans.add(Span.raw(" "));
        viewSpans.add(
                view == View.DEPENDENCIES
                        ? Span.raw("[Dependencies]").bold().cyan()
                        : Span.raw(" Dependencies ").dim());
        viewSpans.add(Span.raw(" "));
        viewSpans.add(
                view == View.MODULES
                        ? Span.raw("[Modules]").bold().cyan()
                        : Span.raw(" Modules ").dim());
        frame.renderWidget(Paragraph.from(Line.from(viewSpans)), rows.get(0));

        // Status
        frame.renderWidget(
                Paragraph.from(Line.from(List.of(Span.raw(" " + status).fg(Color.GREEN)))), rows.get(1));

        // Selected count
        long selectedCount = reactorResult.allDependencies.stream()
                .filter(d -> d.selected && d.hasUpdate())
                .count();
        if (selectedCount > 0) {
            frame.renderWidget(
                    Paragraph.from(Line.from(List.of(Span.raw(" " + selectedCount + " update(s) selected")
                            .fg(Color.CYAN)))),
                    rows.get(2));
        }

        // Key bindings
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" "));
        if (diffOverlay.isActive()) {
            spans.add(Span.raw("\u2191\u2193").bold());
            spans.add(Span.raw(":Scroll  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Close  "));
            spans.add(Span.raw("q").bold());
            spans.add(Span.raw(":Quit"));
        } else {
            spans.add(Span.raw("Tab").bold());
            spans.add(Span.raw(":View  "));
            if (view == View.DEPENDENCIES) {
                spans.add(Span.raw("\u2191\u2193").bold());
                spans.add(Span.raw(":Nav  "));
                spans.add(Span.raw("Space").bold());
                spans.add(Span.raw(":Toggle  "));
                spans.add(Span.raw("a").bold());
                spans.add(Span.raw(":All  "));
                spans.add(Span.raw("n").bold());
                spans.add(Span.raw(":None  "));
                spans.add(Span.raw("d").bold());
                spans.add(Span.raw(":Diff  "));
                spans.add(Span.raw("Enter").bold());
                spans.add(Span.raw(":Apply  "));
                spans.add(Span.raw("1-4").bold());
                spans.add(Span.raw(":Filter  "));
                spans.add(Span.raw("h").bold());
                spans.add(Span.raw(":Help  "));
            } else {
                spans.add(Span.raw("\u2191\u2193").bold());
                spans.add(Span.raw(":Nav  "));
                spans.add(Span.raw("\u2190\u2192").bold());
                spans.add(Span.raw(":Expand  "));
                spans.add(Span.raw("h").bold());
                spans.add(Span.raw(":Help  "));
            }
            spans.add(Span.raw("q").bold());
            spans.add(Span.raw(":Quit"));
        }

        frame.renderWidget(Paragraph.from(Line.from(spans)), rows.get(3));
    }

    private List<HelpOverlay.Section> buildHelp() {
        return List.of(
                new HelpOverlay.Section(
                        "Reactor Dependency Updates",
                        List.of(
                                new HelpOverlay.Entry("", "Aggregates dependency updates across all reactor"),
                                new HelpOverlay.Entry("", "modules. Dependencies managed via properties (e.g."),
                                new HelpOverlay.Entry("", "${jackson.version}) are grouped together \u2014 selecting"),
                                new HelpOverlay.Entry("", "the group header toggles all dependencies in it."),
                                new HelpOverlay.Entry("", ""),
                                new HelpOverlay.Entry("", "The 'N mod' column shows how many reactor modules"),
                                new HelpOverlay.Entry("", "use each dependency. 'M' indicates a managed"),
                                new HelpOverlay.Entry("", "dependency (from dependencyManagement)."),
                                new HelpOverlay.Entry("", ""),
                                new HelpOverlay.Entry("", "When you apply updates, property-based deps edit"),
                                new HelpOverlay.Entry("", "the <properties> in the POM that defines them;"),
                                new HelpOverlay.Entry("", "direct deps edit the module's own POM."))),
                new HelpOverlay.Section(
                        "Colors",
                        List.of(
                                new HelpOverlay.Entry("green", "Patch update \u2014 bug fixes, safe to apply"),
                                new HelpOverlay.Entry("yellow", "Minor update \u2014 new features, usually compatible"),
                                new HelpOverlay.Entry("red", "Major update \u2014 breaking changes possible"),
                                new HelpOverlay.Entry("cyan", "Property group header (${property.name})"))),
                new HelpOverlay.Section(
                        "Dependencies View",
                        List.of(
                                new HelpOverlay.Entry("\u2191 / \u2193", "Move selection up / down"),
                                new HelpOverlay.Entry("Space", "Toggle selection (group or individual)"),
                                new HelpOverlay.Entry("a / n", "Select all / deselect all"),
                                new HelpOverlay.Entry("Enter", "Apply selected updates to POM files"),
                                new HelpOverlay.Entry("1-4", "Filter: all / patch / minor / major"),
                                new HelpOverlay.Entry("d", "Preview changes as a multi-file diff"))),
                new HelpOverlay.Section(
                        "Modules View",
                        List.of(
                                new HelpOverlay.Entry("", "Shows the reactor module tree with update counts."),
                                new HelpOverlay.Entry("\u2191 / \u2193", "Move selection up / down"),
                                new HelpOverlay.Entry("\u2190 / \u2192", "Collapse / expand module tree"))),
                new HelpOverlay.Section(
                        "General",
                        List.of(
                                new HelpOverlay.Entry("Tab", "Switch Dependencies / Modules view"),
                                new HelpOverlay.Entry("h", "Toggle this help screen"),
                                new HelpOverlay.Entry("q / Esc", "Quit"))));
    }
}
