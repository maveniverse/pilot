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

import dev.tamboui.style.Color;
import dev.tamboui.text.Span;
import java.util.List;
import org.junit.jupiter.api.Test;

class TabBarTest {

    private enum TwoTabs {
        FIRST,
        SECOND
    }

    private enum ThreeTabs {
        A,
        B,
        C
    }

    @Test
    void renderProducesCorrectSpanCount() {
        List<Span> spans = TabBar.render(TwoTabs.FIRST, TwoTabs.values(), Enum::name);
        // 2 tabs * 2 spans each (separator + tab label) = 4
        assertThat(spans).hasSize(4);
    }

    @Test
    void renderThreeTabsProducesCorrectSpanCount() {
        List<Span> spans = TabBar.render(ThreeTabs.A, ThreeTabs.values(), Enum::name);
        // 3 tabs * 2 spans each = 6
        assertThat(spans).hasSize(6);
    }

    @Test
    void renderActiveTabHasIndicator() {
        List<Span> spans = TabBar.render(TwoTabs.FIRST, TwoTabs.values(), v -> switch (v) {
            case FIRST -> "First";
            case SECOND -> "Second";
        });
        // spans[1] is the first tab label (spans[0] is separator)
        assertThat(spans.get(1).content()).contains("\u25B8 ");
        assertThat(spans.get(1).content()).contains("First");
    }

    @Test
    void renderInactiveTabHasNoIndicator() {
        List<Span> spans = TabBar.render(TwoTabs.FIRST, TwoTabs.values(), v -> switch (v) {
            case FIRST -> "First";
            case SECOND -> "Second";
        });
        // spans[3] is the second tab label (inactive)
        assertThat(spans.get(3).content()).doesNotContain("\u25B8");
        assertThat(spans.get(3).content()).contains("Second");
    }

    @Test
    void renderWithCustomActiveColor() {
        List<Span> spans = TabBar.render(TwoTabs.FIRST, TwoTabs.values(), Enum::name, v -> Color.RED);
        // Active tab should use the custom color
        assertThat(spans.get(1).style().fg()).contains(Color.RED);
        // Inactive tab should use DARK_GRAY
        assertThat(spans.get(3).style().fg()).contains(Color.DARK_GRAY);
    }

    @Test
    void renderDefaultActiveColorIsYellow() {
        List<Span> spans = TabBar.render(TwoTabs.FIRST, TwoTabs.values(), Enum::name);
        assertThat(spans.get(1).style().fg()).contains(Color.YELLOW);
    }

    @Test
    void nextCyclesToNextValue() {
        assertThat(TabBar.next(TwoTabs.FIRST, TwoTabs.values())).isEqualTo(TwoTabs.SECOND);
        assertThat(TabBar.next(ThreeTabs.A, ThreeTabs.values())).isEqualTo(ThreeTabs.B);
        assertThat(TabBar.next(ThreeTabs.B, ThreeTabs.values())).isEqualTo(ThreeTabs.C);
    }

    @Test
    void nextWrapsAround() {
        assertThat(TabBar.next(TwoTabs.SECOND, TwoTabs.values())).isEqualTo(TwoTabs.FIRST);
        assertThat(TabBar.next(ThreeTabs.C, ThreeTabs.values())).isEqualTo(ThreeTabs.A);
    }

    @Test
    void renderSwitchingActiveTab() {
        List<Span> spansFirst = TabBar.render(TwoTabs.FIRST, TwoTabs.values(), Enum::name);
        List<Span> spansSecond = TabBar.render(TwoTabs.SECOND, TwoTabs.values(), Enum::name);

        // First tab active in spansFirst, inactive in spansSecond
        assertThat(spansFirst.get(1).style().fg()).contains(Color.YELLOW);
        assertThat(spansSecond.get(1).style().fg()).contains(Color.DARK_GRAY);

        // Second tab inactive in spansFirst, active in spansSecond
        assertThat(spansFirst.get(3).style().fg()).contains(Color.DARK_GRAY);
        assertThat(spansSecond.get(3).style().fg()).contains(Color.YELLOW);
    }
}
