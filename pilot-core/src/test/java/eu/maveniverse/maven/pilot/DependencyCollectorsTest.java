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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.DefaultDependencyNode;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.junit.jupiter.api.Test;

class DependencyCollectorsTest {

    @Test
    void collectTransitiveFindsUndeclared() {
        DependencyNode root = nodeWithArtifact("g", "root", "1.0");
        DependencyNode declared = nodeWithDependency("g", "declared", "1.0", "compile");
        DependencyNode transitive = nodeWithDependency("g", "transitive", "1.0", "compile");
        declared.getChildren().add(transitive);
        root.getChildren().add(declared);

        List<AnalyzeTui.DepEntry> result = DependencyCollectors.collectTransitive(root, Set.of("g:declared"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).artifactId).isEqualTo("transitive");
        assertThat(result.get(0).pulledBy).isEqualTo("g:declared");
    }

    @Test
    void collectTransitiveExcludesDeclared() {
        DependencyNode root = nodeWithArtifact("g", "root", "1.0");
        DependencyNode child = nodeWithDependency("g", "child", "1.0", "compile");
        root.getChildren().add(child);

        List<AnalyzeTui.DepEntry> result = DependencyCollectors.collectTransitive(root, Set.of("g:child"));

        assertThat(result).isEmpty();
    }

    @Test
    void collectTransitiveDeduplicates() {
        DependencyNode root = nodeWithArtifact("g", "root", "1.0");
        DependencyNode a = nodeWithDependency("g", "a", "1.0", "compile");
        DependencyNode b = nodeWithDependency("g", "b", "1.0", "compile");
        // Both a and b pull in the same transitive dep
        DependencyNode t1 = nodeWithDependency("g", "shared", "1.0", "compile");
        DependencyNode t2 = nodeWithDependency("g", "shared", "1.0", "compile");
        a.getChildren().add(t1);
        b.getChildren().add(t2);
        root.getChildren().add(a);
        root.getChildren().add(b);

        List<AnalyzeTui.DepEntry> result = DependencyCollectors.collectTransitive(root, Set.of("g:a", "g:b"));

        assertThat(result).hasSize(1);
    }

    @Test
    void collectConflictsDetectsVersionMismatch() {
        DependencyNode root = nodeWithArtifact("g", "root", "1.0");
        DependencyNode a = nodeWithDependency("g", "lib", "2.0", "compile");
        DependencyNode b = nodeWithDependency("g", "other", "1.0", "compile");
        DependencyNode conflicting = nodeWithDependency("g", "lib", "1.0", "compile");
        b.getChildren().add(conflicting);
        root.getChildren().add(a);
        root.getChildren().add(b);

        List<ConflictsTui.ConflictGroup> conflicts = DependencyCollectors.collectConflicts(root);
        // Both versions of g:lib are collected; they form a group with 2 entries
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).ga).isEqualTo("g:lib");
        assertThat(conflicts.get(0).entries).hasSize(2);
    }

    @Test
    void collectConflictsReturnsEmptyForNoConflicts() {
        DependencyNode root = nodeWithArtifact("g", "root", "1.0");
        DependencyNode a = nodeWithDependency("g", "a", "1.0", "compile");
        DependencyNode b = nodeWithDependency("g", "b", "1.0", "compile");
        root.getChildren().add(a);
        root.getChildren().add(b);

        List<ConflictsTui.ConflictGroup> conflicts = DependencyCollectors.collectConflicts(root);
        assertThat(conflicts).isEmpty();
    }

    @Test
    void collectAuditEntriesSkipsRoot() {
        DependencyNode root = nodeWithArtifact("g", "root", "1.0");
        DependencyNode child = nodeWithDependency("g", "child", "1.0", "compile");
        root.getChildren().add(child);

        List<AuditTui.AuditEntry> entries = DependencyCollectors.collectAuditEntries(root);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).artifactId).isEqualTo("child");
    }

    @Test
    void collectAuditEntriesDeduplicatesByGa() {
        DependencyNode root = nodeWithArtifact("g", "root", "1.0");
        DependencyNode a = nodeWithDependency("g", "a", "1.0", "compile");
        DependencyNode b = nodeWithDependency("g", "b", "1.0", "compile");
        DependencyNode aDup = nodeWithDependency("g", "a", "2.0", "compile");
        b.getChildren().add(aDup);
        root.getChildren().add(a);
        root.getChildren().add(b);

        List<AuditTui.AuditEntry> entries = DependencyCollectors.collectAuditEntries(root);
        assertThat(entries).hasSize(2); // a and b, not a duplicate
        assertThat(entries).extracting(e -> e.artifactId).containsExactly("a", "b");
    }

    @Test
    void collectAuditEntriesCollectsTransitive() {
        DependencyNode root = nodeWithArtifact("g", "root", "1.0");
        DependencyNode child = nodeWithDependency("g", "child", "1.0", "compile");
        DependencyNode grandchild = nodeWithDependency("g", "grandchild", "1.0", "runtime");
        child.getChildren().add(grandchild);
        root.getChildren().add(child);

        List<AuditTui.AuditEntry> entries = DependencyCollectors.collectAuditEntries(root);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(1).scope).isEqualTo("runtime");
    }

    private DependencyNode nodeWithArtifact(String groupId, String artifactId, String version) {
        var artifact = new DefaultArtifact(groupId, artifactId, "jar", version);
        var node = new DefaultDependencyNode(artifact);
        node.setChildren(new ArrayList<>());
        return node;
    }

    private DependencyNode nodeWithDependency(String groupId, String artifactId, String version, String scope) {
        var artifact = new DefaultArtifact(groupId, artifactId, "jar", version);
        var dep = new Dependency(artifact, scope);
        var node = new DefaultDependencyNode(dep);
        node.setChildren(new ArrayList<>());
        return node;
    }
}
