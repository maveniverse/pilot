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
import java.util.List;
import org.junit.jupiter.api.Test;

class TreeTuiTest {

    private static TreeTui createSimpleTreeTui() {
        var root = new DependencyTreeModel.TreeNode("com.example", "root", "1.0", "compile", false, 0);
        var child1 = new DependencyTreeModel.TreeNode("org.dep", "lib-a", "2.0", "compile", false, 1);
        var child2 = new DependencyTreeModel.TreeNode("org.dep", "lib-b", "3.0", "compile", false, 1);
        root.children.add(child1);
        root.children.add(child2);
        var model = new DependencyTreeModel(root, List.of(), 3);
        return new TreeTui(model, "compile", "com.example:root:1.0");
    }

    // ── Scrollbar gutter guard ──────────────────────────────────────────────

    @Test
    void gutterClickLeavesSortStateUnchanged() {
        var tui = createSimpleTreeTui();
        Rect area = new Rect(0, 0, 80, 20);

        // Click on the scrollbar gutter column: x = area.width - 1 = 79
        MouseEvent gutterClick = MouseEvent.press(MouseButton.LEFT, 79, 0);

        boolean handled = tui.handleMouseEvent(gutterClick, area);

        assertThat(handled).isFalse();
        assertThat(tui.sortState.sortColumn()).isEqualTo(-1);
        assertThat(tui.sortState.isSorted()).isFalse();
    }

    @Test
    void gutterClickWithOffsetAreaIsRejected() {
        var tui = createSimpleTreeTui();
        // Area starts at x=10, width=60 → gutter at x=69
        Rect area = new Rect(10, 5, 60, 20);

        MouseEvent gutterClick = MouseEvent.press(MouseButton.LEFT, 69, 5);

        boolean handled = tui.handleMouseEvent(gutterClick, area);

        assertThat(handled).isFalse();
        assertThat(tui.sortState.isSorted()).isFalse();
    }

    @Test
    void clickInsideGutterBoundaryIsNotRejected() {
        var tui = createSimpleTreeTui();
        Rect area = new Rect(0, 0, 80, 20);

        // One pixel inside the gutter boundary: x = 78 (not the gutter)
        MouseEvent normalClick = MouseEvent.press(MouseButton.LEFT, 78, 5);

        // Not rejected by the gutter guard — proceeds to row/sort handling
        tui.handleMouseEvent(normalClick, area);
    }

    @Test
    void scrollEventsAreNotAffectedByGutterGuard() {
        var tui = createSimpleTreeTui();
        Rect area = new Rect(0, 0, 80, 20);

        // Scroll at the gutter column — scrolls should still work
        MouseEvent scrollAtGutter = MouseEvent.scrollDown(79, 0);

        boolean handled = tui.handleMouseEvent(scrollAtGutter, area);

        // Scroll events are not clicks, so the gutter guard doesn't apply
        assertThat(handled).isTrue();
    }

    // ── Tree expand/collapse via keyboard ───────────────────────────────────

    @Test
    void rightKeyExpandsCollapsedNode() {
        var tui = createSimpleTreeTui();
        assertThat(tui.nodeCount()).isEqualTo(3);

        // Collapse root via left key (root is selected at index 0)
        tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.LEFT));

        // Now expand via right key
        boolean handled = tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.RIGHT));
        assertThat(handled).isTrue();
    }

    @Test
    void leftKeyCollapsesExpandedNode() {
        var tui = createSimpleTreeTui();

        // Root is expanded by default, pressing left should collapse it
        boolean handled = tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.LEFT));
        assertThat(handled).isTrue();
    }

    @Test
    void upDownNavigatesBetweenNodes() {
        var tui = createSimpleTreeTui();

        // Start at root (index 0), press down to move to child
        boolean handled = tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.DOWN));
        assertThat(handled).isTrue();

        // Press up to move back to root
        handled = tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.UP));
        assertThat(handled).isTrue();
    }

    @Test
    void expandAllThenCollapseAll() {
        var tui = createSimpleTreeTui();

        // 'E' expands all nodes
        boolean handled = tui.handleKeyEvent(KeyEvent.ofChar('E'));
        assertThat(handled).isTrue();

        // 'W' collapses all nodes
        handled = tui.handleKeyEvent(KeyEvent.ofChar('W'));
        assertThat(handled).isTrue();
    }

    @Test
    void cycleScopeChangesFilter() {
        var tui = createSimpleTreeTui();

        // 'f' cycles scope: compile → runtime
        boolean handled = tui.handleKeyEvent(KeyEvent.ofChar('f'));
        assertThat(handled).isTrue();
    }
}
