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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UpdatesTuiTest {

    @Test
    void versionResolverInterface() throws Exception {
        UpdatesTui.VersionResolver resolver = (g, a) -> List.of("2.0", "1.5", "1.0");
        List<String> versions = resolver.resolveVersions("com.example", "lib");
        assertThat(versions).containsExactly("2.0", "1.5", "1.0");
    }

    @Test
    void versionResolverThrowingException() {
        UpdatesTui.VersionResolver resolver = (g, a) -> {
            throw new Exception("repo unavailable");
        };
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> {
            resolver.resolveVersions("com.example", "lib");
        });
    }

    @Test
    void updateStatusIfDoneNoFailures() {
        var deps = new ArrayList<UpdatesTui.DependencyInfo>();
        deps.add(new UpdatesTui.DependencyInfo("com.example", "a", "1.0", "compile", "jar"));
        deps.add(new UpdatesTui.DependencyInfo("com.example", "b", "2.0", "compile", "jar"));
        deps.get(0).newestVersion = "1.1";
        deps.get(0).updateType = VersionComparator.UpdateType.PATCH;

        var tui = new UpdatesTui(deps, "/tmp/pom.xml", "com.example:app:1.0", (g, a) -> List.of());
        tui.loadedCount = 2;
        tui.updateStatusIfDone();

        assertThat(tui.loading).isFalse();
        assertThat(tui.status).contains("1 update(s) available");
        assertThat(tui.status).doesNotContain("failed");
    }

    @Test
    void updateStatusIfDoneWithFailures() {
        var deps = new ArrayList<UpdatesTui.DependencyInfo>();
        deps.add(new UpdatesTui.DependencyInfo("com.example", "a", "1.0", "compile", "jar"));
        deps.add(new UpdatesTui.DependencyInfo("com.example", "b", "2.0", "compile", "jar"));
        deps.add(new UpdatesTui.DependencyInfo("com.example", "c", "3.0", "compile", "jar"));

        var tui = new UpdatesTui(deps, "/tmp/pom.xml", "com.example:app:1.0", (g, a) -> List.of());
        tui.loadedCount = 3;
        tui.failedCount = 2;
        tui.updateStatusIfDone();

        assertThat(tui.loading).isFalse();
        assertThat(tui.status).contains("0 update(s) available");
        assertThat(tui.status).contains("2 lookup(s) failed");
    }

    @Test
    void updateStatusIfDoneNotYetComplete() {
        var deps = new ArrayList<UpdatesTui.DependencyInfo>();
        deps.add(new UpdatesTui.DependencyInfo("com.example", "a", "1.0", "compile", "jar"));
        deps.add(new UpdatesTui.DependencyInfo("com.example", "b", "2.0", "compile", "jar"));

        var tui = new UpdatesTui(deps, "/tmp/pom.xml", "com.example:app:1.0", (g, a) -> List.of());
        tui.loadedCount = 1; // not all loaded yet
        tui.updateStatusIfDone();

        assertThat(tui.loading).isTrue(); // still loading
        assertThat(tui.status).isEqualTo("Loading updates\u2026"); // unchanged
    }

    @Test
    void dependencyInfoGa() {
        var dep = new UpdatesTui.DependencyInfo("org.slf4j", "slf4j-api", "2.0.9", "compile", "jar");
        assertThat(dep.ga()).isEqualTo("org.slf4j:slf4j-api");
    }

    @Test
    void dependencyInfoHasUpdate() {
        var dep = new UpdatesTui.DependencyInfo("com.example", "lib", "1.0", "compile", "jar");
        assertThat(dep.hasUpdate()).isFalse();

        dep.newestVersion = "1.1";
        assertThat(dep.hasUpdate()).isTrue();

        dep.newestVersion = "1.0"; // same version
        assertThat(dep.hasUpdate()).isFalse();

        dep.newestVersion = ""; // empty
        assertThat(dep.hasUpdate()).isFalse();
    }

    @Test
    void dependencyInfoDefaults() {
        var dep = new UpdatesTui.DependencyInfo("g", "a", null, null, null);
        assertThat(dep.version).isEqualTo("");
        assertThat(dep.scope).isEqualTo("compile");
        assertThat(dep.type).isEqualTo("jar");
    }

    @Test
    void applyVersionResultFindsNewest() {
        var deps = new ArrayList<UpdatesTui.DependencyInfo>();
        var dep = new UpdatesTui.DependencyInfo("com.example", "lib", "1.0", "compile", "jar");
        deps.add(dep);

        var tui = new UpdatesTui(deps, "/tmp/pom.xml", "g:a:1.0", (g, a) -> List.of());
        tui.applyVersionResult(dep, List.of("2.0.0", "1.5.0", "1.0.0"));

        assertThat(dep.newestVersion).isEqualTo("2.0.0");
        assertThat(dep.updateType).isEqualTo(VersionComparator.UpdateType.MAJOR);
        assertThat(tui.loadedCount).isEqualTo(1);
    }

    @Test
    void applyVersionResultSkipsPreview() {
        var deps = new ArrayList<UpdatesTui.DependencyInfo>();
        var dep = new UpdatesTui.DependencyInfo("com.example", "lib", "1.0", "compile", "jar");
        deps.add(dep);

        var tui = new UpdatesTui(deps, "/tmp/pom.xml", "g:a:1.0", (g, a) -> List.of());
        tui.applyVersionResult(dep, List.of("2.0.0-beta1", "2.0.0-alpha1", "1.1.0"));

        assertThat(dep.newestVersion).isEqualTo("1.1.0");
        assertThat(dep.updateType).isEqualTo(VersionComparator.UpdateType.MINOR);
    }

    @Test
    void applyVersionResultNoUpdate() {
        var deps = new ArrayList<UpdatesTui.DependencyInfo>();
        var dep = new UpdatesTui.DependencyInfo("com.example", "lib", "2.0", "compile", "jar");
        deps.add(dep);

        var tui = new UpdatesTui(deps, "/tmp/pom.xml", "g:a:1.0", (g, a) -> List.of());
        tui.applyVersionResult(dep, List.of("1.5.0", "1.0.0"));

        assertThat(dep.newestVersion).isNull();
        assertThat(dep.updateType).isNull();
    }

    @Test
    void applyVersionResultEmptyVersions() {
        var deps = new ArrayList<UpdatesTui.DependencyInfo>();
        var dep = new UpdatesTui.DependencyInfo("com.example", "lib", "1.0", "compile", "jar");
        deps.add(dep);

        var tui = new UpdatesTui(deps, "/tmp/pom.xml", "g:a:1.0", (g, a) -> List.of());
        tui.applyVersionResult(dep, List.of());

        assertThat(dep.newestVersion).isNull();
        assertThat(tui.loadedCount).isEqualTo(1);
    }

    @Test
    void applyVersionResultWithEmptyCurrentVersion() {
        var deps = new ArrayList<UpdatesTui.DependencyInfo>();
        var dep = new UpdatesTui.DependencyInfo("com.example", "lib", null, "compile", "jar");
        deps.add(dep);

        var tui = new UpdatesTui(deps, "/tmp/pom.xml", "g:a:1.0", (g, a) -> List.of());
        tui.applyVersionResult(dep, List.of("2.0.0", "1.0.0"));

        // Empty version should accept any non-preview version
        assertThat(dep.newestVersion).isEqualTo("2.0.0");
    }
}
