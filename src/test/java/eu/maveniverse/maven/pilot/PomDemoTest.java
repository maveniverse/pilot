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
import org.junit.jupiter.api.Test;

/**
 * Demo test for POM Viewer TUI. Exercises key workflows and produces recordings.
 */
class PomDemoTest {

    private static final String RAW_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>demo-app</artifactId>
              <version>1.0.0</version>
              <packaging>jar</packaging>
              <name>Demo Application</name>
              <properties>
                <guava.version>33.0.0-jre</guava.version>
                <slf4j.version>2.0.9</slf4j.version>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>com.google.guava</groupId>
                  <artifactId>guava</artifactId>
                  <version>${guava.version}</version>
                </dependency>
                <dependency>
                  <groupId>org.slf4j</groupId>
                  <artifactId>slf4j-api</artifactId>
                  <version>${slf4j.version}</version>
                </dependency>
              </dependencies>
            </project>
            """;

    private static final String EFFECTIVE_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>demo-app</artifactId>
              <version>1.0.0</version>
              <packaging>jar</packaging>
              <name>Demo Application</name>
              <properties>
                <guava.version>33.0.0-jre</guava.version>
                <slf4j.version>2.0.9</slf4j.version>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>com.google.guava</groupId>
                  <artifactId>guava</artifactId>
                  <version>33.0.0-jre</version>
                </dependency>
                <dependency>
                  <groupId>org.slf4j</groupId>
                  <artifactId>slf4j-api</artifactId>
                  <version>2.0.9</version>
                </dependency>
              </dependencies>
            </project>
            """;

    @Test
    void browseAndNavigatePom() throws Exception {
        PomTui tui = new PomTui(RAW_POM, EFFECTIVE_POM, "pom.xml");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::render, new Size(100, 30))) {

            Pilot pilot = testRunner.pilot();

            // Initial render - Raw POM view
            pilot.pause();

            // Navigate down through the tree
            pilot.press(KeyCode.DOWN);
            pilot.pause();
            pilot.press(KeyCode.DOWN);
            pilot.pause();

            // Expand a node
            pilot.press(KeyCode.RIGHT);
            pilot.pause();

            // Switch to Effective POM view
            pilot.press(KeyCode.TAB);
            pilot.pause();

            // Navigate in effective POM
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.pause();

            // Expand all
            pilot.press('e');
            pilot.pause();

            // Collapse all
            pilot.press('w');
            pilot.pause();

            // Search for a tag
            pilot.press('/');
            pilot.press("guava");
            pilot.press(KeyCode.ENTER);
            pilot.pause();

            // Switch back to raw view
            pilot.press(KeyCode.TAB);
            pilot.pause();

            // Quit
            pilot.press('q');
        }
    }

    @Test
    void expandCollapseXmlNodes() throws Exception {
        PomTui tui = new PomTui(RAW_POM, EFFECTIVE_POM, "pom.xml");

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::render, new Size(100, 30))) {

            Pilot pilot = testRunner.pilot();
            pilot.pause();

            // Navigate to properties node (should be at depth 1, initially expanded)
            pilot.press(KeyCode.DOWN); // modelVersion
            pilot.press(KeyCode.DOWN); // groupId
            pilot.press(KeyCode.DOWN); // artifactId
            pilot.press(KeyCode.DOWN); // version
            pilot.press(KeyCode.DOWN); // packaging
            pilot.press(KeyCode.DOWN); // name
            pilot.press(KeyCode.DOWN); // properties
            pilot.pause();

            // Collapse properties
            pilot.press(KeyCode.LEFT);
            pilot.pause();

            // Expand again
            pilot.press(KeyCode.RIGHT);
            pilot.pause();

            pilot.press('q');
        }
    }
}
