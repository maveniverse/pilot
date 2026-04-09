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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReactorCollectorTest {

    @TempDir
    Path tempDir;

    private Path subdir(String name) throws IOException {
        return Files.createDirectories(tempDir.resolve(name));
    }

    private MavenProject createProject(String groupId, String artifactId, String version, Path basedir) {
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
        project.setFile(basedir.resolve("pom.xml").toFile());
        return project;
    }

    private Dependency dep(String groupId, String artifactId, String version) {
        return dep(groupId, artifactId, version, null);
    }

    private Dependency dep(String groupId, String artifactId, String version, String scope) {
        Dependency d = new Dependency();
        d.setGroupId(groupId);
        d.setArtifactId(artifactId);
        d.setVersion(version);
        d.setScope(scope);
        return d;
    }

    @Test
    void collectSingleProjectDependencies() {
        MavenProject project = createProject("com.example", "app", "1.0", tempDir);
        project.getModel().addDependency(dep("org.junit", "junit", "5.10.0", "test"));
        project.getOriginalModel().addDependency(dep("org.junit", "junit", "5.10.0", "test"));

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));

        assertThat(result.allDependencies).hasSize(1);
        assertThat(result.allDependencies.get(0).groupId).isEqualTo("org.junit");
        assertThat(result.allDependencies.get(0).artifactId).isEqualTo("junit");
        assertThat(result.allDependencies.get(0).primaryVersion).isEqualTo("5.10.0");
        assertThat(result.allDependencies.get(0).usages).hasSize(1);
    }

    @Test
    void collectDeduplicatesByGA() throws IOException {
        Path dir1 = subdir("mod1");
        Path dir2 = subdir("mod2");

        MavenProject p1 = createProject("com.example", "mod1", "1.0", dir1);
        p1.getModel().addDependency(dep("org.slf4j", "slf4j-api", "2.0.9"));
        p1.getOriginalModel().addDependency(dep("org.slf4j", "slf4j-api", "2.0.9"));

        MavenProject p2 = createProject("com.example", "mod2", "1.0", dir2);
        p2.getModel().addDependency(dep("org.slf4j", "slf4j-api", "2.0.9"));
        p2.getOriginalModel().addDependency(dep("org.slf4j", "slf4j-api", "2.0.9"));

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(p1, p2));

        assertThat(result.allDependencies).hasSize(1);
        assertThat(result.allDependencies.get(0).usages).hasSize(2);
        assertThat(result.allDependencies.get(0).moduleCount()).isEqualTo(2);
    }

    @Test
    void collectDetectsPropertyVersion() {
        MavenProject project = createProject("com.example", "app", "1.0", tempDir);
        project.getModel().addDependency(dep("com.fasterxml", "jackson-core", "2.17.0"));

        Dependency origDep = dep("com.fasterxml", "jackson-core", "${jackson.version}");
        project.getOriginalModel().addDependency(origDep);

        Properties props = new Properties();
        props.setProperty("jackson.version", "2.17.0");
        project.getOriginalModel().setProperties(props);

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));

        assertThat(result.allDependencies).hasSize(1);
        var dep = result.allDependencies.get(0);
        assertThat(dep.propertyName).isEqualTo("jackson.version");
        assertThat(dep.rawVersionExpr).isEqualTo("${jackson.version}");
        assertThat(dep.propertyOrigin).isEqualTo(project);
    }

    @Test
    void collectGroupsByProperty() {
        MavenProject project = createProject("com.example", "app", "1.0", tempDir);

        project.getModel().addDependency(dep("com.fasterxml", "jackson-core", "2.17.0"));
        project.getModel().addDependency(dep("com.fasterxml", "jackson-databind", "2.17.0"));

        project.getOriginalModel().addDependency(dep("com.fasterxml", "jackson-core", "${jackson.version}"));
        project.getOriginalModel().addDependency(dep("com.fasterxml", "jackson-databind", "${jackson.version}"));

        Properties props = new Properties();
        props.setProperty("jackson.version", "2.17.0");
        project.getOriginalModel().setProperties(props);

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));

        assertThat(result.allDependencies).hasSize(2);
        assertThat(result.propertyGroups).hasSize(1);
        assertThat(result.propertyGroups.get(0).propertyName).isEqualTo("jackson.version");
        assertThat(result.propertyGroups.get(0).dependencies).hasSize(2);
        assertThat(result.ungroupedDependencies).isEmpty();
    }

    @Test
    void collectSeparatesUngroupedDependencies() {
        MavenProject project = createProject("com.example", "app", "1.0", tempDir);

        // Property-based dependency
        project.getModel().addDependency(dep("com.fasterxml", "jackson-core", "2.17.0"));
        project.getOriginalModel().addDependency(dep("com.fasterxml", "jackson-core", "${jackson.version}"));
        Properties props = new Properties();
        props.setProperty("jackson.version", "2.17.0");
        project.getOriginalModel().setProperties(props);

        // Direct version dependency
        project.getModel().addDependency(dep("commons-io", "commons-io", "2.15.1"));
        project.getOriginalModel().addDependency(dep("commons-io", "commons-io", "2.15.1"));

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));

        assertThat(result.allDependencies).hasSize(2);
        assertThat(result.propertyGroups).hasSize(1);
        assertThat(result.ungroupedDependencies).hasSize(1);
        assertThat(result.ungroupedDependencies.get(0).groupId).isEqualTo("commons-io");
    }

    @Test
    void collectManagedDependencies() {
        MavenProject project = createProject("com.example", "app", "1.0", tempDir);

        DependencyManagement mgmt = new DependencyManagement();
        mgmt.addDependency(dep("org.junit", "junit", "5.10.0", "test"));
        project.getModel().setDependencyManagement(mgmt);

        DependencyManagement origMgmt = new DependencyManagement();
        origMgmt.addDependency(dep("org.junit", "junit", "5.10.0", "test"));
        project.getOriginalModel().setDependencyManagement(origMgmt);

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));

        assertThat(result.allDependencies).hasSize(1);
        assertThat(result.allDependencies.get(0).usages.get(0).managed).isTrue();
    }

    @Test
    void collectEmptyProjectList() {
        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of());
        assertThat(result.allDependencies).isEmpty();
        assertThat(result.propertyGroups).isEmpty();
        assertThat(result.ungroupedDependencies).isEmpty();
    }

    @Test
    void propertyOriginReturnsNullWhenNotFound() {
        MavenProject project = createProject("com.example", "app", "1.0", tempDir);

        // Raw expression but no property defined in original model
        project.getModel().addDependency(dep("com.fasterxml", "jackson-core", "2.17.0"));
        project.getOriginalModel().addDependency(dep("com.fasterxml", "jackson-core", "${jackson.version}"));

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));

        assertThat(result.allDependencies).hasSize(1);
        var dep = result.allDependencies.get(0);
        assertThat(dep.propertyName).isEqualTo("jackson.version");
        assertThat(dep.propertyOrigin).isNull();
        // Should be ungrouped since propertyOrigin is null
        assertThat(result.ungroupedDependencies).hasSize(1);
        assertThat(result.propertyGroups).isEmpty();
    }

    @Test
    void compoundGroupingKeySeparatesSamePropertyFromDifferentOrigins() throws IOException {
        Path dir1 = subdir("parent");
        Path dir2 = subdir("child");

        MavenProject parent = createProject("com.example", "parent", "1.0", dir1);
        Properties parentProps = new Properties();
        parentProps.setProperty("lib.version", "1.0");
        parent.getOriginalModel().setProperties(parentProps);
        parent.getModel().addDependency(dep("com.example", "lib-a", "1.0"));
        parent.getOriginalModel().addDependency(dep("com.example", "lib-a", "${lib.version}"));

        MavenProject child = createProject("com.example", "child", "1.0", dir2);
        Properties childProps = new Properties();
        childProps.setProperty("lib.version", "2.0");
        child.getOriginalModel().setProperties(childProps);
        child.getModel().addDependency(dep("com.example", "lib-b", "2.0"));
        child.getOriginalModel().addDependency(dep("com.example", "lib-b", "${lib.version}"));

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(parent, child));

        // Same property name but from different origins → two groups
        assertThat(result.propertyGroups).hasSize(2);
    }

    @Test
    void aggregatedDependencyGa() {
        var dep = new ReactorCollector.AggregatedDependency("org.slf4j", "slf4j-api");
        assertThat(dep.ga()).isEqualTo("org.slf4j:slf4j-api");
    }

    @Test
    void aggregatedDependencyHasUpdate() {
        var dep = new ReactorCollector.AggregatedDependency("g", "a");
        dep.primaryVersion = "1.0";
        assertThat(dep.hasUpdate()).isFalse();

        dep.newestVersion = "2.0";
        assertThat(dep.hasUpdate()).isTrue();

        dep.newestVersion = "1.0";
        assertThat(dep.hasUpdate()).isFalse();

        dep.newestVersion = "";
        assertThat(dep.hasUpdate()).isFalse();
    }

    @Test
    void aggregatedDependencyIsPropertyManaged() {
        var dep = new ReactorCollector.AggregatedDependency("g", "a");
        assertThat(dep.isPropertyManaged()).isFalse();

        dep.propertyName = "lib.version";
        assertThat(dep.isPropertyManaged()).isTrue();
    }

    @Test
    void propertyGroupHasUpdate() {
        var group = new ReactorCollector.PropertyGroup("lib.version", "${lib.version}", "1.0", null);
        assertThat(group.hasUpdate()).isFalse();

        group.newestVersion = "2.0";
        assertThat(group.hasUpdate()).isTrue();

        group.newestVersion = "1.0";
        assertThat(group.hasUpdate()).isFalse();

        group.newestVersion = "";
        assertThat(group.hasUpdate()).isFalse();
    }

    @Test
    void propertyGroupTotalModuleCount() {
        MavenProject p1 = createProject("com.example", "mod1", "1.0", tempDir);
        var group = new ReactorCollector.PropertyGroup("lib.version", "${lib.version}", "1.0", null);

        assertThat(group.totalModuleCount()).isZero();

        var dep1 = new ReactorCollector.AggregatedDependency("g", "a");
        dep1.usages.add(new ReactorCollector.ModuleUsage(p1, "1.0", "compile", false));
        group.dependencies.add(dep1);

        assertThat(group.totalModuleCount()).isEqualTo(1);
    }

    @Test
    void collectWithPropertyInParent() throws IOException {
        Path parentDir = subdir("parent2");
        Path childDir = subdir("child2");

        MavenProject parent = createProject("com.example", "parent", "1.0", parentDir);
        Properties parentProps = new Properties();
        parentProps.setProperty("junit.version", "5.10.0");
        parent.getOriginalModel().setProperties(parentProps);

        MavenProject child = createProject("com.example", "child", "1.0", childDir);
        child.setParent(parent);
        child.getModel().addDependency(dep("org.junit", "junit", "5.10.0", "test"));
        child.getOriginalModel().addDependency(dep("org.junit", "junit", "${junit.version}", "test"));

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(parent, child));

        var dep = result.allDependencies.stream()
                .filter(d -> d.artifactId.equals("junit"))
                .findFirst()
                .orElseThrow();
        assertThat(dep.propertyName).isEqualTo("junit.version");
        assertThat(dep.propertyOrigin).isEqualTo(parent);
    }
}
