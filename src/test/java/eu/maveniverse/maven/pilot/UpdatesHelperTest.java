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

import java.nio.file.Path;
import java.util.List;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdatesHelperTest {

    @TempDir
    Path tempDir;

    private MavenProject createProject(String groupId, String artifactId, String version) {
        Model model = new Model();
        model.setGroupId(groupId);
        model.setArtifactId(artifactId);
        model.setVersion(version);

        Model originalModel = new Model();
        originalModel.setGroupId(groupId);
        originalModel.setArtifactId(artifactId);
        originalModel.setVersion(version);

        MavenProject project = new MavenProject(model);
        project.setOriginalModel(originalModel);
        project.setFile(tempDir.resolve("pom.xml").toFile());
        return project;
    }

    private Dependency dep(String groupId, String artifactId, String version) {
        Dependency d = new Dependency();
        d.setGroupId(groupId);
        d.setArtifactId(artifactId);
        d.setVersion(version);
        return d;
    }

    private Dependency dep(String groupId, String artifactId, String version, String type, String scope) {
        Dependency d = dep(groupId, artifactId, version);
        d.setType(type);
        d.setScope(scope);
        return d;
    }

    @Test
    void directDepsAreCollected() {
        MavenProject project = createProject("com.example", "app", "1.0");
        project.getModel().addDependency(dep("org.slf4j", "slf4j-api", "2.0.9"));
        project.getModel().addDependency(dep("org.junit", "junit", "5.10.0"));

        List<UpdatesTui.DependencyInfo> result = UpdatesHelper.collectDependencies(project);

        assertThat(result).hasSize(2);
        assertThat(result)
                .extracting(d -> d.groupId + ":" + d.artifactId)
                .containsExactly("org.slf4j:slf4j-api", "org.junit:junit");
        assertThat(result).allMatch(d -> !d.managed);
    }

    @Test
    void originalModelManagedDepsAreCollected() {
        MavenProject project = createProject("com.example", "app", "1.0");

        DependencyManagement origMgmt = new DependencyManagement();
        origMgmt.addDependency(dep("org.slf4j", "slf4j-api", "2.0.9"));
        origMgmt.addDependency(dep("com.fasterxml", "jackson-core", "2.17.0"));
        project.getOriginalModel().setDependencyManagement(origMgmt);

        List<UpdatesTui.DependencyInfo> result = UpdatesHelper.collectDependencies(project);

        assertThat(result).hasSize(2).allMatch(d -> d.managed);
        assertThat(result)
                .extracting(d -> d.groupId + ":" + d.artifactId)
                .containsExactly("org.slf4j:slf4j-api", "com.fasterxml:jackson-core");
    }

    @Test
    void bomImportedDepsAreExcluded() {
        MavenProject project = createProject("com.example", "app", "1.0");

        // Effective model has 50 deps from BOM (simulated with 3 here)
        DependencyManagement effectiveMgmt = new DependencyManagement();
        effectiveMgmt.addDependency(dep("org.slf4j", "slf4j-api", "2.0.9"));
        effectiveMgmt.addDependency(dep("com.fasterxml", "jackson-core", "2.17.0"));
        effectiveMgmt.addDependency(dep("com.fasterxml", "jackson-databind", "2.17.0"));
        effectiveMgmt.addDependency(dep("io.netty", "netty-all", "4.1.100"));
        effectiveMgmt.addDependency(dep("io.micrometer", "micrometer-core", "1.12.0"));
        project.getModel().setDependencyManagement(effectiveMgmt);

        // Original model has only 2 explicitly declared managed deps
        DependencyManagement origMgmt = new DependencyManagement();
        origMgmt.addDependency(dep("org.slf4j", "slf4j-api", "2.0.9"));
        origMgmt.addDependency(dep("com.fasterxml", "jackson-core", "2.17.0"));
        project.getOriginalModel().setDependencyManagement(origMgmt);

        List<UpdatesTui.DependencyInfo> result = UpdatesHelper.collectDependencies(project);

        // Should only have the 2 from the original model, not the 5 from effective
        assertThat(result).hasSize(2);
        assertThat(result)
                .extracting(d -> d.groupId + ":" + d.artifactId)
                .containsExactly("org.slf4j:slf4j-api", "com.fasterxml:jackson-core");
    }

    @Test
    void bomImportEntriesFiltered() {
        MavenProject project = createProject("com.example", "app", "1.0");

        DependencyManagement origMgmt = new DependencyManagement();
        origMgmt.addDependency(dep("org.slf4j", "slf4j-api", "2.0.9"));
        origMgmt.addDependency(dep("org.springframework.boot", "spring-boot-dependencies", "3.2.0", "pom", "import"));
        origMgmt.addDependency(dep("com.fasterxml", "jackson-core", "2.17.0"));
        project.getOriginalModel().setDependencyManagement(origMgmt);

        List<UpdatesTui.DependencyInfo> result = UpdatesHelper.collectDependencies(project);

        assertThat(result).hasSize(2);
        assertThat(result)
                .extracting(d -> d.groupId + ":" + d.artifactId)
                .containsExactly("org.slf4j:slf4j-api", "com.fasterxml:jackson-core");
        assertThat(result).noneMatch(d -> "spring-boot-dependencies".equals(d.artifactId));
    }

    @Test
    void deduplication() {
        MavenProject project = createProject("com.example", "app", "1.0");

        // Direct dependency
        project.getModel().addDependency(dep("org.slf4j", "slf4j-api", "2.0.9"));

        // Same dep also in managed
        DependencyManagement origMgmt = new DependencyManagement();
        origMgmt.addDependency(dep("org.slf4j", "slf4j-api", "2.0.9"));
        origMgmt.addDependency(dep("com.fasterxml", "jackson-core", "2.17.0"));
        project.getOriginalModel().setDependencyManagement(origMgmt);

        List<UpdatesTui.DependencyInfo> result = UpdatesHelper.collectDependencies(project);

        // slf4j appears once (as direct, not managed), jackson appears as managed
        assertThat(result).hasSize(2);
        UpdatesTui.DependencyInfo slf4j = result.stream()
                .filter(d -> "slf4j-api".equals(d.artifactId))
                .findFirst()
                .orElseThrow();
        assertThat(slf4j.managed).isFalse();

        UpdatesTui.DependencyInfo jackson = result.stream()
                .filter(d -> "jackson-core".equals(d.artifactId))
                .findFirst()
                .orElseThrow();
        assertThat(jackson.managed).isTrue();
    }

    @Test
    void emptyProject() {
        MavenProject project = createProject("com.example", "app", "1.0");

        List<UpdatesTui.DependencyInfo> result = UpdatesHelper.collectDependencies(project);

        assertThat(result).isEmpty();
    }

    @Test
    void nullDependencyManagement() {
        MavenProject project = createProject("com.example", "app", "1.0");
        project.getModel().addDependency(dep("org.slf4j", "slf4j-api", "2.0.9"));
        // originalModel has no dependencyManagement (null)

        List<UpdatesTui.DependencyInfo> result = UpdatesHelper.collectDependencies(project);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).groupId).isEqualTo("org.slf4j");
        assertThat(result.get(0).managed).isFalse();
    }

    @Test
    void propertyVersionsPreserved() {
        MavenProject project = createProject("com.example", "app", "1.0");

        DependencyManagement origMgmt = new DependencyManagement();
        origMgmt.addDependency(dep("org.slf4j", "slf4j-api", "${slf4j.version}"));
        origMgmt.addDependency(dep("com.fasterxml", "jackson-core", "${jackson.version}"));
        project.getOriginalModel().setDependencyManagement(origMgmt);

        List<UpdatesTui.DependencyInfo> result = UpdatesHelper.collectDependencies(project);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).version).isEqualTo("${slf4j.version}");
        assertThat(result.get(1).version).isEqualTo("${jackson.version}");
    }
}
