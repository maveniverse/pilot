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
import dev.tamboui.text.Text;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import eu.maveniverse.domtrip.maven.Coordinates;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/**
 * Interactive TUI for license and security audit.
 */
public class AuditTui extends ToolPanel {

    public static class AuditEntry {
        final String groupId;
        final String artifactId;
        final String version;
        final String scope;
        public final List<String> modules = new ArrayList<>();
        String license;
        String licenseUrl;
        List<OsvClient.Vulnerability> vulnerabilities;
        boolean licenseLoaded;
        boolean vulnsLoaded;

        public AuditEntry(String groupId, String artifactId, String version, String scope) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.scope = scope != null ? scope : "compile";
        }

        String ga() {
            return groupId + ":" + artifactId;
        }

        String gav() {
            return groupId + ":" + artifactId + ":" + version;
        }

        boolean hasVulnerabilities() {
            return vulnerabilities != null && !vulnerabilities.isEmpty();
        }
    }

    private static final String HIGHLIGHT_SYMBOL = "\u25B8 ";
    private static final String SEVERITY_CRITICAL = "CRITICAL";
    private static final String SEVERITY_HIGH = "HIGH";
    private static final String SEVERITY_MEDIUM = "MEDIUM";
    private static final String SEVERITY_LOW = "LOW";
    private static final String TO_DEPENDENCY_MANAGEMENT = " to dependencyManagement";

    private enum View {
        VULNERABILITIES,
        LICENSES
    }

    /** Row in the by-license view: either a license group header or a dependency. */
    private static class LicenseRow {
        final String licenseName;
        final String licenseUrl;
        final List<AuditEntry> deps; // non-null for group headers
        final AuditEntry entry; // non-null for dependency rows
        boolean expanded;

        static LicenseRow group(String name, String url, List<AuditEntry> deps) {
            var r = new LicenseRow(name, url, deps, null);
            return r;
        }

        static LicenseRow dep(AuditEntry entry) {
            return new LicenseRow(null, null, null, entry);
        }

        private LicenseRow(String licenseName, String licenseUrl, List<AuditEntry> deps, AuditEntry entry) {
            this.licenseName = licenseName;
            this.licenseUrl = licenseUrl;
            this.deps = deps;
            this.entry = entry;
        }

        boolean isGroup() {
            return deps != null;
        }
    }

    private final List<AuditEntry> entries;
    private final String projectGav;
    private final DependencyTreeModel treeModel;
    private final TableState tableState = new TableState();
    private final ExecutorService httpPool = PilotUtil.newHttpPool();
    private final OsvClient osvClient = new OsvClient();

    private static final String COL_SCOPE = "scope";
    private static final String LABEL_SCOPE = "  scope: ";
    private static final List<String> SCOPE_FILTERS = List.of("compile", "runtime", "test", "provided");
    private final DiffOverlay diffOverlay = new DiffOverlay();
    private final TableState vulnTableState = new TableState();
    private final TableState byLicenseTableState = new TableState();

    private View view = View.LICENSES;
    private boolean licensesGrouped = false;
    private int licensesLoaded = 0;
    private int vulnsLoaded = 0;
    private int vulnCount = 0;
    private String status;
    private boolean pendingQuit;
    private int lastContentHeight;
    private int lastTableHeight;
    private String scopeFilter; // null = show all
    private PomEditSession managementSession;
    private List<AuditEntry> filteredEntries;
    List<VulnRow> vulnRows = new ArrayList<>();
    private List<VulnGroup> vulnGroups = new ArrayList<>();
    private List<VulnDisplayRow> vulnDisplayRows = new ArrayList<>();
    private List<LicenseRow> byLicenseRows = new ArrayList<>();

    /** Flattened vulnerability row linking back to its parent entry. */
    record VulnRow(AuditEntry entry, OsvClient.Vulnerability vuln) {}

    /** Group of vulnerability rows sharing the same CVE ID. */
    static class VulnGroup {
        final String id;
        final String summary;
        final String severity;
        final String published;
        final List<VulnRow> rows = new ArrayList<>();
        boolean expanded = false;

        VulnGroup(String id, String summary, String severity, String published) {
            this.id = id;
            this.summary = summary;
            this.severity = severity;
            this.published = published;
        }
    }

    /** Display row in the vulnerability table: either a group header or an artifact entry. */
    record VulnDisplayRow(VulnGroup group, VulnRow row) {
        boolean isGroupHeader() {
            return row == null;
        }
    }

    /** Standalone constructor — creates its own PomEditSession from the POM file path. */
    public AuditTui(List<AuditEntry> entries, String projectGav, DependencyTreeModel treeModel, String pomPath) {
        this(entries, projectGav, treeModel, new PomEditSession(Path.of(pomPath)), null);
    }

    /** Panel-mode constructor — uses a shared PomEditSession from the shell. */
    public AuditTui(
            List<AuditEntry> entries,
            String projectGav,
            DependencyTreeModel treeModel,
            PomEditSession session,
            PomEditSession managementSession) {
        this.editSession = session;
        this.managementSession = managementSession != null ? managementSession : session;
        this.entries = entries;
        this.filteredEntries = entries;
        this.projectGav = projectGav;
        this.treeModel = treeModel;
        this.sortState = new SortState(4);
        this.status = "Loading license and vulnerability data…";
        if (!entries.isEmpty()) {
            tableState.select(0);
        }
    }

    /** Initialize counters from pre-populated entry data (for tests). */
    void initFromEntries() {
        licensesLoaded = (int) entries.stream().filter(e -> e.licenseLoaded).count();
        vulnsLoaded = (int) entries.stream().filter(e -> e.vulnsLoaded).count();
        vulnCount = entries.stream()
                .filter(AuditEntry::hasVulnerabilities)
                .mapToInt(e -> e.vulnerabilities.size())
                .sum();
        rebuildVulnRows();
        rebuildByLicenseRows();
        updateStatus();
    }

    private void fetchAllData() {
        for (var entry : entries) {
            fetchLicenseForEntry(entry);
            fetchVulnsForEntry(entry);
        }
        if (licensesLoaded > 0) {
            rebuildByLicenseRows();
        }
        if (vulnCount > 0) {
            rebuildVulnRows();
        }
        updateStatus();
    }

    private void fetchLicenseForEntry(AuditEntry entry) {
        if (entry.licenseLoaded) {
            licensesLoaded++;
            return;
        }
        CompletableFuture.supplyAsync(
                        () -> SearchTui.fetchPomFromCentral(entry.groupId, entry.artifactId, entry.version), httpPool)
                .thenAccept(info -> runner.runOnRenderThread(() -> {
                    if (info != null && info.license != null) {
                        entry.license = info.license;
                        entry.licenseUrl = info.licenseUrl;
                    }
                    entry.licenseLoaded = true;
                    licensesLoaded++;
                    rebuildByLicenseRows();
                    updateStatus();
                }));
    }

    private void fetchVulnsForEntry(AuditEntry entry) {
        if (entry.vulnsLoaded) {
            vulnsLoaded++;
            if (entry.hasVulnerabilities()) {
                vulnCount += entry.vulnerabilities.size();
            }
            return;
        }
        CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return osvClient.query(entry.groupId, entry.artifactId, entry.version);
                            } catch (Exception e) {
                                return List.<OsvClient.Vulnerability>of();
                            }
                        },
                        httpPool)
                .thenAccept(vulns -> runner.runOnRenderThread(() -> {
                    entry.vulnerabilities = vulns;
                    entry.vulnsLoaded = true;
                    vulnsLoaded++;
                    if (!vulns.isEmpty()) {
                        vulnCount += vulns.size();
                        rebuildVulnRows();
                    }
                    updateStatus();
                }));
    }

    void rebuildVulnRows() {
        vulnRows = new ArrayList<>();
        for (var entry : entries) {
            if (entry.hasVulnerabilities()) {
                if (scopeFilter != null && !scopeFilter.equalsIgnoreCase(entry.scope)) continue;
                for (var vuln : entry.vulnerabilities) {
                    vulnRows.add(new VulnRow(entry, vuln));
                }
            }
        }

        Set<String> expandedIds = collectExpandedIds();
        vulnGroups = groupVulnsByCve(expandedIds);
        rebuildVulnDisplayRows();

        if (vulnDisplayRows.isEmpty()) {
            vulnTableState.clearSelection();
        } else if (vulnTableState.selected() == null || vulnTableState.selected() >= vulnDisplayRows.size()) {
            vulnTableState.select(0);
        }
    }

    private Set<String> collectExpandedIds() {
        var expandedIds = new HashSet<String>();
        for (var group : vulnGroups) {
            if (group.expanded) {
                expandedIds.add(group.id);
                for (var vr : group.rows) {
                    expandedIds.add(vr.vuln().id);
                    if (vr.vuln().aliases != null) {
                        expandedIds.addAll(vr.vuln().aliases);
                    }
                }
            }
        }
        return expandedIds;
    }

    private List<VulnGroup> groupVulnsByCve(Set<String> expandedIds) {
        Map<String, VulnGroup> groupMap = new LinkedHashMap<>();
        Map<String, String> aliasToId = new HashMap<>();
        for (var vr : vulnRows) {
            String groupId = resolveGroupId(vr.vuln(), aliasToId);
            VulnGroup group = groupMap.get(groupId);
            if (group == null) {
                group = createVulnGroup(vr, groupId, expandedIds, aliasToId, groupMap);
            } else if (vr.vuln().aliases != null) {
                for (String alias : vr.vuln().aliases) {
                    aliasToId.putIfAbsent(alias, groupId);
                }
            }
            boolean alreadyPresent = group.rows.stream()
                    .anyMatch(r -> r.entry().gav().equals(vr.entry().gav()));
            if (!alreadyPresent) {
                group.rows.add(vr);
            }
        }
        return new ArrayList<>(groupMap.values());
    }

    private static String resolveGroupId(OsvClient.Vulnerability vuln, Map<String, String> aliasToId) {
        String groupId = aliasToId.getOrDefault(vuln.id, vuln.id);
        if (vuln.aliases != null) {
            for (String alias : vuln.aliases) {
                String mapped = aliasToId.get(alias);
                if (mapped != null) {
                    return mapped;
                }
            }
        }
        return groupId;
    }

    private VulnGroup createVulnGroup(
            VulnRow vr,
            String groupId,
            Set<String> expandedIds,
            Map<String, String> aliasToId,
            Map<String, VulnGroup> groupMap) {
        String id = vr.vuln().id;
        String severity = normalizeSeverity(vr.vuln().severity);
        VulnGroup group = new VulnGroup(id, vr.vuln().summary, severity, vr.vuln().published);
        group.expanded = expandedIds.contains(id);
        groupMap.put(groupId, group);
        aliasToId.put(id, groupId);
        if (vr.vuln().aliases != null) {
            for (String alias : vr.vuln().aliases) {
                aliasToId.put(alias, groupId);
            }
        }
        return group;
    }

    private void rebuildVulnDisplayRows() {
        vulnDisplayRows = new ArrayList<>();
        for (var group : vulnGroups) {
            vulnDisplayRows.add(new VulnDisplayRow(group, null));
            if (group.expanded) {
                for (var vr : group.rows) {
                    vulnDisplayRows.add(new VulnDisplayRow(group, vr));
                }
            }
        }
    }

    private void rebuildFilteredEntries() {
        if (scopeFilter == null) {
            filteredEntries = entries;
        } else {
            filteredEntries = new ArrayList<>();
            for (var entry : entries) {
                if (scopeFilter.equalsIgnoreCase(entry.scope)) {
                    filteredEntries.add(entry);
                }
            }
        }
        if (filteredEntries.isEmpty()) {
            tableState.clearSelection();
        } else if (tableState.selected() != null && tableState.selected() >= filteredEntries.size()) {
            tableState.select(0);
        }
    }

    private void cycleScope() {
        if (scopeFilter == null) {
            scopeFilter = SCOPE_FILTERS.get(0);
        } else {
            int idx = SCOPE_FILTERS.indexOf(scopeFilter);
            scopeFilter = (idx + 1 >= SCOPE_FILTERS.size()) ? null : SCOPE_FILTERS.get(idx + 1);
        }
        rebuildFilteredEntries();
        rebuildVulnRows();
        rebuildByLicenseRows();
    }

    private void rebuildByLicenseRows() {
        // Preserve expansion state
        var expanded = new HashSet<String>();
        for (var row : byLicenseRows) {
            if (row.isGroup() && row.expanded) expanded.add(row.licenseName);
        }

        // Group entries by normalized license name
        var groups = new LinkedHashMap<String, List<AuditEntry>>();
        var urls = new HashMap<String, String>();
        for (var entry : entries) {
            if (!entry.licenseLoaded) continue;
            if (scopeFilter != null && !scopeFilter.equalsIgnoreCase(entry.scope)) continue;
            String key = entry.license != null ? normalizeLicense(entry.license, entry.licenseUrl) : "(not specified)";
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
            if (entry.licenseUrl != null && !urls.containsKey(key)) {
                urls.put(key, entry.licenseUrl);
            }
        }

        // Sort groups by usage count (descending)
        var sortedGroups = new ArrayList<>(groups.entrySet());
        sortedGroups.sort(
                (a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));

        // Build flat row list
        byLicenseRows = new ArrayList<>();
        for (var e : sortedGroups) {
            var group = LicenseRow.group(e.getKey(), urls.get(e.getKey()), e.getValue());
            group.expanded = expanded.contains(e.getKey());
            byLicenseRows.add(group);
            if (group.expanded) {
                for (var dep : e.getValue()) {
                    byLicenseRows.add(LicenseRow.dep(dep));
                }
            }
        }

        // Re-apply active sort so expand/collapse preserves order
        if (view == View.LICENSES && licensesGrouped && sortState.isSorted()) {
            sortByLicenseTree();
        }

        if (byLicenseRows.isEmpty()) {
            byLicenseTableState.clearSelection();
        } else if (byLicenseTableState.selected() == null || byLicenseTableState.selected() >= byLicenseRows.size()) {
            byLicenseTableState.select(0);
        }
    }

    private void updateStatus() {
        if (licensesLoaded >= entries.size() && vulnsLoaded >= entries.size()) {
            long withLicense = entries.stream().filter(e -> e.license != null).count();
            status = withLicense + "/" + entries.size() + " with license info, " + vulnCount + " vulnerabilities found";
        } else {
            status = "Loading… licenses: " + licensesLoaded + "/" + entries.size() + ", vulnerabilities: " + vulnsLoaded
                    + "/" + entries.size();
        }
    }

    @Override
    public boolean handleKeyEvent(KeyEvent key) {
        // Diff overlay mode — consume all keys
        if (diffOverlay.isActive()) {
            if (key.isKey(KeyCode.ESCAPE) || key.isCharIgnoreCase('q') || key.isCharIgnoreCase('d')) {
                diffOverlay.close();
                return true;
            }
            if (diffOverlay.handleScrollKey(key, lastContentHeight)) return true;
            return true; // consume all
        }

        if (handleSearchInput(key)) return true;
        if (handleSortInput(key)) return true;

        if (key.isUp()) {
            activeTableState().selectPrevious();
            return true;
        }
        if (key.isDown()) {
            activeTableState().selectNext(activeRowCount());
            return true;
        }

        if (view == View.VULNERABILITIES && handleVulnExpandCollapse(key)) return true;
        if (view == View.LICENSES && licensesGrouped && handleLicenseExpandCollapse(key)) return true;

        if (key.isCharIgnoreCase('m')) {
            addManagedDependency();
            return true;
        }

        if (key.isCharIgnoreCase('d')) {
            toggleDiffView();
            return true;
        }

        if (key.isCharIgnoreCase('g') && view == View.LICENSES) {
            licensesGrouped = !licensesGrouped;
            clearSearch();
            sortState = new SortState(4);
            if (licensesGrouped) {
                rebuildByLicenseRows();
            } else {
                rebuildFilteredEntries();
            }
            return true;
        }

        return false;
    }

    private boolean handleVulnExpandCollapse(KeyEvent key) {
        if (key.isRight()) {
            int idx = vulnTableState.selected() != null ? vulnTableState.selected() : -1;
            if (idx >= 0
                    && idx < vulnDisplayRows.size()
                    && vulnDisplayRows.get(idx).isGroupHeader()) {
                var group = vulnDisplayRows.get(idx).group();
                if (!group.expanded) {
                    group.expanded = true;
                    rebuildVulnDisplayRows();
                } else {
                    vulnTableState.selectNext(vulnDisplayRows.size());
                }
                return true;
            }
        }
        if (key.isLeft()) {
            int idx = vulnTableState.selected() != null ? vulnTableState.selected() : -1;
            if (idx >= 0 && idx < vulnDisplayRows.size()) {
                var dr = vulnDisplayRows.get(idx);
                if (dr.isGroupHeader() && dr.group().expanded) {
                    dr.group().expanded = false;
                    rebuildVulnDisplayRows();
                    return true;
                } else if (!dr.isGroupHeader()) {
                    for (int i = idx - 1; i >= 0; i--) {
                        if (vulnDisplayRows.get(i).isGroupHeader()) {
                            vulnTableState.select(i);
                            break;
                        }
                    }
                    return true;
                }
            }
        }
        if (key.isKey(KeyCode.ENTER) || key.isChar(' ')) {
            int idx = vulnTableState.selected() != null ? vulnTableState.selected() : -1;
            if (idx >= 0
                    && idx < vulnDisplayRows.size()
                    && vulnDisplayRows.get(idx).isGroupHeader()) {
                var group = vulnDisplayRows.get(idx).group();
                group.expanded = !group.expanded;
                rebuildVulnDisplayRows();
            }
            return true;
        }
        return false;
    }

    private boolean handleLicenseExpandCollapse(KeyEvent key) {
        if (key.isRight()) {
            int idx = byLicenseTableState.selected() != null ? byLicenseTableState.selected() : -1;
            if (idx >= 0 && idx < byLicenseRows.size() && byLicenseRows.get(idx).isGroup()) {
                if (!byLicenseRows.get(idx).expanded) {
                    byLicenseRows.get(idx).expanded = true;
                    rebuildByLicenseRows();
                } else {
                    byLicenseTableState.selectNext(byLicenseRows.size());
                }
                return true;
            }
        }
        if (key.isLeft()) {
            int idx = byLicenseTableState.selected() != null ? byLicenseTableState.selected() : -1;
            if (idx >= 0 && idx < byLicenseRows.size()) {
                LicenseRow row = byLicenseRows.get(idx);
                if (row.isGroup() && row.expanded) {
                    row.expanded = false;
                    rebuildByLicenseRows();
                    return true;
                } else if (!row.isGroup()) {
                    for (int i = idx - 1; i >= 0; i--) {
                        if (byLicenseRows.get(i).isGroup()) {
                            byLicenseTableState.select(i);
                            break;
                        }
                    }
                    return true;
                }
            }
        }
        if (key.isKey(KeyCode.ENTER) || key.isChar(' ')) {
            int idx = byLicenseTableState.selected() != null ? byLicenseTableState.selected() : -1;
            if (idx >= 0 && idx < byLicenseRows.size() && byLicenseRows.get(idx).isGroup()) {
                byLicenseRows.get(idx).expanded = !byLicenseRows.get(idx).expanded;
                rebuildByLicenseRows();
            }
            return true;
        }
        return false;
    }

    boolean handleEvent(Event event, TuiRunner runner) {
        if (!(event instanceof KeyEvent key)) {
            return true;
        }

        if (helpOverlay.isActive()) {
            if (helpOverlay.handleKey(key)) return true;
            if (key.isCharIgnoreCase('q') || key.isCtrlC()) {
                requestQuit();
                return true;
            }
            return false;
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
                updateStatus();
                return true;
            }
            return false;
        }

        // Diff overlay in standalone
        if (diffOverlay.isActive()) {
            if (key.isKey(KeyCode.ESCAPE) || key.isCharIgnoreCase('q') || key.isCharIgnoreCase('d')) {
                diffOverlay.close();
                return true;
            }
            if (diffOverlay.handleScrollKey(key, lastContentHeight)) return true;
            if (key.isCtrlC()) {
                requestQuit();
                return true;
            }
            return false;
        }

        if (key.isCtrlC()) {
            requestQuit();
            return true;
        }

        // Delegate tool-specific keys (up/down, sort, search, expand/collapse, m, d)
        if (handleKeyEvent(key)) return true;
        if (TableNavigation.handlePageKeys(key, activeTableState(), activeRowCount(), lastTableHeight)) {
            return true;
        }

        // Standalone: Tab switches views
        if (key.isKey(KeyCode.TAB)) {
            view = TabBar.next(view, View.values());
            return true;
        }

        if (key.isChar('s')) {
            cycleScope();
            return true;
        }

        if (key.isCharIgnoreCase('q') || key.isKey(KeyCode.ESCAPE)) {
            requestQuit();
            return true;
        }

        if (key.isCharIgnoreCase('h')) {
            helpOverlay.open(buildHelpStandalone());
            return true;
        }

        return false;
    }

    private AuditEntry selectedEntry() {
        return switch (view) {
            case LICENSES -> selectedLicenseEntry();
            case VULNERABILITIES -> selectedVulnEntry();
        };
    }

    private AuditEntry selectedLicenseEntry() {
        if (licensesGrouped) {
            int idx = byLicenseTableState.selected() != null ? byLicenseTableState.selected() : -1;
            return (idx >= 0
                            && idx < byLicenseRows.size()
                            && !byLicenseRows.get(idx).isGroup())
                    ? byLicenseRows.get(idx).entry
                    : null;
        }
        int idx = tableState.selected() != null ? tableState.selected() : -1;
        return (idx >= 0 && idx < filteredEntries.size()) ? filteredEntries.get(idx) : null;
    }

    private AuditEntry selectedVulnEntry() {
        int idx = vulnTableState.selected() != null ? vulnTableState.selected() : -1;
        if (idx >= 0 && idx < vulnDisplayRows.size()) {
            var dr = vulnDisplayRows.get(idx);
            if (dr.row() != null) {
                return dr.row().entry();
            }
            return dr.group().rows.isEmpty() ? null : dr.group().rows.get(0).entry();
        }
        return null;
    }

    private void addManagedDependency() {
        if (view == View.VULNERABILITIES) {
            int idx = vulnTableState.selected() != null ? vulnTableState.selected() : -1;
            if (idx >= 0
                    && idx < vulnDisplayRows.size()
                    && vulnDisplayRows.get(idx).isGroupHeader()) {
                manageGroup(vulnDisplayRows.get(idx).group());
                return;
            }
        }
        AuditEntry entry = selectedEntry();
        if (entry == null) {
            status = "No dependency selected";
            return;
        }
        manageSingleEntry(entry);
    }

    private void manageSingleEntry(AuditEntry entry) {
        if (editSession.isChanged(entry.ga())) {
            status = entry.ga() + " already managed";
            return;
        }
        try {
            editSession.beforeMutation();
            var coords = Coordinates.of(entry.groupId, entry.artifactId, entry.version);
            editSession.addManagedDependencyAligned(coords, managementSession);
            editSession.recordChange(
                    PomEditSession.ChangeType.ADD,
                    "managed",
                    entry.ga(),
                    "added " + entry.version + TO_DEPENDENCY_MANAGEMENT,
                    "audit");
            status = "Added " + entry.ga() + ":" + entry.version + TO_DEPENDENCY_MANAGEMENT;
        } catch (Exception e) {
            status = "Failed: " + e.getMessage();
        }
    }

    private void manageGroup(VulnGroup group) {
        int count = 0;
        for (var vr : group.rows) {
            if (!editSession.isChanged(vr.entry().ga())) {
                manageSingleEntry(vr.entry());
                count++;
            }
        }
        if (count == 0) {
            status = "All artifacts in " + group.id + " already managed";
        } else {
            status = "Added " + count + " artifact(s) from " + group.id + TO_DEPENDENCY_MANAGEMENT;
        }
    }

    private void requestQuit() {
        if (isDirty()) {
            pendingQuit = true;
            status = "Save changes to POM? (y/n/Esc)";
        } else {
            runner.quit();
        }
    }

    private void saveAndQuit() {
        PomEditSession.SaveResult result = editSession.save();
        if (result.success()) {
            runner.quit();
        } else {
            pendingQuit = false;
            status = result.message();
        }
    }

    private void toggleDiffView() {
        var diffs = collectAllDiffs();
        if (diffs.isEmpty()) {
            status = "No changes to show";
            return;
        }
        long changes = diffOverlay.openMulti(diffs);
        status = changes == 0 ? "No changes to show" : changes + " line(s) changed across " + diffs.size() + " file(s)";
    }

    @Override
    protected Line buildSubViewTabLine() {
        Line base = super.buildSubViewTabLine();
        if (vulnsLoaded < entries.size()) {
            List<Span> spans = new ArrayList<>(base.spans());
            spans.add(Span.raw("  checking vulnerabilities\u2026 " + vulnsLoaded + "/" + entries.size())
                    .fg(Color.DARK_GRAY));
            return Line.from(spans);
        }
        return base;
    }

    // -- Rendering --

    @Override
    public void render(Frame frame, Rect area) {
        lastContentHeight = area.height();
        if (diffOverlay.isActive()) {
            diffOverlay.render(frame, area, " POM Changes ");
        } else {
            Rect contentArea = renderTabBar(frame, area);
            switch (view) {
                case LICENSES -> {
                    if (licensesGrouped) renderByLicense(frame, contentArea);
                    else renderLicenses(frame, contentArea);
                }
                case VULNERABILITIES -> renderVulnerabilities(frame, contentArea);
            }
        }
    }

    void renderStandalone(Frame frame) {
        var zones = Layout.vertical()
                .constraints(Constraint.length(3), Constraint.fill(), Constraint.length(3))
                .split(frame.area());

        renderHeader(frame, zones.get(0));

        Rect contentArea = renderStandaloneHelp(frame, zones.get(1));
        if (contentArea != null) {
            lastContentHeight = contentArea.height();
            if (diffOverlay.isActive()) {
                diffOverlay.render(frame, contentArea, " POM Changes ");
            } else {
                switch (view) {
                    case LICENSES -> {
                        if (licensesGrouped) renderByLicense(frame, contentArea);
                        else renderLicenses(frame, contentArea);
                    }
                    case VULNERABILITIES -> renderVulnerabilities(frame, contentArea);
                }
            }
        }

        renderInfoBar(frame, zones.get(2));
    }

    private void renderHeader(Frame frame, Rect area) {
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" " + projectGav).bold().cyan());
        if (isDirty()) {
            spans.add(theme.dirtyIndicator());
        }
        spans.addAll(TabBar.render(
                view,
                View.values(),
                v -> switch (v) {
                    case LICENSES -> licensesGrouped ? "Licenses (grouped)" : "Licenses";
                    case VULNERABILITIES -> "Vulnerabilities" + (vulnCount > 0 ? " (" + vulnCount + ")" : "");
                },
                v -> v == View.VULNERABILITIES && vulnCount > 0 ? Color.RED : Color.YELLOW));
        if (vulnsLoaded < entries.size()) {
            spans.add(Span.raw("  Checking vulnerabilities\u2026 " + vulnsLoaded + "/" + entries.size())
                    .fg(Color.DARK_GRAY));
        }
        renderStandaloneHeader(frame, area, "License & Security Audit", Line.from(spans));
    }

    private void renderLicenses(Frame frame, Rect area) {
        var zones = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1), Constraint.length(6))
                .split(area);
        lastTableHeight = zones.get(0).height();

        String filterLabel = scopeFilter != null ? " [" + scopeFilter + "]" : "";
        Block block = Block.builder()
                .borderType(BorderType.ROUNDED)
                .borderStyle(borderStyle())
                .build();

        Row header = sortState.decorateHeader(
                List.of("groupId:artifactId", "version", "license", COL_SCOPE), theme.tableHeader());

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < filteredEntries.size(); i++) {
            var entry = filteredEntries.get(i);
            boolean managed = editSession != null && editSession.isChanged(entry.ga());
            String license = entry.license != null
                    ? normalizeLicense(entry.license, entry.licenseUrl)
                    : (entry.licenseLoaded ? "-" : "…");
            Style style = managed ? Style.create().dim() : getLicenseStyle(entry.license);
            if (view == View.LICENSES && isSearchMatch(i)) {
                style = style.bg(theme.searchHighlightBg());
            }
            String marker = managed ? "✓ " : "";
            rows.add(Row.from(marker + entry.ga(), entry.version, license, entry.scope)
                    .style(style));
        }

        Table table = Table.builder()
                .header(header)
                .rows(rows)
                .widths(
                        Constraint.percentage(40), Constraint.percentage(15),
                        Constraint.percentage(30), Constraint.percentage(15))
                .highlightStyle(theme.highlightStyle())
                .highlightSymbol(HIGHLIGHT_SYMBOL)
                .block(block)
                .build();

        setTableArea(zones.get(0), block);
        frame.renderStatefulWidget(table, zones.get(0), tableState);
        renderDivider(frame, zones.get(1));

        // -- Detail pane --
        renderLicenseDetail(frame, zones.get(2));
    }

    private void renderLicenseDetail(Frame frame, Rect area) {
        Block block = Block.builder()
                .title(" Details ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(theme.unfocusedBorder())
                .build();

        int idx = tableState.selected() != null ? tableState.selected() : -1;
        if (idx < 0 || idx >= filteredEntries.size()) {
            frame.renderWidget(Paragraph.builder().text("").block(block).build(), area);
            return;
        }

        AuditEntry entry = filteredEntries.get(idx);
        List<Span> spans = new ArrayList<>();
        String centralUrl = centralUrl(entry.groupId, entry.artifactId);
        spans.add(Span.raw(entry.gav()).bold().cyan().hyperlink(centralUrl));
        spans.add(Span.raw(LABEL_SCOPE).fg(theme.detailSeparatorColor()));
        spans.add(Span.raw(entry.scope));
        spans.add(Span.raw("  ↗ ").fg(theme.detailSeparatorColor()));
        spans.add(Span.raw(centralUrl).fg(theme.linkColor()).hyperlink(centralUrl));

        List<Span> licSpans = new ArrayList<>();
        if (entry.license != null) {
            licSpans.add(Span.raw("License: ").fg(theme.detailSeparatorColor()));
            if (entry.licenseUrl != null && !entry.licenseUrl.isEmpty()) {
                licSpans.add(Span.raw(entry.license)
                        .style(getLicenseStyle(entry.license))
                        .hyperlink(entry.licenseUrl));
                licSpans.add(Span.raw("  ↗ ").fg(theme.detailSeparatorColor()));
                licSpans.add(Span.raw(entry.licenseUrl).fg(theme.linkColor()).hyperlink(entry.licenseUrl));
            } else {
                licSpans.add(Span.raw(entry.license).style(getLicenseStyle(entry.license)));
            }
        } else if (entry.licenseLoaded) {
            licSpans.add(Span.raw("License: ").fg(theme.detailSeparatorColor()));
            licSpans.add(Span.raw("not specified").fg(theme.detailSeparatorColor()));
        } else {
            licSpans.add(Span.raw("Loading…").fg(theme.detailSeparatorColor()));
        }

        List<Line> lines = new ArrayList<>();
        lines.add(Line.from(spans));
        lines.add(Line.from(licSpans));
        Line modulesLine = buildModulesLine(entry);
        if (modulesLine != null) lines.add(modulesLine);

        Paragraph detail =
                Paragraph.builder().text(Text.from(lines)).block(block).build();
        frame.renderWidget(detail, area);
    }

    private Line buildModulesLine(AuditEntry entry) {
        if (entry.modules.isEmpty()) return null;
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw("Modules: ").fg(theme.detailSeparatorColor()));
        for (int i = 0; i < entry.modules.size(); i++) {
            if (i > 0) spans.add(Span.raw(", ").fg(theme.detailSeparatorColor()));
            spans.add(Span.raw(entry.modules.get(i)).fg(theme.moduleColor()));
        }
        return Line.from(spans);
    }

    private Line buildPathLine(AuditEntry entry) {
        if (treeModel == null) return null;
        var node = treeModel.findByGA(entry.ga());
        if (node == null) return null;
        var path = treeModel.pathToRoot(node);
        if (path.size() <= 1) return null; // root or direct dep — no path to show
        List<Span> pathSpans = new ArrayList<>();
        pathSpans.add(Span.raw("Path: ").fg(theme.detailSeparatorColor()));
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) pathSpans.add(Span.raw(" → ").fg(theme.detailSeparatorColor()));
            var n = path.get(i);
            if (i == 0) {
                pathSpans.add(Span.raw(n.artifactId).dim());
            } else if (i == path.size() - 1) {
                pathSpans.add(Span.raw(n.ga()).bold());
            } else if (i == 1) {
                // Direct dependency — highlight
                pathSpans.add(Span.raw(n.ga()).cyan());
            } else {
                pathSpans.add(Span.raw(n.artifactId).dim());
            }
        }
        return Line.from(pathSpans);
    }

    private TableState activeTableState() {
        return switch (view) {
            case VULNERABILITIES -> vulnTableState;
            case LICENSES -> licensesGrouped ? byLicenseTableState : tableState;
        };
    }

    private int activeRowCount() {
        return switch (view) {
            case VULNERABILITIES -> vulnDisplayRows.size();
            case LICENSES -> licensesGrouped ? byLicenseRows.size() : filteredEntries.size();
        };
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
        switch (view) {
            case LICENSES -> {
                if (licensesGrouped) {
                    for (int i = 0; i < byLicenseRows.size(); i++) {
                        if (licenseRowMatchesSearch(byLicenseRows.get(i), query)) {
                            searchMatches.add(i);
                        }
                    }
                } else {
                    for (int i = 0; i < filteredEntries.size(); i++) {
                        if (auditEntryMatchesSearch(filteredEntries.get(i), query)) {
                            searchMatches.add(i);
                        }
                    }
                }
            }
            case VULNERABILITIES -> {
                for (int i = 0; i < vulnDisplayRows.size(); i++) {
                    var dr = vulnDisplayRows.get(i);
                    if (dr.isGroupHeader()) {
                        var g = dr.group();
                        if (g.id.toLowerCase().contains(query)
                                || (g.summary != null && g.summary.toLowerCase().contains(query))) {
                            searchMatches.add(i);
                        }
                    } else {
                        var vr = dr.row();
                        if (auditEntryMatchesSearch(vr.entry(), query)
                                || vr.vuln().id.toLowerCase().contains(query)
                                || (vr.vuln().summary != null
                                        && vr.vuln().summary.toLowerCase().contains(query))) {
                            searchMatches.add(i);
                        }
                    }
                }
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
        activeTableState().select(searchMatches.get(matchIndex));
    }

    private boolean auditEntryMatchesSearch(AuditEntry entry, String query) {
        String searchable = entry.groupId + ":" + entry.artifactId + ":" + entry.version;
        if (entry.license != null) searchable += ":" + entry.license;
        if (entry.scope != null) searchable += ":" + entry.scope;
        return searchable.toLowerCase().contains(query);
    }

    private boolean licenseRowMatchesSearch(LicenseRow row, String query) {
        if (row.isGroup()) {
            return row.licenseName != null && row.licenseName.toLowerCase().contains(query);
        }
        return auditEntryMatchesSearch(row.entry, query);
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
    void setActiveSubView(int index) {
        view = View.values()[index];
        clearSearch();
        sortState = view == View.VULNERABILITIES
                ? new SortState(5, 3, false) // default sort: published date descending
                : new SortState(4);
    }

    @Override
    List<String> subViewNames() {
        return List.of("Vulns", "Licenses");
    }

    private List<Function<AuditEntry, String>> licensesExtractors() {
        return List.of(
                AuditEntry::ga,
                e -> e.version,
                e -> e.license != null ? normalizeLicense(e.license, e.licenseUrl) : "",
                e -> e.scope);
    }

    private List<Function<VulnGroup, String>> vulnGroupExtractors() {
        return List.of(
                g -> g.id,
                g -> severitySortKey(g.severity),
                g -> "",
                g -> g.published != null ? g.published : "",
                g -> g.summary);
    }

    private List<Function<LicenseRow, String>> byLicenseGroupExtractors() {
        return List.of(r -> r.licenseName, r -> String.valueOf(r.deps.size()), r -> "", r -> "");
    }

    private List<Function<AuditEntry, String>> byLicenseChildExtractors() {
        return List.of(AuditEntry::ga, e -> e.version, e -> e.scope, e -> "");
    }

    @Override
    protected void onSortChanged() {
        switch (view) {
            case LICENSES -> {
                if (licensesGrouped) sortByLicenseTree();
                else sortState.sort(entries, licensesExtractors());
            }
            case VULNERABILITIES -> {
                sortState.sort(vulnGroups, vulnGroupExtractors());
                rebuildVulnDisplayRows();
            }
        }
    }

    private void sortByLicenseTree() {
        // Extract and sort groups
        var sortedGroups = new ArrayList<LicenseRow>();
        for (var row : byLicenseRows) {
            if (row.isGroup()) sortedGroups.add(row);
        }
        sortState.sort(sortedGroups, byLicenseGroupExtractors());
        // Rebuild flat list, sorting children within each group
        var childExtractors = byLicenseChildExtractors();
        byLicenseRows = new ArrayList<>();
        for (var group : sortedGroups) {
            byLicenseRows.add(group);
            if (group.expanded) {
                var sortedDeps = new ArrayList<>(group.deps);
                sortState.sort(sortedDeps, childExtractors);
                for (var dep : sortedDeps) {
                    byLicenseRows.add(LicenseRow.dep(dep));
                }
            }
        }
    }

    private void renderByLicense(Frame frame, Rect area) {
        if (byLicenseRows.isEmpty()) {
            Block block = Block.builder()
                    .borderType(BorderType.ROUNDED)
                    .borderStyle(borderStyle())
                    .build();
            String msg = (licensesLoaded >= entries.size())
                    ? "No license data available"
                    : "Loading licenses… " + licensesLoaded + "/" + entries.size();
            frame.renderWidget(
                    Paragraph.builder().text(msg).block(block).centered().build(), area);
            return;
        }

        // Split into table + separator + detail pane
        var zones = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1), Constraint.length(6))
                .split(area);
        lastTableHeight = zones.get(0).height();

        Block block = Block.builder()
                .borderType(BorderType.ROUNDED)
                .borderStyle(borderStyle())
                .build();

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < byLicenseRows.size(); i++) {
            var row = byLicenseRows.get(i);
            boolean highlight = isSearchMatch(i);
            if (row.isGroup()) {
                String arrow = row.expanded ? "▾ " : HIGHLIGHT_SYMBOL;
                String label = arrow + row.licenseName + " (" + row.deps.size() + ")";
                Style style = getLicenseStyle("(not specified)".equals(row.licenseName) ? null : row.licenseName)
                        .bold();
                if (highlight) style = style.bg(theme.searchHighlightBg());
                rows.add(Row.from(label, "", "", "").style(style));
            } else {
                boolean managed = editSession != null && editSession.isChanged(row.entry.ga());
                Style style = managed ? Style.create().dim() : theme.unfocusedBorder();
                if (highlight) style = style.bg(theme.searchHighlightBg());
                String prefix = managed ? "  ✓ " : "    ";
                rows.add(Row.from(prefix + row.entry.ga(), row.entry.version, row.entry.scope, "")
                        .style(style));
            }
        }

        Row header =
                sortState.decorateHeader(List.of("license / artifact", "version", COL_SCOPE, ""), theme.tableHeader());

        Table table = Table.builder()
                .header(header)
                .rows(rows)
                .widths(
                        Constraint.percentage(55), Constraint.percentage(20),
                        Constraint.percentage(15), Constraint.percentage(10))
                .highlightStyle(theme.highlightStyle())
                .highlightSymbol(HIGHLIGHT_SYMBOL)
                .block(block)
                .build();

        setTableArea(zones.get(0), block);
        frame.renderStatefulWidget(table, zones.get(0), byLicenseTableState);
        renderDivider(frame, zones.get(1));

        // -- Detail pane --
        renderByLicenseDetail(frame, zones.get(2));
    }

    private long countLicenseGroups() {
        return byLicenseRows.stream().filter(LicenseRow::isGroup).count();
    }

    private void renderByLicenseDetail(Frame frame, Rect area) {
        Block block = Block.builder()
                .title(" Details ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(theme.unfocusedBorder())
                .build();

        int idx = byLicenseTableState.selected() != null ? byLicenseTableState.selected() : -1;
        if (idx < 0 || idx >= byLicenseRows.size()) {
            frame.renderWidget(Paragraph.builder().text("").block(block).build(), area);
            return;
        }

        LicenseRow row = byLicenseRows.get(idx);
        List<Line> lines = new ArrayList<>();

        if (row.isGroup()) {
            List<Span> titleSpans = new ArrayList<>();
            titleSpans.add(Span.raw(row.licenseName).bold().cyan());
            titleSpans.add(Span.raw("  " + row.deps.size() + " dependencies").fg(theme.detailSeparatorColor()));
            lines.add(Line.from(titleSpans));

            if (row.licenseUrl != null && !row.licenseUrl.isEmpty()) {
                lines.add(Line.from(
                        Span.raw("URL: ").fg(theme.detailSeparatorColor()),
                        Span.raw(row.licenseUrl).fg(theme.linkColor()).hyperlink(row.licenseUrl)));
            }
        } else {
            String centralUrl = centralUrl(row.entry.groupId, row.entry.artifactId);
            lines.add(Line.from(
                    Span.raw(row.entry.gav()).bold().cyan().hyperlink(centralUrl),
                    Span.raw(LABEL_SCOPE).fg(theme.detailSeparatorColor()),
                    Span.raw(row.entry.scope),
                    Span.raw("  ↗ ").fg(theme.detailSeparatorColor()),
                    Span.raw(centralUrl).fg(theme.linkColor()).hyperlink(centralUrl)));
            if (row.entry.license != null) {
                List<Span> licSpans = new ArrayList<>();
                licSpans.add(Span.raw("License: ").fg(theme.detailSeparatorColor()));
                if (row.entry.licenseUrl != null && !row.entry.licenseUrl.isEmpty()) {
                    licSpans.add(Span.raw(row.entry.license)
                            .style(getLicenseStyle(row.entry.license))
                            .hyperlink(row.entry.licenseUrl));
                    licSpans.add(Span.raw("  ↗ ").fg(theme.detailSeparatorColor()));
                    licSpans.add(
                            Span.raw(row.entry.licenseUrl).fg(theme.linkColor()).hyperlink(row.entry.licenseUrl));
                } else {
                    licSpans.add(Span.raw(row.entry.license).style(getLicenseStyle(row.entry.license)));
                }
                lines.add(Line.from(licSpans));
            }
            Line modulesLine = buildModulesLine(row.entry);
            if (modulesLine != null) lines.add(modulesLine);
        }

        Paragraph detail =
                Paragraph.builder().text(Text.from(lines)).block(block).build();
        frame.renderWidget(detail, area);
    }

    private void renderVulnerabilities(Frame frame, Rect area) {
        if (vulnDisplayRows.isEmpty() && vulnsLoaded >= entries.size()) {
            Block block = Block.builder()
                    .borderType(BorderType.ROUNDED)
                    .borderStyle(borderStyle())
                    .build();
            String msg = scopeFilter != null
                    ? "No known vulnerabilities in " + scopeFilter + " scope"
                    : "No known vulnerabilities found \u2713";
            Paragraph empty =
                    Paragraph.builder().text(msg).block(block).centered().build();
            frame.renderWidget(empty, area);
            clearTableArea();
            return;
        }

        // Split into table + separator + detail pane
        var zones = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1), Constraint.length(8))
                .split(area);
        lastTableHeight = zones.get(0).height();

        // -- Vulnerability table --
        Block block = Block.builder()
                .borderType(BorderType.ROUNDED)
                .borderStyle(borderStyle())
                .build();

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < vulnDisplayRows.size(); i++) {
            rows.add(buildVulnTableRow(vulnDisplayRows.get(i), i));
        }

        Row header = sortState.decorateHeader(
                List.of("artifact / CVE", "severity", COL_SCOPE, "published", "summary"), theme.tableHeader());

        Table table = Table.builder()
                .header(header)
                .rows(rows)
                .widths(
                        Constraint.percentage(35),
                        Constraint.percentage(10),
                        Constraint.percentage(9),
                        Constraint.percentage(11),
                        Constraint.percentage(35))
                .highlightStyle(theme.highlightStyle())
                .highlightSymbol(HIGHLIGHT_SYMBOL)
                .block(block)
                .build();

        setTableArea(zones.get(0), block);
        frame.renderStatefulWidget(table, zones.get(0), vulnTableState);
        renderDivider(frame, zones.get(1));

        // -- Detail pane --
        renderVulnDetail(frame, zones.get(2));
    }

    private Row buildVulnTableRow(VulnDisplayRow dr, int i) {
        return dr.isGroupHeader() ? buildVulnGroupHeaderRow(dr.group(), i) : buildVulnArtifactRow(dr.row(), i);
    }

    private Row buildVulnGroupHeaderRow(VulnGroup group, int i) {
        boolean allManaged = editSession != null
                && group.rows.stream()
                        .allMatch(r -> editSession.isChanged(r.entry().ga()));
        String arrow = group.expanded ? "▾ " : "▸ ";
        String severity = normalizeSeverity(group.severity);
        String published = formatPublished(group.published);
        int affected = group.rows.size();
        String countLabel = affected > 1 ? " (" + affected + " artifacts)" : "";
        Style style =
                allManaged ? Style.create().dim() : getSeverityStyle(severity).bold();
        if (view == View.VULNERABILITIES && isSearchMatch(i)) {
            style = style.bg(theme.searchHighlightBg());
        }
        String statusIcon = allManaged ? "✓ " : "";
        return Row.from(
                Cell.from(statusIcon + arrow + group.id + countLabel).style(style),
                Cell.from(severity).style(style),
                Cell.from("").style(style),
                Cell.from(published).style(style),
                Cell.from(truncateSummary(group.summary, 50)).style(style));
    }

    private Row buildVulnArtifactRow(VulnRow vr, int i) {
        boolean managed =
                editSession != null && editSession.isChanged(vr.entry().ga());
        String severity = normalizeSeverity(vr.vuln().severity);
        Style style = managed ? Style.create().dim() : getSeverityStyle(severity);
        if (view == View.VULNERABILITIES && isSearchMatch(i)) {
            style = style.bg(theme.searchHighlightBg());
        }
        String marker = managed ? "  ✓ " : "    ";
        return Row.from(
                Cell.from(marker + vr.entry().ga() + ":" + vr.entry().version).style(style),
                Cell.from("").style(style),
                Cell.from(vr.entry().scope).style(managed ? Style.create().dim() : getScopeStyle(vr.entry().scope)),
                Cell.from("").style(style),
                Cell.from("").style(style));
    }

    private void renderVulnDetail(Frame frame, Rect area) {
        Block block = Block.builder()
                .title(" Details ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(theme.unfocusedBorder())
                .build();

        int idx = vulnTableState.selected() != null ? vulnTableState.selected() : -1;
        if (idx < 0 || idx >= vulnDisplayRows.size()) {
            frame.renderWidget(Paragraph.builder().text("").block(block).build(), area);
            return;
        }

        var dr = vulnDisplayRows.get(idx);
        List<Line> lines = dr.isGroupHeader() ? buildGroupDetailLines(dr.group()) : buildArtifactDetailLines(dr.row());

        Paragraph detail =
                Paragraph.builder().text(Text.from(lines)).block(block).build();
        frame.renderWidget(detail, area);
    }

    private List<Line> buildGroupDetailLines(VulnGroup group) {
        List<Line> lines = new ArrayList<>();
        String url = vulnUrl(group.id);
        String severity = normalizeSeverity(group.severity);
        lines.add(Line.from(
                Span.raw(group.id).bold().cyan().hyperlink(url),
                Span.raw("  "),
                Span.raw(severity).style(getSeverityStyle(severity).bold()),
                Span.raw("  " + group.rows.size() + " affected artifact(s)").fg(theme.detailSeparatorColor())));
        lines.add(Line.from(
                Span.raw("↗ ").fg(theme.detailSeparatorColor()),
                Span.raw(url).fg(theme.linkColor()).hyperlink(url)));
        if (group.published != null && !group.published.isEmpty()) {
            String pub = group.published.length() > 10 ? group.published.substring(0, 10) : group.published;
            lines.add(Line.from(Span.raw("Published: ").fg(theme.detailSeparatorColor()), Span.raw(pub)));
        }
        lines.add(Line.from(Span.raw(group.summary != null ? group.summary : "")));
        List<Span> artifacts = new ArrayList<>();
        artifacts.add(Span.raw("Artifacts: ").fg(theme.detailSeparatorColor()));
        for (int i = 0; i < group.rows.size(); i++) {
            if (i > 0) artifacts.add(Span.raw(", ").fg(theme.detailSeparatorColor()));
            var r = group.rows.get(i);
            artifacts.add(Span.raw(r.entry().ga() + ":" + r.entry().version));
        }
        lines.add(Line.from(artifacts));
        return lines;
    }

    private List<Line> buildArtifactDetailLines(VulnRow vr) {
        List<Line> lines = new ArrayList<>();
        var vuln = vr.vuln();
        String url = vulnUrl(vuln.id);
        String severity = normalizeSeverity(vuln.severity);
        String cUrl = centralUrl(vr.entry().groupId, vr.entry().artifactId);
        lines.add(Line.from(
                Span.raw(vuln.id).bold().cyan().hyperlink(url),
                Span.raw("  "),
                Span.raw(severity).style(getSeverityStyle(severity).bold()),
                Span.raw(LABEL_SCOPE).fg(Color.DARK_GRAY),
                Span.raw(vr.entry().scope).style(getScopeStyle(vr.entry().scope)),
                Span.raw("  "),
                Span.raw(vr.entry().ga() + ":" + vr.entry().version)
                        .fg(theme.detailSeparatorColor())
                        .hyperlink(cUrl)));
        lines.add(Line.from(
                Span.raw("↗ ").fg(theme.detailSeparatorColor()),
                Span.raw(url).fg(theme.linkColor()).hyperlink(url)));
        if (!vuln.aliases.isEmpty()) {
            List<Span> aliasSpans = new ArrayList<>();
            aliasSpans.add(Span.raw("Aliases: ").fg(theme.detailSeparatorColor()));
            for (int i = 0; i < vuln.aliases.size(); i++) {
                if (i > 0) aliasSpans.add(Span.raw(", "));
                String alias = vuln.aliases.get(i);
                aliasSpans.add(Span.raw(alias).hyperlink(vulnUrl(alias)));
            }
            lines.add(Line.from(aliasSpans));
        }
        if (vuln.published != null && !vuln.published.isEmpty()) {
            String pub = vuln.published.length() > 10 ? vuln.published.substring(0, 10) : vuln.published;
            lines.add(Line.from(Span.raw("Published: ").fg(theme.detailSeparatorColor()), Span.raw(pub)));
        }
        lines.add(Line.from(Span.raw(vuln.summary != null ? vuln.summary : "")));
        Line modulesLine = buildModulesLine(vr.entry());
        if (modulesLine != null) lines.add(modulesLine);
        return lines;
    }

    private static String centralUrl(String groupId, String artifactId) {
        return "https://central.sonatype.com/artifact/" + groupId + "/" + artifactId;
    }

    private static String vulnUrl(String id) {
        if (id.startsWith("CVE-")) {
            return "https://nvd.nist.gov/vuln/detail/" + id;
        }
        if (id.startsWith("GHSA-")) {
            return "https://github.com/advisories/" + id;
        }
        return "https://osv.dev/vulnerability/" + id;
    }

    static String normalizeSeverity(String severity) {
        if (severity == null) return "UNKNOWN";
        String upper = severity.toUpperCase(Locale.ROOT);
        if ("MODERATE".equals(upper)) return SEVERITY_MEDIUM;
        return upper;
    }

    static String formatPublished(String published) {
        if (published == null) return "";
        return published.length() >= 10 ? published.substring(0, 10) : published;
    }

    static String truncateSummary(String summary, int maxLen) {
        if (summary == null || summary.isEmpty()) return "";
        if (summary.length() <= maxLen) return summary;
        return summary.substring(0, maxLen - 3) + "...";
    }

    /** Return a sort key for severity so that sorting is semantic (CRITICAL > HIGH > MEDIUM > LOW) rather than alphabetic. */
    private static String severitySortKey(String severity) {
        return switch (normalizeSeverity(severity)) {
            case SEVERITY_CRITICAL -> "0";
            case SEVERITY_HIGH -> "1";
            case SEVERITY_MEDIUM -> "2";
            case SEVERITY_LOW -> "3";
            default -> "4";
        };
    }

    private Style getSeverityStyle(String severity) {
        if (severity == null) return theme.severityUnknown();
        return switch (severity.toUpperCase()) {
            case SEVERITY_CRITICAL -> theme.severityCritical();
            case SEVERITY_HIGH -> theme.severityHigh();
            case SEVERITY_MEDIUM -> theme.severityMedium();
            case SEVERITY_LOW -> theme.severityLow();
            default -> {
                if (severity.toUpperCase().startsWith("CVSS")) {
                    yield theme.severityHigh();
                }
                yield theme.severityUnknown();
            }
        };
    }

    static Style getScopeStyle(String scope) {
        if (scope == null) return Style.create();
        return switch (scope.trim().toLowerCase(Locale.ROOT)) {
            case "test" -> Style.create().fg(Color.DARK_GRAY);
            case "provided" -> Style.create().fg(Color.DARK_GRAY);
            case "runtime" -> Style.create().fg(Color.YELLOW);
            default -> Style.create();
        };
    }

    private Style getLicenseStyle(String license) {
        if (license == null) return theme.licenseUnknown();
        String normalized = normalizeLicense(license, null);
        // GPL with classpath exception is weak copyleft
        if (normalized.equals("GPL-2.0-CE")) {
            return theme.licenseWeakCopyleft();
        }
        // Strong copyleft
        if (normalized.startsWith("AGPL") || normalized.startsWith("GPL")) {
            return theme.licenseStrongCopyleft();
        }
        // Permissive
        if (normalized.startsWith("Apache")
                || normalized.equals("MIT")
                || normalized.startsWith("BSD")
                || normalized.equals("ISC")
                || normalized.equals("Unlicense")
                || normalized.equals("CC0-1.0")
                || normalized.equals("Public Domain")
                || normalized.startsWith("EDL")) {
            return theme.licensePermissive();
        }
        // Weak copyleft
        if (normalized.startsWith("LGPL")
                || normalized.startsWith("MPL")
                || normalized.startsWith("EPL")
                || normalized.startsWith("CDDL")) {
            return theme.licenseWeakCopyleft();
        }
        return theme.licenseUnknown();
    }

    // -- Well-known license URL → SPDX mapping --
    private static final Map<String, String> LICENSE_URL_MAP = Map.ofEntries(
            // Apache
            Map.entry("apache.org/licenses/license-2.0", "Apache-2.0"),
            Map.entry("www.apache.org/licenses/license-2.0", "Apache-2.0"),
            // MIT
            Map.entry("opensource.org/licenses/mit", "MIT"),
            Map.entry("mit-license.org", "MIT"),
            // BSD
            Map.entry("opensource.org/licenses/bsd-2-clause", "BSD-2-Clause"),
            Map.entry("opensource.org/licenses/bsd-3-clause", "BSD-3-Clause"),
            // EPL
            Map.entry("eclipse.org/legal/epl-2.0", "EPL-2.0"),
            Map.entry("eclipse.org/legal/epl-v20", "EPL-2.0"),
            Map.entry("eclipse.org/legal/epl-v10", "EPL-1.0"),
            Map.entry("eclipse.org/org/documents/epl-2.0", "EPL-2.0"),
            // EDL
            Map.entry("eclipse.org/org/documents/edl-v10", "EDL-1.0"),
            Map.entry("eclipse.org/legal/edl-v10", "EDL-1.0"),
            // LGPL
            Map.entry("gnu.org/licenses/lgpl-2.1", "LGPL-2.1"),
            Map.entry("gnu.org/licenses/lgpl-3.0", "LGPL-3.0"),
            // GPL
            Map.entry("gnu.org/licenses/gpl-2.0", "GPL-2.0"),
            Map.entry("gnu.org/licenses/gpl-3.0", "GPL-3.0"),
            // AGPL
            Map.entry("gnu.org/licenses/agpl-3.0", "AGPL-3.0"),
            // MPL
            Map.entry("mozilla.org/mpl/2.0", "MPL-2.0"),
            // CDDL
            Map.entry("opensource.org/licenses/cddl-1.0", "CDDL-1.0"),
            Map.entry("glassfish.dev.java.net/public/cddl", "CDDL-1.0"),
            // CC0
            Map.entry("creativecommons.org/publicdomain/zero/1.0", "CC0-1.0"),
            // Unlicense
            Map.entry("unlicense.org", "Unlicense"),
            // ISC
            Map.entry("opensource.org/licenses/isc", "ISC"),
            // WTFPL
            Map.entry("sam.zoy.org/wtfpl", "WTFPL"));

    /**
     * Normalize a license name (and optional URL) to a canonical SPDX-like identifier
     * for grouping purposes. Uses URL matching first, then name pattern matching.
     */
    static String normalizeLicense(String name, String url) {
        // Try URL match first (most reliable)
        if (url != null) {
            String normalizedUrl = url.toLowerCase()
                    .replaceAll("^https?://", "")
                    .replaceAll("[/.]$", "")
                    .replace(".html", "")
                    .replace(".txt", "")
                    .replace(".php", "");
            for (var entry : LICENSE_URL_MAP.entrySet()) {
                if (normalizedUrl.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }

        if (name == null) return null;
        String lower = name.toLowerCase().trim();

        // Dual-license: pick the most permissive option
        // CDDL + GPL with classpath exception (common in Java EE / Jakarta EE)
        if ((lower.contains("cddl") && lower.contains("gpl"))
                || lower.contains("cddl+gpl")
                || lower.contains("cddl/gpl")) {
            return "CDDL-1.0";
        }
        // Apache OR MIT / BSD — pick Apache
        if ((lower.contains("apache") || lower.contains("asl"))
                && (lower.contains(" or ") || lower.contains("/"))
                && (lower.contains("mit") || lower.contains("bsd"))) {
            return "Apache-2.0";
        }
        // "GPL with classpath exception" alone is weak copyleft (like LGPL)
        if (lower.contains("classpath exception") || lower.contains("class path exception")) {
            return "GPL-2.0-CE";
        }

        // SPDX short identifiers (already normalized)
        if (lower.equals("apache-2.0") || lower.equals("asl 2.0")) return "Apache-2.0";
        if (lower.equals("mit")) return "MIT";
        if (lower.equals("bsd-2-clause")) return "BSD-2-Clause";
        if (lower.equals("bsd-3-clause")) return "BSD-3-Clause";
        if (lower.equals("epl-1.0")) return "EPL-1.0";
        if (lower.equals("epl-2.0")) return "EPL-2.0";
        if (lower.equals("edl 1.0")) return "EDL-1.0";
        if (lower.equals("lgpl-2.1") || lower.equals("lgpl-2.1-only")) return "LGPL-2.1";
        if (lower.equals("lgpl-3.0") || lower.equals("lgpl-3.0-only")) return "LGPL-3.0";
        if (lower.equals("gpl-2.0") || lower.equals("gpl-2.0-only")) return "GPL-2.0";
        if (lower.equals("gpl-3.0") || lower.equals("gpl-3.0-only")) return "GPL-3.0";
        if (lower.equals("agpl-3.0") || lower.equals("agpl-3.0-only")) return "AGPL-3.0";
        if (lower.equals("mpl-2.0")) return "MPL-2.0";
        if (lower.equals("cddl-1.0")) return "CDDL-1.0";
        if (lower.equals("cc0-1.0")) return "CC0-1.0";
        if (lower.equals("isc")) return "ISC";

        // Pattern matching on common name variations
        if (lower.contains("apache") && lower.contains("2")) return "Apache-2.0";
        if (lower.equals("the mit license") || lower.equals("mit license")) return "MIT";
        if (lower.contains("bsd") && lower.contains("2")) return "BSD-2-Clause";
        if (lower.contains("bsd") && lower.contains("3")) return "BSD-3-Clause";
        if (lower.contains("bsd") && !lower.contains("2") && !lower.contains("3")) return "BSD-3-Clause";
        if (lower.contains("eclipse public") && lower.contains("2")) return "EPL-2.0";
        if (lower.contains("eclipse public") && lower.contains("1")) return "EPL-1.0";
        if (lower.contains("eclipse distribution") || lower.equals("edl 1.0")) return "EDL-1.0";
        if (lower.contains("lesser general public") && lower.contains("2")) return "LGPL-2.1";
        if (lower.contains("lesser general public") && lower.contains("3")) return "LGPL-3.0";
        if (lower.contains("lgpl") && lower.contains("2")) return "LGPL-2.1";
        if (lower.contains("lgpl") && lower.contains("3")) return "LGPL-3.0";
        if (lower.contains("agpl") || lower.contains("affero")) return "AGPL-3.0";
        if (lower.contains("general public") && lower.contains("2")) return "GPL-2.0";
        if (lower.contains("general public") && lower.contains("3")) return "GPL-3.0";
        if (lower.contains("gpl") && lower.contains("2")) return "GPL-2.0";
        if (lower.contains("gpl") && lower.contains("3")) return "GPL-3.0";
        if (lower.contains("mozilla") && lower.contains("2")) return "MPL-2.0";
        if (lower.contains("common development") || lower.contains("cddl")) return "CDDL-1.0";
        if (lower.contains("creative commons") && lower.contains("zero")) return "CC0-1.0";
        if (lower.contains("unlicense") || lower.equals("the unlicense")) return "Unlicense";
        if (lower.contains("public domain")) return "Public Domain";

        // No match — return original name
        return name;
    }

    @Override
    public List<HelpOverlay.Section> helpSections() {
        return HelpOverlay.parse("""
                ## License & Security Audit
                Audits all resolved dependencies for license
                compliance and known security vulnerabilities.
                Licenses view: shows each dependency's license
                (from POM metadata). Review for compatibility
                with your project's licensing requirements.
                Vulnerabilities view: queries for known CVEs
                affecting your dependencies. Shows severity,
                CVE identifier, and affected version range.

                ## License Colors
                default         Permissive license (Apache, MIT, BSD)
                yellow          Weak copyleft (LGPL, MPL, EPL, CDDL)
                red             Strong copyleft (GPL, AGPL)
                dim             Unknown or not yet loaded

                ## Vulnerability Colors
                red bold        Critical severity
                yellow          High severity
                white           Medium severity
                dim             Low severity
                dim             Unknown severity

                ## Audit Actions
                """ + NAV_KEYS + """
                ← / →           Collapse / expand (Vulns / grouped Licenses)
                Enter / Space   Toggle expand/collapse on group header
                Tab             Switch between Licenses / Vulns
                g               Toggle flat / grouped license display
                s               Cycle scope filter: all → compile → runtime → test → provided
                m               Add selected dep (or all in group) to dependencyManagement
                d               Preview POM changes as unified diff
                """);
    }

    private List<HelpOverlay.Section> buildHelpStandalone() {
        List<HelpOverlay.Section> sections = new ArrayList<>(helpSections());
        sections.addAll(HelpOverlay.parse("""
                ## Keys
                ↑ / ↓           Move selection up / down
                ← / →           Collapse / expand (Vulns / grouped Licenses)
                Enter / Space   Toggle expand/collapse on group header
                Tab             Switch between Licenses / Vulns
                g               Toggle flat / grouped license display
                s               Cycle scope filter: all → compile → runtime → test → provided
                m               Add selected dep (or all in group) to dependencyManagement
                d               Preview POM changes as a unified diff
                h               Toggle this help screen
                q / Esc         Quit (prompts to save if modified)
                """));
        return sections;
    }

    private void renderInfoBar(Frame frame, Rect area) {
        var rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.length(1))
                .split(area);

        List<Span> statusSpans = new ArrayList<>();
        statusSpans.add(Span.raw(" " + status).fg(theme.standaloneStatusColor()));
        frame.renderWidget(Paragraph.from(Line.from(statusSpans)), rows.get(1));

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
            spans.add(Span.raw("↑↓").bold());
            spans.add(Span.raw(":Scroll  "));
            spans.add(Span.raw("Esc").bold());
            spans.add(Span.raw(":Close  "));
            spans.add(Span.raw("q").bold());
            spans.add(Span.raw(":Quit"));
        } else {
            spans.add(Span.raw("↑↓").bold());
            spans.add(Span.raw(":Navigate  "));
            spans.add(Span.raw("Tab").bold());
            spans.add(Span.raw(":Licenses/Vulns  "));
            spans.add(Span.raw("s").bold());
            spans.add(Span.raw(":Scope" + (scopeFilter != null ? "=" + scopeFilter : "") + "  "));
            if (view == View.LICENSES) {
                spans.add(Span.raw("g").bold());
                spans.add(Span.raw(":Group  "));
            }
            spans.add(Span.raw("m").bold());
            spans.add(Span.raw(":Manage dep  "));
            spans.add(Span.raw("d").bold());
            spans.add(Span.raw(":Diff  "));
            spans.add(Span.raw("h").bold());
            spans.add(Span.raw(":Help  "));
            spans.add(Span.raw("q").bold());
            spans.add(Span.raw(":Quit"));
        }

        frame.renderWidget(Paragraph.from(Line.from(spans)), rows.get(2));
    }

    // ── ToolPanel interface ─────────────────────────────────────────────────

    @Override
    public String toolName() {
        return "Audit";
    }

    @Override
    public boolean handleMouseEvent(MouseEvent mouse, Rect area) {
        if (diffOverlay.isActive()) {
            return diffOverlay.handleMouseScroll(mouse, lastContentHeight);
        }
        if (handleMouseTabBar(mouse)) return true;
        if (handleMouseSortHeader(mouse, currentTableWidths())) return true;
        return handleMouseTableInteraction(mouse, activeRowCount(), activeTableState());
    }

    private List<Constraint> currentTableWidths() {
        return switch (view) {
            case LICENSES ->
                licensesGrouped
                        ? List.of(
                                Constraint.percentage(55), Constraint.percentage(20),
                                Constraint.percentage(15), Constraint.percentage(10))
                        : List.of(
                                Constraint.percentage(40), Constraint.percentage(15),
                                Constraint.percentage(30), Constraint.percentage(15));
            case VULNERABILITIES ->
                List.of(
                        Constraint.percentage(35),
                        Constraint.percentage(10),
                        Constraint.percentage(9),
                        Constraint.percentage(11),
                        Constraint.percentage(35));
        };
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
            spans.add(Span.raw(":Navigate  "));
            spans.add(Span.raw("m").bold());
            spans.add(Span.raw(":Manage dep  "));
            if (view == View.LICENSES) {
                spans.add(Span.raw("g").bold());
                spans.add(Span.raw(":Group  "));
            }
            spans.addAll(sortKeyHints());
            spans.add(Span.raw("/").bold());
            spans.add(Span.raw(":Search  "));
            spans.add(Span.raw("d").bold());
            spans.add(Span.raw(":Diff"));
        }
        return spans;
    }

    @Override
    public void setRunner(TuiRunner runner) {
        super.setRunner(runner);
        fetchAllData();
    }

    @Override
    void close() {
        httpPool.shutdownNow();
    }
}
