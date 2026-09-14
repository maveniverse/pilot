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
package eu.maveniverse.maven.pilot.mvn3;

import static org.assertj.core.api.Assertions.assertThat;

import eu.maveniverse.maven.pilot.ConflictsTui;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.DefaultDependencyNode;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.util.graph.manager.DependencyManagerUtils;
import org.junit.jupiter.api.Test;

class ConflictsMojoTest {

    // --- collectConflicts: dependency-management version override detection ---

    /**
     * When ClassicDependencyManager overrides a version, it sets the MANAGED_VERSION bit and
     * writes the pre-management version into NODE_DATA_PREMANAGED_VERSION. The old code used
     * the non-existent "conflict.originalVersion" key, which was always null. This test
     * verifies the fix uses DependencyManagerUtils.getPremanagedVersion() correctly.
     */
    @Test
    void collectConflictsDetectsVersionsOverriddenByDependencyManagement() {
        var mojo = new ConflictsMojo();
        var root = rootNode("com.example", "app", "1.0.0");
        var managed = depNode("org.jline", "jline", "4.4.3", "compile");

        Map<Object, Object> data = new HashMap<>();
        data.put(DependencyManagerUtils.NODE_DATA_PREMANAGED_VERSION, "3.25.1");
        managed.setData(data);
        managed.setManagedBits(DependencyNode.MANAGED_VERSION);

        root.setChildren(List.of(managed));

        Map<String, List<ConflictsTui.ConflictEntry>> conflicts = new HashMap<>();
        mojo.collectConflicts(root, conflicts, new ArrayList<>());

        assertThat(conflicts).containsKey("org.jline:jline");
        var entries = conflicts.get("org.jline:jline");
        assertThat(entries).hasSize(1);
        var entry = entries.get(0);
        assertThat(entry.resolvedVersion).isEqualTo("4.4.3");
        assertThat(entry.requestedVersion).isEqualTo("3.25.1");
    }

    /**
     * If the premanaged version equals the resolved version (i.e. depManagement pins a dep to
     * the same version it already declared), requestedVersion must equal resolvedVersion —
     * no false "conflict" should be introduced.
     */
    @Test
    void collectConflictsNoDifferenceWhenManagedVersionMatchesDeclared() {
        var mojo = new ConflictsMojo();

        var root = rootNode("com.example", "app", "1.0.0");
        var managed = depNode("org.slf4j", "slf4j-api", "2.0.9", "compile");

        Map<Object, Object> data = new HashMap<>();
        data.put(DependencyManagerUtils.NODE_DATA_PREMANAGED_VERSION, "2.0.9"); // same as resolved
        managed.setData(data);
        managed.setManagedBits(DependencyNode.MANAGED_VERSION);

        root.setChildren(List.of(managed));

        Map<String, List<ConflictsTui.ConflictEntry>> conflicts = new HashMap<>();
        mojo.collectConflicts(root, conflicts, new ArrayList<>());

        // Entry should exist (it appears in the dep graph), but requestedVersion == resolvedVersion
        assertThat(conflicts).containsKey("org.slf4j:slf4j-api");
        var entry = conflicts.get("org.slf4j:slf4j-api").get(0);
        assertThat(entry.requestedVersion).isEqualTo("2.0.9");
        assertThat(entry.resolvedVersion).isEqualTo("2.0.9");
    }

    /**
     * Nodes without any dependency-management data should report requestedVersion equal to
     * resolvedVersion — unmanaged entries must not be mistakenly flagged.
     */
    @Test
    void collectConflictsNoOverrideWhenNoPremanagedData() {
        var mojo = new ConflictsMojo();

        var root = rootNode("com.example", "app", "1.0.0");
        var plain = depNode("com.google.guava", "guava", "33.0.0-jre", "compile");
        root.setChildren(List.of(plain));

        Map<String, List<ConflictsTui.ConflictEntry>> conflicts = new HashMap<>();
        mojo.collectConflicts(root, conflicts, new ArrayList<>());

        assertThat(conflicts).containsKey("com.google.guava:guava");
        var entry = conflicts.get("com.google.guava:guava").get(0);
        assertThat(entry.requestedVersion).isEqualTo("33.0.0-jre");
        assertThat(entry.resolvedVersion).isEqualTo("33.0.0-jre");
    }

    // --- helpers ---

    private DefaultDependencyNode rootNode(String groupId, String artifactId, String version) {
        return new DefaultDependencyNode(new DefaultArtifact(groupId + ":" + artifactId + ":jar:" + version));
    }

    private DefaultDependencyNode depNode(String groupId, String artifactId, String version, String scope) {
        var artifact = new DefaultArtifact(groupId + ":" + artifactId + ":jar:" + version);
        return new DefaultDependencyNode(new Dependency(artifact, scope));
    }
}
