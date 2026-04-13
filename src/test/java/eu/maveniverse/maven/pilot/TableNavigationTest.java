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

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.widgets.table.TableState;
import org.junit.jupiter.api.Test;

class TableNavigationTest {

    private static KeyEvent keyEvent(KeyCode code) {
        return new KeyEvent(code, KeyModifiers.NONE, (char) 0);
    }

    private static KeyEvent homeEvent() {
        return new KeyEvent(KeyCode.HOME, KeyModifiers.NONE, (char) 0);
    }

    private static KeyEvent endEvent() {
        return new KeyEvent(KeyCode.END, KeyModifiers.NONE, (char) 0);
    }

    @Test
    void pageDownFromFirstRow() {
        TableState ts = new TableState();
        ts.select(0);
        // contentHeight=24 -> pageSize = max(1, 24-3) = 21
        boolean handled = TableNavigation.handlePageKeys(keyEvent(KeyCode.PAGE_DOWN), ts, 50, 24);
        assertThat(handled).isTrue();
        assertThat(ts.selected()).isEqualTo(21);
    }

    @Test
    void pageDownClampsToLastRow() {
        TableState ts = new TableState();
        ts.select(0);
        boolean handled = TableNavigation.handlePageKeys(keyEvent(KeyCode.PAGE_DOWN), ts, 5, 24);
        assertThat(handled).isTrue();
        assertThat(ts.selected()).isEqualTo(4);
    }

    @Test
    void pageUpFromMiddle() {
        TableState ts = new TableState();
        ts.select(25);
        boolean handled = TableNavigation.handlePageKeys(keyEvent(KeyCode.PAGE_UP), ts, 50, 24);
        assertThat(handled).isTrue();
        assertThat(ts.selected()).isEqualTo(4);
    }

    @Test
    void pageUpClampsToFirstRow() {
        TableState ts = new TableState();
        ts.select(3);
        boolean handled = TableNavigation.handlePageKeys(keyEvent(KeyCode.PAGE_UP), ts, 50, 24);
        assertThat(handled).isTrue();
        assertThat(ts.selected()).isEqualTo(0);
    }

    @Test
    void homeSelectsFirstRow() {
        TableState ts = new TableState();
        ts.select(25);
        boolean handled = TableNavigation.handlePageKeys(homeEvent(), ts, 50, 24);
        assertThat(handled).isTrue();
        assertThat(ts.selected()).isZero();
    }

    @Test
    void endSelectsLastRow() {
        TableState ts = new TableState();
        ts.select(0);
        boolean handled = TableNavigation.handlePageKeys(endEvent(), ts, 50, 24);
        assertThat(handled).isTrue();
        assertThat(ts.selected()).isEqualTo(49);
    }

    @Test
    void endWithEmptyList() {
        TableState ts = new TableState();
        boolean handled = TableNavigation.handlePageKeys(endEvent(), ts, 0, 24);
        assertThat(handled).isTrue();
        assertThat(ts.selected()).isNull();
    }

    @Test
    void unhandledKeyReturnsFalse() {
        TableState ts = new TableState();
        ts.select(0);
        boolean handled = TableNavigation.handlePageKeys(keyEvent(KeyCode.TAB), ts, 50, 24);
        assertThat(handled).isFalse();
        assertThat(ts.selected()).isZero();
    }

    @Test
    void pageDownWithNoSelection() {
        TableState ts = new TableState();
        boolean handled = TableNavigation.handlePageKeys(keyEvent(KeyCode.PAGE_DOWN), ts, 50, 24);
        assertThat(handled).isTrue();
        assertThat(ts.selected()).isEqualTo(21);
    }

    @Test
    void pageUpWithNoSelection() {
        TableState ts = new TableState();
        boolean handled = TableNavigation.handlePageKeys(keyEvent(KeyCode.PAGE_UP), ts, 50, 24);
        assertThat(handled).isTrue();
        assertThat(ts.selected()).isZero();
    }

    @Test
    void smallContentHeight() {
        TableState ts = new TableState();
        ts.select(5);
        // contentHeight=4 -> pageSize = max(1, 4-3) = 1
        boolean handled = TableNavigation.handlePageKeys(keyEvent(KeyCode.PAGE_DOWN), ts, 50, 4);
        assertThat(handled).isTrue();
        assertThat(ts.selected()).isEqualTo(6);
    }

    @Test
    void tinyContentHeight() {
        TableState ts = new TableState();
        ts.select(5);
        // contentHeight=2 -> pageSize = max(1, 2-3) = max(1, -1) = 1
        boolean handled = TableNavigation.handlePageKeys(keyEvent(KeyCode.PAGE_DOWN), ts, 50, 2);
        assertThat(handled).isTrue();
        assertThat(ts.selected()).isEqualTo(6);
    }
}
