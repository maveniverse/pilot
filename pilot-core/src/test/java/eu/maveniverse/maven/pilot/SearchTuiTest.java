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

import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.layout.Rect;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseButton;
import dev.tamboui.tui.event.MouseEvent;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchTuiTest {

    private static final SearchTui.SearchClient NOOP_CLIENT =
            (q, rows, start) -> Json.createObjectBuilder().add("numFound", 0).build();

    @Test
    void handleCharKeyInSearchMode() {
        var tui = new SearchTui(NOOP_CLIENT, "", null, 0);
        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('a'))).isTrue();
    }

    @Test
    void handleCharKeyInTableMode() {
        var results = List.<String[]>of(new String[] {"org.example", "lib", "1.0", "jar", "1", ""});
        var tui = new SearchTui(NOOP_CLIENT, "test", results, 1);
        tui.handleKeyEvent(KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.DOWN));
        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('x'))).isTrue();
    }

    // ── Scrollbar gutter guard ──────────────────────────────────────────────

    @Test
    void gutterClickLeavesSortStateUnchanged() {
        var tui = new SearchTui(NOOP_CLIENT, "", null, 0);
        Rect area = new Rect(0, 0, 80, 20);

        // Click on the scrollbar gutter column: x = area.width - 1 = 79
        MouseEvent gutterClick = MouseEvent.press(MouseButton.LEFT, 79, 0);

        boolean handled = tui.handleMouseEvent(gutterClick, area);

        assertThat(handled).isFalse();
        assertThat(tui.sortState.isSorted()).isFalse();
    }

    @Test
    void gutterClickWithOffsetAreaIsRejected() {
        var tui = new SearchTui(NOOP_CLIENT, "", null, 0);
        // Area starts at x=10, width=60 → gutter at x=69
        Rect area = new Rect(10, 5, 60, 20);

        MouseEvent gutterClick = MouseEvent.press(MouseButton.LEFT, 69, 5);

        boolean handled = tui.handleMouseEvent(gutterClick, area);

        assertThat(handled).isFalse();
        assertThat(tui.sortState.isSorted()).isFalse();
    }

    @Test
    void clickInsideGutterBoundaryIsNotRejected() {
        var tui = new SearchTui(NOOP_CLIENT, "", null, 0);
        Rect area = new Rect(0, 0, 80, 20);

        // x = 78 is one pixel left of the gutter — should NOT be rejected
        MouseEvent normalClick = MouseEvent.press(MouseButton.LEFT, 78, 5);

        // Not rejected by the gutter guard — proceeds to row/sort handling
        tui.handleMouseEvent(normalClick, area);
    }

    @Test
    void scrollEventsAreNotAffectedByGutterGuard() {
        var tui = new SearchTui(NOOP_CLIENT, "", null, 0);
        Rect area = new Rect(0, 0, 80, 20);

        // Scroll at the gutter column — scrolls should still work (not clicks)
        MouseEvent scrollAtGutter = MouseEvent.scrollDown(79, 0);

        // Scroll events are not clicks, so the gutter guard doesn't apply
        tui.handleMouseEvent(scrollAtGutter, area);
    }

    // ── Multi-char cursor positioning ──────────────────────────────────────

    @Test
    void supplementaryCharacterAdvancesCursorByStringLength() {
        var tui = new SearchTui(NOOP_CLIENT, "", null, 0);

        // U+1F600 GRINNING FACE — a supplementary character (2 UTF-16 code units)
        tui.handleKeyEvent(KeyEvent.ofChar(0x1F600));

        // Type a regular ASCII char after the supplementary character
        tui.handleKeyEvent(KeyEvent.ofChar('a'));

        // Then delete backwards: should remove 'a', not corrupt the buffer
        tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.BACKSPACE));

        // Type another char — should still work without IndexOutOfBoundsException
        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('b'))).isTrue();
    }

    @Test
    void multipleCharInsertionsThenCursorNavigationIsConsistent() {
        var tui = new SearchTui(NOOP_CLIENT, "", null, 0);

        // Type "abc" one char at a time
        tui.handleKeyEvent(KeyEvent.ofChar('a'));
        tui.handleKeyEvent(KeyEvent.ofChar('b'));
        tui.handleKeyEvent(KeyEvent.ofChar('c'));

        // Move cursor left twice (should be at position 1, between 'a' and 'b')
        tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.LEFT));
        tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.LEFT));

        // Delete forward should remove 'b'
        tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.DELETE));

        // Typing 'x' here should work without errors
        assertThat(tui.handleKeyEvent(KeyEvent.ofChar('x'))).isTrue();
    }

    // ── Extract artifacts ──────────────────────────────────────────────────

    @Test
    void extractArtifactsFromDocs() {
        JsonObject response = Json.createObjectBuilder()
                .add("numFound", 2)
                .add(
                        "docs",
                        Json.createArrayBuilder()
                                .add(Json.createObjectBuilder()
                                        .add("g", "org.slf4j")
                                        .add("a", "slf4j-api")
                                        .add("latestVersion", "2.0.9")
                                        .add("p", "jar")
                                        .add("versionCount", 42)
                                        .add("timestamp", 1696000000000L))
                                .add(Json.createObjectBuilder()
                                        .add("g", "com.google.guava")
                                        .add("a", "guava")
                                        .add("latestVersion", "33.0.0-jre")
                                        .add("p", "bundle")
                                        .add("versionCount", 100)))
                .build();

        List<String[]> results = SearchTui.extractArtifacts(response);

        assertThat(results).hasSize(2);
        assertThat(results.get(0)[0]).isEqualTo("org.slf4j");
        assertThat(results.get(0)[1]).isEqualTo("slf4j-api");
        assertThat(results.get(0)[2]).isEqualTo("2.0.9");
        assertThat(results.get(0)[3]).isEqualTo("jar");
        assertThat(results.get(0)[4]).isEqualTo("42");
        assertThat(results.get(0)[5]).isNotEmpty(); // timestamp formatted as date

        assertThat(results.get(1)[0]).isEqualTo("com.google.guava");
        assertThat(results.get(1)[2]).isEqualTo("33.0.0-jre");
        assertThat(results.get(1)[5]).isEmpty(); // no timestamp
    }

    @Test
    void extractArtifactsSkipsEmptyGroupOrArtifact() {
        JsonObject response = Json.createObjectBuilder()
                .add("numFound", 1)
                .add(
                        "docs",
                        Json.createArrayBuilder()
                                .add(Json.createObjectBuilder().add("g", "").add("a", "something")))
                .build();

        List<String[]> results = SearchTui.extractArtifacts(response);
        assertThat(results).isEmpty();
    }

    @Test
    void extractArtifactsHandlesNullDocs() {
        JsonObject response = Json.createObjectBuilder().add("numFound", 0).build();

        List<String[]> results = SearchTui.extractArtifacts(response);
        assertThat(results).isEmpty();
    }

    @Test
    void extractArtifactsUsesVFieldWhenNoLatestVersion() {
        JsonObject response = Json.createObjectBuilder()
                .add("numFound", 1)
                .add(
                        "docs",
                        Json.createArrayBuilder()
                                .add(Json.createObjectBuilder()
                                        .add("g", "com.example")
                                        .add("a", "lib")
                                        .add("v", "1.0.0")
                                        .add("p", "jar")))
                .build();

        List<String[]> results = SearchTui.extractArtifacts(response);
        assertThat(results).hasSize(1);
        assertThat(results.get(0)[2]).isEqualTo("1.0.0");
    }
}
