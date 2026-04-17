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

import dev.tamboui.tui.event.KeyEvent;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PomTuiRenderTest {

    private static final String SAMPLE_POM = """
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

    private PomTui createTui() {
        XmlTreeModel effective = XmlTreeModel.parse(SAMPLE_POM);
        return new PomTui(SAMPLE_POM, effective, null, "pom.xml", Map.of());
    }

    @Test
    void renderShowsPomContent() {
        var tui = createTui();
        String output = render(tui::renderStandalone);

        assertThat(output).contains("groupId").contains("artifactId").contains("demo");
    }

    @Test
    void effectiveViewPanelModeDivider() {
        var tui = createTui();
        tui.handleEvent(KeyEvent.ofChar('2'), null);
        String output = render(f -> tui.render(f, f.area()));

        assertThat(output).contains("─".repeat(10));
    }

    @Test
    void effectiveViewStandaloneDivider() {
        var tui = createTui();
        tui.handleEvent(KeyEvent.ofChar('2'), null);
        String output = render(tui::renderStandalone);

        assertThat(output).contains("─".repeat(10));
    }
}
