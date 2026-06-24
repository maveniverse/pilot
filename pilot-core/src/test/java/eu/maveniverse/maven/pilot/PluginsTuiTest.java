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

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginsTuiTest {

    @TempDir
    Path tempDir;

    private Path subdir(String name) throws IOException {
        return Files.createDirectories(tempDir.resolve(name));
    }

    private PilotProject createProject(
            String groupId,
            String artifactId,
            String version,
            Path basedir,
            List<PilotProject.Plugin> plugins,
            List<PilotProject.Plugin> managedPlugins) {
        PilotProject pp = new PilotProject(
                groupId,
                artifactId,
                version,
                "jar",
                basedir,
                basedir.resolve("pom.xml"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new Properties(),
                null,
                null);
        pp.setPlugins(plugins);
        pp.setManagedPlugins(managedPlugins);
        return pp;
    }

    private PluginsTui createTui(PilotProject project, List<PilotProject> allProjects) {
        return new PluginsTui(project, allProjects, (g, a) -> List.of());
    }

    private void renderFrame(PluginsTui tui) {
        String output = TuiTestHelper.render(tui::renderStandalone);
        assertThat(output).isNotEmpty();
    }

    @Test
    void constructionWithEmptyPlugins() throws IOException {
        Path dir = subdir("empty");
        PilotProject project = createProject("com.example", "app", "1.0", dir, List.of(), List.of());
        PluginsTui tui = createTui(project, List.of(project));

        assertThat(tui.toolName()).isEqualTo("Plugins");
        assertThat(tui.status()).contains("Loading");
        assertThat(tui.subViewCount()).isEqualTo(3);
        assertThat(tui.activeSubView()).isEqualTo(0);
    }

    @Test
    void constructionWithPlugins() throws IOException {
        Path dir = subdir("with-plugins");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0"),
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-surefire-plugin", "3.2.5")),
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-jar-plugin", "3.3.0")));
        PluginsTui tui = createTui(project, List.of(project));

        assertThat(tui.subViewNames().get(0)).contains("2");
        assertThat(tui.subViewNames().get(1)).contains("1");
    }

    @Test
    void constructionAcrossModules() throws IOException {
        Path dir1 = subdir("mod1");
        Path dir2 = subdir("mod2");
        PilotProject p1 = createProject(
                "com.example",
                "mod1",
                "1.0",
                dir1,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PilotProject p2 = createProject(
                "com.example",
                "mod2",
                "1.0",
                dir2,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(p1, List.of(p1, p2));

        assertThat(tui.subViewNames().get(0)).contains("1");
    }

    @Test
    void switchViewWithKeyEvent() throws IOException {
        Path dir = subdir("switch");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));

        assertThat(tui.activeSubView()).isEqualTo(0);

        tui.setActiveSubView(1);
        assertThat(tui.activeSubView()).isEqualTo(1);

        tui.setActiveSubView(2);
        assertThat(tui.activeSubView()).isEqualTo(2);

        tui.setActiveSubView(0);
        assertThat(tui.activeSubView()).isEqualTo(0);
    }

    @Test
    void navigationKeysHandled() throws IOException {
        Path dir = subdir("nav");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0"),
                        new PilotProject.Plugin("org.apache.maven.plugins", "maven-surefire-plugin", "3.2.5")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));

        assertThat(tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.DOWN))).isTrue();
        assertThat(tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.UP))).isTrue();
        assertThat(tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.PAGE_DOWN))).isTrue();
        assertThat(tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.PAGE_UP))).isTrue();
    }

    @Test
    void renderPluginsView() throws IOException {
        Path dir = subdir("render-plugins");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));
        renderFrame(tui);
    }

    @Test
    void renderManagedView() throws IOException {
        Path dir = subdir("render-managed");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(),
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")));
        PluginsTui tui = createTui(project, List.of(project));
        tui.setActiveSubView(1);
        renderFrame(tui);
    }

    @Test
    void renderUpdatesViewEmpty() throws IOException {
        Path dir = subdir("render-updates");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));
        tui.loading = false;
        tui.setActiveSubView(2);
        renderFrame(tui);
    }

    @Test
    void renderEmptyPluginsView() throws IOException {
        Path dir = subdir("render-empty");
        PilotProject project = createProject("com.example", "app", "1.0", dir, List.of(), List.of());
        PluginsTui tui = createTui(project, List.of(project));
        tui.loading = false;
        renderFrame(tui);
    }

    @Test
    void keyHintsIncludesExpectedKeys() throws IOException {
        Path dir = subdir("keyhints");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));

        assertThat(tui.keyHints()).isNotEmpty();
    }

    @Test
    void helpSectionsNotEmpty() throws IOException {
        Path dir = subdir("help");
        PilotProject project = createProject("com.example", "app", "1.0", dir, List.of(), List.of());
        PluginsTui tui = createTui(project, List.of(project));

        assertThat(tui.helpSections()).isNotEmpty();
        assertThat(tui.helpSections().get(0).title()).isEqualTo("Plugin Browser");
    }

    @Test
    void handleKeyEventForNavigation() throws IOException {
        Path dir = subdir("nav-key");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));

        assertThat(tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.HOME))).isTrue();
        assertThat(tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.END))).isTrue();
    }

    @Test
    void statusReportsLoadingState() throws IOException {
        Path dir = subdir("status-loading");
        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());
        PluginsTui tui = createTui(project, List.of(project));

        assertThat(tui.loading).isTrue();
        assertThat(tui.status()).contains("Loading");
    }

    @Test
    void pluginEntryHasUpdate() {
        PluginsTui.PluginEntry entry = new PluginsTui.PluginEntry("g", "a", "1.0", false);
        assertThat(entry.hasUpdate()).isFalse();

        entry.newestVersion = "2.0";
        assertThat(entry.hasUpdate()).isTrue();
    }

    @Test
    void pluginEntryGaAndGav() {
        PluginsTui.PluginEntry entry = new PluginsTui.PluginEntry("org.example", "my-plugin", "1.0", false);
        assertThat(entry.ga()).isEqualTo("org.example:my-plugin");
        assertThat(entry.gav()).isEqualTo("org.example:my-plugin:1.0");
    }

    @Test
    void pluginEntryHasUpdateFalseForSameVersion() {
        PluginsTui.PluginEntry entry = new PluginsTui.PluginEntry("g", "a", "1.0", false);
        entry.newestVersion = "1.0";
        assertThat(entry.hasUpdate()).isFalse();
    }

    @Test
    void pluginEntryHasUpdateFalseForEmpty() {
        PluginsTui.PluginEntry entry = new PluginsTui.PluginEntry("g", "a", "1.0", false);
        entry.newestVersion = "";
        assertThat(entry.hasUpdate()).isFalse();
    }

    @Test
    void pluginRecordGaAndGav() {
        PilotProject.Plugin plugin = new PilotProject.Plugin("org.example", "artifact", "1.0");
        assertThat(plugin.ga()).isEqualTo("org.example:artifact");
        assertThat(plugin.gav()).isEqualTo("org.example:artifact:1.0");
    }

    @Test
    void pluginRecordWithDependenciesAndExclusions() {
        PilotProject.Plugin plugin = new PilotProject.Plugin(
                "org.example",
                "artifact",
                "1.0",
                List.of(new PilotProject.Dep("g", "a", "1.0")),
                List.of(new PilotProject.Excl("e", "x")));
        assertThat(plugin.dependencies()).hasSize(1);
        assertThat(plugin.exclusions()).hasSize(1);
    }

    @Test
    void constructionDeduplicatesPluginsByGa() throws IOException {
        Path dir1 = subdir("dedup1");
        Path dir2 = subdir("dedup2");
        PilotProject p1 = createProject(
                "com.example",
                "mod1",
                "1.0",
                dir1,
                List.of(new PilotProject.Plugin("org.apache", "plugin-a", "1.0")),
                List.of());
        PilotProject p2 = createProject(
                "com.example",
                "mod2",
                "1.0",
                dir2,
                List.of(new PilotProject.Plugin("org.apache", "plugin-a", "1.0")),
                List.of());

        PluginsTui tui = createTui(p1, List.of(p1, p2));
        assertThat(tui.subViewNames().get(0)).contains("1");
    }
}
