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

import dev.tamboui.layout.Size;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.pilot.TuiTestRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for UpdatesTui async tree-impact computation paths.
 * Uses TuiTestRunner so that runner.runOnRenderThread callbacks are processed
 * by the render loop (required for thenAccept / exceptionally branches).
 */
class UpdatesTuiAsyncTest {

    private static final int WIDTH = 120;
    private static final int HEIGHT = 30;

    @TempDir
    Path tempDir;

    private Path subdir(String name) throws IOException {
        return Files.createDirectories(tempDir.resolve(name));
    }

    private PilotProject createProject(String groupId, String artifactId, String version, Path basedir) {
        return new PilotProject(
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
    }

    /** Poll until status no longer contains "Computing" or 5-second timeout. */
    @SuppressWarnings("java:S2925") // Thread.sleep intentional in test helper
    private void waitForImpact(UpdatesTui tui) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        // First wait for status to become "Computing..." (max 2s)
        long computingDeadline = System.currentTimeMillis() + 2000;
        while (!tui.status().contains("Computing") && System.currentTimeMillis() < computingDeadline) {
            Thread.sleep(20);
        }
        // Then wait for it to complete (max 5s from start)
        while (tui.status().contains("Computing") && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    // ── showTreeImpact — no-change result (entries empty / all SAME) ──────────

    @Test
    void showTreeImpactNoTransitiveChanges() throws Exception {
        Path dir = subdir("no-changes");
        PilotProject project = createProject("com.example", "app", "1.0", dir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));

        // Manually inject an ungrouped dep with a newest version so 't' can resolve a target
        ReactorCollector.AggregatedDependency dep = new ReactorCollector.AggregatedDependency("com.example", "lib");
        dep.primaryVersion = "1.0";
        dep.newestVersion = "2.0";
        dep.updateType = VersionComparator.UpdateType.MAJOR;
        result.ungroupedDependencies.add(dep);
        result.allDependencies.add(dep);

        ReactorModel model = ReactorModel.build(List.of(project));

        // Resolver returns empty list → "No transitive changes"
        UpdatesTui.TreeImpactResolver resolver = (g, a, ov, nv) -> List.of();
        UpdatesTui tui = new UpdatesTui(result, model, "com.example:app:1.0", (g, a) -> List.of(), resolver, null);

        tui.loading = false;
        tui.buildDisplayRows(); // also calls tableState.select(0) when rows non-empty

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            tui.setRunner(testRunner.runner());

            // Press 't' to trigger tree impact computation (row 0 already selected)
            pilot.press('t');
            pilot.pause(Duration.ofMillis(200));
            waitForImpact(tui);

            assertThat(tui.status()).contains("No transitive changes");
            pilot.press('q');
        }
        tui.close();
    }

    // ── showTreeImpact — entries with actual diffs (overlay opens) ────────────

    @Test
    void showTreeImpactWithDiffOpensOverlay() throws Exception {
        Path dir = subdir("with-diff");
        PilotProject project = createProject("com.example", "app", "1.0", dir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));

        ReactorCollector.AggregatedDependency dep = new ReactorCollector.AggregatedDependency("com.example", "lib");
        dep.primaryVersion = "1.0";
        dep.newestVersion = "2.0";
        dep.updateType = VersionComparator.UpdateType.MAJOR;
        result.ungroupedDependencies.add(dep);
        result.allDependencies.add(dep);

        ReactorModel model = ReactorModel.build(List.of(project));

        // Resolver returns one LEFT entry → diff exists → overlay should open
        UpdatesTui.TreeImpactResolver resolver = (g, a, ov, nv) ->
                List.of(new TreeDiff.DiffEntry("com.example:transitive-lib", "1.0", "compile", 0, TreeDiff.Side.LEFT));
        UpdatesTui tui = new UpdatesTui(result, model, "com.example:app:1.0", (g, a) -> List.of(), resolver, null);

        tui.loading = false;
        tui.buildDisplayRows(); // also calls tableState.select(0) when rows non-empty

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            tui.setRunner(testRunner.runner());

            pilot.press('t');
            pilot.pause(Duration.ofMillis(200));
            waitForImpact(tui);

            assertThat(tui.status()).contains("Tree impact:");
            pilot.press('q');
        }
        tui.close();
    }

    // ── showTreeImpact — resolver throws (exceptionally path) ────────────────

    @Test
    void showTreeImpactResolverFailureSetsErrorStatus() throws Exception {
        Path dir = subdir("resolver-fail");
        PilotProject project = createProject("com.example", "app", "1.0", dir);
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));

        ReactorCollector.AggregatedDependency dep = new ReactorCollector.AggregatedDependency("com.example", "lib");
        dep.primaryVersion = "1.0";
        dep.newestVersion = "2.0";
        dep.updateType = VersionComparator.UpdateType.MAJOR;
        result.ungroupedDependencies.add(dep);
        result.allDependencies.add(dep);

        ReactorModel model = ReactorModel.build(List.of(project));

        // Resolver throws → .exceptionally path sets "Tree impact failed for..."
        UpdatesTui.TreeImpactResolver resolver = (g, a, ov, nv) -> {
            throw new RuntimeException("network timeout");
        };
        UpdatesTui tui = new UpdatesTui(result, model, "com.example:app:1.0", (g, a) -> List.of(), resolver, null);

        tui.loading = false;
        tui.buildDisplayRows(); // also calls tableState.select(0) when rows non-empty

        try (var testRunner = TuiTestRunner.runTest(tui::handleEvent, tui::renderStandalone, new Size(WIDTH, HEIGHT))) {
            Pilot pilot = testRunner.pilot();
            tui.setRunner(testRunner.runner());

            pilot.press('t');
            pilot.pause(Duration.ofMillis(500));
            waitForImpact(tui);

            assertThat(tui.status()).contains("Tree impact failed for");
            pilot.press('q');
        }
        tui.close();
    }
}
