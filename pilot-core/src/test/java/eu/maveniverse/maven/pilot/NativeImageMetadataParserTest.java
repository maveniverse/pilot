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
import static org.assertj.core.api.Assertions.assertThatCode;

import jakarta.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeImageMetadataParserTest {

    // --- reflect-config.json ---

    @Test
    void parseReflectConfig_extractsClassNames() {
        var array = Json.createArrayBuilder()
                .add(Json.createObjectBuilder()
                        .add("name", "com.example.MyClass")
                        .add("allDeclaredConstructors", true))
                .add(Json.createObjectBuilder()
                        .add("name", "com.example.AnotherClass")
                        .add("allPublicMethods", true))
                .build();

        Set<String> result = new HashSet<>();
        NativeImageMetadataParser.parseReflectConfig(array, result);

        assertThat(result).containsExactlyInAnyOrder("com.example.MyClass", "com.example.AnotherClass");
    }

    @Test
    void parseReflectConfig_skipsEntriesWithoutName() {
        var array = Json.createArrayBuilder()
                .add(Json.createObjectBuilder().add("allPublicMethods", true)) // no "name"
                .add(Json.createObjectBuilder().add("name", "com.example.Valid"))
                .build();

        Set<String> result = new HashSet<>();
        NativeImageMetadataParser.parseReflectConfig(array, result);

        assertThat(result).containsExactly("com.example.Valid");
    }

    @Test
    void parseReflectConfig_skipsEmptyName() {
        var array = Json.createArrayBuilder()
                .add(Json.createObjectBuilder().add("name", ""))
                .add(Json.createObjectBuilder().add("name", "com.example.Valid"))
                .build();

        Set<String> result = new HashSet<>();
        NativeImageMetadataParser.parseReflectConfig(array, result);

        assertThat(result).containsExactly("com.example.Valid");
    }

    // --- resource-config.json ---

    @Test
    void parseResourceConfig_extractsBundleClassNames() {
        var obj = Json.createObjectBuilder()
                .add(
                        "bundles",
                        Json.createArrayBuilder()
                                .add(Json.createObjectBuilder().add("name", "com.example.i18n.Messages"))
                                .add(Json.createObjectBuilder().add("name", "com.example.i18n.Errors")))
                .add(
                        "resources",
                        Json.createObjectBuilder()
                                .add(
                                        "includes",
                                        Json.createArrayBuilder()
                                                .add(Json.createObjectBuilder()
                                                        .add("pattern", "\\Qconfig.properties\\E"))))
                .build();

        Set<String> result = new HashSet<>();
        NativeImageMetadataParser.parseResourceConfig(obj, result);

        // Only bundle names are class names; resource patterns are ignored
        assertThat(result).containsExactlyInAnyOrder("com.example.i18n.Messages", "com.example.i18n.Errors");
    }

    @Test
    void parseResourceConfig_noBundlesKey_returnsEmpty() {
        var obj = Json.createObjectBuilder()
                .add("resources", Json.createObjectBuilder())
                .build();

        Set<String> result = new HashSet<>();
        NativeImageMetadataParser.parseResourceConfig(obj, result);

        assertThat(result).isEmpty();
    }

    // --- proxy-config.json ---

    @Test
    void parseProxyConfig_extractsInterfaceNames() {
        var array = Json.createArrayBuilder()
                .add(Json.createObjectBuilder()
                        .add(
                                "interfaces",
                                Json.createArrayBuilder()
                                        .add("com.example.MyInterface")
                                        .add("java.io.Serializable")))
                .add(Json.createObjectBuilder()
                        .add("interfaces", Json.createArrayBuilder().add("com.example.OtherInterface")))
                .build();

        Set<String> result = new HashSet<>();
        NativeImageMetadataParser.parseProxyConfig(array, result);

        assertThat(result)
                .containsExactlyInAnyOrder(
                        "com.example.MyInterface", "java.io.Serializable", "com.example.OtherInterface");
    }

    @Test
    void parseProxyConfig_entryWithoutInterfaces_isSkipped() {
        var array = Json.createArrayBuilder()
                .add(Json.createObjectBuilder().add("someOtherKey", "value")) // no "interfaces"
                .add(Json.createObjectBuilder()
                        .add("interfaces", Json.createArrayBuilder().add("com.example.Valid")))
                .build();

        Set<String> result = new HashSet<>();
        NativeImageMetadataParser.parseProxyConfig(array, result);

        assertThat(result).containsExactly("com.example.Valid");
    }

    // --- serialization-config.json (modern object format) ---

    @Test
    void parseSerializationConfig_objectFormat_extractsTypeNames() {
        var root = Json.createObjectBuilder()
                .add(
                        "types",
                        Json.createArrayBuilder()
                                .add(Json.createObjectBuilder().add("name", "com.example.SerializableClass"))
                                .add(Json.createObjectBuilder().add("name", "com.example.AnotherSerializable")))
                .build();

        Set<String> result = new HashSet<>();
        NativeImageMetadataParser.parseSerializationConfig(root, result);

        assertThat(result)
                .containsExactlyInAnyOrder("com.example.SerializableClass", "com.example.AnotherSerializable");
    }

    @Test
    void parseSerializationConfig_objectFormat_lambdaCapturingTypes() {
        var root = Json.createObjectBuilder()
                .add("types", Json.createArrayBuilder())
                .add(
                        "lambdaCapturingTypes",
                        Json.createArrayBuilder()
                                .add(Json.createObjectBuilder().add("name", "com.example.LambdaClass")))
                .build();

        Set<String> result = new HashSet<>();
        NativeImageMetadataParser.parseSerializationConfig(root, result);

        assertThat(result).containsExactly("com.example.LambdaClass");
    }

    @Test
    void parseSerializationConfig_arrayFormat_extractsNames() {
        // Legacy: flat array of {"name":"<fqcn>"} objects
        var root = Json.createArrayBuilder()
                .add(Json.createObjectBuilder().add("name", "com.example.LegacyClass"))
                .add(Json.createObjectBuilder().add("name", "com.example.AnotherLegacy"))
                .build();

        Set<String> result = new HashSet<>();
        NativeImageMetadataParser.parseSerializationConfig(root, result);

        assertThat(result).containsExactlyInAnyOrder("com.example.LegacyClass", "com.example.AnotherLegacy");
    }

    // --- jni-config.json (same format as reflect-config.json) ---

    @Test
    void parseJniConfig_viaExtractClassNames(@TempDir Path tmp) throws Exception {
        Path nativeDir = tmp.resolve("META-INF/native-image");
        Files.createDirectories(nativeDir);
        Files.writeString(
                nativeDir.resolve("jni-config.json"),
                "[{\"name\": \"com.example.JniAccessedClass\", \"allDeclaredMethods\": true}]");

        Set<String> result = NativeImageMetadataParser.scanDirectory(tmp);
        assertThat(result).containsExactly("com.example.JniAccessedClass");
    }

    // --- reachability-metadata.json bundles[] ---

    @Test
    void parseReachabilityMetadata_bundlesSection() {
        var obj = Json.createObjectBuilder()
                .add(
                        "bundles",
                        Json.createArrayBuilder()
                                .add(Json.createObjectBuilder()
                                        .add("name", "com.example.i18n.Messages")
                                        .add(
                                                "locales",
                                                Json.createArrayBuilder()
                                                        .add("en")
                                                        .add("fr"))))
                .build();

        Set<String> result = new HashSet<>();
        NativeImageMetadataParser.parseReachabilityMetadata(obj, result);

        assertThat(result).containsExactly("com.example.i18n.Messages");
    }

    @Test
    void parseReachabilityMetadata_resourcesBundleEntries() {
        // GraalVM v1.2+: bundles are in resources[] with a "bundle" key
        var obj = Json.createObjectBuilder()
                .add(
                        "resources",
                        Json.createArrayBuilder()
                                .add(Json.createObjectBuilder().add("glob", "META-INF/**"))
                                .add(Json.createObjectBuilder().add("bundle", "com.example.i18n.Messages"))
                                .add(Json.createObjectBuilder().add("bundle", "com.example.i18n.Errors")))
                .build();

        Set<String> result = new HashSet<>();
        NativeImageMetadataParser.parseReachabilityMetadata(obj, result);

        assertThat(result).containsExactlyInAnyOrder("com.example.i18n.Messages", "com.example.i18n.Errors");
    }

    @Test
    void parseReachabilityMetadata_allSections() {
        var obj = Json.createObjectBuilder()
                .add(
                        "reflection",
                        Json.createArrayBuilder()
                                .add(Json.createObjectBuilder()
                                        .add("type", Json.createObjectBuilder().add("name", "com.example.Reflected"))))
                .add(
                        "jni",
                        Json.createArrayBuilder()
                                .add(Json.createObjectBuilder()
                                        .add("type", Json.createObjectBuilder().add("name", "com.example.JniClass"))))
                .add(
                        "bundles",
                        Json.createArrayBuilder().add(Json.createObjectBuilder().add("name", "com.example.Bundle")))
                // resources contains glob patterns — must be ignored; bundle entries are extracted
                .add(
                        "resources",
                        Json.createArrayBuilder().add(Json.createObjectBuilder().add("glob", "META-INF/**")))
                .build();

        Set<String> result = new HashSet<>();
        NativeImageMetadataParser.parseReachabilityMetadata(obj, result);

        assertThat(result)
                .containsExactlyInAnyOrder("com.example.Reflected", "com.example.JniClass", "com.example.Bundle");
    }

    // --- reachability-metadata.json (GraalVM 23+ unified format) ---

    @Test
    void parseReachabilityMetadata_stringTypeFormat() {
        // GraalVM v1.2 schema: "type" is a plain string
        var obj = Json.createObjectBuilder()
                .add(
                        "reflection",
                        Json.createArrayBuilder()
                                .add(Json.createObjectBuilder()
                                        .add("type", "com.example.ReflectedClass")
                                        .add("allDeclaredMethods", true))
                                .add(Json.createObjectBuilder()
                                        .add("type", "com.example.AnotherReflected")
                                        .add("serializable", true)))
                // resources contains glob patterns and bundle entries
                .add(
                        "resources",
                        Json.createArrayBuilder()
                                .add(Json.createObjectBuilder().add("glob", "META-INF/services/*"))
                                .add(Json.createObjectBuilder().add("bundle", "com.example.Messages")))
                .build();

        Set<String> result = new HashSet<>();
        NativeImageMetadataParser.parseReachabilityMetadata(obj, result);

        assertThat(result)
                .containsExactlyInAnyOrder(
                        "com.example.ReflectedClass", "com.example.AnotherReflected", "com.example.Messages");
    }

    @Test
    void parseReachabilityMetadata_proxyTypeFormat() {
        // Proxy entries: "type": {"proxy": ["iface1", "iface2"]}
        var obj = Json.createObjectBuilder()
                .add(
                        "reflection",
                        Json.createArrayBuilder()
                                .add(Json.createObjectBuilder()
                                        .add(
                                                "type",
                                                Json.createObjectBuilder()
                                                        .add(
                                                                "proxy",
                                                                Json.createArrayBuilder()
                                                                        .add("com.example.Iface1")
                                                                        .add("java.io.Serializable")))
                                        .add("serializable", true)))
                .build();

        Set<String> result = new HashSet<>();
        NativeImageMetadataParser.parseReachabilityMetadata(obj, result);

        assertThat(result).containsExactlyInAnyOrder("com.example.Iface1", "java.io.Serializable");
    }

    @Test
    void parseReachabilityMetadata_extractsReflectionAndSerializationTypes() {
        // Older schema variant: "type": {"name": "..."} — still supported
        var obj = Json.createObjectBuilder()
                .add(
                        "reflection",
                        Json.createArrayBuilder()
                                .add(Json.createObjectBuilder()
                                        .add(
                                                "type",
                                                Json.createObjectBuilder().add("name", "com.example.ReflectedClass")))
                                .add(Json.createObjectBuilder()
                                        .add(
                                                "type",
                                                Json.createObjectBuilder()
                                                        .add("name", "com.example.AnotherReflected"))))
                // resources contain glob patterns, not class names — must be ignored
                .add(
                        "resources",
                        Json.createArrayBuilder().add(Json.createObjectBuilder().add("glob", "META-INF/services/*")))
                .build();

        Set<String> result = new HashSet<>();
        NativeImageMetadataParser.parseReachabilityMetadata(obj, result);

        assertThat(result).containsExactlyInAnyOrder("com.example.ReflectedClass", "com.example.AnotherReflected");
    }

    @Test
    void parseReachabilityMetadata_jniSection() {
        var obj = Json.createObjectBuilder()
                .add(
                        "jni",
                        Json.createArrayBuilder()
                                .add(Json.createObjectBuilder()
                                        .add("type", Json.createObjectBuilder().add("name", "com.example.JniClass"))))
                .build();

        Set<String> result = new HashSet<>();
        NativeImageMetadataParser.parseReachabilityMetadata(obj, result);

        assertThat(result).containsExactly("com.example.JniClass");
    }

    // --- scanDirectory ---

    @Test
    void scanDirectory_emptyWhenNoMetaInfNativeImage(@TempDir Path tmp) throws Exception {
        assertThat(NativeImageMetadataParser.scanDirectory(tmp)).isEmpty();
    }

    @Test
    void scanDirectory_parsesAllSupportedFileTypes(@TempDir Path tmp) throws Exception {
        Path nativeDir = tmp.resolve("META-INF/native-image/com.example/mylib");
        Files.createDirectories(nativeDir);

        Files.writeString(nativeDir.resolve("reflect-config.json"), "[{\"name\": \"com.example.Reflected\"}]");
        Files.writeString(
                nativeDir.resolve("resource-config.json"), "{\"bundles\": [{\"name\": \"com.example.Bundle\"}]}");
        Files.writeString(nativeDir.resolve("proxy-config.json"), "[{\"interfaces\": [\"com.example.Proxied\"]}]");
        Files.writeString(
                nativeDir.resolve("serialization-config.json"),
                "{\"types\": [{\"name\": \"com.example.Serialized\"}]}");

        Set<String> result = NativeImageMetadataParser.scanDirectory(tmp);

        assertThat(result)
                .containsExactlyInAnyOrder(
                        "com.example.Reflected", "com.example.Bundle", "com.example.Proxied", "com.example.Serialized");
    }

    @Test
    void scanDirectory_recursivelyScansSubdirectories(@TempDir Path tmp) throws Exception {
        // GraalVM recommends: META-INF/native-image/<groupId>/<artifactId>/
        Path libDir = tmp.resolve("META-INF/native-image/com.example/lib");
        Path otherLibDir = tmp.resolve("META-INF/native-image/org.other/other-lib");
        Files.createDirectories(libDir);
        Files.createDirectories(otherLibDir);

        Files.writeString(libDir.resolve("reflect-config.json"), "[{\"name\": \"com.example.ClassA\"}]");
        Files.writeString(otherLibDir.resolve("reflect-config.json"), "[{\"name\": \"org.other.ClassB\"}]");

        Set<String> result = NativeImageMetadataParser.scanDirectory(tmp);

        assertThat(result).containsExactlyInAnyOrder("com.example.ClassA", "org.other.ClassB");
    }

    @Test
    void scanDirectory_silentlySkipsMalformedJson(@TempDir Path tmp) throws Exception {
        Path nativeDir = tmp.resolve("META-INF/native-image");
        Files.createDirectories(nativeDir);
        Files.writeString(nativeDir.resolve("reflect-config.json"), "THIS IS NOT JSON");
        Files.writeString(nativeDir.resolve("proxy-config.json"), "[{\"interfaces\": [\"com.example.Valid\"]}]");

        // Must not throw — malformed file is silently skipped
        assertThatCode(() -> NativeImageMetadataParser.scanDirectory(tmp)).doesNotThrowAnyException();
        Set<String> result = NativeImageMetadataParser.scanDirectory(tmp);
        assertThat(result).containsExactly("com.example.Valid");
    }

    @Test
    void scanDirectory_ignoresUnrecognisedFileNames(@TempDir Path tmp) throws Exception {
        Path nativeDir = tmp.resolve("META-INF/native-image");
        Files.createDirectories(nativeDir);
        // Not a supported file name — should be silently ignored
        Files.writeString(nativeDir.resolve("custom-hints.json"), "[{\"name\": \"com.example.Ignored\"}]");
        Files.writeString(nativeDir.resolve("reflect-config.json"), "[{\"name\": \"com.example.Valid\"}]");

        Set<String> result = NativeImageMetadataParser.scanDirectory(tmp);

        assertThat(result).containsExactly("com.example.Valid");
    }

    @Test
    void scanDirectory_reachabilityMetadataJson(@TempDir Path tmp) throws Exception {
        Path nativeDir = tmp.resolve("META-INF/native-image");
        Files.createDirectories(nativeDir);
        Files.writeString(nativeDir.resolve("reachability-metadata.json"), """
                {
                  "reflection": [
                    {"type": "com.example.ReflectedClass", "allDeclaredMethods": true}
                  ],
                  "resources": [
                    {"glob": "META-INF/services/com.example.Service"},
                    {"bundle": "com.example.Messages"}
                  ]
                }
                """);

        Set<String> result = NativeImageMetadataParser.scanDirectory(tmp);

        // resources[].glob is ignored; resources[].bundle and reflection[].type (string) are extracted
        assertThat(result).containsExactlyInAnyOrder("com.example.ReflectedClass", "com.example.Messages");
    }
}
