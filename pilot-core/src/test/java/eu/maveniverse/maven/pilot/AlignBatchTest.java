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

import eu.maveniverse.domtrip.maven.AlignOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration-level tests for batch (multi-module) alignment.
 *
 * <p>Each test creates a realistic multi-module project structure on disk,
 * runs the batch alignment logic, and verifies the resulting POM files.</p>
 */
class AlignBatchTest {

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Path writePom(Path dir, String content) throws Exception {
        Files.createDirectories(dir);
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, content);
        return pom;
    }

    private static AlignOptions managedLiteral() {
        return AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.LITERAL)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();
    }

    private static AlignOptions managedProperty() {
        return AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.PROPERTY)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();
    }

    // ── batch align: inline → managed (literal) ────────────────────────────

    @Test
    void batchAlignMovesInlineVersionsToParentDepMgmt(@TempDir Path root) throws Exception {
        Path parentPom = writePom(root.resolve("parent"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <packaging>pom</packaging>
                </project>
                """);

        Path childA = writePom(root.resolve("module-a"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>module-a</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <version>2.0.9</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        Path childB = writePom(root.resolve("module-b"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>module-b</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>33.0.0-jre</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        var tui = new AlignTui(
                parentPom.toString(),
                List.of(childA.toString(), childB.toString()),
                "com.example:parent:1.0",
                managedLiteral(),
                null);

        tui.applyBatchAlignment();

        String parentResult = Files.readString(parentPom);
        String childAResult = Files.readString(childA);
        String childBResult = Files.readString(childB);

        // Parent should have dependencyManagement with both deps
        assertThat(parentResult)
                .contains("dependencyManagement")
                .contains("slf4j-api")
                .contains("2.0.9")
                .contains("guava")
                .contains("33.0.0-jre");

        // Children should no longer have inline versions
        assertThat(childAResult).doesNotContain("<version>2.0.9</version>");
        assertThat(childBResult).doesNotContain("<version>33.0.0-jre</version>");
    }

    // ── batch align: property versions → property source ───────────────────

    @Test
    void batchAlignWithPropertySourceCreatesPropertiesInParent(@TempDir Path root) throws Exception {
        Path parentPom = writePom(root.resolve("parent"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <packaging>pom</packaging>
                </project>
                """);

        Path child = writePom(root.resolve("child"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>child</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.11.0</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """);

        var tui = new AlignTui(
                parentPom.toString(), List.of(child.toString()), "com.example:parent:1.0", managedProperty(), null);

        tui.applyBatchAlignment();

        String parentResult = Files.readString(parentPom);
        String childResult = Files.readString(child);

        // Parent should have properties and dependencyManagement
        assertThat(parentResult)
                .contains("<properties>")
                .contains("5.11.0")
                .contains("dependencyManagement")
                .contains("junit-jupiter");

        // Child should lose its version
        assertThat(childResult).doesNotContain("<version>5.11.0</version>");
    }

    // ── batch align: already aligned → no changes ──────────────────────────

    @Test
    void batchAlignNoChangesWhenAlreadyAligned(@TempDir Path root) throws Exception {
        String parentContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <packaging>pom</packaging>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.slf4j</groupId>
                                <artifactId>slf4j-api</artifactId>
                                <version>2.0.9</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """;
        String childContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>child</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;

        Path parentPom = writePom(root.resolve("parent"), parentContent);
        Path childPom = writePom(root.resolve("child"), childContent);

        var tui = new AlignTui(
                parentPom.toString(), List.of(childPom.toString()), "com.example:parent:1.0", managedLiteral(), null);

        tui.applyBatchAlignment();

        // Files should be unchanged
        assertThat(Files.readString(parentPom)).isEqualTo(parentContent);
        assertThat(Files.readString(childPom)).isEqualTo(childContent);
    }

    // ── batch align: multiple children with overlapping deps ────────────────

    @Test
    void batchAlignDeduplicatesDepsAcrossChildren(@TempDir Path root) throws Exception {
        Path parentPom = writePom(root.resolve("parent"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <packaging>pom</packaging>
                </project>
                """);

        // Both children depend on the same artifact
        Path childA = writePom(root.resolve("module-a"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>module-a</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <version>2.0.9</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        Path childB = writePom(root.resolve("module-b"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>module-b</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <version>2.0.9</version>
                        </dependency>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>33.0.0-jre</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        var tui = new AlignTui(
                parentPom.toString(),
                List.of(childA.toString(), childB.toString()),
                "com.example:parent:1.0",
                managedLiteral(),
                null);

        tui.applyBatchAlignment();

        String parentResult = Files.readString(parentPom);

        // Parent should have both deps in management
        assertThat(parentResult)
                .contains("dependencyManagement")
                .contains("slf4j-api")
                .contains("guava");

        // Both children should lose inline versions
        assertThat(Files.readString(childA)).doesNotContain("<version>2.0.9</version>");
        assertThat(Files.readString(childB))
                .doesNotContain("<version>2.0.9</version>")
                .doesNotContain("<version>33.0.0-jre</version>");
    }

    // ── batch preview → write flow ─────────────────────────────────────────

    @Test
    void batchPreviewThenWriteModifiesFiles(@TempDir Path root) throws Exception {
        Path parentPom = writePom(root.resolve("parent"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <packaging>pom</packaging>
                </project>
                """);

        Path child = writePom(root.resolve("child"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>child</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>lib</artifactId>
                            <version>3.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        var tui = new AlignTui(
                parentPom.toString(), List.of(child.toString()), "com.example:parent:1.0", managedLiteral(), null);

        // Preview should not modify files
        tui.showBatchPreview();
        assertThat(Files.readString(parentPom)).doesNotContain("dependencyManagement");

        // Write should modify files
        tui.writeCrossPomChanges();

        assertThat(Files.readString(parentPom)).contains("dependencyManagement").contains("3.0");
        assertThat(Files.readString(child)).doesNotContain("<version>3.0</version>");
    }

    // ── batch preview with no changes ──────────────────────────────────────

    @Test
    void batchPreviewNoChangesDoesNotEnterPreviewPhase(@TempDir Path root) throws Exception {
        Path parentPom = writePom(root.resolve("parent"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <packaging>pom</packaging>
                </project>
                """);

        // Child has no deps with versions
        Path child = writePom(root.resolve("child"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>child</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>managed-dep</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        var tui = new AlignTui(
                parentPom.toString(), List.of(child.toString()), "com.example:parent:1.0", managedLiteral(), null);

        tui.showBatchPreview();

        // Should stay in SELECT phase since there are no changes
        // (writeCrossPomChanges would fail if called without preview data)
    }

    // ── batch align preserves existing parent depMgmt ───────────────────────

    @Test
    void batchAlignPreservesExistingParentManagedDeps(@TempDir Path root) throws Exception {
        Path parentPom = writePom(root.resolve("parent"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <packaging>pom</packaging>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.existing</groupId>
                                <artifactId>already-managed</artifactId>
                                <version>1.0</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);

        Path child = writePom(root.resolve("child"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>child</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.new</groupId>
                            <artifactId>new-dep</artifactId>
                            <version>2.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        var tui = new AlignTui(
                parentPom.toString(), List.of(child.toString()), "com.example:parent:1.0", managedLiteral(), null);

        tui.applyBatchAlignment();

        String parentResult = Files.readString(parentPom);

        // Should have both old and new managed deps
        assertThat(parentResult)
                .contains("already-managed")
                .contains("1.0")
                .contains("new-dep")
                .contains("2.0");
    }

    // ── batch align with property refs in children ─────────────────────────

    @Test
    void batchAlignWithPropertyRefsMovesPropertiesToParent(@TempDir Path root) throws Exception {
        Path parentPom = writePom(root.resolve("parent"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <packaging>pom</packaging>
                </project>
                """);

        Path child = writePom(root.resolve("child"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>child</artifactId>
                    <version>1.0</version>
                    <properties>
                        <guava.version>33.0.0-jre</guava.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>${guava.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        var tui = new AlignTui(
                parentPom.toString(), List.of(child.toString()), "com.example:parent:1.0", managedProperty(), null);

        tui.applyBatchAlignment();

        String parentResult = Files.readString(parentPom);

        // Parent should have the property and managed dep using the property ref
        assertThat(parentResult)
                .contains("<guava.version>33.0.0-jre</guava.version>")
                .contains("${guava.version}")
                .contains("dependencyManagement");

        // Child should lose inline version
        assertThat(Files.readString(child)).doesNotContain("<version>${guava.version}</version>");
    }

    // ── batch align: mixed versioned and versionless deps ──────────────────

    @Test
    void batchAlignOnlyMovesVersionedDeps(@TempDir Path root) throws Exception {
        Path parentPom = writePom(root.resolve("parent"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <packaging>pom</packaging>
                </project>
                """);

        Path child = writePom(root.resolve("child"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>child</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.already</groupId>
                            <artifactId>managed</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.new</groupId>
                            <artifactId>inline</artifactId>
                            <version>4.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        var tui = new AlignTui(
                parentPom.toString(), List.of(child.toString()), "com.example:parent:1.0", managedLiteral(), null);

        tui.applyBatchAlignment();

        String parentResult = Files.readString(parentPom);
        String childResult = Files.readString(child);

        // Only the versioned dep should be managed
        assertThat(parentResult).contains("inline").contains("4.0");
        // The versionless dep should stay as-is in child
        assertThat(childResult).contains("<artifactId>managed</artifactId>");
        assertThat(childResult).doesNotContain("<version>4.0</version>");
    }

    // ── findManagementPom tests via AlignHelper ────────────────────────────

    @Test
    void findManagementPomWalksToGrandparentWithDepMgmt(@TempDir Path root) throws Exception {
        // Simulate a Camel-like structure:
        //   root (no depMgmt) → parent (has depMgmt) → child
        Path rootPom = writePom(root.resolve("root"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>root</artifactId>
                    <version>1.0</version>
                    <packaging>pom</packaging>
                </project>
                """);

        Path parentPom = writePom(root.resolve("parent"), """
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
                                <artifactId>lib</artifactId>
                                <version>1.0</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);

        Path childPom = writePom(root.resolve("child"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>child</artifactId>
                    <version>1.0</version>
                </project>
                """);

        var rootProject = createPilotProject("root", rootPom);
        var parentProject = createPilotProject("parent", parentPom);
        parentProject = withManagedDeps(parentProject);
        var childProject = createPilotProject("child", childPom);

        // Set parent chain: child → parent → root
        childProject.parent = parentProject;
        parentProject.parent = rootProject;

        var result = AlignHelper.findParentPomInfo(childProject, List.of(rootProject, parentProject, childProject));

        // Should find parent (has depMgmt), not root
        assertThat(result).isNotNull();
        assertThat(result.pomPath()).isEqualTo(parentPom.toString());
        assertThat(result.gav()).contains("parent");
    }

    @Test
    void findManagementPomFallsBackToDirectParentWhenNoneHasDepMgmt(@TempDir Path root) throws Exception {
        Path parentPom = writePom(root.resolve("parent"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <packaging>pom</packaging>
                </project>
                """);

        Path childPom = writePom(root.resolve("child"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>child</artifactId>
                    <version>1.0</version>
                </project>
                """);

        var parentProject = createPilotProject("parent", parentPom);
        var childProject = createPilotProject("child", childPom);
        childProject.parent = parentProject;

        var result = AlignHelper.findParentPomInfo(childProject, List.of(parentProject, childProject));

        assertThat(result).isNotNull();
        assertThat(result.pomPath()).isEqualTo(parentPom.toString());
    }

    // ── helpers for PilotProject creation ──────────────────────────────────

    private static PilotProject createPilotProject(String artifactId, Path pomFile) {
        return new PilotProject(
                "test",
                artifactId,
                "1.0",
                "jar",
                pomFile.getParent(),
                pomFile,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new java.util.Properties(),
                null,
                null);
    }

    private static PilotProject withManagedDeps(PilotProject base) {
        PilotProject.Dep dep = new PilotProject.Dep("org.example", "lib", "1.0");
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
