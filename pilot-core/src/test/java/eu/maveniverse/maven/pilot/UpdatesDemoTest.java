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
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.export.ExportRequest;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Terminal;
import dev.tamboui.terminal.TestBackend;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.pilot.TuiTestRunner;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdatesDemoTest {

    private static final int WIDTH = 100;
    private static final int HEIGHT = 24;

    private String renderToText(dev.tamboui.tui.Renderer renderer) {
        var terminal = new Terminal<>(new TestBackend(WIDTH, HEIGHT));
        var frame = terminal.draw(renderer::render);
        return ExportRequest.export(frame.buffer()).text().toString();
    }

    @Test
    void browseAndSwitchViews(@TempDir Path tempDir) throws Exception {
        var root = createProject("parent", tempDir);
        var child = createProject("child", TestProjects.subdir(tempDir, "child"));
        child.parent = root;
        var projects = List.of(root, child);
        var model = ReactorModel.build(projects);
        var result = new ReactorCollector.CollectionResult(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());

        UpdatesTui tui = new UpdatesTui(result, model, "com.example:parent:1.0", (g, a) -> List.of());
        // Mark loading as done since we have no deps to resolve
        tui.loading = false;
        tui.status = "No updates";

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();

            // Initial view should show Dependencies tab active
            String rendered = renderToText(tui::renderStandalone);
            assertThat(rendered).contains("\u25B8 Dependencies");
            assertThat(rendered).doesNotContain("\u25B8 Modules");

            // Switch to Modules view
            pilot.press(KeyCode.TAB);
            pilot.pause();

            rendered = renderToText(tui::renderStandalone);
            assertThat(rendered).contains("\u25B8 Modules");
            assertThat(rendered).doesNotContain("\u25B8 Dependencies");

            // Switch back to Dependencies view
            pilot.press(KeyCode.TAB);
            pilot.pause();

            rendered = renderToText(tui::renderStandalone);
            assertThat(rendered).contains("\u25B8 Dependencies");
            assertThat(rendered).doesNotContain("\u25B8 Modules");

            pilot.press('q');
        }
    }
}
