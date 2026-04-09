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

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassFileScannerTest {

    @Test
    void scanDirectoryFindsClasses() throws Exception {
        Path testClasses = Path.of("target/test-classes");
        if (!Files.isDirectory(testClasses)) {
            return;
        }

        ClassFileScanner.ScanResult result = ClassFileScanner.scanDirectory(testClasses);

        // Should find references from all test classes
        assertThat(result.referencedClasses()).isNotEmpty().contains("java.lang.Object");
    }

    @Test
    void scanDirectoryFindsMemberReferences() throws Exception {
        Path testClasses = Path.of("target/test-classes");
        if (!Files.isDirectory(testClasses)) {
            return;
        }

        ClassFileScanner.ScanResult result = ClassFileScanner.scanDirectory(testClasses);

        // Should find member-level references (e.g. method calls on Path, assertThat, etc.)
        assertThat(result.memberReferences()).isNotEmpty();
        // This test class calls Path.of(), so we should see it
        assertThat(result.referencedClasses()).contains("java.nio.file.Path");
    }

    @Test
    void scanEmptyDirectory(@TempDir Path tempDir) throws Exception {
        ClassFileScanner.ScanResult result = ClassFileScanner.scanDirectory(tempDir);
        assertThat(result.referencedClasses()).isEmpty();
        assertThat(result.memberReferences()).isEmpty();
    }

    @Test
    void referencedClassesFiltersArrayDescriptors() throws Exception {
        Path mainClasses = Path.of("target/classes");
        if (!Files.isDirectory(mainClasses)) {
            return;
        }

        ClassFileScanner.ScanResult result = ClassFileScanner.scanDirectory(mainClasses);

        // No class name should start with '[' (array descriptors should be filtered)
        assertThat(result.referencedClasses())
                .noneMatch(name -> name.startsWith("["))
                // No class name should contain '/' (should be dot-separated)
                .noneMatch(name -> name.contains("/"));
    }

    @Test
    void formatDescriptorHumanReadable() {
        assertThat(ClassFileScanner.formatDescriptor("()V")).isEqualTo("()");
        assertThat(ClassFileScanner.formatDescriptor("(Ljava/lang/String;)V")).isEqualTo("(String)");
        assertThat(ClassFileScanner.formatDescriptor("(Ljava/lang/String;I)Z")).isEqualTo("(String, int)");
        assertThat(ClassFileScanner.formatDescriptor("([Ljava/lang/Object;)V")).isEqualTo("(Object[])");
    }
}
