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
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.paragraph.Paragraph;
import java.util.ArrayList;
import java.util.List;

/**
 * Reusable help overlay component for TUI screens.
 *
 * <p>Displays a scrollable help screen with sections, key bindings,
 * and descriptions. Close with Esc or h.</p>
 */
class HelpOverlay {

    record Entry(String key, String description) {}

    record Section(String title, List<Entry> entries) {}

    private List<Line> lines;
    private int scroll;
    private int lastHeight;

    boolean isActive() {
        return lines != null;
    }

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
    }

    void close() {
        lines = null;
        scroll = 0;
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
        if (key.isKey(KeyCode.ESCAPE) || key.isCharIgnoreCase('h')) {
            close();
            return true;
        }
        if (key.isUp()) {
            if (scroll > 0) scroll--;
            return true;
        }
        if (key.isDown()) {
            scroll = Math.min(scroll + 1, maxScroll(lastHeight));
            return true;
        }
        if (key.isKey(KeyCode.PAGE_UP)) {
            scroll = Math.max(0, scroll - 10);
            return true;
        }
        if (key.isKey(KeyCode.PAGE_DOWN)) {
            scroll = Math.min(scroll + 10, maxScroll(lastHeight));
            return true;
        }
        return false;
    }

    private int maxScroll(int areaHeight) {
        int innerHeight = Math.max(0, areaHeight - 2);
        return lines == null ? 0 : Math.max(0, lines.size() - innerHeight);
    }

    void render(Frame frame, Rect area) {
        lastHeight = area.height();
        Block block = Block.builder()
                .title(" Help ")
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().cyan())
                .build();

        if (lines == null || lines.isEmpty()) {
            frame.renderWidget(Paragraph.builder().text("").block(block).build(), area);
            return;
        }

        int innerHeight = area.height() - 2;
        if (innerHeight <= 0) {
            frame.renderWidget(Paragraph.builder().text("").block(block).build(), area);
            return;
        }

        List<Line> visible = new ArrayList<>();
        int end = Math.min(scroll + innerHeight, lines.size());
        for (int idx = scroll; idx < end; idx++) {
            visible.add(lines.get(idx));
        }

        Paragraph paragraph =
                Paragraph.builder().text(Text.from(visible)).block(block).build();
        frame.renderWidget(paragraph, area);
    }
}
