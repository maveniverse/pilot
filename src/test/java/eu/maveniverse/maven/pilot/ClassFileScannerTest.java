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
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassFileScannerTest {

    @Test
    void scanOwnClassFile() throws Exception {
        // Scan this test class's own .class file from target/test-classes
        Path testClasses = Path.of("target/test-classes");
        Path thisClass = testClasses.resolve("eu/maveniverse/maven/pilot/ClassFileScannerTest.class");
        if (!Files.exists(thisClass)) {
            return; // skip if not compiled
        }

        Set<String> refs = ClassFileScanner.referencedClasses(thisClass);

        // Must reference standard JDK classes and project classes
        assertThat(refs)
                .contains("java.lang.Object")
                .contains("eu.maveniverse.maven.pilot.ClassFileScanner")
                .contains("java.nio.file.Path");
    }

    @Test
    void scanDirectoryFindsClasses() throws Exception {
        Path testClasses = Path.of("target/test-classes");
        if (!Files.isDirectory(testClasses)) {
            return;
        }

        Set<String> refs = ClassFileScanner.scanDirectory(testClasses);

        // Should find references from all test classes
        assertThat(refs).isNotEmpty().contains("java.lang.Object");
    }

    @Test
    void scanEmptyDirectory(@TempDir Path tempDir) throws Exception {
        Set<String> refs = ClassFileScanner.scanDirectory(tempDir);
        assertThat(refs).isEmpty();
    }

    @Test
    void referencedClassesFiltersArrayDescriptors() throws Exception {
        Path mainClasses = Path.of("target/classes");
        if (!Files.isDirectory(mainClasses)) {
            return;
        }

        Set<String> refs = ClassFileScanner.scanDirectory(mainClasses);

        // No class name should start with '[' (array descriptors should be filtered)
        assertThat(refs)
                .noneMatch(name -> name.startsWith("["))
                // No class name should contain '/' (should be dot-separated)
                .noneMatch(name -> name.contains("/"));
    }
}
