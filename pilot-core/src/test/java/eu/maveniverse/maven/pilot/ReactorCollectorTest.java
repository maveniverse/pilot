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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReactorCollectorTest {

    @TempDir
    Path tempDir;

    private Path subdir(String name) throws IOException {
        return Files.createDirectories(tempDir.resolve(name));
    }

    private PilotProject createProject(String groupId, String artifactId, String version, Path basedir) {
        return createProject(groupId, artifactId, version, basedir, List.of(), List.of(), List.of(), List.of());
    }

    private PilotProject createProject(
            String groupId,
            String artifactId,
            String version,
            Path basedir,
            List<PilotProject.Dep> deps,
            List<PilotProject.Dep> mgmtDeps,
            List<PilotProject.Dep> origDeps,
            List<PilotProject.Dep> origMgmtDeps) {
        return createProject(
                groupId, artifactId, version, basedir, deps, mgmtDeps, origDeps, origMgmtDeps, new Properties());
    }

    private PilotProject createProject(
            String groupId,
            String artifactId,
            String version,
            Path basedir,
            List<PilotProject.Dep> deps,
            List<PilotProject.Dep> mgmtDeps,
            List<PilotProject.Dep> origDeps,
            List<PilotProject.Dep> origMgmtDeps,
            Properties origProps) {
        return new PilotProject(
                groupId,
                artifactId,
                version,
                "jar",
                basedir,
                basedir.resolve("pom.xml"),
                deps,
                mgmtDeps,
                origDeps,
                origMgmtDeps,
                origProps,
                null,
                null);
    }

    private PilotProject.Dep dep(String groupId, String artifactId, String version) {
        return new PilotProject.Dep(groupId, artifactId, version);
    }

    private PilotProject.Dep dep(String groupId, String artifactId, String version, String scope) {
        return new PilotProject.Dep(groupId, artifactId, version, scope, null);
    }

    @Test
    void collectSingleProjectDependencies() {
        PilotProject.Dep d = dep("org.junit", "junit", "5.10.0", "test");
        PilotProject project =
                createProject("com.example", "app", "1.0", tempDir, List.of(d), List.of(), List.of(d), List.of());

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

        PilotProject.Dep d = dep("org.slf4j", "slf4j-api", "2.0.9");
        PilotProject p1 =
                createProject("com.example", "mod1", "1.0", dir1, List.of(d), List.of(), List.of(d), List.of());
        PilotProject p2 =
                createProject("com.example", "mod2", "1.0", dir2, List.of(d), List.of(), List.of(d), List.of());

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(p1, p2));

        assertThat(result.allDependencies).hasSize(1);
        assertThat(result.allDependencies.get(0).usages).hasSize(2);
        assertThat(result.allDependencies.get(0).moduleCount()).isEqualTo(2);
    }

    @Test
    void collectDetectsPropertyVersion() {
        PilotProject.Dep effectiveDep = dep("com.fasterxml", "jackson-core", "2.17.0");
        PilotProject.Dep origDep = dep("com.fasterxml", "jackson-core", "${jackson.version}");

        Properties props = new Properties();
        props.setProperty("jackson.version", "2.17.0");

        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                tempDir,
                List.of(effectiveDep),
                List.of(),
                List.of(origDep),
                List.of(),
                props);

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));

        assertThat(result.allDependencies).hasSize(1);
        var dep = result.allDependencies.get(0);
        assertThat(dep.propertyName).isEqualTo("jackson.version");
        assertThat(dep.rawVersionExpr).isEqualTo("${jackson.version}");
        assertThat(dep.propertyOrigin).isEqualTo(project);
    }

    @Test
    void collectGroupsByProperty() {
        PilotProject.Dep eDep1 = dep("com.fasterxml", "jackson-core", "2.17.0");
        PilotProject.Dep eDep2 = dep("com.fasterxml", "jackson-databind", "2.17.0");
        PilotProject.Dep oDep1 = dep("com.fasterxml", "jackson-core", "${jackson.version}");
        PilotProject.Dep oDep2 = dep("com.fasterxml", "jackson-databind", "${jackson.version}");

        Properties props = new Properties();
        props.setProperty("jackson.version", "2.17.0");

        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                tempDir,
                List.of(eDep1, eDep2),
                List.of(),
                List.of(oDep1, oDep2),
                List.of(),
                props);

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));

        assertThat(result.allDependencies).hasSize(2);
        assertThat(result.propertyGroups).hasSize(1);
        assertThat(result.propertyGroups.get(0).propertyName).isEqualTo("jackson.version");
        assertThat(result.propertyGroups.get(0).dependencies).hasSize(2);
        assertThat(result.ungroupedDependencies).isEmpty();
    }

    @Test
    void collectSeparatesUngroupedDependencies() {
        PilotProject.Dep eDep1 = dep("com.fasterxml", "jackson-core", "2.17.0");
        PilotProject.Dep oDep1 = dep("com.fasterxml", "jackson-core", "${jackson.version}");
        PilotProject.Dep eDep2 = dep("commons-io", "commons-io", "2.15.1");
        PilotProject.Dep oDep2 = dep("commons-io", "commons-io", "2.15.1");

        Properties props = new Properties();
        props.setProperty("jackson.version", "2.17.0");

        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                tempDir,
                List.of(eDep1, eDep2),
                List.of(),
                List.of(oDep1, oDep2),
                List.of(),
                props);

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));

        assertThat(result.allDependencies).hasSize(2);
        assertThat(result.propertyGroups).hasSize(1);
        assertThat(result.ungroupedDependencies).hasSize(1);
        assertThat(result.ungroupedDependencies.get(0).groupId).isEqualTo("commons-io");
    }

    @Test
    void collectManagedDependencies() {
        PilotProject.Dep mgmtDep = dep("org.junit", "junit", "5.10.0", "test");
        PilotProject project = createProject(
                "com.example", "app", "1.0", tempDir, List.of(), List.of(mgmtDep), List.of(), List.of(mgmtDep));

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
        PilotProject.Dep effectiveDep = dep("com.fasterxml", "jackson-core", "2.17.0");
        PilotProject.Dep origDep = dep("com.fasterxml", "jackson-core", "${jackson.version}");

        // No property defined in original model
        PilotProject project = createProject(
                "com.example", "app", "1.0", tempDir, List.of(effectiveDep), List.of(), List.of(origDep), List.of());

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

        Properties parentProps = new Properties();
        parentProps.setProperty("lib.version", "1.0");
        PilotProject.Dep pEffDep = dep("com.example", "lib-a", "1.0");
        PilotProject.Dep pOrigDep = dep("com.example", "lib-a", "${lib.version}");
        PilotProject parent = createProject(
                "com.example",
                "parent",
                "1.0",
                dir1,
                List.of(pEffDep),
                List.of(),
                List.of(pOrigDep),
                List.of(),
                parentProps);

        Properties childProps = new Properties();
        childProps.setProperty("lib.version", "2.0");
        PilotProject.Dep cEffDep = dep("com.example", "lib-b", "2.0");
        PilotProject.Dep cOrigDep = dep("com.example", "lib-b", "${lib.version}");
        PilotProject child = createProject(
                "com.example",
                "child",
                "1.0",
                dir2,
                List.of(cEffDep),
                List.of(),
                List.of(cOrigDep),
                List.of(),
                childProps);

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
        PilotProject p1 = new PilotProject(
                "com.example",
                "mod1",
                "1.0",
                "jar",
                tempDir,
                tempDir.resolve("pom.xml"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new Properties(),
                null,
                null);
        var group = new ReactorCollector.PropertyGroup("lib.version", "${lib.version}", "1.0", null);

        assertThat(group.totalModuleCount()).isZero();

        var dep1 = new ReactorCollector.AggregatedDependency("g", "a");
        dep1.usages.add(new ReactorCollector.ModuleUsage(p1, "1.0", "compile", false));
        group.dependencies.add(dep1);

        assertThat(group.totalModuleCount()).isEqualTo(1);
    }

    @Test
    void collectExcludesBomImportedDependencies() {
        // Original model: one BOM import and one direct managed dep
        PilotProject.Dep bomImport =
                new PilotProject.Dep("io.quarkus", "quarkus-bom", "3.0.0", "import", "pom", null, false, List.of());
        PilotProject.Dep origMgmt = dep("com.example", "my-lib", "1.0");

        // Effective model: BOM is flattened
        PilotProject.Dep effMgmt1 = dep("com.example", "my-lib", "1.0");
        PilotProject.Dep effMgmt2 = dep("io.quarkus", "quarkus-core", "3.0.0");
        PilotProject.Dep effMgmt3 = dep("io.quarkus", "quarkus-arc", "3.0.0");
        PilotProject.Dep effMgmt4 = dep("jakarta.enterprise", "jakarta.enterprise.cdi-api", "4.0.1");

        PilotProject project = createProject(
                "com.example",
                "app",
                "1.0",
                tempDir,
                List.of(),
                List.of(effMgmt1, effMgmt2, effMgmt3, effMgmt4),
                List.of(),
                List.of(bomImport, origMgmt));

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(project));

        // Only the directly declared managed dep should appear, not BOM-imported ones
        assertThat(result.allDependencies).hasSize(1);
        assertThat(result.allDependencies.get(0).groupId).isEqualTo("com.example");
        assertThat(result.allDependencies.get(0).artifactId).isEqualTo("my-lib");
    }

    @Test
    void collectWithPropertyInParent() throws IOException {
        Path parentDir = subdir("parent2");
        Path childDir = subdir("child2");

        Properties parentProps = new Properties();
        parentProps.setProperty("junit.version", "5.10.0");

        PilotProject parent = createProject(
                "com.example", "parent", "1.0", parentDir, List.of(), List.of(), List.of(), List.of(), parentProps);

        PilotProject.Dep childEffDep = dep("org.junit", "junit", "5.10.0", "test");
        PilotProject.Dep childOrigDep = dep("org.junit", "junit", "${junit.version}", "test");
        PilotProject child = createProject(
                "com.example",
                "child",
                "1.0",
                childDir,
                List.of(childEffDep),
                List.of(),
                List.of(childOrigDep),
                List.of());
        child.parent = parent;

        ReactorCollector.CollectionResult result = ReactorCollector.collect(List.of(parent, child));

        var dep = result.allDependencies.stream()
                .filter(d -> d.artifactId.equals("junit"))
                .findFirst()
                .orElseThrow();
        assertThat(dep.propertyName).isEqualTo("junit.version");
        assertThat(dep.propertyOrigin).isEqualTo(parent);
    }
}
