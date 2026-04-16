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
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.widgets.paragraph.Paragraph;
import java.util.ArrayList;
import java.util.List;

/**
 * Reusable help component for TUI screens.
 *
 * <p>Displays a scrollable help panel that slides up from the bottom of
 * the screen, similar to the module tree resize animation. A separator
 * line rises from the info bar divider; when fully open the help content
 * fills the entire body area.</p>
 */
class HelpOverlay {

    record Entry(String key, String description) {}

    record Section(String title, List<Entry> entries) {}

    static List<Section> parse(String text) {
        List<Section> sections = new ArrayList<>();
        String currentTitle = null;
        List<Entry> currentEntries = null;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("## ")) {
                if (currentTitle != null) {
                    sections.add(new Section(currentTitle, currentEntries));
                }
                currentTitle = trimmed.substring(3).trim();
                currentEntries = new ArrayList<>();
            } else if (currentEntries != null) {
                String[] parts = trimmed.split(" {2,}", 2);
                if (parts.length == 2) {
                    currentEntries.add(new Entry(parts[0], parts[1]));
                } else {
                    currentEntries.add(new Entry("", trimmed));
                }
            }
        }
        if (currentTitle != null) {
            sections.add(new Section(currentTitle, currentEntries));
        }
        return sections;
    }

    private List<Line> lines;
    private int scroll;
    private int currentHeight;
    private boolean opening;

    boolean isActive() {
        return lines != null;
    }

    boolean isAnimating() {
        return lines != null && ((opening && currentHeight < targetHeight) || (!opening && currentHeight > 0));
    }

    private int targetHeight;

    void open(List<Section> sections) {
        lines = new ArrayList<>();
        int maxKeyLen = 0;
        for (Section section : sections) {
            for (Entry entry : section.entries) {
                maxKeyLen = Math.max(maxKeyLen, entry.key.length());
            }
        }
        int pad = maxKeyLen + 2;

        for (Section section : sections) {
            if (!lines.isEmpty()) {
                lines.add(Line.from(Span.raw("")));
            }
            lines.add(Line.from(Span.raw("  " + section.title).bold().cyan()));
            lines.add(Line.from(Span.raw("")));
            for (Entry entry : section.entries) {
                String paddedKey = String.format("  %-" + pad + "s", entry.key);
                lines.add(Line.from(Span.raw(paddedKey).bold().yellow(), Span.raw(entry.description)));
            }
        }

        // Footer
        lines.add(Line.from(Span.raw("")));
        lines.add(Line.from(
                Span.raw("  Press ").dim(),
                Span.raw("Esc").bold().yellow(),
                Span.raw(" or ").dim(),
                Span.raw("h").bold().yellow(),
                Span.raw(" to close this help screen").dim()));

        scroll = 0;
        opening = true;
        // currentHeight stays at 0 if freshly opening; animate() will grow it
    }

    void close() {
        opening = false;
        // animate() will shrink currentHeight to 0, then clear lines
    }

    /**
     * Handle key events: close on Esc/h, scroll on up/down/PgUp/PgDn.
     *
     * @return {@code true} if the key was handled
     */
    boolean handleKey(KeyEvent key) {
        if (!isActive()) {
            return false;
        }
        // During open animation, any key skips to fully open
        if (opening && currentHeight < targetHeight) {
            currentHeight = targetHeight;
            return true;
        }
        // During close animation, consume keys but don't act
        if (!opening) {
            return true;
        }
        if (key.isKey(KeyCode.ESCAPE) || key.isCharIgnoreCase('h')) {
            close();
            return true;
        }
        if (key.isUp()) {
            if (scroll > 0) scroll--;
            return true;
        }
        if (key.isDown()) {
            int contentHeight = Math.max(0, currentHeight - 1); // minus separator
            scroll = Math.min(scroll + 1, maxScroll(contentHeight));
            return true;
        }
        if (key.isKey(KeyCode.PAGE_UP)) {
            scroll = Math.max(0, scroll - 10);
            return true;
        }
        if (key.isKey(KeyCode.PAGE_DOWN)) {
            int contentHeight = Math.max(0, currentHeight - 1);
            scroll = Math.min(scroll + 10, maxScroll(contentHeight));
            return true;
        }
        return false;
    }

    /**
     * Handle mouse scroll events.
     *
     * @return {@code true} if the event was handled
     */
    boolean handleMouse(MouseEvent mouse) {
        if (!isActive() || !opening) return false;
        if (mouse.isScroll()) {
            int contentHeight = Math.max(0, currentHeight - 1);
            if (mouse.kind() == MouseEventKind.SCROLL_UP) {
                if (scroll > 0) scroll--;
            } else {
                scroll = Math.min(scroll + 1, maxScroll(contentHeight));
            }
            return true;
        }
        return false;
    }

    private int maxScroll(int contentHeight) {
        return lines == null ? 0 : Math.max(0, lines.size() - contentHeight);
    }

    /**
     * Animate the help panel height toward its target.
     * Call this each render tick. Returns the current height in terminal rows
     * that the help panel should occupy (0 when fully closed).
     *
     * @param maxHeight the maximum available height (full body area)
     * @return current height for the help panel
     */
    int animate(int maxHeight) {
        targetHeight = opening ? maxHeight : 0;
        if (currentHeight != targetHeight) {
            int diff = targetHeight - currentHeight;
            int step = Math.max(2, Math.abs(diff) / 2);
            if (diff > 0) {
                currentHeight = Math.min(currentHeight + step, targetHeight);
            } else {
                currentHeight = Math.max(currentHeight - step, targetHeight);
            }
        }
        // Cleanup when close animation finishes
        if (!opening && currentHeight == 0) {
            lines = null;
        }
        return currentHeight;
    }

    /**
     * Render the help panel into the given area.
     * Draws a separator line at the top and scrollable help content below.
     */
    void render(Frame frame, Rect area) {
        if (!isActive() || area.height() <= 0) return;

        if (area.height() == 1) {
            // Only room for separator
            renderSeparator(frame, area, area.width());
            return;
        }

        var zones = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.fill())
                .split(area);

        renderSeparator(frame, zones.get(0), area.width());

        // Content area
        Rect contentArea = zones.get(1);
        int contentHeight = contentArea.height();
        if (contentHeight <= 0 || lines == null || lines.isEmpty()) return;

        List<Line> visible = new ArrayList<>();
        int end = Math.min(scroll + contentHeight, lines.size());
        for (int idx = scroll; idx < end; idx++) {
            visible.add(lines.get(idx));
        }

        frame.renderWidget(Paragraph.builder().text(Text.from(visible)).build(), contentArea);
    }

    private void renderSeparator(Frame frame, Rect area, int width) {
        String title = " Help ";
        int sideLen = Math.max(0, (width - title.length()) / 2);
        String left = "─".repeat(sideLen);
        String right = "─".repeat(Math.max(0, width - sideLen - title.length()));
        Line sep = Line.from(
                Span.raw(left).fg(Color.DARK_GRAY),
                Span.raw(title).bold().cyan(),
                Span.raw(right).fg(Color.DARK_GRAY));
        frame.renderWidget(Paragraph.from(sep), area);
    }
}
