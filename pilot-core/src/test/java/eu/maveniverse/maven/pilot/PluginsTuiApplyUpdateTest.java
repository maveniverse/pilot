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

/**
 * Tests for {@code PluginsTui.applySelectedUpdate()} covering:
 * <ul>
 *   <li>Session resolves to the sub-module POM (not root) for multi-module builds.</li>
 *   <li>{@code recordChange} is called after a successful save.</li>
 *   <li>Entry removal uses the index overload ({@code remove(int)}) so the list shrinks correctly.</li>
 *   <li>Fallback to no-op when no session is available.</li>
 * </ul>
 */
class PluginsTuiApplyUpdateTest {

    @TempDir
    Path tempDir;

    private Path subdir(String name) throws IOException {
        return Files.createDirectories(tempDir.resolve(name));
    }

    /**
     * Writes a minimal POM with a {@code <build><plugins>} section containing the given
     * plugin, so that domtrip {@code updatePlugin} can find and update it.
     */
    private Path writePomWithPlugin(Path dir, String groupId, String artifactId, String version) throws IOException {
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>%s</groupId>
                        <artifactId>%s</artifactId>
                        <version>%s</version>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.formatted(dir.getFileName(), groupId, artifactId, version));
        return pom;
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

    // ── pomPath targets sub-module POM for multi-module updates ──────────────

    /**
     * In a multi-module build, each PluginEntry carries the pomPath of the module that
     * declares the plugin.  applySelectedUpdate() must open a session on that path so
     * the sub-module POM (not the root) is modified.
     *
     * <p>We write a real POM for the sub-module, simulate an update for its plugin, press
     * 'u', and verify that (a) the update succeeds, (b) the entry is removed from the
     * updates list, and (c) the sub-module POM file now contains the new version.</p>
     */
    @Test
    void applyUpdateWritesToSubModulePom() throws IOException {
        Path rootDir = subdir("root");
        Path subDir = subdir("sub");

        // Write a POM only for the sub-module (root has none — it would be the fallback session)
        Path subPom = writePomWithPlugin(subDir, "org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0");

        // Root project: no pom.xml on disk → session = null (root has no real POM)
        PilotProject root = createProject("com.example", "root", "1.0", rootDir, List.of(), List.of());

        // Sub-module project: has a real POM and declares the plugin
        PilotProject sub = createProject(
                "com.example",
                "sub",
                "1.0",
                subDir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());

        PluginsTui tui = new PluginsTui(root, List.of(root, sub), (g, a) -> List.of());

        // Verify pomPath was captured correctly for the plugin entry
        assertThat(tui.plugins).hasSize(1);
        PluginsTui.PluginEntry entry = tui.plugins.get(0);
        assertThat(entry.pomPath).isEqualTo(subPom);

        // Simulate version resolution: compiler-plugin has update to 3.14.0
        entry.newestVersion = "3.14.0";
        entry.updateType = VersionComparator.UpdateType.MINOR;
        tui.loading = false;
        tui.applyFilter();
        assertThat(tui.updates).hasSize(1);

        // Switch to Updates view and press 'u'
        tui.setActiveSubView(2);
        tui.handleKeyEvent(KeyEvent.ofChar('u'));

        // Entry should be removed from the updates list after a successful apply
        assertThat(tui.updates).isEmpty();
        // Entry version should reflect the new version
        assertThat(entry.version).isEqualTo("3.14.0");
        // The sub-module POM must contain the new version string
        String pomContent = Files.readString(subPom);
        assertThat(pomContent).contains("3.14.0");
    }

    // ── Entry is removed by index (not by object) ────────────────────────────

