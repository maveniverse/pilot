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

import eu.maveniverse.domtrip.Document;
import eu.maveniverse.domtrip.maven.AlignOptions;
import eu.maveniverse.domtrip.maven.PomEditor;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AlignTuiTest {

    @Test
    void nextEnumWrapsAround() {
        var values = AlignOptions.VersionStyle.values();
        assertThat(AlignTui.nextEnum(values, AlignOptions.VersionStyle.INLINE))
                .isEqualTo(AlignOptions.VersionStyle.MANAGED);
        assertThat(AlignTui.nextEnum(values, AlignOptions.VersionStyle.MANAGED))
                .isEqualTo(AlignOptions.VersionStyle.INLINE);
    }

    @Test
    void prevEnumWrapsAround() {
        var values = AlignOptions.VersionStyle.values();
        assertThat(AlignTui.prevEnum(values, AlignOptions.VersionStyle.INLINE))
                .isEqualTo(AlignOptions.VersionStyle.MANAGED);
        assertThat(AlignTui.prevEnum(values, AlignOptions.VersionStyle.MANAGED))
                .isEqualTo(AlignOptions.VersionStyle.INLINE);
    }

    @Test
    void nextEnumCyclesAllPropertyNamingValues() {
        var values = AlignOptions.PropertyNamingConvention.values();
        var current = AlignOptions.PropertyNamingConvention.DOT_SUFFIX;
        current = AlignTui.nextEnum(values, current);
        assertThat(current).isEqualTo(AlignOptions.PropertyNamingConvention.DASH_SUFFIX);
        current = AlignTui.nextEnum(values, current);
        assertThat(current).isEqualTo(AlignOptions.PropertyNamingConvention.CAMEL_CASE);
        current = AlignTui.nextEnum(values, current);
        assertThat(current).isEqualTo(AlignOptions.PropertyNamingConvention.DOT_PREFIX);
        current = AlignTui.nextEnum(values, current);
        assertThat(current).isEqualTo(AlignOptions.PropertyNamingConvention.DOT_SUFFIX);
    }

    @Test
    void buildSelectedOptionsReflectsDefaults() {
        var detected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.PROPERTY)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();

        var tui = new AlignTui("/tmp/pom.xml", "g:a:1.0", detected, null);
        var options = tui.buildSelectedOptions();

        assertThat(options.versionStyle()).isEqualTo(AlignOptions.VersionStyle.MANAGED);
        assertThat(options.versionSource()).isEqualTo(AlignOptions.VersionSource.PROPERTY);
        assertThat(options.namingConvention()).isEqualTo(AlignOptions.PropertyNamingConvention.DOT_SUFFIX);
    }

    @Test
    void cycleForwardChangesSelectedStyle() {
        var detected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.INLINE)
                .versionSource(AlignOptions.VersionSource.LITERAL)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();

        var tui = new AlignTui("/tmp/pom.xml", "g:a:1.0", detected, null);
        tui.cycleForward(); // row 0 = VersionStyle

        var options = tui.buildSelectedOptions();
        assertThat(options.versionStyle()).isEqualTo(AlignOptions.VersionStyle.MANAGED);
    }

    @Test
    void cycleBackwardChangesSelectedStyle() {
        var detected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.LITERAL)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();

        var tui = new AlignTui("/tmp/pom.xml", "g:a:1.0", detected, null);
        tui.cycleBackward(); // row 0 = VersionStyle

        var options = tui.buildSelectedOptions();
        assertThat(options.versionStyle()).isEqualTo(AlignOptions.VersionStyle.INLINE);
    }

    // -- Cross-POM alignment tests --

    @Test
    void constructorWithParentMergesConventionsWhenManaged() {
        var childDetected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.LITERAL)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();
        var parentDetected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.INLINE)
                .versionSource(AlignOptions.VersionSource.PROPERTY)
                .namingConvention(AlignOptions.PropertyNamingConvention.CAMEL_CASE)
                .build();
        var parentInfo = new AlignTui.ParentPomInfo("/tmp/parent/pom.xml", "g:parent:1.0", parentDetected);

        var tui = new AlignTui("/tmp/child/pom.xml", "g:child:1.0", childDetected, parentInfo);
        var options = tui.buildSelectedOptions();

        assertThat(options.versionStyle()).isEqualTo(AlignOptions.VersionStyle.MANAGED);
        assertThat(options.versionSource()).isEqualTo(AlignOptions.VersionSource.PROPERTY);
        assertThat(options.namingConvention()).isEqualTo(AlignOptions.PropertyNamingConvention.CAMEL_CASE);
    }

    @Test
    void constructorWithParentKeepsChildConventionsWhenNotManaged() {
        var childDetected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.INLINE)
                .versionSource(AlignOptions.VersionSource.LITERAL)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();
        var parentDetected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.PROPERTY)
                .namingConvention(AlignOptions.PropertyNamingConvention.CAMEL_CASE)
                .build();
        var parentInfo = new AlignTui.ParentPomInfo("/tmp/parent/pom.xml", "g:parent:1.0", parentDetected);

        var tui = new AlignTui("/tmp/child/pom.xml", "g:child:1.0", childDetected, parentInfo);
        var options = tui.buildSelectedOptions();

        assertThat(options.versionStyle()).isEqualTo(AlignOptions.VersionStyle.INLINE);
        assertThat(options.versionSource()).isEqualTo(AlignOptions.VersionSource.LITERAL);
        assertThat(options.namingConvention()).isEqualTo(AlignOptions.PropertyNamingConvention.DOT_SUFFIX);
    }

    @Test
    void alignCrossPomMovesVersionsToParent() {
        String childPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>child</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>foo</artifactId>
                            <version>2.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        String parentPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <packaging>pom</packaging>
                </project>
                """;

        var detected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.LITERAL)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();
        PomEditor childEditor = new PomEditor(Document.of(childPom));
        PomEditor parentEditor = new PomEditor(Document.of(parentPom));

        int count = childEditor.dependencies().alignAllToParent(parentEditor, detected);

        assertThat(count).isEqualTo(1);
        assertThat(childEditor.toXml()).doesNotContain("<version>2.0</version>");
        assertThat(parentEditor.toXml())
                .contains("dependencyManagement")
                .contains("org.example")
                .contains("foo")
                .contains("2.0");
    }

    @Test
    void alignCrossPomReturnsZeroForNoDeps() {
        String childPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>child</artifactId>
                    <version>1.0</version>
                </project>
                """;
        String parentPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <packaging>pom</packaging>
                </project>
                """;

        var detected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.LITERAL)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();
        PomEditor childEditor = new PomEditor(Document.of(childPom));
        PomEditor parentEditor = new PomEditor(Document.of(parentPom));

        int count = childEditor.dependencies().alignAllToParent(parentEditor, detected);
        assertThat(count).isZero();
    }

    @Test
    void alignCrossPomHandlesMultipleDependencies() {
        String childPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>child</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>foo</artifactId>
                            <version>2.0</version>
                        </dependency>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>bar</artifactId>
                            <version>3.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        String parentPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <packaging>pom</packaging>
                </project>
                """;

        var detected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.LITERAL)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();
        PomEditor childEditor = new PomEditor(Document.of(childPom));
        PomEditor parentEditor = new PomEditor(Document.of(parentPom));

        int count = childEditor.dependencies().alignAllToParent(parentEditor, detected);

        assertThat(count).isEqualTo(2);
        assertThat(parentEditor.toXml())
                .contains("foo")
                .contains("bar")
                .contains("2.0")
                .contains("3.0");
    }

    @Test
    void alignCrossPomSkipsVersionlessDependencies() {
        String childPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>child</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>managed</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>foo</artifactId>
                            <version>2.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        String parentPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <packaging>pom</packaging>
                </project>
                """;

        var detected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.LITERAL)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();
        PomEditor childEditor = new PomEditor(Document.of(childPom));
        PomEditor parentEditor = new PomEditor(Document.of(parentPom));

        int count = childEditor.dependencies().alignAllToParent(parentEditor, detected);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void alignCrossPomWithPropertySourceCreatesPropertyInParent() {
        String childPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>child</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>foo</artifactId>
                            <version>2.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        String parentPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <packaging>pom</packaging>
                </project>
                """;

        var detected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.PROPERTY)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();
        PomEditor childEditor = new PomEditor(Document.of(childPom));
        PomEditor parentEditor = new PomEditor(Document.of(parentPom));

        int count = childEditor.dependencies().alignAllToParent(parentEditor, detected);

        assertThat(count).isEqualTo(1);
        assertThat(parentEditor.toXml())
                .contains("<properties>")
                .contains("2.0")
                .contains("dependencyManagement");
    }

    @Test
    void alignCrossPomReusesExistingPropertyReference() {
        String childPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>child</artifactId>
                    <version>1.0</version>
                    <properties>
                        <foo.version>2.0</foo.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>foo</artifactId>
                            <version>${foo.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        String parentPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <packaging>pom</packaging>
                </project>
                """;

        var detected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.PROPERTY)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();
        PomEditor childEditor = new PomEditor(Document.of(childPom));
        PomEditor parentEditor = new PomEditor(Document.of(parentPom));

        int count = childEditor.dependencies().alignAllToParent(parentEditor, detected);

        assertThat(count).isEqualTo(1);
        assertThat(parentEditor.toXml()).contains("foo.version").contains("2.0").contains("${foo.version}");
    }

    // -- isCrossPomMode tests --

    @Test
    void isCrossPomModeReturnsTrueWhenManagedWithParent() {
        var detected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.LITERAL)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();
        var parentInfo = new AlignTui.ParentPomInfo("/tmp/parent/pom.xml", "g:parent:1.0", detected);
        var tui = new AlignTui("/tmp/child/pom.xml", "g:child:1.0", detected, parentInfo);

        assertThat(tui.isCrossPomMode()).isTrue();
    }

    @Test
    void isCrossPomModeReturnsFalseWhenNoParent() {
        var detected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.LITERAL)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();
        var tui = new AlignTui("/tmp/child/pom.xml", "g:child:1.0", detected, null);

        assertThat(tui.isCrossPomMode()).isFalse();
    }

    @Test
    void isCrossPomModeReturnsFalseWhenNotManaged() {
        var detected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.INLINE)
                .versionSource(AlignOptions.VersionSource.LITERAL)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();
        var parentInfo = new AlignTui.ParentPomInfo("/tmp/parent/pom.xml", "g:parent:1.0", detected);
        var tui = new AlignTui("/tmp/child/pom.xml", "g:child:1.0", detected, parentInfo);

        assertThat(tui.isCrossPomMode()).isFalse();
    }

    // -- File-based cross-POM tests --

    private static final String CHILD_POM_WITH_VERSION = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>test</groupId>
                <artifactId>child</artifactId>
                <version>1.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>foo</artifactId>
                        <version>2.0</version>
                    </dependency>
                </dependencies>
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
    void applyCrossPomAlignmentWritesBothFiles(@TempDir Path tempDir) throws Exception {
        Path childPom = tempDir.resolve("child/pom.xml");
        Path parentPom = tempDir.resolve("parent/pom.xml");
        Files.createDirectories(childPom.getParent());
        Files.createDirectories(parentPom.getParent());
        Files.writeString(childPom, CHILD_POM_WITH_VERSION);
        Files.writeString(parentPom, EMPTY_PARENT_POM);

        var detected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.LITERAL)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();
        var parentInfo = new AlignTui.ParentPomInfo(parentPom.toString(), "g:parent:1.0", detected);
        var tui = new AlignTui(childPom.toString(), "g:child:1.0", detected, parentInfo);

        tui.applyCrossPomAlignment();

        String resultChild = Files.readString(childPom);
        String resultParent = Files.readString(parentPom);

        assertThat(resultChild).doesNotContain("<version>2.0</version>");
        assertThat(resultParent).contains("dependencyManagement").contains("2.0");
    }

    @Test
    void crossPomPreviewThenWriteModifiesBothFiles(@TempDir Path tempDir) throws Exception {
        Path childPom = tempDir.resolve("child/pom.xml");
        Path parentPom = tempDir.resolve("parent/pom.xml");
        Files.createDirectories(childPom.getParent());
        Files.createDirectories(parentPom.getParent());
        Files.writeString(childPom, CHILD_POM_WITH_VERSION);
        Files.writeString(parentPom, EMPTY_PARENT_POM);

        var detected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.LITERAL)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();
        var parentInfo = new AlignTui.ParentPomInfo(parentPom.toString(), "g:parent:1.0", detected);
        var tui = new AlignTui(childPom.toString(), "g:child:1.0", detected, parentInfo);

        tui.showCrossPomPreview();
        tui.writeCrossPomChanges();

        String resultChild = Files.readString(childPom);
        String resultParent = Files.readString(parentPom);

        assertThat(resultChild).doesNotContain("<version>2.0</version>");
        assertThat(resultParent).contains("dependencyManagement").contains("2.0");
    }

    @Test
    void crossPomPreviewNoChangesWhenAlreadyAligned(@TempDir Path tempDir) throws Exception {
        String alignedChild = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>child</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>foo</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;

        Path childPom = tempDir.resolve("child/pom.xml");
        Path parentPom = tempDir.resolve("parent/pom.xml");
        Files.createDirectories(childPom.getParent());
        Files.createDirectories(parentPom.getParent());
        Files.writeString(childPom, alignedChild);
        Files.writeString(parentPom, EMPTY_PARENT_POM);

        var detected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.LITERAL)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();
        var parentInfo = new AlignTui.ParentPomInfo(parentPom.toString(), "g:parent:1.0", detected);
        var tui = new AlignTui(childPom.toString(), "g:child:1.0", detected, parentInfo);

        tui.showCrossPomPreview();

        // Files should be unchanged
        assertThat(Files.readString(childPom)).isEqualTo(alignedChild);
        assertThat(Files.readString(parentPom)).isEqualTo(EMPTY_PARENT_POM);
    }

    // -- Constructor convention inheritance tests --

    @Test
    void constructorInheritsParentConventionsWhenManaged() {
        var childDetected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.LITERAL)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();
        var parentDetected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.PROPERTY)
                .namingConvention(AlignOptions.PropertyNamingConvention.CAMEL_CASE)
                .build();
        var parentInfo = new AlignTui.ParentPomInfo("/tmp/parent/pom.xml", "g:parent:1.0", parentDetected);
        var tui = new AlignTui("/tmp/child/pom.xml", "g:child:1.0", childDetected, parentInfo);

        var options = tui.buildSelectedOptions();
        assertThat(options.versionStyle()).isEqualTo(AlignOptions.VersionStyle.MANAGED);
        assertThat(options.versionSource()).isEqualTo(AlignOptions.VersionSource.PROPERTY);
        assertThat(options.namingConvention()).isEqualTo(AlignOptions.PropertyNamingConvention.CAMEL_CASE);
    }

    @Test
    void constructorKeepsChildConventionsWhenInline() {
        var childDetected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.INLINE)
                .versionSource(AlignOptions.VersionSource.LITERAL)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();
        var parentDetected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.PROPERTY)
                .namingConvention(AlignOptions.PropertyNamingConvention.CAMEL_CASE)
                .build();
        var parentInfo = new AlignTui.ParentPomInfo("/tmp/parent/pom.xml", "g:parent:1.0", parentDetected);
        var tui = new AlignTui("/tmp/child/pom.xml", "g:child:1.0", childDetected, parentInfo);

        var options = tui.buildSelectedOptions();
        assertThat(options.versionSource()).isEqualTo(AlignOptions.VersionSource.LITERAL);
        assertThat(options.namingConvention()).isEqualTo(AlignOptions.PropertyNamingConvention.DOT_SUFFIX);
    }

    // -- alignCrossPom edge cases --

    @Test
    void alignCrossPomReturnsZeroWhenNoDependenciesElement() {
        String childPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>child</artifactId>
                    <version>1.0</version>
                </project>
                """;
        String parentPom = EMPTY_PARENT_POM;

        var detected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.LITERAL)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();
        PomEditor childEditor = new PomEditor(Document.of(childPom));
        PomEditor parentEditor = new PomEditor(Document.of(parentPom));

        assertThat(childEditor.dependencies().alignAllToParent(parentEditor, detected))
                .isZero();
    }

    @Test
    void alignCrossPomSkipsDependenciesWithoutVersion() {
        String childPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>child</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>foo</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;
        String parentPom = EMPTY_PARENT_POM;

        var detected = AlignOptions.builder()
                .versionStyle(AlignOptions.VersionStyle.MANAGED)
                .versionSource(AlignOptions.VersionSource.LITERAL)
                .namingConvention(AlignOptions.PropertyNamingConvention.DOT_SUFFIX)
                .build();
        PomEditor childEditor = new PomEditor(Document.of(childPom));
        PomEditor parentEditor = new PomEditor(Document.of(parentPom));

        assertThat(childEditor.dependencies().alignAllToParent(parentEditor, detected))
                .isZero();
    }
}
