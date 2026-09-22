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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Strategy for recognising one runtime-discovery convention inside a JAR.
 *
 * <p>Each implementation knows how to detect entries that belong to a particular framework's
 * registration mechanism (ServiceLoader, Sisu, Maven DI, Apache Camel, Spring Boot, Quarkus …)
 * and, when necessary, how to parse their content to extract relevant class names.</p>
 *
 * <p>Implementations are package-private; the ordered list {@link DependencyUsageAnalyzer#CONVENTIONS}
 * is the single place where new conventions are registered. Adding support for a new framework means
 * adding a new {@code permits} clause and a corresponding entry in that list.</p>
 */
sealed interface DiscoveryConvention
        permits DiscoveryConvention.SisuConvention,
                DiscoveryConvention.MavenDiConvention,
                DiscoveryConvention.CamelConvention,
                DiscoveryConvention.SpringBootConvention,
                DiscoveryConvention.QuarkusConvention,
                DiscoveryConvention.GraalVmConvention,
                DiscoveryConvention.ServiceLoaderConvention {

    /**
     * Returns {@code true} when a JAR matching this convention should yield {@link
     * DependencyUsageAnalyzer.UsageStatus#UNDETERMINED} even if the consumer has no direct bytecode
     * reference to the extracted keys.
     *
     * <p>Set this to {@code true} for DI-style registries (Sisu, Maven DI, Camel, Spring Boot,
     * Quarkus) where the framework resolves implementations at runtime without any import in the
     * consumer. Set it to {@code false} for plain ServiceLoader, where a missing interface import
     * genuinely means the service is not consumed.</p>
     */
    boolean impliesUndetermined();

    /**
     * Inspect a single JAR entry by name only (fast path, no I/O).
     *
     * @param name the JAR entry name (e.g. {@code META-INF/services/com.example.Svc})
     * @return the class-name keys extracted from this entry's name, or an empty set if this entry
     *         does not belong to the convention
     */
    Set<String> matchEntry(String name);

    /**
     * Called for entries where {@link #needsContent(String)} returns {@code true}; reads the entry
     * body and returns additional class-name keys.
     *
     * <p>The default implementation returns an empty set (no content parsing needed).</p>
     *
     * @param jar   the open JAR being scanned
     * @param entry the JAR entry whose content should be parsed
     * @return additional class-name keys extracted from the entry content
     * @throws IOException if reading the entry fails
     */
    default Set<String> matchContent(JarFile jar, JarEntry entry) throws IOException {
        return Set.of();
    }

    /**
     * Returns {@code true} if this convention needs to read the entry body for the given entry name.
     *
     * <p>The default implementation returns {@code false}; override in conventions that do content
     * parsing (e.g. {@link SpringBootConvention}).</p>
     */
    default boolean needsContent(String name) {
        return false;
    }

    // -------------------------------------------------------------------------
    // Built-in conventions (package-private, ordered in CONVENTIONS list)
    // -------------------------------------------------------------------------

    /**
     * Eclipse Sisu / JSR-330: {@code META-INF/sisu/<annotation-fqn>} (flat file, no sub-path).
     *
     * <p>Example: {@code META-INF/sisu/javax.inject.Named}.</p>
     *
     * <p>The DI container reads these index files at runtime to discover injectable components;
     * there is no direct bytecode reference in the consuming module. Classification must be
     * {@code UNDETERMINED} when the consumer does not import the annotation directly.</p>
     */
    final class SisuConvention implements DiscoveryConvention {
        private static final String PREFIX = "META-INF/sisu/";

        @Override
        public boolean impliesUndetermined() {
            return true;
        }

        @Override
        public Set<String> matchEntry(String name) {
            if (!name.startsWith(PREFIX) || name.equals(PREFIX)) {
                return Set.of();
            }
            String remainder = name.substring(PREFIX.length());
            // Flat file only — no sub-path (e.g. "javax.inject.Named", not "foo/bar")
            if (remainder.contains("/") || remainder.isEmpty()) {
                return Set.of();
            }
            return Set.of(remainder);
        }
    }

    /**
     * Maven DI annotation index: {@code META-INF/maven/<annotation-fqn>} (flat file, not POM
     * metadata).
     *
     * <p>Distinguishes DI index files (e.g. {@code META-INF/maven/org.apache.maven.api.di.Inject})
     * from standard POM metadata ({@code META-INF/maven/<groupId>/<artifactId>/…}) by requiring
     * the remainder to contain no {@code /}.</p>
     */
    final class MavenDiConvention implements DiscoveryConvention {
        private static final String PREFIX = "META-INF/maven/";

        @Override
        public boolean impliesUndetermined() {
            return true;
        }

        @Override
        public Set<String> matchEntry(String name) {
            if (!name.startsWith(PREFIX) || name.equals(PREFIX)) {
                return Set.of();
            }
            String remainder = name.substring(PREFIX.length());
            // DI index: flat file, e.g. "org.apache.maven.api.di.Inject"
            // POM metadata: "org.apache.maven/maven-jline/pom.xml" (contains '/')
            if (remainder.contains("/") || remainder.isEmpty()) {
                return Set.of();
            }
            return Set.of(remainder);
        }
    }

    /**
     * Apache Camel SPI registry: entries under {@code META-INF/services/org/apache/camel/} or
     * {@code META-INF/camel/}.
     *
     * <p>Camel's {@code FactoryFinder} / {@code PluginHelper} resolves components, languages, and
     * data formats at runtime using these path-based registrations — no direct class reference
     * appears in the consuming module's bytecode.</p>
     *
     * <p>A synthetic sentinel key is added so that {@link DependencyUsageAnalyzer#classifyByRuntimeDiscovery}
     * can fall through to {@code UNDETERMINED} rather than {@code USED}. The sentinel is never
     * present in consumer bytecode.</p>
     *
     * <p><b>Must be registered before {@link ServiceLoaderConvention}</b> in the conventions list,
     * because Camel paths are a sub-path of {@code META-INF/services/}.</p>
     */
    final class CamelConvention implements DiscoveryConvention {
        private static final String CAMEL_SERVICES_PREFIX = "META-INF/services/org/apache/camel/";
        private static final String CAMEL_META_PREFIX = "META-INF/camel/";
        /**
         * Synthetic sentinel key — never present in consumer bytecode, so the consumer-reference
         * check in {@link DependencyUsageAnalyzer#classifyByRuntimeDiscovery} will not fire and the
         * result falls through to {@code UNDETERMINED}.
         */
        static final String SENTINEL = "org.apache.camel.spi.CamelSpiProvider";

        @Override
        public boolean impliesUndetermined() {
            return true;
        }

        @Override
        public Set<String> matchEntry(String name) {
            boolean isCamelServices = name.startsWith(CAMEL_SERVICES_PREFIX)
                    && !name.endsWith("/")
                    && name.length() > CAMEL_SERVICES_PREFIX.length();
            boolean isCamelMeta = name.startsWith(CAMEL_META_PREFIX)
                    && !name.endsWith("/")
                    && name.length() > CAMEL_META_PREFIX.length();
            return (isCamelServices || isCamelMeta) ? Set.of(SENTINEL) : Set.of();
        }
    }

    /**
     * Spring Boot auto-configuration and component index.
     *
     * <p>Three file patterns are covered:</p>
     * <ul>
     * <li>{@code META-INF/spring.factories} — Spring Boot 1.x/2.x auto-configuration registry;
     *     content is a {@code key=value,...} properties file where keys are interface FQCNs and
     *     values are comma-separated implementation FQCNs. The key FQCNs are extracted as class
     *     keys so that a consumer referencing the auto-configuration interface is classified
     *     {@code USED}.</li>
     * <li>{@code META-INF/spring/…AutoConfiguration.imports} — Spring Boot 3.x replacement for
     *     {@code spring.factories}; content is a plain list of implementation class names. The
     *     sentinel {@code EnableAutoConfiguration} is added so that the dep is never falsely
     *     {@code UNUSED}.</li>
     * <li>{@code META-INF/spring.components} — Spring component index (spring-context-indexer);
     *     presence is sufficient to add the {@code @Component} sentinel.</li>
     * </ul>
     *
     * <p>Content parsing is used for {@code spring.factories} (to extract interface keys) and
     * {@code AutoConfiguration.imports} (sentinel only, no content parse needed).</p>
     */
    final class SpringBootConvention implements DiscoveryConvention {
        static final String SPRING_COMPONENTS = "META-INF/spring.components";
        static final String SPRING_FACTORIES = "META-INF/spring.factories";
        static final String AUTO_CONFIG_IMPORTS =
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

        static final String COMPONENT_SENTINEL = "org.springframework.stereotype.Component";
        static final String AUTO_CONFIG_SENTINEL = "org.springframework.boot.autoconfigure.EnableAutoConfiguration";

        @Override
        public boolean impliesUndetermined() {
            return true;
        }

        @Override
        public Set<String> matchEntry(String name) {
            if (SPRING_COMPONENTS.equals(name)) {
                return Set.of(COMPONENT_SENTINEL);
            }
            if (AUTO_CONFIG_IMPORTS.equals(name)) {
                return Set.of(AUTO_CONFIG_SENTINEL);
            }
            // spring.factories needs content parsing for interface keys — return sentinel now;
            // matchContent will add the actual interface FQCNs.
            if (SPRING_FACTORIES.equals(name)) {
                return Set.of(AUTO_CONFIG_SENTINEL);
            }
            return Set.of();
        }

        @Override
        public boolean needsContent(String name) {
            return SPRING_FACTORIES.equals(name);
        }

        /**
         * Parse {@code META-INF/spring.factories} and return all interface (key) FQCNs declared in
         * the file. These are the class names that a consumer may reference directly to trigger
         * auto-configuration loading.
         *
         * <p>Format example:
         * <pre>
         * org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
         *   com.example.FooAutoConfiguration,\
         *   com.example.BarAutoConfiguration
         * org.springframework.context.ApplicationContextInitializer=\
         *   com.example.MyInitializer
         * </pre>
         * Only the keys (left of {@code =}) are extracted; values (implementation class names) are
         * ignored because the consumer references the interface, not the implementation.</p>
         */
        @Override
        public Set<String> matchContent(JarFile jar, JarEntry entry) throws IOException {
            var props = new Properties();
            try (InputStream is = jar.getInputStream(entry);
                    var reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            return Set.copyOf(props.stringPropertyNames());
        }
    }

    /**
     * Quarkus extension registry: presence of {@code META-INF/quarkus-extension.properties}.
     *
     * <p>A Quarkus extension contributes build steps and runtime behaviours that are wired by the
     * Quarkus build system, not by direct bytecode references in the consuming module. Presence of
     * this descriptor is sufficient to classify the dep as {@code UNDETERMINED}.</p>
     */
    final class QuarkusConvention implements DiscoveryConvention {
        static final String QUARKUS_EXTENSION = "META-INF/quarkus-extension.properties";
        static final String SENTINEL = "io.quarkus.runtime.QuarkusExtension";

        @Override
        public boolean impliesUndetermined() {
            return true;
        }

        @Override
        public Set<String> matchEntry(String name) {
            return QUARKUS_EXTENSION.equals(name) ? Set.of(SENTINEL) : Set.of();
        }
    }

    /**
     * GraalVM Native Image bundled reachability metadata: JSON files shipped <em>inside a
     * dependency JAR</em> under {@code META-INF/native-image/<groupId>/<artifactId>/}.
     *
     * <p>Many libraries ship their own native-image metadata so that users can build native
     * binaries without maintaining a separate metadata repository. The file names follow the
     * same convention as the GraalVM reachability metadata repository:</p>
     * <ul>
     *   <li>{@code reflect-config.json} / {@code jni-config.json}</li>
     *   <li>{@code resource-config.json}</li>
     *   <li>{@code proxy-config.json}</li>
     *   <li>{@code serialization-config.json}</li>
     *   <li>{@code reachability-metadata.json}</li>
     * </ul>
     *
     * <p>The class names extracted from these files are passed back as discovery keys so that
     * {@link DependencyUsageAnalyzer#classifyByRuntimeDiscovery} can match them against the
     * consumer's bytecode references. If none match, the dep is {@code UNDETERMINED}: presence
     * of bundled native-image metadata is strong evidence the dep requires native-image wiring,
     * but bytecode analysis cannot confirm the specific classes used.</p>
     *
     * <p>Note: this convention handles metadata <em>bundled inside the dep JAR itself</em>.
     * Metadata generated by the project under {@code target/classes/META-INF/native-image/} is
     * handled separately by {@link NativeImageMetadataParser} and fed through
     * {@link DependencyUsageAnalyzer.Builder#extraUsedClasses}.</p>
     */
    final class GraalVmConvention implements DiscoveryConvention {
        private static final String PREFIX = "META-INF/native-image/";
        private static final Set<String> KNOWN_FILES = Set.of(
                "reflect-config.json",
                "jni-config.json",
                "resource-config.json",
                "proxy-config.json",
                "serialization-config.json",
                "reachability-metadata.json");

        @Override
        public boolean impliesUndetermined() {
            return true;
        }

        @Override
        public Set<String> matchEntry(String name) {
            if (!name.startsWith(PREFIX) || name.endsWith("/")) {
                return Set.of();
            }
            String fileName = fileName(name);
            return KNOWN_FILES.contains(fileName) ? Set.of(fileName) : Set.of();
        }

        @Override
        public boolean needsContent(String name) {
            return !matchEntry(name).isEmpty();
        }

        @Override
        public Set<String> matchContent(JarFile jar, JarEntry entry) throws IOException {
            Set<String> result = new HashSet<>();
            try {
                NativeImageMetadataParser.extractClassNames(entry.getName(), jar.getInputStream(entry), result);
            } catch (RuntimeException ignored) {
                // malformed JSON — skip silently, same as NativeImageMetadataParser.scanDirectory
            }
            return result;
        }

        private static String fileName(String entryName) {
            int slash = entryName.lastIndexOf('/');
            return slash >= 0 ? entryName.substring(slash + 1) : entryName;
        }
    }

    /**
     * Standard Java {@link java.util.ServiceLoader}: {@code META-INF/services/<interface-fqcn>}
     * (flat file, no sub-path).
     *
     * <p>The entry name (after the prefix) is the service interface FQCN. If the consuming module
     * imports that interface, the dep is {@code USED}; if not, it is {@code UNDETERMINED} (the
     * framework may load it at runtime without a direct import).</p>
     *
     * <p><b>Must be registered last</b> in the conventions list so that Camel paths (which are a
     * sub-path of {@code META-INF/services/}) are matched by {@link CamelConvention} first.</p>
     */
    final class ServiceLoaderConvention implements DiscoveryConvention {
        private static final String PREFIX = "META-INF/services/";

        @Override
        public boolean impliesUndetermined() {
            return false;
        }

        @Override
        public Set<String> matchEntry(String name) {
            if (!name.startsWith(PREFIX) || name.equals(PREFIX)) {
                return Set.of();
            }
            String entry = name.substring(PREFIX.length());
            // Flat file only — sub-paths (e.g. "org/apache/camel/…") are handled by CamelConvention
            if (entry.contains("/") || entry.isEmpty()) {
                return Set.of();
            }
            return Set.of(entry);
        }
    }
}
