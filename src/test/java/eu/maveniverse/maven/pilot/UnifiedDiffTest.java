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

import java.util.List;
import org.junit.jupiter.api.Test;

class UnifiedDiffTest {

    @Test
    void identicalInputsProduceAllContext() {
        String text = "line1\nline2\nline3";
        var result = UnifiedDiff.compute(text, text);

        assertThat(result).hasSize(3);
        assertThat(result).allMatch(dl -> dl.type() == UnifiedDiff.Type.CONTEXT);
        assertThat(result.get(0).text()).isEqualTo("line1");
        assertThat(result.get(1).text()).isEqualTo("line2");
        assertThat(result.get(2).text()).isEqualTo("line3");
    }

    @Test
    void emptyOriginalProducesAllAdded() {
        var result = UnifiedDiff.compute("", "line1\nline2");

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(dl -> dl.type() == UnifiedDiff.Type.ADDED);
        assertThat(result.get(0).text()).isEqualTo("line1");
        assertThat(result.get(1).text()).isEqualTo("line2");
    }

    @Test
    void emptyModifiedProducesAllRemoved() {
        var result = UnifiedDiff.compute("line1\nline2", "");

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(dl -> dl.type() == UnifiedDiff.Type.REMOVED);
    }

    @Test
    void singleLineChange() {
        var result = UnifiedDiff.compute("hello", "world");

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo(new UnifiedDiff.DiffLine(UnifiedDiff.Type.REMOVED, "hello"));
        assertThat(result.get(1)).isEqualTo(new UnifiedDiff.DiffLine(UnifiedDiff.Type.ADDED, "world"));
    }

    @Test
    void multiLineChangesWithContext() {
        String original = "a\nb\nc\nd";
        String modified = "a\nB\nc\nd";

        var result = UnifiedDiff.compute(original, modified);

        assertThat(result).hasSize(5);
        assertThat(result.get(0)).isEqualTo(new UnifiedDiff.DiffLine(UnifiedDiff.Type.CONTEXT, "a"));
        assertThat(result.get(1)).isEqualTo(new UnifiedDiff.DiffLine(UnifiedDiff.Type.REMOVED, "b"));
        assertThat(result.get(2)).isEqualTo(new UnifiedDiff.DiffLine(UnifiedDiff.Type.ADDED, "B"));
        assertThat(result.get(3)).isEqualTo(new UnifiedDiff.DiffLine(UnifiedDiff.Type.CONTEXT, "c"));
        assertThat(result.get(4)).isEqualTo(new UnifiedDiff.DiffLine(UnifiedDiff.Type.CONTEXT, "d"));
    }

    @Test
    void addedLinesInMiddle() {
        String original = "a\nc";
        String modified = "a\nb\nc";

        var result = UnifiedDiff.compute(original, modified);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).type()).isEqualTo(UnifiedDiff.Type.CONTEXT);
        assertThat(result.get(1)).isEqualTo(new UnifiedDiff.DiffLine(UnifiedDiff.Type.ADDED, "b"));
        assertThat(result.get(2).type()).isEqualTo(UnifiedDiff.Type.CONTEXT);
    }

    @Test
    void removedLinesInMiddle() {
        String original = "a\nb\nc";
        String modified = "a\nc";

        var result = UnifiedDiff.compute(original, modified);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).type()).isEqualTo(UnifiedDiff.Type.CONTEXT);
        assertThat(result.get(1)).isEqualTo(new UnifiedDiff.DiffLine(UnifiedDiff.Type.REMOVED, "b"));
        assertThat(result.get(2).type()).isEqualTo(UnifiedDiff.Type.CONTEXT);
    }

    @Test
    void changeCountReturnsNonContextLines() {
        var lines = List.of(
                new UnifiedDiff.DiffLine(UnifiedDiff.Type.CONTEXT, "a"),
                new UnifiedDiff.DiffLine(UnifiedDiff.Type.REMOVED, "b"),
                new UnifiedDiff.DiffLine(UnifiedDiff.Type.ADDED, "B"),
                new UnifiedDiff.DiffLine(UnifiedDiff.Type.CONTEXT, "c"));

        assertThat(UnifiedDiff.changeCount(lines)).isEqualTo(2);
    }

    @Test
    void changeCountZeroForIdentical() {
        var lines = List.of(
                new UnifiedDiff.DiffLine(UnifiedDiff.Type.CONTEXT, "a"),
                new UnifiedDiff.DiffLine(UnifiedDiff.Type.CONTEXT, "b"));

        assertThat(UnifiedDiff.changeCount(lines)).isZero();
    }

    @Test
    void filterContextKeepsSurroundingLines() {
        var lines = List.of(
                new UnifiedDiff.DiffLine(UnifiedDiff.Type.CONTEXT, "1"),
                new UnifiedDiff.DiffLine(UnifiedDiff.Type.CONTEXT, "2"),
                new UnifiedDiff.DiffLine(UnifiedDiff.Type.CONTEXT, "3"),
                new UnifiedDiff.DiffLine(UnifiedDiff.Type.REMOVED, "4"),
                new UnifiedDiff.DiffLine(UnifiedDiff.Type.ADDED, "4x"),
                new UnifiedDiff.DiffLine(UnifiedDiff.Type.CONTEXT, "5"),
                new UnifiedDiff.DiffLine(UnifiedDiff.Type.CONTEXT, "6"),
                new UnifiedDiff.DiffLine(UnifiedDiff.Type.CONTEXT, "7"),
                new UnifiedDiff.DiffLine(UnifiedDiff.Type.CONTEXT, "8"));

        var filtered = UnifiedDiff.filterContext(lines, 1);

        // Should keep lines 2-5 (1 context before/after the change at indices 3-4)
        assertThat(filtered).hasSize(4);
        assertThat(filtered.get(0).text()).isEqualTo("3");
        assertThat(filtered.get(1).text()).isEqualTo("4");
        assertThat(filtered.get(2).text()).isEqualTo("4x");
        assertThat(filtered.get(3).text()).isEqualTo("5");
    }

    @Test
    void maxScrollCalculation() {
        var lines = List.of(
                new UnifiedDiff.DiffLine(UnifiedDiff.Type.CONTEXT, "a"),
                new UnifiedDiff.DiffLine(UnifiedDiff.Type.CONTEXT, "b"),
                new UnifiedDiff.DiffLine(UnifiedDiff.Type.CONTEXT, "c"),
                new UnifiedDiff.DiffLine(UnifiedDiff.Type.CONTEXT, "d"),
                new UnifiedDiff.DiffLine(UnifiedDiff.Type.CONTEXT, "e"));

        // area height 5 = 3 inner lines (minus 2 for borders)
        assertThat(UnifiedDiff.maxScroll(lines, 5)).isEqualTo(2);
        // area height 10 = 8 inner lines > 5 lines → 0
        assertThat(UnifiedDiff.maxScroll(lines, 10)).isZero();
    }

    @Test
    void bothEmptyProducesEmptyResult() {
        var result = UnifiedDiff.compute("", "");
        assertThat(result).isEmpty();
    }
}
