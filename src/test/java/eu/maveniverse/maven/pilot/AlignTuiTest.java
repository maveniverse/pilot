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

import eu.maveniverse.domtrip.maven.AlignOptions;
import org.junit.jupiter.api.Test;

class AlignTuiTest {

    @Test
    void nextEnumWrapsAround() {
        var values = AlignOptions.VersionStyle.values();
        assertThat(AlignTui.nextEnum(values, AlignOptions.VersionStyle.INLINE))
                .isEqualTo(AlignOptions.VersionStyle.MANAGED);
        assertThat(AlignTui.nextEnum(values, AlignOptions.VersionStyle.MANAGED))
                .isEqualTo(AlignOptions.VersionStyle.INLINE);
    }

    @Test
    void prevEnumWrapsAround() {
        var values = AlignOptions.VersionStyle.values();
        assertThat(AlignTui.prevEnum(values, AlignOptions.VersionStyle.INLINE))
                .isEqualTo(AlignOptions.VersionStyle.MANAGED);
        assertThat(AlignTui.prevEnum(values, AlignOptions.VersionStyle.MANAGED))
                .isEqualTo(AlignOptions.VersionStyle.INLINE);
    }

    @Test
    void nextEnumCyclesAllPropertyNamingValues() {
        var values = AlignOptions.PropertyNamingConvention.values();
        var current = AlignOptions.PropertyNamingConvention.DOT_SUFFIX;
        current = AlignTui.nextEnum(values, current);
        assertThat(current).isEqualTo(AlignOptions.PropertyNamingConvention.DASH_SUFFIX);
        current = AlignTui.nextEnum(values, current);
        assertThat(current).isEqualTo(AlignOptions.PropertyNamingConvention.CAMEL_CASE);
        current = AlignTui.nextEnum(values, current);
        assertThat(current).isEqualTo(AlignOptions.PropertyNamingConvention.DOT_PREFIX);
        current = AlignTui.nextEnum(values, current);
        assertThat(current).isEqualTo(AlignOptions.PropertyNamingConvention.DOT_SUFFIX);
    }

    @Test
    void buildSelectedOptionsReflectsDefaults() {
        var detected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.PROPERTY)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();

        var tui = new AlignTui("/tmp/pom.xml", "g:a:1.0", "<project/>", detected);
        var options = tui.buildSelectedOptions();

        assertThat(options.versionStyle()).isEqualTo(AlignOptions.VersionStyle.MANAGED);
        assertThat(options.versionSource()).isEqualTo(AlignOptions.VersionSource.PROPERTY);
        assertThat(options.namingConvention()).isEqualTo(AlignOptions.PropertyNamingConvention.DOT_SUFFIX);
    }

    @Test
    void cycleForwardChangesSelectedStyle() {
        var detected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.INLINE)
                .versionSource(AlignOptions.VersionSource.LITERAL)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();

        var tui = new AlignTui("/tmp/pom.xml", "g:a:1.0", "<project/>", detected);
        tui.cycleForward(); // row 0 = VersionStyle

        var options = tui.buildSelectedOptions();
        assertThat(options.versionStyle()).isEqualTo(AlignOptions.VersionStyle.MANAGED);
    }

    @Test
    void cycleBackwardChangesSelectedStyle() {
        var detected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.LITERAL)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();

        var tui = new AlignTui("/tmp/pom.xml", "g:a:1.0", "<project/>", detected);
        tui.cycleBackward(); // row 0 = VersionStyle

        var options = tui.buildSelectedOptions();
        assertThat(options.versionStyle()).isEqualTo(AlignOptions.VersionStyle.INLINE);
    }
}
