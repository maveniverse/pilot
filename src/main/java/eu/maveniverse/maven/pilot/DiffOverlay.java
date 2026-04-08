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
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.util.List;

/**
 * Reusable diff overlay component for TUI screens.
 *
 * <p>Encapsulates the state and behavior for displaying a scrollable
 * unified diff overlay, including key handling for scrolling.</p>
 */
class DiffOverlay {

    private List<UnifiedDiff.DiffLine> lines;
    private int scroll;

    boolean isActive() {
        return lines != null;
    }

    List<UnifiedDiff.DiffLine> lines() {
        return lines;
    }

    int scroll() {
        return scroll;
    }

    /**
     * Open the diff overlay by computing a unified diff between two strings.
     *
     * @return the number of changed lines, or 0 if no changes detected
     */
    long open(String original, String modified) {
        var fullDiff = UnifiedDiff.compute(original, modified);
        long changes = UnifiedDiff.changeCount(fullDiff);
        if (changes == 0) {
            return 0;
        }
        lines = UnifiedDiff.filterContext(fullDiff, 3);
        scroll = 0;
        return changes;
    }

    void close() {
        lines = null;
        scroll = 0;
    }

    /**
     * Handle scroll keys (up, down, page up, page down).
     *
     * @return {@code true} if the key was handled
     */
    boolean handleScrollKey(KeyEvent key, int contentHeight) {
        if (!isActive()) {
            return false;
        }
        if (key.isUp()) {
            if (scroll > 0) scroll--;
            return true;
        }
        if (key.isDown()) {
            scroll = Math.min(scroll + 1, UnifiedDiff.maxScroll(lines, contentHeight));
            return true;
        }
        if (key.isKey(KeyCode.PAGE_UP)) {
            scroll = Math.max(0, scroll - 10);
            return true;
        }
        if (key.isKey(KeyCode.PAGE_DOWN)) {
            scroll = Math.min(scroll + 10, UnifiedDiff.maxScroll(lines, contentHeight));
            return true;
        }
        return false;
    }

    void render(Frame frame, Rect area, String title) {
        UnifiedDiff.render(frame, area, lines, scroll, title);
    }
}
