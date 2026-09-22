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
 * Builds a class-to-artifact index from resolved JARs and classifies dependency usage based on bytecode analysis
 * results.
 * <p>
 * The analyzer supports three optional allowlists that override the default classification for dependencies that are
 * legitimately used but cannot be detected through bytecode analysis:
 * </p>
 * <ul>
 * <li><b>runtimeArtifacts</b> — artifacts needed only at runtime (JDBC drivers, SLF4J backends)</li>
 * <li><b>annotationOnlyArtifacts</b> — artifacts providing source/class-retention annotations</li>
 * <li><b>extraUsedClasses</b> — classes invisible to bytecode analysis (reflection, native-image
 *     metadata), verified against the JAR's class index</li>
 * </ul>
 * <p>
 * Use {@link #builder()} to configure allowlists:
 * </p>
 *
 * <pre>{@code
 * DependencyUsageAnalyzer analyzer = DependencyUsageAnalyzer.builder()
 *         .runtimeArtifacts(Set.of("org.postgresql:postgresql"))
 *         .annotationOnlyArtifacts(Set.of("org.projectlombok:lombok"))
 *         .extraUsedClasses(Map.of("org.postgresql:postgresql", List.of("org.postgresql.Driver"))).build();
 * }</pre>
 */
public final class DependencyUsageAnalyzer {

    /**
     * Ordered list of runtime-discovery conventions used by {@link #scanDiscoveryMetadata}.
     *
     * <p>Order matters: {@link DiscoveryConvention.CamelConvention} must precede
     * {@link DiscoveryConvention.ServiceLoaderConvention} because Camel paths ({@code
     * META-INF/services/org/apache/camel/…}) are a sub-path of {@code META-INF/services/}.</p>
     */
    private static final List<DiscoveryConvention> CONVENTIONS = List.of(
            new DiscoveryConvention.SisuConvention(),
            new DiscoveryConvention.MavenDiConvention(),
            new DiscoveryConvention.CamelConvention(), // before ServiceLoader — overlapping prefix
            new DiscoveryConvention.SpringBootConvention(),
            new DiscoveryConvention.QuarkusConvention(),
            new DiscoveryConvention.GraalVmConvention(),
            new DiscoveryConvention.ServiceLoaderConvention() // catch-all, must be last
            );

    private static final Set<String> TEST_SCOPES = Set.of("test", "test-only", "test-runtime");

    /**
     * Scopes that can be narrowed to {@code test} when a dependency's classes are used only in tests.
     *
     * <p>Only {@code compile} and {@code compile-only} are eligible:
     * <ul>
     * <li>{@code provided} — the container supplies the artifact at runtime; narrowing to {@code test}
     *     would silently remove it from the production runtime classpath.</li>
     * <li>{@code runtime} — the artifact is loaded at runtime without bytecode references from main
     *     sources; test-only class references do not justify narrowing it to {@code test} scope.</li>
     * </ul>
     */
    private static final Set<String> NARROWABLE_TO_TEST_SCOPES = Set.of("compile", "compile-only");

    /**
     * Returns {@code true} if the given Maven scope is test-only
     * ({@code test}, {@code test-only}, or {@code test-runtime}).
     *
     * <p>Test-scoped dependencies are checked against both main and test bytecode
     * references. When test classes have not been compiled, their usage cannot be
     * determined from bytecode analysis alone.</p>
     */
    public static boolean isTestScope(String scope) {
        return scope != null && TEST_SCOPES.contains(scope);
    }

    /**
     * Returns {@code true} if the given Maven scope can be narrowed to {@code test} when the
     * dependency's classes are used only in test bytecode.
     *
     * <p>Only {@code compile} and {@code compile-only} are narrowable; {@code provided} and
     * {@code runtime} must remain on their original scope to preserve runtime correctness.</p>
     */
    public static boolean isNarrowableToTestScope(String scope) {
        return scope != null && NARROWABLE_TO_TEST_SCOPES.contains(scope);
    }

    /**
     * Scopes in which annotation processors may be legitimately declared. An annotation processor JAR (one whose
     * {@code META-INF/services/javax.annotation.processing.Processor} entry is present) is considered used when
     * declared in any of these scopes:
     * <ul>
     * <li>{@code compile} — common for processors bundled with annotations (e.g. Lombok)</li>
     * <li>{@code provided} — processor applied at compile time, not needed at runtime</li>
     * <li>{@code test} — processor applied only during test compilation (e.g. jmh-generator-annprocess)</li>
     * <li>{@code test-only} — Maven 4 equivalent of {@code test}</li>
     * <li>{@code compile-only} — Maven 4 equivalent of {@code provided}</li>
     * </ul>
     * {@code runtime} and {@code test-runtime} are intentionally excluded: annotation processors must be on the compile
     * classpath to be invoked by {@code javac}; dependencies declared with those scopes are only available at runtime
     * and would never be invoked as processors.
     */
    private static final Set<String> ANNOTATION_PROCESSOR_SCOPES =
            Set.of("compile", "provided", "test", "test-only", "compile-only");

    public enum UsageStatus {
        USED,
        /**
         * The dependency's classes are referenced only from test sources (target/test-classes),
         * not from main sources (target/classes). For compile-scope dependencies, this means
         * the scope should be narrowed to {@code test} rather than removed.
         */
        USED_IN_TEST,
        UNUSED,
        UNDETERMINED
    }

    public record AnalysisResult(Map<String, UsageStatus> declaredUsage, Map<String, UsageStatus> transitiveUsage) {}

    private final Set<String> runtimeArtifacts;
    private final Set<String> annotationOnlyArtifacts;
    private final Map<String, List<String>> extraUsedClasses;
    private final Map<String, Boolean> inlineableConstantsCache = new HashMap<>();

    private DependencyUsageAnalyzer(Builder builder) {
        this.runtimeArtifacts = Set.copyOf(builder.runtimeArtifacts);
        this.annotationOnlyArtifacts = Set.copyOf(builder.annotationOnlyArtifacts);
        HashMap<String, List<String>> copy = new HashMap<>();
        builder.extraUsedClasses.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        this.extraUsedClasses = Collections.unmodifiableMap(copy);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Build an index mapping fully-qualified class names to their providing artifact's {@code groupId:artifactId}.
     *
     * @param gaToJar
     *            map from GA string to resolved JAR file
     *
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
     * @param mainRefs
     *            class names referenced from {@code target/classes}
     * @param testRefs
     *            class names referenced from {@code target/test-classes}; an empty set is valid when test analysis
     *            was performed and found no references, but callers must also pass {@code testRefsAvailable=true}
     *            to distinguish this from "test bytecode was not scanned at all"
     * @param classIndex
     *            class name to GA mapping (from {@link #buildClassIndex})
     * @param gaToJar
     *            GA to JAR file mapping (for annotation processor detection)
     * @param declared
     *            declared dependency entries
     * @param transitive
     *            transitive dependency entries
     * @param testRefsAvailable
     *            {@code true} when {@code testRefs} was populated from an actual scan of
     *            {@code target/test-classes}; {@code false} when test compilation was skipped or
     *            {@code target/test-classes} was absent. When {@code false}, narrowable dependencies
     *            with no main references are classified as {@code UNDETERMINED} rather than
     *            {@code USED_IN_TEST} or {@code UNUSED}, because the absence of test references is
     *            not evidence that the dep is unused — the test scan simply did not run.
     *
     * @return analysis result with usage status for each dependency
     */
    public AnalysisResult analyze(
            Set<String> mainRefs,
            Set<String> testRefs,
            Map<String, String> classIndex,
            Map<String, File> gaToJar,
            List<DependenciesTui.DepEntry> declared,
            List<DependenciesTui.DepEntry> transitive,
            boolean testRefsAvailable) {

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
            declaredUsage.put(
                    dep.ga(), classify(dep, gaToClasses, gaToJar, mainRefs, testRefs, allRefs, testRefsAvailable));
        }

        Map<String, UsageStatus> transitiveUsage = new HashMap<>();
        for (var dep : transitive) {
            transitiveUsage.put(
                    dep.ga(), classify(dep, gaToClasses, gaToJar, mainRefs, testRefs, allRefs, testRefsAvailable));
        }

        return new AnalysisResult(declaredUsage, transitiveUsage);
    }

    private UsageStatus classify(
            DependenciesTui.DepEntry dep,
            Map<String, Set<String>> gaToClasses,
            Map<String, File> gaToJar,
            Set<String> mainRefs,
            Set<String> testRefs,
            Set<String> allRefs,
            boolean testRefsAvailable) {

        // Choose the appropriate reference set based on scope.
        // Maven 3 scopes: compile, provided, runtime, test, system.
        // Maven 4.1.0+ adds: compile-only, test-only, test-runtime.
        // Test-related scopes are checked against allRefs (main + test); others against mainRefs only.
        boolean testScope = isTestScope(dep.scope);
        Set<String> refs = testScope ? allRefs : mainRefs;

        Set<String> depClasses = gaToClasses.get(dep.ga());
        if (depClasses != null && !Collections.disjoint(depClasses, refs)) {
            return UsageStatus.USED;
        }

        // Check explicit allowlists before runtime-discovery classification, so that a user-supplied
        // runtimeArtifacts or annotationOnlyArtifacts entry always wins and is never shadowed by the
        // UNDETERMINED result that classifyByRuntimeDiscovery returns for ServiceLoader-registered deps.
        // This must also run before the USED_IN_TEST check: a dep in runtimeArtifacts or
        // annotationOnlyArtifacts may have its classes referenced only in test bytecode (e.g. an
        // annotation processor used from test code), and must be classified USED — not narrowed to
        // test scope — to avoid removing it from the production runtime classpath.
        if (matchesArtifactPattern(dep.ga(), annotationOnlyArtifacts)
                || matchesArtifactPattern(dep.ga(), runtimeArtifacts)
                || matchesExtraUsedClasses(dep.ga(), depClasses)) {
            return UsageStatus.USED;
        }

        // Run runtime-discovery classification before the USED_IN_TEST check: a dep with
        // ServiceLoader or DI registration metadata may have its classes referenced only in test
        // bytecode (e.g. an SLF4J backend exercised by a test), but narrowing its scope to test
        // would be wrong — the DI/SPI container loads it at runtime in production. UNDETERMINED
        // is the safe return value in that case.
        UsageStatus discoveryStatus = classifyByRuntimeDiscovery(dep, gaToJar, refs);
        if (discoveryStatus != null) {
            return discoveryStatus;
        }

        // For compile-like scopes (compile, compile-only), check whether the dep is used exclusively
        // in tests. If so, it should be narrowed to test scope rather than removed.
        // This runs after the allowlists and after runtime-discovery so that runtime/annotation-only
        // deps and SPI/DI-registered deps are not mistakenly narrowed when their classes appear only
        // in test bytecode.
        // NOTE: "provided" and "runtime" scopes are intentionally excluded — see NARROWABLE_TO_TEST_SCOPES.
        UsageStatus testScopeStatus = classifyTestScopeNarrowing(dep.scope, depClasses, testRefs, testRefsAvailable);
        if (testScopeStatus != null) {
            return testScopeStatus;
        }

        // A JAR that contains public static final String/int/… fields (ConstantValue attribute)
        // may have had those constants inlined by javac at every call site.  Inlined constants
        // produce an LDC instruction — not a GETSTATIC — so the declaring class never appears in
        // the consumer's bytecode.  We cannot distinguish "truly unused" from "all usages were
        // inlined", so we conservatively classify such deps as UNDETERMINED.
        if (depClasses != null && hasInlineableConstants(dep.ga(), gaToJar)) {
            return UsageStatus.UNDETERMINED;
        }

        return depClasses == null ? UsageStatus.UNDETERMINED : UsageStatus.UNUSED;
    }

    /**
     * Determine whether a compile-like-scoped dependency should be classified as {@link UsageStatus#USED_IN_TEST}.
     * <p>
     * Only classify {@code USED_IN_TEST} when test refs were actually scanned. When {@code testRefsAvailable} is
     * {@code false} (test compilation was skipped or {@code target/test-classes} was absent), the absence of test
     * references is not evidence of test-only usage — the scan simply did not run. In that case,
     * treat a narrowable dep with known classes and no main refs as {@code UNDETERMINED}.
     * </p>
     *
     * @return {@link UsageStatus#USED_IN_TEST} if the dep is used exclusively in tests,
     *         {@link UsageStatus#UNDETERMINED} if test bytecode was not scanned, or
     *         {@code null} if this check does not apply (non-narrowable scope or no known classes).
     */
    private UsageStatus classifyTestScopeNarrowing(
            String scope, Set<String> depClasses, Set<String> testRefs, boolean testRefsAvailable) {
        if (!isNarrowableToTestScope(scope) || depClasses == null) {
            return null;
        }
        if (!testRefsAvailable) {
            // Test bytecode was not scanned — cannot distinguish test-only from genuinely unused.
            return UsageStatus.UNDETERMINED;
        }
        return Collections.disjoint(depClasses, testRefs) ? null : UsageStatus.USED_IN_TEST;
    }

    /**
     * Classify a dependency based on runtime-discovery metadata (ServiceLoader, Sisu, Maven DI).
     * <p>
     * Returns:
     * </p>
     * <ul>
     * <li>{@link UsageStatus#USED} — the dep's registered service interface is directly referenced by the consuming
     * module's bytecode, or it is an annotation processor.</li>
     * <li>{@link UsageStatus#UNDETERMINED} — the dep's JAR contains DI/SPI registration metadata (Maven DI, Sisu) but
     * its wiring interface is not in the consumer's bytecode. The DI container resolves these at runtime without direct
     * class references, so we cannot determine usage from bytecode alone.</li>
     * <li>{@code null} — the dep has no runtime-discovery metadata; caller decides.</li>
     * </ul>
     */
    private static UsageStatus classifyByRuntimeDiscovery(
            DependenciesTui.DepEntry dep, Map<String, File> gaToJar, Set<String> refs) {
        File jarFile = gaToJar.get(dep.ga());
        if (jarFile == null) {
            return null;
        }
        DiscoveryInfo info = scanDiscoveryMetadata(jarFile);
        if (!info.hasAnyDiscoveryMetadata()) {
            return null;
        }
        // If the consumer directly references the registered service interface, it's clearly used.
        if (!Collections.disjoint(info.discoveryClasses(), refs)) {
            return UsageStatus.USED;
        }
        // Annotation processors are used at compile time without direct bytecode references.
        // They may be declared in any scope listed in ANNOTATION_PROCESSOR_SCOPES
        // (compile, provided, test, test-only, compile-only); runtime and test-runtime are excluded.
        if (ANNOTATION_PROCESSOR_SCOPES.contains(dep.scope)
                && info.discoveryClasses().contains("javax.annotation.processing.Processor")) {
            return UsageStatus.USED;
        }
        // The dep has DI/SPI registration but the consumer doesn't directly reference
        // the wiring interface. DI containers (Sisu/Guice, Maven DI) resolve implementations
        // by scanning the index at runtime — there is no bytecode reference in the consumer.
        // We cannot determine from bytecode alone whether the dep is actually needed.
        if (info.impliesUndetermined()) {
            return UsageStatus.UNDETERMINED;
        }
        // The dep has ServiceLoader registration metadata (META-INF/services/<X>) but the consumer
        // does not reference the service interface <X> in bytecode. This covers runtime-only
        // bindings such as SLF4J backends (e.g. log4j-slf4j2-impl registers
        // META-INF/services/org.slf4j.spi.SLF4JServiceProvider): the SLF4J framework itself
        // loads the provider at runtime via ServiceLoader — the consuming module has no direct
        // import. Bytecode analysis cannot determine whether such a dep is actually needed, so
        // UNDETERMINED is the correct status rather than UNUSED.
        return UsageStatus.UNDETERMINED;
    }

    /**
     * Result of a single JAR scan for runtime-discovery metadata.
     *
     * @param discoveryClasses
     *            class names used as service/DI wiring keys; populated from matched convention entry names and (for
     *            content-parsing conventions) file content
     * @param impliesUndetermined
     *            {@code true} if at least one matched convention declares {@link DiscoveryConvention#impliesUndetermined()};
     *            means the dep cannot be classified {@code UNUSED} even without a direct consumer bytecode reference
     */
    record DiscoveryInfo(Set<String> discoveryClasses, boolean impliesUndetermined) {
        boolean hasAnyDiscoveryMetadata() {
            return !discoveryClasses.isEmpty() || impliesUndetermined;
        }
    }

    /**
     * Returns {@code true} if the JAR contains any runtime-discovery registration that would
     * cause it to be classified {@link UsageStatus#UNDETERMINED} rather than {@link UsageStatus#UNUSED}.
     * <p>
     * This covers all conventions whose {@link DiscoveryConvention#impliesUndetermined()} flag
     * returns {@code true}: Maven DI, Sisu, Camel, Spring Boot, Quarkus, and GraalVM native-image
     * metadata. A JAR matching any of these conventions registers runtime-wired components whose
     * usage cannot be verified through bytecode analysis alone.
     * </p>
     */
    @SuppressWarnings("java:S5042") // JARs are from Maven's local repository, already verified
    static boolean hasImpliesUndeterminedRegistration(File jarFile) {
        return scanDiscoveryMetadata(jarFile).impliesUndetermined();
    }

    /**
     * Scan a JAR for all runtime-discovery metadata in a single pass, delegating to the ordered
     * list of {@link DiscoveryConvention} strategies.
     *
     * <p>For each JAR entry the list is walked in order; the first convention that returns a
     * non-empty key set from {@link DiscoveryConvention#matchEntry} wins and its {@link
     * DiscoveryConvention#impliesUndetermined()} flag is ORed into the aggregate result. If the
     * winning convention also declares {@link DiscoveryConvention#needsContent}, the entry body is
     * read and the additional keys from {@link DiscoveryConvention#matchContent} are merged in.</p>
     */
    @SuppressWarnings("java:S5042") // JARs are from Maven's local repository, already verified
    private static DiscoveryInfo scanDiscoveryMetadata(File jarFile) {
        Set<String> classes = new HashSet<>();
        boolean impliesUndetermined = false;
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                for (DiscoveryConvention conv : CONVENTIONS) {
                    Set<String> keys = conv.matchEntry(name);
                    if (!keys.isEmpty()) {
                        classes.addAll(keys);
                        if (conv.impliesUndetermined()) {
                            impliesUndetermined = true;
                        }
                        if (conv.needsContent(name)) {
                            classes.addAll(conv.matchContent(jar, entry));
                        }
                        break; // first match wins
                    }
                }
            }
        } catch (IOException ignored) {
            // skip unreadable JARs
        }
        return new DiscoveryInfo(Set.copyOf(classes), impliesUndetermined);
    }

    private boolean matchesExtraUsedClasses(String ga, Set<String> depClasses) {
        if (depClasses == null) {
            return false;
        }
        List<String> expectedClasses = extraUsedClasses.get(ga);
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
     * Returns {@code true} if {@code ga}'s JAR is non-null and contains at least one class with
     * a {@code public static final} field backed by a compile-time constant ({@code ConstantValue}
     * attribute).  Results are memoized per GA to avoid re-scanning the same JAR multiple times
     * when a dependency appears in both the declared and transitive lists.
     */
    private boolean hasInlineableConstants(String ga, Map<String, File> gaToJar) {
        return inlineableConstantsCache.computeIfAbsent(ga, k -> {
            File jarFile = gaToJar.get(k);
            return jarFile != null && ClassFileScanner.hasInlineableConstants(jarFile);
        });
    }

    /**
     * Check whether a dependency's GA coordinates match any pattern in the given set.
     * Supports exact matches ({@code groupId:artifactId}) and wildcard matches ({@code groupId:*}).
     * For dependencies with a classifier ({@code groupId:artifactId:classifier}), the pattern
     * is also matched against the base {@code groupId:artifactId}.
     */
    public static boolean matchesArtifactPattern(String ga, Set<String> patterns) {
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
     * <p>
     * Covers all conventions registered in {@link #CONVENTIONS}:
     * </p>
     * <ul>
     * <li><b>ServiceLoader</b>: {@code META-INF/services/<interface>}</li>
     * <li><b>Sisu/JSR-330</b>: {@code META-INF/sisu/<annotation>}</li>
     * <li><b>Maven DI</b>: {@code META-INF/maven/<annotation>} (flat file, not POM metadata)</li>
     * <li><b>Apache Camel</b>: {@code META-INF/services/org/apache/camel/…} and {@code META-INF/camel/…}</li>
     * <li><b>Spring Boot</b>: {@code META-INF/spring.components}, {@code META-INF/spring.factories} (interface keys
     * parsed from content), {@code META-INF/spring/…AutoConfiguration.imports}</li>
     * <li><b>Quarkus</b>: {@code META-INF/quarkus-extension.properties}</li>
     * <li><b>GraalVM Native Image</b>: {@code META-INF/native-image/…/reflect-config.json},
     * {@code jni-config.json}, {@code resource-config.json}, {@code proxy-config.json},
     * {@code serialization-config.json}, {@code reachability-metadata.json}</li>
     * </ul>
     */
    public static Set<String> getRuntimeDiscoveryClasses(File jarFile) {
        return scanDiscoveryMetadata(jarFile).discoveryClasses();
    }

    public static final class Builder {
        private Set<String> runtimeArtifacts = Set.of();
        private Set<String> annotationOnlyArtifacts = Set.of();
        private Map<String, List<String>> extraUsedClasses = Map.of();

        private Builder() {}

        /**
         * Artifacts that are needed only at runtime and never referenced in bytecode (JDBC drivers, SLF4J backends, XML
         * parser implementations, JVM agents). Patterns: {@code groupId:artifactId} or {@code groupId:*}.
         */
        public Builder runtimeArtifacts(Set<String> patterns) {
            this.runtimeArtifacts = patterns;
            return this;
        }

        /**
         * Artifacts that provide only source- or class-retention annotations which are erased during compilation
         * (Lombok, SpotBugs annotations, ErrorProne). Patterns: {@code groupId:artifactId} or {@code groupId:*}.
         */
        public Builder annotationOnlyArtifacts(Set<String> patterns) {
            this.annotationOnlyArtifacts = patterns;
            return this;
        }

        /**
         * Classes known to be used but invisible to bytecode analysis — loaded via reflection,
         * referenced from native-image metadata, or otherwise accessed without a direct import.
         * Mapped by providing artifact; keys must be exact {@code groupId:artifactId} coordinates
         * (wildcard patterns like {@code groupId:*} are not supported). During classification the
         * artifact's class index is checked to verify the class is actually present, so the
         * allowlist stays valid even if a class moves.
         */
        public Builder extraUsedClasses(Map<String, List<String>> classes) {
            this.extraUsedClasses = classes;
            return this;
        }

        public DependencyUsageAnalyzer build() {
            return new DependencyUsageAnalyzer(this);
        }
    }
}