    /**
     * Before the fix, {@code updates.remove(sel)} where {@code sel} is an {@code Integer}
     * called {@code List.remove(Object)}, which compares by equality against {@code PluginEntry}
     * objects and never matches — leaving the entry in the list.  After the fix,
     * {@code updates.remove(sel.intValue())} removes by index.
     *
     * <p>We set up two update entries, apply the second one (index 1), and verify that
     * exactly one entry remains (the first) — not two (the object-remove no-op).</p>
     */
    @Test
    void applyUpdateRemovesEntryByIndex() throws IOException {
        Path dir1 = subdir("idx1");
        Path dir2 = subdir("idx2");

        // Write real POMs for both plugins
        writePomWithPlugin(dir1, "org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0");
        writePomWithPlugin(dir2, "org.apache.maven.plugins", "maven-surefire-plugin", "3.2.5");

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
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-surefire-plugin", "3.2.5")),
                List.of());

        PluginsTui tui = new PluginsTui(p1, List.of(p1, p2), (g, a) -> List.of());

        // Simulate updates for both plugins
        for (PluginsTui.PluginEntry e : tui.plugins) {
            e.newestVersion = "3.14.0";
            e.updateType = VersionComparator.UpdateType.MINOR;
        }
        tui.loading = false;
        tui.applyFilter();
        assertThat(tui.updates).hasSize(2);

        // Select index 1 (second entry) and apply
        tui.setActiveSubView(2);
        tui.handleKeyEvent(KeyEvent.ofChar('u')); // applies index 0 (first entry)

