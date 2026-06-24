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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiffOverlayTest {

    @Test
    void initiallyInactive() {
        DiffOverlay overlay = new DiffOverlay();
        assertThat(overlay.isActive()).isFalse();
        assertThat(overlay.lines()).isNull();
        assertThat(overlay.scroll()).isEqualTo(0);
    }

    @Test
    void openWithChanges() {
        DiffOverlay overlay = new DiffOverlay();
        long changes = overlay.open("line1\nline2\n", "line1\nmodified\n");

        assertThat(changes).isGreaterThan(0);
        assertThat(overlay.isActive()).isTrue();
        assertThat(overlay.lines()).isNotEmpty();
        assertThat(overlay.scroll()).isEqualTo(0);
    }

    @Test
    void openWithNoChanges() {
        DiffOverlay overlay = new DiffOverlay();
        long changes = overlay.open("same content", "same content");

        assertThat(changes).isEqualTo(0);
        assertThat(overlay.isActive()).isFalse();
    }

    @Test
    void close() {
        DiffOverlay overlay = new DiffOverlay();
        overlay.open("a\n", "b\n");
        assertThat(overlay.isActive()).isTrue();

        overlay.close();
        assertThat(overlay.isActive()).isFalse();
        assertThat(overlay.scroll()).isEqualTo(0);
    }

    @Test
    void openAfterClose() {
        DiffOverlay overlay = new DiffOverlay();
        overlay.open("a\n", "b\n");
        overlay.close();

        long changes = overlay.open("x\n", "y\n");
        assertThat(changes).isGreaterThan(0);
        assertThat(overlay.isActive()).isTrue();
    }

    @Test
    void openMultiWithChanges() {
        DiffOverlay overlay = new DiffOverlay();
        Map<String, Map.Entry<String, String>> files = new LinkedHashMap<>();
        files.put("file1.txt", Map.entry("old1\n", "new1\n"));
        files.put("file2.txt", Map.entry("old2\n", "new2\n"));

        long changes = overlay.openMulti(files);
        assertThat(changes).isGreaterThan(0);
        assertThat(overlay.isActive()).isTrue();
        assertThat(overlay.lines()).isNotEmpty();
    }

    @Test
    void openMultiWithNoChanges() {
        DiffOverlay overlay = new DiffOverlay();
        Map<String, Map.Entry<String, String>> files = new LinkedHashMap<>();
        files.put("same.txt", Map.entry("content\n", "content\n"));

        long changes = overlay.openMulti(files);
        assertThat(changes).isEqualTo(0);
        assertThat(overlay.isActive()).isFalse();
    }

    @Test
    void openMultiContainsFileHeaders() {
        DiffOverlay overlay = new DiffOverlay();
        Map<String, Map.Entry<String, String>> files = new LinkedHashMap<>();
        files.put("pom.xml", Map.entry("a\n", "b\n"));
        files.put("other.xml", Map.entry("x\n", "y\n"));

        overlay.openMulti(files);
        assertThat(overlay.lines().stream()
                        .filter(l -> l.type() == UnifiedDiff.Type.HEADER)
                        .count())
                .isEqualTo(2);
    }

    @Test
    void openTreeImpactActivatesOverlay() {
        DiffOverlay overlay = new DiffOverlay();
        List<TreeDiff.DiffEntry> entries = List.of(
                new TreeDiff.DiffEntry("g:a", "1.0", 0, TreeDiff.Side.SAME),
                new TreeDiff.DiffEntry("g:b", "1.0", 1, TreeDiff.Side.LEFT),
                new TreeDiff.DiffEntry("g:b", "2.0", 1, TreeDiff.Side.RIGHT));

        overlay.openTreeImpact(entries);
        assertThat(overlay.isActive()).isTrue();
        assertThat(overlay.lines()).hasSize(3);
    }

    @Test
    void openTreeImpactMapsTypesCorrectly() {
        DiffOverlay overlay = new DiffOverlay();
        List<TreeDiff.DiffEntry> entries = List.of(
                new TreeDiff.DiffEntry("g:a", "1.0", 0, TreeDiff.Side.SAME),
                new TreeDiff.DiffEntry("g:b", "1.0", 1, TreeDiff.Side.LEFT),
                new TreeDiff.DiffEntry("g:c", "2.0", 1, TreeDiff.Side.RIGHT));

        overlay.openTreeImpact(entries);
        assertThat(overlay.lines().get(0).type()).isEqualTo(UnifiedDiff.Type.CONTEXT);
        assertThat(overlay.lines().get(1).type()).isEqualTo(UnifiedDiff.Type.REMOVED);
        assertThat(overlay.lines().get(2).type()).isEqualTo(UnifiedDiff.Type.ADDED);
    }

    @Test
    void openTreeImpactIndentsByDepth() {
        DiffOverlay overlay = new DiffOverlay();
        List<TreeDiff.DiffEntry> entries = List.of(
                new TreeDiff.DiffEntry("g:root", "1.0", 0, TreeDiff.Side.SAME),
                new TreeDiff.DiffEntry("g:child", "1.0", 2, TreeDiff.Side.SAME));

        overlay.openTreeImpact(entries);
        assertThat(overlay.lines().get(0).text()).startsWith("g:root");
        assertThat(overlay.lines().get(1).text()).startsWith("    g:child");
    }

    @Test
    void scrollUpDoesNotGoBelowZero() {
        DiffOverlay overlay = new DiffOverlay();
        overlay.open("a\nb\nc\nd\ne\nf\n", "x\ny\nz\nd\ne\nf\n");
        assertThat(overlay.scroll()).isEqualTo(0);
        overlay.scrollUp();
        assertThat(overlay.scroll()).isEqualTo(0);
    }

    @Test
    void scrollDownIncrementsScroll() {
        DiffOverlay overlay = new DiffOverlay();
        overlay.open("a\nb\nc\nd\ne\nf\ng\nh\n", "x\ny\nz\nw\ne\nf\ng\nh\n");
        overlay.scrollDown(2);
        assertThat(overlay.scroll()).isGreaterThanOrEqualTo(0);
    }
}
