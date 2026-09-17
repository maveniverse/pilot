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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Bytecode scanner using ASM that extracts referenced classes and member-level references
 * (methods and fields) from {@code .class} files.
 */
public final class ClassFileScanner {

    public record ScanResult(Set<String> referencedClasses, Map<String, Set<String>> memberReferences) {

        /**
         * Merge another ScanResult into this one, returning a new combined result.
         */
        ScanResult merge(ScanResult other) {
            Set<String> classes = new HashSet<>(referencedClasses);
            classes.addAll(other.referencedClasses);
            Map<String, Set<String>> members = new HashMap<>(memberReferences);
            for (var entry : other.memberReferences.entrySet()) {
                members.computeIfAbsent(entry.getKey(), k -> new HashSet<>()).addAll(entry.getValue());
            }
            return new ScanResult(classes, members);
        }
    }

    private ClassFileScanner() {}

    /**
     * Scan all {@code .class} files under a directory tree and return class and member references.
     *
     * @param classesDir root directory to scan (e.g. {@code target/classes})
     * @return scan result with referenced classes and member-level references
     * @throws IOException if the directory cannot be walked
     */
    public static ScanResult scanDirectory(Path classesDir) throws IOException {
        ScanResult result = new ScanResult(new HashSet<>(), new HashMap<>());
        try (Stream<Path> walk = Files.walk(classesDir)) {
            walk.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                try {
                    ScanResult fileResult = scanFile(p);
                    result.referencedClasses.addAll(fileResult.referencedClasses);
                    for (var entry : fileResult.memberReferences.entrySet()) {
                        result.memberReferences
                                .computeIfAbsent(entry.getKey(), k -> new HashSet<>())
                                .addAll(entry.getValue());
                    }
                } catch (IOException | IllegalArgumentException ignored) {
                    // skip corrupt, unreadable, or unsupported class file versions
                }
            });
        }
        return result;
    }

    private static ScanResult scanFile(Path classFile) throws IOException {
        try (InputStream is = Files.newInputStream(classFile)) {
            ClassReader reader = new ClassReader(is);
            ReferenceCollector collector = new ReferenceCollector();
            reader.accept(collector, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return new ScanResult(collector.referencedClasses, collector.memberReferences);
        }
    }

    private static class ReferenceCollector extends ClassVisitor {
        final Set<String> referencedClasses = new HashSet<>();
        final Map<String, Set<String>> memberReferences = new HashMap<>();
        private String thisClass;

        ReferenceCollector() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(
                int version, int access, String name, String signature, String superName, String[] interfaces) {
            thisClass = toClassName(name);
            if (superName != null) {
                addClassRef(superName);
            }
            if (interfaces != null) {
                for (String iface : interfaces) {
                    addClassRef(iface);
                }
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            addDescriptorRefs(descriptor);
            return annotationScanner();
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            addDescriptorRefs(descriptor);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    addDescriptorRefs(descriptor);
                    return annotationScanner();
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            addDescriptorRefs(descriptor);
            if (exceptions != null) {
                for (String ex : exceptions) {
                    addClassRef(ex);
                }
            }
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(
                        int opcode, String owner, String mName, String mDescriptor, boolean isInterface) {
                    String className = toClassName(owner);
                    addClassRef(owner);
                    if (!className.equals(thisClass)) {
                        String member = mName + formatDescriptor(mDescriptor);
                        memberReferences
                                .computeIfAbsent(className, k -> new HashSet<>())
                                .add(member);
                    }
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String fName, String descriptor) {
                    String className = toClassName(owner);
                    addClassRef(owner);
                    if (!className.equals(thisClass)) {
                        memberReferences
                                .computeIfAbsent(className, k -> new HashSet<>())
                                .add(fName);
                    }
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    addClassRef(type);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    addDescriptorRefs(descriptor);
                    return annotationScanner();
                }

                @Override
                public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
                    addDescriptorRefs(descriptor);
                    return annotationScanner();
                }

                @Override
                public void visitLdcInsn(Object value) {
                    if (value instanceof Type t) {
                        addClassRef(t.getInternalName());
                    }
                }
            };
        }

        /**
         * Returns an {@link AnnotationVisitor} that collects class references from annotation element
         * values: {@code Class} literals ({@link AnnotationVisitor#visit} with a {@link Type} value),
         * nested annotations ({@link AnnotationVisitor#visitAnnotation}), and enum constants
         * ({@link AnnotationVisitor#visitEnum}).
         */
        private AnnotationVisitor annotationScanner() {
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String name, Object value) {
                    if (value instanceof Type t) {
                        addClassRef(t.getInternalName());
                    }
                }

                @Override
                public void visitEnum(String name, String descriptor, String value) {
                    addDescriptorRefs(descriptor);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                    addDescriptorRefs(descriptor);
                    return this;
                }

                @Override
                public AnnotationVisitor visitArray(String name) {
                    return this;
                }
            };
        }

        private void addClassRef(String internalName) {
            if (internalName == null || internalName.startsWith("[")) return;
            referencedClasses.add(toClassName(internalName));
        }

        private void addDescriptorRefs(String descriptor) {
            if (descriptor == null) return;
            for (Type type : getTypesFromDescriptor(descriptor)) {
                if (type.getSort() == Type.OBJECT) {
                    referencedClasses.add(type.getClassName());
                } else if (type.getSort() == Type.ARRAY && type.getElementType().getSort() == Type.OBJECT) {
                    referencedClasses.add(type.getElementType().getClassName());
                }
            }
        }

        private static Type[] getTypesFromDescriptor(String descriptor) {
            try {
                if (descriptor.startsWith("(")) {
                    // Method descriptor
                    Type returnType = Type.getReturnType(descriptor);
                    Type[] argTypes = Type.getArgumentTypes(descriptor);
                    Type[] all = new Type[argTypes.length + 1];
                    System.arraycopy(argTypes, 0, all, 0, argTypes.length);
                    all[argTypes.length] = returnType;
                    return all;
                } else {
                    return new Type[] {Type.getType(descriptor)};
                }
            } catch (Exception e) {
                return new Type[0];
            }
        }

        private static String toClassName(String internalName) {
            return internalName.replace('/', '.');
        }
    }

    /**
     * Returns {@code true} if the given JAR contains at least one class with a
     * {@code public static final} field backed by a compile-time {@code ConstantValue}
     * attribute (i.e. a {@code String}, {@code int}, {@code long}, {@code float}, or
     * {@code double} literal).
     *
     * <p>The Java compiler inlines such constants at every call site: instead of emitting
     * {@code GETSTATIC com/example/Constants.FOO}, it emits {@code LDC "the-literal-value"}.
     * This means the declaring class leaves <em>no</em> bytecode trace in consuming modules,
     * and pilot's bytecode scanner would otherwise classify the dependency as {@code UNUSED}.
     * Detecting the presence of inlineable constants allows the analyzer to return
     * {@code UNDETERMINED} instead, avoiding false-positive removals.</p>
     *
     * @param jarFile the JAR to inspect
     * @return {@code true} if at least one inlineable constant field is found
     */
    @SuppressWarnings("java:S5042") // JARs are from Maven's local repository, already verified
    public static boolean hasInlineableConstants(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || name.equals("module-info.class") || name.startsWith("META-INF/")) {
                    continue;
                }
                try (InputStream is = jar.getInputStream(entry)) {
                    ClassReader reader = new ClassReader(is);
                    InlineableConstantDetector detector = new InlineableConstantDetector();
                    reader.accept(detector, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    if (detector.found) {
                        return true;
                    }
                } catch (IOException | IllegalArgumentException ignored) {
                    // skip corrupt or unsupported class files
                }
            }
        } catch (IOException ignored) {
            // skip unreadable JARs
        }
        return false;
    }

    /**
     * ASM visitor that stops as soon as it finds a {@code public static final} field
     * with a compile-time {@link ClassVisitor#visitField ConstantValue} attribute.
     * The {@code value} parameter of {@code visitField} is non-null exactly when the
     * field has a {@code ConstantValue} attribute in the class file.
     */
    private static final class InlineableConstantDetector extends ClassVisitor {
        boolean found = false;

        InlineableConstantDetector() {
            super(Opcodes.ASM9);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            // ConstantValue attribute is present iff value != null.
            // We only care about public static final fields (the ones javac inlines).
            boolean isPublicStaticFinal = (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))
                    == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL);
            if (isPublicStaticFinal && value != null) {
                found = true;
            }
            return null;
        }
    }

    /**
     * Format a method descriptor into a human-readable parameter list.
     * E.g. {@code (Ljava/lang/String;I)V} becomes {@code (String, int)}.
     */
    static String formatDescriptor(String descriptor) {
        try {
            Type[] argTypes = Type.getArgumentTypes(descriptor);
            if (argTypes.length == 0) return "()";
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < argTypes.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(simpleName(argTypes[i]));
            }
            sb.append(")");
            return sb.toString();
        } catch (Exception e) {
            return "()";
        }
    }

    private static String simpleName(Type type) {
        return switch (type.getSort()) {
            case Type.ARRAY -> simpleName(type.getElementType()) + "[]";
            case Type.OBJECT -> {
                String name = type.getClassName();
                int lastDot = name.lastIndexOf('.');
                yield lastDot >= 0 ? name.substring(lastDot + 1) : name;
            }
            default -> type.getClassName();
        };
    }
}
