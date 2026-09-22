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

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Parses GraalVM Native Image reachability metadata JSON files and extracts class names
 * referenced by reflection, resource bundles, JDK proxy interfaces, and serialization.
 *
 * <p>Supported file names (matched by suffix, case-insensitive):
 * <ul>
 *   <li>{@code reflect-config.json} — {@code [{"name":"<fqcn>", ...}]}</li>
 *   <li>{@code resource-config.json} — {@code {"bundles":[{"name":"<fqcn>"}], ...}}</li>
 *   <li>{@code proxy-config.json} — {@code [{"interfaces":["<fqcn>", ...]}]}</li>
 *   <li>{@code serialization-config.json} — {@code {"types":[{"name":"<fqcn>"}], ...}}
 *       or {@code [{"name":"<fqcn>"}]}</li>
 *   <li>{@code reachability-metadata.json} — new unified format (GraalVM v1.2 schema):
 *       {@code {"reflection":[{"type":"<fqcn>", ...}], "resources":[{"bundle":"<fqcn>"},...]}};
 *       older schema variant {@code {"type":{"name":"<fqcn>"}}} and proxy entries
 *       {@code {"type":{"proxy":["<iface>",...]}} are also supported.</li>
 * </ul>
 *
 * <p>Usage: call {@link #scanDirectory(Path)} with the project's {@code outputDirectory}
 * (typically {@code target/classes}). The method returns the union of all class names found
 * in any {@code META-INF/native-image/**&#47;*.json} file under that directory.
 *
 * <p>Malformed or unrecognised JSON files are silently skipped (no class names extracted).
 */
public final class NativeImageMetadataParser {

    private static final String META_INF_NATIVE_IMAGE = "META-INF/native-image";

    private NativeImageMetadataParser() {}

    /**
     * Scans {@code outputDirectory/META-INF/native-image/} recursively and returns the union of
     * all class names found in recognised GraalVM metadata JSON files.
     *
     * @param outputDirectory the compiled output directory (e.g. {@code target/classes})
     * @return set of fully-qualified class names extracted from native-image metadata;
     *         empty if no metadata directory exists or no class names are found
     * @throws IOException if directory traversal fails
     */
    public static Set<String> scanDirectory(Path outputDirectory) throws IOException {
        Path metaInfDir = outputDirectory.resolve(META_INF_NATIVE_IMAGE);
        if (!Files.isDirectory(metaInfDir)) {
            return Set.of();
        }
        Set<String> classNames = new HashSet<>();
        try (Stream<Path> files = Files.walk(metaInfDir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(jsonFile -> {
                        try {
                            extractClassNames(jsonFile, classNames);
                        } catch (IOException e) {
                            // I/O failure reading a metadata file — rethrow so callers can
                            // decide whether to treat missing metadata as fatal or not
                            throw new UncheckedIOException(e);
                        } catch (RuntimeException ignored) {
                            // silently skip malformed / unrecognised files (JSON parse errors,
                            // ClassCastException on unexpected structure, etc.)
                        }
                    });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return Set.copyOf(classNames);
    }

    /**
     * Scans {@code outputDirectory/META-INF/native-image/} for GraalVM reachability metadata JSON
     * files and maps the discovered class names back to their providing {@code groupId:artifactId}
     * using the supplied {@code classIndex}.
     *
     * <p>Class names that cannot be mapped to any known dependency (e.g. classes from the project
     * itself) are silently ignored — they need no dependency entry in the analysis.</p>
     *
     * @param outputDirectory the compiled output directory (e.g. {@code target/classes})
     * @param classIndex      map of fully-qualified class name → {@code groupId:artifactId} for
     *                        all resolved dependency JARs (see
     *                        {@link DependencyUsageAnalyzer#buildClassIndex})
     * @return map of {@code groupId:artifactId} → list of class names referenced in native-image
     *         metadata; empty if no metadata directory exists or no mappable class names are found
     */
    public static Map<String, List<String>> collectNativeImageClasses(
            Path outputDirectory, Map<String, String> classIndex) {
        Set<String> classNames;
        try {
            classNames = scanDirectory(outputDirectory);
        } catch (IOException e) {
            // Non-fatal: treat as no native-image metadata
            return Map.of();
        }
        if (classNames.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> result = new HashMap<>();
        for (String className : classNames) {
            String ga = classIndex.get(className);
            if (ga != null) {
                result.computeIfAbsent(ga, k -> new ArrayList<>()).add(className);
            }
        }
        // Make lists unmodifiable
        result.replaceAll((ga, classes) -> List.copyOf(classes));
        return Map.copyOf(result);
    }

    /**
     * Parses a single JSON file and adds discovered class names to {@code result}.
     * Dispatches to the appropriate parser based on the file name.
     */
    static void extractClassNames(Path jsonFile, Set<String> result) throws IOException {
        String fileName = jsonFile.getFileName().toString().toLowerCase();
        try (InputStream is = Files.newInputStream(jsonFile);
                JsonReader reader = Json.createReader(is)) {
            if (fileName.equals("reflect-config.json") || fileName.equals("jni-config.json")) {
                parseReflectConfig(reader.readArray(), result);
            } else if (fileName.equals("resource-config.json")) {
                parseResourceConfig(reader.readObject(), result);
            } else if (fileName.equals("proxy-config.json")) {
                parseProxyConfig(reader.readArray(), result);
            } else if (fileName.equals("serialization-config.json")) {
                parseSerializationConfig(reader.read(), result);
            } else if (fileName.equals("reachability-metadata.json")) {
                parseReachabilityMetadata(reader.readObject(), result);
            }
            // unknown file names are silently ignored
        }
    }

    /**
     * {@code reflect-config.json}: {@code [{"name":"<fqcn>", ...}, ...]}
     */
    static void parseReflectConfig(JsonArray array, Set<String> result) {
        for (JsonValue entry : array) {
            if (entry.getValueType() == JsonValue.ValueType.OBJECT) {
                String name = entry.asJsonObject().getString("name", null);
                if (name != null && !name.isEmpty()) {
                    result.add(name);
                }
            }
        }
    }

    /**
     * {@code resource-config.json}:
     * <pre>
     * {
     *   "bundles": [{"name": "<fqcn>"}, ...],
     *   "resources": { ... }   // resource patterns — not class names, ignored
     * }
     * </pre>
     * Only {@code bundles[].name} entries are class names (ResourceBundle implementations).
     */
    static void parseResourceConfig(JsonObject obj, Set<String> result) {
        JsonArray bundles = obj.getJsonArray("bundles");
        if (bundles != null) {
            for (JsonValue entry : bundles) {
                if (entry.getValueType() == JsonValue.ValueType.OBJECT) {
                    String name = entry.asJsonObject().getString("name", null);
                    if (name != null && !name.isEmpty()) {
                        result.add(name);
                    }
                }
            }
        }
    }

    /**
     * {@code proxy-config.json}: {@code [{"interfaces":["<fqcn>", ...]}]}
     * <p>All interface names in each entry are class names.
     */
    static void parseProxyConfig(JsonArray array, Set<String> result) {
        for (JsonValue entry : array) {
            if (entry.getValueType() == JsonValue.ValueType.OBJECT) {
                JsonArray interfaces = entry.asJsonObject().getJsonArray("interfaces");
                if (interfaces != null) {
                    for (JsonValue iface : interfaces) {
                        if (iface.getValueType() == JsonValue.ValueType.STRING) {
                            String name = ((JsonString) iface).getString();
                            if (!name.isEmpty()) {
                                result.add(name);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * {@code serialization-config.json}: two possible formats:
     * <ul>
     *   <li>Object: {@code {"types":[{"name":"<fqcn>"}], ...}}</li>
     *   <li>Array (legacy): {@code [{"name":"<fqcn>"}, ...]}</li>
     * </ul>
     */
    static void parseSerializationConfig(JsonValue root, Set<String> result) {
        if (root.getValueType() == JsonValue.ValueType.OBJECT) {
            // Modern format: {"types": [...], "lambdaCapturingTypes": [...]}
            JsonObject obj = root.asJsonObject();
            extractNamesFromArray(obj.getJsonArray("types"), result);
            extractNamesFromArray(obj.getJsonArray("lambdaCapturingTypes"), result);
        } else if (root.getValueType() == JsonValue.ValueType.ARRAY) {
            // Legacy format: flat array of {"name":"<fqcn>"} objects
            extractNamesFromArray(root.asJsonArray(), result);
        }
    }

    /**
     * {@code reachability-metadata.json} (GraalVM 23+ unified format):
     * <pre>
     * {
     *   "reflection": [{"type": "com.example.Foo", ...}, ...],
     *   "jni":        [{"type": "com.example.Bar", ...}, ...],
     *   "resources":  [{"glob": "..."}, {"bundle": "com.example.Messages"}, ...]
     * }
     * </pre>
     * <ul>
     *   <li>{@code reflection[]} and {@code jni[]} entries have a {@code type} field that is a
     *       fully-qualified class name string (GraalVM v1.2 schema) or, in the older schema,
     *       an object with a {@code name} key or a {@code proxy} array of interface names.
     *       Both forms are supported.</li>
     *   <li>{@code resources[]} entries with a {@code bundle} key are ResourceBundle class names.</li>
     *   <li>All other {@code resources[]} entries (e.g. {@code glob}) are not class names — ignored.</li>
     *   <li>A top-level {@code bundles[]} array (older intermediate format) is also supported.</li>
     *   <li>Serialization metadata is in {@code reflection[]} entries with {@code "serializable": true}
     *       — covered by the reflection section above; there is no separate top-level
     *       {@code serialization} key in the v1.2 schema.</li>
     * </ul>
     */
    static void parseReachabilityMetadata(JsonObject obj, Set<String> result) {
        extractTypeNamesFromSection(obj.getJsonArray("reflection"), result);
        extractTypeNamesFromSection(obj.getJsonArray("jni"), result);
        // resources[].bundle — ResourceBundle class names (GraalVM v1.2+)
        JsonArray resources = obj.getJsonArray("resources");
        if (resources != null) {
            for (JsonValue entry : resources) {
                if (entry.getValueType() == JsonValue.ValueType.OBJECT) {
                    String bundle = entry.asJsonObject().getString("bundle", null);
                    if (bundle != null && !bundle.isEmpty()) {
                        result.add(bundle);
                    }
                }
            }
        }
        // bundles[] — top-level bundle array from older intermediate format
        extractNamesFromArray(obj.getJsonArray("bundles"), result);
    }

    // --- helpers ---

    private static void extractNamesFromArray(JsonArray array, Set<String> result) {
        if (array == null) {
            return;
        }
        for (JsonValue entry : array) {
            if (entry.getValueType() == JsonValue.ValueType.OBJECT) {
                String name = entry.asJsonObject().getString("name", null);
                if (name != null && !name.isEmpty()) {
                    result.add(name);
                }
            }
        }
    }

    private static void extractTypeNamesFromSection(JsonArray array, Set<String> result) {
        if (array == null) {
            return;
        }
        for (JsonValue entry : array) {
            if (entry.getValueType() != JsonValue.ValueType.OBJECT) {
                continue;
            }
            JsonValue type = entry.asJsonObject().get("type");
            if (type == null) {
                continue;
            }
            if (type.getValueType() == JsonValue.ValueType.STRING) {
                // GraalVM v1.2 schema: "type": "com.example.Foo"
                String name = ((JsonString) type).getString();
                if (!name.isEmpty()) {
                    result.add(name);
                }
            } else if (type.getValueType() == JsonValue.ValueType.OBJECT) {
                JsonObject typeObj = type.asJsonObject();
                // Older schema: "type": {"name": "com.example.Foo"}
                String name = typeObj.getString("name", null);
                if (name != null && !name.isEmpty()) {
                    result.add(name);
                }
                // Proxy type: "type": {"proxy": ["com.example.Iface1", ...]}
                JsonArray proxy = typeObj.getJsonArray("proxy");
                if (proxy != null) {
                    for (JsonValue ifaceName : proxy) {
                        if (ifaceName.getValueType() == JsonValue.ValueType.STRING) {
                            String iface = ((JsonString) ifaceName).getString();
                            if (!iface.isEmpty()) {
                                result.add(iface);
                            }
                        }
                    }
                }
            }
        }
    }
}
