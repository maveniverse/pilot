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

class PilotEngineTest {

    private PilotProject.Dep dep(String groupId, String artifactId, String version) {
        return new PilotProject.Dep(groupId, artifactId, version);
    }

    private PilotProject.Dep bomImport(String groupId, String artifactId, String version) {
        return new PilotProject.Dep(groupId, artifactId, version, "import", "pom", null, false, List.of());
    }

    @Test
    void collectManagedDependenciesExcludesBomImports() {
        List<PilotProject.Dep> originalMgmt =
                List.of(bomImport("io.quarkus", "quarkus-bom", "3.0.0"), dep("com.example", "my-lib", "1.0"));

        List<PilotProject.Dep> effectiveMgmt = List.of(
                dep("com.example", "my-lib", "1.0"),
                dep("io.quarkus", "quarkus-core", "3.0.0"),
                dep("io.quarkus", "quarkus-arc", "3.0.0"));

        List<UpdatesTui.DependencyInfo> dependencies = new ArrayList<>();
        PilotEngine.collectManagedDependencies(originalMgmt, effectiveMgmt, dependencies);

        assertThat(dependencies).hasSize(1);
        assertThat(dependencies.get(0).groupId).isEqualTo("com.example");
        assertThat(dependencies.get(0).artifactId).isEqualTo("my-lib");
        assertThat(dependencies.get(0).managed).isTrue();
    }

    @Test
    void collectManagedDependenciesResolvesPropertyVersions() {
        List<PilotProject.Dep> originalMgmt = List.of(dep("com.example", "my-lib", "${my.version}"));
        List<PilotProject.Dep> effectiveMgmt = List.of(dep("com.example", "my-lib", "2.5.0"));

        List<UpdatesTui.DependencyInfo> dependencies = new ArrayList<>();
        PilotEngine.collectManagedDependencies(originalMgmt, effectiveMgmt, dependencies);

        assertThat(dependencies).hasSize(1);
        assertThat(dependencies.get(0).version).isEqualTo("2.5.0");
    }

    @Test
    void collectManagedDependenciesSkipsAlreadyListed() {
        List<PilotProject.Dep> originalMgmt = List.of(dep("com.example", "my-lib", "1.0"));
        List<PilotProject.Dep> effectiveMgmt = List.of(dep("com.example", "my-lib", "1.0"));

        List<UpdatesTui.DependencyInfo> dependencies = new ArrayList<>();
        dependencies.add(new UpdatesTui.DependencyInfo("com.example", "my-lib", "1.0", "compile", "jar"));

        PilotEngine.collectManagedDependencies(originalMgmt, effectiveMgmt, dependencies);

        assertThat(dependencies).hasSize(1);
    }

    @Test
    void collectManagedDependenciesHandlesNullOriginalMgmt() {
        List<UpdatesTui.DependencyInfo> dependencies = new ArrayList<>();
        PilotEngine.collectManagedDependencies(null, null, dependencies);
        assertThat(dependencies).isEmpty();
    }

    @Test
    void collectManagedDependenciesHandlesNullEffectiveMgmt() {
        List<PilotProject.Dep> originalMgmt = List.of(dep("com.example", "my-lib", "1.0"));

        List<UpdatesTui.DependencyInfo> dependencies = new ArrayList<>();
        PilotEngine.collectManagedDependencies(originalMgmt, null, dependencies);

        assertThat(dependencies).hasSize(1);
        assertThat(dependencies.get(0).version).isEqualTo("1.0");
    }
}
