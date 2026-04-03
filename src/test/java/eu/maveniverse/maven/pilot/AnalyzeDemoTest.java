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
 * Demo test for Dependency Analysis TUI.
 */
class AnalyzeDemoTest {

    @Test
    void browseDeclaredAndTransitive() throws Exception {
        List<AnalyzeTui.DepEntry> declared = new ArrayList<>();
        declared.add(new AnalyzeTui.DepEntry("com.google.guava", "guava", "33.0.0-jre", "compile", true));
        declared.add(new AnalyzeTui.DepEntry("org.slf4j", "slf4j-api", "2.0.9", "compile", true));
        declared.add(new AnalyzeTui.DepEntry("commons-io", "commons-io", "2.15.1", "compile", true));

        List<AnalyzeTui.DepEntry> transitive = new ArrayList<>();
        var t1 = new AnalyzeTui.DepEntry("com.google.guava", "failureaccess", "1.0.2", "compile", false);
        t1.pulledBy = "guava";
        transitive.add(t1);
        var t2 = new AnalyzeTui.DepEntry("org.checkerframework", "checker-qual", "3.42.0", "compile", false);
        t2.pulledBy = "guava";
        transitive.add(t2);

        // Use a non-existent POM path since we won't actually write
        AnalyzeTui tui = new AnalyzeTui(declared, transitive, "/tmp/test-pom.xml", "com.example:demo:1.0.0");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::render, new Size(100, 24))) {

            Pilot pilot = testRunner.pilot();

            // Render initial state
            pilot.pause();

            // Navigate declared dependencies
            pilot.press(KeyCode.DOWN);
            pilot.pause();
            pilot.press(KeyCode.DOWN);
            pilot.pause();

            // Switch to transitive view
            pilot.press(KeyCode.TAB);
            pilot.pause();

            // Navigate transitive deps
            pilot.press(KeyCode.DOWN);
            pilot.pause();

            // Switch back
            pilot.press(KeyCode.TAB);
            pilot.pause();

            // Quit
            pilot.press('q');
        }
    }
}
