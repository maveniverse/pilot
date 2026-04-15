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
package eu.maveniverse.maven.pilot.mvn4;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.maven.api.ProtoSession;
import org.apache.maven.api.Session;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.services.ModelBuilder;
import org.apache.maven.api.services.ModelBuilderRequest;
import org.apache.maven.api.services.ModelBuilderResult;
import org.apache.maven.api.services.Sources;
import org.apache.maven.api.spi.PropertyContributor;

/**
 * Loads Maven extensions from {@code .mvn/extensions.xml} and invokes
 * {@link PropertyContributor} implementations to contribute properties
 * that the standalone API does not handle (e.g., Nisse dynamic versions).
 */
class ExtensionLoader {

    /**
     * Loads extensions from the project's {@code .mvn/extensions.xml},
     * resolves their artifacts, discovers {@link PropertyContributor}
     * implementations, and returns contributed properties.
     */
    static Map<String, String> loadExtensionProperties(Session session, Path projectDir) {
        return loadExtensionProperties(session, projectDir, s -> {});
    }

    static Map<String, String> loadExtensionProperties(Session session, Path projectDir, Consumer<String> status) {
        Path extensionsXml = projectDir.resolve(".mvn").resolve("extensions.xml");
        if (!Files.isRegularFile(extensionsXml)) {
            return Map.of();
        }

        try {
            // Parse extensions.xml
            status.accept("Parsing extensions.xml…");
            List<ExtensionCoord> extensions = parseExtensionsXml(extensionsXml);
            if (extensions.isEmpty()) {
                return Map.of();
            }

            // Resolve all extension JARs
            status.accept("Resolving extension JARs (" + extensions.size() + " extensions)…");
            List<Path> jars = resolveExtensionJars(session, extensions);
            if (jars.isEmpty()) {
                return Map.of();
            }

            // Create classloader and discover PropertyContributor implementations
            status.accept("Discovering property contributors…");
            URL[] urls = jars.stream()
                    .map(p -> {
                        try {
                            return p.toUri().toURL();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .toArray(URL[]::new);

            try (URLClassLoader cl = new ChildFirstClassLoader(urls, ExtensionLoader.class.getClassLoader())) {
                List<PropertyContributor> contributors = discoverAndInstantiate(cl);
                if (contributors.isEmpty()) {
                    return Map.of();
                }

                // Build system properties: session props + .mvn/maven.config -D props
                Map<String, String> systemProps = new HashMap<>(session.getSystemProperties());
                systemProps.putAll(parseMavenConfigProperties(projectDir));

                // Build ProtoSession and invoke contributors
                ProtoSession proto = ProtoSession.newBuilder()
                        .withSystemProperties(systemProps)
                        .withUserProperties(session.getUserProperties())
                        .withTopDirectory(projectDir)
                        .withRootDirectory(projectDir)
                        .build();

                Map<String, String> result = new HashMap<>();
                for (PropertyContributor contributor : contributors) {
                    Map<String, String> props = contributor.contribute(proto);
                    if (props != null) {
                        result.putAll(props);
                    }
                }
                return result;
            }
        } catch (Exception e) {
            System.err.println("Warning: failed to load extensions: " + e.getMessage());
            return Map.of();
        }
    }

    record ExtensionCoord(String groupId, String artifactId, String version) {}

    private static List<ExtensionCoord> parseExtensionsXml(Path extensionsXml) throws Exception {
        String content = Files.readString(extensionsXml);
        List<ExtensionCoord> extensions = new ArrayList<>();
        int idx = 0;
        while ((idx = content.indexOf("<extension>", idx)) >= 0) {
            int end = content.indexOf("</extension>", idx);
            if (end < 0) break;
            String block = content.substring(idx, end);
            String groupId = extractXmlValue(block, "groupId");
            String artifactId = extractXmlValue(block, "artifactId");
            String version = extractXmlValue(block, "version");
            if (groupId != null && artifactId != null && version != null) {
                extensions.add(new ExtensionCoord(groupId, artifactId, version));
            }
            idx = end;
        }
        return extensions;
    }

    /**
     * Parses {@code .mvn/maven.config} for {@code -D} property definitions.
     */
    private static Map<String, String> parseMavenConfigProperties(Path projectDir) {
        Path mavenConfig = projectDir.resolve(".mvn").resolve("maven.config");
        if (!Files.isRegularFile(mavenConfig)) {
            return Map.of();
        }
        try {
            Map<String, String> props = new HashMap<>();
            for (String line : Files.readAllLines(mavenConfig)) {
                line = line.trim();
                if (line.startsWith("-D")) {
                    String prop = line.substring(2);
                    int eq = prop.indexOf('=');
                    if (eq > 0) {
                        props.put(prop.substring(0, eq), prop.substring(eq + 1));
                    } else {
                        props.put(prop, "true");
                    }
                }
            }
            return props;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String extractXmlValue(String xml, String tag) {
        int start = xml.indexOf("<" + tag + ">");
        if (start < 0) return null;
        start += tag.length() + 2;
        int end = xml.indexOf("</" + tag + ">", start);
        if (end < 0) return null;
        return xml.substring(start, end).trim();
    }

    private static List<Path> resolveExtensionJars(Session session, List<ExtensionCoord> extensions) {
        Path localRepo = session.getLocalRepository().getPath();
        List<Path> allJars = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        for (ExtensionCoord ext : extensions) {
            collectFromLocalRepo(session, localRepo, ext.groupId(), ext.artifactId(), ext.version(), allJars, visited);
        }
        return allJars;
    }

    /**
     * Recursively collects JARs from the local Maven repository by reading
     * effective POMs via ModelBuilder to resolve dependency management.
     */
    private static void collectFromLocalRepo(
            Session session, Path localRepo, String g, String a, String v, List<Path> jars, Set<String> visited) {
        String key = g + ":" + a + ":" + v;
        if (!visited.add(key)) {
            return;
        }

        Path dir = localRepo.resolve(g.replace('.', '/')).resolve(a).resolve(v);
        Path jar = dir.resolve(a + "-" + v + ".jar");
        if (Files.isRegularFile(jar)) {
            jars.add(jar);
        }

        // Build effective POM to get resolved dependency versions
        Path pom = dir.resolve(a + "-" + v + ".pom");
        if (!Files.isRegularFile(pom)) {
            return;
        }

        try {
            ModelBuilder modelBuilder = session.getService(ModelBuilder.class);
            ModelBuilderResult result = modelBuilder
                    .newSession()
                    .build(ModelBuilderRequest.builder()
                            .session(session)
                            .requestType(ModelBuilderRequest.RequestType.CONSUMER_DEPENDENCY)
                            .source(Sources.buildSource(pom))
                            .build());
            Model model = result.getEffectiveModel();

            if (model.getDependencies() != null) {
                for (org.apache.maven.api.model.Dependency dep : model.getDependencies()) {
                    String scope = dep.getScope();
                    if ("test".equals(scope) || "system".equals(scope)) {
                        continue;
                    }
                    if (dep.getGroupId() != null && dep.getArtifactId() != null && dep.getVersion() != null) {
                        collectFromLocalRepo(
                                session,
                                localRepo,
                                dep.getGroupId(),
                                dep.getArtifactId(),
                                dep.getVersion(),
                                jars,
                                visited);
                    }
                }
            }
        } catch (Exception ignored) {
            // Lenient: dependency POMs may have validation issues we can't control
        }
    }

    // ── javax.inject-aware mini-DI ──────────────────────────────────────

    private static List<PropertyContributor> discoverAndInstantiate(URLClassLoader cl) throws Exception {
        // Discover all named classes from Sisu index files
        List<Class<?>> allClasses = new ArrayList<>();
        Enumeration<URL> resources = cl.getResources("META-INF/sisu/javax.inject.Named");
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()))) {
                reader.lines().filter(l -> !l.startsWith("#") && !l.isBlank()).forEach(className -> {
                    try {
                        allClasses.add(cl.loadClass(className));
                    } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                    }
                });
            }
        }

        if (allClasses.isEmpty()) {
            return List.of();
        }

        // Build a mini-DI context and instantiate PropertyContributor implementations
        MiniDI di = new MiniDI(allClasses);
        List<PropertyContributor> contributors = new ArrayList<>();
        for (Class<?> cls : allClasses) {
            if (PropertyContributor.class.isAssignableFrom(cls)) {
                Object instance = di.getInstance(cls);
                if (instance != null) {
                    contributors.add((PropertyContributor) instance);
                }
            }
        }
        return contributors;
    }

