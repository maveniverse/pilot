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

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lightweight constant pool scanner that extracts referenced class names from {@code .class} files
 * without requiring ASM or any external bytecode library.
 *
 * <p>The scanner reads only the constant pool section of the class file format (JVMS 4.1),
 * collecting all {@code CONSTANT_Class} entries and resolving them to fully-qualified class names.</p>
 */
final class ClassFileScanner {

    private static final int MAGIC = 0xCAFEBABE;

    // Constant pool tags (JVMS 4.4)
    private static final int CONSTANT_UTF_8 = 1;
    private static final int CONSTANT_INTEGER = 3;
    private static final int CONSTANT_FLOAT = 4;
    private static final int CONSTANT_LONG = 5;
    private static final int CONSTANT_DOUBLE = 6;
    private static final int CONSTANT_CLASS = 7;
    private static final int CONSTANT_STRING = 8;
    private static final int CONSTANT_FIELDREF = 9;
    private static final int CONSTANT_METHODREF = 10;
    private static final int CONSTANT_INTERFACE_METHODREF = 11;
    private static final int CONSTANT_NAME_AND_TYPE = 12;
    private static final int CONSTANT_METHOD_HANDLE = 15;
    private static final int CONSTANT_METHOD_TYPE = 16;
    private static final int CONSTANT_DYNAMIC = 17;
    private static final int CONSTANT_INVOKE_DYNAMIC = 18;
    private static final int CONSTANT_MODULE = 19;
    private static final int CONSTANT_PACKAGE = 20;

    /** Pattern matching class references in type descriptors, e.g. {@code Lcom/example/Foo;} */
    private static final Pattern DESCRIPTOR_CLASS_PATTERN = Pattern.compile("L([a-zA-Z][\\w/$]*);");

    private ClassFileScanner() {}

    /**
     * Scan a single {@code .class} file and return all referenced class names as dot-separated
     * fully-qualified names (e.g. {@code com.example.Foo}).
     *
     * <p>Array descriptors and primitive types are filtered out.</p>
     *
     * @param classFile path to the {@code .class} file
     * @return set of referenced class names
     * @throws IOException if the file cannot be read or has an invalid format
     */
    static Set<String> referencedClasses(Path classFile) throws IOException {
        try (InputStream fis = Files.newInputStream(classFile);
                DataInputStream dis = new DataInputStream(fis)) {
            return parseConstantPool(dis);
        }
    }

    /**
     * Scan all {@code .class} files under a directory tree and return the union of all
     * referenced class names.
     *
     * <p>Individual file scan errors are silently skipped to avoid failing the entire scan
     * due to a single corrupt class file.</p>
     *
     * @param classesDir root directory to scan (e.g. {@code target/classes})
     * @return aggregated set of referenced class names
     * @throws IOException if the directory cannot be walked
     */
    static Set<String> scanDirectory(Path classesDir) throws IOException {
        Set<String> result = new HashSet<>();
        try (Stream<Path> walk = Files.walk(classesDir)) {
            walk.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                try {
                    result.addAll(referencedClasses(p));
                } catch (IOException ignored) {
                    // skip corrupt or unreadable class files
                }
            });
        }
        return result;
    }

    private static Set<String> parseConstantPool(DataInputStream dis) throws IOException {
        int magic = dis.readInt();
        if (magic != MAGIC) {
            throw new IOException("Not a valid class file (bad magic number)");
        }
        dis.readUnsignedShort(); // minor_version
        dis.readUnsignedShort(); // major_version

        int poolCount = dis.readUnsignedShort();

        // Storage for constant pool entries we care about
        String[] utf8Entries = new String[poolCount];
        Set<Integer> classNameIndices = new HashSet<>();

        // Walk the constant pool (indices 1..poolCount-1); use while to allow
        // incrementing the index for Long/Double entries which occupy two slots.
        int i = 1;
        while (i < poolCount) {
            int tag = dis.readUnsignedByte();
            switch (tag) {
                case CONSTANT_UTF_8 -> {
                    int length = dis.readUnsignedShort();
                    byte[] bytes = new byte[length];
                    dis.readFully(bytes);
                    utf8Entries[i] = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
                case CONSTANT_CLASS -> {
                    int nameIndex = dis.readUnsignedShort();
                    classNameIndices.add(nameIndex);
                }
                case CONSTANT_STRING, CONSTANT_METHOD_TYPE, CONSTANT_MODULE, CONSTANT_PACKAGE ->
                    dis.readUnsignedShort();
                case CONSTANT_INTEGER, CONSTANT_FLOAT -> dis.readInt();
                case CONSTANT_LONG, CONSTANT_DOUBLE -> {
                    dis.readLong();
                    i++; // longs and doubles occupy two constant pool slots
                }
                case CONSTANT_FIELDREF,
                        CONSTANT_METHODREF,
                        CONSTANT_INTERFACE_METHODREF,
                        CONSTANT_NAME_AND_TYPE,
                        CONSTANT_DYNAMIC,
                        CONSTANT_INVOKE_DYNAMIC -> dis.readInt();
                case CONSTANT_METHOD_HANDLE -> {
                    dis.readUnsignedByte(); // reference_kind
                    dis.readUnsignedShort(); // reference_index
                }
                default -> throw new IOException("Unknown constant pool tag: " + tag + " at index " + i);
            }
            i++;
        }

        // Resolve class names from CONSTANT_Class entries
        Set<String> classes = new HashSet<>();
        for (int idx : classNameIndices) {
            String internalName = utf8Entries[idx];
            if (internalName == null || internalName.startsWith("[")) {
                continue; // skip array descriptors
            }
            // Convert internal name (com/example/Foo) to binary name (com.example.Foo)
            classes.add(internalName.replace('/', '.'));
        }

        // Also extract class references from descriptors in UTF8 entries.
        // This catches annotation types, field types, and method parameter/return types
        // which are encoded as descriptors (e.g. Lcom/example/Foo;) rather than
        // CONSTANT_Class entries.
        for (String utf8 : utf8Entries) {
            if (utf8 != null) {
                extractDescriptorClasses(utf8, classes);
            }
        }

        return classes;
    }

    /**
     * Extract class names embedded in type descriptor strings (e.g. {@code Lcom/example/Foo;})
     * and add them to the result set as dot-separated names.
     */
    private static void extractDescriptorClasses(String utf8, Set<String> classes) {
        Matcher matcher = DESCRIPTOR_CLASS_PATTERN.matcher(utf8);
        while (matcher.find()) {
            String internalName = matcher.group(1);
            classes.add(internalName.replace('/', '.'));
        }
    }
}
