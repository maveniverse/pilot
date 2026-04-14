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

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Span;

/**
 * Centralizes all color and style decisions for the Pilot TUI.
 *
 * <p>Every hardcoded color, style, or symbol used in the UI is defined here,
 * making it straightforward to customize or swap the visual theme.</p>
 */
class Theme {

    static final Theme DEFAULT = new Theme();

    // ── Focus & borders ────────────────────────────────────────────────────

    /** Border style when a panel has focus. */
    Style focusedBorder() {
        return Style.create().cyan();
    }

    /** Border style when a panel does not have focus. */
    Style unfocusedBorder() {
        return Style.create().fg(Color.DARK_GRAY);
    }

    // ── Sub-view tabs (ToolPanel tab line) ──────────────────────────────────

    /** Active sub-view tab label with number prefix. */
    Span activeTab(String label, boolean focused, int number) {
        return Span.raw(" [▸" + number + ":" + label + "]").bold().fg(focused ? Color.CYAN : Color.WHITE);
    }

    /** Inactive sub-view tab label with number prefix. */
    Span inactiveTab(String label, boolean focused, int number) {
        return Span.raw(" [" + number + ":" + label + "]").fg(focused ? Color.WHITE : Color.DARK_GRAY);
    }

    /** Dirty indicator shown next to tabs when there are unsaved changes. */
    Span dirtyIndicator() {
        return Span.raw(" [modified]").fg(Color.YELLOW);
    }

    // ── Tool header tabs (PilotShell header) ───────────────────────────────

    /** Brand label in the header. */
    Span brandLabel() {
        return Span.raw(" Pilot").bold().cyan();
    }

    /** Separator after brand label. */
    Span brandSeparator() {
        return Span.raw(" ── ").fg(Color.DARK_GRAY);
    }

    /** Active tool tab in the header. */
    Span activeToolTab(String label) {
        return Span.raw("[" + "▸" + label + "]").bold().cyan();
    }

    /** Inactive but available tool tab in the header. */
    Span inactiveToolTab(String label) {
        return Span.raw(label);
    }

    /** Unavailable tool tab in the header. */
    Span unavailableToolTab(String label) {
        return Span.raw(label).fg(Color.DARK_GRAY);
    }

    // ── Dividers & separators ──────────────────────────────────────────────

    /** Style for horizontal divider lines. */
    Color dividerColor() {
        return Color.DARK_GRAY;
    }

    /** Style for vertical separator characters. */
    Color separatorColor() {
        return Color.DARK_GRAY;
    }

    // ── Table headers ──────────────────────────────────────────────────────

    /** Style for table column headers. */
    Style tableHeader() {
        return Style.create().bold().yellow();
    }

    // ── Selection highlight ────────────────────────────────────────────────

    /** Style for the currently selected/highlighted row. */
    Style highlightStyle() {
        return Style.create().reversed().bold();
    }

    /** Symbol shown next to the selected row. */
    String highlightSymbol() {
        return "▸ ";
    }

    // ── Search ─────────────────────────────────────────────────────────────

    /** Background style for rows that match the search query. */
    Style searchHighlight() {
        return Style.create().bg(Color.DARK_GRAY);
    }

    /** Background color for search-matched rows (for composing with other styles). */
    Color searchHighlightBg() {
        return Color.DARK_GRAY;
    }

    /** Color used for search bar label text. */
    Color searchBarLabelColor() {
        return Color.YELLOW;
    }

    /** Color used for "no matches" indicator. */
    Color searchNoMatchColor() {
        return Color.RED;
    }

    /** Color used for match count indicator. */
    Color searchMatchCountColor() {
        return Color.GREEN;
    }

    // ── Severity levels (AuditTui) ─────────────────────────────────────────

    /** Style for critical severity. */
    Style severityCritical() {
        return Style.create().fg(Color.RED).bold();
    }

    /** Style for high severity. */
    Style severityHigh() {
        return Style.create().fg(Color.RED);
    }

    /** Style for medium severity. */
    Style severityMedium() {
        return Style.create().fg(Color.YELLOW);
    }

    /** Style for low severity. */
    Style severityLow() {
        return Style.create();
    }

    /** Style for unknown severity. */
    Style severityUnknown() {
        return Style.create().fg(Color.DARK_GRAY);
    }

    // ── Update types (ReactorUpdatesTui) ───────────────────────────────────

    /** Style for patch updates. */
    Style updatePatch() {
        return Style.create().fg(Color.GREEN);
    }

    /** Style for minor updates. */
    Style updateMinor() {
        return Style.create().fg(Color.YELLOW);
    }

    /** Style for major updates. */
    Style updateMajor() {
        return Style.create().fg(Color.RED);
    }

    /** Style for property group header rows in the updates table. */
    Style propertyGroupHeader() {
        return Style.create().fg(Color.CYAN).bold();
    }

    /** Style for rows with update counts (modules with updates). */
    Style moduleWithUpdates() {
        return Style.create().fg(Color.YELLOW);
    }

    // ── Dependency usage (DependenciesTui) ──────────────────────────────────

    /** Style for dependencies that have an issue (unused declared or used transitive). */
    Style usageIssue() {
        return Style.create().fg(Color.YELLOW);
    }

    /** Style for undetermined usage. */
    Style usageUndetermined() {
        return Style.create().fg(Color.DARK_GRAY);
    }

