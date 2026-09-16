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

import eu.maveniverse.maven.pilot.PilotProject;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;

class PluginsMojoTest {

    private static PilotProject makeProject(String artifactId) {
        Path base = Path.of("/tmp/test-" + artifactId);
        return new PilotProject(
                "com.example",
                artifactId,
                "1.0",
                "jar",
                base,
                base.resolve("pom.xml"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new Properties(),
                null,
                null);
    }

    @Test
    void executeRejectsInvalidAction() throws Exception {
        var mojo = new PluginsMojo(null);
        mojo.action = "invalid";

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Invalid action 'invalid'");
    }

    @Test
    void defaultActionIsReport() {
        var mojo = new PluginsMojo(null);
        assertThat(mojo.action).isEqualTo("report");
    }

    @Test
    void reportDoesNotThrowWhenUpdatesExist() throws Exception {
        var mojo = new PluginsMojo(null);
        mojo.action = "report";

        PilotProject p = makeProject("app");
        p.setPlugins(List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.10.0")));

        // Resolver returns a newer version — report mode exits 0 regardless
        mojo.executeNonInteractive(List.of(p), (g, a) -> List.of("3.13.0", "3.10.0"), "com.example:app:1.0");
    }

    @Test
    void reportDoesNotThrowWhenNoUpdates() throws Exception {
        var mojo = new PluginsMojo(null);
        mojo.action = "report";

        PilotProject p = makeProject("app");
        p.setPlugins(List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.13.0")));

        mojo.executeNonInteractive(List.of(p), (g, a) -> List.of("3.13.0"), "com.example:app:1.0");
    }

    @Test
    void checkFailsWhenUpdatesExist() {
        var mojo = new PluginsMojo(null);
        mojo.action = "check";

        PilotProject p = makeProject("app");
        p.setPlugins(List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.10.0")));

        assertThatThrownBy(() ->
                        mojo.executeNonInteractive(List.of(p), (g, a) -> List.of("3.13.0"), "com.example:app:1.0"))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("plugin update(s) available");
    }

    @Test
    void checkPassesWhenNoUpdates() throws Exception {
        var mojo = new PluginsMojo(null);
        mojo.action = "check";

        PilotProject p = makeProject("app");
        p.setPlugins(List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.13.0")));

        mojo.executeNonInteractive(List.of(p), (g, a) -> List.of("3.13.0"), "com.example:app:1.0");
    }

    @Test
    void managedPluginsAreIncluded() {
        var mojo = new PluginsMojo(null);
        mojo.action = "check";

        PilotProject p = makeProject("app");
        p.setManagedPlugins(
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-surefire-plugin", "3.0.0")));

        assertThatThrownBy(
                        () -> mojo.executeNonInteractive(List.of(p), (g, a) -> List.of("3.5.0"), "com.example:app:1.0"))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("plugin update(s) available");
    }

    @Test
    void declaredPluginWinsOverManagedForSameGa() throws Exception {
        var mojo = new PluginsMojo(null);
        mojo.action = "report";

        // Same GA: declared at 3.12.0, managed at 3.10.0 — should appear once at the declared version
        PilotProject p = makeProject("app");
        p.setPlugins(List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.12.0")));
        p.setManagedPlugins(
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.10.0")));

        // Resolver: nothing newer than 3.12.0 → no update, no failure
        mojo.executeNonInteractive(List.of(p), (g, a) -> List.of("3.12.0"), "com.example:app:1.0");
    }

    @Test
    void previewVersionsAreIgnored() throws Exception {
        var mojo = new PluginsMojo(null);
        mojo.action = "check";

        PilotProject p = makeProject("app");
        p.setPlugins(List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.13.0")));

        // Only a preview release is "newer" — must be filtered out
        mojo.executeNonInteractive(List.of(p), (g, a) -> List.of("4.0.0-alpha-1", "3.13.0"), "com.example:app:1.0");
    }

    @Test
    void multiModuleReactorAggregatesPlugins() {
        var mojo = new PluginsMojo(null);
        mojo.action = "check";

        PilotProject p1 = makeProject("module-a");
        p1.setPlugins(List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.10.0")));

        PilotProject p2 = makeProject("module-b");
        p2.setPlugins(List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-surefire-plugin", "3.0.0")));

        assertThatThrownBy(() -> mojo.executeNonInteractive(
                        List.of(p1, p2), (g, a) -> List.of("3.13.0"), "com.example:parent:1.0"))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("plugin update(s) available");
    }
}
