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

import dev.tamboui.layout.Size;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.pilot.TuiTestRunner;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Demo test for Dependency Updates TUI.
 */
class UpdatesDemoTest {

    @Test
    void browseAndFilterUpdates() throws Exception {
        List<UpdatesTui.DependencyInfo> deps = new ArrayList<>();

        var d1 = new UpdatesTui.DependencyInfo("com.google.guava", "guava", "33.0.0-jre", "compile", "jar");
        d1.newestVersion = "33.4.0-jre";
        d1.updateType = VersionComparator.UpdateType.MINOR;
        deps.add(d1);

        var d2 = new UpdatesTui.DependencyInfo("org.slf4j", "slf4j-api", "2.0.9", "compile", "jar");
        d2.newestVersion = "2.0.16";
        d2.updateType = VersionComparator.UpdateType.PATCH;
        deps.add(d2);

        var d3 = new UpdatesTui.DependencyInfo("commons-io", "commons-io", "2.15.1", "compile", "jar");
        d3.newestVersion = "2.18.0";
        d3.updateType = VersionComparator.UpdateType.MINOR;
        deps.add(d3);

        var d4 = new UpdatesTui.DependencyInfo("org.junit.jupiter", "junit-jupiter", "5.10.1", "test", "jar");
        d4.newestVersion = "5.11.4";
        d4.updateType = VersionComparator.UpdateType.MINOR;
        deps.add(d4);

        UpdatesTui tui = new UpdatesTui(deps, "/tmp/test-pom.xml", "com.example:demo:1.0.0");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::render, new Size(100, 24))) {

            Pilot pilot = testRunner.pilot();
            pilot.pause();

            // Navigate through updates
            pilot.press(KeyCode.DOWN);
            pilot.pause();
            pilot.press(KeyCode.DOWN);
            pilot.pause();

            // Toggle selection
            pilot.press(' ');
            pilot.pause();

            // Filter to patch only
            pilot.press('2');
            pilot.pause();

            // Back to all
            pilot.press('1');
            pilot.pause();

            // Select all
            pilot.press('a');
            pilot.pause();

            // Deselect all
            pilot.press('n');
            pilot.pause();

            // Quit
            pilot.press('q');
        }
    }
}
