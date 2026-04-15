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
}
