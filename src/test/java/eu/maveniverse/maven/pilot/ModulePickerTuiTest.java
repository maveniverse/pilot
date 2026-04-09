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

import static eu.maveniverse.maven.pilot.TestProjects.createProject;
import static eu.maveniverse.maven.pilot.TestProjects.subdir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tamboui.layout.Size;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.pilot.TuiTestRunner;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModulePickerTuiTest {

    @TempDir
    Path tempDir;

    @Test
    void enterSelectsSingleModule() throws Exception {
        Path childDir = subdir(tempDir, "child");
        MavenProject root = createProject("parent", tempDir);
        MavenProject child = createProject("child", childDir);

        ReactorModel model = ReactorModel.build(List.of(root, child));
        ModulePickerTui picker = new ModulePickerTui(model, "com.example:parent:1.0", "test");

        try (var testRunner = TuiTestRunner.runTest(picker::handleEvent, picker::render, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();
            pilot.press(KeyCode.ENTER);
        }

        assertThat(picker.getPickResult()).isNotNull();
        assertThat(picker.getPickResult().projects()).hasSize(1);
        assertThat(picker.getPickResult().projects().get(0).getArtifactId()).isEqualTo("parent");
    }

    @Test
    void enterAfterNavigateSelectsChild() throws Exception {
        Path childDir = subdir(tempDir, "child");
        MavenProject root = createProject("parent", tempDir);
        MavenProject child = createProject("child", childDir);

        ReactorModel model = ReactorModel.build(List.of(root, child));
        ModulePickerTui picker = new ModulePickerTui(model, "com.example:parent:1.0", "test");

        try (var testRunner = TuiTestRunner.runTest(picker::handleEvent, picker::render, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();
            pilot.press(KeyCode.DOWN);
            pilot.pause();
            pilot.press(KeyCode.ENTER);
        }

        assertThat(picker.getPickResult()).isNotNull();
        assertThat(picker.getPickResult().projects()).hasSize(1);
        assertThat(picker.getPickResult().projects().get(0).getArtifactId()).isEqualTo("child");
    }

    @Test
    void quitReturnsNull() throws Exception {
        MavenProject root = createProject("app", tempDir);
        ReactorModel model = ReactorModel.build(List.of(root));
        ModulePickerTui picker = new ModulePickerTui(model, "com.example:app:1.0", "test");

        try (var testRunner = TuiTestRunner.runTest(picker::handleEvent, picker::render, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();
            pilot.press('q');
        }

        assertThat(picker.getPickResult()).isNull();
    }

    @Test
    void escapeReturnsNull() throws Exception {
        MavenProject root = createProject("app", tempDir);
        ReactorModel model = ReactorModel.build(List.of(root));
        ModulePickerTui picker = new ModulePickerTui(model, "com.example:app:1.0", "test");

        try (var testRunner = TuiTestRunner.runTest(picker::handleEvent, picker::render, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();
            pilot.press(KeyCode.ESCAPE);
        }

        assertThat(picker.getPickResult()).isNull();
    }

    @Test
    void sSearchReturnsDirectTool() throws Exception {
        Path childDir = subdir(tempDir, "child");
        MavenProject root = createProject("parent", tempDir);
        MavenProject child = createProject("child", childDir);

        ReactorModel model = ReactorModel.build(List.of(root, child));
        ModulePickerTui picker = new ModulePickerTui(model, "com.example:parent:1.0", "test", true);

        try (var testRunner = TuiTestRunner.runTest(picker::handleEvent, picker::render, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();
            pilot.press('s');
        }

        assertThat(picker.getPickResult()).isNotNull();
        assertThat(picker.getPickResult().directTool()).isEqualTo("search");
        assertThat(picker.getPickResult().projects()).isEmpty();
    }

    @Test
    void sIgnoredWithoutSearchEnabled() throws Exception {
        MavenProject root = createProject("app", tempDir);
        ReactorModel model = ReactorModel.build(List.of(root));
        ModulePickerTui picker = new ModulePickerTui(model, "com.example:app:1.0", "test");

        try (var testRunner = TuiTestRunner.runTest(picker::handleEvent, picker::render, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();
            pilot.press('s');
            pilot.pause();
            pilot.press('q');
        }

        assertThat(picker.getPickResult()).isNull();
    }

    @Test
    void upKeyMovesSelectionUp() throws Exception {
        Path childDir = subdir(tempDir, "child");
        MavenProject root = createProject("parent", tempDir);
        MavenProject child = createProject("child", childDir);

        ReactorModel model = ReactorModel.build(List.of(root, child));
        ModulePickerTui picker = new ModulePickerTui(model, "com.example:parent:1.0", "test");

        try (var testRunner = TuiTestRunner.runTest(picker::handleEvent, picker::render, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();
            pilot.press(KeyCode.DOWN);
            pilot.pause();
            pilot.press(KeyCode.UP);
            pilot.pause();
            pilot.press(KeyCode.ENTER);
        }

        assertThat(picker.getPickResult()).isNotNull();
        assertThat(picker.getPickResult().projects().get(0).getArtifactId()).isEqualTo("parent");
    }

    @Test
    void rightExpandsAndLeftCollapses() throws Exception {
        Path childDir = subdir(tempDir, "child");
        MavenProject root = createProject("parent", tempDir);
        MavenProject child = createProject("child", childDir);

        ReactorModel model = ReactorModel.build(List.of(root, child));
        ModulePickerTui picker = new ModulePickerTui(model, "com.example:parent:1.0", "test");

        try (var testRunner = TuiTestRunner.runTest(picker::handleEvent, picker::render, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();
            // Collapse root with LEFT
            pilot.press(KeyCode.LEFT);
            pilot.pause();
            // Expand root with RIGHT
            pilot.press(KeyCode.RIGHT);
            pilot.pause();
            // Navigate to child and select
            pilot.press(KeyCode.DOWN);
            pilot.pause();
            pilot.press(KeyCode.ENTER);
        }

        assertThat(picker.getPickResult()).isNotNull();
        assertThat(picker.getPickResult().projects().get(0).getArtifactId()).isEqualTo("child");
    }

    @Test
    void leftOnLeafNavigatesToParent() throws Exception {
        Path childDir = subdir(tempDir, "child");
        MavenProject root = createProject("parent", tempDir);
        MavenProject child = createProject("child", childDir);

        ReactorModel model = ReactorModel.build(List.of(root, child));
        ModulePickerTui picker = new ModulePickerTui(model, "com.example:parent:1.0", "test");

        try (var testRunner = TuiTestRunner.runTest(picker::handleEvent, picker::render, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();
            // Navigate to child (leaf)
            pilot.press(KeyCode.DOWN);
            pilot.pause();
            // LEFT on leaf navigates to parent
            pilot.press(KeyCode.LEFT);
            pilot.pause();
            // Now on root, select it
            pilot.press(KeyCode.ENTER);
        }

        assertThat(picker.getPickResult()).isNotNull();
        assertThat(picker.getPickResult().projects().get(0).getArtifactId()).isEqualTo("parent");
    }

    @Test
    void spaceMovesDownOnExpandedNode() throws Exception {
        Path childDir = subdir(tempDir, "child");
        MavenProject root = createProject("parent", tempDir);
        MavenProject child = createProject("child", childDir);

        ReactorModel model = ReactorModel.build(List.of(root, child));
        ModulePickerTui picker = new ModulePickerTui(model, "com.example:parent:1.0", "test");

        try (var testRunner = TuiTestRunner.runTest(picker::handleEvent, picker::render, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();
            // Root is expanded, Space moves down to child
            pilot.press(' ');
            pilot.pause();
            pilot.press(KeyCode.ENTER);
        }

        assertThat(picker.getPickResult()).isNotNull();
        assertThat(picker.getPickResult().projects().get(0).getArtifactId()).isEqualTo("child");
    }

    @Test
    void expandAllWithE() throws Exception {
        Path childDir = subdir(tempDir, "child");
        MavenProject root = createProject("parent", tempDir);
        MavenProject child = createProject("child", childDir);

        ReactorModel model = ReactorModel.build(List.of(root, child));
        ModulePickerTui picker = new ModulePickerTui(model, "com.example:parent:1.0", "test");

        try (var testRunner = TuiTestRunner.runTest(picker::handleEvent, picker::render, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();
            pilot.press('e');
            pilot.pause();
            pilot.press('q');
        }

        assertThat(picker.getPickResult()).isNull();
    }

    @Test
    void collapseAllWithW() throws Exception {
        Path childDir = subdir(tempDir, "child");
        MavenProject root = createProject("parent", tempDir);
        MavenProject child = createProject("child", childDir);

        ReactorModel model = ReactorModel.build(List.of(root, child));
        ModulePickerTui picker = new ModulePickerTui(model, "com.example:parent:1.0", "test");

        try (var testRunner = TuiTestRunner.runTest(picker::handleEvent, picker::render, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();
            pilot.press('w');
            pilot.pause();
            pilot.press('q');
        }

        assertThat(picker.getPickResult()).isNull();
    }

    @Test
    void helpOverlayOpensAndQuit() throws Exception {
        MavenProject root = createProject("app", tempDir);
        ReactorModel model = ReactorModel.build(List.of(root));
        ModulePickerTui picker = new ModulePickerTui(model, "com.example:app:1.0", "test");

        try (var testRunner = TuiTestRunner.runTest(picker::handleEvent, picker::render, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();
            // Open help overlay
            pilot.press('h');
            pilot.pause();
            // Quit from help overlay
            pilot.press('q');
        }

        assertThat(picker.getPickResult()).isNull();
    }

    @Test
    void forEachSelectedRejectsNull() {
        assertThatThrownBy(() -> ModulePickerTui.forEachSelected(null, "test", p -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("projects must be non-empty");
    }

    @Test
    void forEachSelectedRejectsEmpty() {
        assertThatThrownBy(() -> ModulePickerTui.forEachSelected(List.of(), "test", p -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("projects must be non-empty");
    }
}
