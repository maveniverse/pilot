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
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.junit.jupiter.api.Test;

class PilotMojoTest {

    private Dependency dep(String groupId, String artifactId, String version) {
        Dependency d = new Dependency();
        d.setGroupId(groupId);
        d.setArtifactId(artifactId);
        d.setVersion(version);
        return d;
    }

    @Test
    void collectManagedDependenciesExcludesBomImports() {
        DependencyManagement originalMgmt = new DependencyManagement();
        Dependency bomImport = dep("io.quarkus", "quarkus-bom", "3.0.0");
        bomImport.setType("pom");
        bomImport.setScope("import");
        originalMgmt.addDependency(bomImport);
        originalMgmt.addDependency(dep("com.example", "my-lib", "1.0"));

        // Effective model: BOM flattened
        DependencyManagement effectiveMgmt = new DependencyManagement();
        effectiveMgmt.addDependency(dep("com.example", "my-lib", "1.0"));
        effectiveMgmt.addDependency(dep("io.quarkus", "quarkus-core", "3.0.0"));
        effectiveMgmt.addDependency(dep("io.quarkus", "quarkus-arc", "3.0.0"));

        List<UpdatesTui.DependencyInfo> dependencies = new ArrayList<>();
        PilotMojo.collectManagedDependencies(originalMgmt, effectiveMgmt, dependencies);

        assertThat(dependencies).hasSize(1);
        assertThat(dependencies.get(0).groupId).isEqualTo("com.example");
        assertThat(dependencies.get(0).artifactId).isEqualTo("my-lib");
        assertThat(dependencies.get(0).managed).isTrue();
    }

    @Test
    void collectManagedDependenciesResolvesPropertyVersions() {
        DependencyManagement originalMgmt = new DependencyManagement();
        originalMgmt.addDependency(dep("com.example", "my-lib", "${my.version}"));

        DependencyManagement effectiveMgmt = new DependencyManagement();
        effectiveMgmt.addDependency(dep("com.example", "my-lib", "2.5.0"));

        List<UpdatesTui.DependencyInfo> dependencies = new ArrayList<>();
        PilotMojo.collectManagedDependencies(originalMgmt, effectiveMgmt, dependencies);

        assertThat(dependencies).hasSize(1);
        assertThat(dependencies.get(0).version).isEqualTo("2.5.0");
    }

    @Test
    void collectManagedDependenciesSkipsAlreadyListed() {
        DependencyManagement originalMgmt = new DependencyManagement();
        originalMgmt.addDependency(dep("com.example", "my-lib", "1.0"));

        DependencyManagement effectiveMgmt = new DependencyManagement();
        effectiveMgmt.addDependency(dep("com.example", "my-lib", "1.0"));

        List<UpdatesTui.DependencyInfo> dependencies = new ArrayList<>();
        dependencies.add(new UpdatesTui.DependencyInfo("com.example", "my-lib", "1.0", "compile", "jar"));

        PilotMojo.collectManagedDependencies(originalMgmt, effectiveMgmt, dependencies);

        assertThat(dependencies).hasSize(1);
    }

    @Test
    void collectManagedDependenciesHandlesNullOriginalMgmt() {
        List<UpdatesTui.DependencyInfo> dependencies = new ArrayList<>();
        PilotMojo.collectManagedDependencies(null, null, dependencies);
        assertThat(dependencies).isEmpty();
    }

    @Test
    void collectManagedDependenciesHandlesNullEffectiveMgmt() {
        DependencyManagement originalMgmt = new DependencyManagement();
        originalMgmt.addDependency(dep("com.example", "my-lib", "1.0"));

        List<UpdatesTui.DependencyInfo> dependencies = new ArrayList<>();
        PilotMojo.collectManagedDependencies(originalMgmt, null, dependencies);

        assertThat(dependencies).hasSize(1);
        assertThat(dependencies.get(0).version).isEqualTo("1.0");
    }
}
