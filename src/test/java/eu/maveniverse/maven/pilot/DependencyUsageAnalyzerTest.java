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

        var result = DependencyUsageAnalyzer.analyze(
                Set.of("com.example.Foo"), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

        assertThat(result.declaredUsage())
                .containsEntry("com.example:used-lib", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void unusedDeclaredDependency() {
        var dep = new DependenciesTui.DepEntry("com.example", "unused-lib", "", "1.0", "compile", true);

        Map<String, String> classIndex = Map.of("com.example.Bar", "com.example:unused-lib");
        Map<String, File> gaToJar = Map.of();

        var result = DependencyUsageAnalyzer.analyze(
                Set.of("com.other.Unrelated"), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

        assertThat(result.declaredUsage())
                .containsEntry("com.example:unused-lib", DependencyUsageAnalyzer.UsageStatus.UNUSED);
    }

    @Test
    void usedTransitiveDependency() {
        var dep = new DependenciesTui.DepEntry("com.transitive", "lib", "", "1.0", "compile", false);
        dep.pulledBy = "com.example:parent";

        Map<String, String> classIndex = Map.of("com.transitive.Helper", "com.transitive:lib");
        Map<String, File> gaToJar = Map.of();

        var result = DependencyUsageAnalyzer.analyze(
                Set.of("com.transitive.Helper"), Set.of(), classIndex, gaToJar, List.of(), List.of(dep));

        assertThat(result.transitiveUsage())
                .containsEntry("com.transitive:lib", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void testScopedDepCheckedAgainstTestRefs() {
        var dep = new DependenciesTui.DepEntry("org.junit", "junit-api", "", "5.0", "test", true);

        Map<String, String> classIndex = Map.of("org.junit.Test", "org.junit:junit-api");
        Map<String, File> gaToJar = Map.of();

        // Not in main refs but in test refs — should be USED
        var result = DependencyUsageAnalyzer.analyze(
                Set.of(), Set.of("org.junit.Test"), classIndex, gaToJar, List.of(dep), List.of());

        assertThat(result.declaredUsage())
                .containsEntry("org.junit:junit-api", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void testOnlyScopedDepCheckedAgainstTestRefs() {
        var dep = new DependenciesTui.DepEntry("org.junit", "junit-api", "", "5.0", "test-only", true);

        Map<String, String> classIndex = Map.of("org.junit.Test", "org.junit:junit-api");
        Map<String, File> gaToJar = Map.of();

        // Maven 4 "test-only" scope: should be checked against allRefs (main + test)
        var result = DependencyUsageAnalyzer.analyze(
                Set.of(), Set.of("org.junit.Test"), classIndex, gaToJar, List.of(dep), List.of());

        assertThat(result.declaredUsage())
                .containsEntry("org.junit:junit-api", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void testRuntimeScopedDepCheckedAgainstTestRefs() {
        var dep = new DependenciesTui.DepEntry("org.example", "test-util", "", "1.0", "test-runtime", true);

        Map<String, String> classIndex = Map.of("org.example.TestUtil", "org.example:test-util");
        Map<String, File> gaToJar = Map.of();

        // Maven 4 "test-runtime" scope: should be checked against allRefs (main + test)
        var result = DependencyUsageAnalyzer.analyze(
                Set.of(), Set.of("org.example.TestUtil"), classIndex, gaToJar, List.of(dep), List.of());

        assertThat(result.declaredUsage())
                .containsEntry("org.example:test-util", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void compileScopedDepNotCheckedAgainstTestRefs() {
        var dep = new DependenciesTui.DepEntry("com.example", "lib", "", "1.0", "compile", true);

        Map<String, String> classIndex = Map.of("com.example.Foo", "com.example:lib");
        Map<String, File> gaToJar = Map.of();

        // Only in test refs, not main refs — compile-scoped dep should be UNUSED
        var result = DependencyUsageAnalyzer.analyze(
                Set.of(), Set.of("com.example.Foo"), classIndex, gaToJar, List.of(dep), List.of());

        assertThat(result.declaredUsage()).containsEntry("com.example:lib", DependencyUsageAnalyzer.UsageStatus.UNUSED);
    }

    @Test
    void undeterminedWhenNoClassesInIndex() {
        var dep = new DependenciesTui.DepEntry("com.mystery", "lib", "", "1.0", "compile", true);

        // No classes mapped to this GA in the index
        Map<String, String> classIndex = Map.of();
        Map<String, File> gaToJar = Map.of();

        var result = DependencyUsageAnalyzer.analyze(
                Set.of("com.example.Foo"), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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

        var result = DependencyUsageAnalyzer.analyze(
                Set.of("com.example.SomeService"), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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

        var result = DependencyUsageAnalyzer.analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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

        var result = DependencyUsageAnalyzer.analyze(Set.of(), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

        assertThat(result.declaredUsage())
                .containsEntry("org.projectlombok:lombok", DependencyUsageAnalyzer.UsageStatus.USED);
    }

    @Test
    void serviceEntryWithSubdirectoryIgnored(@TempDir Path tempDir) throws Exception {
        Path tempJar = tempDir.resolve("subdir-lib.jar");
        // Subdirectory under services — should NOT be treated as a service interface
        createJarWithEntries(tempJar, "META-INF/services/com/example/SomeService");

        var dep = new DependenciesTui.DepEntry("com.example", "lib", "", "1.0", "compile", true);
        Map<String, File> gaToJar = Map.of("com.example:lib", tempJar.toFile());
        Map<String, String> classIndex = Map.of();

        var result = DependencyUsageAnalyzer.analyze(
                Set.of("com.example.SomeService"), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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

        var result = DependencyUsageAnalyzer.analyze(
                Set.of("javax.inject.Named"), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

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

        var result = DependencyUsageAnalyzer.analyze(
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

        var result = DependencyUsageAnalyzer.analyze(
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

        var result = DependencyUsageAnalyzer.analyze(
                Set.of("com.other.Unrelated"), Set.of(), classIndex, gaToJar, List.of(dep), List.of());

        assertThat(result.declaredUsage())
                .containsEntry("com.example:unused-svc", DependencyUsageAnalyzer.UsageStatus.UNDETERMINED);
    }
}
