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

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.tui.event.MouseButton;
import dev.tamboui.tui.event.MouseEvent;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests that mouse clicks on tree arrows correctly select rows and toggle expand/collapse.
 * Each test renders the TUI to populate layout geometry, finds the arrow glyph in the
 * buffer (using buffer coordinates, not text positions), then simulates a click at that
 * position and verifies the state change.
 */
class MouseClickTest {

    private static final String POM_WITH_TREE = """
            <project>
              <groupId>com.example</groupId>
              <artifactId>demo</artifactId>
              <version>1.0</version>
              <dependencies>
                <dependency>
                  <groupId>org.slf4j</groupId>
                  <artifactId>slf4j-api</artifactId>
                  <version>2.0.9</version>
                </dependency>
              </dependencies>
            </project>
            """;

    private static final Rect AREA = new Rect(0, 0, WIDTH, HEIGHT);

    private PomTui createPomTui() {
        XmlTreeModel effective = XmlTreeModel.parse(POM_WITH_TREE);
        return new PomTui(POM_WITH_TREE, effective, null, "pom.xml", Map.of());
    }

    private MouseEvent click(int x, int y) {
        return MouseEvent.press(MouseButton.LEFT, x, y);
    }

    private Buffer renderPom(PomTui tui) {
        return renderToBuffer(WIDTH, HEIGHT, f -> tui.render(f, AREA));
    }

    @Test
    void pomClickOnArrowCollapsesNode() {
        var tui = createPomTui();
        Buffer buf = renderPom(tui);

        // <dependencies> should be expanded initially
        String before = bufferText(buf);
        assertThat(before).contains("▼ <dependencies>").contains("<dependency>");

        // Find and click on the ▼ arrow
        int[] pos = findInBuffer(buf, "▼ <dependencies>");
        assertThat(pos).isNotNull();
        tui.handleMouseEvent(click(pos[0], pos[1]), AREA);

        // After collapse, <dependency> children should be hidden
        String after = render(f -> tui.render(f, AREA));
        assertThat(after).contains("▶ <dependencies>").doesNotContain("<dependency>");
    }

    @Test
    void pomClickOnArrowExpandsNode() {
        var tui = createPomTui();

        // Collapse <dependencies> first
        Buffer buf = renderPom(tui);
        int[] pos = findInBuffer(buf, "▼ <dependencies>");
        assertThat(pos).isNotNull();
        tui.handleMouseEvent(click(pos[0], pos[1]), AREA);

        // Verify collapsed
        String collapsed = render(f -> tui.render(f, AREA));
        assertThat(collapsed).contains("▶ <dependencies>").doesNotContain("<dependency>");

        // Find and click the ▶ arrow to expand
        buf = renderPom(tui);
        int[] collapsedPos = findInBuffer(buf, "▶ <dependencies>");
        assertThat(collapsedPos).isNotNull();
        tui.handleMouseEvent(click(collapsedPos[0], collapsedPos[1]), AREA);

        // After expand, <dependency> should be visible again
        String after = render(f -> tui.render(f, AREA));
        assertThat(after).contains("▼ <dependencies>").contains("<dependency>");
    }

    @Test
    void pomClickSelectsRow() {
        var tui = createPomTui();
        Buffer buf = renderPom(tui);

        int[] pos = findInBuffer(buf, "<version>1.0");
        assertThat(pos).isNotNull();

        tui.handleMouseEvent(click(pos[0], pos[1]), AREA);
        assertThat(tui.status()).contains("Raw POM");
    }

    @Test
    void pomClickNextToArrowSelectsButDoesNotToggle() {
        var tui = createPomTui();
        Buffer buf = renderPom(tui);

        int[] pos = findInBuffer(buf, "▼ <dependencies>");
        assertThat(pos).isNotNull();

        // Click well past the arrow (on the tag name text)
        tui.handleMouseEvent(click(pos[0] + 5, pos[1]), AREA);

        // Node should remain expanded — ▼ still shows and children are visible
        String after = render(f -> tui.render(f, AREA));
        assertThat(after).contains("▼ <dependencies>").contains("<dependency>");
    }

    @Test
    void pomClickOnTabBarDoesNotSelectRow() {
        var tui = createPomTui();
        renderPom(tui);

        // Click on the tab bar row (y=0) — should be handled by tab bar, not data rows
        boolean handled = tui.handleMouseEvent(click(5, 0), AREA);
        assertThat(handled).isTrue();
    }
}