    /** Color for usage "used" status in the detail pane. */
    Color usageUsedColor() {
        return Color.GREEN;
    }

    /** Color for usage "issue" status in the detail pane. */
    Color usageIssueColor() {
        return Color.YELLOW;
    }

    /** Color for usage "undetermined" status in the detail pane. */
    Color usageUndeterminedColor() {
        return Color.DARK_GRAY;
    }

    // ── License styles (AuditTui) ──────────────────────────────────────────

    /** Style for permissive licenses (Apache, MIT, BSD, etc.). */
    Style licensePermissive() {
        return Style.create();
    }

    /** Style for weak copyleft licenses (LGPL, MPL, EPL, CDDL). */
    Style licenseWeakCopyleft() {
        return Style.create().fg(Color.YELLOW);
    }

    /** Style for strong copyleft licenses (GPL, AGPL). */
    Style licenseStrongCopyleft() {
        return Style.create().fg(Color.RED);
    }

    /** Style for unknown or unloaded licenses. */
    Style licenseUnknown() {
        return Style.create().fg(Color.DARK_GRAY);
    }

    // ── Info bar / status ──────────────────────────────────────────────────

    /** Color for status bar text in the shell. */
    Color statusColor() {
        return Color.CYAN;
    }

    /** Color for status bar text in standalone modes. */
    Color standaloneStatusColor() {
        return Color.GREEN;
    }

    /** Color for warning/prompt status text. */
    Color statusWarningColor() {
        return Color.YELLOW;
    }

    /** Color for secondary/metadata text (lighter shade). */
    Color secondaryTextColor() {
        return Color.GRAY;
    }

    /** Color for detail pane separator characters. */
    Color detailSeparatorColor() {
        return Color.DARK_GRAY;
    }

    // ── Placeholder (loading/empty states) ─────────────────────────────────

    /** Border style for placeholder panels. */
    Style placeholderBorder() {
        return Style.create().fg(Color.DARK_GRAY);
    }

    // ── POM viewer ─────────────────────────────────────────────────────────

    /** Border style for PomTui origin detail pane. */
    Style originDetailBorder() {
        return Style.create().yellow();
    }

    /** Color for the target line in origin detail. */
    Color originTargetColor() {
        return Color.YELLOW;
    }

    /** Color for context lines in origin detail. */
    Color originContextColor() {
        return Color.DARK_GRAY;
    }

    /** Color for active view tab indicators (PomTui/AuditTui standalone headers). */
    Color activeViewTabColor() {
        return Color.YELLOW;
    }

    /** Color for inactive view tab indicators. */
    Color inactiveViewTabColor() {
        return Color.DARK_GRAY;
    }

    // ── Link / URL colors ──────────────────────────────────────────────────

    /** Color for hyperlink text. */
    Color linkColor() {
        return Color.BLUE;
    }

    // ── SPI / special labels ───────────────────────────────────────────────

    /** Color for SPI service labels. */
    Color spiColor() {
        return Color.MAGENTA;
    }

    /** Color for module labels in audit detail. */
    Color moduleColor() {
        return Color.MAGENTA;
    }

    // ── Selected count indicator ───────────────────────────────────────────

    /** Color for selected item count text. */
    Color selectedCountColor() {
        return Color.CYAN;
    }

    // ── Vuln tab colors (AuditTui standalone header) ───────────────────────

    /** Color for vulnerability tab when vulnerabilities are present. */
    Color vulnTabActiveColor() {
        return Color.RED;
    }

    // ── Dependency scope styles (TreeTui) ──────────────────────────────────

    /** Style for compile-scope dependencies (default style). */
    Style scopeCompile() {
        return Style.create();
    }

    /** Style for runtime-scope dependencies. */
    Style scopeRuntime() {
        return Style.create().fg(Color.BLUE);
    }

    /** Style for test-scope dependencies. */
    Style scopeTest() {
        return Style.create().fg(Color.DARK_GRAY);
    }

    /** Style for provided-scope dependencies. */
    Style scopeProvided() {
        return Style.create().fg(Color.MAGENTA);
    }

    /** Style for system-scope dependencies. */
    Style scopeSystem() {
        return Style.create().fg(Color.RED);
    }

    // ── Diff styles (UnifiedDiff) ──────────────────────────────────────────

    /** Style for added lines in a unified diff. */
    Style diffAdded() {
        return Style.create().fg(Color.GREEN);
    }

    /** Style for removed lines in a unified diff. */
    Style diffRemoved() {
        return Style.create().fg(Color.RED);
    }

    // ── Conflict / warning indicators ──────────────────────────────────────

    /** Style for conflict warning indicators (e.g. version conflicts). */
    Style conflictWarning() {
        return Style.create().fg(Color.YELLOW);
    }

    /** Style for rows with changed values (e.g. convention alignment). */
    Style changedValue() {
        return Style.create().fg(Color.YELLOW);
    }

    // ── Search input ───────────────────────────────────────────────────────

    /** Color for search input label text. */
    Color searchInputLabelColor() {
        return Color.CYAN;
    }

    // ── Tree connector characters ──────────────────────────────────────────

    /** Color for tree connector characters (e.g. └─). */
    Color treeConnectorColor() {
        return Color.DARK_GRAY;
    }

    // ── Metadata values ────────────────────────────────────────────────────

    /** Color for metadata value text (e.g. license names). */
    Color metadataValueColor() {
        return Color.GREEN;
    }
}
