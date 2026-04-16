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

import java.util.List;
import org.junit.jupiter.api.Test;

class HelpOverlayTest {

    @Test
    void parseMultipleSections() {
        List<HelpOverlay.Section> sections = HelpOverlay.parse("""
                ## Navigation
                ↑ / ↓           Move up / down
                PgUp / PgDn     Page up / down

                ## Actions
                Enter           Confirm
                q / Esc         Quit
                """);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).title()).isEqualTo("Navigation");
        assertThat(sections.get(0).entries()).hasSize(2);
        assertThat(sections.get(0).entries().get(0).key()).isEqualTo("↑ / ↓");
        assertThat(sections.get(0).entries().get(0).description()).isEqualTo("Move up / down");
        assertThat(sections.get(0).entries().get(1).key()).isEqualTo("PgUp / PgDn");

        assertThat(sections.get(1).title()).isEqualTo("Actions");
        assertThat(sections.get(1).entries()).hasSize(2);
        assertThat(sections.get(1).entries().get(0).key()).isEqualTo("Enter");
        assertThat(sections.get(1).entries().get(1).key()).isEqualTo("q / Esc");
    }

    @Test
    void parseDescriptionOnlyEntries() {
        List<HelpOverlay.Section> sections = HelpOverlay.parse("""
                ## About
                This is a description line without a key.
                Another description line.
                """);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).entries()).hasSize(2);
        assertThat(sections.get(0).entries().get(0).key()).isEmpty();
        assertThat(sections.get(0).entries().get(0).description())
                .isEqualTo("This is a description line without a key.");
        assertThat(sections.get(0).entries().get(1).key()).isEmpty();
        assertThat(sections.get(0).entries().get(1).description()).isEqualTo("Another description line.");
    }

    @Test
    void parseBlankLinesIgnored() {
        List<HelpOverlay.Section> sections = HelpOverlay.parse("""
                ## Section


                key1            value1


                key2            value2

                """);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).entries()).hasSize(2);
    }

    @Test
    void parseEmptyInput() {
        assertThat(HelpOverlay.parse("")).isEmpty();
        assertThat(HelpOverlay.parse("   \n  \n  ")).isEmpty();
    }

    @Test
    void parseLinesBeforeFirstSection() {
        List<HelpOverlay.Section> sections = HelpOverlay.parse("""
                stray line without a section
                ## First
                a               b
                """);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).title()).isEqualTo("First");
    }

    @Test
    void parseConcatenatedTextBlocks() {
        String text = """
                ## Nav
                ↑ / ↓           Move
                """ + """
                ## General
                h               Help
                """;
        List<HelpOverlay.Section> sections = HelpOverlay.parse(text);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).title()).isEqualTo("Nav");
        assertThat(sections.get(1).title()).isEqualTo("General");
    }

    @Test
    void parseSplitsOnTwoOrMoreSpaces() {
        List<HelpOverlay.Section> sections = HelpOverlay.parse("""
                ## Keys
                a / b           Description with  internal  spaces
                single-word-key  value
                """);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).entries().get(0).key()).isEqualTo("a / b");
        assertThat(sections.get(0).entries().get(0).description()).isEqualTo("Description with  internal  spaces");
        assertThat(sections.get(0).entries().get(1).key()).isEqualTo("single-word-key");
        assertThat(sections.get(0).entries().get(1).description()).isEqualTo("value");
    }
}
