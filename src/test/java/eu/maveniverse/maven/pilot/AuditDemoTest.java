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

import dev.tamboui.export.ExportRequest;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Terminal;
import dev.tamboui.terminal.TestBackend;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.pilot.TuiTestRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditDemoTest {

    private static final int WIDTH = 100;
    private static final int HEIGHT = 24;

    private String renderToText(dev.tamboui.tui.Renderer renderer) {
        var terminal = new Terminal<>(new TestBackend(WIDTH, HEIGHT));
        var frame = terminal.draw(renderer::render);
        return ExportRequest.export(frame.buffer()).text().toString();
    }

    @Test
    void browseAndSwitchViews(@TempDir Path tempDir) throws Exception {
        String pomPath =
                Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();

        List<AuditTui.AuditEntry> entries = new ArrayList<>();
        var e1 = new AuditTui.AuditEntry("org.slf4j", "slf4j-api", "2.0.9", "compile");
        e1.license = "MIT";
        e1.licenseLoaded = true;
        e1.vulnsLoaded = true;
        e1.vulnerabilities = List.of();
        entries.add(e1);

        var e2 = new AuditTui.AuditEntry("com.google.guava", "guava", "33.0.0-jre", "compile");
        e2.license = "Apache-2.0";
        e2.licenseLoaded = true;
        e2.vulnsLoaded = true;
        e2.vulnerabilities = List.of();
        entries.add(e2);

        AuditTui tui = new AuditTui(entries, "com.example:demo:1.0.0", null, pomPath);

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::render, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();

            // Initial view: Licenses tab
            String rendered = renderToText(tui::render);
            assertThat(rendered).contains("Licenses");

            // Switch to By License view
            pilot.press(KeyCode.TAB);
            pilot.pause();

            rendered = renderToText(tui::render);
            assertThat(rendered).contains("By License");

            // Switch to Vulnerabilities view
            pilot.press(KeyCode.TAB);
            pilot.pause();

            rendered = renderToText(tui::render);
            assertThat(rendered).contains("Vulnerabilities");

            // Switch back to Licenses view
            pilot.press(KeyCode.TAB);
            pilot.pause();

            rendered = renderToText(tui::render);
            assertThat(rendered).contains("Licenses");

            pilot.press('q');
        }
    }

    @Test
    void emptyEntries(@TempDir Path tempDir) throws Exception {
        String pomPath =
                Files.writeString(tempDir.resolve("pom.xml"), "<project/>").toString();

        AuditTui tui = new AuditTui(new ArrayList<>(), "com.example:demo:1.0.0", null, pomPath);

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::render, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            pilot.pause();

            // Verify tab bar renders even with empty data
            String rendered = renderToText(tui::render);
            assertThat(rendered).contains("Licenses");

            // Switch views on empty data
            pilot.press(KeyCode.TAB);
            pilot.pause();

            rendered = renderToText(tui::render);
            assertThat(rendered).contains("By License");

            pilot.press('q');
        }
    }
}