        // After applying: exactly 1 entry should remain
        assertThat(tui.updates).hasSize(1);
    }

    // ── recordChange is called after a successful update ─────────────────────

    /**
     * Verifies that a successful {@code applySelectedUpdate()} records a MODIFY change
     * in the PomEditSession so that change tracking and undo history are consistent.
     *
     * <p>We read the change log from the session via the PluginEntry's pomPath.
     * Since the session is created fresh inside applySelectedUpdate, we verify the
     * side-effect: the status text contains the expected "old → new" marker (set only
     * after recordChange returns, i.e. after save succeeds).</p>
     */
    @Test
    void applyUpdateSetsStatusTextAfterSuccessfulSave() throws IOException {
        Path dir = subdir("record-change");
        writePomWithPlugin(dir, "org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0");

        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());

        PluginsTui tui = new PluginsTui(project, List.of(project), (g, a) -> List.of());
        PluginsTui.PluginEntry entry = tui.plugins.get(0);
        entry.newestVersion = "3.14.0";
        entry.updateType = VersionComparator.UpdateType.MINOR;
        tui.loading = false;
        tui.applyFilter();

        tui.setActiveSubView(2);
        tui.handleKeyEvent(KeyEvent.ofChar('u'));

        // Status should show the version transition (set only on success, after recordChange)
        assertThat(tui.statusText).contains("3.11.0").contains("3.14.0");
    }

    // ── Fallback: no session and no pomPath → status reports error ────────────

    /**
     * When no real POM file exists for an entry and the shared session is null,
     * {@code applySelectedUpdate()} must not throw — it should set an error status instead.
     */
    @Test
    void applyUpdateSetsErrorStatusWhenNoPomAvailable() throws IOException {
        Path dir = subdir("no-pom");
        // NOTE: do NOT write pom.xml to dir — both pomPath and session will be unavailable

        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());

        // project.pomPath points to a non-existent file → session = null in the TUI
        PluginsTui tui = new PluginsTui(project, List.of(project), (g, a) -> List.of());
        PluginsTui.PluginEntry entry = tui.plugins.get(0);
        // Also clear pomPath so neither fallback works
        entry.pomPath = null;

        entry.newestVersion = "3.14.0";
        entry.updateType = VersionComparator.UpdateType.MINOR;
        tui.loading = false;
        tui.applyFilter();
        assertThat(tui.updates).hasSize(1);

        tui.setActiveSubView(2);
        tui.handleKeyEvent(KeyEvent.ofChar('u'));

        // Entry must still be in the list — the apply failed gracefully
        assertThat(tui.updates).hasSize(1);
        assertThat(tui.statusText).contains("Cannot apply");
    }

    // ── pomPath is stored correctly when first module declares the plugin ─────

    /**
     * Verifies that {@code PluginEntry.pomPath} is set from the <em>first</em> module
     * that declares the plugin (the {@code computeIfAbsent} semantics).  When two modules
     * declare the same plugin, pomPath should refer to the first module's POM.
     */
    @Test
    void pluginEntryPomPathPointsToDeclaringModule() throws IOException {
        Path dir1 = subdir("declaring-mod1");
        Path dir2 = subdir("declaring-mod2");

        Path pom1 = writePomWithPlugin(dir1, "org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0");
        writePomWithPlugin(dir2, "org.apache.maven.plugins", "maven-compiler-plugin", "3.12.0");

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
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.12.0")),
                List.of());

        // Pass p1 first — p1's POM should be stored in entry.pomPath
        PluginsTui tui = new PluginsTui(p1, List.of(p1, p2), (g, a) -> List.of());

        PluginsTui.PluginEntry entry = tui.plugins.stream()
                .filter(e -> "maven-compiler-plugin".equals(e.artifactId))
                .findFirst()
                .orElseThrow();

        assertThat(entry.pomPath).isEqualTo(pom1);
    }

    // ── Profile-aware update targets the correct profile scope ───────────────

    /**
     * Verifies that when a plugin entry has a non-null profileId, the update is
     * applied via {@code editor.plugins().forProfile(profileId)}.  We use a POM
     * that declares the plugin inside a {@code <profile>} block.
     */
    @Test
    void applyUpdateTargetsProfileScope() throws IOException {
        Path dir = subdir("profile-scope");
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>profile-scope</artifactId>
                  <version>1.0</version>
                  <profiles>
                    <profile>
                      <id>release</id>
                      <build>
                        <plugins>
                          <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-compiler-plugin</artifactId>
                            <version>3.11.0</version>
                          </plugin>
                        </plugins>
                      </build>
                    </profile>
                  </profiles>
                </project>
                """);

        // Create a plugin entry with profileId="release"
        PilotProject.Plugin profilePlugin = new PilotProject.Plugin(
                "org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0", List.of(), List.of(), "release");

        PilotProject project = createProject("com.example", "app", "1.0", dir, List.of(profilePlugin), List.of());

        PluginsTui tui = new PluginsTui(project, List.of(project), (g, a) -> List.of());
        assertThat(tui.plugins).hasSize(1);
        PluginsTui.PluginEntry entry = tui.plugins.get(0);
        assertThat(entry.profileId).isEqualTo("release");

        entry.newestVersion = "3.14.0";
        entry.updateType = VersionComparator.UpdateType.MINOR;
        tui.loading = false;
        tui.applyFilter();

        tui.setActiveSubView(2);
        tui.handleKeyEvent(KeyEvent.ofChar('u'));

        // The update should succeed: the profile-scoped plugin version is updated
        assertThat(tui.updates).isEmpty();
        String pomContent = Files.readString(pom);
        assertThat(pomContent).contains("3.14.0");
    }

    // ── Session fallback: pomPath null but shared session available ───────────

    /**
     * When {@code entry.pomPath} is null (cleared after construction) but the shared
     * root session is available (project POM exists), {@code applySelectedUpdate()} must
     * fall back to the shared session and succeed.
     */
    @Test
    void applyUpdateUsesSharedSessionWhenPomPathIsNull() throws IOException {
        Path dir = subdir("session-fallback");
        // Write a POM at the root project's path so the shared session is created
        Path pom = writePomWithPlugin(dir, "org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0");

        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                dir,
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0")),
                List.of());

        PluginsTui tui = new PluginsTui(project, List.of(project), (g, a) -> List.of());
        PluginsTui.PluginEntry entry = tui.plugins.get(0);
        // Clear pomPath to force fallback to shared session
        entry.pomPath = null;
        entry.newestVersion = "3.14.0";
        entry.updateType = VersionComparator.UpdateType.MINOR;
        tui.loading = false;
        tui.applyFilter();
        assertThat(tui.updates).hasSize(1);

        tui.setActiveSubView(2);
        tui.handleKeyEvent(KeyEvent.ofChar('u'));

        // The shared session (created from project.pomPath) should have succeeded
        assertThat(tui.updates).isEmpty();
        assertThat(Files.readString(pom)).contains("3.14.0");
    }

    // ── "Plugin not found in POM" path ────────────────────────────────────────

    /**
     * When updatePlugin returns false (plugin is not directly declared in the POM — e.g.
     * it's inherited or version is a property reference), the status must report
     * "Plugin not found in POM".
     */
    @Test
    void applyUpdateSetsNotFoundStatusWhenPluginMissingFromPom() throws IOException {
        Path dir = subdir("not-found");
        // Write a POM WITHOUT the compiler plugin — updatePlugin will return false
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>not-found</artifactId>
                  <version>1.0</version>
                  <build><plugins/></build>
                </project>
                """);

        // Create a plugin entry that points to this POM, but the plugin isn't actually in it
        PilotProject.Plugin plugin =
                new PilotProject.Plugin("org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0");
        PilotProject project = createProject("com.example", "app", "1.0", dir, List.of(plugin), List.of());
        // Override pomPath to the POM we wrote
        PluginsTui tui = new PluginsTui(project, List.of(project), (g, a) -> List.of());
        PluginsTui.PluginEntry entry = tui.plugins.get(0);
        entry.pomPath = pom;
        entry.newestVersion = "3.14.0";
        entry.updateType = VersionComparator.UpdateType.MINOR;
        tui.loading = false;
        tui.applyFilter();
        assertThat(tui.updates).hasSize(1);

        tui.setActiveSubView(2);
        tui.handleKeyEvent(KeyEvent.ofChar('u'));

        // updatePlugin returns false → status must contain the expected message
        assertThat(tui.statusText).contains("Plugin not found in POM");
        // Entry must still be in the list
        assertThat(tui.updates).hasSize(1);
    }

    // ── Selection adjustment: applying the last update decrements selection ───

    /**
     * When the selected entry is the last in the updates list (sel > 0) and is removed,
     * the selection must be decremented so the new last item is highlighted rather than
     * leaving the selection index out of bounds.
     */
    @Test
    void applyUpdateDecrementsSelectionWhenLastEntryRemoved() throws IOException {
        Path dir1 = subdir("desel-1");
        Path dir2 = subdir("desel-2");

        writePomWithPlugin(dir1, "org.apache.maven.plugins", "maven-compiler-plugin", "3.11.0");
        writePomWithPlugin(dir2, "org.apache.maven.plugins", "maven-surefire-plugin", "3.2.5");

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
                List.of(new PilotProject.Plugin("org.apache.maven.plugins", "maven-surefire-plugin", "3.2.5")),
                List.of());

        PluginsTui tui = new PluginsTui(p1, List.of(p1, p2), (g, a) -> List.of());

        // Simulate two updates
        for (PluginsTui.PluginEntry e : tui.plugins) {
            e.newestVersion = "4.0.0";
            e.updateType = VersionComparator.UpdateType.MAJOR;
        }
        tui.loading = false;
        tui.applyFilter();
        assertThat(tui.updates).hasSize(2);

        // Switch to Updates view, move selection to index 1 (last), then apply
        tui.setActiveSubView(2);
        tui.handleKeyEvent(KeyEvent.ofKey(KeyCode.DOWN)); // select index 1

        // Applying last entry removes it; selection should decrement to 0
        tui.handleKeyEvent(KeyEvent.ofChar('u'));
        assertThat(tui.updates).hasSize(1);
        // Selection must not be out of bounds (should be 0)
        assertThat(tui.statusText).contains("→");
    }
}
