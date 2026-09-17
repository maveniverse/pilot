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

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassFileScannerTest {

    @Test
    void scanDirectoryFindsClasses() throws Exception {
        Path testClasses = Path.of("target/test-classes");
        Assumptions.assumeTrue(
                Files.isDirectory(testClasses), "target/test-classes not found — skipping bytecode scan test");

        ClassFileScanner.ScanResult result = ClassFileScanner.scanDirectory(testClasses);

        // Should find references from all test classes
        assertThat(result.referencedClasses()).isNotEmpty().contains("java.lang.Object");
    }

    @Test
    void scanDirectoryFindsMemberReferences() throws Exception {
        Path testClasses = Path.of("target/test-classes");
        Assumptions.assumeTrue(
                Files.isDirectory(testClasses), "target/test-classes not found — skipping bytecode scan test");

        ClassFileScanner.ScanResult result = ClassFileScanner.scanDirectory(testClasses);

        // Should find member-level references (e.g. method calls on Path, assertThat, etc.)
        assertThat(result.memberReferences()).isNotEmpty();
        // This test class calls Path.of(), so we should see it
        assertThat(result.referencedClasses()).contains("java.nio.file.Path");
    }

    @Test
    void scanEmptyDirectory(@TempDir Path tempDir) throws Exception {
        ClassFileScanner.ScanResult result = ClassFileScanner.scanDirectory(tempDir);
        assertThat(result.referencedClasses()).isEmpty();
        assertThat(result.memberReferences()).isEmpty();
    }

    @Test
    void referencedClassesFiltersArrayDescriptors() throws Exception {
        Path mainClasses = Path.of("target/classes");
        Assumptions.assumeTrue(
                Files.isDirectory(mainClasses), "target/classes not found — skipping bytecode scan test");

        ClassFileScanner.ScanResult result = ClassFileScanner.scanDirectory(mainClasses);

        // No class name should start with '[' (array descriptors should be filtered)
        assertThat(result.referencedClasses())
                .noneMatch(name -> name.startsWith("["))
                // No class name should contain '/' (should be dot-separated)
                .noneMatch(name -> name.contains("/"));
    }

    /**
     * Regression test for the inlined-constants false-positive removal bug:
     * {@link ClassFileScanner#hasInlineableConstants} must return {@code true} for a JAR that
     * contains a class with {@code public static final} String/int fields (ConstantValue
     * attribute), and {@code false} for a JAR that has no such fields.
     *
     * <p>The fixture class {@link ConstantProviderFixture} is compiled as part of the test
     * sources, so it is already in {@code target/test-classes}.  We create a one-class JAR from
     * it and verify the detector fires, then verify it does NOT fire on an empty JAR.</p>
     */
    @Test
    void hasInlineableConstantsDetectsConstantValueAttribute(@TempDir Path tempDir) throws Exception {
        Path testClasses = Path.of("target/test-classes");
        Assumptions.assumeTrue(
                Files.isDirectory(testClasses), "target/test-classes not found — skipping bytecode scan test");

        // Build a JAR that contains ConstantProviderFixture (which has public static final fields)
        Path constantsJar = tempDir.resolve("constants.jar");
        Path classFile = testClasses.resolve("eu/maveniverse/maven/pilot/ConstantProviderFixture.class");
        Assumptions.assumeTrue(Files.exists(classFile), "ConstantProviderFixture.class not compiled — skipping");

        try (OutputStream os = Files.newOutputStream(constantsJar);
                JarOutputStream jos = new JarOutputStream(os)) {
            jos.putNextEntry(new JarEntry("eu/maveniverse/maven/pilot/ConstantProviderFixture.class"));
            Files.copy(classFile, jos);
            jos.closeEntry();
        }

        assertThat(ClassFileScanner.hasInlineableConstants(constantsJar.toFile()))
                .as("JAR with public static final String/int fields must be detected as having inlineable constants")
                .isTrue();

        // An empty JAR (no classes) must NOT trigger the detector
        Path emptyJar = tempDir.resolve("empty.jar");
        try (OutputStream os = Files.newOutputStream(emptyJar);
                JarOutputStream jos = new JarOutputStream(os)) {
            // intentionally empty
        }
        assertThat(ClassFileScanner.hasInlineableConstants(emptyJar.toFile()))
                .as("Empty JAR must NOT be detected as having inlineable constants")
                .isFalse();
    }

    /**
     * Verifies that a JAR whose classes have no {@code public static final} constant fields
     * is NOT flagged as having inlineable constants.  {@link AnnotationFixture} has only
     * instance fields and no static fields at all, so it serves as a representative class
     * that should never trigger the detector.
     */
    @Test
    void hasInlineableConstantsIgnoresNonConstantClasses(@TempDir Path tempDir) throws Exception {
        // We reuse the AnnotationFixture class, which has only instance fields — no static
        // fields at all — and therefore no ConstantValue attributes in its class file.
        Path testClasses = Path.of("target/test-classes");
        Assumptions.assumeTrue(
                Files.isDirectory(testClasses), "target/test-classes not found — skipping bytecode scan test");

        Path classFile = testClasses.resolve("eu/maveniverse/maven/pilot/AnnotationFixture.class");
        Assumptions.assumeTrue(Files.exists(classFile), "AnnotationFixture.class not compiled — skipping");

        Path noConstantsJar = tempDir.resolve("no-constants.jar");
        try (OutputStream os = Files.newOutputStream(noConstantsJar);
                JarOutputStream jos = new JarOutputStream(os)) {
            jos.putNextEntry(new JarEntry("eu/maveniverse/maven/pilot/AnnotationFixture.class"));
            Files.copy(classFile, jos);
            jos.closeEntry();
        }

        assertThat(ClassFileScanner.hasInlineableConstants(noConstantsJar.toFile()))
                .as("JAR without public static final constant fields must NOT be flagged")
                .isFalse();
    }

    /**
     * Verifies that a JAR whose classes have <em>only</em> non-public {@code static final}
     * constant fields is NOT flagged as having inlineable constants.
     *
     * <p>{@link NonPublicConstantFixture} contains only package-private and private
     * {@code static final} fields.  javac still emits a {@code ConstantValue} attribute
     * for each (JVMS §4.7.2), but they cannot be inlined by external consumers because
     * they lack the {@code public} modifier.  The scanner's {@code ACC_PUBLIC} guard must
     * reject them.</p>
     *
     * <p>This test distinguishes the non-public-constants case from the no-constants case
     * covered by {@link #hasInlineableConstantsIgnoresNonConstantClasses}: a regression
     * that accidentally removed the {@code ACC_PUBLIC} mask check would still pass the
     * {@code AnnotationFixture}-based test (which has no static fields at all) but would
     * fail here.</p>
     */
    @Test
    void hasInlineableConstantsIgnoresNonPublicConstants(@TempDir Path tempDir) throws Exception {
        Path testClasses = Path.of("target/test-classes");
        Assumptions.assumeTrue(
                Files.isDirectory(testClasses), "target/test-classes not found — skipping bytecode scan test");

        Path classFile = testClasses.resolve("eu/maveniverse/maven/pilot/NonPublicConstantFixture.class");
        Assumptions.assumeTrue(Files.exists(classFile), "NonPublicConstantFixture.class not compiled — skipping");

        Path nonPublicJar = tempDir.resolve("non-public-constants.jar");
        try (OutputStream os = Files.newOutputStream(nonPublicJar);
                JarOutputStream jos = new JarOutputStream(os)) {
            jos.putNextEntry(new JarEntry("eu/maveniverse/maven/pilot/NonPublicConstantFixture.class"));
            Files.copy(classFile, jos);
            jos.closeEntry();
        }

        assertThat(ClassFileScanner.hasInlineableConstants(nonPublicJar.toFile()))
                .as(
                        "JAR with only non-public static final constants must NOT be flagged as having inlineable constants")
                .isFalse();
    }

    @Test
    void formatDescriptorHumanReadable() {
        assertThat(ClassFileScanner.formatDescriptor("()V")).isEqualTo("()");
        assertThat(ClassFileScanner.formatDescriptor("(Ljava/lang/String;)V")).isEqualTo("(String)");
        assertThat(ClassFileScanner.formatDescriptor("(Ljava/lang/String;I)Z")).isEqualTo("(String, int)");
        assertThat(ClassFileScanner.formatDescriptor("([Ljava/lang/Object;)V")).isEqualTo("(Object[])");
    }

    /**
     * Regression test for <a href="https://github.com/maveniverse/pilot/issues/158">issue #158</a>:
     * the bytecode scanner must detect annotation types used only as annotations (not appearing in
     * method/field descriptors or call sites).
     *
     * <p>Specifically verifies:
     * <ul>
     *   <li>Method-level annotation types are collected ({@code @Test} on methods in this class)</li>
     *   <li>Field-level annotation types are collected ({@link org.junit.jupiter.api.io.TempDir}
     *       on a field in {@link AnnotationFixture}) — tests {@code FieldVisitor.visitAnnotation}</li>
     *   <li>Annotation element {@code Class[]} literals are collected
     *       ({@link AnnotationFixture.NoopExtension} referenced as the value of {@code @ExtendWith}
     *       on {@link AnnotationFixture}) — tests {@code annotationScanner().visit(name, Type)} +
     *       {@code visitArray}</li>
     * </ul>
     */
    @Test
    void scanDetectsAnnotationOnlyDependencies() throws Exception {
        Path testClasses = Path.of("target/test-classes");
        Assumptions.assumeTrue(
                Files.isDirectory(testClasses), "target/test-classes not found — skipping bytecode scan test");

        ClassFileScanner.ScanResult result = ClassFileScanner.scanDirectory(testClasses);

        // @Test is a method-level annotation used in many test classes — must be detected
        assertThat(result.referencedClasses())
                .as("method-level annotation type @Test must be detected")
                .contains("org.junit.jupiter.api.Test");

        // @TempDir is used as a field annotation in AnnotationFixture — tests the field-annotation path
        assertThat(result.referencedClasses())
                .as("field-level annotation type @TempDir must be detected (regression: issue #158)")
                .contains("org.junit.jupiter.api.io.TempDir");

        // NoopExtension is a Class<?> literal in @ExtendWith(NoopExtension.class) on AnnotationFixture
        // — tests the annotation element-value scanning path (visitArray + visit(name, Type))
        assertThat(result.referencedClasses())
                .as("Class[] annotation element value must be detected (regression: issue #158)")
                .contains("eu.maveniverse.maven.pilot.AnnotationFixture$NoopExtension");
    }
}
