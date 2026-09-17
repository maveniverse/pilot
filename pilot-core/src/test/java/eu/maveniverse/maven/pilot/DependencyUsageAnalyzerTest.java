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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
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
                .analyze(Set.of("com.example.Foo"), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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
                .analyze(Set.of("com.other.Unrelated"), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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
                .analyze(Set.of("com.transitive.Helper"), Set.of(), classIndex, gaToJar, List.of(), List.of(dep));

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
                .analyze(Set.of("com.other.Unrelated"), Set.of(), classIndex, gaToJar, List.of(), List.of(dep));

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
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(), List.of(dep));

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
                .analyze(Set.of(), Set.of("org.junit.Test"), classIndex, gaToJar, List.of(dep), List.of());

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
                .analyze(Set.of(), Set.of("org.junit.Test"), classIndex, gaToJar, List.of(dep), List.of());

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
                .analyze(Set.of(), Set.of("org.example.TestUtil"), classIndex, gaToJar, List.of(dep), List.of());

        assertThat(result.declaredUsage())
                .containsEntry("org.example:test-util", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void compileScopedDepNotCheckedAgainstTestRefs() {
        var dep = new DependenciesTui.DepEntry("com.example", "lib", "", "1.0", "compile", true);

        Map<String, String> classIndex = Map.of("com.example.Foo", "com.example:lib");
        Map<String, File> gaToJar = Map.of();

        // Only in test refs, not main refs — compile-scoped dep should be UNUSED
        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of(), Set.of("com.example.Foo"), classIndex, gaToJar, List.of(dep), List.of());

        assertThat(result.declaredUsage()).containsEntry("com.example:lib", DependencyUsageAnalyzer.UsageStatus.UNUSED);
    }

    @Test
    void undeterminedWhenNoClassesInIndex() {
        var dep = new DependenciesTui.DepEntry("com.mystery", "lib", "", "1.0", "compile", true);

        // No classes mapped to this GA in the index
        Map<String, String> classIndex = Map.of();
        Map<String, File> gaToJar = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .build()
                .analyze(Set.of("com.example.Foo"), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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
                "module-info.class", // should
                // be
                // excluded
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
                .analyze(Set.of("com.example.SomeService"), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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
                .analyze(Set.of("com.example.SomeService"), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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
                .analyze(Set.of("javax.inject.Named"), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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
                        List.of());

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
                        List.of());

        assertThat(result.declaredUsage())
                .containsEntry("com.example:spring-boot-lib", DependencyUsageAnalyzer.UsageStatus.USED);
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
                .analyze(Set.of("com.other.Unrelated"), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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
                .analyze(Set.of("com.other.Unrelated"), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

        assertThat(result.declaredUsage())
                .containsEntry("org.projectlombok:lombok", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void reflectionLoadedClassMarksAsUsedWhenClassPresent() {
        var dep = new DependenciesTui.DepEntry("org.postgresql", "postgresql", "", "42.7", "runtime", true);

        Map<String, String> classIndex = Map.of("org.postgresql.Driver", "org.postgresql:postgresql");
        Map<String, File> gaToJar = Map.of();

        var result = DependencyUsageAnalyzer.builder()
                .reflectionLoadedClasses(Map.of("org.postgresql:postgresql", List.of("org.postgresql.Driver")))
                .build()
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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
                .reflectionLoadedClasses(Map.of("org.postgresql:postgresql", List.of("org.postgresql.Driver")))
                .build()
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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
                .reflectionLoadedClasses(Map.of("org.postgresql:postgresql", List.of("org.postgresql.Driver")))
                .build()
                .analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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
                        List.of());

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
                .analyze(Set.of("com.other.Unrelated"), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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
                        List.of());

        assertThat(result.declaredUsage())
                .containsEntry("org.apache.maven:maven-di", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void mavenDiEntryDetectedByHasMavenDiOrSisu(@TempDir Path tempDir) throws Exception {
        Path tempJar = tempDir.resolve("maven-di.jar");
        createJarWithEntries(tempJar, "META-INF/maven/org.apache.maven.api.di.Inject");
        assertThat(DependencyUsageAnalyzer.hasMavenDiOrSisuRegistration(tempJar.toFile()))
                .isTrue();
    }

    @Test
    void pomMetadataEntryNotMistokenForMavenDi(@TempDir Path tempDir) throws Exception {
        // META-INF/maven/org.apache.maven/maven-jline/pom.xml is POM metadata, not a DI index
        Path tempJar = tempDir.resolve("regular.jar");
        createJarWithEntries(tempJar, "META-INF/maven/org.apache.maven/maven-jline/pom.xml");
        assertThat(DependencyUsageAnalyzer.hasMavenDiOrSisuRegistration(tempJar.toFile()))
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
                .analyze(Set.of("com.other.Unrelated"), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

        assertThat(result.declaredUsage())
                .containsEntry("com.example:svc", DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
    }

    // --- SLF4J binding / logging backend false-positive reproducer ---

    /**
     * Reproducer for the SLF4J binding false-positive.
     * <p>
     * {@code log4j-slf4j2-impl} ships {@code META-INF/services/org.slf4j.spi.SLF4JServiceProvider}, which is loaded by
     * the SLF4J framework at runtime via {@link java.util.ServiceLoader}. The consuming module (e.g.
     * {@code camel-timer}) never references {@code org.slf4j.spi.SLF4JServiceProvider} directly in its bytecode — SLF4J
     * does that internally. Pilot's {@code classifyByRuntimeDiscovery} detects the service entry but, because the
     * consumer has no bytecode reference to the service interface, falls through to the
     * {@code depClasses != null → UNUSED} branch and reports it as unused.
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
                        List.of());

        // Should NOT be UNUSED — it is a ServiceLoader-based SLF4J backend.
        // Acceptable statuses: USED (preferred) or UNDETERMINED.
        assertThat(result.declaredUsage().get("org.apache.logging.log4j:log4j-slf4j2-impl"))
                .isNotEqualTo(DependencyUsageAnalyzer.UsageStatus.UNUSED);
    }
}