    /**
     * Minimal DI container that understands {@code javax.inject} annotations.
     * Handles constructor injection with {@code List<T>} and {@code Map<String, T>} parameters.
     */
    private static class MiniDI {
        private final List<Class<?>> classes;
        private final Map<Class<?>, Object> instances = new HashMap<>();
        private final Map<String, Object> namedInstances = new LinkedHashMap<>();

        MiniDI(List<Class<?>> classes) {
            this.classes = classes;
        }

        @SuppressWarnings("unchecked")
        <T> T getInstance(Class<T> type) {
            Object cached = instances.get(type);
            if (cached != null) {
                return (T) cached;
            }

            // Find the implementation class
            Class<?> implClass = null;
            for (Class<?> cls : classes) {
                if (type.isAssignableFrom(cls) && !cls.isInterface()) {
                    implClass = cls;
                    break;
                }
            }
            if (implClass == null && !type.isInterface()) {
                implClass = type;
            }
            if (implClass == null) {
                return null;
            }

            try {
                T instance = (T) instantiate(implClass);
                instances.put(type, instance);
                instances.put(implClass, instance);
                return instance;
            } catch (Exception e) {
                return null;
            }
        }

        private Object instantiate(Class<?> cls) throws Exception {
            // Find @Inject constructor or single constructor
            Constructor<?> ctor = findInjectConstructor(cls);
            if (ctor == null) {
                return cls.getDeclaredConstructor().newInstance();
            }

            ctor.setAccessible(true);
            Type[] paramTypes = ctor.getGenericParameterTypes();
            Object[] args = new Object[paramTypes.length];

            for (int i = 0; i < paramTypes.length; i++) {
                args[i] = resolveParam(paramTypes[i]);
            }

            Object instance = ctor.newInstance(args);
            instances.put(cls, instance);

            // Store with @Named qualifier if present
            String name = getNamedValue(cls);
            if (name != null) {
                namedInstances.put(name + ":" + findServiceType(cls).getName(), instance);
            }

            return instance;
        }

