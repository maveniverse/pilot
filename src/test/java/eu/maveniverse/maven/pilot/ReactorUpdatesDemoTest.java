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

import dev.tamboui.layout.Size;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.pilot.TuiTestRunner;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReactorUpdatesDemoTest {

    @Test
    void browseAndSwitchViews(@TempDir java.nio.file.Path tempDir) throws Exception {
        var root = createProject("parent", tempDir);
        var model = ReactorModel.build(List.of(root));
        var result = new ReactorCollector.CollectionResult(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());

        ReactorUpdatesTui tui = new ReactorUpdatesTui(result, model, "com.example:parent:1.0", (g, a) -> List.of());
        // Mark loading as done since we have no deps to resolve
        tui.loading = false;
        tui.status = "No updates";

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::render, new Size(100, 24))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();

            // Switch to Modules view
            pilot.press(KeyCode.TAB);
            pilot.pause();

            // Switch back to Dependencies view
            pilot.press(KeyCode.TAB);
            pilot.pause();

            pilot.press('q');
        }
    }
}
