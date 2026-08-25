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

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.terminal.Frame;
import dev.tamboui.terminal.Terminal;
import dev.tamboui.terminal.TestBackend;
import java.util.function.Consumer;

/**
 * Shared utilities for TUI rendering tests.
 * Provides helpers to render a TUI panel to a virtual terminal and
 * extract plain text for assertion-based testing.
 */
final class TuiTestHelper {

    static final int WIDTH = 120;
    static final int HEIGHT = 30;

    private TuiTestHelper() {}

    /**
     * Render a TUI component at default size (120x30) and return the buffer content as plain text.
     */
    static String render(Consumer<Frame> renderer) {
        return render(WIDTH, HEIGHT, renderer);
    }

    /**
     * Render a TUI component at the given size and return the buffer content as plain text.
     */
    static String render(int width, int height, Consumer<Frame> renderer) {
        var terminal = new Terminal<>(new TestBackend(width, height));
        var frame = terminal.draw(renderer);
        return bufferText(frame.buffer());
    }

    /**
     * Extract plain text from a terminal buffer.
     * Each row becomes a line terminated by '\n'.
     */
    static String bufferText(Buffer buffer) {
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < buffer.height(); y++) {
            for (int x = 0; x < buffer.width(); x++) {
                Cell cell = buffer.get(x, y);
                if (!cell.isContinuation()) {
                    String sym = cell.symbol();
                    sb.append(sym.isEmpty() ? " " : sym);
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Find the (x, y) buffer coordinate of the first occurrence of a target string.
     * Searches the buffer cell-by-cell so the returned coordinates are valid for MouseEvent.
     */
    static int[] findInBuffer(Buffer buffer, String target) {
        for (int y = 0; y < buffer.height(); y++) {
            for (int x = 0; x <= buffer.width() - target.length(); x++) {
                if (bufferMatchesAt(buffer, x, y, target)) {
                    return new int[] {x, y};
                }
            }
        }
        return null;
    }

    private static boolean bufferMatchesAt(Buffer buffer, int startX, int y, String target) {
        int ti = 0;
        for (int x = startX; x < buffer.width() && ti < target.length(); x++) {
            Cell cell = buffer.get(x, y);
            if (cell.isContinuation()) continue;
            String sym = cell.symbol();
            if (sym.isEmpty()) sym = " ";
            for (int si = 0; si < sym.length() && ti < target.length(); si++, ti++) {
                if (sym.charAt(si) != target.charAt(ti)) return false;
            }
        }
        return ti == target.length();
    }

    /**
     * Render and return the buffer (not text) for direct coordinate lookup.
     */
    static Buffer renderToBuffer(int width, int height, Consumer<Frame> renderer) {
        var terminal = new Terminal<>(new TestBackend(width, height));
        var frame = terminal.draw(renderer);
        return frame.buffer();
    }

    /**
     * Count non-overlapping occurrences of a substring in a string.
     */
    static int countOccurrences(String text, String target) {
        if (target.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(target, idx)) != -1) {
            count++;
            idx += target.length();
        }
        return count;
    }
}