        private Object resolveParam(Type type) {
            if (type instanceof ParameterizedType pt) {
                Type rawType = pt.getRawType();
                if (rawType == List.class || rawType == Collection.class) {
                    Type elementType = pt.getActualTypeArguments()[0];
                    if (elementType instanceof Class<?> elemClass) {
                        return collectAll(elemClass);
                    }
                }
                if (rawType == Map.class) {
                    Type[] typeArgs = pt.getActualTypeArguments();
                    if (typeArgs[0] == String.class && typeArgs[1] instanceof Class<?> valueClass) {
                        return collectNamed(valueClass);
                    }
                }
            }
            if (type instanceof Class<?> cls) {
                return getInstance(cls);
            }
            return null;
        }

        private <T> List<T> collectAll(Class<T> type) {
            List<T> result = new ArrayList<>();
            for (Class<?> cls : classes) {
                if (type.isAssignableFrom(cls) && !cls.isInterface()) {
                    T instance = type.cast(getInstance(cls));
                    if (instance != null) {
                        result.add(instance);
                    }
                }
            }
            return result;
        }

        @SuppressWarnings("unchecked")
        private <T> Map<String, T> collectNamed(Class<T> type) {
            Map<String, T> result = new LinkedHashMap<>();
            for (Class<?> cls : classes) {
                if (type.isAssignableFrom(cls) && !cls.isInterface()) {
                    String name = getNamedValue(cls);
                    if (name != null) {
                        T instance = type.cast(getInstance(cls));
                        if (instance != null) {
                            result.put(name, instance);
                        }
                    }
                }
            }
            return result;
        }

        private static Constructor<?> findInjectConstructor(Class<?> cls) {
            for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
                for (Annotation ann : ctor.getAnnotations()) {
                    if (ann.annotationType().getSimpleName().equals("Inject")) {
                        return ctor;
                    }
                }
            }
            // Fall back to single public constructor with parameters
            Constructor<?>[] ctors = cls.getDeclaredConstructors();
            if (ctors.length == 1 && ctors[0].getParameterCount() > 0) {
                return ctors[0];
            }
            return null;
        }

        private static String getNamedValue(Class<?> cls) {
            for (Annotation ann : cls.getAnnotations()) {
                if (ann.annotationType().getSimpleName().equals("Named")) {
                    try {
                        Object value = ann.annotationType().getMethod("value").invoke(ann);
                        String s = value.toString();
                        return s.isEmpty() ? cls.getSimpleName() : s;
                    } catch (Exception e) {
                        return cls.getSimpleName();
                    }
                }
            }
            return null;
        }

        private static Class<?> findServiceType(Class<?> cls) {
            for (Class<?> iface : cls.getInterfaces()) {
                if (!iface.getName().startsWith("java.")) {
                    return iface;
                }
            }
            return cls;
        }
    }

    /**
     * Child-first classloader that checks its own URLs before delegating to the parent.
     * This ensures extension dependencies (e.g., resolver 1.9.x) take precedence
     * over the fat JAR's versions (resolver 2.x).
     */
    private static class ChildFirstClassLoader extends URLClassLoader {
        ChildFirstClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    // For Maven API SPI types, delegate to parent (they must match)
                    if (name.startsWith("org.apache.maven.api.")) {
                        c = getParent().loadClass(name);
                    } else {
                        try {
                            c = findClass(name);
                        } catch (ClassNotFoundException e) {
                            c = getParent().loadClass(name);
                        }
                    }
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }

        @Override
        public URL getResource(String name) {
            URL url = findResource(name);
            if (url == null) {
                url = getParent().getResource(name);
            }
            return url;
        }
    }
}
