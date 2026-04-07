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

class AnalyzeTuiTest {

    @Test
    void depEntryGaWithoutClassifier() {
        var dep = new AnalyzeTui.DepEntry("org.slf4j", "slf4j-api", "", "2.0.9", "compile", true);
        assertThat(dep.ga()).isEqualTo("org.slf4j:slf4j-api");
    }

    @Test
    void depEntryGaWithClassifier() {
        var dep = new AnalyzeTui.DepEntry("dev.tamboui", "tamboui-core", "test-fixtures", "0.1.0", "test", true);
        assertThat(dep.ga()).isEqualTo("dev.tamboui:tamboui-core:test-fixtures");
    }

    @Test
    void depEntryGavWithoutClassifier() {
        var dep = new AnalyzeTui.DepEntry("org.slf4j", "slf4j-api", "", "2.0.9", "compile", true);
        assertThat(dep.gav()).isEqualTo("org.slf4j:slf4j-api:2.0.9");
    }

    @Test
    void depEntryGavWithClassifier() {
        var dep = new AnalyzeTui.DepEntry("dev.tamboui", "tamboui-core", "test-fixtures", "0.1.0", "test", true);
        assertThat(dep.gav()).isEqualTo("dev.tamboui:tamboui-core:test-fixtures:0.1.0");
    }

    @Test
    void depEntryNullClassifierDefaultsToEmpty() {
        var dep = new AnalyzeTui.DepEntry("g", "a", null, "1.0", "compile", true);
        assertThat(dep.classifier).isEmpty();
        assertThat(dep.ga()).isEqualTo("g:a");
    }

    @Test
    void depEntryDefaults() {
        var dep = new AnalyzeTui.DepEntry("g", "a", null, null, null, false);
        assertThat(dep.version).isEmpty();
        assertThat(dep.scope).isEqualTo("compile");
        assertThat(dep.classifier).isEmpty();
    }
}
