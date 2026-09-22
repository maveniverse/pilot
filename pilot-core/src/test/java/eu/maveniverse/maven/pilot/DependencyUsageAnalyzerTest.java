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

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyUsageAnalyzerTest {

    private static void createJarWithEntries(Path jarPath, String... entries) throws IOException {
        try (OutputStream os = Files.newOutputStream(jarPath);
                JarOutputStream jos = new JarOutputStream(os)) {
            for (String entry : entries) {
                jos.putNextEntry(new JarEntry(entry));
                jos.closeEntry();
            }
        }
    }

    @Test
    void usedDeclaredDependency() {
        var dep = new DependenciesTui.DepEntry("com.example", "used-lib", "", "1.0", "compile", true);

        Map<String, String> classIndex = Map.of("com.example.Foo", "com.example:used-lib");
        Map<String, File> gaToJar = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of("com.example.Foo"), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("com.example:used-lib", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void unusedDeclaredDependency() {
        var dep = new DependenciesTui.DepEntry("com.example", "unused-lib", "", "1.0", "compile", true);

        Map<String, String> classIndex = Map.of("com.example.Bar", "com.example:unused-lib");
        Map<String, File> gaToJar = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of("com.other.Unrelated"), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("com.example:unused-lib", DependencyUsageAnalyzer.UsageStatus.UNUSED);
    }

    @Test
    void usedTransitiveDependency() {
        var dep = new DependenciesTui.DepEntry("com.transitive", "lib", "", "1.0", "compile", false);
        dep.pulledBy = "com.example:parent";

        Map<String, String> classIndex = Map.of("com.transitive.Helper", "com.transitive:lib");
        Map<String, File> gaToJar = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of("com.transitive.Helper"), Set.of(), classIndex, gaToJar, List.of(), List.of(dep), true);

        assertThat(result.transitiveUsage())
                .containsEntry("com.transitive:lib", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void unusedTransitiveDependency() {
        var dep = new DependenciesTui.DepEntry("com.transitive", "lib", "", "1.0", "compile", false);

        Map<String, String> classIndex = Map.of("com.transitive.Helper", "com.transitive:lib");
        Map<String, File> gaToJar = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of("com.other.Unrelated"), Set.of(), classIndex, gaToJar, List.of(), List.of(dep), true);

        assertThat(result.transitiveUsage())
                .containsEntry("com.transitive:lib", DependencyUsageAnalyzer.UsageStatus.UNUSED);
    }

    @Test
    void transitiveRuntimeArtifactAllowlistMarksAsUsed() {
        var dep = new DependenciesTui.DepEntry("org.postgresql", "postgresql", "", "42.7", "runtime", false);

        Map<String, String> classIndex = Map.of("org.postgresql.Driver", "org.postgresql:postgresql");
        Map<String, File> gaToJar = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .runtimeArtifacts(Set.of("org.postgresql:postgresql"))
                .build()
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(), List.of(dep), true);

        assertThat(result.transitiveUsage())
                .containsEntry("org.postgresql:postgresql", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void testScopedDepCheckedAgainstTestRefs() {
        var dep = new DependenciesTui.DepEntry("org.junit", "junit-api", "", "5.0", "test", true);

        Map<String, String> classIndex = Map.of("org.junit.Test", "org.junit:junit-api");
        Map<String, File> gaToJar = Map.of();

        // Not in main refs but in test refs — should be USED
        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of(), Set.of("org.junit.Test"), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("org.junit:junit-api", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void testOnlyScopedDepCheckedAgainstTestRefs() {
        var dep = new DependenciesTui.DepEntry("org.junit", "junit-api", "", "5.0", "test-only", true);

        Map<String, String> classIndex = Map.of("org.junit.Test", "org.junit:junit-api");
        Map<String, File> gaToJar = Map.of();

        // Maven 4 "test-only" scope: should be checked against allRefs (main + test)
        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of(), Set.of("org.junit.Test"), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("org.junit:junit-api", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void testRuntimeScopedDepCheckedAgainstTestRefs() {
        var dep = new DependenciesTui.DepEntry("org.example", "test-util", "", "1.0", "test-runtime", true);

        Map<String, String> classIndex = Map.of("org.example.TestUtil", "org.example:test-util");
        Map<String, File> gaToJar = Map.of();

        // Maven 4 "test-runtime" scope: should be checked against allRefs (main + test)
        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of(), Set.of("org.example.TestUtil"), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("org.example:test-util", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void compileScopedDepUsedOnlyInTestsClassifiedAsUsedInTest() {
        var dep = new DependenciesTui.DepEntry("com.example", "lib", "", "1.0", "compile", true);

        Map<String, String> classIndex = Map.of("com.example.Foo", "com.example:lib");
        Map<String, File> gaToJar = Map.of();

        // Only in test refs, not main refs — compile-scoped dep should be USED_IN_TEST (scope narrowing)
        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of(), Set.of("com.example.Foo"), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("com.example:lib", DependencyUsageAnalyzer.UsageStatus.USED_IN_TEST);
    }

    @Test
    void undeterminedWhenNoClassesInIndex() {
        var dep = new DependenciesTui.DepEntry("com.mystery", "lib", "", "1.0", "compile", true);

        // No classes mapped to this GA in the index
        Map<String, String> classIndex = Map.of();
        Map<String, File> gaToJar = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of("com.example.Foo"), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("com.mystery:lib", DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
    }

    @Test
    void buildClassIndexFromJar(@TempDir Path tempDir) throws Exception {
        Path tempJar = tempDir.resolve("index-test.jar");
        createJarWithEntries(
                tempJar,
                "com/example/Foo.class",
                "com/example/Bar.class",
                "module-info.class", // should be excluded
                "META-INF/versions/17/com/example/Foo.class"); // should be excluded

        Map<String, File> gaToJar = new HashMap<>();
        gaToJar.put("test:artifact", tempJar.toFile());

        Map<String, String> index = DependencyUsageAnalyzer.buildClassIndex(gaToJar);

        assertThat(index)
                .containsEntry("com.example.Foo", "test:artifact")
                .containsEntry("com.example.Bar", "test:artifact")
                .hasSize(2);
    }

    @Test
    void serviceLoaderDiscoveryMarksAsUsed(@TempDir Path tempDir) throws Exception {
        Path tempJar = tempDir.resolve("service-lib.jar");
        createJarWithEntries(tempJar, "META-INF/services/com.example.SomeService");

        var dep = new DependenciesTui.DepEntry("com.example", "service-lib", "", "1.0", "compile", true);
        Map<String, File> gaToJar = Map.of("com.example:service-lib", tempJar.toFile());
        Map<String, String> classIndex = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(
                        Set.of("com.example.SomeService"),
                        Set.of(),
                        classIndex,
                        gaToJar,
                        List.of(dep),
                        List.of(),
                        true);

        assertThat(result.declaredUsage())
                .containsEntry("com.example:service-lib", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void annotationProcessorProvidedScopeMarkedAsUsed(@TempDir Path tempDir) throws Exception {
        Path tempJar = tempDir.resolve("processor.jar");
        createJarWithEntries(tempJar, "META-INF/services/javax.annotation.processing.Processor");

        var dep = new DependenciesTui.DepEntry("com.example", "processor", "", "1.0", "provided", true);
        Map<String, File> gaToJar = Map.of("com.example:processor", tempJar.toFile());
        Map<String, String> classIndex = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("com.example:processor", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void annotationProcessorCompileScopeMarkedAsUsed(@TempDir Path tempDir) throws Exception {
        Path tempJar = tempDir.resolve("lombok.jar");
        createJarWithEntries(tempJar, "META-INF/services/javax.annotation.processing.Processor");

        var dep = new DependenciesTui.DepEntry("org.projectlombok", "lombok", "", "1.18", "compile", true);
        Map<String, File> gaToJar = Map.of("org.projectlombok:lombok", tempJar.toFile());
        Map<String, String> classIndex = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("org.projectlombok:lombok", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void annotationProcessorTestScopeMarkedAsUsed(@TempDir Path tempDir) throws Exception {
        // jmh-generator-annprocess is typically declared with test scope; it generates benchmark
        // infrastructure at compile time but leaves no bytecode trace in the compiled output.
        Path tempJar = tempDir.resolve("jmh-generator-annprocess.jar");
        createJarWithEntries(tempJar, "META-INF/services/javax.annotation.processing.Processor");

        var dep = new DependenciesTui.DepEntry("org.openjdk.jmh", "jmh-generator-annprocess", "", "1.37", "test", true);
        Map<String, File> gaToJar = Map.of("org.openjdk.jmh:jmh-generator-annprocess", tempJar.toFile());
        Map<String, String> classIndex = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("org.openjdk.jmh:jmh-generator-annprocess", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void annotationProcessorTestOnlyScopeMarkedAsUsed(@TempDir Path tempDir) throws Exception {
        // Maven 4 "test-only" is the equivalent of "test" for annotation processors
        // that are only needed during test compilation.
        Path tempJar = tempDir.resolve("test-only-processor.jar");
        createJarWithEntries(tempJar, "META-INF/services/javax.annotation.processing.Processor");

        var dep = new DependenciesTui.DepEntry("com.example", "test-processor", "", "1.0", "test-only", true);
        Map<String, File> gaToJar = Map.of("com.example:test-processor", tempJar.toFile());
        Map<String, String> classIndex = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("com.example:test-processor", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void annotationProcessorCompileOnlyScopeMarkedAsUsed(@TempDir Path tempDir) throws Exception {
        // Maven 4 "compile-only" is the idiomatic scope for annotation processors.
        Path tempJar = tempDir.resolve("processor.jar");
        createJarWithEntries(tempJar, "META-INF/services/javax.annotation.processing.Processor");

        var dep = new DependenciesTui.DepEntry("com.example", "my-processor", "", "1.0", "compile-only", true);
        Map<String, File> gaToJar = Map.of("com.example:my-processor", tempJar.toFile());
        Map<String, String> classIndex = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("com.example:my-processor", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void annotationProcessorRuntimeScopeNotMarkedAsUsed(@TempDir Path tempDir) throws Exception {
        // A JAR with a processor SPI entry but declared "runtime" scope is not an intentional
        // annotation processor dependency and should NOT be exempted from the unused check.
        Path tempJar = tempDir.resolve("runtime-with-processor-spi.jar");
        createJarWithEntries(tempJar, "META-INF/services/javax.annotation.processing.Processor");

        var dep = new DependenciesTui.DepEntry("com.example", "runtime-lib", "", "1.0", "runtime", true);
        Map<String, File> gaToJar = Map.of("com.example:runtime-lib", tempJar.toFile());
        Map<String, String> classIndex = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        // Should be UNDETERMINED (no classes in index), NOT USED
        assertThat(result.declaredUsage())
                .containsEntry("com.example:runtime-lib", DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
    }

    @Test
    void annotationProcessorTestRuntimeScopeNotMarkedAsUsed(@TempDir Path tempDir) throws Exception {
        // test-runtime sits adjacent to test-only in TEST_SCOPES but must NOT be in
        // ANNOTATION_PROCESSOR_SCOPES: a test-runtime dep is only on the test runtime classpath,
        // not the test compile classpath, so javac would never invoke it as a processor.
        Path tempJar = tempDir.resolve("test-runtime-with-processor-spi.jar");
        createJarWithEntries(tempJar, "META-INF/services/javax.annotation.processing.Processor");

        var dep = new DependenciesTui.DepEntry("com.example", "test-runtime-lib", "", "1.0", "test-runtime", true);
        Map<String, File> gaToJar = Map.of("com.example:test-runtime-lib", tempJar.toFile());
        Map<String, String> classIndex = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        // Should be UNDETERMINED (no classes in index), NOT USED
        assertThat(result.declaredUsage())
                .containsEntry("com.example:test-runtime-lib", DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
    }

    @Test
    void serviceEntryWithSubdirectoryIgnored(@TempDir Path tempDir) throws Exception {
        Path tempJar = tempDir.resolve("subdir-lib.jar");
        // Subdirectory under services — should NOT be treated as a service interface
        createJarWithEntries(tempJar, "META-INF/services/com/example/SomeService");

        var dep = new DependenciesTui.DepEntry("com.example", "lib", "", "1.0", "compile", true);
        Map<String, File> gaToJar = Map.of("com.example:lib", tempJar.toFile());
        Map<String, String> classIndex = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(
                        Set.of("com.example.SomeService"),
                        Set.of(),
                        classIndex,
                        gaToJar,
                        List.of(dep),
                        List.of(),
                        true);

        assertThat(result.declaredUsage())
                .containsEntry("com.example:lib", DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
    }

    @Test
    void sisuDiscoveryMarksAsUsed(@TempDir Path tempDir) throws Exception {
        Path tempJar = tempDir.resolve("sisu-lib.jar");
        createJarWithEntries(tempJar, "META-INF/sisu/javax.inject.Named");

        var dep = new DependenciesTui.DepEntry("com.example", "sisu-lib", "", "1.0", "compile", true);
        Map<String, File> gaToJar = Map.of("com.example:sisu-lib", tempJar.toFile());
        Map<String, String> classIndex = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of("javax.inject.Named"), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("com.example:sisu-lib", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void springComponentsDiscoveryMarksAsUsed(@TempDir Path tempDir) throws Exception {
        Path tempJar = tempDir.resolve("spring-lib.jar");
        createJarWithEntries(tempJar, "META-INF/spring.components");

        var dep = new DependenciesTui.DepEntry("com.example", "spring-lib", "", "1.0", "compile", true);
        Map<String, File> gaToJar = Map.of("com.example:spring-lib", tempJar.toFile());
        Map<String, String> classIndex = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(
                        Set.of("org.springframework.stereotype.Component"),
                        Set.of(),
                        classIndex,
                        gaToJar,
                        List.of(dep),
                        List.of(),
                        true);

        assertThat(result.declaredUsage())
                .containsEntry("com.example:spring-lib", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void springFactoriesDiscoveryMarksAsUsed(@TempDir Path tempDir) throws Exception {
        Path tempJar = tempDir.resolve("spring-boot-lib.jar");
        createJarWithEntries(tempJar, "META-INF/spring.factories");

        var dep = new DependenciesTui.DepEntry("com.example", "spring-boot-lib", "", "1.0", "compile", true);
        Map<String, File> gaToJar = Map.of("com.example:spring-boot-lib", tempJar.toFile());
        Map<String, String> classIndex = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(
                        Set.of("org.springframework.boot.autoconfigure.EnableAutoConfiguration"),
                        Set.of(),
                        classIndex,
                        gaToJar,
                        List.of(dep),
                        List.of(),
                        true);

        assertThat(result.declaredUsage())
                .containsEntry("com.example:spring-boot-lib", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void springFactoriesInterfaceKeysExtractedFromContent(@TempDir Path tempDir) throws Exception {
        // spring.factories content: the interface keys must be extracted so that a consumer
        // referencing e.g. ApplicationContextInitializer (not EnableAutoConfiguration) is also USED.
        Path tempJar = tempDir.resolve("spring-init-lib.jar");
        try (var os = Files.newOutputStream(tempJar);
                var jos = new JarOutputStream(os)) {
            jos.putNextEntry(new JarEntry("META-INF/spring.factories"));
            String content = "org.springframework.context.ApplicationContextInitializer=\\\n"
                    + "  com.example.MyInitializer\n"
                    + "org.springframework.boot.autoconfigure.EnableAutoConfiguration=\\\n"
                    + "  com.example.FooAutoConfiguration\n";
            jos.write(content.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        var dep = new DependenciesTui.DepEntry("com.example", "spring-init-lib", "", "1.0", "compile", true);
        Map<String, File> gaToJar = Map.of("com.example:spring-init-lib", tempJar.toFile());
        Map<String, String> classIndex = Map.of();

        // Consumer references ApplicationContextInitializer (not EnableAutoConfiguration)
        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(
                        Set.of("org.springframework.context.ApplicationContextInitializer"),
                        Set.of(),
                        classIndex,
                        gaToJar,
                        List.of(dep),
                        List.of(),
                        true);

        assertThat(result.declaredUsage())
                .containsEntry("com.example:spring-init-lib", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void springFactoriesWithNoConsumerReferenceIsUndetermined(@TempDir Path tempDir) throws Exception {
        // spring.factories present but consumer references neither interface — must be UNDETERMINED,
        // not UNUSED, because Spring Boot loads auto-configurations at runtime.
        Path tempJar = tempDir.resolve("spring-auto.jar");
        try (var os = Files.newOutputStream(tempJar);
                var jos = new JarOutputStream(os)) {
            jos.putNextEntry(new JarEntry("META-INF/spring.factories"));
            String content = "org.springframework.boot.autoconfigure.EnableAutoConfiguration=\\\n"
                    + "  com.example.FooAutoConfiguration\n";
            jos.write(content.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        var dep = new DependenciesTui.DepEntry("com.example", "spring-auto", "", "1.0", "runtime", true);
        Map<String, File> gaToJar = Map.of("com.example:spring-auto", tempJar.toFile());
        Map<String, String> classIndex = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of("com.app.Main"), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("com.example:spring-auto", DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
    }

    @Test
    void springBootAutoConfigImportsIsUndetermined(@TempDir Path tempDir) throws Exception {
        // Spring Boot 3.x META-INF/spring/…AutoConfiguration.imports — presence alone is enough
        Path tempJar = tempDir.resolve("spring3-auto.jar");
        createJarWithEntries(
                tempJar, "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");

        var dep = new DependenciesTui.DepEntry("com.example", "spring3-auto", "", "3.0", "runtime", true);
        Map<String, File> gaToJar = Map.of("com.example:spring3-auto", tempJar.toFile());
        Map<String, String> classIndex = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of("com.app.Main"), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("com.example:spring3-auto", DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
    }

    @Test
    void quarkusExtensionIsUndetermined(@TempDir Path tempDir) throws Exception {
        // A Quarkus extension registers META-INF/quarkus-extension.properties.
        // The Quarkus build system wires it at build time — no direct bytecode reference in the consumer.
        Path tempJar = tempDir.resolve("quarkus-ext.jar");
        createJarWithEntries(tempJar, "META-INF/quarkus-extension.properties");

        var dep = new DependenciesTui.DepEntry("io.quarkus", "quarkus-resteasy", "", "3.0.0", "compile", true);
        Map<String, File> gaToJar = Map.of("io.quarkus:quarkus-resteasy", tempJar.toFile());
        Map<String, String> classIndex =
                Map.of("io.quarkus.resteasy.ReactiveExceptionMapper", "io.quarkus:quarkus-resteasy");

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(
                        Set.of("com.app.Main"), // consumer doesn't reference Quarkus internals
                        Set.of(),
                        classIndex,
                        gaToJar,
                        List.of(dep),
                        List.of(),
                        true);

        assertThat(result.declaredUsage())
                .containsEntry("io.quarkus:quarkus-resteasy", DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
    }

    @Test
    void camelYamlDescriptorsInMetaInfCamelAreUndetermined(@TempDir Path tempDir) throws Exception {
        // Camel 4 YAML descriptors live under META-INF/camel/ (not META-INF/services/org/apache/camel/)
        Path tempJar = tempDir.resolve("camel-yaml-dsl.jar");
        createJarWithEntries(tempJar, "META-INF/camel/camel-yaml-dsl.json");

        var dep = new DependenciesTui.DepEntry("org.apache.camel", "camel-yaml-dsl", "", "4.0.0", "compile", true);
        Map<String, File> gaToJar = Map.of("org.apache.camel:camel-yaml-dsl", tempJar.toFile());
        Map<String, String> classIndex =
                Map.of("org.apache.camel.dsl.yaml.YamlRoutesLoader", "org.apache.camel:camel-yaml-dsl");

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(
                        Set.of("org.apache.camel.builder.RouteBuilder"),
                        Set.of(),
                        classIndex,
                        gaToJar,
                        List.of(dep),
                        List.of(),
                        true);

        assertThat(result.declaredUsage())
                .containsEntry("org.apache.camel:camel-yaml-dsl", DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
    }

    @Test
    void discoveryNotMatchedWhenInterfaceNotReferenced(@TempDir Path tempDir) throws Exception {
        Path tempJar = tempDir.resolve("unused-svc.jar");
        createJarWithEntries(tempJar, "META-INF/services/com.example.UnusedService");

        var dep = new DependenciesTui.DepEntry("com.example", "unused-svc", "", "1.0", "compile", true);
        Map<String, File> gaToJar = Map.of("com.example:unused-svc", tempJar.toFile());
        Map<String, String> classIndex = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of("com.other.Unrelated"), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("com.example:unused-svc", DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
    }

    // --- Allowlist tests ---

    @Test
    void runtimeArtifactAllowlistMarksAsUsed() {
        var dep = new DependenciesTui.DepEntry("org.postgresql", "postgresql", "", "42.7", "runtime", true);

        Map<String, String> classIndex = Map.of("org.postgresql.Driver", "org.postgresql:postgresql");
        Map<String, File> gaToJar = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .runtimeArtifacts(Set.of("org.postgresql:postgresql"))
                .build()
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("org.postgresql:postgresql", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void runtimeArtifactWildcardMarksAsUsed() {
        var dep = new DependenciesTui.DepEntry("com.oracle.database.jdbc", "ojdbc11", "", "23.3", "runtime", true);

        Map<String, String> classIndex = Map.of("oracle.jdbc.OracleDriver", "com.oracle.database.jdbc:ojdbc11");
        Map<String, File> gaToJar = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .runtimeArtifacts(Set.of("com.oracle.database.jdbc:*"))
                .build()
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("com.oracle.database.jdbc:ojdbc11", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void runtimeArtifactWorksEvenWithNoClassesInIndex() {
        var dep = new DependenciesTui.DepEntry("org.postgresql", "postgresql", "", "42.7", "runtime", true);

        Map<String, String> classIndex = Map.of();
        Map<String, File> gaToJar = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .runtimeArtifacts(Set.of("org.postgresql:postgresql"))
                .build()
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("org.postgresql:postgresql", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void runtimeArtifactNotInAllowlistRemainsUnused() {
        var dep = new DependenciesTui.DepEntry("com.example", "not-allowlisted", "", "1.0", "compile", true);

        Map<String, String> classIndex = Map.of("com.example.Foo", "com.example:not-allowlisted");
        Map<String, File> gaToJar = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .runtimeArtifacts(Set.of("org.postgresql:postgresql"))
                .build()
                .analyze(Set.of("com.other.Unrelated"), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("com.example:not-allowlisted", DependencyUsageAnalyzer.UsageStatus.UNUSED);
    }

    @Test
    void annotationOnlyArtifactAllowlistMarksAsUsed() {
        var dep = new DependenciesTui.DepEntry("org.projectlombok", "lombok", "", "1.18", "provided", true);

        Map<String, String> classIndex = Map.of("lombok.Getter", "org.projectlombok:lombok");
        Map<String, File> gaToJar = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .annotationOnlyArtifacts(Set.of("org.projectlombok:lombok"))
                .build()
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("org.projectlombok:lombok", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void annotationOnlyArtifactWorksEvenWithNoClassesInIndex() {
        var dep = new DependenciesTui.DepEntry("org.projectlombok", "lombok", "", "1.18", "provided", true);

        // No classes from lombok in the index at all
        Map<String, String> classIndex = Map.of();
        Map<String, File> gaToJar = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .annotationOnlyArtifacts(Set.of("org.projectlombok:lombok"))
                .build()
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("org.projectlombok:lombok", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void reflectionLoadedClassMarksAsUsedWhenClassPresent() {
        var dep = new DependenciesTui.DepEntry("org.postgresql", "postgresql", "", "42.7", "runtime", true);

        Map<String, String> classIndex = Map.of("org.postgresql.Driver", "org.postgresql:postgresql");
        Map<String, File> gaToJar = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .extraUsedClasses(Map.of("org.postgresql:postgresql", List.of("org.postgresql.Driver")))
                .build()
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("org.postgresql:postgresql", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void reflectionLoadedClassRemainsUnusedWhenClassAbsent() {
        var dep = new DependenciesTui.DepEntry("org.postgresql", "postgresql", "", "42.7", "runtime", true);

        // The JAR provides different classes, not the expected one
        Map<String, String> classIndex = Map.of("org.postgresql.PGProperty", "org.postgresql:postgresql");
        Map<String, File> gaToJar = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .extraUsedClasses(Map.of("org.postgresql:postgresql", List.of("org.postgresql.Driver")))
                .build()
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("org.postgresql:postgresql", DependencyUsageAnalyzer.UsageStatus.UNUSED);
    }

    @Test
    void reflectionLoadedClassUndeterminedWhenNoClassIndex() {
        var dep = new DependenciesTui.DepEntry("org.postgresql", "postgresql", "", "42.7", "runtime", true);

        // No classes in index at all for this artifact
        Map<String, String> classIndex = Map.of();
        Map<String, File> gaToJar = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .extraUsedClasses(Map.of("org.postgresql:postgresql", List.of("org.postgresql.Driver")))
                .build()
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("org.postgresql:postgresql", DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
    }

    @Test
    void matchesArtifactPatternExact() {
        assertThat(DependencyUsageAnalyzer.matchesArtifactPattern(
                        "org.slf4j:slf4j-simple", Set.of("org.slf4j:slf4j-simple")))
                .isTrue();
    }

    @Test
    void matchesArtifactPatternWildcard() {
        assertThat(DependencyUsageAnalyzer.matchesArtifactPattern(
                        "com.oracle.database.jdbc:ojdbc11", Set.of("com.oracle.database.jdbc:*")))
                .isTrue();
    }

    @Test
    void classifiedDepGaMatchesClassifiedKey() {
        var dep = new DependenciesTui.DepEntry("com.example", "lib", "tests", "1.0", "test", true);
        assertThat(dep.ga()).isEqualTo("com.example:lib:tests");

        Map<String, String> gaToVersion = Map.of("com.example:lib:tests", "1.0");
        assertThat(gaToVersion.get(dep.ga())).isEqualTo("1.0");
    }

    @Test
    void matchesArtifactPatternClassifierFallback() {
        assertThat(DependencyUsageAnalyzer.matchesArtifactPattern("com.example:lib:tests", Set.of("com.example:lib")))
                .isTrue();
    }

    @Test
    void matchesArtifactPatternNoMatch() {
        assertThat(DependencyUsageAnalyzer.matchesArtifactPattern("com.example:other", Set.of("com.example:lib")))
                .isFalse();
    }

    @Test
    void matchesArtifactPatternEmptySet() {
        assertThat(DependencyUsageAnalyzer.matchesArtifactPattern("com.example:lib", Set.of()))
                .isFalse();
    }

    @Test
    void mavenDiRegistrationWithoutBytecodeRefIsUndetermined(@TempDir Path tempDir) throws Exception {
        // maven-jline scenario: provides DI-registered components via META-INF/maven/<annotation>
        // but the consuming module has no direct bytecode reference to the DI annotation
        Path tempJar = tempDir.resolve("maven-jline.jar");
        createJarWithEntries(tempJar, "META-INF/maven/org.apache.maven.api.di.Inject");

        var dep = new DependenciesTui.DepEntry("org.apache.maven", "maven-jline", "", "4.0", "compile", true);
        Map<String, File> gaToJar = Map.of("org.apache.maven:maven-jline", tempJar.toFile());
        Map<String, String> classIndex =
                Map.of("org.apache.maven.jline.DefaultPrompter", "org.apache.maven:maven-jline");

        // Consumer does not reference "org.apache.maven.api.di.Inject" or any maven-jline class
        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(
                        Set.of("org.apache.maven.impl.SomeOtherClass"),
                        Set.of(),
                        classIndex,
                        gaToJar,
                        List.of(dep),
                        List.of(),
                        true);

        // Should be UNDETERMINED, not UNUSED — DI container wires at runtime, not via bytecode
        assertThat(result.declaredUsage())
                .containsEntry("org.apache.maven:maven-jline", DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
    }

    @Test
    void sisuRegistrationWithoutBytecodeRefIsUndetermined(@TempDir Path tempDir) throws Exception {
        // Sisu/JSR-330 scenario: dep registers components via META-INF/sisu/<annotation>
        // but the consuming module does not reference the sisu annotation directly
        Path tempJar = tempDir.resolve("sisu-component.jar");
        createJarWithEntries(tempJar, "META-INF/sisu/javax.inject.Named");

        var dep = new DependenciesTui.DepEntry("com.example", "sisu-component", "", "1.0", "compile", true);
        Map<String, File> gaToJar = Map.of("com.example:sisu-component", tempJar.toFile());
        Map<String, String> classIndex = Map.of("com.example.MyComponent", "com.example:sisu-component");

        // Consumer does not reference "javax.inject.Named" or any component class
        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of("com.other.Unrelated"), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("com.example:sisu-component", DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
    }

    @Test
    void mavenDiRegistrationWithBytecodeRefIsUsed(@TempDir Path tempDir) throws Exception {
        // If the consumer does reference the DI annotation directly, classify as USED
        Path tempJar = tempDir.resolve("maven-di.jar");
        createJarWithEntries(tempJar, "META-INF/maven/org.apache.maven.api.di.Inject");

        var dep = new DependenciesTui.DepEntry("org.apache.maven", "maven-di", "", "4.0", "compile", true);
        Map<String, File> gaToJar = Map.of("org.apache.maven:maven-di", tempJar.toFile());
        Map<String, String> classIndex = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(
                        Set.of("org.apache.maven.api.di.Inject"),
                        Set.of(),
                        classIndex,
                        gaToJar,
                        List.of(dep),
                        List.of(),
                        true);

        assertThat(result.declaredUsage())
                .containsEntry("org.apache.maven:maven-di", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void mavenDiEntryDetectedByHasMavenDiOrSisu(@TempDir Path tempDir) throws Exception {
        Path tempJar = tempDir.resolve("maven-di.jar");
        createJarWithEntries(tempJar, "META-INF/maven/org.apache.maven.api.di.Inject");
        assertThat(DependencyUsageAnalyzer.hasImpliesUndeterminedRegistration(tempJar.toFile()))
                .isTrue();
    }

    @Test
    void pomMetadataEntryNotMistokenForMavenDi(@TempDir Path tempDir) throws Exception {
        // META-INF/maven/org.apache.maven/maven-jline/pom.xml is POM metadata, not a DI index
        Path tempJar = tempDir.resolve("regular.jar");
        createJarWithEntries(tempJar, "META-INF/maven/org.apache.maven/maven-jline/pom.xml");
        assertThat(DependencyUsageAnalyzer.hasImpliesUndeterminedRegistration(tempJar.toFile()))
                .isFalse();
    }

    @Test
    void mavenDiIncludedInRuntimeDiscoveryClasses(@TempDir Path tempDir) throws Exception {
        Path tempJar = tempDir.resolve("maven-di.jar");
        createJarWithEntries(tempJar, "META-INF/maven/org.apache.maven.api.di.Inject");

        Set<String> classes = DependencyUsageAnalyzer.getRuntimeDiscoveryClasses(tempJar.toFile());
        assertThat(classes).contains("org.apache.maven.api.di.Inject");
    }

    @Test
    void serviceLoaderOnlyWithoutBytecodeRefIsUndetermined(@TempDir Path tempDir) throws Exception {
        // ServiceLoader-only dep: hasMavenDiOrSisu=false but has META-INF/services/<X> →
        // classifyByRuntimeDiscovery returns UNDETERMINED (bytecode analysis can't verify
        // whether a ServiceLoader-based provider is actually consumed at runtime).
        Path tempJar = tempDir.resolve("svc.jar");
        createJarWithEntries(tempJar, "META-INF/services/com.example.SomeService");

        var dep = new DependenciesTui.DepEntry("com.example", "svc", "", "1.0", "compile", true);
        Map<String, File> gaToJar = Map.of("com.example:svc", tempJar.toFile());
        Map<String, String> classIndex = Map.of("com.example.SomeService", "com.example:svc");

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of("com.other.Unrelated"), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("com.example:svc", DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
    }

    // --- Apache Camel SPI provider false-positive reproducer ---

    /**
     * Camel components/languages/data-formats register themselves via
     * {@code META-INF/services/org/apache/camel/} entries (e.g.
     * {@code META-INF/services/org/apache/camel/component/timer},
     * {@code META-INF/services/org/apache/camel/language.properties}).
     * Camel's {@code FactoryFinder} / {@code PluginHelper} resolves them at runtime
     * without any direct class reference in the consuming module.
     * A missing Camel SPI dep would cause a runtime {@code NoSuchBeanDefinitionException}
     * or similar, not a compile error — so pilot must never flag them as UNUSED.
     */
    @Test
    void camelSpiComponentIsUndetermined(@TempDir Path tempDir) throws Exception {
        // Simulate camel-timer: registers META-INF/services/org/apache/camel/component/timer
        // and META-INF/services/org/apache/camel/component.properties
        Path camelTimerJar = tempDir.resolve("camel-timer.jar");
        createJarWithEntries(
                camelTimerJar,
                "META-INF/services/org/apache/camel/component/timer",
                "META-INF/services/org/apache/camel/component.properties");

        var dep = new DependenciesTui.DepEntry("org.apache.camel", "camel-timer", "", "4.0.0", "test", true);
        Map<String, File> gaToJar = Map.of("org.apache.camel:camel-timer", camelTimerJar.toFile());
        // Consumer (e.g. camel-core test class) uses "timer" as a URI string literal — no class ref
        Map<String, String> classIndex =
                Map.of("org.apache.camel.component.timer.TimerComponent", "org.apache.camel:camel-timer");

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(
                        Set.of("org.apache.camel.builder.RouteBuilder"),
                        Set.of(),
                        classIndex,
                        gaToJar,
                        List.of(dep),
                        List.of(),
                        true);

        assertThat(result.declaredUsage())
                .containsEntry("org.apache.camel:camel-timer", DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
    }

    @Test
    void camelSpiLanguageIsUndetermined(@TempDir Path tempDir) throws Exception {
        // Simulate camel-xpath: registers META-INF/services/org/apache/camel/language/xpath
        Path camelXpathJar = tempDir.resolve("camel-xpath.jar");
        createJarWithEntries(
                camelXpathJar,
                "META-INF/services/org/apache/camel/language/xpath",
                "META-INF/services/org/apache/camel/language.properties");

        var dep = new DependenciesTui.DepEntry("org.apache.camel", "camel-xpath", "", "4.0.0", "test", true);
        Map<String, File> gaToJar = Map.of("org.apache.camel:camel-xpath", camelXpathJar.toFile());
        Map<String, String> classIndex =
                Map.of("org.apache.camel.language.xpath.XPathBuilder", "org.apache.camel:camel-xpath");

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(
                        Set.of("org.apache.camel.model.language.XPathExpression"),
                        Set.of(),
                        classIndex,
                        gaToJar,
                        List.of(dep),
                        List.of(),
                        true);

        assertThat(result.declaredUsage())
                .containsEntry("org.apache.camel:camel-xpath", DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
    }

    @Test
    void camelSpiNamedExtensionIsUndetermined(@TempDir Path tempDir) throws Exception {
        // Simulate camel-yaml-io: registers META-INF/services/org/apache/camel/modelyaml-dumper
        // (a named singleton SPI, not a component/language)
        Path camelYamlIoJar = tempDir.resolve("camel-yaml-io.jar");
        createJarWithEntries(camelYamlIoJar, "META-INF/services/org/apache/camel/modelyaml-dumper");

        var dep = new DependenciesTui.DepEntry("org.apache.camel", "camel-yaml-io", "", "4.0.0", "test", true);
        Map<String, File> gaToJar = Map.of("org.apache.camel:camel-yaml-io", camelYamlIoJar.toFile());
        Map<String, String> classIndex =
                Map.of("org.apache.camel.yaml.LwModelToYAMLDumper", "org.apache.camel:camel-yaml-io");

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(
                        Set.of("org.apache.camel.support.PluginHelper"),
                        Set.of(),
                        classIndex,
                        gaToJar,
                        List.of(dep),
                        List.of(),
                        true);

        assertThat(result.declaredUsage())
                .containsEntry("org.apache.camel:camel-yaml-io", DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
    }

    // --- SLF4J binding / logging backend false-positive reproducer ---

    /**
     * Reproducer for the SLF4J binding false-positive.
     * <p>
     * {@code log4j-slf4j2-impl} ships {@code META-INF/services/org.slf4j.spi.SLF4JServiceProvider}, which is loaded by
     * the SLF4J framework at runtime via {@link java.util.ServiceLoader}. The consuming module (e.g.
     * {@code camel-timer}) never references {@code org.slf4j.spi.SLF4JServiceProvider} directly in its bytecode — SLF4J
     * does that internally.
     * <p>
     * Expected: a pure ServiceLoader implementation binding declared with test scope and providing no bytecode
     * reference in the consumer should be classified as {@link DependencyUsageAnalyzer.UsageStatus#UNDETERMINED}
     * (cannot be verified from bytecode), not {@link DependencyUsageAnalyzer.UsageStatus#UNUSED}.
     */
    @Test
    void slf4jBindingViaServiceLoaderShouldNotBeUnused(@TempDir Path tempDir) throws Exception {
        // Simulates log4j-slf4j2-impl: has META-INF/services/org.slf4j.spi.SLF4JServiceProvider
        // but the consumer never imports that interface directly.
        Path tempJar = tempDir.resolve("log4j-slf4j2-impl.jar");
        createJarWithEntries(
                tempJar,
                "META-INF/services/org.slf4j.spi.SLF4JServiceProvider",
                "org/apache/logging/slf4j/Log4jLoggerFactory.class");

        var dep =
                new DependenciesTui.DepEntry("org.apache.logging.log4j", "log4j-slf4j2-impl", "", "2.25", "test", true);
        Map<String, File> gaToJar = Map.of("org.apache.logging.log4j:log4j-slf4j2-impl", tempJar.toFile());
        Map<String, String> classIndex =
                Map.of("org.apache.logging.slf4j.Log4jLoggerFactory", "org.apache.logging.log4j:log4j-slf4j2-impl");

        // Consumer (e.g. camel-timer) does not reference SLF4JServiceProvider or any log4j class
        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(
                        Set.of("org.apache.camel.component.timer.TimerConsumer"),
                        Set.of(),
                        classIndex,
                        gaToJar,
                        List.of(dep),
                        List.of(),
                        true);

        // Should be UNDETERMINED — it is a ServiceLoader-based SLF4J backend whose service interface
        // is not referenced in the consumer's bytecode. Bytecode analysis cannot verify whether the
        // provider is actually needed at runtime.
        assertThat(result.declaredUsage().get("org.apache.logging.log4j:log4j-slf4j2-impl"))
                .isEqualTo(DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
    }

    /**
     * Verifies that a dep in the {@code runtimeArtifacts} allowlist is always reported as USED, even when it has
     * ServiceLoader metadata that would otherwise cause {@code classifyByRuntimeDiscovery} to return UNDETERMINED.
     * <p>
     * Regression guard for the allowlist-bypass bug: before the fix, {@code classifyByRuntimeDiscovery} short-circuited
     * {@code classify()} before reaching the allowlist check, so an explicit {@code runtimeArtifacts} entry was
     * silently overridden.
     */
    @Test
    void runtimeArtifactsAllowlistWinsOverServiceLoaderUndetermined(@TempDir Path tempDir) throws Exception {
        // Simulates org.postgresql:postgresql: has META-INF/services/java.sql.Driver (ServiceLoader)
        // but the consumer never references java.sql.Driver directly.
        Path tempJar = tempDir.resolve("postgresql.jar");
        createJarWithEntries(tempJar, "META-INF/services/java.sql.Driver", "org/postgresql/Driver.class");

        var dep = new DependenciesTui.DepEntry("org.postgresql", "postgresql", "", "42.7.3", "runtime", true);
        Map<String, File> gaToJar = Map.of("org.postgresql:postgresql", tempJar.toFile());
        Map<String, String> classIndex = Map.of("org.postgresql.Driver", "org.postgresql:postgresql");

        // Analyzer is configured with postgresql in runtimeArtifacts
        var result = DependencyUsageAnalyzer.builder()
                .runtimeArtifacts(Set.of("org.postgresql:postgresql"))
                .build()
                .analyze(Set.of("com.example.App"), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        // runtimeArtifacts allowlist must win: USED, not UNDETERMINED
        assertThat(result.declaredUsage())
                .containsEntry("org.postgresql:postgresql", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    /**
     * Regression guard: a dep in {@code runtimeArtifacts} whose classes appear only in test bytecode
     * must be classified {@code USED}, not {@code USED_IN_TEST}.
     * <p>
     * Before the fix, the {@code USED_IN_TEST} check ran before the {@code runtimeArtifacts} allowlist.
     * A compile-scope JDBC driver listed in {@code runtimeArtifacts} but referenced only from test code
     * would be classified {@code USED_IN_TEST} and its scope narrowed to {@code test}, removing it from
     * the production runtime classpath and breaking the application at startup.
     */
    @Test
    void runtimeArtifactsAllowlistWinsOverUsedInTest() {
        var dep = new DependenciesTui.DepEntry("org.postgresql", "postgresql", "", "42.7.3", "compile", true);

        // postgresql class appears only in test refs (e.g. test exercises the production DB path)
        Map<String, String> classIndex = Map.of("org.postgresql.Driver", "org.postgresql:postgresql");
        Map<String, File> gaToJar = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .runtimeArtifacts(Set.of("org.postgresql:postgresql"))
                .build()
                .analyze(Set.of(), Set.of("org.postgresql.Driver"), classIndex, gaToJar, List.of(dep), List.of(), true);

        // runtimeArtifacts allowlist must win: USED, not USED_IN_TEST
        assertThat(result.declaredUsage().get("org.postgresql:postgresql"))
                .isEqualTo(DependencyUsageAnalyzer.UsageStatus.USED);
    }

    /**
     * Regression test: a dependency whose JAR contains {@code public static final} fields
     * (compile-time constants with a {@code ConstantValue} attribute) must be classified as
     * {@code UNDETERMINED}, not {@code UNUSED}, even when no bytecode reference to its classes
     * is found in the consuming module.
     *
     * <p>Root cause: the Java compiler inlines constant field values at call sites.  For example,
     * {@code Constants.MAVEN_USER_CONF} compiles to {@code LDC "maven.user.conf"} — no
     * {@code GETSTATIC} is emitted — so the {@code Constants} class never appears in the
     * consumer's bytecode.  Pilot must not suggest removing such a dependency.</p>
     *
     * <p>The fixture {@link ConstantProviderFixture} is compiled as part of the test sources
     * and has {@code public static final String/int} fields.  We package it into a temporary
     * JAR and verify that the analyzer returns {@code UNDETERMINED}.</p>
     */
    @Test
    void depWithInlineableConstantsClassifiedAsUndetermined(@TempDir Path tempDir) throws Exception {
        // Package ConstantProviderFixture into a JAR — it has public static final String/int fields
        Path testClasses = Path.of("target/test-classes");
        Assumptions.assumeTrue(
                Files.isDirectory(testClasses),
                "target/test-classes not found — skipping inlineable-constants regression test");

        Path classFile = testClasses.resolve("eu/maveniverse/maven/pilot/ConstantProviderFixture.class");
        Assumptions.assumeTrue(Files.exists(classFile), "ConstantProviderFixture.class not compiled — skipping");

        Path constantsJar = tempDir.resolve("constants-lib.jar");
        try (OutputStream os = Files.newOutputStream(constantsJar);
                JarOutputStream jos = new JarOutputStream(os)) {
            jos.putNextEntry(new JarEntry("eu/maveniverse/maven/pilot/ConstantProviderFixture.class"));
            Files.copy(classFile, jos);
            jos.closeEntry();
        }

        var dep = new DependenciesTui.DepEntry("com.example", "constants-lib", "", "1.0", "compile", true);
        Map<String, File> gaToJar = Map.of("com.example:constants-lib", constantsJar.toFile());
        // The class is in the index, but no bytecode reference is found (simulates compile-time inlining)
        Map<String, String> classIndex =
                Map.of("eu.maveniverse.maven.pilot.ConstantProviderFixture", "com.example:constants-lib");

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(
                        Set.of("com.other.Unrelated"), // no reference to ConstantProviderFixture
                        Set.of(),
                        classIndex,
                        gaToJar,
                        List.of(dep),
                        List.of(),
                        true);

        assertThat(result.declaredUsage())
                .as("dep with public static final constant fields must be UNDETERMINED, not UNUSED "
                        + "(regression: inlined constants leave no bytecode trace)")
                .containsEntry("com.example:constants-lib", DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
    }

    // --- isTestScope ---

    @Test
    void isTestScopeReturnsTrueForTestScopes() {
        assertThat(DependencyUsageAnalyzer.isTestScope("test")).isTrue();
        assertThat(DependencyUsageAnalyzer.isTestScope("test-only")).isTrue();
        assertThat(DependencyUsageAnalyzer.isTestScope("test-runtime")).isTrue();
    }

    @Test
    void isTestScopeReturnsFalseForNonTestScopes() {
        assertThat(DependencyUsageAnalyzer.isTestScope("compile")).isFalse();
        assertThat(DependencyUsageAnalyzer.isTestScope("provided")).isFalse();
        assertThat(DependencyUsageAnalyzer.isTestScope("runtime")).isFalse();
        assertThat(DependencyUsageAnalyzer.isTestScope("compile-only")).isFalse();
        assertThat(DependencyUsageAnalyzer.isTestScope(null)).isFalse();
        assertThat(DependencyUsageAnalyzer.isTestScope("")).isFalse();
    }

    // --- isNarrowableToTestScope ---

    @Test
    void isNarrowableToTestScopeReturnsTrueOnlyForCompileScopes() {
        assertThat(DependencyUsageAnalyzer.isNarrowableToTestScope("compile")).isTrue();
        assertThat(DependencyUsageAnalyzer.isNarrowableToTestScope("compile-only"))
                .isTrue();
        // "provided" must NOT be narrowable: container supplies it at runtime
        assertThat(DependencyUsageAnalyzer.isNarrowableToTestScope("provided")).isFalse();
        // "runtime" must NOT be narrowable: test-only refs don't mean absent from production
        assertThat(DependencyUsageAnalyzer.isNarrowableToTestScope("runtime")).isFalse();
        assertThat(DependencyUsageAnalyzer.isNarrowableToTestScope("system")).isFalse();
        assertThat(DependencyUsageAnalyzer.isNarrowableToTestScope("test")).isFalse();
        assertThat(DependencyUsageAnalyzer.isNarrowableToTestScope(null)).isFalse();
        assertThat(DependencyUsageAnalyzer.isNarrowableToTestScope("")).isFalse();
    }

    /**
     * Regression guard: a {@code provided}-scope dependency whose classes appear only in test
     * bytecode must NOT be classified {@code USED_IN_TEST}. Narrowing {@code provided} to
     * {@code test} would remove a container-provided artifact from the production runtime
     * classpath, breaking the application at startup.
     */
    @Test
    void providedScopedDepIsNotNarrowedToTestEvenWhenOnlyInTestRefs() {
        var dep = new DependenciesTui.DepEntry("javax.servlet", "javax.servlet-api", "", "4.0.1", "provided", true);

        Map<String, String> classIndex =
                Map.of("javax.servlet.http.HttpServletRequest", "javax.servlet:javax.servlet-api");
        Map<String, File> gaToJar = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(
                        Set.of(), // mainRefs — not in main bytecode
                        Set.of("javax.servlet.http.HttpServletRequest"), // testRefs — only in tests
                        classIndex,
                        gaToJar,
                        List.of(dep),
                        List.of(),
                        true);

        // provided dep must not be narrowed to test — it's UNUSED (or UNDETERMINED), not USED_IN_TEST
        assertThat(result.declaredUsage())
                .as("provided-scope dep used only in tests must not be classified USED_IN_TEST")
                .doesNotContainEntry(
                        "javax.servlet:javax.servlet-api", DependencyUsageAnalyzer.UsageStatus.USED_IN_TEST);
    }

    /**
     * Regression guard: a {@code runtime}-scope dependency whose classes appear only in test
     * bytecode must NOT be classified {@code USED_IN_TEST}. Narrowing {@code runtime} to
     * {@code test} could remove a JDBC driver or SLF4J backend from the production classpath.
     */
    @Test
    void runtimeScopedDepIsNotNarrowedToTestEvenWhenOnlyInTestRefs() {
        var dep = new DependenciesTui.DepEntry("org.slf4j", "slf4j-simple", "", "2.0.9", "runtime", true);

        Map<String, String> classIndex = Map.of("org.slf4j.simple.SimpleLogger", "org.slf4j:slf4j-simple");
        Map<String, File> gaToJar = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(
                        Set.of(), // mainRefs — not in main bytecode
                        Set.of("org.slf4j.simple.SimpleLogger"), // testRefs — only in tests
                        classIndex,
                        gaToJar,
                        List.of(dep),
                        List.of(),
                        true);

        // runtime dep must not be narrowed to test
        assertThat(result.declaredUsage())
                .as("runtime-scope dep used only in tests must not be classified USED_IN_TEST")
                .doesNotContainEntry("org.slf4j:slf4j-simple", DependencyUsageAnalyzer.UsageStatus.USED_IN_TEST);
    }

    /**
     * Regression guard: a compile-scope dep with ServiceLoader metadata whose classes appear only in
     * test bytecode must be classified {@code UNDETERMINED}, not {@code USED_IN_TEST}.
     * <p>
     * Before the fix, the {@code USED_IN_TEST} check ran before {@code classifyByRuntimeDiscovery}.
     * A compile-scope SPI dep (e.g. an SLF4J backend registered via {@code META-INF/services/})
     * with its classes referenced only in test code would be incorrectly classified
     * {@code USED_IN_TEST}, triggering scope narrowing that removes a production runtime provider.
     * {@code classifyByRuntimeDiscovery} must win and return {@code UNDETERMINED} in this case,
     * because the DI/SPI container loads the provider at runtime — test-only class references do
     * not mean the dep is absent from the production classpath.
     * </p>
     */
    @Test
    void serviceLoaderDepWithTestOnlyRefsIsUndeterminedNotUsedInTest(@TempDir Path tempDir) throws Exception {
        // Simulates an SLF4J backend: has META-INF/services/org.slf4j.spi.SLF4JServiceProvider
        // and its class appears only in test refs (e.g. a test configures or exercises the logger).
        Path tempJar = tempDir.resolve("slf4j-backend.jar");
        createJarWithEntries(
                tempJar, "META-INF/services/org.slf4j.spi.SLF4JServiceProvider", "org/example/Slf4jProvider.class");

        var dep = new DependenciesTui.DepEntry("org.example", "slf4j-backend", "", "1.0.0", "compile", true);
        Map<String, File> gaToJar = Map.of("org.example:slf4j-backend", tempJar.toFile());
        Map<String, String> classIndex = Map.of("org.example.Slf4jProvider", "org.example:slf4j-backend");

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(
                        Set.of(), // mainRefs — not in main bytecode
                        Set.of("org.example.Slf4jProvider"), // testRefs — only in tests
                        classIndex,
                        gaToJar,
                        List.of(dep),
                        List.of(),
                        true);

        // classifyByRuntimeDiscovery must win: UNDETERMINED (SPI dep), not USED_IN_TEST
        assertThat(result.declaredUsage())
                .as("compile-scope SPI dep with test-only class refs must be UNDETERMINED, not USED_IN_TEST")
                .containsEntry("org.example:slf4j-backend", DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
    }

    /**
     * Regression guard: when test bytecode was not scanned ({@code testRefsAvailable=false}),
     * a compile-scope dep with no main references must be classified {@code UNDETERMINED}, not
     * {@code UNUSED}.
     * <p>
     * When {@code target/test-classes} is absent (e.g. {@code mvn compile} was run without
     * {@code mvn test-compile}), we cannot distinguish "dep is unused" from "dep is used only in
     * tests". The conservative classification is {@code UNDETERMINED} to avoid a false-positive
     * removal recommendation.
     * </p>
     */
    @Test
    void narrowableDepWithNoMainRefsAndUnavailableTestScanIsUndetermined() {
        var dep = new DependenciesTui.DepEntry("com.example", "compile-lib", "", "1.0", "compile", true);
        Map<String, File> gaToJar = Map.of();
        Map<String, String> classIndex = Map.of("com.example.Foo", "com.example:compile-lib");

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(
                        Set.of(), // mainRefs — dep not referenced in main bytecode
                        Set.of(), // testRefs — empty because test scan was not performed
                        classIndex,
                        gaToJar,
                        List.of(dep),
                        List.of(),
                        false); // testRefsAvailable=false: test-classes not scanned

        // Must be UNDETERMINED, not UNUSED — we cannot rule out test-only usage
        assertThat(result.declaredUsage())
                .as("compile-scope dep with no main refs and unavailable test scan must be UNDETERMINED, not UNUSED")
                .containsEntry("com.example:compile-lib", DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
    }

    @Test
    void graalvmBundledReflectConfigInDepJarIsUndetermined(@TempDir Path tempDir) throws Exception {
        // A dep that ships its own reflect-config.json under META-INF/native-image/
        // with no matching bytecode reference in the consumer → UNDETERMINED.
        Path tempJar = tempDir.resolve("graalvm-dep.jar");
        try (var os = Files.newOutputStream(tempJar);
                var jos = new JarOutputStream(os)) {
            jos.putNextEntry(new JarEntry("META-INF/native-image/com.example/mylib/reflect-config.json"));
            jos.write("[]".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        var dep = new DependenciesTui.DepEntry("com.example", "mylib", "", "1.0", "compile", true);
        Map<String, File> gaToJar = Map.of("com.example:mylib", tempJar.toFile());
        Map<String, String> classIndex = Map.of("com.example.MyClass", "com.example:mylib");

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of("com.app.Main"), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .containsEntry("com.example:mylib", DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
    }

    @Test
    void graalvmBundledReflectConfigWithMatchingClassIsUsed(@TempDir Path tempDir) throws Exception {
        // A dep that ships reflect-config.json naming a class that the consumer also imports
        // → the extracted class name matches the consumer's bytecode refs → USED.
        Path tempJar = tempDir.resolve("graalvm-dep.jar");
        try (var os = Files.newOutputStream(tempJar);
                var jos = new JarOutputStream(os)) {
            jos.putNextEntry(new JarEntry("META-INF/native-image/com.example/mylib/reflect-config.json"));
            String json = "[{\"name\":\"com.example.MyReflectedClass\"}]";
            jos.write(json.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        var dep = new DependenciesTui.DepEntry("com.example", "mylib", "", "1.0", "compile", true);
        Map<String, File> gaToJar = Map.of("com.example:mylib", tempJar.toFile());
        Map<String, String> classIndex = Map.of("com.example.MyReflectedClass", "com.example:mylib");

        // Consumer references the reflected class directly (e.g. via Class.forName constant)
        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(
                        Set.of("com.example.MyReflectedClass"),
                        Set.of(),
                        classIndex,
                        gaToJar,
                        List.of(dep),
                        List.of(),
                        true);

        assertThat(result.declaredUsage()).containsEntry("com.example:mylib", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    /**
     * GraalVM Native Image {@code reflect-config.json} references classes by name — those class
     * references are invisible to bytecode analysis (ASM). A dependency whose classes are only
     * referenced from {@code META-INF/native-image/**&#47;reflect-config.json} (and never imported
     * in bytecode) would be falsely flagged as {@code UNUSED} without explicit configuration.
     *
     * <p>This test documents the <em>correct</em> end-state: once the mojo parses
     * {@code reflect-config.json} and feeds the discovered class names into
     * {@link DependencyUsageAnalyzer.Builder#extraUsedClasses}, the dep is classified
     * {@code USED} (class name found in the dep's JAR).</p>
     *
     * <p>Implementation note (Option B): the mojo is responsible for parsing
     * {@code outputDirectory/META-INF/native-image/**&#47;reflect-config.json} and populating
     * {@code extraUsedClasses} before invoking the analyzer. The analyzer itself remains
     * purely bytecode/metadata-driven.</p>
     */
    @Test
    void graalvmReflectConfigClassMarksDepAsUsed() {
        // matchesExtraUsedClasses checks the class index, not JAR bytes — no JAR needed.
        var dep = new DependenciesTui.DepEntry("com.example", "mylib", "", "1.0", "compile", true);
        Map<String, File> gaToJar = Map.of();
        // Class is in the dep's index but never referenced in bytecode
        Map<String, String> classIndex = Map.of("com.example.MyReflectedClass", "com.example:mylib");

        // The mojo will parse reflect-config.json and feed the class name here (Option B)
        var result = DependencyUsageAnalyzer.builder()
                .extraUsedClasses(Map.of("com.example:mylib", List.of("com.example.MyReflectedClass")))
                .build()
                .analyze(
                        Set.of("com.app.Main"), // no bytecode reference to com.example.MyReflectedClass
                        Set.of(),
                        classIndex,
                        gaToJar,
                        List.of(dep),
                        List.of(),
                        true);

        // With extraUsedClasses populated (by the mojo from reflect-config.json),
        // the dep must be USED — the class exists in the index and is declared in the config
        assertThat(result.declaredUsage())
                .as("dep referenced only via reflect-config.json must be USED once the mojo feeds"
                        + " extraUsedClasses from native-image metadata")
                .containsEntry("com.example:mylib", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    /**
     * Counterpart to {@link #graalvmReflectConfigClassMarksDepAsUsed}: without the mojo parsing
     * {@code reflect-config.json} and populating {@code extraUsedClasses}, the same dep is
     * classified {@code UNUSED} — the current (broken) behaviour that issue #165 addresses.
     *
     * <p>This test is the red-light reproducer: it must pass until the mojo-side parsing is
     * implemented (at which point it becomes obsolete and can be removed).</p>
     */
    @Test
    void graalvmReflectConfigClassIsUnusedWithoutExplicitConfig() {
        // Same dep, but no JAR provided and no extraUsedClasses configured —
        // the mojo hasn't parsed reflect-config.json yet (current / broken state).
        // gaToJar is empty to avoid feeding an empty-byte JAR to the ASM-based scanner.
        var dep = new DependenciesTui.DepEntry("com.example", "mylib", "", "1.0", "compile", true);
        Map<String, File> gaToJar = Map.of();
        Map<String, String> classIndex = Map.of("com.example.MyReflectedClass", "com.example:mylib");

        // No extraUsedClasses — mojo hasn't parsed reflect-config.json yet (current state)
        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of("com.app.Main"), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        // Without the mojo feeding native-image metadata, the dep is falsely flagged UNUSED.
        // This is the false-positive that issue #165 / the mojo-side parsing will fix.
        assertThat(result.declaredUsage())
                .as("without reflect-config.json parsing, dep is falsely UNUSED (issue #165 reproducer)")
                .containsEntry("com.example:mylib", DependencyUsageAnalyzer.UsageStatus.UNUSED);
    }

    /**
     * Verifies that {@code resource-config.json} class references (GraalVM resource bundles) are
     * also covered: a dep providing only resource-accessed classes must not be flagged UNUSED once
     * the mojo feeds its class names via {@code extraUsedClasses}.
     */
    @Test
    void graalvmResourceConfigClassMarksDepAsUsed() {
        // matchesExtraUsedClasses checks the class index, not JAR bytes — no JAR needed.
        var dep = new DependenciesTui.DepEntry("com.example", "i18n-lib", "", "2.0", "runtime", true);
        Map<String, File> gaToJar = Map.of();
        Map<String, String> classIndex = Map.of("com.example.i18n.Messages", "com.example:i18n-lib");

        var result = DependencyUsageAnalyzer.builder()
                .extraUsedClasses(Map.of("com.example:i18n-lib", List.of("com.example.i18n.Messages")))
                .build()
                .analyze(Set.of("com.app.Main"), Set.of(), classIndex, gaToJar, List.of(dep), List.of(), true);

        assertThat(result.declaredUsage())
                .as("dep referenced only via resource-config.json must be USED once extraUsedClasses is fed")
                .containsEntry("com.example:i18n-lib", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    /**
     * Regression guard: when test bytecode was scanned and found no references
     * ({@code testRefsAvailable=true} with an empty {@code testRefs}), a compile-scope dep with
     * no main or test references must be classified {@code UNUSED}, not {@code UNDETERMINED}.
     * <p>
     * An empty {@code testRefs} from a completed scan is conclusive evidence: the dep is not
     * referenced from either main or test bytecode. It is genuinely unused.
     * </p>
     */
    @Test
    void narrowableDepWithNoRefsAndCompletedTestScanIsUnused() {
        var dep = new DependenciesTui.DepEntry("com.example", "compile-lib", "", "1.0", "compile", true);
        Map<String, File> gaToJar = Map.of();
        Map<String, String> classIndex = Map.of("com.example.Foo", "com.example:compile-lib");

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(
                        Set.of(), // mainRefs — dep not referenced in main bytecode
                        Set.of(), // testRefs — empty because scan found no references
                        classIndex,
                        gaToJar,
                        List.of(dep),
                        List.of(),
                        true); // testRefsAvailable=true: test-classes were scanned, just empty

        // Must be UNUSED — test was scanned, dep appears in neither main nor test bytecode
        assertThat(result.declaredUsage())
                .as("compile-scope dep with no refs from completed test scan must be UNUSED")
                .containsEntry("com.example:compile-lib", DependencyUsageAnalyzer.UsageStatus.UNUSED);
    }
}
