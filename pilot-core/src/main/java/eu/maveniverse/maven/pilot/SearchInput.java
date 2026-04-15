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
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reusable inline search/filter input component.
 *
 * <p>Activated with {@code /}, displays a text input with cursor.
 * Characters are accumulated into a buffer with cursor position tracking.
 * Enter confirms (keeps the filter active), Esc cancels (clears the filter).
 * A callback is invoked on every change for live filtering.</p>
 */
class SearchInput {

    private final StringBuilder buffer = new StringBuilder();
    private int cursorPos;
    private boolean active;
    private final String label;
    private final Consumer<String> onChange;

    /**
     * @param label the prefix label (e.g. "Filter" or "Search")
     * @param onChange callback invoked with the current query on every keystroke
     */
    SearchInput(String label, Consumer<String> onChange) {
        this.label = label;
        this.onChange = onChange;
    }

    boolean isActive() {
        return active;
    }

    String query() {
        return buffer.toString();
    }

    /** Activate the input, clearing any previous query. */
    void open() {
        buffer.setLength(0);
        cursorPos = 0;
        active = true;
        onChange.accept("");
    }

    /** Confirm the current query and deactivate input mode. */
    void confirm() {
        active = false;
    }

    /** Cancel input, clear query, and deactivate. */
    void cancel() {
        buffer.setLength(0);
        cursorPos = 0;
        active = false;
        onChange.accept("");
    }

    /**
     * Handle a key event while the input is active.
     *
     * @return {@code true} if the key was consumed
     */
    boolean handleKey(KeyEvent key) {
        if (!active) return false;

        if (key.isKey(KeyCode.ESCAPE)) {
            cancel();
            return true;
        }
        if (key.isKey(KeyCode.ENTER)) {
            confirm();
            return true;
        }

        if (key.code() == KeyCode.CHAR) {
            buffer.insert(cursorPos, key.character());
            cursorPos++;
            onChange.accept(buffer.toString());
            return true;
        }
        if (key.isKey(KeyCode.BACKSPACE) && cursorPos > 0) {
            buffer.deleteCharAt(cursorPos - 1);
            cursorPos--;
            onChange.accept(buffer.toString());
            return true;
        }
        if (key.isKey(KeyCode.DELETE) && cursorPos < buffer.length()) {
            buffer.deleteCharAt(cursorPos);
            onChange.accept(buffer.toString());
            return true;
        }
        if (key.isKey(KeyCode.LEFT) && cursorPos > 0) {
            cursorPos--;
            return true;
        }
        if (key.isKey(KeyCode.RIGHT) && cursorPos < buffer.length()) {
            cursorPos++;
            return true;
        }
        if (key.isKey(KeyCode.HOME)) {
            cursorPos = 0;
            return true;
        }
        if (key.isKey(KeyCode.END)) {
            cursorPos = buffer.length();
            return true;
        }

        return false;
    }

    /**
     * Render the search input bar into a single-line area.
     */
    void render(Frame frame, Rect area) {
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(" " + label + ": ").bold().fg(Theme.DEFAULT.searchInputLabelColor()));

        String text = buffer.toString();
        if (cursorPos < text.length()) {
            spans.add(Span.raw(text.substring(0, cursorPos)));
            spans.add(Span.raw(String.valueOf(text.charAt(cursorPos)))
                    .style(Style.create().reversed()));
            spans.add(Span.raw(text.substring(cursorPos + 1)));
        } else {
            spans.add(Span.raw(text));
            spans.add(Span.raw(" ").style(Style.create().reversed()));
        }

        frame.renderWidget(Paragraph.from(Line.from(spans)), area);
    }
}
