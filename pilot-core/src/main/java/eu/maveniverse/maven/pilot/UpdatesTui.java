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
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import eu.maveniverse.domtrip.Document;
import eu.maveniverse.domtrip.maven.Coordinates;
import eu.maveniverse.domtrip.maven.PomEditor;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Interactive TUI for viewing and applying dependency updates.
 *
 * <p>Works in both single-module and multi-module (reactor) mode. In reactor mode,
 * provides two views switchable via Tab:</p>
 * <ul>
 *   <li><b>Dependencies</b> — aggregated list grouped by shared version property</li>
 *   <li><b>Modules</b> — reactor tree with per-module update counts</li>
 * </ul>
 *
 * <p>In single-module mode, the Modules tab is hidden.</p>
 */
public class UpdatesTui extends ToolPanel {

    @FunctionalInterface
    public interface VersionResolver {
        List<String> resolveVersions(String groupId, String artifactId);
    }

    /**
     * Convert a list of version objects to strings ordered newest-first.
     */
    public static List<String> versionsNewestFirst(List<?> versions) {
        List<String> result = versions.stream().map(Object::toString).collect(Collectors.toCollection(ArrayList::new));
        Collections.reverse(result);
        return result;
    }

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

    static class ReactorRow {
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

    private static final String ARROW = " \u2192 ";
    private static final String KEY_NAV = ":Nav  ";
    private static final String KEY_SPACE = "Space";
    private static final String KEY_ENTER = "Enter";

    private final ReactorCollector.CollectionResult reactorResult;
    private final ReactorModel reactorModel;
    private final String projectGav;
    private final boolean singleModule;
    private final VersionResolver versionResolver;
    private final Function<Path, PomEditSession> sessionProvider;
    private final TableState tableState = new TableState();
    private final TableState moduleTableState = new TableState();
    private final ExecutorService httpPool = PilotUtil.newHttpPool();

    private View view = View.DEPENDENCIES;
    List<ReactorRow> displayRows = new ArrayList<>();
    Set<String> duplicatePropertyNames = Set.of();
    private Filter filter = Filter.ALL;
    private final DiffOverlay diffOverlay = new DiffOverlay();
    private final TableState detailTableState = new TableState();
    boolean showDetails = true;
    private int lastContentHeight;
    String status = "Loading updates…";
    boolean loading = true;
    int loadedCount = 0;
    int failedCount = 0;
    int dateFetchesPending;
    boolean datesLoading;

    /**
     * Standalone constructor — creates a local session cache.
     */
    public UpdatesTui(
            ReactorCollector.CollectionResult result,
            ReactorModel reactorModel,
            String projectGav,
            VersionResolver versionResolver) {
        this(result, reactorModel, projectGav, versionResolver, null);
    }

    /**
     * Panel-mode constructor — receives a session provider from the shell.
     */
    public UpdatesTui(
            ReactorCollector.CollectionResult result,
            ReactorModel reactorModel,
            String projectGav,
            VersionResolver versionResolver,
            Function<Path, PomEditSession> sessionProvider) {
        this.reactorResult = result;
        this.reactorModel = reactorModel;
        this.projectGav = projectGav;
        this.singleModule = reactorModel.allModules.size() <= 1;
        this.versionResolver = versionResolver;
        this.sessionProvider = sessionProvider != null ? sessionProvider : defaultSessionProvider();
        this.sortState = new SortState(7);
        if (!reactorModel.allModules.isEmpty()) {
            moduleTableState.select(0);
        }
    }

    private static Function<Path, PomEditSession> defaultSessionProvider() {
        Map<String, PomEditSession> localCache = new ConcurrentHashMap<>();
        return path -> localCache.computeIfAbsent(path.toString(), k -> new PomEditSession(path));
    }

    // -- Version resolution --

