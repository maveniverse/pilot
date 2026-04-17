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

import static eu.maveniverse.maven.pilot.TuiTestHelper.*;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConflictsTuiRenderTest {

    @TempDir
    Path tempDir;

    private String pomPath;

    @BeforeEach
    void setUp() throws Exception {
        pomPath = Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();
    }

    private ConflictsTui createTuiWithConflicts() {
        List<ConflictsTui.ConflictGroup> conflicts = new ArrayList<>();

        var e1 = new ConflictsTui.ConflictEntry(
                "org.apache.commons", "commons-lang3", "3.12.0", "3.14.0", "commons-text:1.11.0", "compile");
        var e2 = new ConflictsTui.ConflictEntry(
                "org.apache.commons", "commons-lang3", "3.14.0", "3.14.0", "direct", "compile");
        conflicts.add(new ConflictsTui.ConflictGroup("org.apache.commons:commons-lang3", List.of(e1, e2)));

        var e3 =
                new ConflictsTui.ConflictEntry("org.slf4j", "slf4j-api", "2.0.9", "2.0.9", "logback:1.4.14", "compile");
        var e4 = new ConflictsTui.ConflictEntry("org.slf4j", "slf4j-api", "2.0.9", "2.0.9", "direct", "compile");
        conflicts.add(new ConflictsTui.ConflictGroup("org.slf4j:slf4j-api", List.of(e3, e4)));

        return new ConflictsTui(conflicts, pomPath, "com.example:demo:1.0.0");
    }

    @Test
    void renderShowsConflictGroups() {
        var tui = createTuiWithConflicts();
        String output = render(tui::renderStandalone);

        assertThat(output).contains("commons-lang3");
    }

    @Test
    void showAllRevealsConvergedGroups() {
        var tui = createTuiWithConflicts();
        tui.handleEvent(KeyEvent.ofChar('t'), null);
        String output = render(tui::renderStandalone);

        assertThat(output).contains("commons-lang3").contains("slf4j-api");
    }

    @Test
    void renderShowsProjectGav() {
        var tui = createTuiWithConflicts();
        String output = render(tui::renderStandalone);

        assertThat(output).contains("com.example:demo:1.0.0");
    }

    @Test
    void standaloneDividerBetweenTableAndDetails() {
        var tui = createTuiWithConflicts();
        String output = render(tui::renderStandalone);

        assertThat(output).contains("─".repeat(10));
    }

    @Test
    void panelModeDividerBetweenTableAndDetails() {
        var tui = createTuiWithConflicts();
        String output = render(f -> tui.render(f, f.area()));

        assertThat(output).contains("─".repeat(10));
    }

    @Test
    void panelModeNoDividerWhenDetailsHidden() {
        var tui = createTuiWithConflicts();
        tui.handleEvent(KeyEvent.ofKey(KeyCode.ENTER), null);
        String output = render(f -> tui.render(f, f.area()));

        long dividerLines = output.lines().filter(l -> l.matches("^─+$")).count();
        assertThat(dividerLines).isZero();
    }

    @Test
    void loadingStateShowsProgressCounter() {
        List<ConflictsTui.ConflictGroup> empty = List.of();
        var tui = new ConflictsTui(empty, pomPath, "com.example:demo:1.0.0");
        String output = render(tui::renderStandalone);

        assertThat(output).contains("Conflict Resolution");
    }

    @Test
    void renderShowsVersionInfo() {
        var tui = createTuiWithConflicts();
        String output = render(tui::renderStandalone);

        assertThat(output).contains("3.12.0").contains("3.14.0");
    }

    @Test
    void emptyConflictsRenderWithoutError() {
        var tui = new ConflictsTui(List.of(), pomPath, "com.example:app:2.0");
        String output = render(tui::renderStandalone);

        assertThat(output).contains("com.example:app:2.0");
    }
}
