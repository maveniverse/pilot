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

import dev.tamboui.style.Style;
import dev.tamboui.widgets.table.Row;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class SortStateTest {

    @Test
    void initialStateUnsorted() {
        SortState state = new SortState(3);
        assertThat(state.isSorted()).isFalse();
        assertThat(state.sortColumn()).isEqualTo(-1);
        assertThat(state.ascending()).isTrue();
    }

    @Test
    void constructorWithDefaults() {
        SortState state = new SortState(3, 1, false);
        assertThat(state.sortColumn()).isEqualTo(1);
        assertThat(state.ascending()).isFalse();
        assertThat(state.isSorted()).isTrue();
    }

    @Test
    void constructorWithInvalidDefaultIgnored() {
        SortState state = new SortState(3, 5, true);
        assertThat(state.isSorted()).isFalse();
        assertThat(state.sortColumn()).isEqualTo(-1);
    }

    @Test
    void cycleNextThroughAllColumns() {
        SortState state = new SortState(3);
        state.cycleNext();
        assertThat(state.sortColumn()).isZero();
        state.cycleNext();
        assertThat(state.sortColumn()).isEqualTo(1);
        state.cycleNext();
        assertThat(state.sortColumn()).isEqualTo(2);
        state.cycleNext();
        assertThat(state.sortColumn()).isEqualTo(-1);
    }

    @Test
    void reverseDirectionFlipsAscending() {
        SortState state = new SortState(3);
        state.cycleNext(); // col 0 asc
        state.reverseDirection();
        assertThat(state.ascending()).isFalse();
    }

    @Test
    void reverseDirectionNoOpWhenUnsorted() {
        SortState state = new SortState(3);
        state.reverseDirection();
        assertThat(state.ascending()).isTrue();
        assertThat(state.isSorted()).isFalse();
    }

    @Test
    void toggleColumnFirstClickAscending() {
        SortState state = new SortState(3);
        state.toggleColumn(1);
        assertThat(state.sortColumn()).isEqualTo(1);
        assertThat(state.ascending()).isTrue();
    }

    @Test
    void toggleColumnSecondClickDescending() {
        SortState state = new SortState(3);
        state.toggleColumn(1);
        state.toggleColumn(1);
        assertThat(state.sortColumn()).isEqualTo(1);
        assertThat(state.ascending()).isFalse();
    }

    @Test
    void toggleColumnThirdClickUnsorts() {
        SortState state = new SortState(3);
        state.toggleColumn(1);
        state.toggleColumn(1);
        state.toggleColumn(1);
        assertThat(state.isSorted()).isFalse();
    }

    @Test
    void toggleDifferentColumnSwitches() {
        SortState state = new SortState(3);
        state.toggleColumn(0);
        state.toggleColumn(1);
        assertThat(state.sortColumn()).isEqualTo(1);
        assertThat(state.ascending()).isTrue();
    }

    @Test
    void toggleColumnOutOfRangeIgnored() {
        SortState state = new SortState(3);
        state.toggleColumn(-1);
        assertThat(state.isSorted()).isFalse();
        state.toggleColumn(99);
        assertThat(state.isSorted()).isFalse();
    }

    @Test
    void resetClearsSort() {
        SortState state = new SortState(3);
        state.toggleColumn(2);
        state.reset();
        assertThat(state.isSorted()).isFalse();
        assertThat(state.sortColumn()).isEqualTo(-1);
    }

    @Test
    void sortAscending() {
        SortState state = new SortState(1);
        state.toggleColumn(0);
        List<String[]> items = new ArrayList<>(
                Arrays.asList(new String[] {"banana"}, new String[] {"apple"}, new String[] {"cherry"}));
        List<Function<String[], String>> extractors = List.of(r -> r[0]);
        state.sort(items, extractors);
        assertThat(items).extracting(r -> r[0]).containsExactly("apple", "banana", "cherry");
    }

    @Test
    void sortDescending() {
        SortState state = new SortState(1);
        state.toggleColumn(0);
        state.toggleColumn(0); // now descending
        List<String[]> items = new ArrayList<>(
                Arrays.asList(new String[] {"banana"}, new String[] {"apple"}, new String[] {"cherry"}));
        List<Function<String[], String>> extractors = List.of(r -> r[0]);
        state.sort(items, extractors);
        assertThat(items).extracting(r -> r[0]).containsExactly("cherry", "banana", "apple");
    }

    @Test
    void sortUnsortedIsNoOp() {
        SortState state = new SortState(1);
        List<String[]> items = new ArrayList<>(
                Arrays.asList(new String[] {"banana"}, new String[] {"apple"}, new String[] {"cherry"}));
        List<Function<String[], String>> extractors = List.of(r -> r[0]);
        state.sort(items, extractors);
        assertThat(items).extracting(r -> r[0]).containsExactly("banana", "apple", "cherry");
    }

    @Test
    void decorateHeaderAddsUpIndicator() {
        SortState state = new SortState(3);
        state.toggleColumn(0); // col 0 asc
        Row row = state.decorateHeader(List.of("Name", "Version", "Scope"), Style.EMPTY);
        assertThat(row.cells().get(0).content().rawContent()).endsWith(" ▲");
    }

    @Test
    void decorateHeaderAddsDownIndicator() {
        SortState state = new SortState(3);
        state.toggleColumn(0);
        state.toggleColumn(0); // col 0 desc
        Row row = state.decorateHeader(List.of("Name", "Version", "Scope"), Style.EMPTY);
        assertThat(row.cells().get(0).content().rawContent()).endsWith(" ▼");
    }

    @Test
    void decorateHeaderNoIndicatorWhenUnsorted() {
        SortState state = new SortState(3);
        Row row = state.decorateHeader(List.of("Name", "Version", "Scope"), Style.EMPTY);
        assertThat(row.cells().get(0).content().rawContent()).isEqualTo("Name");
        assertThat(row.cells().get(1).content().rawContent()).isEqualTo("Version");
        assertThat(row.cells().get(2).content().rawContent()).isEqualTo("Scope");
    }
}
