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

import org.junit.jupiter.api.Test;

class VersionComparatorTest {

    @Test
    void classifyPatch() {
        assertThat(VersionComparator.classify("1.2.3", "1.2.4")).isEqualTo(VersionComparator.UpdateType.PATCH);
        assertThat(VersionComparator.classify("2.0.0", "2.0.1")).isEqualTo(VersionComparator.UpdateType.PATCH);
    }

    @Test
    void classifyMinor() {
        assertThat(VersionComparator.classify("1.2.3", "1.3.0")).isEqualTo(VersionComparator.UpdateType.MINOR);
        assertThat(VersionComparator.classify("1.0.0", "1.1.0")).isEqualTo(VersionComparator.UpdateType.MINOR);
    }

    @Test
    void classifyMajor() {
        assertThat(VersionComparator.classify("1.2.3", "2.0.0")).isEqualTo(VersionComparator.UpdateType.MAJOR);
        assertThat(VersionComparator.classify("1.0.0", "3.0.0")).isEqualTo(VersionComparator.UpdateType.MAJOR);
    }

    @Test
    void classifyWithQualifiers() {
        assertThat(VersionComparator.classify("1.2.3-SNAPSHOT", "1.2.4")).isEqualTo(VersionComparator.UpdateType.PATCH);
        assertThat(VersionComparator.classify("1.2.3", "2.0.0-beta1")).isEqualTo(VersionComparator.UpdateType.MAJOR);
    }

    @Test
    void isPreviewDetectsAlpha() {
        assertThat(VersionComparator.isPreview("1.0.0-alpha1")).isTrue();
        assertThat(VersionComparator.isPreview("1.0.0-ALPHA")).isTrue();
    }

    @Test
    void isPreviewDetectsBeta() {
        assertThat(VersionComparator.isPreview("1.0.0-beta2")).isTrue();
    }

    @Test
    void isPreviewDetectsRC() {
        assertThat(VersionComparator.isPreview("1.0.0-rc1")).isTrue();
        assertThat(VersionComparator.isPreview("1.0.0.RC2")).isTrue();
    }

    @Test
    void isPreviewDetectsSnapshot() {
        assertThat(VersionComparator.isPreview("1.0.0-SNAPSHOT")).isTrue();
    }

    @Test
    void isPreviewDetectsMilestone() {
        assertThat(VersionComparator.isPreview("1.0.0-M1")).isTrue();
    }

    @Test
    void isPreviewReturnsFalseForRelease() {
        assertThat(VersionComparator.isPreview("1.0.0")).isFalse();
        assertThat(VersionComparator.isPreview("2.3.4")).isFalse();
        assertThat(VersionComparator.isPreview("33.0.0-jre")).isFalse();
    }

    @Test
    void isNewerComparesVersions() {
        assertThat(VersionComparator.isNewer("1.0.0", "2.0.0")).isTrue();
        assertThat(VersionComparator.isNewer("1.0.0", "1.0.1")).isTrue();
        assertThat(VersionComparator.isNewer("1.0.0", "1.1.0")).isTrue();
    }

    @Test
    void isNewerReturnsFalseForOlder() {
        assertThat(VersionComparator.isNewer("2.0.0", "1.0.0")).isFalse();
        assertThat(VersionComparator.isNewer("1.0.0", "1.0.0")).isFalse();
    }

    @Test
    void isNewerHandlesQualifiers() {
        assertThat(VersionComparator.isNewer("1.0.0-SNAPSHOT", "1.0.0")).isFalse();
    }

    @Test
    void classifyTwoPartVersions() {
        assertThat(VersionComparator.classify("1.0", "1.1")).isEqualTo(VersionComparator.UpdateType.MINOR);
        assertThat(VersionComparator.classify("1.0", "2.0")).isEqualTo(VersionComparator.UpdateType.MAJOR);
    }
}
