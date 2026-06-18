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
package eu.maveniverse.maven.pilot.mvn3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;

class DependenciesMojoTest {

    @Test
    void executeRejectsInvalidAction() throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "invalid");

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Invalid action 'invalid'");
    }

    @Test
    void defaultActionIsTui() throws Exception {
        var mojo = new DependenciesMojo(null);
        assertThat(MojoTestHelper.getField(mojo, "action")).isEqualTo("tui");
    }

    @Test
    void executeAcceptsTuiAction() throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "tui");
        assertThat(MojoTestHelper.getField(mojo, "action")).isEqualTo("tui");
    }

    @Test
    void executeAcceptsReportAction() throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "report");
        assertThat(MojoTestHelper.getField(mojo, "action")).isEqualTo("report");
    }

    @Test
    void executeAcceptsCheckAction() throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "check");
        assertThat(MojoTestHelper.getField(mojo, "action")).isEqualTo("check");
    }

    @Test
    void executeAcceptsFixAction() throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "action", "fix");
        assertThat(MojoTestHelper.getField(mojo, "action")).isEqualTo("fix");
    }

    // --- buildIgnoreSet ---

    @Test
    void buildIgnoreSetNull() {
        assertThat(DependenciesMojo.buildIgnoreSet(null)).isEmpty();
    }

    @Test
    void buildIgnoreSetEmpty() {
        assertThat(DependenciesMojo.buildIgnoreSet(List.of())).isEmpty();
    }

    @Test
    void buildIgnoreSetPopulated() {
        Set<String> result = DependenciesMojo.buildIgnoreSet(List.of("org.slf4j:slf4j-api", "com.example:*"));
        assertThat(result).containsExactlyInAnyOrder("org.slf4j:slf4j-api", "com.example:*");
    }

    // --- buildAnalyzer ---

    @Test
    void buildAnalyzerDefaults() {
        var mojo = new DependenciesMojo(null);
        var analyzer = mojo.buildAnalyzer();
        assertThat(analyzer).isNotNull();
    }

    @Test
    void buildAnalyzerWithAllowlists() throws Exception {
        var mojo = new DependenciesMojo(null);
        MojoTestHelper.setField(mojo, "runtimeArtifacts", List.of("org.postgresql:postgresql"));
        MojoTestHelper.setField(mojo, "annotationOnlyArtifacts", List.of("org.projectlombok:lombok"));
        MojoTestHelper.setField(
                mojo, "reflectionLoadedClasses", Map.of("org.postgresql:postgresql", "org.postgresql.Driver"));

        var analyzer = mojo.buildAnalyzer();
        assertThat(analyzer).isNotNull();
    }
}
