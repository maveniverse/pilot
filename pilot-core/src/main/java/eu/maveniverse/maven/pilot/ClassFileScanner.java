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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
            return null;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            addDescriptorRefs(descriptor);
            return null;
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
                    return null;
                }

                @Override
                public void visitLdcInsn(Object value) {
                    if (value instanceof Type t) {
                        addClassRef(t.getInternalName());
                    }
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
