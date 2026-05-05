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

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Builds a class-to-artifact index from resolved JARs and classifies dependency usage
 * based on bytecode analysis results.
 *
 * <p>The analyzer supports three optional allowlists that override the default classification
 * for dependencies that are legitimately used but cannot be detected through bytecode analysis:</p>
 * <ul>
 *   <li><b>runtimeArtifacts</b> — artifacts needed only at runtime (JDBC drivers, SLF4J backends)</li>
 *   <li><b>annotationOnlyArtifacts</b> — artifacts providing source/class-retention annotations</li>
 *   <li><b>reflectionLoadedClasses</b> — classes loaded via reflection, verified against the JAR</li>
 * </ul>
 *
 * <p>Use {@link #builder()} to configure allowlists:</p>
 * <pre>{@code
 * DependencyUsageAnalyzer analyzer = DependencyUsageAnalyzer.builder()
 *     .runtimeArtifacts(Set.of("org.postgresql:postgresql"))
 *     .annotationOnlyArtifacts(Set.of("org.projectlombok:lombok"))
 *     .reflectionLoadedClasses(Map.of(
 *         "org.postgresql:postgresql", List.of("org.postgresql.Driver")))
 *     .build();
 * }</pre>
 */
public final class DependencyUsageAnalyzer {

    private static final String META_INF_SERVICES = "META-INF/services/";
    private static final String META_INF_SISU = "META-INF/sisu/";
    private static final Set<String> TEST_SCOPES = Set.of("test", "test-only", "test-runtime");

    public enum UsageStatus {
        USED,
        UNUSED,
        UNDETERMINED
    }

    public record AnalysisResult(Map<String, UsageStatus> declaredUsage, Map<String, UsageStatus> transitiveUsage) {}

    private final Set<String> runtimeArtifacts;
    private final Set<String> annotationOnlyArtifacts;
    private final Map<String, List<String>> reflectionLoadedClasses;

    private DependencyUsageAnalyzer(Builder builder) {
        this.runtimeArtifacts = Set.copyOf(builder.runtimeArtifacts);
        this.annotationOnlyArtifacts = Set.copyOf(builder.annotationOnlyArtifacts);
        HashMap<String, List<String>> copy = new HashMap<>();
        builder.reflectionLoadedClasses.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        this.reflectionLoadedClasses = Collections.unmodifiableMap(copy);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Build an index mapping fully-qualified class names to their providing artifact's
     * {@code groupId:artifactId}.
     *
     * @param gaToJar map from GA string to resolved JAR file
     * @return map from class name (dot-separated) to GA string
     */
    @SuppressWarnings("java:S5042") // JARs are from Maven's local repository, already verified
    public static Map<String, String> buildClassIndex(Map<String, File> gaToJar) {
        Map<String, String> index = new HashMap<>();
        for (var entry : gaToJar.entrySet()) {
            String ga = entry.getKey();
            File jarFile = entry.getValue();
            try (JarFile jar = new JarFile(jarFile)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry je = entries.nextElement();
                    String name = je.getName();
                    if (name.endsWith(".class") && !name.equals("module-info.class") && !name.startsWith("META-INF/")) {
                        // Convert path (com/example/Foo.class) to class name (com.example.Foo)
                        String className = name.substring(0, name.length() - 6).replace('/', '.');
                        index.put(className, ga);
                    }
                }
            } catch (IOException ignored) {
                // skip unreadable JARs
            }
        }
        return index;
    }

    /**
     * Classify each dependency as USED, UNUSED, or UNDETERMINED based on bytecode references.
     *
     * @param mainRefs     class names referenced from {@code target/classes}
     * @param testRefs     class names referenced from {@code target/test-classes}
     * @param classIndex   class name to GA mapping (from {@link #buildClassIndex})
     * @param gaToJar      GA to JAR file mapping (for annotation processor detection)
     * @param declared     declared dependency entries
     * @param transitive   transitive dependency entries
     * @return analysis result with usage status for each dependency
     */
    public AnalysisResult analyze(
            Set<String> mainRefs,
            Set<String> testRefs,
            Map<String, String> classIndex,
            Map<String, File> gaToJar,
            List<DependenciesTui.DepEntry> declared,
            List<DependenciesTui.DepEntry> transitive) {

        // Build reverse index: GA -> set of class names provided by that artifact
        Map<String, Set<String>> gaToClasses = new HashMap<>();
        for (var entry : classIndex.entrySet()) {
            gaToClasses.computeIfAbsent(entry.getValue(), k -> new HashSet<>()).add(entry.getKey());
        }

        // Combined refs for test scope checking
        Set<String> allRefs = new HashSet<>(mainRefs);
        allRefs.addAll(testRefs);

        Map<String, UsageStatus> declaredUsage = new HashMap<>();
        for (var dep : declared) {
            declaredUsage.put(dep.ga(), classify(dep, gaToClasses, gaToJar, mainRefs, allRefs));
        }

        Map<String, UsageStatus> transitiveUsage = new HashMap<>();
        for (var dep : transitive) {
            transitiveUsage.put(dep.ga(), classify(dep, gaToClasses, gaToJar, mainRefs, allRefs));
        }

        return new AnalysisResult(declaredUsage, transitiveUsage);
    }

    private UsageStatus classify(
            DependenciesTui.DepEntry dep,
            Map<String, Set<String>> gaToClasses,
            Map<String, File> gaToJar,
            Set<String> mainRefs,
            Set<String> allRefs) {

        // Choose the appropriate reference set based on scope.
        // Maven 3 scopes: compile, provided, runtime, test, system.
        // Maven 4.1.0+ adds: compile-only, test-only, test-runtime.
        // Test-related scopes are checked against allRefs (main + test); others against mainRefs only.
        Set<String> refs = TEST_SCOPES.contains(dep.scope) ? allRefs : mainRefs;

        Set<String> depClasses = gaToClasses.get(dep.ga());
        if (depClasses != null && !Collections.disjoint(depClasses, refs)) {
            return UsageStatus.USED;
        }

        if (isUsedByRuntimeDiscovery(dep, gaToJar, refs)) {
            return UsageStatus.USED;
        }

        if (matchesArtifactPattern(dep.ga(), annotationOnlyArtifacts)
                || matchesArtifactPattern(dep.ga(), runtimeArtifacts)
                || matchesReflectionLoadedClasses(dep.ga(), depClasses)) {
            return UsageStatus.USED;
        }

        return depClasses == null ? UsageStatus.UNDETERMINED : UsageStatus.UNUSED;
    }

    private static boolean isUsedByRuntimeDiscovery(
            DependenciesTui.DepEntry dep, Map<String, File> gaToJar, Set<String> refs) {
        File jarFile = gaToJar.get(dep.ga());
        if (jarFile == null) {
            return false;
        }
        Set<String> discoveryClasses = getRuntimeDiscoveryClasses(jarFile);
        if (!Collections.disjoint(discoveryClasses, refs)) {
            return true;
        }
        // Annotation processors are used at compile time without direct bytecode references.
        // They can be declared with "provided" or "compile" scope (e.g. Lombok).
        return ("provided".equals(dep.scope) || "compile".equals(dep.scope))
                && discoveryClasses.contains("javax.annotation.processing.Processor");
    }

    private boolean matchesReflectionLoadedClasses(String ga, Set<String> depClasses) {
        if (depClasses == null) {
            return false;
        }
        List<String> expectedClasses = reflectionLoadedClasses.get(ga);
        if (expectedClasses == null) {
            return false;
        }
        for (String cls : expectedClasses) {
            if (depClasses.contains(cls)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check whether a dependency's GA coordinates match any pattern in the given set.
     * Supports exact matches ({@code groupId:artifactId}) and wildcard matches ({@code groupId:*}).
     * For dependencies with a classifier ({@code groupId:artifactId:classifier}), the pattern
     * is also matched against the base {@code groupId:artifactId}.
     */
    static boolean matchesArtifactPattern(String ga, Set<String> patterns) {
        if (patterns.isEmpty()) {
            return false;
        }
        if (patterns.contains(ga)) {
            return true;
        }
        int firstColon = ga.indexOf(':');
        if (firstColon > 0) {
            // Check wildcard: groupId:*
            if (patterns.contains(ga.substring(0, firstColon) + ":*")) {
                return true;
            }
            // For classifier deps (g:a:c), also try matching g:a
            int secondColon = ga.indexOf(':', firstColon + 1);
            if (secondColon > 0) {
                return patterns.contains(ga.substring(0, secondColon));
            }
        }
        return false;
    }

    /**
     * Extract class names used for runtime discovery from a JAR.
     *
     * <p>Covers three conventions:</p>
     * <ul>
     *   <li><b>ServiceLoader</b>: {@code META-INF/services/<interface>}</li>
     *   <li><b>Sisu/JSR-330</b>: {@code META-INF/sisu/<annotation>}</li>
     *   <li><b>Spring</b>: {@code META-INF/spring.components} and {@code META-INF/spring.factories}
     *       — reads the keys/values to extract referenced class names</li>
     * </ul>
     */
    @SuppressWarnings("java:S5042") // JARs are from Maven's local repository, already verified
    public static Set<String> getRuntimeDiscoveryClasses(File jarFile) {
        Set<String> classes = new HashSet<>();
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                addIfServiceEntry(name, META_INF_SERVICES, classes);
                addIfServiceEntry(name, META_INF_SISU, classes);
            }
            // Spring component index: META-INF/spring.components
            if (jar.getEntry("META-INF/spring.components") != null) {
                classes.add("org.springframework.stereotype.Component");
            }
            // Spring Boot auto-configuration
            if (jar.getEntry("META-INF/spring.factories") != null
                    || jar.getEntry("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
                            != null) {
                classes.add("org.springframework.boot.autoconfigure.EnableAutoConfiguration");
            }
        } catch (IOException ignored) {
            // skip unreadable JARs
        }
        return classes;
    }

    private static void addIfServiceEntry(String name, String prefix, Set<String> classes) {
        if (name.startsWith(prefix) && !name.equals(prefix)) {
            String entry = name.substring(prefix.length());
            if (!entry.contains("/") && !entry.isEmpty()) {
                classes.add(entry);
            }
        }
    }

    public static final class Builder {
        private Set<String> runtimeArtifacts = Set.of();
        private Set<String> annotationOnlyArtifacts = Set.of();
        private Map<String, List<String>> reflectionLoadedClasses = Map.of();

        private Builder() {}

        /**
         * Artifacts that are needed only at runtime and never referenced in bytecode
         * (JDBC drivers, SLF4J backends, XML parser implementations, JVM agents).
         * Patterns: {@code groupId:artifactId} or {@code groupId:*}.
         */
        public Builder runtimeArtifacts(Set<String> patterns) {
            this.runtimeArtifacts = patterns;
            return this;
        }

        /**
         * Artifacts that provide only source- or class-retention annotations which are
         * erased during compilation (Lombok, SpotBugs annotations, ErrorProne).
         * Patterns: {@code groupId:artifactId} or {@code groupId:*}.
         */
        public Builder annotationOnlyArtifacts(Set<String> patterns) {
            this.annotationOnlyArtifacts = patterns;
            return this;
        }

        /**
         * Classes known to be loaded via reflection, mapped by their providing artifact.
         * Keys must be exact {@code groupId:artifactId} coordinates; wildcard patterns
         * like {@code groupId:*} are not supported. During classification the artifact's
         * class index is checked to verify the class is actually present, so the allowlist
         * stays valid even if a class moves.
         */
        public Builder reflectionLoadedClasses(Map<String, List<String>> classes) {
            this.reflectionLoadedClasses = classes;
            return this;
        }

        public DependencyUsageAnalyzer build() {
            return new DependencyUsageAnalyzer(this);
        }
    }
}
