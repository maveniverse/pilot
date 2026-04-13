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

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.table.TableState;

/**
 * Shared page-navigation key handler for table views.
 *
 * <p>Handles Page Up, Page Down, Home, and End keys by computing the page size
 * from the visible content height and updating the table selection accordingly.</p>
 */
class TableNavigation {

    private TableNavigation() {}

    /**
     * Handle page-navigation keys (Page Up/Down, Home/End) for a table.
     *
     * @param key           the key event
     * @param tableState    the table state to update
     * @param listSize      total number of rows in the table
     * @param contentHeight the visible content area height (from the layout zone)
     * @return {@code true} if the key was handled, {@code false} otherwise
     */
    static boolean handlePageKeys(KeyEvent key, TableState tableState, int listSize, int contentHeight) {
        if (key.isKey(KeyCode.PAGE_UP)) {
            int pageSize = Math.max(1, contentHeight - 3);
            Integer sel = tableState.selected();
            tableState.select(Math.max(0, (sel != null ? sel : 0) - pageSize));
            return true;
        }
        if (key.isKey(KeyCode.PAGE_DOWN)) {
            int pageSize = Math.max(1, contentHeight - 3);
            Integer sel = tableState.selected();
            tableState.select(Math.min(Math.max(0, listSize - 1), (sel != null ? sel : 0) + pageSize));
            return true;
        }
        if (key.isHome()) {
            tableState.select(0);
            return true;
        }
        if (key.isEnd()) {
            if (listSize > 0) {
                tableState.select(listSize - 1);
            }
            return true;
        }
        return false;
    }
}