    private void fetchAllUpdates() {
        if (reactorResult.allDependencies.isEmpty()) {
            updateStatusIfDone();
            return;
        }
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

    void updateStatusIfDone() {
        if (loadedCount < reactorResult.allDependencies.size()) {
            return;
        }
        loading = false;
        computePropertyGroupVersions();
        updateReactorCounts();
        buildDisplayRows();
        status = buildStatusMessage();
        fetchReleaseDates();
    }

    private void fetchReleaseDates() {
        int count = 0;
        for (var dep : reactorResult.allDependencies) {
            if (dep.hasUpdate()) count += 2;
        }
        dateFetchesPending = count;
        if (dateFetchesPending == 0) return;
        datesLoading = true;
        for (var dep : reactorResult.allDependencies) {
            if (!dep.hasUpdate()) continue;
            CompletableFuture.supplyAsync(
                            () -> ReleaseDateFetcher.fetchReleaseDate(dep.groupId, dep.artifactId, dep.primaryVersion),
                            httpPool)
                    .thenAccept(date -> runner.runOnRenderThread(() -> {
                        dep.currentReleaseDate = date;
                        computeLibYear(dep);
                        propagateLibYearsToGroups();
                        if (--dateFetchesPending <= 0) onDatesComplete();
                    }))
                    .exceptionally(ex -> {
                        runner.runOnRenderThread(() -> {
                            if (--dateFetchesPending <= 0) onDatesComplete();
                        });
                        return null;
                    });
            CompletableFuture.supplyAsync(
                            () -> ReleaseDateFetcher.fetchReleaseDate(dep.groupId, dep.artifactId, dep.newestVersion),
                            httpPool)
                    .thenAccept(date -> runner.runOnRenderThread(() -> {
                        dep.newestReleaseDate = date;
                        computeLibYear(dep);
                        propagateLibYearsToGroups();
                        if (--dateFetchesPending <= 0) onDatesComplete();
                    }))
                    .exceptionally(ex -> {
                        runner.runOnRenderThread(() -> {
                            if (--dateFetchesPending <= 0) onDatesComplete();
                        });
                        return null;
                    });
        }
    }

    private void computeLibYear(ReactorCollector.AggregatedDependency dep) {
        if (dep.currentReleaseDate != null && dep.newestReleaseDate != null) {
            long weeks = ChronoUnit.WEEKS.between(dep.currentReleaseDate, dep.newestReleaseDate);
            dep.libYears = Math.max(0, weeks / 52.0f);
        }
    }

    private void propagateLibYearsToGroups() {
        for (var group : reactorResult.propertyGroups) {
            float maxLy = -1;
            for (var dep : group.dependencies) {
                if (dep.libYears > maxLy) {
                    maxLy = dep.libYears;
                }
            }
            group.libYears = maxLy;
        }
    }

    private void onDatesComplete() {
        datesLoading = false;
        propagateLibYearsToGroups();
        status = buildStatusMessage();
        onSortChanged();
    }

    private String buildStatusMessage() {
        long updates = reactorResult.allDependencies.stream()
                .filter(ReactorCollector.AggregatedDependency::hasUpdate)
                .count();
        String msg = singleModule
                ? updates + " update(s) available"
                : updates + " update(s) available across " + reactorModel.allModules.size() + " modules";
        if (failedCount > 0) {
            msg += "; " + failedCount + " lookup(s) failed";
        }
        if (!datesLoading) {
            float total = totalLibYears();
            if (total > 0) {
                int tenths = Math.round(total * 10);
                msg += " \u2014 " + (tenths / 10) + "." + (tenths % 10) + " libyear(s) behind";
            }
        }
        return msg;
    }

    private float totalLibYears() {
        float total = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (var dep : reactorResult.allDependencies) {
            if (dep.hasUpdate() && dep.libYears >= 0 && seen.add(dep.ga())) {
                total += dep.libYears;
            }
        }
        return total;
    }

    private String formatAge(float libYears, boolean hasUpdate) {
        if (!hasUpdate) return "";
        if (libYears < 0) return datesLoading ? "\u2026" : "";
        int tenths = Math.round(libYears * 10);
        return (tenths / 10) + "." + (tenths % 10) + "y";
    }

    private void computePropertyGroupVersions() {
        for (var group : reactorResult.propertyGroups) {
            for (var dep : group.dependencies) {
                if (dep.hasUpdate()
                        && (group.newestVersion == null
                                || VersionComparator.isNewer(dep.newestVersion, group.newestVersion))) {
                    group.newestVersion = dep.newestVersion;
                    group.updateType = dep.updateType;
                }
            }
        }
    }

    private void updateReactorCounts() {
        Map<String, Integer> countsByModule = new LinkedHashMap<>();
        for (var dep : reactorResult.allDependencies) {
            if (dep.hasUpdate()) {
                for (var usage : dep.usages) {
                    String ga = usage.project.ga();
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

    private static Set<String> computeDuplicatePropertyNames(List<ReactorCollector.PropertyGroup> propertyGroups) {
        Map<String, Integer> propertyCounts = new LinkedHashMap<>();
        for (var group : propertyGroups) {
            propertyCounts.merge(group.propertyName, 1, Integer::sum);
        }
        Set<String> duplicates = new LinkedHashSet<>();
        for (var entry : propertyCounts.entrySet()) {
            if (entry.getValue() > 1) {
                duplicates.add(entry.getKey());
            }
        }
        return duplicates;
    }

    void buildDisplayRows() {
        displayRows = new ArrayList<>();
        duplicatePropertyNames = computeDuplicatePropertyNames(reactorResult.propertyGroups);

        for (var group : reactorResult.propertyGroups) {
            boolean groupHasUpdate = group.hasUpdate()
                    || group.dependencies.stream().anyMatch(ReactorCollector.AggregatedDependency::hasUpdate);
            if (filter != Filter.ALL) {
                groupHasUpdate = matchesFilter(group.updateType);
            }
            if (groupHasUpdate) {
                displayRows.add(ReactorRow.group(group));
                if (group.expanded) {
                    for (var dep : group.dependencies) {
                        displayRows.add(ReactorRow.dep(dep));
                    }
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

    private List<Function<ReactorRow, String>> depsSortExtractors() {
        List<Function<ReactorRow, String>> extractors = new ArrayList<>();
        extractors.add(this::extractCheckColumn);
        extractors.add(this::extractIdColumn);
        extractors.add(this::extractCurrentVersion);
        extractors.add(this::extractArrow);
        extractors.add(this::extractNewestVersion);
        extractors.add(this::extractUpdateType);
        extractors.add(this::extractAge);
        if (!singleModule) {
            extractors.add(this::extractModuleCount);
        }
        return extractors;
    }

    private String extractCheckColumn(ReactorRow row) {
        if (row.isGroupHeader()) {
            return row.propertyGroup.selected ? "[✓]" : "[ ]";
        }
        return row.dependency.selected ? "[✓]" : "   ";
    }

    private String extractIdColumn(ReactorRow row) {
        if (row.isGroupHeader()) {
            return "${" + row.propertyGroup.propertyName + "}";
        }
        return row.dependency.artifactId;
    }

    private String extractCurrentVersion(ReactorRow row) {
        if (row.isGroupHeader()) {
            return row.propertyGroup.resolvedVersion != null ? row.propertyGroup.resolvedVersion : "";
        }
        if (row.dependency.isPropertyManaged()) {
            return "";
        }
        return row.dependency.primaryVersion != null ? row.dependency.primaryVersion : "";
    }

    private String extractArrow(ReactorRow row) {
        if (row.isGroupHeader()) {
            return row.propertyGroup.hasUpdate() ? "→" : "";
        }
        return !row.dependency.isPropertyManaged() && row.dependency.hasUpdate() ? "→" : "";
    }

    private String extractNewestVersion(ReactorRow row) {
        if (row.isGroupHeader()) {
            return row.propertyGroup.hasUpdate() ? row.propertyGroup.newestVersion : "";
        }
        if (!row.dependency.isPropertyManaged() && row.dependency.hasUpdate()) {
            return row.dependency.newestVersion;
        }
        return "";
    }

    private String extractAge(ReactorRow row) {
        float ly;
        if (row.isGroupHeader()) {
            ly = row.propertyGroup.libYears;
        } else if (row.dependency.isPropertyManaged()) {
            return "";
        } else {
            ly = row.dependency.libYears;
        }
        if (ly < 0) return "";
        return String.format(java.util.Locale.US, "%010.3f", ly);
    }

    private String extractModuleCount(ReactorRow row) {
        if (row.isGroupHeader()) {
            return String.valueOf(row.propertyGroup.totalModuleCount());
        }
        return String.valueOf(row.dependency.moduleCount());
    }

    private String extractUpdateType(ReactorRow row) {
        VersionComparator.UpdateType type =
                row.isGroupHeader() ? row.propertyGroup.updateType : row.dependency.updateType;
        return type != null ? type.name() : "";
    }

    private static String updateTypeLabel(VersionComparator.UpdateType type) {
        if (type == null) return "";
        return switch (type) {
            case PATCH -> "patch";
            case MINOR -> "minor";
            case MAJOR -> "major";
        };
    }

    private Style updateTypeStyle(VersionComparator.UpdateType type) {
        if (type == null) return Style.create().dim();
        return switch (type) {
            case PATCH -> Style.create().dim();
            case MINOR -> Style.create();
            case MAJOR -> Style.create().fg(Color.YELLOW);
        };
    }

    @Override
    protected void onSortChanged() {
        if (view == View.DEPENDENCIES) {
            sortState.sort(displayRows, depsSortExtractors());
        }
        // Modules view uses visibleNodes() which is tree-based; sorting not applied there.
    }

    @Override
    protected void updateSearchMatches() {
        String query = searchBuffer.toString().toLowerCase();
        if (query.isEmpty()) {
            searchMatches = List.of();
            searchMatchIndex = -1;
            return;
        }
        searchMatches = new ArrayList<>();
        for (int i = 0; i < displayRows.size(); i++) {
            if (reactorRowMatchesSearch(displayRows.get(i), query)) {
                searchMatches.add(i);
            }
        }
        if (!searchMatches.isEmpty()) {
            searchMatchIndex = 0;
            selectSearchMatch(0);
        } else {
            searchMatchIndex = -1;
        }
    }

    @Override
    protected void selectSearchMatch(int matchIndex) {
        tableState.select(searchMatches.get(matchIndex));
    }

    private boolean reactorRowMatchesSearch(ReactorRow row, String query) {
        if (row.isGroupHeader()) {
            var group = row.propertyGroup;
            String searchable = group.propertyName;
            if (group.resolvedVersion != null) searchable += ":" + group.resolvedVersion;
            if (group.newestVersion != null) searchable += ":" + group.newestVersion;
            return searchable.toLowerCase().contains(query);
        }
        var dep = row.dependency;
        String searchable = dep.groupId + ":" + dep.artifactId;
        if (dep.primaryVersion != null) searchable += ":" + dep.primaryVersion;
        if (dep.newestVersion != null) searchable += ":" + dep.newestVersion;
        return searchable.toLowerCase().contains(query);
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

        // Diff overlay in standalone — allow q to quit
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

        if (key.isCtrlC()) {
            runner.quit();
            return true;
        }

        if (key.isKey(KeyCode.TAB) && !singleModule) {
            view = TabBar.next(view, View.values());
            return true;
        }

        if (view == View.DEPENDENCIES) {
            if (handleKeyEvent(key)) return true;
        } else {
            if (handleModulesEvent(key)) return true;
        }

        // Standalone-specific keys
        if (key.isCharIgnoreCase('q') || key.isKey(KeyCode.ESCAPE)) {
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
        // Diff overlay mode — consume all keys
        if (diffOverlay.isActive()) {
            if (key.isKey(KeyCode.ESCAPE)) {
                diffOverlay.close();
                return true;
            }
            if (diffOverlay.handleScrollKey(key, lastContentHeight)) return true;
            return true; // consume all
        }

        if (view == View.MODULES) {
            return handleModulesPanelEvent(key);
        }

        if (handleSearchInput(key)) return true;
        if (handleSortInput(key)) return true;

        if (key.isUp()) {
            tableState.selectPrevious();
            return true;
        }
        if (key.isDown()) {
            tableState.selectNext(displayRows.size());
            return true;
        }
        if (TableNavigation.handlePageKeys(key, tableState, displayRows.size(), lastContentHeight)) {
            return true;
        }
        if (key.isRight()) {
            handleDepsRightKey();
            return true;
        }
        if (key.isLeft()) {
            handleDepsLeftKey();
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
        if (key.isChar('f')) {
            filter = Filter.values()[(filter.ordinal() + 1) % Filter.values().length];
            buildDisplayRows();
            return true;
        }
        if (key.isChar('F')) {
            int len = Filter.values().length;
            filter = Filter.values()[(filter.ordinal() - 1 + len) % len];
            buildDisplayRows();
            return true;
        }
        if (key.isCharIgnoreCase('i')) {
            showDetails = !showDetails;
            return true;
        }

        return false;
    }

    private void handleDepsRightKey() {
        Integer sel = tableState.selected();
        if (sel != null && sel < displayRows.size()) {
            var row = displayRows.get(sel);
            if (row.isGroupHeader() && !row.propertyGroup.expanded) {
                row.propertyGroup.expanded = true;
                rebuildDisplayRowsKeepSelection();
            } else {
                tableState.selectNext(displayRows.size());
            }
        }
    }

    private void handleDepsLeftKey() {
        Integer sel = tableState.selected();
        if (sel != null && sel < displayRows.size()) {
            var row = displayRows.get(sel);
            if (row.isGroupHeader() && row.propertyGroup.expanded) {
                row.propertyGroup.expanded = false;
                rebuildDisplayRowsKeepSelection();
            } else if (!row.isGroupHeader() && row.dependency != null && row.dependency.isPropertyManaged()) {
                for (int i = sel - 1; i >= 0; i--) {
                    if (displayRows.get(i).isGroupHeader()) {
                        tableState.select(i);
                        break;
                    }
                }
            }
        }
    }

    private boolean handleModulesNavigation(KeyEvent key) {
        List<ReactorModel.ModuleNode> visible = reactorModel.visibleNodes();
        if (key.isUp()) {
            moduleTableState.selectPrevious();
            return true;
        }
        if (key.isDown()) {
            moduleTableState.selectNext(visible.size());
            return true;
        }
        if (TableNavigation.handlePageKeys(key, moduleTableState, visible.size(), lastContentHeight)) {
            return true;
        }
        if (key.isKey(KeyCode.ENTER) || key.isKey(KeyCode.RIGHT)) {
            handleModulesRightKey(visible);
            return true;
        }
        if (key.isKey(KeyCode.LEFT)) {
            handleModulesLeftKey(visible);
            return true;
        }
        return false;
    }

    private void handleModulesRightKey(List<ReactorModel.ModuleNode> visible) {
        Integer sel = moduleTableState.selected();
        if (sel != null && sel < visible.size()) {
            var node = visible.get(sel);
            if (node.hasChildren() && !node.expanded) {
                node.expanded = true;
            } else {
                moduleTableState.selectNext(reactorModel.visibleNodes().size());
            }
        }
    }

    private void handleModulesLeftKey(List<ReactorModel.ModuleNode> visible) {
        Integer sel = moduleTableState.selected();
        if (sel != null && sel < visible.size()) {
            var node = visible.get(sel);
            if (node.expanded && node.hasChildren()) {
                node.expanded = false;
            } else {
                for (int i = sel - 1; i >= 0; i--) {
                    if (visible.get(i).depth < node.depth) {
                        moduleTableState.select(i);
                        break;
                    }
                }
            }
        }
    }

    private void rebuildDisplayRowsKeepSelection() {
        Integer sel = tableState.selected();
        ReactorRow current = (sel != null && sel < displayRows.size()) ? displayRows.get(sel) : null;
        buildDisplayRows();
        if (current != null) {
            for (int i = 0; i < displayRows.size(); i++) {
                var row = displayRows.get(i);
                if (current.isGroupHeader() && row.isGroupHeader() && row.propertyGroup == current.propertyGroup) {
                    tableState.select(i);
                    return;
                }
                if (!current.isGroupHeader() && !row.isGroupHeader() && row.dependency == current.dependency) {
                    tableState.select(i);
                    return;
                }
            }
        }
    }

    private boolean handleModulesPanelEvent(KeyEvent key) {
        return handleModulesNavigation(key);
    }

    private boolean handleModulesEvent(KeyEvent key) {
        if (handleModulesNavigation(key)) return true;
        if (key.isCharIgnoreCase('h')) {
            helpOverlay.open(buildHelpStandalone());
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

        // Build preview editors from session state (not disk)
        Map<Path, PomEditor> previewEditors = new LinkedHashMap<>();

        for (var group : reactorResult.propertyGroups) {
            if (group.selected && group.hasUpdate()) {
                Path pomPath = group.origin.pomPath;
                PomEditor editor = previewEditors.computeIfAbsent(pomPath, p -> {
                    PomEditSession session = sessionProvider.apply(p);
                    return new PomEditor(Document.of(session.currentXml()));
                });
                editor.properties().updateProperty(true, group.propertyName, group.newestVersion);
            }
        }

        for (var dep : reactorResult.ungroupedDependencies) {
            if (dep.selected && dep.hasUpdate() && !dep.usages.isEmpty()) {
                var loc = findUpdateLocation(dep);
                PomEditor editor = previewEditors.computeIfAbsent(loc.pomPath, p -> {
                    PomEditSession session = sessionProvider.apply(p);
                    return new PomEditor(Document.of(session.currentXml()));
                });
                var coords = Coordinates.of(dep.groupId, dep.artifactId, dep.newestVersion);
                if (loc.managed) {
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
            PomEditSession session = sessionProvider.apply(entry.getKey());
            String original = session.originalContent();
            String modified = entry.getValue().toXml();
            fileDiffs.put(entry.getKey().toString(), Map.entry(original, modified));
        }

        long changes = diffOverlay.openMulti(fileDiffs);
        status = changes == 0
                ? "No changes to show"
                : changes + " line(s) changed across " + fileDiffs.size() + " file(s)";
    }

    // -- Apply --

    private record UpdateLocation(Path pomPath, boolean managed) {}

    private UpdateLocation findUpdateLocation(ReactorCollector.AggregatedDependency dep) {
        var managedUsage = dep.usages.stream().filter(u -> u.managed).findFirst();
        if (managedUsage.isPresent()) {
            return new UpdateLocation(managedUsage.orElseThrow().project.pomPath, true);
        }
        return new UpdateLocation(dep.usages.get(0).project.pomPath, false);
    }

    private Map<Path, List<Map.Entry<String, String>>> collectPropertyUpdates() {
        Map<Path, List<Map.Entry<String, String>>> updates = new LinkedHashMap<>();
        for (var group : reactorResult.propertyGroups) {
            if (group.selected && group.hasUpdate()) {
                updates.computeIfAbsent(group.origin.pomPath, k -> new ArrayList<>())
                        .add(Map.entry(group.propertyName, group.newestVersion));
            }
        }
        return updates;
    }

    private Map<Path, List<Map.Entry<ReactorCollector.AggregatedDependency, Boolean>>> collectDependencyUpdates() {
        Map<Path, List<Map.Entry<ReactorCollector.AggregatedDependency, Boolean>>> updates = new LinkedHashMap<>();
        for (var dep : reactorResult.ungroupedDependencies) {
            if (dep.selected && dep.hasUpdate() && !dep.usages.isEmpty()) {
                var loc = findUpdateLocation(dep);
                updates.computeIfAbsent(loc.pomPath, k -> new ArrayList<>()).add(Map.entry(dep, loc.managed));
            }
        }
        return updates;
    }

    private int applyPropertyChanges(
            Map<Path, List<Map.Entry<String, String>>> propertyUpdates, Map<Path, PomEditSession> sessions) {
        int count = 0;
        for (var entry : propertyUpdates.entrySet()) {
            PomEditSession session = sessions.get(entry.getKey());
            for (var propEntry : entry.getValue()) {
                session.editor().properties().updateProperty(true, propEntry.getKey(), propEntry.getValue());
                session.recordChange(
                        PomEditSession.ChangeType.MODIFY,
                        "property",
                        propEntry.getKey(),
                        propEntry.getValue(),
                        "reactor-updates");
                count++;
            }
        }
        return count;
    }

    private int applyDependencyChanges(
            Map<Path, List<Map.Entry<ReactorCollector.AggregatedDependency, Boolean>>> depUpdates,
            Map<Path, PomEditSession> sessions) {
        int count = 0;
        for (var entry : depUpdates.entrySet()) {
            PomEditSession session = sessions.get(entry.getKey());
            for (var depEntry : entry.getValue()) {
                var dep = depEntry.getKey();
                boolean managed = depEntry.getValue();
                var coords = Coordinates.of(dep.groupId, dep.artifactId, dep.newestVersion);
                if (managed) {
                    session.editor().dependencies().updateManagedDependency(true, coords);
                } else {
                    session.editor().dependencies().updateDependency(true, coords);
                }
                session.recordChange(
                        PomEditSession.ChangeType.MODIFY,
                        managed ? "managed" : "dependency",
                        dep.ga(),
                        dep.primaryVersion + ARROW + dep.newestVersion,
                        "reactor-updates");
                count++;
            }
        }
        return count;
    }

    private void clearAppliedUpdates() {
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
    }

    private void applyUpdates() {
        var propertyUpdates = collectPropertyUpdates();
        var depUpdates = collectDependencyUpdates();

        if (propertyUpdates.isEmpty() && depUpdates.isEmpty()) {
            status = "No updates selected";
            return;
        }

        try {
            // Get sessions for all affected POMs and snapshot before mutations
            Map<Path, PomEditSession> sessions = new LinkedHashMap<>();
            for (Path p : propertyUpdates.keySet()) {
                sessions.computeIfAbsent(p, sessionProvider);
            }
            for (Path p : depUpdates.keySet()) {
                sessions.computeIfAbsent(p, sessionProvider);
            }
            for (PomEditSession session : sessions.values()) {
                session.beforeMutation();
            }

            int totalUpdates = applyPropertyChanges(propertyUpdates, sessions);
            totalUpdates += applyDependencyChanges(depUpdates, sessions);

            // Save all modified sessions
            int updatedFiles = 0;
            for (PomEditSession session : sessions.values()) {
                PomEditSession.SaveResult result = session.save();
                if (result.success()) {
                    updatedFiles++;
                } else {
                    status = result.message();
                    return;
                }
            }

            clearAppliedUpdates();
            updateReactorCounts();
            buildDisplayRows();
            status = "Applied " + totalUpdates + " update(s) to " + updatedFiles + " POM file(s)";
        } catch (Exception e) {
            status = "Failed to apply: " + e.getMessage();
        }
    }

    // -- Rendering --

    @Override
    public void render(Frame frame, Rect area) {
        lastContentHeight = area.height();
        if (diffOverlay.isActive()) {
            diffOverlay.render(frame, area, " POM Changes ");
        } else {
            Rect contentArea = renderTabBar(frame, area);
            if (view == View.DEPENDENCIES) {
                boolean detailsVisible = showDetails && !displayRows.isEmpty();
                if (detailsVisible) {
                    var split = Layout.vertical()
                            .constraints(Constraint.fill(), Constraint.length(1), Constraint.percentage(30))
                            .split(contentArea);
                    renderDepsTable(frame, split.get(0));
                    renderDivider(frame, split.get(1));
                    renderDetailPane(frame, split.get(2));
                } else {
                    renderDepsTable(frame, contentArea);
                }
            } else {
                renderModulesTable(frame, contentArea);
            }
        }
    }

    void renderStandalone(Frame frame) {
        boolean detailsVisible =
                showDetails && view == View.DEPENDENCIES && !diffOverlay.isActive() && !helpOverlay.isActive();
        var zones = Layout.vertical()
                .constraints(
                        Constraint.length(3),
                        Constraint.fill(),
                        detailsVisible ? Constraint.percentage(30) : Constraint.length(0),
                        Constraint.length(3))
                .split(frame.area());

        renderHeader(frame, zones.get(0));
        lastContentHeight = zones.get(1).height();
        if (helpOverlay.isActive()) {
            helpOverlay.render(frame, zones.get(1));
        } else if (diffOverlay.isActive()) {
            diffOverlay.render(frame, zones.get(1), " POM Changes ");
        } else if (view == View.DEPENDENCIES) {
            renderDepsTable(frame, zones.get(1));
            if (detailsVisible) {
                renderDetailPane(frame, zones.get(2));
            }
        } else {
            renderModulesTable(frame, zones.get(1));
        }
        renderInfoBar(frame, zones.get(3));
    }

    private void renderHeader(Frame frame, Rect area) {
        String title = loading ? "Checking Updates…" : "Dependency Updates";
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" " + projectGav).bold().cyan());
        spans.addAll(TabBar.render(view, View.values(), v -> switch (v) {
            case DEPENDENCIES -> loading ? "Dependencies" : "Dependencies (" + updateCount() + ")";
            case MODULES -> "Modules";
        }));
        if (!singleModule) {
            spans.add(Span.raw("  (" + reactorModel.allModules.size() + " modules)")
                    .dim());
        }
        if (loading) {
            spans.add(Span.raw("  Checking " + loadedCount + "/" + reactorResult.allDependencies.size() + "…")
                    .dim());
        } else if (filter != Filter.ALL) {
            spans.add(Span.raw(" [" + filter + "]").fg(Color.YELLOW));
        }
        renderStandaloneHeader(frame, area, title, Line.from(spans));
    }

    private void renderDepsTable(Frame frame, Rect area) {
        Block block = Block.builder()
                .borderType(BorderType.ROUNDED)
                .borderStyle(borderStyle())
                .build();

        List<String> headers = List.of("", "dependency / property", "current", "", "available", "type", "age", "info");
        Row header = sortState.decorateHeader(headers, theme.tableHeader());

        List<Row> rows = new ArrayList<>();
        if (displayRows.isEmpty()) {
            String msg = loading ? "Checking versions…" : "No updates available";
            int colCount = 8;
            List<Cell> cells = new ArrayList<>();
            cells.add(Cell.empty());
            cells.add(Cell.from(msg));
            for (int i = 2; i < colCount; i++) cells.add(Cell.empty());
            rows.add(Row.from(cells).style(Style.create().dim()));
        } else {
            for (int i = 0; i < displayRows.size(); i++) {
                rows.add(createReactorRow(displayRows.get(i), isSearchMatch(i)));
            }
        }

        List<Constraint> widths = List.of(
                Constraint.length(3),
                Constraint.percentage(30),
                Constraint.percentage(12),
                Constraint.length(3),
                Constraint.percentage(12),
                Constraint.length(5),
                Constraint.length(6),
                Constraint.percentage(10));
        Table table = Table.builder()
                .header(header)
                .rows(rows)
                .widths(widths)
                .highlightStyle(displayRows.isEmpty() ? Style.create() : theme.highlightStyle())
                .highlightSymbol("▸ ")
                .highlightSpacing(Table.HighlightSpacing.ALWAYS)
                .block(block)
                .build();

        setTableArea(area, block);
        frame.renderStatefulWidget(table, area, tableState);
    }

    private Row createReactorRow(ReactorRow row, boolean highlight) {
        return row.isGroupHeader() ? createGroupHeaderRow(row, highlight) : createDependencyRow(row, highlight);
    }

    private Row createGroupHeaderRow(ReactorRow row, boolean highlight) {
        var group = row.propertyGroup;
        String check = group.selected ? "[✓]" : "[ ]";
        String name = (group.expanded ? "▾ " : "▸ ") + "${" + group.propertyName + "}";
        if (duplicatePropertyNames.contains(group.propertyName)) {
            name += " (" + group.origin.artifactId + ")";
        }
        String current = group.resolvedVersion != null ? group.resolvedVersion : "";
        String arrow = group.hasUpdate() ? "→" : "";
        String available = group.hasUpdate() ? group.newestVersion : "";
        String type = updateTypeLabel(group.updateType);
        String age = formatAge(group.libYears, group.hasUpdate());
        String info = singleModule ? "" : group.totalModuleCount() + " mod";

        Style style = updateTypeStyle(group.updateType);
        if (highlight) style = style.bg(theme.searchHighlightBg());
        return Row.from(check, name, current, arrow, available, type, age, info).style(style);
    }

    private Row createDependencyRow(ReactorRow row, boolean highlight) {
        var dep = row.dependency;
        if (dep.isPropertyManaged()) {
            return createGroupedDependencyRow(dep, highlight);
        }
        return createStandaloneDependencyRow(dep, highlight);
    }

    private Row createGroupedDependencyRow(ReactorCollector.AggregatedDependency dep, boolean highlight) {
        String check = "   ";
        String ga = "  ↳ " + dep.artifactId;
        Style style = Style.create();
        if (highlight) style = style.bg(theme.searchHighlightBg());
        String info = buildModuleInfo(dep);
        return Row.from(check, ga, "", "", "", "", "", info).style(style);
    }

    private Row createStandaloneDependencyRow(ReactorCollector.AggregatedDependency dep, boolean highlight) {
        String check = dep.selected ? "[✓]" : "[ ]";
        String ga = dep.artifactId;
        String current = dep.primaryVersion != null ? dep.primaryVersion : "";
        String arrow = dep.hasUpdate() ? "→" : "";
        String available = dep.hasUpdate() ? dep.newestVersion : "";
        String type = updateTypeLabel(dep.updateType);
        String age = formatAge(dep.libYears, dep.hasUpdate());
        Style style = updateTypeStyle(dep.updateType);
        if (highlight) style = style.bg(theme.searchHighlightBg());
        String info = buildModuleInfo(dep);
        return Row.from(check, ga, current, arrow, available, type, age, info).style(style);
    }

    private String buildModuleInfo(ReactorCollector.AggregatedDependency dep) {
        if (singleModule) {
            return "";
        }
        int modCount = dep.moduleCount();
        String info = modCount + " mod";
        if (dep.usages.stream().anyMatch(u -> u.managed)) {
            info += " M";
        }
        return info;
    }

    private void renderDetailPane(Frame frame, Rect area) {
        Integer sel = tableState.selected();
        if (sel == null || sel >= displayRows.size()) return;

        var row = displayRows.get(sel);
        String title;
        List<Row> rows;

        if (row.isGroupHeader()) {
            var group = row.propertyGroup;
            title = " " + group.rawExpression + " \u2014 Details ";
            rows = buildGroupDetailRows(group);
        } else if (row.dependency != null) {
            var dep = row.dependency;
            title = " " + dep.ga() + " \u2014 Details ";
            rows = buildDependencyDetailRows(dep);
        } else {
            return;
        }

        Block block = Block.builder()
                .title(title)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().cyan())
                .build();

        Table table = Table.builder()
                .rows(rows)
                .widths(Constraint.fill())
                .block(block)
                .build();

        frame.renderStatefulWidget(table, area, detailTableState);
    }

    private List<Row> buildGroupDetailRows(ReactorCollector.PropertyGroup group) {
        List<Row> rows = new ArrayList<>();

        // Origin POM path (relative to reactor root)
        Path originPath = group.origin.pomPath;
        Path rootPath = reactorModel.root.project.basedir;
        String relativePom = rootPath.relativize(originPath).toString();
        rows.add(Row.from(Cell.from(Line.from(
                List.of(Span.raw("Origin:    ").bold(), Span.raw(relativePom).fg(Color.DARK_GRAY))))));

        // Current → available version
        String value = group.resolvedVersion != null ? group.resolvedVersion : "?";
        if (group.hasUpdate()) {
            rows.add(Row.from(Cell.from(Line.from(List.of(
                    Span.raw("Version:   ").bold(),
                    Span.raw(value),
                    Span.raw(ARROW).dim(),
                    Span.raw(group.newestVersion).fg(Color.GREEN))))));
        } else {
            rows.add(
                    Row.from(Cell.from(Line.from(List.of(Span.raw("Version:   ").bold(), Span.raw(value))))));
        }

        // Managed artifacts
        List<Span> artSpans = new ArrayList<>();
        artSpans.add(Span.raw("Artifacts: ").bold());
        for (int i = 0; i < group.dependencies.size(); i++) {
            if (i > 0) artSpans.add(Span.raw(", ").dim());
            artSpans.add(Span.raw(group.dependencies.get(i).artifactId));
        }
        rows.add(Row.from(Cell.from(Line.from(artSpans))));

        // Modules using this group
        Set<String> moduleNames = new LinkedHashSet<>();
        for (var dep : group.dependencies) {
            for (var usage : dep.usages) {
                moduleNames.add(usage.project.artifactId);
            }
        }
        List<Span> modSpans = new ArrayList<>();
        modSpans.add(Span.raw("Modules:   ").bold());
        int idx = 0;
        for (String mod : moduleNames) {
            if (idx++ > 0) modSpans.add(Span.raw(", ").dim());
            modSpans.add(Span.raw(mod));
        }
        rows.add(Row.from(Cell.from(Line.from(modSpans))));

        return rows;
    }

    private List<Row> buildDependencyDetailRows(ReactorCollector.AggregatedDependency dep) {
        List<Row> rows = new ArrayList<>();

        // Full GAV
        String version = dep.primaryVersion != null ? dep.primaryVersion : "?";
        rows.add(Row.from(Cell.from(Line.from(List.of(
                Span.raw("GAV:       ").bold(), Span.raw(dep.groupId + ":" + dep.artifactId + ":" + version))))));

        // Scope(s) in use (default to "compile" when omitted)
        Set<String> scopes = new LinkedHashSet<>();
        for (var u : dep.usages) {
            scopes.add(u.scope != null && !u.scope.isEmpty() ? u.scope : "compile");
        }
        rows.add(Row.from(
                Cell.from(Line.from(List.of(Span.raw("Scope:     ").bold(), Span.raw(String.join(", ", scopes)))))));

        // Managed vs direct
        boolean anyManaged = dep.usages.stream().anyMatch(u -> u.managed);
        boolean allManaged = dep.usages.stream().allMatch(u -> u.managed);
        String management;
        if (allManaged) {
            management = "yes";
        } else if (anyManaged) {
            management = "mixed";
        } else {
            management = "no";
        }
        rows.add(Row.from(Cell.from(Line.from(List.of(Span.raw("Managed:   ").bold(), Span.raw(management))))));

        // Update info
        if (dep.hasUpdate()) {
            rows.add(Row.from(Cell.from(Line.from(List.of(
                    Span.raw("Update:    ").bold(),
                    Span.raw(dep.primaryVersion),
                    Span.raw(ARROW).dim(),
                    Span.raw(dep.newestVersion).fg(Color.GREEN))))));
        }

        // Which modules use this dependency
        List<Span> modSpans = new ArrayList<>();
        modSpans.add(Span.raw("Modules:   ").bold());
        for (int i = 0; i < dep.usages.size(); i++) {
            if (i > 0) modSpans.add(Span.raw(", ").dim());
            modSpans.add(Span.raw(dep.usages.get(i).project.artifactId));
        }
        rows.add(Row.from(Cell.from(Line.from(modSpans))));

        // Property origin (if property-managed)
        if (dep.isPropertyManaged()) {
            String propOrigin = dep.propertyOrigin != null ? dep.propertyOrigin.artifactId : "?";
            rows.add(Row.from(Cell.from(Line.from(List.of(
                    Span.raw("Property:  ").bold(),
                    Span.raw(dep.rawVersionExpr).fg(Color.CYAN),
                    Span.raw(" (" + propOrigin + ")").dim())))));
        }

        return rows;
    }

    private void renderModulesTable(Frame frame, Rect area) {
        Block block = Block.builder()
                .borderType(BorderType.ROUNDED)
                .borderStyle(borderStyle())
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

        Row header = sortState.decorateHeader(List.of("module", "updates"), theme.tableHeader());

        List<Row> rows = new ArrayList<>();
        for (var node : visible) {
            rows.add(createModuleRow(node));
        }

        Table table = Table.builder()
                .header(header)
                .rows(rows)
                .widths(Constraint.percentage(75), Constraint.percentage(25))
                .highlightStyle(theme.highlightStyle())
                .highlightSymbol("▸ ")
                .block(block)
                .build();

        setTableArea(area, block);
        frame.renderStatefulWidget(table, area, moduleTableState);
    }

    private Row createModuleRow(ReactorModel.ModuleNode node) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < node.depth; i++) {
            sb.append("  ");
        }
        if (node.hasChildren()) {
            sb.append(node.expanded ? "▾ " : "▸ ");
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
            style = theme.moduleWithUpdates();
        } else {
            style = Style.create();
        }

        return Row.from(sb.toString(), counts).style(style);
    }

    private void renderInfoBar(Frame frame, Rect area) {
        var rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.length(1))
                .split(area);

        // Status + selected count
        List<Span> statusSpans = new ArrayList<>();
        statusSpans.add(Span.raw(" " + status).fg(theme.standaloneStatusColor()));
        long selectedCount = reactorResult.allDependencies.stream()
                .filter(d -> d.selected && d.hasUpdate())
                .count();
        if (selectedCount > 0) {
            statusSpans.add(
                    Span.raw("  " + selectedCount + " update(s) selected").fg(theme.selectedCountColor()));
        }
        frame.renderWidget(Paragraph.from(Line.from(statusSpans)), rows.get(1));

        // Key bindings
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" "));
        if (diffOverlay.isActive()) {
            buildDiffKeyHints(spans);
        } else {
            if (view == View.DEPENDENCIES) {
                buildDepsKeyHints(spans);
            } else {
                buildModulesKeyHints(spans);
            }
            spans.add(Span.raw("q").bold());
            spans.add(Span.raw(":Quit"));
        }

        frame.renderWidget(Paragraph.from(Line.from(spans)), rows.get(2));
    }

    private void buildDiffKeyHints(List<Span> spans) {
        spans.add(Span.raw("↑↓").bold());
        spans.add(Span.raw(":Scroll  "));
        spans.add(Span.raw("Esc").bold());
        spans.add(Span.raw(":Close  "));
        spans.add(Span.raw("q").bold());
        spans.add(Span.raw(":Quit"));
    }

    private void buildDepsKeyHints(List<Span> spans) {
        spans.add(Span.raw("↑↓").bold());
        spans.add(Span.raw(KEY_NAV));
        spans.add(Span.raw("←→").bold());
        spans.add(Span.raw(":Expand  "));
        spans.add(Span.raw(KEY_SPACE).bold());
        spans.add(Span.raw(":Toggle  "));
        spans.add(Span.raw("a").bold());
        spans.add(Span.raw(":All  "));
        spans.add(Span.raw("n").bold());
        spans.add(Span.raw(":None  "));
        spans.add(Span.raw("d").bold());
        spans.add(Span.raw(":Diff  "));
        spans.add(Span.raw(KEY_ENTER).bold());
        spans.add(Span.raw(":Apply  "));
        spans.add(Span.raw("f").bold());
        spans.add(Span.raw(":Filter  "));
        spans.add(Span.raw("i").bold());
        spans.add(Span.raw(":Details  "));
        spans.add(Span.raw("h").bold());
        spans.add(Span.raw(":Help  "));
    }

    private void buildModulesKeyHints(List<Span> spans) {
        spans.add(Span.raw("↑↓").bold());
        spans.add(Span.raw(KEY_NAV));
        spans.add(Span.raw("←→").bold());
        spans.add(Span.raw(":Expand  "));
        spans.add(Span.raw("h").bold());
        spans.add(Span.raw(":Help  "));
    }

    // -- ToolPanel methods --

    @Override
    public String toolName() {
        return "Updates";
    }

    @Override
    int subViewCount() {
        return 2;
    }

    @Override
    int activeSubView() {
        return view.ordinal();
    }

    @Override
    boolean isSubViewEnabled(int index) {
        return index != View.MODULES.ordinal() || !singleModule;
    }

    @Override
    void setActiveSubView(int index) {
        if (!isSubViewEnabled(index)) return;
        view = View.values()[index];
        clearSearch();
        int depsCols = 8;
        sortState = new SortState(view == View.DEPENDENCIES ? depsCols : 2);
    }

    @Override
    List<String> subViewNames() {
        String depsLabel = "Dependencies" + (loading ? "" : " (" + updateCount() + ")");
        return List.of(depsLabel, "Modules");
    }

    private long updateCount() {
        return reactorResult.allDependencies.stream()
                .filter(ReactorCollector.AggregatedDependency::hasUpdate)
                .count();
    }

    @Override
    public boolean handleMouseEvent(MouseEvent mouse, Rect area) {
        if (handleMouseTabBar(mouse)) return true;
        List<Constraint> widths;
        if (view == View.DEPENDENCIES) {
            widths = List.of(
                    Constraint.length(3),
                    Constraint.percentage(35),
                    Constraint.percentage(14),
                    Constraint.length(3),
                    Constraint.percentage(14),
                    Constraint.length(6),
                    Constraint.percentage(10));
        } else {
            widths = List.of(Constraint.percentage(75), Constraint.percentage(25));
        }
        if (handleMouseSortHeader(mouse, widths)) {
            return true;
        }
        if (view == View.MODULES) {
            List<ReactorModel.ModuleNode> visible = reactorModel.visibleNodes();
            if (mouse.isClick()) {
                int row = mouseToTableRow(mouse, visible.size(), moduleTableState);
                if (row >= 0) {
                    moduleTableState.select(row);
                    return true;
                }
            }
            if (mouse.isScroll()) {
                if (visible.isEmpty()) return false;
                int sel = moduleTableState.selected();
                if (mouse.kind() == MouseEventKind.SCROLL_UP) {
                    moduleTableState.select(Math.max(0, sel - 1));
                } else {
                    moduleTableState.select(Math.min(visible.size() - 1, sel + 1));
                }
                return true;
            }
        } else {
            if (mouse.isClick()) {
                int row = mouseToTableRow(mouse, displayRows.size(), tableState);
                if (row >= 0) {
                    tableState.select(row);
                    return true;
                }
            }
            if (mouse.isScroll()) {
                if (displayRows.isEmpty()) return false;
                int sel = tableState.selected();
                if (mouse.kind() == MouseEventKind.SCROLL_UP) {
                    tableState.select(Math.max(0, sel - 1));
                } else {
                    tableState.select(Math.min(displayRows.size() - 1, sel + 1));
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public String status() {
        String search = searchStatus();
        if (search != null) {
            return searchMode ? search : status + " — " + search;
        }
        return status;
    }

    @Override
    public List<Span> keyHints() {
        List<Span> searchHints = searchKeyHints();
        if (!searchHints.isEmpty()) {
            return searchHints;
        }
        List<Span> spans = new ArrayList<>();
        if (diffOverlay.isActive()) {
            spans.add(Span.raw("↑↓").bold());
            spans.add(Span.raw(":Scroll  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Close"));
        } else {
            spans.add(Span.raw("↑↓").bold());
            spans.add(Span.raw(KEY_NAV));
            spans.add(Span.raw(KEY_SPACE).bold());
            spans.add(Span.raw(":Toggle  "));
            spans.add(Span.raw("a").bold());
            spans.add(Span.raw(":All  "));
            spans.add(Span.raw("n").bold());
            spans.add(Span.raw(":None  "));
            spans.addAll(sortKeyHints());
            spans.add(Span.raw("/").bold());
            spans.add(Span.raw(":Search  "));
            spans.add(Span.raw("d").bold());
            spans.add(Span.raw(":Diff  "));
            spans.add(Span.raw(KEY_ENTER).bold());
            spans.add(Span.raw(":Apply  "));
            spans.add(Span.raw("f").bold());
            spans.add(Span.raw(":Filter"));
        }
        return spans;
    }

    @Override
    public List<HelpOverlay.Section> helpSections() {
        List<HelpOverlay.Entry> descEntries = new ArrayList<>();
        if (singleModule) {
            descEntries.add(new HelpOverlay.Entry("", "Shows available dependency updates for this project."));
            descEntries.add(new HelpOverlay.Entry("", "Dependencies managed via properties (e.g."));
            descEntries.add(new HelpOverlay.Entry("", "${jackson.version}) are grouped together — selecting"));
            descEntries.add(new HelpOverlay.Entry("", "the group header toggles all dependencies in it."));
        } else {
            descEntries.add(new HelpOverlay.Entry("", "Aggregates dependency updates across all reactor"));
            descEntries.add(new HelpOverlay.Entry("", "modules. Dependencies managed via properties (e.g."));
            descEntries.add(new HelpOverlay.Entry("", "${jackson.version}) are grouped together — selecting"));
            descEntries.add(new HelpOverlay.Entry("", "the group header toggles all dependencies in it."));
            descEntries.add(new HelpOverlay.Entry("", ""));
            descEntries.add(new HelpOverlay.Entry("", "The 'N mod' column shows how many reactor modules"));
            descEntries.add(new HelpOverlay.Entry("", "use each dependency. 'M' indicates a managed"));
            descEntries.add(new HelpOverlay.Entry("", "dependency (from dependencyManagement)."));
        }
        descEntries.add(new HelpOverlay.Entry("", ""));
        descEntries.add(new HelpOverlay.Entry("", "When you apply updates, property-based deps edit"));
        descEntries.add(new HelpOverlay.Entry("", "the <properties> in the POM that defines them;"));
        descEntries.add(new HelpOverlay.Entry("", "direct deps edit the module's own POM."));

        String sectionTitle = singleModule ? "Dependency Updates" : "Reactor Dependency Updates";
        String actionsTitle = singleModule ? "Actions" : "Reactor Updates Actions";

        return List.of(
                new HelpOverlay.Section(sectionTitle, descEntries),
                new HelpOverlay.Section(
                        "Colors",
                        List.of(
                                new HelpOverlay.Entry("dim", "Patch update — bug fixes, safe to apply"),
                                new HelpOverlay.Entry("white", "Minor update — new features, usually compatible"),
                                new HelpOverlay.Entry("yellow", "Major update — breaking changes possible"))),
                new HelpOverlay.Section(
                        actionsTitle,
                        List.of(
                                new HelpOverlay.Entry("↑ / ↓", "Move selection up / down"),
                                new HelpOverlay.Entry("← / →", "Collapse / expand property group"),
                                new HelpOverlay.Entry("PgUp / PgDn", "Move selection up / down by one page"),
                                new HelpOverlay.Entry("Home / End", "Jump to first / last row"),
                                new HelpOverlay.Entry(KEY_SPACE, "Toggle selection (group or individual)"),
                                new HelpOverlay.Entry("a / n", "Select all / deselect all"),
                                new HelpOverlay.Entry(KEY_ENTER, "Apply selected updates to POM files"),
                                new HelpOverlay.Entry("f / F", "Cycle filter: all → patch → minor → major"),
                                new HelpOverlay.Entry("d", "Preview changes as a multi-file diff"),
                                new HelpOverlay.Entry("i", "Toggle detail pane for selected row"))));
    }

    @Override
    public void setRunner(TuiRunner runner) {
        super.setRunner(runner);
        if (loading) {
            fetchAllUpdates();
        }
    }

    @Override
    void close() {
        httpPool.shutdownNow();
    }

    // -- Help --

    private List<HelpOverlay.Section> buildHelpStandalone() {
        List<HelpOverlay.Section> sections = new ArrayList<>(helpSections());
        if (!singleModule) {
            sections.addAll(HelpOverlay.parse("""
                    ## Modules View
                    Shows the reactor module tree with update counts.
                    """ + NAV_KEYS + """
                    ← / →           Collapse / expand module tree
                    """));
        }
        String tabEntry = singleModule ? "" : "Tab             Switch Dependencies / Modules view\n";
        sections.addAll(HelpOverlay.parse("""
                ## General
                """ + tabEntry + """
                h               Toggle this help screen
                q / Esc         Quit
                """));
        return sections;
    }
}
