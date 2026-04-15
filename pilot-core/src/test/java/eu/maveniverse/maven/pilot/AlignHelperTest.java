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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AlignHelperTest {

    private static final String PARENT_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>test</groupId>
                <artifactId>parent</artifactId>
                <version>1.0</version>
                <packaging>pom</packaging>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>foo</artifactId>
                            <version>2.0</version>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """;

    private static final String EMPTY_PARENT_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>test</groupId>
                <artifactId>parent</artifactId>
                <version>1.0</version>
                <packaging>pom</packaging>
            </project>
            """;

    @Test
    void findParentPomInfoFindsParentWithDepMgmt(@TempDir Path tempDir) throws Exception {
        Path parentPomFile = tempDir.resolve("parent/pom.xml");
        Files.createDirectories(parentPomFile.getParent());
        Files.writeString(parentPomFile, PARENT_POM);

        PilotProject parentProject = createProject("test", "parent", "1.0", parentPomFile);
        parentProject = withManagedDeps(parentProject, "org.example", "foo", "2.0");

        Path childPomFile = tempDir.resolve("child/pom.xml");
        Files.createDirectories(childPomFile.getParent());
        Files.writeString(childPomFile, EMPTY_PARENT_POM);

        PilotProject childProject = createProject("test", "child", "1.0", childPomFile);
        childProject.parent = parentProject;

        var result = AlignHelper.findParentPomInfo(childProject, List.of(parentProject, childProject));

        assertThat(result).isNotNull();
        assertThat(result.pomPath()).isEqualTo(parentPomFile.toString());
        assertThat(result.gav()).isEqualTo("test:parent:1.0");
    }

    @Test
    void findParentPomInfoFallsBackToDirectParent(@TempDir Path tempDir) throws Exception {
        Path parentPomFile = tempDir.resolve("parent/pom.xml");
        Files.createDirectories(parentPomFile.getParent());
        Files.writeString(parentPomFile, EMPTY_PARENT_POM);

        PilotProject parentProject = createProject("test", "parent", "1.0", parentPomFile);

        Path childPomFile = tempDir.resolve("child/pom.xml");
        Files.createDirectories(childPomFile.getParent());
        Files.writeString(childPomFile, EMPTY_PARENT_POM);

        PilotProject childProject = createProject("test", "child", "1.0", childPomFile);
        childProject.parent = parentProject;

        var result = AlignHelper.findParentPomInfo(childProject, List.of(parentProject, childProject));

        assertThat(result).isNotNull();
        assertThat(result.pomPath()).isEqualTo(parentPomFile.toString());
        assertThat(result.gav()).isEqualTo("test:parent:1.0");
    }

    @Test
    void findParentPomInfoReturnsNullWhenNoReactorParent(@TempDir Path tempDir) throws Exception {
        Path childPomFile = tempDir.resolve("child/pom.xml");
        Files.createDirectories(childPomFile.getParent());
        Files.writeString(childPomFile, EMPTY_PARENT_POM);

        PilotProject childProject = createProject("test", "child", "1.0", childPomFile);

        var result = AlignHelper.findParentPomInfo(childProject, List.of(childProject));

        assertThat(result).isNull();
    }

    @Test
    void findParentPomInfoSkipsNonReactorParent(@TempDir Path tempDir) throws Exception {
        Path parentPomFile = tempDir.resolve("parent/pom.xml");
        Files.createDirectories(parentPomFile.getParent());
        Files.writeString(parentPomFile, PARENT_POM);

        PilotProject parentProject = createProject("test", "parent", "1.0", parentPomFile);
        parentProject = withManagedDeps(parentProject, "org.example", "foo", "2.0");

        Path childPomFile = tempDir.resolve("child/pom.xml");
        Files.createDirectories(childPomFile.getParent());
        Files.writeString(childPomFile, EMPTY_PARENT_POM);

        PilotProject childProject = createProject("test", "child", "1.0", childPomFile);
        childProject.parent = parentProject;

        // Parent is NOT in reactor list
        var result = AlignHelper.findParentPomInfo(childProject, List.of(childProject));

        assertThat(result).isNull();
    }

    private static PilotProject createProject(String groupId, String artifactId, String version, Path pomFile) {
        return new PilotProject(
                groupId,
                artifactId,
                version,
                "jar",
                pomFile.getParent(),
                pomFile,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new Properties(),
                null,
                null);
    }

    private static PilotProject withManagedDeps(PilotProject base, String groupId, String artifactId, String version) {
        PilotProject.Dep dep = new PilotProject.Dep(groupId, artifactId, version);
        PilotProject pp = new PilotProject(
                base.groupId,
                base.artifactId,
                base.version,
                base.packaging,
                base.basedir,
                base.pomPath,
                base.dependencies,
                base.managedDependencies,
                base.originalDependencies,
                List.of(dep),
                base.originalProperties,
                base.outputDirectory,
                base.testOutputDirectory);
        pp.parent = base.parent;
        return pp;
    }
}
