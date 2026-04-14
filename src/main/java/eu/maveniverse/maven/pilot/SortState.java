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

import dev.tamboui.style.Style;
import dev.tamboui.widgets.table.Row;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Tracks column sorting state for tables.
 *
 * <p>Supports cycling through columns with {@code s} (next column ascending)
 * and reversing direction with {@code S}. Column headers are decorated with
 * {@code ▲}/{@code ▼} indicators.</p>
 */
class SortState {

    private int sortColumn = -1;
    private boolean ascending = true;
    private final int columnCount;

    SortState(int columnCount) {
        this.columnCount = columnCount;
    }

    int sortColumn() {
        return sortColumn;
    }

    boolean ascending() {
        return ascending;
    }

    boolean isSorted() {
        return sortColumn >= 0;
    }

    /**
     * Cycle to the next sort column.
     * Sequence: unsorted → col0↑ → col1↑ → … → colN↑ → unsorted
     */
    void cycleNext() {
        sortColumn++;
        if (sortColumn >= columnCount) {
            sortColumn = -1;
        }
        ascending = true;
    }

    /** Reverse direction on the current column. No-op if unsorted. */
    void reverseDirection() {
        if (sortColumn >= 0) {
            ascending = !ascending;
        }
    }

    /**
     * Toggle sort on a specific column (for mouse click on column header).
     * First click: sort ascending. Second click: sort descending.
     * Third click: unsort.
     */
    void toggleColumn(int col) {
        if (col < 0 || col >= columnCount) return;
        if (sortColumn == col) {
            if (ascending) {
                ascending = false;
            } else {
                sortColumn = -1;
                ascending = true;
            }
        } else {
            sortColumn = col;
            ascending = true;
        }
    }

    /** Reset to unsorted state. */
    void reset() {
        sortColumn = -1;
        ascending = true;
    }

    /**
     * Decorate a header row by appending ▲/▼ to the sorted column.
     *
     * @param headerLabels the original column header labels
     * @param headerStyle the style for the header row
     * @return a new Row with the sort indicator on the active column
     */
    Row decorateHeader(List<String> headerLabels, Style headerStyle) {
        List<String> decorated = new ArrayList<>(headerLabels);
        if (sortColumn >= 0 && sortColumn < decorated.size()) {
            String indicator = ascending ? " ▲" : " ▼";
            decorated.set(sortColumn, decorated.get(sortColumn) + indicator);
        }
        return Row.from(decorated.toArray(new String[0])).style(headerStyle);
    }

    /**
     * Sort a list in place according to the current sort state.
     *
     * @param items the list to sort
     * @param extractors one extractor per column, returning the comparable string value
     */
    <T> void sort(List<T> items, List<Function<T, String>> extractors) {
        if (sortColumn < 0 || sortColumn >= extractors.size()) return;
        Function<T, String> extractor = extractors.get(sortColumn);
        Comparator<T> cmp = Comparator.comparing(extractor, String.CASE_INSENSITIVE_ORDER);
        if (!ascending) {
            cmp = cmp.reversed();
        }
        items.sort(cmp);
    }
}
