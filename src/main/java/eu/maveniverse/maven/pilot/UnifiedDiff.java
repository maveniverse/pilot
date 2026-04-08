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

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.paragraph.Paragraph;
import java.util.ArrayList;
import java.util.List;

/**
 * Line-based unified diff computation and TUI rendering.
 */
class UnifiedDiff {

    enum Type {
        CONTEXT,
        ADDED,
        REMOVED
    }

    record DiffLine(Type type, String text) {}

    /**
     * Compute a line-based diff between two strings.
     *
     * @param original the original text
     * @param modified the modified text
     * @return list of diff lines with type annotations
     */
    static List<DiffLine> compute(String original, String modified) {
        String[] oldLines = original.isEmpty() ? new String[0] : original.split("\n", -1);
        String[] newLines = modified.isEmpty() ? new String[0] : modified.split("\n", -1);

        // LCS table
        int m = oldLines.length;
        int n = newLines.length;
        int[][] lcs = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                if (oldLines[i].equals(newLines[j])) {
                    lcs[i][j] = lcs[i + 1][j + 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }

        // Backtrack to produce diff
        List<DiffLine> result = new ArrayList<>();
        int i = 0, j = 0;
        while (i < m || j < n) {
            if (i < m && j < n && oldLines[i].equals(newLines[j])) {
                result.add(new DiffLine(Type.CONTEXT, oldLines[i]));
                i++;
                j++;
            } else if (i < m && (j >= n || lcs[i + 1][j] >= lcs[i][j + 1])) {
                result.add(new DiffLine(Type.REMOVED, oldLines[i]));
                i++;
            } else if (j < n) {
                result.add(new DiffLine(Type.ADDED, newLines[j]));
                j++;
            }
        }
        return result;
    }

    /**
     * Render a diff into a bordered area with scrolling support.
     *
     * @param frame the TUI frame to render into
     * @param area the rectangular area to use
     * @param lines the diff lines to display
     * @param scroll the scroll offset (number of lines to skip)
     * @param title the title for the diff block
     */
    static void render(Frame frame, Rect area, List<DiffLine> lines, int scroll, String title) {
        Block block = Block.builder()
                .title(title)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().yellow())
                .build();

        if (lines.isEmpty()) {
            Paragraph empty = Paragraph.builder()
                    .text("No changes")
                    .block(block)
                    .centered()
                    .build();
            frame.renderWidget(empty, area);
            return;
        }

        // Build visible lines within the bordered area
        int innerHeight = area.height() - 2; // borders top/bottom
        if (innerHeight <= 0) {
            frame.renderWidget(Paragraph.builder().text("").block(block).build(), area);
            return;
        }

        List<Line> visibleLines = new ArrayList<>();
        int end = Math.min(scroll + innerHeight, lines.size());
        for (int idx = scroll; idx < end; idx++) {
            DiffLine dl = lines.get(idx);
            String prefix;
            Style style;
            switch (dl.type()) {
                case ADDED -> {
                    prefix = "+ ";
                    style = Style.create().fg(Color.GREEN);
                }
                case REMOVED -> {
                    prefix = "- ";
                    style = Style.create().fg(Color.RED);
                }
                default -> {
                    prefix = "  ";
                    style = Style.create().dim();
                }
            }
            visibleLines.add(Line.from(Span.raw(prefix + dl.text()).style(style)));
        }

        // Build a single Text block from all visible lines
        dev.tamboui.text.Text text = dev.tamboui.text.Text.from(visibleLines);
        Paragraph paragraph = Paragraph.builder().text(text).block(block).build();
        frame.renderWidget(paragraph, area);
    }

    /**
     * Compute the maximum scroll offset for the given diff lines and visible area height.
     */
    static int maxScroll(List<DiffLine> lines, int areaHeight) {
        int innerHeight = Math.max(0, areaHeight - 2);
        return Math.max(0, lines.size() - innerHeight);
    }

    /**
     * Filter diff lines to only show changes (added/removed) with surrounding context.
     *
     * @param lines the full diff lines
     * @param contextLines number of context lines to show around changes
     * @return filtered list with only relevant lines
     */
    static List<DiffLine> filterContext(List<DiffLine> lines, int contextLines) {
        if (lines.isEmpty()) return lines;

        boolean[] show = new boolean[lines.size()];
        // Mark all non-context lines and their surrounding context
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).type() != Type.CONTEXT) {
                for (int j = Math.max(0, i - contextLines); j <= Math.min(lines.size() - 1, i + contextLines); j++) {
                    show[j] = true;
                }
            }
        }

        List<DiffLine> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (show[i]) {
                result.add(lines.get(i));
            }
        }
        return result;
    }

    /**
     * Count the number of changes (added + removed lines) in the diff.
     */
    static long changeCount(List<DiffLine> lines) {
        return lines.stream().filter(l -> l.type() != Type.CONTEXT).count();
    }
}
